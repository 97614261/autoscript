use std::collections::{BTreeMap, VecDeque};
use std::time::{Duration, Instant};

use runtime_scheduler::{MonoTime, TaskToken};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TransactionId(pub u64);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputCommand {
    Tap {
        x: i32,
        y: i32,
    },
    Swipe {
        from_x: i32,
        from_y: i32,
        to_x: i32,
        to_y: i32,
        duration_ms: u32,
    },
    KeyEvent {
        key_code: u32,
    },
    PointerDown {
        pointer_id: u8,
        x: i32,
        y: i32,
    },
    PointerMove {
        pointer_id: u8,
        x: i32,
        y: i32,
    },
    PointerUp {
        pointer_id: u8,
    },
    Delay {
        milliseconds: u32,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputTransaction {
    pub id: TransactionId,
    pub task: TaskToken,
    pub expires_at: MonoTime,
    pub commands: Vec<InputCommand>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InputArbiterConfig {
    pub max_queued: usize,
    pub max_per_task: usize,
    pub max_commands_per_transaction: usize,
    /// Maximum time a task may retain the single physical pointer without refreshing its lease.
    pub pointer_lease_timeout: Duration,
}

impl Default for InputArbiterConfig {
    fn default() -> Self {
        Self {
            max_queued: 128,
            max_per_task: 16,
            max_commands_per_transaction: 1_024,
            pointer_lease_timeout: Duration::from_secs(30),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputError {
    Stopped,
    QueueFull,
    TaskQueueFull(TaskToken),
    EmptyTransaction,
    TooManyCommands,
    DelayTooLong,
    InvalidPointerSequence,
    PointerOwnedByAnotherTask,
    TransactionAlreadyActive,
    NoActiveTransaction,
    TransactionMismatch,
    CommandMismatch,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InputDecision {
    Dispatch(InputTransaction),
    Expired(TransactionId),
    Idle,
    Stopped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StopPlan {
    pub discarded: Vec<TransactionId>,
    pub release_pointer_ids: Vec<u8>,
}

#[derive(Debug)]
pub struct InputArbiter {
    config: InputArbiterConfig,
    accepting: bool,
    queued: usize,
    by_task: BTreeMap<TaskToken, VecDeque<InputTransaction>>,
    round_robin: VecDeque<TaskToken>,
    active: Option<InputTransaction>,
    dispatched_index: usize,
    pressed_pointers: BTreeMap<u8, PointerLease>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PointerLease {
    task: TaskToken,
    expires_at: Instant,
}

impl InputArbiter {
    #[must_use]
    pub fn new(mut config: InputArbiterConfig) -> Self {
        if config.pointer_lease_timeout.is_zero() {
            config.pointer_lease_timeout = Duration::from_millis(1);
        } else {
            config.pointer_lease_timeout =
                config.pointer_lease_timeout.min(Duration::from_secs(60));
        }
        Self {
            config,
            accepting: true,
            queued: 0,
            by_task: BTreeMap::new(),
            round_robin: VecDeque::new(),
            active: None,
            dispatched_index: 0,
            pressed_pointers: BTreeMap::new(),
        }
    }

    /// Enqueues one indivisible transaction in the task-fair bounded queue.
    ///
    /// # Errors
    ///
    /// Returns an error after stop, on capacity limits or invalid pointer sequences.
    pub fn enqueue(&mut self, transaction: InputTransaction) -> Result<(), InputError> {
        if !self.accepting {
            return Err(InputError::Stopped);
        }
        if transaction.commands.is_empty() {
            return Err(InputError::EmptyTransaction);
        }
        if transaction.commands.len() > self.config.max_commands_per_transaction {
            return Err(InputError::TooManyCommands);
        }
        validate_pointer_sequence(
            &transaction.commands,
            transaction.task,
            &self.pressed_pointers,
        )?;
        if self.queued >= self.config.max_queued {
            return Err(InputError::QueueFull);
        }
        let queue = self.by_task.entry(transaction.task).or_default();
        if queue.len() >= self.config.max_per_task {
            return Err(InputError::TaskQueueFull(transaction.task));
        }
        if queue.is_empty() {
            self.round_robin.push_back(transaction.task);
        }
        queue.push_back(transaction);
        self.queued += 1;
        Ok(())
    }

    /// Selects one whole transaction. Tasks rotate after every selection to prevent starvation.
    ///
    /// # Errors
    ///
    /// Returns an error if a previous transaction is still active.
    pub fn next(&mut self, now: MonoTime) -> Result<InputDecision, InputError> {
        if self.active.is_some() {
            return Err(InputError::TransactionAlreadyActive);
        }
        if !self.accepting {
            return Ok(InputDecision::Stopped);
        }
        loop {
            let Some(task) = self.round_robin.pop_front() else {
                return Ok(InputDecision::Idle);
            };
            let Some(queue) = self.by_task.get_mut(&task) else {
                continue;
            };
            let Some(transaction) = queue.pop_front() else {
                self.by_task.remove(&task);
                continue;
            };
            self.queued = self.queued.saturating_sub(1);
            if queue.is_empty() {
                self.by_task.remove(&task);
            } else {
                self.round_robin.push_back(task);
            }
            if transaction.expires_at <= now {
                return Ok(InputDecision::Expired(transaction.id));
            }
            self.dispatched_index = 0;
            self.active = Some(transaction.clone());
            return Ok(InputDecision::Dispatch(transaction));
        }
    }

    /// Records one command after the backend accepted it, so stop can release live pointers.
    ///
    /// # Errors
    ///
    /// Returns an error for stale transaction IDs or out-of-order command acknowledgements.
    pub fn note_dispatched(
        &mut self,
        transaction: TransactionId,
        command: &InputCommand,
    ) -> Result<(), InputError> {
        let active = self
            .active
            .as_ref()
            .ok_or(InputError::NoActiveTransaction)?;
        if active.id != transaction {
            return Err(InputError::TransactionMismatch);
        }
        if active.commands.get(self.dispatched_index) != Some(command) {
            return Err(InputError::CommandMismatch);
        }
        match command {
            InputCommand::PointerDown { pointer_id, .. } => {
                self.pressed_pointers.insert(
                    *pointer_id,
                    PointerLease {
                        task: active.task,
                        expires_at: Instant::now() + self.config.pointer_lease_timeout,
                    },
                );
            }
            InputCommand::PointerUp { pointer_id } => {
                self.pressed_pointers.remove(pointer_id);
            }
            InputCommand::PointerMove { pointer_id, .. } => {
                if let Some(lease) = self.pressed_pointers.get_mut(pointer_id) {
                    lease.expires_at = Instant::now() + self.config.pointer_lease_timeout;
                }
            }
            InputCommand::Tap { .. }
            | InputCommand::Swipe { .. }
            | InputCommand::KeyEvent { .. }
            | InputCommand::Delay { .. } => {}
        }
        self.dispatched_index += 1;
        Ok(())
    }

    /// Completes the active transaction after every command was acknowledged.
    ///
    /// # Errors
    ///
    /// Returns an error for stale IDs or incomplete transactions.
    pub fn complete(&mut self, transaction: TransactionId) -> Result<(), InputError> {
        let active = self
            .active
            .as_ref()
            .ok_or(InputError::NoActiveTransaction)?;
        if active.id != transaction {
            return Err(InputError::TransactionMismatch);
        }
        if self.dispatched_index != active.commands.len() {
            return Err(InputError::CommandMismatch);
        }
        self.active = None;
        self.dispatched_index = 0;
        Ok(())
    }

    /// Removes one queued or active transaction and returns the pointer cleanup it requires.
    #[must_use]
    pub fn cancel(&mut self, transaction: TransactionId) -> Option<StopPlan> {
        if self
            .active
            .as_ref()
            .is_some_and(|active| active.id == transaction)
        {
            let task = self.active.as_ref().map(|active| active.task);
            self.active = None;
            self.dispatched_index = 0;
            let release_pointer_ids = self
                .pressed_pointers
                .iter()
                .filter_map(|(pointer_id, lease)| (Some(lease.task) == task).then_some(*pointer_id))
                .collect::<Vec<_>>();
            for pointer_id in &release_pointer_ids {
                self.pressed_pointers.remove(pointer_id);
            }
            return Some(StopPlan {
                discarded: vec![transaction],
                release_pointer_ids,
            });
        }

        let queued = self.by_task.iter().find_map(|(task, queue)| {
            queue
                .iter()
                .position(|candidate| candidate.id == transaction)
                .map(|position| (*task, position))
        });
        let Some((task, position)) = queued else {
            return None;
        };
        let empty = {
            let queue = self.by_task.get_mut(&task).expect("task queue exists");
            let _ = queue.remove(position);
            queue.is_empty()
        };
        self.queued = self.queued.saturating_sub(1);
        if empty {
            self.by_task.remove(&task);
            self.round_robin.retain(|queued_task| *queued_task != task);
        }
        Some(StopPlan {
            discarded: vec![transaction],
            release_pointer_ids: Vec::new(),
        })
    }

    /// Rejects new input, discards queued work and returns the exact pointer cleanup plan.
    #[must_use]
    pub fn stop(&mut self) -> StopPlan {
        self.accepting = false;
        let mut discarded = self
            .by_task
            .values()
            .flat_map(|queue| queue.iter().map(|transaction| transaction.id))
            .collect::<Vec<_>>();
        if let Some(active) = self.active.take() {
            discarded.push(active.id);
        }
        discarded.sort();
        let release_pointer_ids = self.pressed_pointers.keys().copied().collect();
        self.by_task.clear();
        self.round_robin.clear();
        self.pressed_pointers.clear();
        self.queued = 0;
        self.dispatched_index = 0;
        StopPlan {
            discarded,
            release_pointer_ids,
        }
    }

    /// Releases every pointer lease held by one finished/cancelled task.
    pub fn release_task_pointers(&mut self, task: TaskToken) -> Vec<u8> {
        let pointers = self
            .pressed_pointers
            .iter()
            .filter_map(|(pointer, lease)| (lease.task == task).then_some(*pointer))
            .collect::<Vec<_>>();
        for pointer in &pointers {
            self.pressed_pointers.remove(pointer);
        }
        pointers
    }

    /// Releases leases whose bounded wall-clock timeout elapsed.
    pub fn expire_pointers(&mut self, now: Instant) -> Vec<u8> {
        let pointers = self
            .pressed_pointers
            .iter()
            .filter_map(|(pointer, lease)| (lease.expires_at <= now).then_some(*pointer))
            .collect::<Vec<_>>();
        for pointer in &pointers {
            self.pressed_pointers.remove(pointer);
        }
        pointers
    }

    #[must_use]
    pub fn next_pointer_deadline(&self) -> Option<Instant> {
        self.pressed_pointers
            .values()
            .map(|lease| lease.expires_at)
            .min()
    }
}

fn validate_pointer_sequence(
    commands: &[InputCommand],
    task: TaskToken,
    active: &BTreeMap<u8, PointerLease>,
) -> Result<(), InputError> {
    let now = Instant::now();
    let mut down = active
        .iter()
        .filter_map(|(pointer, lease)| (lease.expires_at > now).then_some((*pointer, lease.task)))
        .collect::<BTreeMap<_, _>>();
    for command in commands {
        match command {
            InputCommand::Delay { milliseconds } if *milliseconds > 60_000 => {
                return Err(InputError::DelayTooLong);
            }
            InputCommand::PointerDown { pointer_id, .. } => match down.get(pointer_id) {
                None => {
                    down.insert(*pointer_id, task);
                }
                Some(owner) if *owner == task => {
                    return Err(InputError::InvalidPointerSequence);
                }
                Some(_) => return Err(InputError::PointerOwnedByAnotherTask),
            },
            InputCommand::PointerMove { pointer_id, .. } => match down.get(pointer_id) {
                Some(owner) if *owner == task => {}
                Some(_) => return Err(InputError::PointerOwnedByAnotherTask),
                None => return Err(InputError::InvalidPointerSequence),
            },
            InputCommand::PointerUp { pointer_id } => match down.get(pointer_id) {
                Some(owner) if *owner == task => {
                    down.remove(pointer_id);
                }
                Some(_) => return Err(InputError::PointerOwnedByAnotherTask),
                None => return Err(InputError::InvalidPointerSequence),
            },
            _ => {}
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use runtime_scheduler::{MonoTime, TaskGeneration, TaskId, TaskToken};

    use super::{
        InputArbiter, InputArbiterConfig, InputCommand, InputDecision, InputError,
        InputTransaction, TransactionId,
    };

    fn task(id: u64) -> TaskToken {
        TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        }
    }

    fn transaction(id: u64, task: TaskToken) -> InputTransaction {
        InputTransaction {
            id: TransactionId(id),
            task,
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::Tap { x: 1, y: 2 }],
        }
    }

    #[test]
    fn task_round_robin_prevents_one_producer_from_starving_another() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        arbiter.enqueue(transaction(1, task(1))).expect("one");
        arbiter.enqueue(transaction(2, task(1))).expect("two");
        arbiter.enqueue(transaction(3, task(2))).expect("three");
        for expected in [TransactionId(1), TransactionId(3), TransactionId(2)] {
            let InputDecision::Dispatch(selected) =
                arbiter.next(MonoTime::ZERO).expect("selection")
            else {
                panic!("transaction expected");
            };
            assert_eq!(selected.id, expected);
            arbiter
                .note_dispatched(selected.id, &selected.commands[0])
                .expect("command");
            arbiter.complete(selected.id).expect("complete");
        }
    }

    #[test]
    fn stop_discards_queue_and_releases_pointer_pressed_mid_transaction() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        let multi = InputTransaction {
            id: TransactionId(7),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![
                InputCommand::PointerDown {
                    pointer_id: 4,
                    x: 1,
                    y: 2,
                },
                InputCommand::PointerMove {
                    pointer_id: 4,
                    x: 2,
                    y: 3,
                },
                InputCommand::PointerUp { pointer_id: 4 },
            ],
        };
        arbiter.enqueue(multi).expect("multi");
        arbiter.enqueue(transaction(8, task(2))).expect("queued");
        let InputDecision::Dispatch(active) = arbiter.next(MonoTime::ZERO).expect("active") else {
            panic!("active transaction");
        };
        arbiter
            .note_dispatched(active.id, &active.commands[0])
            .expect("down");
        let plan = arbiter.stop();
        assert_eq!(plan.release_pointer_ids, [4]);
        assert_eq!(plan.discarded, [TransactionId(7), TransactionId(8)]);
        assert_eq!(
            arbiter.enqueue(transaction(9, task(1))),
            Err(InputError::Stopped)
        );
    }

    #[test]
    fn invalid_or_expired_transactions_fail_deterministically() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        let mut invalid = transaction(1, task(1));
        invalid.commands = vec![InputCommand::PointerUp { pointer_id: 1 }];
        assert_eq!(
            arbiter.enqueue(invalid),
            Err(InputError::InvalidPointerSequence)
        );
        arbiter.enqueue(transaction(2, task(1))).expect("valid");
        assert_eq!(
            arbiter.next(MonoTime::from_nanos(100)).expect("expiry"),
            InputDecision::Expired(TransactionId(2))
        );

        let mut delayed = transaction(3, task(1));
        delayed.commands = vec![InputCommand::Delay {
            milliseconds: 60_001,
        }];
        assert_eq!(arbiter.enqueue(delayed), Err(InputError::DelayTooLong));
    }

    #[test]
    fn cancel_removes_queued_work_and_releases_an_active_pointer() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig::default());
        let active = InputTransaction {
            id: TransactionId(1),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![
                InputCommand::PointerDown {
                    pointer_id: 0,
                    x: 4,
                    y: 5,
                },
                InputCommand::PointerUp { pointer_id: 0 },
            ],
        };
        arbiter.enqueue(active).expect("active");
        arbiter.enqueue(transaction(2, task(2))).expect("queued");
        let InputDecision::Dispatch(selected) = arbiter.next(MonoTime::ZERO).expect("next") else {
            panic!("transaction expected");
        };
        arbiter
            .note_dispatched(selected.id, &selected.commands[0])
            .expect("down");

        let plan = arbiter.cancel(TransactionId(1)).expect("active cancelled");
        assert_eq!(plan.release_pointer_ids, [0]);
        assert_eq!(plan.discarded, [TransactionId(1)]);
        assert_eq!(
            arbiter
                .cancel(TransactionId(2))
                .expect("queued cancelled")
                .discarded,
            [TransactionId(2)]
        );
        assert_eq!(arbiter.next(MonoTime::ZERO), Ok(InputDecision::Idle));
    }

    #[test]
    fn pointer_lease_is_task_owned_across_transactions_and_expires() {
        let mut arbiter = InputArbiter::new(InputArbiterConfig {
            pointer_lease_timeout: Duration::from_millis(20),
            ..InputArbiterConfig::default()
        });
        let down = InputTransaction {
            id: TransactionId(20),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::PointerDown {
                pointer_id: 0,
                x: 1,
                y: 2,
            }],
        };
        arbiter.enqueue(down).expect("down is valid");
        let InputDecision::Dispatch(down) = arbiter.next(MonoTime::ZERO).expect("dispatch") else {
            panic!("down transaction expected");
        };
        arbiter
            .note_dispatched(down.id, &down.commands[0])
            .expect("down accepted");
        arbiter
            .complete(down.id)
            .expect("down transaction completed");

        let foreign_move = InputTransaction {
            id: TransactionId(21),
            task: task(2),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::PointerMove {
                pointer_id: 0,
                x: 3,
                y: 4,
            }],
        };
        assert_eq!(
            arbiter.enqueue(foreign_move),
            Err(InputError::PointerOwnedByAnotherTask)
        );

        let same_task_move = InputTransaction {
            id: TransactionId(22),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::PointerMove {
                pointer_id: 0,
                x: 3,
                y: 4,
            }],
        };
        arbiter
            .enqueue(same_task_move)
            .expect("owner may move pointer");

        let expired = arbiter.expire_pointers(Instant::now() + Duration::from_secs(1));
        assert_eq!(expired, [0]);
        let late_up = InputTransaction {
            id: TransactionId(23),
            task: task(1),
            expires_at: MonoTime::from_nanos(100),
            commands: vec![InputCommand::PointerUp { pointer_id: 0 }],
        };
        assert_eq!(
            arbiter.enqueue(late_up),
            Err(InputError::InvalidPointerSequence)
        );
    }
}
