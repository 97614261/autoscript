//! Request-ID fence and event-driven cancellation, independent of the input dispatch lock.
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
};
use std::thread::Thread;

#[derive(Clone, Debug, Default)]
pub struct InputCancellation(Arc<CancellationInner>);
#[derive(Debug, Default)]
struct CancellationInner {
    cancelled: AtomicBool,
    waiter: Mutex<Option<Thread>>,
}
impl InputCancellation {
    pub fn is_cancelled(&self) -> bool {
        self.0.cancelled.load(Ordering::Acquire)
    }
    pub fn cancel(&self) {
        self.0.cancelled.store(true, Ordering::Release);
        if let Some(thread) = self
            .0
            .waiter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
        {
            thread.unpark();
        }
    }
    /// The owned child watchdog is the only waiter. Registration handles cancellation-before-spawn.
    pub fn register_watchdog(&self, thread: Thread) {
        let mut waiter = self
            .0
            .waiter
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if self.is_cancelled() {
            thread.unpark();
        }
        *waiter = Some(thread);
    }
}
#[derive(Debug, Default)]
struct FenceState {
    watermark: u64,
    latest_input: u64,
    active: Option<(u64, InputCancellation)>,
    failed: bool,
}
#[derive(Debug, Default)]
pub struct InputFence(Mutex<FenceState>);
impl InputFence {
    /// Exactly one serial business stream owns the active request. Stale IDs never inject.
    pub fn begin(&self, id: u64) -> Option<InputCancellation> {
        let mut state = self
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if id <= state.watermark || state.failed || state.active.is_some() {
            return None;
        }
        let token = InputCancellation::default();
        state.latest_input = id;
        state.active = Some((id, token.clone()));
        Some(token)
    }
    pub fn finish(&self, id: u64) {
        let mut state = self
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state
            .active
            .as_ref()
            .is_some_and(|(active, _)| *active == id)
        {
            state.active = None;
        }
    }
    /// False means a newer input has already begun; stale stop must not wait for or release it.
    pub fn cancel_through(&self, id: u64) -> bool {
        let mut state = self
            .0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.watermark = state.watermark.max(id);
        if let Some((active, token)) = &state.active {
            if *active <= state.watermark {
                token.cancel();
            }
        }
        state.latest_input <= id
    }
    pub fn fail_closed(&self) {
        self.0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .failed = true;
    }
    pub fn is_failed(&self) -> bool {
        self.0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .failed
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn late_request_and_stale_stop_are_fenced() {
        let fence = InputFence::default();
        let old = fence.begin(2).unwrap();
        fence.cancel_through(2);
        assert!(old.is_cancelled());
        fence.finish(2);
        assert!(fence.begin(2).is_none());
        let fresh = fence.begin(5).unwrap();
        fence.cancel_through(2);
        assert!(!fresh.is_cancelled());
        fence.finish(2);
        assert!(fence.begin(6).is_none());
        fence.finish(5);
        fence.fail_closed();
        assert!(fence.begin(6).is_none());
    }
    #[test]
    fn cancellation_before_watchdog_registration_is_delivered() {
        let token = InputCancellation::default();
        token.cancel();
        let worker = std::thread::spawn(move || {
            token.register_watchdog(std::thread::current());
            std::thread::park_timeout(std::time::Duration::from_secs(1));
            assert!(token.is_cancelled());
        });
        worker.join().unwrap();
    }
}
