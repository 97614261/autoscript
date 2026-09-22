use script_api::RuntimeApiVersion;
use std::sync::{Arc, Mutex};

use automation_core::{
    CapturedFrameId, Color, ColorTolerance, FrameHandle, FrameMetadata, FramePool, FramePoolConfig,
    FrameVisionError, InputArbiterConfig, PixelPoint, SearchOptions, TemplateMatch,
    TemplateOptions,
};
use coordinate::{
    CoordinateSnapshot, CoordinateSpec, DisplayRect, FrameRotation, Insets, ScaleMode, Size,
};
use lua_runtime::{LuaRuntimeConfig, LuaScalar};
use runtime_executor::{
    DriveReport, ExecutorConfig, ExternalHost, ExternalHostQueue, RuntimeExecutor, RuntimeHost,
    VirtualHost,
};
use runtime_scheduler::{
    ManualClock, MonoTime, ResourceOwner, SchedulerConfig, SchedulerHandle, SchedulerPoll,
    TaskFailure, TaskState, TaskToken,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineState {
    Created,
    Starting,
    Running,
    Paused,
    Stopping,
    Stopped,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransitionError {
    InvalidTransition { from: EngineState, to: EngineState },
}

#[derive(Debug)]
pub struct EngineControl {
    state: EngineState,
    runtime_api: RuntimeApiVersion,
}

impl EngineControl {
    #[must_use]
    pub const fn new(runtime_api: RuntimeApiVersion) -> Self {
        Self {
            state: EngineState::Created,
            runtime_api,
        }
    }

    #[must_use]
    pub const fn state(&self) -> EngineState {
        self.state
    }

    #[must_use]
    pub const fn runtime_api(&self) -> RuntimeApiVersion {
        self.runtime_api
    }

    /// Applies a legal lifecycle transition.
    ///
    /// # Errors
    ///
    /// Returns [`TransitionError::InvalidTransition`] when `next` is not reachable from
    /// the current state.
    pub fn transition(&mut self, next: EngineState) -> Result<(), TransitionError> {
        let valid = matches!(
            (self.state, next),
            (EngineState::Created, EngineState::Starting)
                | (
                    EngineState::Starting,
                    EngineState::Running | EngineState::Failed
                )
                | (
                    EngineState::Running,
                    EngineState::Paused | EngineState::Stopping | EngineState::Failed
                )
                | (
                    EngineState::Paused,
                    EngineState::Running | EngineState::Stopping | EngineState::Failed
                )
                | (
                    EngineState::Stopping,
                    EngineState::Stopped | EngineState::Failed
                )
        );
        if !valid {
            return Err(TransitionError::InvalidTransition {
                from: self.state,
                to: next,
            });
        }
        self.state = next;
        Ok(())
    }
}

#[derive(Debug, Clone, Default)]
pub struct EngineSessionConfig {
    pub scheduler: SchedulerConfig,
    pub lua: LuaRuntimeConfig,
    pub executor: ExecutorConfig,
    pub input: InputArbiterConfig,
    pub frames: FramePoolConfig,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineSessionError {
    InvalidDisplaySize,
    AlreadyStarted,
    NotRunning,
    StaleCoordinateSnapshot,
    Frame(String),
    Dictionary(String),
    Executor(String),
    Transition(TransitionError),
}

impl From<TransitionError> for EngineSessionError {
    fn from(value: TransitionError) -> Self {
        Self::Transition(value)
    }
}

/// One isolated Runner execution session. It owns one Lua VM and never uses a process-global
/// engine lock.
#[derive(Debug)]
pub struct EngineSession {
    control: EngineControl,
    clock: ManualClock,
    executor: RuntimeExecutor<ManualClock, RuntimeHost>,
    frames: Arc<Mutex<FramePool>>,
    coordinates: CoordinateSnapshot,
    root_task: Option<TaskToken>,
    next: SchedulerPoll,
}

impl EngineSession {
    /// Creates a ready session using display geometry supplied by the Android host.
    ///
    /// # Errors
    ///
    /// Returns an error for zero display dimensions, invalid VM configuration or lifecycle
    /// initialization failure.
    pub fn new(
        width: u32,
        height: u32,
        config: EngineSessionConfig,
    ) -> Result<Self, EngineSessionError> {
        let frames = Arc::new(Mutex::new(FramePool::new(config.frames)));
        Self::new_with_host(
            width,
            height,
            config,
            RuntimeHost::Virtual(VirtualHost::new(width, height)),
            frames,
        )
    }

    /// Creates a session whose host calls are delivered through one bounded external queue.
    ///
    /// # Errors
    ///
    /// Returns the same geometry, VM, or lifecycle errors as [`Self::new`].
    pub fn new_external(
        width: u32,
        height: u32,
        config: EngineSessionConfig,
        host_queue: ExternalHostQueue,
        frames: Arc<Mutex<FramePool>>,
    ) -> Result<Self, EngineSessionError> {
        Self::new_with_host(
            width,
            height,
            config,
            RuntimeHost::External(ExternalHost::new(host_queue)),
            frames,
        )
    }

    fn new_with_host(
        width: u32,
        height: u32,
        config: EngineSessionConfig,
        host: RuntimeHost,
        frames: Arc<Mutex<FramePool>>,
    ) -> Result<Self, EngineSessionError> {
        if width == 0 || height == 0 {
            return Err(EngineSessionError::InvalidDisplaySize);
        }
        let mut control = EngineControl::new(RuntimeApiVersion { major: 1, minor: 5 });
        control.transition(EngineState::Starting)?;
        let clock = ManualClock::new(MonoTime::ZERO);
        let executor = RuntimeExecutor::new_with_frames(
            clock.clone(),
            config.scheduler,
            config.lua,
            config.executor,
            host,
            Arc::clone(&frames),
        )
        .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        control.transition(EngineState::Running)?;
        let display_size = Size {
            width: f32::from(
                u16::try_from(width).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
            height: f32::from(
                u16::try_from(height).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
        };
        let coordinates = CoordinateSnapshot::letterbox(1, display_size, display_size)
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        Ok(Self {
            control,
            clock,
            executor,
            frames,
            coordinates,
            root_task: None,
            next: SchedulerPoll::Idle,
        })
    }

    #[must_use]
    pub const fn state(&self) -> EngineState {
        self.control.state()
    }

    #[must_use]
    pub const fn next_wake(&self) -> SchedulerPoll {
        self.next
    }

    #[must_use]
    pub const fn root_task(&self) -> Option<TaskToken> {
        self.root_task
    }

    #[must_use]
    pub fn frame_pool(&self) -> Arc<Mutex<FramePool>> {
        Arc::clone(&self.frames)
    }

    /// Returns the number of task/outcome resource leases still owned by this session.
    ///
    /// This is intended for lifecycle diagnostics and end-to-end cleanup assertions; registered
    /// project resources are not counted until a running task leases them.
    #[must_use]
    pub fn active_resource_count(&self) -> usize {
        self.executor.resources().resource_count()
    }

    /// Returns the root task's retained terminal failure for Runner diagnostics.
    #[must_use]
    pub fn root_failure(&self) -> Option<&TaskFailure> {
        self.root_task
            .and_then(|task| self.executor.task_failure(task))
    }

    /// Returns script-authored diagnostic lines accumulated since the previous call.
    #[must_use]
    pub fn drain_script_logs(&mut self) -> Vec<String> {
        self.executor.drain_script_logs()
    }

    /// Registers one decoded project image before the entry task starts.
    ///
    /// # Errors
    ///
    /// Rejects registration after start, unsafe names, malformed pixels, or resource limits.
    pub fn register_template(
        &mut self,
        name: &str,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
    ) -> Result<(), EngineSessionError> {
        if self.root_task.is_some() {
            return Err(EngineSessionError::AlreadyStarted);
        }
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .register_template(name, metadata, pixels)
            .map_err(|error| EngineSessionError::Frame(format!("{error:?}")))
    }

    /// Registers one canonical project glyph dictionary before the entry task starts.
    ///
    /// # Errors
    ///
    /// Rejects registration after start, unsafe/duplicate paths, malformed bytes, or resource
    /// limits.
    pub fn register_dictionary(
        &mut self,
        path: &str,
        bytes: &[u8],
    ) -> Result<(), EngineSessionError> {
        if self.root_task.is_some() {
            return Err(EngineSessionError::AlreadyStarted);
        }
        self.executor
            .dictionary_store()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .register(path, bytes)
            .map_err(|error| EngineSessionError::Dictionary(format!("{error:?}")))
    }

    /// Publishes validated capture bytes into the bounded immutable frame pool.
    ///
    /// # Errors
    ///
    /// Returns a frame geometry, byte-length, or capacity error.
    pub fn publish_capture(
        &mut self,
        metadata: FrameMetadata,
        pixels: &[u8],
    ) -> Result<CapturedFrameId, EngineSessionError> {
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(metadata, pixels)
            .map_err(|error| EngineSessionError::Frame(format!("{error:?}")))
    }

    /// Pins a current capture to one scheduler Task.
    ///
    /// # Errors
    ///
    /// Returns a stale capture, duplicate lease, or resource-registry error.
    pub fn cache_capture(
        &mut self,
        task: TaskToken,
        capture: CapturedFrameId,
    ) -> Result<FrameHandle, EngineSessionError> {
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .cache(task, capture, self.executor.resources_mut())
            .map_err(|error| EngineSessionError::Frame(format!("{error:?}")))
    }

    /// Releases one Task's frame lease and immediately drops storage after the final lease.
    ///
    /// # Errors
    ///
    /// Returns an error for stale handles or leases owned by another Task.
    pub fn release_frame(
        &mut self,
        task: TaskToken,
        handle: FrameHandle,
    ) -> Result<(), EngineSessionError> {
        let final_lease = self
            .executor
            .resources_mut()
            .release_lease(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| EngineSessionError::Frame(format!("{error:?}")))?;
        if final_lease
            && !self
                .frames
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .release_resource(handle.resource_id)
        {
            return Err(EngineSessionError::Frame(
                "released frame storage was not found".to_owned(),
            ));
        }
        Ok(())
    }

    /// Runs bounded scalar color search directly over one leased immutable frame.
    ///
    /// # Errors
    ///
    /// Returns a lease, geometry, options, or comparison-budget error.
    pub fn find_color(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        target: Color,
        tolerance: ColorTolerance,
        options: SearchOptions,
    ) -> Result<Option<PixelPoint>, FrameVisionError> {
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_color(
                task,
                handle,
                self.executor.resources(),
                target,
                tolerance,
                options,
            )
    }

    /// Runs bounded template search directly over two leased immutable frames.
    ///
    /// # Errors
    ///
    /// Returns a lease, geometry, options, or comparison-budget error.
    pub fn find_template(
        &self,
        task: TaskToken,
        screen: FrameHandle,
        template: FrameHandle,
        options: TemplateOptions,
    ) -> Result<Option<TemplateMatch>, FrameVisionError> {
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_template(task, screen, template, self.executor.resources(), options)
    }

    #[must_use]
    pub const fn coordinate_snapshot(&self) -> CoordinateSnapshot {
        self.coordinates
    }

    /// Replaces geometry only with a newer immutable snapshot.
    ///
    /// # Errors
    ///
    /// Rejects a repeated or older snapshot identity.
    pub fn update_coordinate_snapshot(
        &mut self,
        snapshot: CoordinateSnapshot,
    ) -> Result<(), EngineSessionError> {
        if snapshot.snapshot_id <= self.coordinates.snapshot_id {
            return Err(EngineSessionError::StaleCoordinateSnapshot);
        }
        self.coordinates = snapshot;
        Ok(())
    }

    /// Applies the project's immutable design canvas to the current full display.
    ///
    /// # Errors
    ///
    /// Rejects invalid project dimensions, a stale snapshot identity, or invalid geometry.
    pub fn configure_project_geometry(
        &mut self,
        snapshot_id: u64,
        design_width: u32,
        design_height: u32,
        scale_mode: ScaleMode,
    ) -> Result<(), EngineSessionError> {
        if self.root_task.is_some() {
            return Err(EngineSessionError::AlreadyStarted);
        }
        let design_size = Size {
            width: f32::from(
                u16::try_from(design_width).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
            height: f32::from(
                u16::try_from(design_height).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
        };
        let display_size = self.coordinates.display_size;
        let snapshot = CoordinateSnapshot::new(CoordinateSpec {
            snapshot_id,
            design_size,
            display_size,
            window_bounds: DisplayRect {
                left: 0.0,
                top: 0.0,
                right: display_size.width,
                bottom: display_size.height,
            },
            content_insets: Insets::ZERO,
            frame_size: display_size,
            frame_rotation: FrameRotation::Degrees0,
            scale_mode,
        })
        .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.update_coordinate_snapshot(snapshot)
    }

    /// Updates full-display geometry while preserving the project's design size and scale mode.
    ///
    /// # Errors
    ///
    /// Rejects invalid dimensions or a stale snapshot identity.
    pub fn update_full_display(
        &mut self,
        snapshot_id: u64,
        width: u32,
        height: u32,
    ) -> Result<(), EngineSessionError> {
        let display_size = Size {
            width: f32::from(
                u16::try_from(width).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
            height: f32::from(
                u16::try_from(height).map_err(|_| EngineSessionError::InvalidDisplaySize)?,
            ),
        };
        let snapshot = CoordinateSnapshot::new(CoordinateSpec {
            snapshot_id,
            design_size: self.coordinates.design_size,
            display_size,
            window_bounds: DisplayRect {
                left: 0.0,
                top: 0.0,
                right: display_size.width,
                bottom: display_size.height,
            },
            content_insets: Insets::ZERO,
            frame_size: display_size,
            frame_rotation: FrameRotation::Degrees0,
            scale_mode: self.coordinates.scale_mode,
        })
        .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.update_coordinate_snapshot(snapshot)
    }

    /// Returns the thread-safe priority control handle. It contains no Lua values.
    #[must_use]
    pub fn scheduler_handle(&self) -> SchedulerHandle {
        self.executor.handle()
    }

    /// Starts one generated Lua entry module in this session.
    ///
    /// # Errors
    ///
    /// Returns an error if a script was already started or compilation fails.
    pub fn start(
        &mut self,
        source: &[u8],
        chunk_name: &str,
        capabilities: &[String],
    ) -> Result<TaskToken, EngineSessionError> {
        if self.root_task.is_some() {
            return Err(EngineSessionError::AlreadyStarted);
        }
        if self.control.state() != EngineState::Running {
            return Err(EngineSessionError::NotRunning);
        }
        self.executor
            .set_allowed_capabilities(capabilities)
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        let task = self
            .executor
            .start_entry_chunk(source, chunk_name)
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.root_task = Some(task);
        self.next = SchedulerPoll::Runnable;
        Ok(task)
    }

    /// Advances the injected Android boot clock and drains all immediately available work.
    ///
    /// # Errors
    ///
    /// Returns an execution error for malformed scripts or scheduler invariant failures.
    pub fn pump(&mut self, boot_time_nanos: u64) -> Result<DriveReport, EngineSessionError> {
        if !matches!(
            self.control.state(),
            EngineState::Running | EngineState::Paused
        ) {
            return Err(EngineSessionError::NotRunning);
        }
        self.clock.set(MonoTime::from_nanos(boot_time_nanos));
        let report = self
            .executor
            .drive()
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.next = report.next;
        self.update_terminal_state()?;
        Ok(report)
    }

    /// Pauses business execution without blocking stop control or host request timeouts.
    ///
    /// # Errors
    ///
    /// Returns an error when the session is not running or the scheduler cannot pause safely.
    pub fn pause(&mut self, boot_time_nanos: u64) -> Result<(), EngineSessionError> {
        if self.control.state() != EngineState::Running || self.root_task.is_none() {
            return Err(EngineSessionError::NotRunning);
        }
        self.clock.set(MonoTime::from_nanos(boot_time_nanos));
        self.next = self
            .executor
            .pause()
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.control.transition(EngineState::Paused)?;
        Ok(())
    }

    /// Resumes a paused session and makes newly runnable work visible to the Android pump.
    ///
    /// # Errors
    ///
    /// Returns an error when the session is not paused or the scheduler cannot resume safely.
    pub fn resume(&mut self, boot_time_nanos: u64) -> Result<(), EngineSessionError> {
        if self.control.state() != EngineState::Paused {
            return Err(EngineSessionError::NotRunning);
        }
        self.clock.set(MonoTime::from_nanos(boot_time_nanos));
        self.next = self
            .executor
            .resume()
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.control.transition(EngineState::Running)?;
        Ok(())
    }

    /// Stops input first, then atomically cancels and closes all Lua tasks.
    ///
    /// # Errors
    ///
    /// Returns an execution or lifecycle error if deterministic shutdown fails.
    pub fn stop(&mut self, boot_time_nanos: u64) -> Result<(), EngineSessionError> {
        if matches!(self.control.state(), EngineState::Stopped) {
            return Ok(());
        }
        if !matches!(
            self.control.state(),
            EngineState::Running | EngineState::Paused
        ) {
            return Err(EngineSessionError::NotRunning);
        }
        self.control.transition(EngineState::Stopping)?;
        self.clock.set(MonoTime::from_nanos(boot_time_nanos));
        self.executor.handle().request_stop();
        self.executor
            .drive()
            .map_err(|error| EngineSessionError::Executor(format!("{error:?}")))?;
        self.next = SchedulerPoll::Stopped;
        self.control.transition(EngineState::Stopped)?;
        Ok(())
    }

    #[must_use]
    pub fn diagnostic_global(&self, name: &str) -> Option<LuaScalar> {
        self.executor.lua().global_scalar(name).ok()
    }

    fn update_terminal_state(&mut self) -> Result<(), EngineSessionError> {
        let Some(task) = self.root_task else {
            return Ok(());
        };
        match self.executor.task_state(task) {
            Some(TaskState::Completed | TaskState::Cancelled) => {
                self.control.transition(EngineState::Stopping)?;
                self.control.transition(EngineState::Stopped)?;
                self.next = SchedulerPoll::Stopped;
            }
            Some(TaskState::Failed) => {
                self.control.transition(EngineState::Failed)?;
                self.next = SchedulerPoll::Stopped;
            }
            Some(TaskState::Running) | None => {}
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use coordinate::{DesignPoint, ScaleMode};
    use lua_runtime::LuaScalar;
    use script_api::RuntimeApiVersion;

    use super::{EngineControl, EngineSession, EngineSessionConfig, EngineState, TransitionError};

    #[test]
    fn rejects_running_before_starting() {
        let mut engine = EngineControl::new(RuntimeApiVersion { major: 1, minor: 0 });
        assert_eq!(
            engine.transition(EngineState::Running),
            Err(TransitionError::InvalidTransition {
                from: EngineState::Created,
                to: EngineState::Running,
            })
        );
    }

    #[test]
    fn supports_normal_lifecycle() {
        let mut engine = EngineControl::new(RuntimeApiVersion { major: 1, minor: 0 });
        for state in [
            EngineState::Starting,
            EngineState::Running,
            EngineState::Stopping,
            EngineState::Stopped,
        ] {
            engine
                .transition(state)
                .expect("valid lifecycle transition");
        }
    }

    #[test]
    fn engine_session_runs_generated_entry_to_completion() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .start(
                b"return function() local s=System.getScreenSize(); session_result=s.width+s.height end",
                "session",
                &["device.display".to_owned()],
            )
            .expect("start");
        session.pump(1).expect("pump");
        assert_eq!(session.state(), EngineState::Stopped);
        assert_eq!(
            session.diagnostic_global("session_result"),
            Some(LuaScalar::Integer(2000))
        );
    }

    #[test]
    fn engine_session_retains_root_failure_for_runner_diagnostics() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .start(
                b"return function() error('vision assertion failed', 0) end",
                "failure",
                &[],
            )
            .expect("start");

        session.pump(1).expect("pump");

        assert_eq!(session.state(), EngineState::Failed);
        let failure = session.root_failure().expect("root failure");
        assert_eq!(failure.code, "LUA_RUNTIME_ERROR");
        assert!(failure.message.contains("vision assertion failed"));
        assert_eq!(session.active_resource_count(), 0);
    }

    #[test]
    fn display_changes_replace_coordinates_only_with_a_newer_snapshot() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .update_full_display(2, 1280, 720)
            .expect("rotate display");
        let snapshot = session.coordinate_snapshot();
        assert_eq!(snapshot.snapshot_id, 2);
        assert!((snapshot.display_size.width - 1280.0).abs() < f32::EPSILON);
        assert_eq!(
            session.update_full_display(2, 720, 1280),
            Err(super::EngineSessionError::StaleCoordinateSnapshot)
        );
    }

    #[test]
    fn project_design_geometry_is_applied_before_display_updates() {
        let mut session =
            EngineSession::new(1080, 1920, EngineSessionConfig::default()).expect("session");
        session
            .configure_project_geometry(2, 720, 1280, ScaleMode::Letterbox)
            .expect("project geometry");
        let center = session
            .coordinate_snapshot()
            .design_to_display(DesignPoint { x: 360.0, y: 640.0 })
            .expect("center");
        assert!((center.x - 540.0).abs() < f32::EPSILON);
        assert!((center.y - 960.0).abs() < f32::EPSILON);
        session
            .update_full_display(3, 1920, 1080)
            .expect("display rotation");
        assert_eq!(
            session.coordinate_snapshot().design_size,
            coordinate::Size {
                width: 720.0,
                height: 1280.0,
            }
        );
    }

    #[test]
    fn stop_cancels_sleeping_session_without_waiting_for_deadline() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .start(
                b"return function() Task.sleep(60000) end",
                "sleep",
                &["core.task".to_owned()],
            )
            .expect("start");
        session.pump(10).expect("reach sleep");
        session.stop(11).expect("stop");
        assert_eq!(session.state(), EngineState::Stopped);
    }

    #[test]
    fn pause_and_resume_preserve_the_session_and_allow_priority_stop() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .start(
                b"return function() Task.sleep(60000) end",
                "pause",
                &["core.task".to_owned()],
            )
            .expect("start");
        session.pump(10).expect("reach sleep");
        session.pause(20).expect("pause");
        assert_eq!(session.state(), EngineState::Paused);
        session
            .pump(1_000_000)
            .expect("pump host timeouts while paused");
        assert_eq!(session.state(), EngineState::Paused);
        session.resume(1_000_010).expect("resume");
        assert_eq!(session.state(), EngineState::Running);
        session.stop(1_000_020).expect("priority stop");
        assert_eq!(session.state(), EngineState::Stopped);
    }

    #[test]
    fn undeclared_host_capability_fails_the_lua_task() {
        let mut session =
            EngineSession::new(720, 1280, EngineSessionConfig::default()).expect("session");
        session
            .start(b"return function() Task.sleep(1) end", "denied", &[])
            .expect("start");

        session.pump(1).expect("pump");

        assert_eq!(session.state(), EngineState::Failed);
        assert!(session
            .root_failure()
            .expect("capability failure")
            .message
            .contains("CAPABILITY_DENIED"));
    }
}
