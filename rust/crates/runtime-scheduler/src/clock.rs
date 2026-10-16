use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct MonoTime(u64);

impl MonoTime {
    pub const ZERO: Self = Self(0);

    #[must_use]
    pub const fn from_nanos(value: u64) -> Self {
        Self(value)
    }

    #[must_use]
    pub const fn as_nanos(self) -> u64 {
        self.0
    }

    #[must_use]
    pub fn checked_add(self, duration: Duration) -> Option<Self> {
        let nanos = u64::try_from(duration.as_nanos()).ok()?;
        self.0.checked_add(nanos).map(Self)
    }

    #[must_use]
    pub const fn saturating_duration_since(self, earlier: Self) -> Duration {
        Duration::from_nanos(self.0.saturating_sub(earlier.0))
    }
}

pub trait Clock {
    fn now(&self) -> MonoTime;
}

#[derive(Debug, Clone, Default)]
pub struct ManualClock {
    nanos: Arc<AtomicU64>,
}

impl ManualClock {
    #[must_use]
    pub fn new(now: MonoTime) -> Self {
        Self {
            nanos: Arc::new(AtomicU64::new(now.as_nanos())),
        }
    }

    pub fn set(&self, now: MonoTime) {
        self.nanos.store(now.as_nanos(), Ordering::Release);
    }

    pub fn advance(&self, duration: Duration) {
        let nanos = u64::try_from(duration.as_nanos()).unwrap_or(u64::MAX);
        let _ = self
            .nanos
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
                Some(current.saturating_add(nanos))
            });
    }
}

impl Clock for ManualClock {
    fn now(&self) -> MonoTime {
        MonoTime::from_nanos(self.nanos.load(Ordering::Acquire))
    }
}
