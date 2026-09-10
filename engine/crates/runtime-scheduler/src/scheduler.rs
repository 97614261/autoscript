use std::cmp::Reverse;
use std::collections::{BTreeMap, BTreeSet, BinaryHeap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use crate::{
    Clock, MonoTime, RequestId, ResourceError, ResourceId, ResourceOwner, TaskGeneration, TaskId,
    TaskResourceRegistry, TaskToken, TimerId,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskState {
    Running,
    Completed,
    Cancelled,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WaitReason {
    HostRequest(RequestId),
    Timer(TimerId),
    Join(TaskToken),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskFailure {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskOutcome {
    pub status: TaskState,
    pub value: Vec<u8>,
    pub error: Option<TaskFailure>,
    pub resources: Vec<ResourceId>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HostResult {
    Success(Vec<u8>),
    Failure { code: String, message: String },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostCompletion {
    pub request_id: RequestId,
    pub task: TaskToken,
    pub result: HostResult,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SchedulerEvent {
    HostReady(HostCompletion),
    HostTimedOut {
        request: RequestId,
        task: TaskToken,
    },
    HostCancelled {
        request: RequestId,
        task: TaskToken,
    },
    TimerReady {
        timer: TimerId,
        task: TaskToken,
        missed_count: u32,
    },
    TaskFinished {
        task: TaskToken,
        state: TaskState,
    },
    ResourceReleased(ResourceId),
    FinalizerFailed {
        task: TaskToken,
        message: String,
    },
    LateHostResultDiscarded {
        request: RequestId,
        task: TaskToken,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SchedulerPoll {
    Runnable,
    Deadline(MonoTime),
    Idle,
    Paused,
    PausedUntil(MonoTime),
    Stopped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JoinResult {
    Pending,
    Ready(TaskOutcome),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScheduleError {
    TaskLimitReached { maximum: usize },
    OutcomeLimitReached { maximum: usize },
    TimerLimitReached { maximum: usize },
    UnknownTask(TaskToken),
    TaskNotRunning(TaskToken),
    InvalidParent(TaskToken),
    UnknownTimer(TimerId),
    TimerNotInFlight(TimerId),
    RequestAlreadyPending(RequestId),
    InvalidTimerPeriod,
    InvalidTimeout,
    TimeOverflow,
    AlreadyPaused,
    NotPaused,
    OutcomeNotReady(TaskToken),
    CannotJoinSelf(TaskToken),
    DuplicateOutcomeResource(ResourceId),
    Resource(ResourceError),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubmitError {
    CancelQueueFull { maximum: usize },
    CompletionQueueFull { maximum: usize },
}

impl From<ResourceError> for ScheduleError {
    fn from(value: ResourceError) -> Self {
        Self::Resource(value)
    }
}

#[derive(Debug, Clone)]
pub struct SchedulerConfig {
    pub max_active_tasks: usize,
    pub max_retained_outcomes: usize,
    pub max_timers: usize,
    pub max_cancel_events: usize,
    pub max_completion_events: usize,
    pub min_timer_period: Duration,
    pub max_timer_backlog: u32,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            max_active_tasks: 256,
            max_retained_outcomes: 256,
            max_timers: 1_024,
            max_cancel_events: 256,
            max_completion_events: 1_024,
            min_timer_period: Duration::from_millis(1),
            max_timer_backlog: 10_000,
        }
    }
}

pub trait TaskFinalizer {
    /// Closes/unwinds the task's VM coroutine on the VM owner thread.
    ///
    /// # Errors
    ///
    /// Returns a diagnostic if VM cleanup failed. Resource cleanup still proceeds.
    fn close_task(&mut self, task: TaskToken) -> Result<(), String>;
}

#[derive(Debug, Default)]
pub struct NoopTaskFinalizer;

impl TaskFinalizer for NoopTaskFinalizer {
    fn close_task(&mut self, _task: TaskToken) -> Result<(), String> {
        Ok(())
    }
}

#[derive(Debug)]
struct SharedQueues {
    cancelled_tasks: Mutex<BTreeSet<TaskToken>>,
    completions: Mutex<VecDeque<HostCompletion>>,
    wake_epoch: Mutex<u64>,
    wake_condvar: Condvar,
    stop_requested: AtomicBool,
    max_cancel_events: usize,
    max_completion_events: usize,
}

impl SharedQueues {
    fn new(max_cancel_events: usize, max_completion_events: usize) -> Self {
        Self {
            cancelled_tasks: Mutex::new(BTreeSet::new()),
            completions: Mutex::new(VecDeque::new()),
            wake_epoch: Mutex::new(0),
            wake_condvar: Condvar::new(),
            stop_requested: AtomicBool::new(false),
            max_cancel_events,
            max_completion_events,
        }
    }

    fn wake(&self) {
        let mut epoch = self
            .wake_epoch
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        *epoch = epoch.wrapping_add(1);
        self.wake_condvar.notify_all();
    }
}

#[derive(Debug, Clone)]
pub struct SchedulerHandle {
    shared: Arc<SharedQueues>,
}

impl SchedulerHandle {
    pub fn request_stop(&self) {
        self.shared.stop_requested.store(true, Ordering::Release);
        self.shared.wake();
    }

    /// Queues an idempotent task cancellation on the priority control channel.
    ///
    /// # Errors
    ///
    /// Returns backpressure when the bounded control channel is full.
    pub fn request_cancel(&self, task: TaskToken) -> Result<(), SubmitError> {
        let mut queue = self
            .shared
            .cancelled_tasks
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !queue.contains(&task) && queue.len() >= self.shared.max_cancel_events {
            return Err(SubmitError::CancelQueueFull {
                maximum: self.shared.max_cancel_events,
            });
        }
        queue.insert(task);
        drop(queue);
        self.shared.wake();
        Ok(())
    }

    /// Queues a host completion without blocking its producer.
    ///
    /// # Errors
    ///
    /// Returns backpressure when the bounded completion channel is full.
    pub fn submit_host_completion(&self, completion: HostCompletion) -> Result<(), SubmitError> {
        let mut queue = self
            .shared
            .completions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if queue.len() >= self.shared.max_completion_events {
            return Err(SubmitError::CompletionQueueFull {
                maximum: self.shared.max_completion_events,
            });
        }
        queue.push_back(completion);
        drop(queue);
        self.shared.wake();
        Ok(())
    }

    #[must_use]
    pub fn wake_epoch(&self) -> u64 {
        *self
            .shared
            .wake_epoch
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// Sleeps until another thread changes the wake epoch, or until `timeout` expires.
    /// This is the executor's non-polling wait primitive.
    #[must_use]
    pub fn wait_for_wake(&self, observed_epoch: u64, timeout: Option<Duration>) -> u64 {
        let epoch = self
            .shared
            .wake_epoch
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if *epoch != observed_epoch {
            return *epoch;
        }
        let epoch = if let Some(timeout) = timeout {
            self.shared
                .wake_condvar
                .wait_timeout_while(epoch, timeout, |current| *current == observed_epoch)
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .0
        } else {
            self.shared
                .wake_condvar
                .wait_while(epoch, |current| *current == observed_epoch)
                .unwrap_or_else(std::sync::PoisonError::into_inner)
        };
        *epoch
    }
}

#[derive(Debug)]
struct TaskRecord {
    token: TaskToken,
    state: TaskState,
    wait: Option<WaitReason>,
    queued: bool,
    parent: Option<TaskToken>,
    detached: bool,
    children: BTreeSet<TaskToken>,
    outcome: Option<TaskOutcome>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TimerKind {
    Sleep,
    HostTimeout(RequestId),
    Interval {
        period: Duration,
        in_flight: bool,
        pending_ticks: u32,
    },
}

#[derive(Debug)]
struct TimerRecord {
    owner: TaskToken,
    deadline: MonoTime,
    kind: TimerKind,
}

#[derive(Debug, Clone, Copy)]
struct HostWaiter {
    task: TaskToken,
    timeout_timer: TimerId,
}

#[derive(Debug)]
pub struct Scheduler<C, F = NoopTaskFinalizer> {
    clock: C,
    finalizer: F,
    config: SchedulerConfig,
    shared: Arc<SharedQueues>,
    tasks: BTreeMap<TaskId, TaskRecord>,
    active_tasks: usize,
    next_task_id: u64,
    next_timer_id: u64,
    run_queue: VecDeque<TaskToken>,
    host_waiters: BTreeMap<RequestId, HostWaiter>,
    join_waiters: BTreeMap<TaskToken, BTreeSet<TaskToken>>,
    timers: BTreeMap<TimerId, TimerRecord>,
    timer_heap: BinaryHeap<Reverse<(MonoTime, TimerId)>>,
    resources: TaskResourceRegistry,
    events: VecDeque<SchedulerEvent>,
    paused_at: Option<MonoTime>,
    stopped: bool,
}

impl<C: Clock> Scheduler<C, NoopTaskFinalizer> {
    #[must_use]
    pub fn new(clock: C, config: SchedulerConfig) -> Self {
        Self::with_finalizer(clock, config, NoopTaskFinalizer)
    }
}

impl<C: Clock, F: TaskFinalizer> Scheduler<C, F> {
    #[must_use]
    pub fn with_finalizer(clock: C, config: SchedulerConfig, finalizer: F) -> Self {
        let shared = Arc::new(SharedQueues::new(
            config.max_cancel_events,
            config.max_completion_events,
        ));
        Self {
            clock,
            finalizer,
            config,
            shared,
            tasks: BTreeMap::new(),
            active_tasks: 0,
            next_task_id: 1,
            next_timer_id: 1,
            run_queue: VecDeque::new(),
            host_waiters: BTreeMap::new(),
            join_waiters: BTreeMap::new(),
            timers: BTreeMap::new(),
            timer_heap: BinaryHeap::new(),
            resources: TaskResourceRegistry::default(),
            events: VecDeque::new(),
            paused_at: None,
            stopped: false,
        }
    }

    #[must_use]
    pub fn handle(&self) -> SchedulerHandle {
        SchedulerHandle {
            shared: Arc::clone(&self.shared),
        }
    }

    #[must_use]
    pub const fn resources(&self) -> &TaskResourceRegistry {
        &self.resources
    }

    pub const fn resources_mut(&mut self) -> &mut TaskResourceRegistry {
        &mut self.resources
    }

    #[must_use]
    pub const fn finalizer(&self) -> &F {
        &self.finalizer
    }

    pub const fn finalizer_mut(&mut self) -> &mut F {
        &mut self.finalizer
    }

    #[must_use]
    pub const fn active_task_count(&self) -> usize {
        self.active_tasks
    }

    /// Spawns a runnable task. A non-detached child is cancelled when its parent ends.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid parent or when the active task limit is reached.
    pub fn spawn(
        &mut self,
        parent: Option<TaskToken>,
        detached: bool,
    ) -> Result<TaskToken, ScheduleError> {
        if self.active_tasks >= self.config.max_active_tasks {
            return Err(ScheduleError::TaskLimitReached {
                maximum: self.config.max_active_tasks,
            });
        }
        let retained_outcomes = self.tasks.len().saturating_sub(self.active_tasks);
        if retained_outcomes >= self.config.max_retained_outcomes {
            return Err(ScheduleError::OutcomeLimitReached {
                maximum: self.config.max_retained_outcomes,
            });
        }
        if let Some(parent) = parent {
            self.running_task(parent)
                .map_err(|_| ScheduleError::InvalidParent(parent))?;
        }
        let id = TaskId(self.next_task_id);
        self.next_task_id = self.next_task_id.checked_add(1).unwrap_or(1);
        let token = TaskToken {
            id,
            generation: TaskGeneration(1),
        };
        self.tasks.insert(
            id,
            TaskRecord {
                token,
                state: TaskState::Running,
                wait: None,
                queued: true,
                parent,
                detached,
                children: BTreeSet::new(),
                outcome: None,
            },
        );
        if let Some(parent) = parent {
            if let Some(parent_record) = self.tasks.get_mut(&parent.id) {
                parent_record.children.insert(token);
            }
        }
        self.active_tasks += 1;
        self.run_queue.push_back(token);
        Ok(token)
    }

    #[must_use]
    pub fn task_state(&self, task: TaskToken) -> Option<TaskState> {
        self.tasks
            .get(&task.id)
            .filter(|record| record.token == task)
            .map(|record| record.state)
    }

    /// Borrows the retained terminal outcome for a matching task token.
    #[must_use]
    pub fn task_outcome(&self, task: TaskToken) -> Option<&TaskOutcome> {
        self.tasks
            .get(&task.id)
            .filter(|record| record.token == task)
            .and_then(|record| record.outcome.as_ref())
    }

    #[must_use]
    pub fn wait_reason(&self, task: TaskToken) -> Option<WaitReason> {
        self.tasks
            .get(&task.id)
            .filter(|record| record.token == task)
            .and_then(|record| record.wait)
    }

    pub fn take_next_runnable(&mut self) -> Option<TaskToken> {
        while let Some(token) = self.run_queue.pop_front() {
            let Some(record) = self.tasks.get_mut(&token.id) else {
                continue;
            };
            if record.token == token
                && record.state == TaskState::Running
                && record.wait.is_none()
                && record.queued
            {
                record.queued = false;
                return Some(token);
            }
        }
        None
    }

    /// Puts an instruction-budget-exhausted coroutine at the back of the fair run queue.
    ///
    /// # Errors
    ///
    /// Returns an error if the task is stale or no longer runnable.
    pub fn yield_budget(&mut self, task: TaskToken) -> Result<(), ScheduleError> {
        let record = self.running_task_mut(task)?;
        if record.wait.is_some() {
            return Err(ScheduleError::TaskNotRunning(task));
        }
        self.enqueue(task);
        Ok(())
    }

    /// Suspends a task until the exact host request completes or its deadline expires.
    ///
    /// # Errors
    ///
    /// Returns an error for stale tasks, duplicate request identifiers, timer limits, invalid
    /// timeouts or time overflow.
    pub fn wait_for_host(
        &mut self,
        task: TaskToken,
        request: RequestId,
        timeout: Duration,
    ) -> Result<(), ScheduleError> {
        if self.host_waiters.contains_key(&request) {
            return Err(ScheduleError::RequestAlreadyPending(request));
        }
        if timeout.is_zero() {
            return Err(ScheduleError::InvalidTimeout);
        }
        self.ensure_timer_capacity()?;
        let deadline = self
            .clock
            .now()
            .checked_add(timeout)
            .ok_or(ScheduleError::TimeOverflow)?;
        let timeout_timer = self.allocate_timer_id();
        self.set_wait(task, WaitReason::HostRequest(request))?;
        self.host_waiters.insert(
            request,
            HostWaiter {
                task,
                timeout_timer,
            },
        );
        self.timers.insert(
            timeout_timer,
            TimerRecord {
                owner: task,
                deadline,
                kind: TimerKind::HostTimeout(request),
            },
        );
        self.timer_heap.push(Reverse((deadline, timeout_timer)));
        Ok(())
    }

    /// Suspends a task until a one-shot timer expires.
    ///
    /// # Errors
    ///
    /// Returns an error for stale tasks, timer limits, zero durations or time overflow.
    pub fn sleep(&mut self, task: TaskToken, duration: Duration) -> Result<TimerId, ScheduleError> {
        if duration.is_zero() {
            return Err(ScheduleError::InvalidTimerPeriod);
        }
        self.ensure_timer_capacity()?;
        let deadline = self
            .clock
            .now()
            .checked_add(duration)
            .ok_or(ScheduleError::TimeOverflow)?;
        let timer = self.allocate_timer_id();
        self.set_wait(task, WaitReason::Timer(timer))?;
        self.timers.insert(
            timer,
            TimerRecord {
                owner: task,
                deadline,
                kind: TimerKind::Sleep,
            },
        );
        self.timer_heap.push(Reverse((deadline, timer)));
        Ok(timer)
    }

    /// Creates a fixed-rate, non-reentrant interval timer owned by a task.
    ///
    /// # Errors
    ///
    /// Returns an error for stale tasks, timer limits, periods below the configured minimum,
    /// or time overflow.
    pub fn every(&mut self, owner: TaskToken, period: Duration) -> Result<TimerId, ScheduleError> {
        self.running_task(owner)?;
        if period.is_zero() || period < self.config.min_timer_period {
            return Err(ScheduleError::InvalidTimerPeriod);
        }
        self.ensure_timer_capacity()?;
        let deadline = self
            .clock
            .now()
            .checked_add(period)
            .ok_or(ScheduleError::TimeOverflow)?;
        let timer = self.allocate_timer_id();
        self.timers.insert(
            timer,
            TimerRecord {
                owner,
                deadline,
                kind: TimerKind::Interval {
                    period,
                    in_flight: false,
                    pending_ticks: 0,
                },
            },
        );
        self.timer_heap.push(Reverse((deadline, timer)));
        Ok(timer)
    }

    /// Marks an interval callback complete, allowing at most one consolidated callback next.
    ///
    /// # Errors
    ///
    /// Returns an error for unknown timers or callbacks that are not in flight.
    pub fn complete_timer_callback(&mut self, timer: TimerId) -> Result<(), ScheduleError> {
        self.process_due_timers();
        let record = self
            .timers
            .get_mut(&timer)
            .ok_or(ScheduleError::UnknownTimer(timer))?;
        let TimerKind::Interval {
            in_flight,
            pending_ticks,
            ..
        } = &mut record.kind
        else {
            return Err(ScheduleError::UnknownTimer(timer));
        };
        if !*in_flight {
            return Err(ScheduleError::TimerNotInFlight(timer));
        }
        if *pending_ticks == 0 {
            *in_flight = false;
        } else {
            let ticks = std::mem::take(pending_ticks);
            self.events.push_back(SchedulerEvent::TimerReady {
                timer,
                task: record.owner,
                missed_count: ticks.saturating_sub(1),
            });
        }
        Ok(())
    }

    pub fn cancel_timer(&mut self, timer: TimerId) -> bool {
        self.timers.remove(&timer).is_some()
    }

    /// Freezes business timers. Host completions may still arrive but tasks do not run.
    ///
    /// # Errors
    ///
    /// Returns an error if already paused.
    pub fn pause(&mut self) -> Result<(), ScheduleError> {
        if self.paused_at.is_some() {
            return Err(ScheduleError::AlreadyPaused);
        }
        self.paused_at = Some(self.clock.now());
        Ok(())
    }

    /// Resumes and shifts all timer deadlines by the exact paused duration.
    ///
    /// # Errors
    ///
    /// Returns an error if not paused or shifting a deadline overflows.
    pub fn resume(&mut self) -> Result<(), ScheduleError> {
        let paused_at = self.paused_at.ok_or(ScheduleError::NotPaused)?;
        let pause_duration = self.clock.now().saturating_duration_since(paused_at);
        for record in self.timers.values_mut() {
            if !matches!(record.kind, TimerKind::HostTimeout(_)) {
                record.deadline = record
                    .deadline
                    .checked_add(pause_duration)
                    .ok_or(ScheduleError::TimeOverflow)?;
            }
        }
        self.rebuild_timer_heap();
        self.paused_at = None;
        Ok(())
    }

    /// Joins a task, suspending the caller if the target is still running. A ready outcome's
    /// resource leases are atomically transferred to the joining task.
    ///
    /// # Errors
    ///
    /// Returns an error for stale tasks, self-join, or an unavailable outcome.
    pub fn join(
        &mut self,
        waiter: TaskToken,
        target: TaskToken,
    ) -> Result<JoinResult, ScheduleError> {
        self.running_task(waiter)?;
        if waiter == target {
            return Err(ScheduleError::CannotJoinSelf(waiter));
        }
        let target_state = self
            .tasks
            .get(&target.id)
            .filter(|record| record.token == target)
            .ok_or(ScheduleError::UnknownTask(target))?
            .state;
        if target_state == TaskState::Running {
            self.set_wait(waiter, WaitReason::Join(target))?;
            self.join_waiters.entry(target).or_default().insert(waiter);
            return Ok(JoinResult::Pending);
        }
        self.claim_outcome(waiter, target).map(JoinResult::Ready)
    }

    /// Claims a finished outcome after a pending join wakes.
    ///
    /// # Errors
    ///
    /// Returns an error if either task is stale or the target is unfinished.
    pub fn claim_outcome(
        &mut self,
        waiter: TaskToken,
        target: TaskToken,
    ) -> Result<TaskOutcome, ScheduleError> {
        self.running_task(waiter)?;
        let target_record = self
            .tasks
            .get(&target.id)
            .filter(|record| record.token == target)
            .ok_or(ScheduleError::UnknownTask(target))?;
        if target_record.state == TaskState::Running {
            return Err(ScheduleError::OutcomeNotReady(target));
        }
        let mut outcome = target_record
            .outcome
            .clone()
            .ok_or(ScheduleError::OutcomeNotReady(target))?;
        let resources = self
            .resources
            .transfer_owner(ResourceOwner::Outcome(target), ResourceOwner::Task(waiter))?;
        outcome.resources = resources;
        self.tasks.remove(&target.id);
        Ok(outcome)
    }

    /// Drops an unclaimed outcome and releases its hosted resources.
    ///
    /// # Errors
    ///
    /// Returns an error if the outcome does not exist.
    pub fn discard_outcome(&mut self, target: TaskToken) -> Result<(), ScheduleError> {
        let record = self
            .tasks
            .get(&target.id)
            .filter(|record| record.token == target)
            .ok_or(ScheduleError::UnknownTask(target))?;
        if record.state == TaskState::Running || record.outcome.is_none() {
            return Err(ScheduleError::OutcomeNotReady(target));
        }
        let released = self.resources.release_owner(ResourceOwner::Outcome(target));
        self.tasks.remove(&target.id);
        self.publish_released(released);
        Ok(())
    }

    /// Completes a task and optionally moves selected task-owned resources into its outcome.
    ///
    /// # Errors
    ///
    /// Returns an error for stale tasks or resources not leased by the task.
    pub fn complete(
        &mut self,
        task: TaskToken,
        value: Vec<u8>,
        outcome_resources: &[ResourceId],
    ) -> Result<(), ScheduleError> {
        self.running_task(task)?;
        let mut unique_resources = BTreeSet::new();
        for resource in outcome_resources {
            if !unique_resources.insert(*resource) {
                return Err(ScheduleError::DuplicateOutcomeResource(*resource));
            }
            if !self.resources.holds(ResourceOwner::Task(task), *resource) {
                return Err(ScheduleError::Resource(ResourceError::LeaseNotHeld {
                    resource: *resource,
                    owner: ResourceOwner::Task(task),
                }));
            }
        }
        for resource in outcome_resources {
            self.resources.transfer_resource(
                ResourceOwner::Task(task),
                ResourceOwner::Outcome(task),
                *resource,
            )?;
        }
        let outcome = TaskOutcome {
            status: TaskState::Completed,
            value,
            error: None,
            resources: outcome_resources.to_vec(),
        };
        self.finish_task(task, outcome);
        Ok(())
    }

    /// Fails a running task.
    ///
    /// # Errors
    ///
    /// Returns an error for stale or already-finished tasks.
    pub fn fail(&mut self, task: TaskToken, failure: TaskFailure) -> Result<(), ScheduleError> {
        self.running_task(task)?;
        self.finish_task(
            task,
            TaskOutcome {
                status: TaskState::Failed,
                value: Vec::new(),
                error: Some(failure),
                resources: Vec::new(),
            },
        );
        Ok(())
    }

    /// Processes stop/cancel controls before ordinary completions and timer events.
    #[must_use]
    pub fn process(&mut self) -> Vec<SchedulerEvent> {
        self.process_controls();
        if self.stopped {
            self.discard_all_host_completions();
        } else {
            self.process_host_completions();
            self.process_due_timers();
        }
        self.events.drain(..).collect()
    }

    #[must_use]
    pub fn poll_state(&self) -> SchedulerPoll {
        if self.stopped {
            return SchedulerPoll::Stopped;
        }
        if self.paused_at.is_some() {
            return self
                .next_host_timeout_deadline()
                .map_or(SchedulerPoll::Paused, SchedulerPoll::PausedUntil);
        }
        if self.run_queue.iter().any(|task| {
            self.tasks.get(&task.id).is_some_and(|record| {
                record.token == *task
                    && record.state == TaskState::Running
                    && record.wait.is_none()
                    && record.queued
            })
        }) {
            return SchedulerPoll::Runnable;
        }
        self.next_live_deadline()
            .map_or(SchedulerPoll::Idle, SchedulerPoll::Deadline)
    }

    fn running_task(&self, task: TaskToken) -> Result<&TaskRecord, ScheduleError> {
        let record = self
            .tasks
            .get(&task.id)
            .filter(|record| record.token == task)
            .ok_or(ScheduleError::UnknownTask(task))?;
        if record.state != TaskState::Running {
            return Err(ScheduleError::TaskNotRunning(task));
        }
        Ok(record)
    }

    fn running_task_mut(&mut self, task: TaskToken) -> Result<&mut TaskRecord, ScheduleError> {
        let record = self
            .tasks
            .get_mut(&task.id)
            .filter(|record| record.token == task)
            .ok_or(ScheduleError::UnknownTask(task))?;
        if record.state != TaskState::Running {
            return Err(ScheduleError::TaskNotRunning(task));
        }
        Ok(record)
    }

    fn set_wait(&mut self, task: TaskToken, wait: WaitReason) -> Result<(), ScheduleError> {
        let record = self.running_task_mut(task)?;
        if record.wait.is_some() {
            return Err(ScheduleError::TaskNotRunning(task));
        }
        record.wait = Some(wait);
        record.queued = false;
        Ok(())
    }

    fn enqueue(&mut self, task: TaskToken) {
        if let Some(record) = self.tasks.get_mut(&task.id) {
            if record.token == task
                && record.state == TaskState::Running
                && record.wait.is_none()
                && !record.queued
            {
                record.queued = true;
                self.run_queue.push_back(task);
            }
        }
    }

    fn process_controls(&mut self) {
        if self.shared.stop_requested.load(Ordering::Acquire) && !self.stopped {
            let active = self
                .tasks
                .values()
                .filter(|record| record.state == TaskState::Running)
                .map(|record| record.token)
                .collect::<Vec<_>>();
            for task in active {
                if self.task_state(task) == Some(TaskState::Running) {
                    self.cancel_tree(task, true);
                }
            }
            self.stopped = true;
            self.run_queue.clear();
        }
        let cancelled = {
            let mut queue = self
                .shared
                .cancelled_tasks
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            std::mem::take(&mut *queue).into_iter().collect::<Vec<_>>()
        };
        if !self.stopped {
            for task in cancelled {
                if self.task_state(task) == Some(TaskState::Running) {
                    self.cancel_tree(task, false);
                }
            }
        }
    }

    fn process_host_completions(&mut self) {
        let completions = {
            let mut queue = self
                .shared
                .completions
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            queue.drain(..).collect::<Vec<_>>()
        };
        for completion in completions {
            let expected = self.host_waiters.get(&completion.request_id).copied();
            let valid = expected.is_some_and(|waiter| waiter.task == completion.task)
                && self.tasks.get(&completion.task.id).is_some_and(|record| {
                    record.token == completion.task
                        && record.state == TaskState::Running
                        && record.wait == Some(WaitReason::HostRequest(completion.request_id))
                });
            if !valid {
                self.events
                    .push_back(SchedulerEvent::LateHostResultDiscarded {
                        request: completion.request_id,
                        task: completion.task,
                    });
                continue;
            }
            if let Some(waiter) = self.host_waiters.remove(&completion.request_id) {
                self.timers.remove(&waiter.timeout_timer);
            }
            if let Some(record) = self.tasks.get_mut(&completion.task.id) {
                record.wait = None;
            }
            self.enqueue(completion.task);
            self.events.push_back(SchedulerEvent::HostReady(completion));
        }
    }

    fn discard_all_host_completions(&mut self) {
        let completions = {
            let mut queue = self
                .shared
                .completions
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            queue.drain(..).collect::<Vec<_>>()
        };
        for completion in completions {
            self.events
                .push_back(SchedulerEvent::LateHostResultDiscarded {
                    request: completion.request_id,
                    task: completion.task,
                });
        }
    }

    fn cancel_tree(&mut self, task: TaskToken, include_detached: bool) {
        let children = self
            .tasks
            .get(&task.id)
            .map(|record| {
                record
                    .children
                    .iter()
                    .copied()
                    .filter(|child| {
                        include_detached
                            || self
                                .tasks
                                .get(&child.id)
                                .is_some_and(|record| !record.detached)
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        for child in children {
            if self.task_state(child) == Some(TaskState::Running) {
                self.cancel_tree(child, include_detached);
            }
        }
        if self.task_state(task) == Some(TaskState::Running) {
            self.finish_task(
                task,
                TaskOutcome {
                    status: TaskState::Cancelled,
                    value: Vec::new(),
                    error: None,
                    resources: Vec::new(),
                },
            );
        }
    }

    fn finish_task(&mut self, task: TaskToken, outcome: TaskOutcome) {
        let state = outcome.status;
        let parent = self.tasks.get(&task.id).and_then(|record| record.parent);
        let children = self
            .tasks
            .get(&task.id)
            .map(|record| record.children.iter().copied().collect::<Vec<_>>())
            .unwrap_or_default();
        for child in children {
            let detached = self
                .tasks
                .get(&child.id)
                .is_some_and(|record| record.detached);
            if !detached && self.task_state(child) == Some(TaskState::Running) {
                self.cancel_tree(child, false);
            } else if detached {
                if let Some(child_record) = self.tasks.get_mut(&child.id) {
                    child_record.parent = None;
                }
            }
        }

        self.remove_task_waits(task);
        self.remove_owned_timers(task);
        self.run_queue.retain(|queued| *queued != task);
        if let Err(message) = self.finalizer.close_task(task) {
            self.events
                .push_back(SchedulerEvent::FinalizerFailed { task, message });
        }
        let released = self.resources.release_owner(ResourceOwner::Task(task));
        self.publish_released(released);

        if let Some(record) = self.tasks.get_mut(&task.id) {
            record.state = state;
            record.wait = None;
            record.queued = false;
            record.outcome = Some(outcome);
        }
        if let Some(parent) = parent {
            if let Some(parent_record) = self.tasks.get_mut(&parent.id) {
                parent_record.children.remove(&task);
            }
        }
        self.active_tasks = self.active_tasks.saturating_sub(1);
        self.wake_joiners(task);
        self.events
            .push_back(SchedulerEvent::TaskFinished { task, state });
    }

    fn remove_task_waits(&mut self, task: TaskToken) {
        let requests = self
            .host_waiters
            .iter()
            .filter_map(|(request, waiter)| (waiter.task == task).then_some((*request, *waiter)))
            .collect::<Vec<_>>();
        for (request, waiter) in requests {
            self.host_waiters.remove(&request);
            self.timers.remove(&waiter.timeout_timer);
            self.events
                .push_back(SchedulerEvent::HostCancelled { request, task });
        }
        self.join_waiters.retain(|_, waiters| {
            waiters.remove(&task);
            !waiters.is_empty()
        });
    }

    fn wake_joiners(&mut self, target: TaskToken) {
        let waiters = self.join_waiters.remove(&target).unwrap_or_default();
        for waiter in waiters {
            if let Some(record) = self.tasks.get_mut(&waiter.id) {
                if record.token == waiter
                    && record.state == TaskState::Running
                    && record.wait == Some(WaitReason::Join(target))
                {
                    record.wait = None;
                    self.enqueue(waiter);
                }
            }
        }
    }

    fn remove_owned_timers(&mut self, task: TaskToken) {
        self.timers.retain(|_, timer| timer.owner != task);
    }

    fn publish_released(&mut self, released: Vec<ResourceId>) {
        self.events
            .extend(released.into_iter().map(SchedulerEvent::ResourceReleased));
    }

    fn ensure_timer_capacity(&self) -> Result<(), ScheduleError> {
        if self.timers.len() >= self.config.max_timers {
            Err(ScheduleError::TimerLimitReached {
                maximum: self.config.max_timers,
            })
        } else {
            Ok(())
        }
    }

    fn allocate_timer_id(&mut self) -> TimerId {
        let timer = TimerId(self.next_timer_id);
        self.next_timer_id = self.next_timer_id.checked_add(1).unwrap_or(1);
        timer
    }

    fn process_due_timers(&mut self) {
        if self.paused_at.is_some() {
            self.process_due_host_timeouts();
            return;
        }
        let now = self.clock.now();
        while let Some(Reverse((deadline, timer))) = self.timer_heap.peek().copied() {
            if deadline > now {
                break;
            }
            self.timer_heap.pop();
            let Some(record) = self.timers.get_mut(&timer) else {
                continue;
            };
            if record.deadline != deadline {
                continue;
            }
            let owner = record.owner;
            let mut remove_after_fire = false;
            match &mut record.kind {
                TimerKind::Sleep => {
                    self.timers.remove(&timer);
                    if let Some(task) = self.tasks.get_mut(&owner.id) {
                        if task.token == owner
                            && task.state == TaskState::Running
                            && task.wait == Some(WaitReason::Timer(timer))
                        {
                            task.wait = None;
                            self.enqueue(owner);
                            self.events.push_back(SchedulerEvent::TimerReady {
                                timer,
                                task: owner,
                                missed_count: 0,
                            });
                        }
                    }
                }
                TimerKind::HostTimeout(request) => {
                    let request = *request;
                    self.timers.remove(&timer);
                    let valid = self.host_waiters.get(&request).is_some_and(|waiter| {
                        waiter.task == owner && waiter.timeout_timer == timer
                    });
                    if valid {
                        self.host_waiters.remove(&request);
                        if let Some(task) = self.tasks.get_mut(&owner.id) {
                            if task.token == owner
                                && task.state == TaskState::Running
                                && task.wait == Some(WaitReason::HostRequest(request))
                            {
                                task.wait = None;
                                self.enqueue(owner);
                                self.events.push_back(SchedulerEvent::HostTimedOut {
                                    request,
                                    task: owner,
                                });
                            }
                        }
                    }
                }
                TimerKind::Interval {
                    period,
                    in_flight,
                    pending_ticks,
                } => {
                    let period_nanos = period.as_nanos();
                    let elapsed = u128::from(now.as_nanos().saturating_sub(deadline.as_nanos()));
                    let ticks_exact = elapsed / period_nanos + 1;
                    let ticks = u32::try_from(ticks_exact)
                        .unwrap_or(u32::MAX)
                        .min(self.config.max_timer_backlog);
                    let next_nanos = u128::from(deadline.as_nanos())
                        .saturating_add(ticks_exact.saturating_mul(period_nanos));
                    if let Ok(next_nanos) = u64::try_from(next_nanos) {
                        record.deadline = MonoTime::from_nanos(next_nanos);
                        self.timer_heap.push(Reverse((record.deadline, timer)));
                    } else {
                        remove_after_fire = true;
                    }
                    if *in_flight {
                        *pending_ticks = pending_ticks
                            .saturating_add(ticks)
                            .min(self.config.max_timer_backlog);
                    } else {
                        *in_flight = true;
                        self.events.push_back(SchedulerEvent::TimerReady {
                            timer,
                            task: owner,
                            missed_count: ticks.saturating_sub(1),
                        });
                    }
                }
            }
            if remove_after_fire {
                self.timers.remove(&timer);
            }
        }
    }

    fn rebuild_timer_heap(&mut self) {
        self.timer_heap = self
            .timers
            .iter()
            .map(|(timer, record)| Reverse((record.deadline, *timer)))
            .collect();
    }

    fn next_live_deadline(&self) -> Option<MonoTime> {
        self.timers.values().map(|timer| timer.deadline).min()
    }

    fn next_host_timeout_deadline(&self) -> Option<MonoTime> {
        self.timers
            .values()
            .filter(|timer| matches!(timer.kind, TimerKind::HostTimeout(_)))
            .map(|timer| timer.deadline)
            .min()
    }

    fn process_due_host_timeouts(&mut self) {
        let now = self.clock.now();
        let due = self
            .timers
            .iter()
            .filter_map(|(timer, record)| {
                (record.deadline <= now && matches!(record.kind, TimerKind::HostTimeout(_)))
                    .then_some(*timer)
            })
            .collect::<Vec<_>>();
        for timer in due {
            let Some(record) = self.timers.remove(&timer) else {
                continue;
            };
            let TimerKind::HostTimeout(request) = record.kind else {
                continue;
            };
            let valid = self
                .host_waiters
                .get(&request)
                .is_some_and(|waiter| waiter.task == record.owner && waiter.timeout_timer == timer);
            if valid {
                self.host_waiters.remove(&request);
                if let Some(task) = self.tasks.get_mut(&record.owner.id) {
                    if task.token == record.owner
                        && task.state == TaskState::Running
                        && task.wait == Some(WaitReason::HostRequest(request))
                    {
                        task.wait = None;
                        self.enqueue(record.owner);
                        self.events.push_back(SchedulerEvent::HostTimedOut {
                            request,
                            task: record.owner,
                        });
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use crate::{ManualClock, MonoTime, ResourceId, ResourceKind};

    use super::{
        HostCompletion, HostResult, JoinResult, ScheduleError, Scheduler, SchedulerConfig,
        SchedulerEvent, SchedulerPoll, SubmitError, TaskFinalizer, TaskState,
    };

    fn scheduler() -> (ManualClock, Scheduler<ManualClock>) {
        let clock = ManualClock::new(MonoTime::ZERO);
        let scheduler = Scheduler::new(clock.clone(), SchedulerConfig::default());
        (clock, scheduler)
    }

    fn resource(local_id: u64) -> ResourceId {
        ResourceId::try_new(ResourceKind::Generic, local_id).expect("test resource id")
    }

    #[test]
    fn cross_thread_queues_apply_backpressure_and_coalesce_cancel() {
        let config = SchedulerConfig {
            max_cancel_events: 1,
            max_completion_events: 1,
            ..SchedulerConfig::default()
        };
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut scheduler = Scheduler::new(clock, config);
        let first = scheduler.spawn(None, false).expect("first");
        let second = scheduler.spawn(None, false).expect("second");
        let handle = scheduler.handle();

        handle.request_cancel(first).expect("first cancel");
        handle
            .request_cancel(first)
            .expect("duplicate is coalesced");
        assert_eq!(
            handle.request_cancel(second),
            Err(SubmitError::CancelQueueFull { maximum: 1 })
        );
        handle
            .submit_host_completion(HostCompletion {
                request_id: crate::RequestId(30),
                task: first,
                result: HostResult::Success(Vec::new()),
            })
            .expect("first completion");
        assert_eq!(
            handle.submit_host_completion(HostCompletion {
                request_id: crate::RequestId(31),
                task: second,
                result: HostResult::Success(Vec::new()),
            }),
            Err(SubmitError::CompletionQueueFull { maximum: 1 })
        );
    }

    #[test]
    fn retained_outcome_limit_is_recovered_by_discard() {
        let config = SchedulerConfig {
            max_retained_outcomes: 1,
            ..SchedulerConfig::default()
        };
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut scheduler = Scheduler::new(clock, config);
        let task = scheduler.spawn(None, false).expect("task");
        scheduler.complete(task, Vec::new(), &[]).expect("complete");
        assert_eq!(
            scheduler.spawn(None, false),
            Err(ScheduleError::OutcomeLimitReached { maximum: 1 })
        );
        scheduler.discard_outcome(task).expect("discard");
        scheduler.spawn(None, false).expect("capacity recovered");
    }

    #[test]
    fn budget_yield_round_robins_tasks() {
        let (_, mut scheduler) = scheduler();
        let first = scheduler.spawn(None, false).expect("first task");
        let second = scheduler.spawn(None, false).expect("second task");
        assert_eq!(scheduler.take_next_runnable(), Some(first));
        scheduler.yield_budget(first).expect("yield first");
        assert_eq!(scheduler.take_next_runnable(), Some(second));
        scheduler.yield_budget(second).expect("yield second");
        assert_eq!(scheduler.take_next_runnable(), Some(first));
    }

    #[test]
    fn stop_beats_an_already_queued_host_completion() {
        let (_, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .wait_for_host(task, crate::RequestId(8), Duration::from_secs(1))
            .expect("wait");
        let handle = scheduler.handle();
        handle
            .submit_host_completion(HostCompletion {
                request_id: crate::RequestId(8),
                task,
                result: HostResult::Success(vec![1]),
            })
            .expect("completion queued");
        handle.request_stop();

        let events = scheduler.process();
        assert_eq!(scheduler.task_state(task), Some(TaskState::Cancelled));
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Stopped);
        assert!(!events
            .iter()
            .any(|event| matches!(event, SchedulerEvent::HostReady(_))));
        assert!(events.iter().any(|event| matches!(
            event,
            SchedulerEvent::LateHostResultDiscarded { request, .. }
                if *request == crate::RequestId(8)
        )));
    }

    #[test]
    fn task_cancel_is_idempotent_and_late_result_is_discarded() {
        let (_, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .wait_for_host(task, crate::RequestId(2), Duration::from_secs(1))
            .expect("wait");
        let handle = scheduler.handle();
        handle.request_cancel(task).expect("cancel queued");
        handle
            .request_cancel(task)
            .expect("duplicate cancel accepted");
        let first = scheduler.process();
        assert_eq!(
            first
                .iter()
                .filter(|event| matches!(event, SchedulerEvent::TaskFinished { .. }))
                .count(),
            1
        );

        handle
            .submit_host_completion(HostCompletion {
                request_id: crate::RequestId(2),
                task,
                result: HostResult::Success(Vec::new()),
            })
            .expect("late completion queued");
        let late = scheduler.process();
        assert!(matches!(
            late.as_slice(),
            [SchedulerEvent::LateHostResultDiscarded { .. }]
        ));
    }

    #[test]
    fn parent_completion_cancels_attached_child_but_not_detached_child() {
        let (_, mut scheduler) = scheduler();
        let parent = scheduler.spawn(None, false).expect("parent");
        let attached = scheduler.spawn(Some(parent), false).expect("attached");
        let detached = scheduler.spawn(Some(parent), true).expect("detached");
        scheduler
            .complete(parent, Vec::new(), &[])
            .expect("complete parent");

        assert_eq!(scheduler.task_state(attached), Some(TaskState::Cancelled));
        assert_eq!(scheduler.task_state(detached), Some(TaskState::Running));
    }

    #[test]
    fn completed_and_failed_tasks_release_non_result_resources() {
        let (_, mut scheduler) = scheduler();
        let completed = scheduler.spawn(None, false).expect("completed task");
        scheduler
            .resources_mut()
            .acquire_new(crate::ResourceOwner::Task(completed), resource(61))
            .expect("completed resource");
        scheduler
            .complete(completed, Vec::new(), &[])
            .expect("complete");

        let failed = scheduler.spawn(None, false).expect("failed task");
        scheduler
            .resources_mut()
            .acquire_new(crate::ResourceOwner::Task(failed), resource(62))
            .expect("failed resource");
        scheduler
            .fail(
                failed,
                super::TaskFailure {
                    code: "SCRIPT_ERROR".to_owned(),
                    message: "boom".to_owned(),
                },
            )
            .expect("fail");

        let events = scheduler.process();
        assert!(events.contains(&SchedulerEvent::ResourceReleased(resource(61))));
        assert!(events.contains(&SchedulerEvent::ResourceReleased(resource(62))));
    }

    #[test]
    fn pending_join_wakes_and_transfers_the_outcome() {
        let (_, mut scheduler) = scheduler();
        let waiter = scheduler.spawn(None, false).expect("waiter");
        let worker = scheduler.spawn(Some(waiter), true).expect("worker");
        assert_eq!(scheduler.take_next_runnable(), Some(waiter));
        assert_eq!(scheduler.take_next_runnable(), Some(worker));
        assert_eq!(
            scheduler.join(waiter, worker).expect("pending join"),
            JoinResult::Pending
        );
        scheduler
            .complete(worker, b"result".to_vec(), &[])
            .expect("worker complete");
        assert_eq!(scheduler.take_next_runnable(), Some(waiter));
        let outcome = scheduler
            .claim_outcome(waiter, worker)
            .expect("claim outcome");
        assert_eq!(outcome.status, TaskState::Completed);
        assert_eq!(outcome.value, b"result");
    }

    #[test]
    fn sleep_exposes_exact_deadline_without_polling() {
        let (clock, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .sleep(task, Duration::from_millis(50))
            .expect("sleep");
        assert_eq!(
            scheduler.poll_state(),
            SchedulerPoll::Deadline(MonoTime::from_nanos(50_000_000))
        );
        assert!(scheduler.process().is_empty());
        clock.advance(Duration::from_millis(50));
        let events = scheduler.process();
        assert!(events
            .iter()
            .any(|event| matches!(event, SchedulerEvent::TimerReady { task: found, .. } if *found == task)));
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Runnable);
    }

    #[test]
    fn host_timeout_wakes_task_and_rejects_late_completion() {
        let (clock, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .wait_for_host(task, crate::RequestId(44), Duration::from_millis(25))
            .expect("host wait");
        assert_eq!(
            scheduler.poll_state(),
            SchedulerPoll::Deadline(MonoTime::from_nanos(25_000_000))
        );
        clock.advance(Duration::from_millis(25));
        assert!(scheduler.process().iter().any(|event| matches!(
            event,
            SchedulerEvent::HostTimedOut { request, task: found }
                if *request == crate::RequestId(44) && *found == task
        )));

        scheduler
            .handle()
            .submit_host_completion(HostCompletion {
                request_id: crate::RequestId(44),
                task,
                result: HostResult::Success(Vec::new()),
            })
            .expect("late completion queued");
        assert!(matches!(
            scheduler.process().as_slice(),
            [SchedulerEvent::LateHostResultDiscarded { .. }]
        ));
    }

    #[test]
    fn pause_freezes_business_timer_but_not_host_timeout() {
        let (clock, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .wait_for_host(task, crate::RequestId(45), Duration::from_millis(30))
            .expect("host wait");
        scheduler.pause().expect("pause");
        assert_eq!(
            scheduler.poll_state(),
            SchedulerPoll::PausedUntil(MonoTime::from_nanos(30_000_000))
        );
        clock.advance(Duration::from_millis(30));
        assert!(scheduler.process().iter().any(|event| matches!(
            event,
            SchedulerEvent::HostTimedOut { request, .. }
                if *request == crate::RequestId(45)
        )));
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Paused);
        scheduler.resume().expect("resume");
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Runnable);
    }

    #[test]
    fn interval_is_fixed_rate_and_coalesces_missed_ticks() {
        let (clock, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let timer = scheduler
            .every(task, Duration::from_millis(10))
            .expect("timer");
        clock.advance(Duration::from_millis(35));
        let first = scheduler.process();
        assert!(first.iter().any(|event| matches!(
            event,
            SchedulerEvent::TimerReady {
                missed_count: 2,
                ..
            }
        )));
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Runnable);
        clock.advance(Duration::from_millis(25));
        assert!(scheduler.process().is_empty());
        scheduler
            .complete_timer_callback(timer)
            .expect("finish callback");
        let consolidated = scheduler.process();
        assert!(consolidated.iter().any(|event| matches!(
            event,
            SchedulerEvent::TimerReady {
                missed_count: 2,
                ..
            }
        )));
        scheduler
            .complete_timer_callback(timer)
            .expect("finish consolidated callback");
        assert_eq!(scheduler.poll_state(), SchedulerPoll::Runnable);
    }

    #[test]
    fn pause_shifts_timer_deadline() {
        let (clock, mut scheduler) = scheduler();
        let task = scheduler.spawn(None, false).expect("task");
        let _ = scheduler.take_next_runnable();
        scheduler
            .sleep(task, Duration::from_millis(100))
            .expect("sleep");
        clock.advance(Duration::from_millis(40));
        scheduler.pause().expect("pause");
        clock.advance(Duration::from_secs(2));
        assert!(scheduler.process().is_empty());
        scheduler.resume().expect("resume");
        assert_eq!(
            scheduler.poll_state(),
            SchedulerPoll::Deadline(MonoTime::from_nanos(2_100_000_000))
        );
    }

    #[derive(Debug, Clone)]
    struct RecordingFinalizer(Arc<Mutex<Vec<&'static str>>>);

    impl TaskFinalizer for RecordingFinalizer {
        fn close_task(&mut self, _task: crate::TaskToken) -> Result<(), String> {
            self.0.lock().expect("log lock").push("close");
            Ok(())
        }
    }

    #[test]
    fn cancellation_closes_coroutine_before_releasing_resources() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let order = Arc::new(Mutex::new(Vec::new()));
        let finalizer = RecordingFinalizer(Arc::clone(&order));
        let mut scheduler = Scheduler::with_finalizer(clock, SchedulerConfig::default(), finalizer);
        let task = scheduler.spawn(None, false).expect("task");
        scheduler
            .resources_mut()
            .acquire_new(crate::ResourceOwner::Task(task), resource(4))
            .expect("resource");
        scheduler
            .handle()
            .request_cancel(task)
            .expect("cancel queued");
        let events = scheduler.process();
        assert_eq!(order.lock().expect("log lock").as_slice(), ["close"]);
        let release_position = events
            .iter()
            .position(|event| *event == SchedulerEvent::ResourceReleased(resource(4)))
            .expect("release event");
        let finished_position = events
            .iter()
            .position(|event| matches!(event, SchedulerEvent::TaskFinished { .. }))
            .expect("finish event");
        assert!(release_position < finished_position);
    }

    #[test]
    fn outcome_resource_moves_to_joiner_or_releases_when_discarded() {
        let (_, mut scheduler) = scheduler();
        let waiter = scheduler.spawn(None, false).expect("waiter");
        let worker = scheduler.spawn(Some(waiter), true).expect("worker");
        scheduler
            .resources_mut()
            .acquire_new(crate::ResourceOwner::Task(worker), resource(5))
            .expect("resource");
        scheduler
            .complete(worker, b"ok".to_vec(), &[resource(5)])
            .expect("worker completes");
        let JoinResult::Ready(outcome) = scheduler.join(waiter, worker).expect("join") else {
            panic!("outcome should be ready");
        };
        assert_eq!(outcome.resources, [resource(5)]);
        assert!(scheduler
            .resources()
            .holds(crate::ResourceOwner::Task(waiter), resource(5)));

        let unjoined = scheduler.spawn(None, false).expect("unjoined");
        scheduler
            .resources_mut()
            .acquire_new(crate::ResourceOwner::Task(unjoined), resource(6))
            .expect("resource");
        scheduler
            .complete(unjoined, Vec::new(), &[resource(6)])
            .expect("complete");
        scheduler.discard_outcome(unjoined).expect("discard");
        let events = scheduler.process();
        assert!(events.contains(&SchedulerEvent::ResourceReleased(resource(6))));
    }
}
