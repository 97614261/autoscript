//! Single-threaded task scheduling primitives for the Lua runtime.
//!
//! The scheduler owns no Lua values and its thread-safe handle only transports
//! opaque bytes. This keeps every Lua object on the VM owner thread.

mod clock;
mod ids;
mod resource;
mod scheduler;

pub use clock::{Clock, ManualClock, MonoTime};
pub use ids::{RequestId, ResourceId, ResourceKind, TaskGeneration, TaskId, TaskToken, TimerId};
pub use resource::{ResourceError, ResourceOwner, TaskResourceRegistry};
pub use scheduler::{
    HostCompletion, HostResult, JoinResult, NoopTaskFinalizer, ScheduleError, Scheduler,
    SchedulerConfig, SchedulerEvent, SchedulerHandle, SchedulerPoll, SubmitError, TaskFailure,
    TaskFinalizer, TaskOutcome, TaskState, WaitReason,
};
