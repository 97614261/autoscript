use std::collections::BTreeSet;
use std::time::{Duration, Instant};

use automation_core::{
    AutomationBackend, BackendError, InputArbiter, InputArbiterConfig, InputCommand, InputDecision,
    InputError, InputTransaction, TransactionId,
};
use runtime_scheduler::{MonoTime, RequestId, TaskToken};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputRuntimeError {
    Expired,
    Cancelled,
    Interrupted,
    Arbitration(InputError),
    Backend(BackendError),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputControl {
    Continue,
    Cancel,
    Stop,
}

pub struct InputRuntime {
    config: InputArbiterConfig,
    arbiter: InputArbiter,
    pending_release: BTreeSet<u8>,
}

impl InputRuntime {
    #[must_use]
    pub fn new(config: InputArbiterConfig) -> Self {
        Self {
            config,
            arbiter: InputArbiter::new(config),
            pending_release: BTreeSet::new(),
        }
    }

    pub fn reset(&mut self) {
        self.arbiter = InputArbiter::new(self.config);
    }

    pub fn abandon(&mut self) {
        let _ = self.arbiter.stop();
        self.pending_release.clear();
    }

    #[cfg(test)]
    pub fn dispatch<B: AutomationBackend, F: FnMut() -> InputControl>(
        &mut self,
        request: RequestId,
        task: TaskToken,
        now: MonoTime,
        expires_at: MonoTime,
        commands: Vec<InputCommand>,
        backend: &mut B,
        control: F,
    ) -> Result<(), InputRuntimeError> {
        self.dispatch_with_wait(
            request,
            task,
            now,
            expires_at,
            commands,
            backend,
            control,
            |duration, control| wait_for_control(duration, control),
        )
    }

    pub fn dispatch_with_wait<
        B: AutomationBackend,
        F: FnMut() -> InputControl,
        W: FnMut(Duration, &mut F) -> InputControl,
    >(
        &mut self,
        request: RequestId,
        task: TaskToken,
        now: MonoTime,
        expires_at: MonoTime,
        commands: Vec<InputCommand>,
        backend: &mut B,
        mut control: F,
        mut wait: W,
    ) -> Result<(), InputRuntimeError> {
        self.flush_pending_releases(backend)?;
        self.expire_leases(Instant::now(), backend)?;
        let transaction = InputTransaction {
            id: TransactionId(request.get()),
            task,
            expires_at,
            commands: if backend.supports_pointer_input() {
                expand_pointer_swipes(commands)?
            } else {
                commands
            },
        };
        self.arbiter
            .enqueue(transaction)
            .map_err(InputRuntimeError::Arbitration)?;
        let selected = match self
            .arbiter
            .next(now)
            .map_err(InputRuntimeError::Arbitration)?
        {
            InputDecision::Dispatch(transaction) => transaction,
            InputDecision::Expired(_) => return Err(InputRuntimeError::Expired),
            InputDecision::Stopped => return Err(InputRuntimeError::Interrupted),
            InputDecision::Idle => {
                return Err(InputRuntimeError::Arbitration(
                    InputError::NoActiveTransaction,
                ));
            }
        };

        for command in &selected.commands {
            match control() {
                InputControl::Continue => {}
                InputControl::Cancel => {
                    self.abort(request, backend)?;
                    return Err(InputRuntimeError::Cancelled);
                }
                InputControl::Stop => {
                    self.stop(backend)?;
                    return Err(InputRuntimeError::Interrupted);
                }
            }
            match command {
                InputCommand::Delay { milliseconds } => {
                    match wait(
                        Duration::from_millis(u64::from(*milliseconds)),
                        &mut control,
                    ) {
                        InputControl::Continue => {}
                        InputControl::Cancel => {
                            self.abort(request, backend)?;
                            return Err(InputRuntimeError::Cancelled);
                        }
                        InputControl::Stop => {
                            self.stop(backend)?;
                            return Err(InputRuntimeError::Interrupted);
                        }
                    }
                }
                _ => {
                    if let Err(error) =
                        backend.dispatch_input(request, task, std::slice::from_ref(command))
                    {
                        self.abort(request, backend)?;
                        return Err(InputRuntimeError::Backend(error));
                    }
                }
            }
            self.arbiter
                .note_dispatched(selected.id, command)
                .map_err(InputRuntimeError::Arbitration)?;
            match control() {
                InputControl::Continue => {}
                InputControl::Cancel => {
                    self.abort(request, backend)?;
                    return Err(InputRuntimeError::Cancelled);
                }
                InputControl::Stop => {
                    self.stop(backend)?;
                    return Err(InputRuntimeError::Interrupted);
                }
            }
        }
        self.arbiter
            .complete(selected.id)
            .map_err(InputRuntimeError::Arbitration)
    }

    pub fn cancel<B: AutomationBackend>(
        &mut self,
        request: RequestId,
        backend: &mut B,
    ) -> Result<bool, InputRuntimeError> {
        backend.cancel_input(request);
        self.abort(request, backend)
    }

    pub fn stop<B: AutomationBackend>(&mut self, backend: &mut B) -> Result<(), InputRuntimeError> {
        let plan = self.arbiter.stop();
        self.release(plan.release_pointer_ids, backend)
    }

    pub fn release_task<B: AutomationBackend>(
        &mut self,
        task: TaskToken,
        backend: &mut B,
    ) -> Result<(), InputRuntimeError> {
        let pointers = self.arbiter.release_task_pointers(task);
        self.release(pointers, backend)
    }

    pub fn expire_leases<B: AutomationBackend>(
        &mut self,
        now: Instant,
        backend: &mut B,
    ) -> Result<(), InputRuntimeError> {
        let pointers = self.arbiter.expire_pointers(now);
        self.release(pointers, backend)
    }

    #[must_use]
    pub fn next_lease_timeout(&self) -> Option<Duration> {
        self.arbiter
            .next_pointer_deadline()
            .map(|deadline| deadline.saturating_duration_since(Instant::now()))
    }

    fn abort<B: AutomationBackend>(
        &mut self,
        request: RequestId,
        backend: &mut B,
    ) -> Result<bool, InputRuntimeError> {
        let Some(plan) = self.arbiter.cancel(TransactionId(request.get())) else {
            return Ok(false);
        };
        self.release(plan.release_pointer_ids, backend)?;
        Ok(true)
    }

    fn release<B: AutomationBackend>(
        &mut self,
        pointer_ids: Vec<u8>,
        backend: &mut B,
    ) -> Result<(), InputRuntimeError> {
        self.pending_release.extend(pointer_ids);
        self.flush_pending_releases(backend)
    }

    fn flush_pending_releases<B: AutomationBackend>(
        &mut self,
        backend: &mut B,
    ) -> Result<(), InputRuntimeError> {
        if self.pending_release.is_empty() {
            return Ok(());
        }
        let pointer_ids = self.pending_release.iter().copied().collect::<Vec<_>>();
        backend
            .release_pointers(&pointer_ids)
            .map_err(InputRuntimeError::Backend)?;
        self.pending_release.clear();
        Ok(())
    }
}

fn expand_pointer_swipes(
    commands: Vec<InputCommand>,
) -> Result<Vec<InputCommand>, InputRuntimeError> {
    let mut expanded = Vec::new();
    for command in commands {
        if let InputCommand::Swipe {
            from_x,
            from_y,
            to_x,
            to_y,
            duration_ms,
        } = command
        {
            if !(1..=60_000).contains(&duration_ms) {
                return Err(InputRuntimeError::Arbitration(InputError::DelayTooLong));
            }
            let steps = duration_ms.div_ceil(16).clamp(1, 200);
            expanded.push(InputCommand::PointerDown {
                pointer_id: 0,
                x: from_x,
                y: from_y,
            });
            let mut previous = 0;
            for step in 1..=steps {
                let elapsed = u64::from(duration_ms) * u64::from(step) / u64::from(steps);
                let delay = u32::try_from(elapsed).expect("bounded duration") - previous;
                if delay > 0 {
                    expanded.push(InputCommand::Delay {
                        milliseconds: delay,
                    });
                }
                previous = u32::try_from(elapsed).expect("bounded duration");
                let coordinate = |from: i32, to: i32| {
                    i32::try_from(
                        i64::from(from)
                            + (i64::from(to) - i64::from(from)) * i64::from(step)
                                / i64::from(steps),
                    )
                    .expect("interpolated i32 endpoints")
                };
                expanded.push(InputCommand::PointerMove {
                    pointer_id: 0,
                    x: coordinate(from_x, to_x),
                    y: coordinate(from_y, to_y),
                });
            }
            expanded.push(InputCommand::PointerUp { pointer_id: 0 });
        } else {
            expanded.push(command);
        }
        if expanded.len() > 1024 {
            return Err(InputRuntimeError::Arbitration(InputError::TooManyCommands));
        }
    }
    Ok(expanded)
}

#[cfg(test)]
fn wait_for_control<F: FnMut() -> InputControl>(
    duration: Duration,
    control: &mut F,
) -> InputControl {
    if duration.is_zero() {
        return control();
    }
    let Some(deadline) = Instant::now().checked_add(duration) else {
        return InputControl::Stop;
    };
    loop {
        let requested = control();
        if requested != InputControl::Continue {
            return requested;
        }
        let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
            return control();
        };
        std::thread::park_timeout(remaining.min(Duration::from_millis(10)));
    }
}

#[cfg(test)]
mod tests {
    use std::cell::Cell;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    use automation_core::{AutomationBackend, BackendError, InputArbiterConfig, InputCommand};
    use runtime_scheduler::{MonoTime, RequestId, TaskGeneration, TaskId, TaskToken};

    use super::{InputControl, InputRuntime, InputRuntimeError};

    #[derive(Default)]
    struct RecordingBackend {
        commands: Vec<InputCommand>,
        released: Vec<u8>,
        stop_after_first: bool,
        stop_requested: Arc<AtomicBool>,
        fail_release: bool,
        pointer_capable: bool,
    }

    impl AutomationBackend for RecordingBackend {
        fn supports_pointer_input(&self) -> bool {
            self.pointer_capable
        }
        fn dispatch_input(
            &mut self,
            _request: RequestId,
            _task: TaskToken,
            commands: &[InputCommand],
        ) -> Result<(), BackendError> {
            self.commands.extend_from_slice(commands);
            if self.stop_after_first && self.commands.len() == 1 {
                self.stop_requested
                    .store(true, std::sync::atomic::Ordering::Release);
            }
            Ok(())
        }

        fn cancel_input(&mut self, _request: RequestId) {}

        fn release_pointers(&mut self, pointer_ids: &[u8]) -> Result<(), BackendError> {
            if self.fail_release {
                return Err(BackendError {
                    code: "RELEASE_FAILED",
                    message: "expected test failure".to_owned(),
                    retryable: true,
                    connection_lost: false,
                });
            }
            self.released.extend_from_slice(pointer_ids);
            Ok(())
        }
    }

    fn task() -> TaskToken {
        TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        }
    }

    #[test]
    fn segmented_swipes_keep_one_touch_exact_endpoint_and_total_duration() {
        for duration in [1, 16, 50, 60_000] {
            let commands = super::expand_pointer_swipes(vec![InputCommand::Swipe {
                from_x: i32::MAX,
                from_y: 0,
                to_x: 0,
                to_y: i32::MAX,
                duration_ms: duration,
            }])
            .unwrap();
            assert!(commands.len() <= 402);
            assert!(matches!(
                commands.first(),
                Some(InputCommand::PointerDown { .. })
            ));
            assert!(matches!(
                commands.last(),
                Some(InputCommand::PointerUp { .. })
            ));
            assert_eq!(
                commands
                    .iter()
                    .filter(|command| matches!(command, InputCommand::PointerDown { .. }))
                    .count(),
                1
            );
            assert_eq!(
                commands
                    .iter()
                    .filter_map(
                        |command| if let InputCommand::Delay { milliseconds } = command {
                            Some(milliseconds)
                        } else {
                            None
                        }
                    )
                    .sum::<u32>(),
                duration
            );
            assert!(matches!(
                commands[commands.len() - 2],
                InputCommand::PointerMove {
                    x: 0,
                    y: i32::MAX,
                    ..
                }
            ));
        }
    }

    #[test]
    fn stopping_inside_a_segmented_swipe_releases_the_pointer() {
        let mut runtime = InputRuntime::new(InputArbiterConfig::default());
        let mut backend = RecordingBackend {
            pointer_capable: true,
            ..RecordingBackend::default()
        };
        let stop = Cell::new(false);
        let result = runtime.dispatch_with_wait(
            RequestId(1),
            task(),
            MonoTime::ZERO,
            MonoTime::from_nanos(60_000_000_000),
            vec![InputCommand::Swipe {
                from_x: 0,
                from_y: 0,
                to_x: 500,
                to_y: 500,
                duration_ms: 60_000,
            }],
            &mut backend,
            || {
                if stop.get() {
                    InputControl::Stop
                } else {
                    InputControl::Continue
                }
            },
            |_, control| {
                stop.set(true);
                control()
            },
        );
        assert_eq!(result, Err(InputRuntimeError::Interrupted));
        assert!(matches!(
            backend.commands[0],
            InputCommand::PointerDown { .. }
        ));
        assert!(!backend.commands.iter().any(|command| matches!(
            command,
            InputCommand::Swipe { .. } | InputCommand::PointerMove { .. }
        )));
        assert_eq!(backend.released, vec![0]);
    }

    #[test]
    fn dispatches_a_transaction_in_order() {
        let mut runtime = InputRuntime::new(InputArbiterConfig::default());
        let mut backend = RecordingBackend::default();
        runtime
            .dispatch(
                RequestId(7),
                task(),
                MonoTime::ZERO,
                MonoTime::from_nanos(10),
                vec![
                    InputCommand::Tap { x: 1, y: 2 },
                    InputCommand::KeyEvent { key_code: 4 },
                ],
                &mut backend,
                || InputControl::Continue,
            )
            .expect("dispatch");
        assert_eq!(backend.commands.len(), 2);
        assert!(backend.released.is_empty());
    }

    #[test]
    fn stop_between_commands_releases_the_live_pointer() {
        let mut runtime = InputRuntime::new(InputArbiterConfig::default());
        let stop = Arc::new(AtomicBool::new(false));
        let mut backend = RecordingBackend {
            stop_after_first: true,
            stop_requested: Arc::clone(&stop),
            ..RecordingBackend::default()
        };
        let result = runtime.dispatch(
            RequestId(8),
            task(),
            MonoTime::ZERO,
            MonoTime::from_nanos(10),
            vec![
                InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 1,
                    y: 2,
                },
                InputCommand::PointerUp { pointer_id: 0 },
            ],
            &mut backend,
            || {
                if stop.load(Ordering::Acquire) {
                    InputControl::Stop
                } else {
                    InputControl::Continue
                }
            },
        );
        assert_eq!(result, Err(InputRuntimeError::Interrupted));
        assert_eq!(backend.released, [0]);
    }

    #[test]
    fn cancellation_aborts_only_the_active_transaction() {
        let mut runtime = InputRuntime::new(InputArbiterConfig::default());
        let mut backend = RecordingBackend::default();
        let checks = Cell::new(0_u8);
        let result = runtime.dispatch(
            RequestId(11),
            task(),
            MonoTime::ZERO,
            MonoTime::from_nanos(10),
            vec![
                InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 1,
                    y: 2,
                },
                InputCommand::PointerUp { pointer_id: 0 },
            ],
            &mut backend,
            || {
                let current = checks.get();
                checks.set(current + 1);
                if current == 0 {
                    InputControl::Continue
                } else {
                    InputControl::Cancel
                }
            },
        );
        assert_eq!(result, Err(InputRuntimeError::Cancelled));
        assert_eq!(backend.released, [0]);

        runtime
            .dispatch(
                RequestId(12),
                task(),
                MonoTime::ZERO,
                MonoTime::from_nanos(10),
                vec![InputCommand::Tap { x: 3, y: 4 }],
                &mut backend,
                || InputControl::Continue,
            )
            .expect("arbiter remains usable after cancellation");
    }

    #[test]
    fn failed_release_is_retried_before_new_input() {
        let mut runtime = InputRuntime::new(InputArbiterConfig::default());
        let stop = Arc::new(AtomicBool::new(false));
        let mut backend = RecordingBackend {
            stop_after_first: true,
            stop_requested: Arc::clone(&stop),
            fail_release: true,
            ..RecordingBackend::default()
        };
        let result = runtime.dispatch(
            RequestId(9),
            task(),
            MonoTime::ZERO,
            MonoTime::from_nanos(10),
            vec![
                InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 1,
                    y: 2,
                },
                InputCommand::PointerUp { pointer_id: 0 },
            ],
            &mut backend,
            || {
                if stop.load(Ordering::Acquire) {
                    InputControl::Stop
                } else {
                    InputControl::Continue
                }
            },
        );
        assert!(matches!(result, Err(InputRuntimeError::Backend(_))));

        backend.fail_release = false;
        backend.stop_after_first = false;
        stop.store(false, Ordering::Release);
        runtime.reset();
        runtime
            .dispatch(
                RequestId(10),
                task(),
                MonoTime::ZERO,
                MonoTime::from_nanos(10),
                vec![InputCommand::Tap { x: 3, y: 4 }],
                &mut backend,
                || {
                    if stop.load(Ordering::Acquire) {
                        InputControl::Stop
                    } else {
                        InputControl::Continue
                    }
                },
            )
            .expect("release retry then dispatch");
        assert_eq!(backend.released, [0]);
    }

    #[test]
    fn task_completion_and_lease_timeout_release_a_cross_transaction_pointer() {
        let mut runtime = InputRuntime::new(InputArbiterConfig {
            pointer_lease_timeout: Duration::from_millis(1),
            ..InputArbiterConfig::default()
        });
        let mut backend = RecordingBackend::default();
        runtime
            .dispatch(
                RequestId(51),
                task(),
                MonoTime::ZERO,
                MonoTime::from_nanos(10),
                vec![InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 1,
                    y: 2,
                }],
                &mut backend,
                || InputControl::Continue,
            )
            .expect("pointer down dispatch");
        runtime
            .release_task(task(), &mut backend)
            .expect("task termination releases pointer");
        assert_eq!(backend.released, [0]);

        runtime
            .dispatch(
                RequestId(52),
                task(),
                MonoTime::ZERO,
                MonoTime::from_nanos(10),
                vec![InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 3,
                    y: 4,
                }],
                &mut backend,
                || InputControl::Continue,
            )
            .expect("pointer down dispatch after release");
        std::thread::sleep(Duration::from_millis(3));
        runtime
            .expire_leases(Instant::now(), &mut backend)
            .expect("lease watchdog releases pointer");
        assert_eq!(backend.released, [0, 0]);
    }
}
