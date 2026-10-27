//! A single deadline worker with a completion fence for one owned, unreaped child.

use root_daemon_core::priority::InputCancellation;
use std::io;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread::{self, JoinHandle, Thread};
use std::time::Instant;

struct Arm {
    pid: i32,
    deadline: Instant,
    cancellation: InputCancellation,
    gate: Mutex<Completion>,
}

#[derive(Default)]
struct Completion {
    finished: bool,
    fired: bool,
}

#[derive(Default)]
struct Shared {
    active: Mutex<Option<Arc<Arm>>>,
    shutdown: AtomicBool,
}

/// One persistent worker; the caller retains child ownership until its lease finishes.
pub struct CaptureWatchdog {
    shared: Arc<Shared>,
    thread: Thread,
    join: Option<JoinHandle<()>>,
}

/// Finishing synchronizes with an in-flight kill before the child may be reaped.
pub struct CaptureWatchdogLease {
    arm: Arc<Arm>,
    shared: Arc<Shared>,
    thread: Thread,
}

impl CaptureWatchdog {
    /// Starts a worker whose fixed callback must kill only the caller's owned child.
    ///
    /// # Errors
    /// Returns an error if the worker cannot be created.
    pub fn new(kill: impl Fn(i32) + Send + 'static) -> io::Result<Self> {
        Self::spawn(kill, || {})
    }

    fn spawn(
        kill: impl Fn(i32) + Send + 'static,
        before_loop: impl FnOnce() + Send + 'static,
    ) -> io::Result<Self> {
        let shared = Arc::new(Shared::default());
        let worker_shared = Arc::clone(&shared);
        let join = thread::Builder::new()
            .name("root-capture-deadline".into())
            .spawn(move || {
                before_loop();
                worker(&worker_shared, &kill);
            })?;
        Ok(Self {
            shared,
            thread: join.thread().clone(),
            join: Some(join),
        })
    }

    /// Arms one capture without depending on when the worker is scheduled.
    ///
    /// # Errors
    /// Rejects shutdown or an overlapping request whose lease has not finished.
    pub fn arm(
        &self,
        pid: i32,
        cancellation: InputCancellation,
        deadline: Instant,
    ) -> io::Result<CaptureWatchdogLease> {
        if self.shared.shutdown.load(Ordering::Acquire) {
            return Err(io::Error::other("capture watchdog stopped"));
        }
        let arm = Arc::new(Arm {
            pid,
            deadline,
            cancellation,
            gate: Mutex::new(Completion::default()),
        });
        let mut active = self
            .shared
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if active.is_some() {
            return Err(io::Error::other("capture watchdog busy"));
        }
        *active = Some(Arc::clone(&arm));
        drop(active);
        self.thread.unpark();
        Ok(CaptureWatchdogLease {
            arm,
            shared: Arc::clone(&self.shared),
            thread: self.thread.clone(),
        })
    }

    /// Finishes an abandoned staged capture before its bridge is killed or reaped.
    pub fn finish_active(&self) {
        let arm = self
            .shared
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone();
        if let Some(arm) = arm {
            finish_arm(&self.shared, &arm, &self.thread);
        }
    }
}

fn worker(shared: &Shared, kill: &impl Fn(i32)) {
    while !shared.shutdown.load(Ordering::Acquire) {
        let arm = shared
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone();
        let Some(arm) = arm else {
            thread::park();
            continue;
        };
        let mut completion = arm
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if completion.finished || completion.fired {
            drop(completion);
            // A coalesced unpark may belong to a newer arm. Check its identity
            // before parking, rather than losing that arm's only wakeup.
            let same = shared
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .as_ref()
                .is_some_and(|current| Arc::ptr_eq(current, &arm));
            if same {
                thread::park();
            }
            continue;
        }
        // A fired cancellation must park until lease completion. Re-registering
        // its already-cancelled token would continuously unpark this worker.
        arm.cancellation.register_watchdog(thread::current());
        if arm.cancellation.is_cancelled() || Instant::now() >= arm.deadline {
            // Completion and kill share this gate. finish() cannot return while
            // the callback is still using the unreaped child's PID.
            if !shared.shutdown.load(Ordering::Acquire) {
                kill(arm.pid);
                completion.fired = true;
            }
            continue;
        }
        drop(completion);
        thread::park_timeout(arm.deadline.saturating_duration_since(Instant::now()));
    }
}

impl CaptureWatchdogLease {
    /// Cancels this arm and waits for any in-flight kill callback to return.
    pub fn finish(&self) {
        finish_arm(&self.shared, &self.arm, &self.thread);
    }
}

fn finish_arm(shared: &Shared, arm: &Arc<Arm>, thread: &Thread) {
    arm.gate
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .finished = true;
    let mut active = shared
        .active
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if active
        .as_ref()
        .is_some_and(|current| Arc::ptr_eq(current, arm))
    {
        active.take();
    }
    drop(active);
    thread.unpark();
}

impl Drop for CaptureWatchdogLease {
    fn drop(&mut self) {
        self.finish();
    }
}

impl Drop for CaptureWatchdog {
    fn drop(&mut self) {
        self.shared.shutdown.store(true, Ordering::Release);
        let arm = self
            .shared
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        if let Some(arm) = arm {
            arm.gate
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .finished = true;
        }
        self.thread.unpark();
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;
    use std::time::Duration;

    #[test]
    fn completed_pending_arms_rearm_without_worker_scheduling_and_stale_leases_are_harmless() {
        let (release, blocked) = mpsc::channel();
        let (killed, events) = mpsc::channel();
        let watchdog = CaptureWatchdog::spawn(
            move |pid| killed.send(pid).unwrap(),
            move || blocked.recv().unwrap(),
        )
        .unwrap();
        let deadline = Instant::now() + Duration::from_mins(1);
        for _ in 0..20_000 {
            let first = watchdog
                .arm(1, InputCancellation::default(), deadline)
                .unwrap();
            first.finish();
            let second = watchdog
                .arm(2, InputCancellation::default(), deadline)
                .unwrap();
            let released = Arc::downgrade(&second.arm);
            drop(first);
            assert!(watchdog
                .arm(3, InputCancellation::default(), deadline)
                .is_err());
            second.finish();
            drop(second);
            assert!(released.upgrade().is_none());
            assert!(watchdog.shared.active.lock().unwrap().is_none());
        }
        release.send(()).unwrap();
        drop(watchdog);
        assert!(events.try_recv().is_err());
    }

    #[test]
    fn cancellation_and_deadline_fire_once_and_keep_overlap_blocked_until_finish() {
        let (killed, events) = mpsc::channel();
        let watchdog = CaptureWatchdog::new(move |pid| killed.send(pid).unwrap()).unwrap();
        for (pid, cancelled) in [(1, true), (2, false)] {
            let cancellation = InputCancellation::default();
            let deadline = Instant::now()
                + if cancelled {
                    Duration::from_secs(30)
                } else {
                    Duration::from_millis(10)
                };
            let lease = watchdog.arm(pid, cancellation.clone(), deadline).unwrap();
            if cancelled {
                cancellation.cancel();
            }
            assert_eq!(events.recv_timeout(Duration::from_secs(2)).unwrap(), pid);
            assert!(watchdog
                .arm(3, InputCancellation::default(), deadline)
                .is_err());
            assert!(events.recv_timeout(Duration::from_millis(30)).is_err());
            lease.finish();
        }
    }

    #[test]
    fn scheduled_worker_never_loses_a_new_arms_cancellation_wakeup() {
        let (killed, events) = mpsc::channel();
        let watchdog = CaptureWatchdog::new(move |pid| killed.send(pid).unwrap()).unwrap();
        for pid in 1..=1_000 {
            let cancellation = InputCancellation::default();
            let lease = watchdog
                .arm(
                    pid,
                    cancellation.clone(),
                    Instant::now() + Duration::from_secs(30),
                )
                .unwrap();
            cancellation.cancel();
            assert_eq!(events.recv_timeout(Duration::from_secs(2)).unwrap(), pid);
            lease.finish();
        }
    }

    #[test]
    fn completion_fences_inflight_kill_and_no_late_kill_follows_finish() {
        let (entered, entered_rx) = mpsc::channel();
        let (release, release_rx) = mpsc::channel();
        let watchdog = CaptureWatchdog::new(move |_| {
            entered.send(()).unwrap();
            release_rx.recv().unwrap();
        })
        .unwrap();
        let lease = watchdog
            .arm(1, InputCancellation::default(), Instant::now())
            .unwrap();
        entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        let (finished, finished_rx) = mpsc::channel();
        let completion_thread = thread::spawn(move || {
            lease.finish();
            finished.send(()).unwrap();
        });
        assert!(finished_rx.recv_timeout(Duration::from_millis(30)).is_err());
        release.send(()).unwrap();
        finished_rx.recv_timeout(Duration::from_secs(2)).unwrap();
        completion_thread.join().unwrap();
        assert!(entered_rx.try_recv().is_err());
    }

    #[test]
    fn shutdown_and_cancelled_completed_pending_arm_never_kill() {
        let (release, blocked) = mpsc::channel();
        let (killed, events) = mpsc::channel();
        let watchdog = CaptureWatchdog::spawn(
            move |pid| killed.send(pid).unwrap(),
            move || blocked.recv().unwrap(),
        )
        .unwrap();
        let cancellation = InputCancellation::default();
        cancellation.cancel();
        let lease = watchdog.arm(1, cancellation, Instant::now()).unwrap();
        watchdog.finish_active();
        lease.finish();
        let retained = watchdog
            .arm(
                2,
                InputCancellation::default(),
                Instant::now() + Duration::from_secs(30),
            )
            .unwrap();
        watchdog.shared.shutdown.store(true, Ordering::Release);
        release.send(()).unwrap();
        drop(watchdog);
        drop(retained);
        assert!(events.try_recv().is_err());
    }
}
