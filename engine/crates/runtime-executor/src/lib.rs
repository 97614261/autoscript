//! Event-driven bridge between generated Lua, the scheduler and typed host requests.

use std::collections::{BTreeMap, BTreeSet, VecDeque};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use automation_core::{
    duo_dian_bi_se, duo_dian_zhao_se, get_rect_color_num, get_rgb_color, CaptureSeriesConfig,
    CaptureSeriesHandle, CapturedFrameId, Color, ColorTolerance, FrameHandle, FramePool,
    FramePoolConfig, FrameView, LegacySearchLimits, PatternSample, PixelPoint, PixelRect,
    SearchOptions, TemplateOptions,
};
use glyph_ocr::{
    recognize as recognize_glyphs, DictionaryHandle, DictionaryStore, DictionaryStoreConfig,
    GlyphOcrOptions,
};
use lua_runtime::{
    LuaInput, LuaRuntimeConfig, LuaRuntimeError, LuaScalar, LuaStep, LuaTaskRegistry,
};
use runtime_scheduler::{
    Clock, HostCompletion, HostResult, RequestId, ResourceId, ResourceKind, ResourceOwner,
    ScheduleError, Scheduler, SchedulerConfig, SchedulerEvent, SchedulerHandle, SchedulerPoll,
    SubmitError, TaskFailure, TaskResourceRegistry, TaskState, TaskToken, TimerId,
};
use script_api::{ApiContract, CancelMode, API_CONTRACTS};

const HOST_MARKER: &[u8] = b"__AUTOSCRIPT_HOST_V1";
const OP_LOG_WRITE: u32 = 1_100;
const OP_PROMPT_SHOW: u32 = 1_101;
const OP_PROMPT_TOAST: u32 = 1_102;
const OP_SYSTEM_GET_SCREEN_SIZE: u32 = 2_000;
const OP_SYSTEM_ELAPSED_REALTIME_MILLIS: u32 = 2_001;
const OP_TASK_SLEEP: u32 = 3_000;
const OP_INPUT_TAP: u32 = 4_000;
const OP_INPUT_SWIPE: u32 = 4_001;
const OP_INPUT_KEY_EVENT: u32 = 4_002;
const OP_INPUT_POINTER_DOWN: u32 = 4_003;
const OP_INPUT_POINTER_MOVE: u32 = 4_004;
const OP_INPUT_POINTER_UP: u32 = 4_005;
const OP_INPUT_TAP_SCREEN: u32 = 4_006;
const OP_INPUT_POINTER_DOWN_SCREEN: u32 = 4_007;
const OP_SCREEN_CAPTURE: u32 = 5_000;
const OP_SCREEN_CACHE: u32 = 5_001;
const OP_SCREEN_RELEASE: u32 = 5_002;
const OP_SCREEN_LOAD_IMAGE: u32 = 5_003;
const OP_SCREEN_CAPTURE_SERIES_FRAME: u32 = 5_004;
const OP_SCREEN_CROP: u32 = 5_005;
const OP_SCREEN_FIND_COLOR: u32 = 5_100;
const OP_SCREEN_FIND_IMAGE: u32 = 5_101;
const OP_SCREEN_GET_COLOR: u32 = 5_102;
const OP_SCREEN_COMPARE_COLOR: u32 = 5_103;
const OP_SCREEN_FIND_MULTI_COLOR: u32 = 5_104;
const OP_SCREEN_COUNT_COLOR: u32 = 5_105;
const OP_SCREEN_FIND_ALL_COLOR: u32 = 5_106;
const OP_SCREEN_FIND_IMAGES: u32 = 5_107;
const OP_LEGACY_DUO_DIAN_ZHAO_SE: u32 = 5_110;
const OP_LEGACY_DUO_DIAN_BI_SE: u32 = 5_111;
const OP_LEGACY_GET_RECT_COLOR_NUM: u32 = 5_112;
const OP_LEGACY_GET_RGB_COLOR: u32 = 5_113;
const OP_SCREEN_BEGIN_SERIES: u32 = 5_200;
const OP_SCREEN_CACHE_SERIES_FRAME: u32 = 5_201;
const OP_SCREEN_RELEASE_SERIES: u32 = 5_202;
const MAX_MULTI_COLOR_SAMPLES: usize = 64;
const MAX_LUA_COLOR_RESULTS: usize = 256;
const OP_OCR_LOAD_DICTIONARY: u32 = 6_000;
const OP_OCR_RELEASE_DICTIONARY: u32 = 6_001;
const OP_OCR_GLYPH: u32 = 6_100;
const MAX_PROJECT_CAPABILITIES: usize = 64;
const MAX_SCRIPT_LOG_ENTRIES: usize = 200;
const MAX_SCRIPT_LOG_BYTES: usize = 2_048;
const MAX_SCRIPT_PROMPT_ENTRIES: usize = 64;
const MAX_SCRIPT_PROMPT_BYTES: usize = 2_048;
const MAX_POPUP_STYLE_BYTES: usize = 1_024;

#[derive(Debug, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields, default)]
struct PopupStyle {
    #[serde(alias = "widthDp")]
    width_px: u16,
    #[serde(alias = "heightDp")]
    height_px: u16,
    #[serde(alias = "xPercent")]
    x_px: i32,
    #[serde(alias = "yPercent")]
    y_px: i32,
    background_color: String,
    text_color: String,
    #[serde(alias = "fontSp")]
    font_px: u8,
    #[serde(alias = "cornerDp")]
    corner_px: u16,
    duration_ms: u16,
    text_align: String,
}

impl Default for PopupStyle {
    fn default() -> Self {
        Self {
            width_px: 520,
            height_px: 144,
            x_px: -1,
            y_px: -1,
            background_color: "#B3000000".into(),
            text_color: "#FFFFFFFF".into(),
            font_px: 32,
            corner_px: 16,
            duration_ms: 3_000,
            text_align: "center".into(),
        }
    }
}

impl PopupStyle {
    fn valid(&self) -> bool {
        let color = |value: &str| {
            value.len() == 9
                && value.starts_with('#')
                && value[1..].bytes().all(|byte| byte.is_ascii_hexdigit())
        };
        (1..=2160).contains(&self.width_px)
            && (1..=1200).contains(&self.height_px)
            && (-1..=10_000).contains(&self.x_px)
            && (-1..=10_000).contains(&self.y_px)
            && (10..=160).contains(&self.font_px)
            && self.corner_px <= 200
            && (500..=30_000).contains(&self.duration_ms)
            && matches!(self.text_align.as_str(), "left" | "center" | "right")
            && color(&self.background_color)
            && color(&self.text_color)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct HostRequest {
    pub request_id: RequestId,
    pub task: TaskToken,
    pub opcode: u32,
    pub cancel_mode: CancelMode,
    pub timeout: Duration,
    pub args: Vec<LuaScalar>,
    pub native_vision: Option<NativeVisionRequest>,
}

/// Immutable owner-validated leases; no native pointers or arbitrary model paths.
#[derive(Debug, Clone, PartialEq)]
pub struct NativeVisionRequest {
    pub frame: FrameView,
    pub template: Option<FrameView>,
    pub region: PixelRect,
    pub minimum_score: u16,
}

pub trait HostCancelListener: Send + Sync + std::fmt::Debug {
    fn cancel(&self, request: RequestId, task: TaskToken);
}

pub trait HostBackend {
    /// Starts a request. Implementations must return quickly and publish completion through
    /// `completion`; they must not call back into the Lua VM.
    ///
    /// # Errors
    ///
    /// Returns a stable host diagnostic when dispatch cannot start.
    fn dispatch(
        &mut self,
        request: &HostRequest,
        completion: &SchedulerHandle,
    ) -> Result<(), String>;

    fn cancel(&mut self, request_id: RequestId, task: TaskToken, cancel_mode: CancelMode);
}

#[derive(Debug, Clone)]
pub enum ExternalHostEvent {
    Dispatch {
        request: HostRequest,
        completion: SchedulerHandle,
    },
    Cancel {
        request_id: RequestId,
        task: TaskToken,
        cancel_mode: CancelMode,
    },
    /// Wakes an attached backend worker for out-of-band lifecycle control without stopping the
    /// queue or consuming ordinary business capacity.
    Interrupted,
    /// Notifies the device backend that a task no longer owns its transient host resources.
    TaskFinished(TaskToken),
    Stop,
}

#[derive(Debug)]
struct ExternalQueueState {
    requests: VecDeque<(HostRequest, SchedulerHandle)>,
    cancellations: BTreeMap<RequestId, (TaskToken, CancelMode)>,
    finished_tasks: BTreeSet<TaskToken>,
    interrupted: bool,
    stopped: bool,
}

#[derive(Debug)]
struct ExternalQueueInner {
    state: Mutex<ExternalQueueState>,
    changed: Condvar,
    max_pending: usize,
    max_cancellations: usize,
    cancel_listener: Mutex<Option<Arc<dyn HostCancelListener>>>,
}

#[derive(Debug, Clone)]
pub struct ExternalHostQueue(Arc<ExternalQueueInner>);

impl ExternalHostQueue {
    pub fn set_cancel_listener(&self, listener: Arc<dyn HostCancelListener>) {
        *self
            .0
            .cancel_listener
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(listener);
    }
    /// Creates one bounded, event-driven host queue.
    ///
    /// # Errors
    ///
    /// Rejects zero queue or cancellation limits.
    pub fn new(max_pending: usize, max_cancellations: usize) -> Result<Self, String> {
        if max_pending == 0 || max_cancellations == 0 {
            return Err("external host queue limits must be non-zero".to_owned());
        }
        Ok(Self(Arc::new(ExternalQueueInner {
            state: Mutex::new(ExternalQueueState {
                requests: VecDeque::with_capacity(max_pending),
                cancellations: BTreeMap::new(),
                finished_tasks: BTreeSet::new(),
                interrupted: false,
                stopped: false,
            }),
            cancel_listener: Mutex::new(None),
            changed: Condvar::new(),
            max_pending,
            max_cancellations,
        })))
    }

    /// Waits without polling and always observes Stop, then cancellation, before business work.
    #[must_use]
    pub fn wait_next(&self) -> ExternalHostEvent {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        loop {
            if state.stopped {
                return ExternalHostEvent::Stop;
            }
            if state.interrupted {
                state.interrupted = false;
                return ExternalHostEvent::Interrupted;
            }
            if let Some((request_id, (task, cancel_mode))) = state.cancellations.pop_first() {
                return ExternalHostEvent::Cancel {
                    request_id,
                    task,
                    cancel_mode,
                };
            }
            if let Some(task) = state.finished_tasks.pop_first() {
                return ExternalHostEvent::TaskFinished(task);
            }
            if let Some((request, completion)) = state.requests.pop_front() {
                return ExternalHostEvent::Dispatch {
                    request,
                    completion,
                };
            }
            state = self
                .0
                .changed
                .wait(state)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
        }
    }

    /// Waits for the next host event until `timeout` expires.
    ///
    /// This keeps the same lifecycle priority as [`Self::wait_next`] and is intended for
    /// bounded callers such as integration tests and watchdog-controlled workers.
    #[must_use]
    pub fn wait_next_timeout(&self, timeout: Duration) -> Option<ExternalHostEvent> {
        let deadline = Instant::now().checked_add(timeout)?;
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        loop {
            if state.stopped {
                return Some(ExternalHostEvent::Stop);
            }
            if state.interrupted {
                state.interrupted = false;
                return Some(ExternalHostEvent::Interrupted);
            }
            if let Some((request_id, (task, cancel_mode))) = state.cancellations.pop_first() {
                return Some(ExternalHostEvent::Cancel {
                    request_id,
                    task,
                    cancel_mode,
                });
            }
            if let Some(task) = state.finished_tasks.pop_first() {
                return Some(ExternalHostEvent::TaskFinished(task));
            }
            if let Some((request, completion)) = state.requests.pop_front() {
                return Some(ExternalHostEvent::Dispatch {
                    request,
                    completion,
                });
            }
            let remaining = deadline.checked_duration_since(Instant::now())?;
            let (next_state, timeout_result) = self
                .0
                .changed
                .wait_timeout(state, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            state = next_state;
            if timeout_result.timed_out() {
                return None;
            }
        }
    }

    pub fn request_stop(&self) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.stopped = true;
        state.requests.clear();
        state.finished_tasks.clear();
        self.0.changed.notify_all();
    }

    /// Wakes the backend worker for lifecycle control without making the queue terminal.
    pub fn interrupt(&self) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !state.stopped {
            state.interrupted = true;
            self.0.changed.notify_all();
        }
    }

    /// Publishes task termination so an external host can release task-scoped resources.
    pub fn task_finished(&self, task: TaskToken) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !state.stopped {
            if state.finished_tasks.len() < self.0.max_pending
                || state.finished_tasks.contains(&task)
            {
                state.finished_tasks.insert(task);
            }
            self.0.changed.notify_one();
        }
    }

    /// Consumes cancellation for the exact request generation while a backend is dispatching a
    /// multi-command transaction. This keeps cancellation observable at command boundaries
    /// without polling the Lua VM or exposing queue internals.
    #[must_use]
    pub fn take_cancellation(&self, request_id: RequestId, task: TaskToken) -> Option<CancelMode> {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state
            .cancellations
            .get(&request_id)
            .is_some_and(|(candidate, _)| *candidate == task)
        {
            state
                .cancellations
                .remove(&request_id)
                .map(|(_, mode)| mode)
        } else {
            None
        }
    }

    /// Drops terminal-session work before the same external backend is attached to a fresh VM.
    ///
    /// # Errors
    ///
    /// A queue stopped for process shutdown cannot be reused.
    pub fn reset_pending(&self) -> Result<(), String> {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.stopped {
            return Err("external host queue is stopped".to_owned());
        }
        state.requests.clear();
        state.cancellations.clear();
        state.finished_tasks.clear();
        state.interrupted = false;
        Ok(())
    }

    /// Waits until an input delay expires or priority control changes; never consumes business work.
    pub fn wait_input_control(&self, request: RequestId, task: TaskToken, timeout: Duration) {
        let state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let (mut state, _elapsed) = self
            .0
            .changed
            .wait_timeout_while(state, timeout, |state| {
                !state.stopped
                    && !state.interrupted
                    && !state
                        .cancellations
                        .get(&request)
                        .is_some_and(|(owner, _)| *owner == task)
            })
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.interrupted = false;
    }

    fn dispatch(&self, request: HostRequest, completion: SchedulerHandle) -> Result<(), String> {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.stopped {
            return Err("external host queue is stopped".to_owned());
        }
        if state.requests.len() >= self.0.max_pending {
            return Err("external host queue is full".to_owned());
        }
        state.requests.push_back((request, completion));
        self.0.changed.notify_one();
        Ok(())
    }

    fn cancel(&self, request_id: RequestId, task: TaskToken, cancel_mode: CancelMode) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state
            .requests
            .retain(|(request, _)| request.request_id != request_id);
        if state.cancellations.len() >= self.0.max_cancellations
            && !state.cancellations.contains_key(&request_id)
        {
            state.stopped = true;
            state.requests.clear();
        } else {
            state.cancellations.insert(request_id, (task, cancel_mode));
        }
        self.0.changed.notify_all();
        drop(state);
        let listener = self
            .0
            .cancel_listener
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone();
        if let Some(listener) = listener {
            listener.cancel(request_id, task);
        }
    }
}

#[derive(Debug, Clone)]
pub struct ExternalHost {
    queue: ExternalHostQueue,
}

impl ExternalHost {
    #[must_use]
    pub const fn new(queue: ExternalHostQueue) -> Self {
        Self { queue }
    }
}

impl HostBackend for ExternalHost {
    fn dispatch(
        &mut self,
        request: &HostRequest,
        completion: &SchedulerHandle,
    ) -> Result<(), String> {
        self.queue.dispatch(request.clone(), completion.clone())
    }

    fn cancel(&mut self, request_id: RequestId, task: TaskToken, cancel_mode: CancelMode) {
        self.queue.cancel(request_id, task, cancel_mode);
    }
}

#[derive(Debug)]
pub enum RuntimeHost {
    Virtual(VirtualHost),
    External(ExternalHost),
}

impl HostBackend for RuntimeHost {
    fn dispatch(
        &mut self,
        request: &HostRequest,
        completion: &SchedulerHandle,
    ) -> Result<(), String> {
        match self {
            Self::Virtual(host) => host.dispatch(request, completion),
            Self::External(host) => host.dispatch(request, completion),
        }
    }

    fn cancel(&mut self, request_id: RequestId, task: TaskToken, cancel_mode: CancelMode) {
        match self {
            Self::Virtual(host) => host.cancel(request_id, task, cancel_mode),
            Self::External(host) => host.cancel(request_id, task, cancel_mode),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExecutorConfig {
    pub max_slices_per_drive: usize,
}

impl Default for ExecutorConfig {
    fn default() -> Self {
        Self {
            max_slices_per_drive: 1_024,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExecutorError {
    Lua(String),
    Schedule(ScheduleError),
    InvalidYield(String),
    UnknownOpcode(u32),
    MissingContract(u32),
    InvalidCapabilities(String),
    RequestIdExhausted,
}

impl From<LuaRuntimeError> for ExecutorError {
    fn from(value: LuaRuntimeError) -> Self {
        Self::Lua(value.to_string())
    }
}

impl From<ScheduleError> for ExecutorError {
    fn from(value: ScheduleError) -> Self {
        Self::Schedule(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DriveReport {
    pub events: Vec<SchedulerEvent>,
    pub slices: usize,
    pub next: SchedulerPoll,
}

#[derive(Debug, Clone, Copy)]
struct PendingHost {
    task: TaskToken,
    opcode: u32,
    cancel_mode: CancelMode,
}

#[derive(Debug, Clone, Copy)]
struct CallbackTimer {
    owner: TaskToken,
    callback: u64,
    active: Option<TaskToken>,
}

#[derive(Debug)]
pub struct RuntimeExecutor<C, H> {
    scheduler: Scheduler<C, LuaTaskRegistry>,
    backend: H,
    config: ExecutorConfig,
    next_request_id: u64,
    pending_host: BTreeMap<RequestId, PendingHost>,
    resume_inputs: BTreeMap<TaskToken, LuaInput>,
    frames: Arc<Mutex<FramePool>>,
    dictionaries: Arc<Mutex<DictionaryStore>>,
    script_logs: VecDeque<String>,
    script_prompts: VecDeque<String>,
    ui_values: BTreeMap<String, String>,
    ui_events: VecDeque<(String, String, String)>,
    ui_waiting: Option<RequestId>,
    ui_current_event: Option<(String, String)>,
    allowed_capabilities: Option<BTreeSet<String>>,
    capabilities_locked: bool,
    callback_timers: BTreeMap<TimerId, CallbackTimer>,
    callback_tasks: BTreeMap<TaskToken, (TaskToken, Option<TimerId>)>,
}

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    /// Creates one scheduler and one sandboxed Lua VM on the current owner thread.
    ///
    /// # Errors
    ///
    /// Returns a Lua initialization error when VM limits or the R0 API bootstrap are invalid.
    pub fn new(
        clock: C,
        scheduler_config: SchedulerConfig,
        lua_config: LuaRuntimeConfig,
        executor_config: ExecutorConfig,
        backend: H,
    ) -> Result<Self, ExecutorError> {
        Self::new_with_frames(
            clock,
            scheduler_config,
            lua_config,
            executor_config,
            backend,
            Arc::new(Mutex::new(FramePool::new(FramePoolConfig::default()))),
        )
    }

    /// Creates an executor using a frame pool shared with an asynchronous capture backend.
    ///
    /// # Errors
    ///
    /// Returns a Lua initialization error when VM limits or API bootstrap are invalid.
    pub fn new_with_frames(
        clock: C,
        scheduler_config: SchedulerConfig,
        lua_config: LuaRuntimeConfig,
        executor_config: ExecutorConfig,
        backend: H,
        frames: Arc<Mutex<FramePool>>,
    ) -> Result<Self, ExecutorError> {
        Self::new_with_resources(
            clock,
            scheduler_config,
            lua_config,
            executor_config,
            backend,
            frames,
            Arc::new(Mutex::new(DictionaryStore::new(
                DictionaryStoreConfig::default(),
            ))),
        )
    }

    /// Creates an executor using frame and dictionary stores owned by this Runner session.
    ///
    /// # Errors
    ///
    /// Returns a Lua initialization error when VM limits or API bootstrap are invalid.
    pub fn new_with_resources(
        clock: C,
        scheduler_config: SchedulerConfig,
        lua_config: LuaRuntimeConfig,
        executor_config: ExecutorConfig,
        backend: H,
        frames: Arc<Mutex<FramePool>>,
        dictionaries: Arc<Mutex<DictionaryStore>>,
    ) -> Result<Self, ExecutorError> {
        let lua = LuaTaskRegistry::with_config(lua_config)?;
        lua.install_api()?;
        Ok(Self {
            scheduler: Scheduler::with_finalizer(clock, scheduler_config, lua),
            backend,
            config: executor_config,
            next_request_id: 1,
            pending_host: BTreeMap::new(),
            resume_inputs: BTreeMap::new(),
            frames,
            dictionaries,
            script_logs: VecDeque::with_capacity(MAX_SCRIPT_LOG_ENTRIES),
            script_prompts: VecDeque::with_capacity(MAX_SCRIPT_PROMPT_ENTRIES),
            ui_values: BTreeMap::new(),
            ui_events: VecDeque::new(),
            ui_waiting: None,
            ui_current_event: None,
            allowed_capabilities: None,
            capabilities_locked: false,
            callback_timers: BTreeMap::new(),
            callback_tasks: BTreeMap::new(),
        })
    }

    /// Installs the immutable project capability set before the first Lua task is registered.
    ///
    /// # Errors
    ///
    /// Rejects late mutation, duplicate/invalid names, or more than 64 declarations.
    pub fn set_allowed_capabilities(
        &mut self,
        capabilities: &[String],
    ) -> Result<(), ExecutorError> {
        if self.capabilities_locked {
            return Err(ExecutorError::InvalidCapabilities(
                "capabilities are immutable after script registration".to_owned(),
            ));
        }
        if capabilities.len() > MAX_PROJECT_CAPABILITIES {
            return Err(ExecutorError::InvalidCapabilities(
                "more than 64 project capabilities".to_owned(),
            ));
        }
        let mut allowed = BTreeSet::new();
        for capability in capabilities {
            if !valid_capability(capability) || !allowed.insert(capability.clone()) {
                return Err(ExecutorError::InvalidCapabilities(format!(
                    "invalid or duplicate capability: {capability}"
                )));
            }
        }
        self.allowed_capabilities = Some(allowed);
        Ok(())
    }

    #[must_use]
    pub fn handle(&self) -> SchedulerHandle {
        self.scheduler.handle()
    }

    #[must_use]
    pub const fn backend(&self) -> &H {
        &self.backend
    }

    pub const fn backend_mut(&mut self) -> &mut H {
        &mut self.backend
    }

    #[must_use]
    pub const fn lua(&self) -> &LuaTaskRegistry {
        self.scheduler.finalizer()
    }

    #[must_use]
    pub const fn resources(&self) -> &TaskResourceRegistry {
        self.scheduler.resources()
    }

    pub const fn resources_mut(&mut self) -> &mut TaskResourceRegistry {
        self.scheduler.resources_mut()
    }

    #[must_use]
    pub fn frame_pool(&self) -> Arc<Mutex<FramePool>> {
        Arc::clone(&self.frames)
    }

    #[must_use]
    pub fn dictionary_store(&self) -> Arc<Mutex<DictionaryStore>> {
        Arc::clone(&self.dictionaries)
    }

    #[must_use]
    pub fn task_state(&self, task: TaskToken) -> Option<TaskState> {
        self.scheduler.task_state(task)
    }

    /// Returns the stable failure retained for a terminal task, if any.
    #[must_use]
    pub fn task_failure(&self, task: TaskToken) -> Option<&TaskFailure> {
        self.scheduler
            .task_outcome(task)
            .and_then(|outcome| outcome.error.as_ref())
    }

    /// Drains script-authored diagnostic lines without exposing the Lua VM across threads.
    #[must_use]
    pub fn drain_script_logs(&mut self) -> Vec<String> {
        self.script_logs.drain(..).collect()
    }

    /// Drains user-facing runtime prompt messages without exposing the Lua VM across threads.
    #[must_use]
    pub fn drain_script_prompts(&mut self) -> Vec<String> {
        self.script_prompts.drain(..).collect()
    }

    /// Registers a generated Lua module whose return value is the entry function.
    ///
    /// # Errors
    ///
    /// Returns a scheduler or Lua compilation error.
    pub fn start_entry_chunk(
        &mut self,
        source: &[u8],
        chunk_name: &str,
    ) -> Result<TaskToken, ExecutorError> {
        self.capabilities_locked = true;
        let task = self.scheduler.spawn(None, false)?;
        if let Err(error) = self
            .scheduler
            .finalizer_mut()
            .register_entry_chunk(task, source, chunk_name)
        {
            self.scheduler
                .handle()
                .request_cancel(task)
                .map_err(|submit| {
                    ExecutorError::Lua(format!("cleanup queue failed: {submit:?}"))
                })?;
            let _ = self.scheduler.process();
            return Err(error.into());
        }
        Ok(task)
    }

    /// Pauses business tasks and timers while leaving priority control and host timeouts active.
    ///
    /// # Errors
    ///
    /// Returns an error if the scheduler rejects the lifecycle transition.
    pub fn pause(&mut self) -> Result<SchedulerPoll, ExecutorError> {
        self.scheduler.pause()?;
        Ok(self.scheduler.poll_state())
    }

    /// Resumes business work and shifts paused timer deadlines by the exact pause duration.
    ///
    /// # Errors
    ///
    /// Returns an error if the scheduler rejects the lifecycle transition.
    pub fn resume(&mut self) -> Result<SchedulerPoll, ExecutorError> {
        self.lua().set_debug_step(false)?;
        self.scheduler.resume()?;
        Ok(self.scheduler.poll_state())
    }

    /// Resume until the next compiler checkpoint, keeping stop on its priority channel.
    ///
    /// # Errors
    /// Returns an error when VM assignment or scheduler resume fails.
    pub fn step(&mut self) -> Result<SchedulerPoll, ExecutorError> {
        self.lua().set_debug_step(true)?;
        self.scheduler.resume()?;
        Ok(self.scheduler.poll_state())
    }

    /// Drives all immediately available work up to the configured fairness boundary.
    /// Future work is represented by `DriveReport.next`; callers can block on `SchedulerHandle`
    /// until that exact deadline or an external wake event.
    ///
    /// # Errors
    ///
    /// Returns an error for malformed VM yields or internal scheduling failures.
    pub fn drive(&mut self) -> Result<DriveReport, ExecutorError> {
        let mut report_events = Vec::new();
        let mut slices = 0;
        loop {
            let events = self.scheduler.process();
            let event_progress = !events.is_empty();
            for event in &events {
                self.apply_event(event)?;
            }
            report_events.extend(events);

            let mut ran = false;
            while slices < self.config.max_slices_per_drive {
                let Some(task) = self.scheduler.take_next_runnable() else {
                    break;
                };
                ran = true;
                slices += 1;
                let input = self.resume_inputs.remove(&task).unwrap_or(LuaInput::None);
                match self.scheduler.finalizer_mut().resume(task, input) {
                    Ok(LuaStep::BudgetExhausted) => self.scheduler.yield_budget(task)?,
                    Ok(LuaStep::Yielded(values)) => self.apply_yield(task, &values)?,
                    Ok(LuaStep::Completed(values)) => {
                        self.scheduler
                            .complete(task, format!("{values:?}").into_bytes(), &[])?;
                    }
                    Err(error) => {
                        self.scheduler.fail(
                            task,
                            TaskFailure {
                                code: "LUA_RUNTIME_ERROR".to_owned(),
                                message: error.to_string(),
                            },
                        )?;
                    }
                }
            }
            if slices >= self.config.max_slices_per_drive || (!ran && !event_progress) {
                break;
            }
        }
        Ok(DriveReport {
            events: report_events,
            slices,
            next: self.scheduler.poll_state(),
        })
    }

    fn apply_yield(&mut self, task: TaskToken, values: &[LuaScalar]) -> Result<(), ExecutorError> {
        let Some(LuaScalar::Bytes(marker)) = values.first() else {
            return Err(ExecutorError::InvalidYield(
                "missing host marker".to_owned(),
            ));
        };
        if marker == b"__AUTOSCRIPT_DEBUG_CHECKPOINT_V1" && values.len() == 1 {
            self.scheduler.yield_budget(task)?;
            self.scheduler.pause()?;
            return Ok(());
        }
        if marker != HOST_MARKER {
            return Err(ExecutorError::InvalidYield(
                "unknown host marker".to_owned(),
            ));
        }
        let Some(LuaScalar::Integer(opcode)) = values.get(1) else {
            return Err(ExecutorError::InvalidYield("missing opcode".to_owned()));
        };
        let opcode = u32::try_from(*opcode)
            .map_err(|_| ExecutorError::InvalidYield("opcode out of range".to_owned()))?;
        if !self.authorize_opcode(task, opcode)? {
            let callback_index = match opcode {
                3001 => Some(2),
                3003 => Some(3),
                _ => None,
            };
            if let Some(LuaScalar::Integer(callback)) =
                callback_index.and_then(|index| values.get(index))
            {
                if let Ok(callback) = u64::try_from(*callback) {
                    self.lua().remove_callback(task, callback);
                }
            }
            return Ok(());
        }
        match opcode {
            1200..=1203 => self.apply_ui(task, opcode, &values[2..]),
            5108 | 6101 => self.dispatch_host(task, opcode, values[2..].to_vec()),
            OP_LOG_WRITE => self.apply_log(task, &values[2..]),
            OP_PROMPT_SHOW => self.apply_prompt(task, &values[2..]),
            OP_PROMPT_TOAST => self.apply_toast(task, &values[2..]),
            OP_TASK_SLEEP => self.apply_sleep(task, &values[2..]),
            3001..=3004 => self.apply_callback_operation(task, opcode, &values[2..]),
            OP_SYSTEM_GET_SCREEN_SIZE
            | OP_SYSTEM_ELAPSED_REALTIME_MILLIS
            | OP_INPUT_TAP
            | OP_INPUT_SWIPE
            | OP_INPUT_KEY_EVENT
            | OP_INPUT_POINTER_DOWN
            | OP_INPUT_POINTER_MOVE
            | OP_INPUT_POINTER_UP
            | OP_INPUT_TAP_SCREEN
            | OP_INPUT_POINTER_DOWN_SCREEN
            | OP_SCREEN_CAPTURE
            | OP_SCREEN_CAPTURE_SERIES_FRAME => {
                self.dispatch_host(task, opcode, values[2..].to_vec())
            }
            OP_SCREEN_CACHE
            | OP_SCREEN_RELEASE
            | OP_SCREEN_LOAD_IMAGE
            | OP_SCREEN_FIND_COLOR
            | OP_SCREEN_FIND_IMAGE
            | OP_SCREEN_FIND_IMAGES
            | OP_SCREEN_CROP
            | OP_SCREEN_GET_COLOR
            | OP_SCREEN_COMPARE_COLOR
            | OP_SCREEN_FIND_MULTI_COLOR
            | OP_SCREEN_COUNT_COLOR
            | OP_SCREEN_FIND_ALL_COLOR
            | OP_LEGACY_DUO_DIAN_ZHAO_SE
            | OP_LEGACY_DUO_DIAN_BI_SE
            | OP_LEGACY_GET_RECT_COLOR_NUM
            | OP_LEGACY_GET_RGB_COLOR
            | OP_SCREEN_BEGIN_SERIES
            | OP_SCREEN_CACHE_SERIES_FRAME
            | OP_SCREEN_RELEASE_SERIES => self.apply_frame_operation(task, opcode, &values[2..]),
            OP_OCR_LOAD_DICTIONARY | OP_OCR_RELEASE_DICTIONARY | OP_OCR_GLYPH => {
                self.apply_ocr_operation(task, opcode, &values[2..])
            }
            other => Err(ExecutorError::UnknownOpcode(other)),
        }
    }

    fn authorize_opcode(&mut self, task: TaskToken, opcode: u32) -> Result<bool, ExecutorError> {
        let Some(required) = required_capability(opcode) else {
            return Ok(true);
        };
        if self
            .allowed_capabilities
            .as_ref()
            .is_none_or(|allowed| allowed.contains(required))
        {
            return Ok(true);
        }
        let message = format!("project does not declare capability {required}");
        let input = if opcode == OP_TASK_SLEEP {
            LuaInput::Values(vec![
                LuaScalar::Boolean(false),
                LuaScalar::Bytes(b"CAPABILITY_DENIED".to_vec()),
                LuaScalar::Bytes(message.into_bytes()),
            ])
        } else {
            host_failure_input("CAPABILITY_DENIED", &message)
        };
        self.resume_inputs.insert(task, input);
        self.scheduler.yield_budget(task)?;
        Ok(false)
    }

    fn apply_ocr_operation(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let result = match opcode {
            OP_OCR_LOAD_DICTIONARY => self.load_dictionary(task, args),
            OP_OCR_RELEASE_DICTIONARY => self.release_dictionary(task, args),
            OP_OCR_GLYPH => self.recognize_glyph_text(task, args),
            _ => return Err(ExecutorError::UnknownOpcode(opcode)),
        };
        let input = result
            .unwrap_or_else(|message| host_failure_input("GLYPH_OCR_OPERATION_FAILED", &message));
        self.resume_inputs.insert(task, input);
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn load_dictionary(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [LuaScalar::Bytes(path)] = args else {
            return Err("Ocr.loadDictionary requires one dictionary resource path".to_owned());
        };
        let path = std::str::from_utf8(path)
            .map_err(|_| "Ocr.loadDictionary path must be UTF-8".to_owned())?;
        let dictionaries = Arc::clone(&self.dictionaries);
        let handle = dictionaries
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .lease(task, path, self.scheduler.resources_mut())
            .map_err(|error| format!("{error:?}"))?;
        let encoded = i64::try_from(handle.resource_id.get())
            .map_err(|_| "dictionary handle exceeds the Lua integer range".to_owned())?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(encoded),
        ]))
    }

    fn release_dictionary(
        &mut self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(encoded)] = args else {
            return Err("Ocr.releaseDictionary requires one dictionary handle".to_owned());
        };
        let handle = decode_dictionary_handle(*encoded)?;
        let final_lease = self
            .scheduler
            .resources_mut()
            .release_lease(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| format!("{error:?}"))?;
        if final_lease
            && !self
                .dictionaries
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .release_resource(handle.resource_id)
        {
            return Err("dictionary storage is missing".to_owned());
        }
        Ok(LuaInput::Boolean(true))
    }

    fn recognize_glyph_text(
        &self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [frame, dictionary, rgb, tolerance, similarity, left, top, right, bottom, space_gap] =
            integer_args::<10>(args)?;
        let frame = decode_frame_handle(frame)?;
        let dictionary = decode_dictionary_handle(dictionary)?;
        let rgb = u32::try_from(rgb).map_err(|_| "RGB is invalid".to_owned())?;
        let tolerance = u8::try_from(tolerance).map_err(|_| "tolerance is invalid".to_owned())?;
        let similarity =
            u16::try_from(similarity).map_err(|_| "similarity is invalid".to_owned())?;
        let space_gap = u16::try_from(space_gap).map_err(|_| "space gap is invalid".to_owned())?;
        let dictionary = self
            .dictionaries
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(task, dictionary, self.scheduler.resources())
            .map_err(|error| format!("{error:?}"))?;
        let frame = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .view(task, frame, self.scheduler.resources())
            .map_err(|error| format!("{error:?}"))?;
        let result = recognize_glyphs(
            frame.image_view().map_err(|error| format!("{error:?}"))?,
            &dictionary,
            GlyphOcrOptions {
                roi: search_options(left, top, right, bottom)?.roi,
                foreground: Color {
                    red: ((rgb >> 16) & 0xff) as u8,
                    green: ((rgb >> 8) & 0xff) as u8,
                    blue: (rgb & 0xff) as u8,
                    alpha: 255,
                },
                tolerance: ColorTolerance {
                    red: tolerance,
                    green: tolerance,
                    blue: tolerance,
                    alpha: 0,
                },
                minimum_similarity_permille: similarity,
                space_gap_columns: space_gap,
            },
        )
        .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Bytes(result.text.into_bytes()),
            LuaScalar::Integer(i64::from(result.coverage_permille)),
            LuaScalar::Integer(i64::from(result.average_score_permille)),
        ]))
    }

    fn apply_frame_operation(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let result = match opcode {
            OP_SCREEN_CACHE => self.cache_frame(task, args),
            OP_SCREEN_RELEASE => self.release_frame(task, args),
            OP_SCREEN_LOAD_IMAGE => self.load_image(task, args),
            OP_SCREEN_FIND_COLOR => self.find_color(task, args),
            OP_SCREEN_FIND_IMAGE => self.find_image(task, args),
            OP_SCREEN_FIND_IMAGES => self.find_images(task, args),
            OP_SCREEN_CROP => self.crop_frame(task, args),
            OP_SCREEN_GET_COLOR => self.get_color(task, args),
            OP_SCREEN_COMPARE_COLOR => self.compare_color(task, args),
            OP_SCREEN_FIND_MULTI_COLOR => self.find_multi_color(task, args),
            OP_SCREEN_COUNT_COLOR => self.count_color(task, args),
            OP_SCREEN_FIND_ALL_COLOR => self.find_all_color(task, args),
            OP_LEGACY_DUO_DIAN_ZHAO_SE => self.legacy_duo_dian_zhao_se(task, args),
            OP_LEGACY_DUO_DIAN_BI_SE => self.legacy_duo_dian_bi_se(task, args),
            OP_LEGACY_GET_RECT_COLOR_NUM => self.legacy_get_rect_color_num(task, args),
            OP_LEGACY_GET_RGB_COLOR => Self::legacy_get_rgb_color(args),
            OP_SCREEN_BEGIN_SERIES => self.begin_capture_series(task, args),
            OP_SCREEN_CACHE_SERIES_FRAME => self.cache_series_frame(task, args),
            OP_SCREEN_RELEASE_SERIES => self.release_capture_series(task, args),
            _ => return Err(ExecutorError::UnknownOpcode(opcode)),
        };
        let input =
            result.unwrap_or_else(|message| host_failure_input("FRAME_OPERATION_FAILED", &message));
        self.resume_inputs.insert(task, input);
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn cache_frame(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(capture_id)] = args else {
            return Err("Screen.cache requires one capture identifier".to_owned());
        };
        let capture_id = u64::try_from(*capture_id)
            .ok()
            .filter(|id| *id != 0)
            .map(CapturedFrameId)
            .ok_or_else(|| "capture identifier is invalid".to_owned())?;
        let frames = Arc::clone(&self.frames);
        let handle = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .cache(task, capture_id, self.scheduler.resources_mut())
            .map_err(|error| format!("{error:?}"))?;
        let encoded = i64::try_from(handle.resource_id.get())
            .map_err(|_| "frame handle exceeds the Lua integer range".to_owned())?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(encoded),
        ]))
    }

    fn load_image(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [LuaScalar::Bytes(name)] = args else {
            return Err("Screen.loadImage requires one image resource path".to_owned());
        };
        let name = std::str::from_utf8(name)
            .map_err(|_| "Screen.loadImage path must be UTF-8".to_owned())?;
        let frames = Arc::clone(&self.frames);
        let handle = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .lease_template(task, name, self.scheduler.resources_mut())
            .map_err(|error| format!("{error:?}"))?;
        let encoded = i64::try_from(handle.resource_id.get())
            .map_err(|_| "frame handle exceeds the Lua integer range".to_owned())?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(encoded),
        ]))
    }

    fn release_frame(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(encoded)] = args else {
            return Err("Screen.release requires one frame handle".to_owned());
        };
        let handle = decode_frame_handle(*encoded)?;
        let final_lease = self
            .scheduler
            .resources_mut()
            .release_lease(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| format!("{error:?}"))?;
        if final_lease
            && !self
                .frames
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .release_resource(handle.resource_id)
        {
            return Err("frame storage is missing".to_owned());
        }
        Ok(LuaInput::Boolean(true))
    }

    fn find_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, rgb, tolerance, left, top, right, bottom] = integer_args::<7>(args)?;
        let handle = decode_frame_handle(frame)?;
        let options = search_options(left, top, right, bottom)?;
        let target = decode_rgb(rgb)?;
        let tolerance = decode_tolerance(tolerance)?;
        let point = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_color(
                task,
                handle,
                self.scheduler.resources(),
                target,
                tolerance,
                options,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(point_input(point.map(|point| (point.x, point.y))))
    }

    fn find_image(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let (args, direction) = match args {
            [head @ .., LuaScalar::Integer(direction)] if args.len() == 9 => (head, *direction),
            _ if args.len() == 8 => (args, 0),
            _ => {
                return Err(
                    "Screen.findImage requires 8 integers and optional direction".to_owned(),
                )
            }
        };
        let [frame, template, tolerance, similarity, left, top, right, bottom] =
            integer_args::<8>(args)?;
        let screen = decode_frame_handle(frame)?;
        let template = decode_frame_handle(template)?;
        let tolerance = decode_tolerance(tolerance)?;
        let similarity =
            u16::try_from(similarity).map_err(|_| "similarity is invalid".to_owned())?;
        let started = Instant::now();
        let control = self.scheduler.handle();
        let match_result = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_template_controlled(
                task,
                screen,
                template,
                self.scheduler.resources(),
                TemplateOptions {
                    search: SearchOptions {
                        order: decode_search_direction(direction)?,
                        ..search_options(left, top, right, bottom)?
                    },
                    tolerance,
                    minimum_match_permille: similarity,
                    ignore_transparent_template_pixels: true,
                },
                &mut || {
                    control.cancellation_requested(task)
                        || started.elapsed() >= Duration::from_secs(5)
                },
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(point_input(
            match_result.map(|matched| (matched.origin.x, matched.origin.y)),
        ))
    }

    fn get_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, x, y] = integer_args::<3>(args)?;
        let color = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get_color(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources(),
                decode_point(x, y)?,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(i64::from(encode_rgb(color))),
        ]))
    }

    fn find_images(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(frame), LuaScalar::Bytes(paths_json), tail @ ..] = args else {
            return Err("Screen.findImages expects frame and JSON template paths".to_owned());
        };
        if paths_json.len() > 16_384 {
            return Err("template paths exceed byte limit".to_owned());
        }
        let paths: Vec<String> = serde_json::from_slice(paths_json)
            .map_err(|_| "invalid template paths JSON".to_owned())?;
        let [tolerance, similarity, left, top, right, bottom, direction] = integer_args::<7>(tail)?;
        let started = Instant::now();
        let control = self.scheduler.handle();
        let result = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_named_templates(
                task,
                decode_frame_handle(*frame)?,
                self.scheduler.resources(),
                &paths,
                TemplateOptions {
                    search: SearchOptions {
                        order: decode_search_direction(direction)?,
                        ..search_options(left, top, right, bottom)?
                    },
                    tolerance: decode_tolerance(tolerance)?,
                    minimum_match_permille: u16::try_from(similarity)
                        .map_err(|_| "invalid similarity")?,
                    ignore_transparent_template_pixels: true,
                },
                &mut || {
                    control.cancellation_requested(task)
                        || started.elapsed() >= Duration::from_secs(5)
                },
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(result.map_or_else(
            || LuaInput::Values(vec![LuaScalar::Boolean(true), LuaScalar::Nil]),
            |result| {
                LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Integer(i64::from(result.matched.origin.x)),
                    LuaScalar::Integer(i64::from(result.matched.origin.y)),
                    LuaScalar::Bytes(result.path.into_bytes()),
                    LuaScalar::Integer(i64::from(result.matched.matched_permille)),
                    LuaScalar::Integer(i64::from(result.width)),
                    LuaScalar::Integer(i64::from(result.height)),
                ])
            },
        ))
    }

    fn crop_frame(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, left, top, right, bottom] = integer_args::<5>(args)?;
        let roi = search_options(left, top, right, bottom)?.roi;
        let handle = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .crop(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources_mut(),
                roi,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(
                i64::try_from(handle.resource_id.get()).map_err(|_| "frame handle overflow")?,
            ),
        ]))
    }

    fn compare_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, x, y, rgb, tolerance] = integer_args::<5>(args)?;
        let matched = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .compare_color(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources(),
                decode_point(x, y)?,
                decode_rgb(rgb)?,
                decode_tolerance(tolerance)?,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Boolean(matched),
        ]))
    }

    fn find_multi_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        if args.len() < 8 {
            return Err("Screen.findMultiColor requires at least 8 integer arguments".to_owned());
        }
        let header = integer_args::<8>(&args[..8])?;
        let [frame, anchor_rgb, anchor_tolerance, left, top, right, bottom, sample_count] = header;
        let sample_count = usize::try_from(sample_count)
            .ok()
            .filter(|count| *count <= MAX_MULTI_COLOR_SAMPLES)
            .ok_or_else(|| "multi-color sample count is invalid".to_owned())?;
        let expected = 8_usize
            .checked_add(
                sample_count
                    .checked_mul(4)
                    .ok_or_else(|| "multi-color argument count overflow".to_owned())?,
            )
            .ok_or_else(|| "multi-color argument count overflow".to_owned())?;
        if args.len() != expected {
            return Err("multi-color argument count does not match sample count".to_owned());
        }
        let mut samples = Vec::with_capacity(sample_count + 1);
        samples.push(PatternSample {
            offset_x: 0,
            offset_y: 0,
            color: decode_rgb(anchor_rgb)?,
            tolerance: decode_tolerance(anchor_tolerance)?,
        });
        for values in args[8..].chunks_exact(4) {
            let values = integer_args::<4>(values)?;
            samples.push(PatternSample {
                offset_x: i32::try_from(values[0])
                    .map_err(|_| "multi-color x offset is invalid".to_owned())?,
                offset_y: i32::try_from(values[1])
                    .map_err(|_| "multi-color y offset is invalid".to_owned())?,
                color: decode_rgb(values[2])?,
                tolerance: decode_tolerance(values[3])?,
            });
        }
        let point = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_pattern(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources(),
                &samples,
                search_options(left, top, right, bottom)?,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(point_input(point.map(|point| (point.x, point.y))))
    }

    fn count_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, rgb, tolerance, left, top, right, bottom, limit] = integer_args::<8>(args)?;
        let limit = decode_result_limit(limit)?;
        let count = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .count_color(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources(),
                decode_rgb(rgb)?,
                decode_tolerance(tolerance)?,
                search_options(left, top, right, bottom)?,
                limit,
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(i64::try_from(count).expect("bounded result count")),
        ]))
    }

    fn find_all_color(&self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [frame, rgb, tolerance, left, top, right, bottom, limit] = integer_args::<8>(args)?;
        let points = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .find_all_colors(
                task,
                decode_frame_handle(frame)?,
                self.scheduler.resources(),
                decode_rgb(rgb)?,
                decode_tolerance(tolerance)?,
                search_options(left, top, right, bottom)?,
                decode_result_limit(limit)?,
            )
            .map_err(|error| format!("{error:?}"))?;
        let mut values = Vec::with_capacity(2 + points.len() * 2);
        values.push(LuaScalar::Boolean(true));
        values.push(LuaScalar::Integer(
            i64::try_from(points.len()).expect("bounded result count"),
        ));
        for point in points {
            values.push(LuaScalar::Integer(i64::from(point.x)));
            values.push(LuaScalar::Integer(i64::from(point.y)));
        }
        Ok(LuaInput::Values(values))
    }

    fn legacy_duo_dian_zhao_se(
        &self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(frame), LuaScalar::Integer(left), LuaScalar::Integer(top), LuaScalar::Integer(width), LuaScalar::Integer(height), LuaScalar::Bytes(parameters), LuaScalar::Integer(direction), LuaScalar::Integer(minimum_match_percent)] =
            args
        else {
            return Err("Legacy.duoDianZhaoSe requires frame, left, top, width, height, parameters, direction and percentage".to_owned());
        };
        let frame = self.legacy_frame_view(task, *frame)?;
        let points = duo_dian_zhao_se(
            frame.image_view().map_err(|error| format!("{error:?}"))?,
            legacy_coordinate(*left, "left")?,
            legacy_coordinate(*top, "top")?,
            legacy_coordinate(*width, "width")?,
            legacy_coordinate(*height, "height")?,
            legacy_text(parameters, "DuoDianZhaoSe parameters")?,
            i32::try_from(*direction).map_err(|_| "legacy direction is invalid".to_owned())?,
            legacy_percentage(*minimum_match_percent)?,
            LegacySearchLimits::default(),
        )
        .map_err(|error| format!("{error:?}"))?;
        points_input(&points)
    }

    fn legacy_duo_dian_bi_se(
        &self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(frame), LuaScalar::Bytes(parameters), LuaScalar::Integer(minimum_match_percent)] =
            args
        else {
            return Err("Legacy.duoDianBiSe requires frame, parameters and percentage".to_owned());
        };
        let frame = self.legacy_frame_view(task, *frame)?;
        let matched = duo_dian_bi_se(
            frame.image_view().map_err(|error| format!("{error:?}"))?,
            legacy_text(parameters, "DuoDianBiSe parameters")?,
            legacy_percentage(*minimum_match_percent)?,
            LegacySearchLimits::default().max_pixel_comparisons,
        )
        .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(i64::from(matched)),
        ]))
    }

    fn legacy_get_rect_color_num(
        &self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(frame), LuaScalar::Integer(left), LuaScalar::Integer(top), LuaScalar::Integer(width), LuaScalar::Integer(height), LuaScalar::Bytes(parameters)] =
            args
        else {
            return Err(
                "Legacy.getRectColorNum requires frame, left, top, width, height and parameters"
                    .to_owned(),
            );
        };
        let frame = self.legacy_frame_view(task, *frame)?;
        let count = get_rect_color_num(
            frame.image_view().map_err(|error| format!("{error:?}"))?,
            legacy_coordinate(*left, "left")?,
            legacy_coordinate(*top, "top")?,
            legacy_coordinate(*width, "width")?,
            legacy_coordinate(*height, "height")?,
            legacy_text(parameters, "GetRectColorNum parameters")?,
            LegacySearchLimits::default(),
        )
        .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(
                i64::try_from(count).map_err(|_| "legacy color count is too large".to_owned())?,
            ),
        ]))
    }

    fn legacy_get_rgb_color(args: &[LuaScalar]) -> Result<LuaInput, String> {
        let [red, green, blue] = integer_args::<3>(args)?;
        let rgb = get_rgb_color(
            i32::try_from(red).map_err(|_| "legacy red channel is invalid".to_owned())?,
            i32::try_from(green).map_err(|_| "legacy green channel is invalid".to_owned())?,
            i32::try_from(blue).map_err(|_| "legacy blue channel is invalid".to_owned())?,
        )
        .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(i64::from(rgb)),
        ]))
    }

    fn legacy_frame_view(
        &self,
        task: TaskToken,
        encoded: i64,
    ) -> Result<automation_core::FrameView, String> {
        self.frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .view(
                task,
                decode_frame_handle(encoded)?,
                self.scheduler.resources(),
            )
            .map_err(|error| format!("{error:?}"))
    }

    fn begin_capture_series(
        &mut self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [first_frame, max_frames, duration_ms, target_fps, allow_partial] =
            integer_args::<5>(args)?;
        let first_frame = decode_frame_handle(first_frame)?;
        let config = CaptureSeriesConfig {
            max_frames: usize::try_from(max_frames)
                .map_err(|_| "series maxFrames is invalid".to_owned())?,
            max_duration_nanos: u64::try_from(duration_ms)
                .ok()
                .and_then(|value| value.checked_mul(1_000_000))
                .ok_or_else(|| "series durationMs is invalid".to_owned())?,
            target_fps: u16::try_from(target_fps)
                .map_err(|_| "series targetFps is invalid".to_owned())?,
            allow_partial: match allow_partial {
                0 => false,
                1 => true,
                _ => return Err("series allowPartial is invalid".to_owned()),
            },
        };
        let frames = Arc::clone(&self.frames);
        let mut frames = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let timestamp_nanos = frames
            .view(task, first_frame, self.scheduler.resources())
            .map_err(|error| format!("{error:?}"))?
            .metadata
            .timestamp_nanos;
        let series = frames
            .begin_series(task, first_frame, self.scheduler.resources_mut(), config)
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(encode_series_handle(series)?),
            LuaScalar::Integer(
                i64::try_from(timestamp_nanos)
                    .map_err(|_| "series timestamp exceeds Lua integer range".to_owned())?,
            ),
            LuaScalar::Boolean(config.max_frames == 1),
        ]))
    }

    fn cache_series_frame(
        &mut self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [series, capture] = integer_args::<2>(args)?;
        let capture = u64::try_from(capture)
            .ok()
            .filter(|capture| *capture != 0)
            .map(CapturedFrameId)
            .ok_or_else(|| "series capture identifier is invalid".to_owned())?;
        let handle = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .cache_series_capture(
                task,
                decode_series_handle(series)?,
                capture,
                self.scheduler.resources_mut(),
            )
            .map_err(|error| format!("{error:?}"))?;
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(
                i64::try_from(handle.resource_id.get())
                    .map_err(|_| "frame handle exceeds Lua integer range".to_owned())?,
            ),
        ]))
    }

    fn release_capture_series(
        &mut self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        let [LuaScalar::Integer(encoded)] = args else {
            return Err("Screen.captureSeries release requires one series handle".to_owned());
        };
        let handle = decode_series_handle(*encoded)?;
        let final_lease = self
            .scheduler
            .resources_mut()
            .release_lease(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| format!("{error:?}"))?;
        if final_lease
            && !self
                .frames
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .release_series_resource(handle.resource_id)
        {
            return Err("capture series reservation is missing".to_owned());
        }
        Ok(LuaInput::Boolean(true))
    }

    fn apply_sleep(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<(), ExecutorError> {
        let [LuaScalar::Integer(milliseconds)] = args else {
            return Err(ExecutorError::InvalidYield(
                "Task.sleep requires one integer".to_owned(),
            ));
        };
        let milliseconds = u64::try_from(*milliseconds)
            .map_err(|_| ExecutorError::InvalidYield("negative sleep".to_owned()))?;
        if milliseconds == 0 {
            self.scheduler.yield_budget(task)?;
        } else {
            self.scheduler
                .sleep(task, Duration::from_millis(milliseconds))?;
        }
        Ok(())
    }

    /// Called only on the Lua owner thread. The Android host validates control/session identity.
    /// # Errors
    /// Rejects malformed values or configuration after the entry task starts.
    pub fn seed_ui_values(
        &mut self,
        values: BTreeMap<String, String>,
    ) -> Result<(), ExecutorError> {
        if self.capabilities_locked
            || values.len() > 128
            || values.iter().any(|(id, value)| {
                id.is_empty()
                    || id.len() > 64
                    || !id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
                    || value.len() > 8192
                    || value.contains('\0')
            })
        {
            return Err(ExecutorError::InvalidYield(
                "invalid initial UI values/state".into(),
            ));
        }
        self.ui_values = values;
        self.ui_events.clear();
        self.ui_current_event = None;
        self.ui_waiting = None;
        Ok(())
    }

    /// Called only on the Lua owner thread. The Android host validates control/session identity.
    /// # Errors
    /// Rejects malformed events or a full event queue.
    pub fn push_ui_event(
        &mut self,
        id: &str,
        event: &str,
        value: &str,
    ) -> Result<(), ExecutorError> {
        if id.is_empty()
            || id.len() > 64
            || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            || !matches!(event, "click" | "longClick" | "change" | "selection")
            || value.len() > 8192
            || value.contains('\0')
        {
            return Err(ExecutorError::InvalidYield("invalid UI event".into()));
        }
        if self.ui_values.len() >= 128 && !self.ui_values.contains_key(id) {
            return Err(ExecutorError::InvalidYield("UI value limit".into()));
        }
        let key = format!("{id}:{event}");
        if let Some(request) = self
            .ui_waiting
            .take()
            .filter(|request| self.pending_host.contains_key(request))
        {
            let pending = &self.pending_host[&request];
            let completion = HostCompletion {
                request_id: request,
                task: pending.task,
                result: HostResult::Success(key.into_bytes()),
            };
            self.scheduler
                .handle()
                .submit_host_completion(completion)
                .map_err(submit_error)?;
            self.ui_current_event = Some((id.to_owned(), value.to_owned()));
        } else {
            if self.ui_events.len() >= 64 {
                return Err(ExecutorError::InvalidYield("UI event queue full".into()));
            }
            self.ui_events
                .push_back((key, id.to_owned(), value.to_owned()));
        }
        self.ui_values.insert(id.to_owned(), value.to_owned());
        Ok(())
    }

    /// Synchronizes ordinary control edits without filling the plugin-event queue.
    /// # Errors
    /// Rejects malformed control identifiers, values or the 128-control limit.
    pub fn update_ui_value(&mut self, id: &str, value: &str) -> Result<(), ExecutorError> {
        if id.is_empty()
            || id.len() > 64
            || !id.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
            || value.len() > 8192
            || value.contains('\0')
            || (self.ui_values.len() >= 128 && !self.ui_values.contains_key(id))
        {
            return Err(ExecutorError::InvalidYield(
                "invalid UI control value".into(),
            ));
        }
        self.ui_values.insert(id.to_owned(), value.to_owned());
        Ok(())
    }

    fn apply_ui(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let fail = |message: &str| ExecutorError::InvalidYield(message.into());
        let string = |index: usize| -> Result<String, ExecutorError> {
            match args.get(index) {
                Some(LuaScalar::Bytes(bytes)) if bytes.len() <= 8192 && !bytes.contains(&0) => {
                    String::from_utf8(bytes.clone()).map_err(|_| fail("UI requires UTF-8"))
                }
                _ => Err(fail("invalid UI argument")),
            }
        };
        if opcode == 1200 {
            if !args.is_empty() || self.pending_host.values().any(|p| p.opcode == 1200) {
                return Err(fail("only one UI event consumer is allowed"));
            }
            self.ui_current_event = None;
            if let Some((event, id, value)) = self.ui_events.pop_front() {
                self.ui_current_event = Some((id, value));
                self.resume_inputs.insert(
                    task,
                    LuaInput::Values(vec![
                        LuaScalar::Boolean(true),
                        LuaScalar::Bytes(event.into_bytes()),
                    ]),
                );
                self.scheduler.yield_budget(task)?;
            } else {
                let request = self.allocate_request_id()?;
                self.scheduler
                    .wait_for_host(task, request, Duration::from_secs(1800))?;
                self.pending_host.insert(
                    request,
                    PendingHost {
                        task,
                        opcode,
                        cancel_mode: CancelMode::Cooperative,
                    },
                );
                self.ui_waiting = Some(request);
            }
            return Ok(());
        }
        let id = string(0)?;
        if id.is_empty()
            || id.len() > 64
            || !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
            || args.len()
                != match opcode {
                    1201 => 1,
                    1202 => 2,
                    _ => 3,
                }
        {
            return Err(fail("invalid UI control ID"));
        }
        match opcode {
            1201 => {
                let value = self
                    .ui_current_event
                    .as_ref()
                    .filter(|(control, _)| control == &id)
                    .map(|(_, value)| value.clone())
                    .or_else(|| self.ui_values.get(&id).cloned())
                    .unwrap_or_default();
                self.resume_inputs.insert(
                    task,
                    LuaInput::Values(vec![
                        LuaScalar::Boolean(true),
                        LuaScalar::Bytes(value.into_bytes()),
                    ]),
                );
            }
            1202 | 1203 => {
                let operation = if opcode == 1202 {
                    "value".to_owned()
                } else {
                    string(1)?
                };
                let value = string(if opcode == 1202 { 1 } else { 2 })?;
                if !matches!(
                    operation.as_str(),
                    "value"
                        | "text"
                        | "visible"
                        | "enabled"
                        | "items"
                        | "progress"
                        | "page"
                        | "show"
                        | "hide"
                        | "minimize"
                ) {
                    return Err(fail("unknown UI operation"));
                }
                if matches!(operation.as_str(), "visible" | "enabled")
                    && !matches!(value.as_str(), "true" | "false")
                {
                    return Err(fail("UI state requires true/false"));
                }
                if operation == "progress" && !value.parse::<u32>().is_ok_and(|v| v <= 1_000_000) {
                    return Err(fail("invalid UI progress"));
                }
                let new_items = if operation == "items" {
                    let items = value.split('\n').collect::<Vec<_>>();
                    let unique = items.iter().copied().collect::<BTreeSet<_>>();
                    if items.len() > 64
                        || unique.len() != items.len()
                        || items.iter().any(|item| {
                            item.is_empty()
                                || item.chars().count() > 128
                                || item.chars().any(char::is_control)
                        })
                    {
                        return Err(fail("invalid UI items"));
                    }
                    Some(items)
                } else {
                    None
                };
                if self.script_prompts.len() >= MAX_SCRIPT_PROMPT_ENTRIES {
                    return Err(fail("UI command queue full"));
                }
                if matches!(operation.as_str(), "value" | "text" | "progress") {
                    if self.ui_values.len() >= 128 && !self.ui_values.contains_key(&id) {
                        return Err(fail("UI control limit"));
                    }
                    self.ui_values.insert(id.clone(), value.clone());
                }
                if let Some(items) = new_items {
                    if self.ui_values.len() >= 128 && !self.ui_values.contains_key(&id) {
                        return Err(fail("UI control limit"));
                    }
                    if !self
                        .ui_values
                        .get(&id)
                        .is_some_and(|v| items.contains(&v.as_str()))
                    {
                        self.ui_values.insert(id.clone(), items[0].to_owned());
                    }
                }
                if matches!(operation.as_str(), "value" | "text" | "progress" | "items")
                    && self
                        .ui_current_event
                        .as_ref()
                        .is_some_and(|(control, _)| control == &id)
                {
                    self.ui_current_event = Some((
                        id.clone(),
                        self.ui_values.get(&id).cloned().unwrap_or_default(),
                    ));
                }
                self.script_prompts.push_back(
                    serde_json::json!({"kind":"ui","id":id,"operation":operation,"value":value})
                        .to_string(),
                );
                self.resume_inputs.insert(task, LuaInput::Boolean(true));
            }
            _ => unreachable!(),
        }
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn apply_log(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<(), ExecutorError> {
        let [LuaScalar::Integer(level), LuaScalar::Bytes(message)] = args else {
            return Err(ExecutorError::InvalidYield(
                "Log.write requires an integer level and UTF-8 message".to_owned(),
            ));
        };
        let level = match level {
            1 => "INFO",
            2 => "WARN",
            3 => "ERROR",
            _ => {
                return Err(ExecutorError::InvalidYield(
                    "Log.write level must be 1..3".to_owned(),
                ))
            }
        };
        if message.len() > MAX_SCRIPT_LOG_BYTES {
            return Err(ExecutorError::InvalidYield(
                "Log.write message exceeds 2048 UTF-8 bytes".to_owned(),
            ));
        }
        let message = std::str::from_utf8(message)
            .map_err(|_| ExecutorError::InvalidYield("Log.write message must be UTF-8".to_owned()))?
            .replace(['\r', '\n'], "\\n");
        if self.script_logs.len() >= MAX_SCRIPT_LOG_ENTRIES {
            self.script_logs.pop_front();
        }
        self.script_logs
            .push_back(format!("脚本/{level}: {message}"));
        self.resume_inputs.insert(task, LuaInput::Boolean(true));
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn apply_prompt(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<(), ExecutorError> {
        let [LuaScalar::Bytes(message)] = args else {
            return Err(ExecutorError::InvalidYield(
                "Prompt.show requires one UTF-8 message".to_owned(),
            ));
        };
        if message.len() > MAX_SCRIPT_PROMPT_BYTES {
            return Err(ExecutorError::InvalidYield(
                "Prompt.show message exceeds 2048 UTF-8 bytes".to_owned(),
            ));
        }
        let message = std::str::from_utf8(message)
            .map_err(|_| {
                ExecutorError::InvalidYield("Prompt.show message must be UTF-8".to_owned())
            })?
            .replace('\r', "");
        if self.script_prompts.len() >= MAX_SCRIPT_PROMPT_ENTRIES {
            self.script_prompts.pop_front();
        }
        self.script_prompts
            .push_back(serde_json::json!({"kind":"window","message":message}).to_string());
        self.resume_inputs.insert(task, LuaInput::Boolean(true));
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn apply_toast(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<(), ExecutorError> {
        let [LuaScalar::Bytes(message), LuaScalar::Bytes(style_json)] = args else {
            return Err(ExecutorError::InvalidYield(
                "Prompt.toast requires message and style JSON".to_owned(),
            ));
        };
        if message.len() > MAX_SCRIPT_PROMPT_BYTES || style_json.len() > MAX_POPUP_STYLE_BYTES {
            return Err(ExecutorError::InvalidYield(
                "Prompt.toast payload exceeds limit".to_owned(),
            ));
        }
        let message = std::str::from_utf8(message)
            .map_err(|_| {
                ExecutorError::InvalidYield("Prompt.toast message must be UTF-8".to_owned())
            })?
            .replace('\r', "");
        let style: PopupStyle = serde_json::from_slice(style_json).map_err(|_| {
            ExecutorError::InvalidYield("Prompt.toast style JSON is invalid".to_owned())
        })?;
        if !style.valid() {
            return Err(ExecutorError::InvalidYield(
                "Prompt.toast style is out of range".to_owned(),
            ));
        }
        if self.script_prompts.len() >= MAX_SCRIPT_PROMPT_ENTRIES {
            self.script_prompts.pop_front();
        }
        self.script_prompts.push_back(
            serde_json::json!({"kind":"toast","message":message,"style":style}).to_string(),
        );
        self.resume_inputs.insert(task, LuaInput::Boolean(true));
        self.scheduler.yield_budget(task)?;
        Ok(())
    }

    fn dispatch_host(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: Vec<LuaScalar>,
    ) -> Result<(), ExecutorError> {
        let native_vision = if matches!(opcode, 5108 | 6101) {
            Some(
                self.prepare_native_vision(task, opcode, &args)
                    .map_err(ExecutorError::InvalidYield)?,
            )
        } else {
            None
        };
        let (timeout, cancel_mode) = if opcode == OP_SCREEN_CAPTURE_SERIES_FRAME {
            (Duration::from_secs(3), CancelMode::Cooperative)
        } else {
            let contract = contract(opcode).ok_or(ExecutorError::MissingContract(opcode))?;
            (
                Duration::from_millis(
                    contract
                        .timeout_ms
                        .ok_or(ExecutorError::MissingContract(opcode))?,
                ),
                contract.cancel_mode,
            )
        };
        let request_id = self.allocate_request_id()?;
        self.scheduler.wait_for_host(task, request_id, timeout)?;
        let request = HostRequest {
            request_id,
            task,
            opcode,
            cancel_mode,
            timeout,
            args,
            native_vision,
        };
        self.pending_host.insert(
            request_id,
            PendingHost {
                task,
                opcode,
                cancel_mode,
            },
        );
        if let Err(message) = self.backend.dispatch(&request, &self.scheduler.handle()) {
            self.scheduler
                .handle()
                .submit_host_completion(HostCompletion {
                    request_id,
                    task,
                    result: HostResult::Failure {
                        code: "HOST_DISPATCH_FAILED".to_owned(),
                        message,
                    },
                })
                .map_err(submit_error)?;
        }
        Ok(())
    }

    fn prepare_native_vision(
        &self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<NativeVisionRequest, String> {
        let (handle, template, score, left, top, right, bottom) = if opcode == 5108 {
            let [handle, template, score, left, top, right, bottom] = integer_args::<7>(args)?;
            (handle, Some(template), score, left, top, right, bottom)
        } else {
            let [handle, left, top, right, bottom, score] = integer_args::<6>(args)?;
            (handle, None, score, left, top, right, bottom)
        };
        let minimum_score = u16::try_from(score)
            .ok()
            .filter(|score| *score <= 1000)
            .ok_or("score must be 0..1000")?;
        let pool = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let frame = pool
            .view(
                task,
                decode_frame_handle(handle)?,
                self.scheduler.resources(),
            )
            .map_err(|error| format!("{error:?}"))?;
        let template = template
            .map(|handle| {
                pool.view(
                    task,
                    decode_frame_handle(handle)?,
                    self.scheduler.resources(),
                )
                .map_err(|error| format!("{error:?}"))
            })
            .transpose()?;
        let region = search_options(left, top, right, bottom)?.roi;
        if region.right > frame.metadata.width
            || region.bottom > frame.metadata.height
            || u64::from(region.right - region.left) * u64::from(region.bottom - region.top)
                > 4_194_304
        {
            return Err("native vision ROI exceeds frame or 4M pixel budget".into());
        }
        if let Some(template) = &template {
            if template.metadata.width > region.right - region.left
                || template.metadata.height > region.bottom - region.top
                || u64::from(template.metadata.width) * u64::from(template.metadata.height)
                    > 262_144
            {
                return Err("gray template exceeds ROI or 256K pixel budget".into());
            }
        } else if (region.right - region.left) as f64 / f64::from(region.bottom - region.top) > 20.0
        {
            return Err(
                "OCR single line aspect ratio exceeds 20; split into smaller regions".into(),
            );
        }
        Ok(NativeVisionRequest {
            frame,
            template,
            region,
            minimum_score,
        })
    }

    fn start_callback(
        &mut self,
        owner: TaskToken,
        callback: u64,
        timer: Option<TimerId>,
    ) -> Result<TaskToken, ExecutorError> {
        let child = self.scheduler.spawn(Some(owner), false)?;
        self.callback_tasks.insert(child, (owner, timer));
        if let Err(error) = self.scheduler.finalizer_mut().register_callback_task(
            child,
            owner,
            callback,
            timer.is_some(),
        ) {
            self.scheduler
                .handle()
                .request_cancel(child)
                .map_err(submit_error)?;
            return Err(error.into());
        }
        Ok(child)
    }

    fn stop_callback_timer(&mut self, timer: TimerId) -> Result<(), ExecutorError> {
        if let Some(record) = self.callback_timers.remove(&timer) {
            self.scheduler.cancel_timer(timer);
            self.lua().remove_callback(record.owner, record.callback);
            if let Some(active) = record.active {
                self.scheduler
                    .handle()
                    .request_cancel(active)
                    .map_err(submit_error)?;
            }
        }
        Ok(())
    }

    fn apply_callback_operation(
        &mut self,
        owner: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let result = (|| -> Result<LuaScalar, ExecutorError> {
            match (opcode, args) {
                (3001, [LuaScalar::Integer(callback)]) => {
                    let callback = self.checked_callback_id(owner, *callback)?;
                    let result = self.start_callback(owner, callback, None);
                    if result.is_err() {
                        self.lua().remove_callback(owner, callback);
                    }
                    let child = result?;
                    match i64::try_from(child.id.get()) {
                        Ok(id) => Ok(LuaScalar::Integer(id)),
                        Err(_) => {
                            self.scheduler
                                .handle()
                                .request_cancel(child)
                                .map_err(submit_error)?;
                            Err(ExecutorError::InvalidYield(
                                "task IDs exhausted Lua integer range".into(),
                            ))
                        }
                    }
                }
                (3002, [LuaScalar::Integer(id)]) if *id > 0 => {
                    let id = u64::try_from(*id)
                        .map_err(|_| ExecutorError::InvalidYield("invalid task ID".into()))?;
                    let target = self.callback_tasks.iter().find_map(|(task, (parent, _))| {
                        (task.id.get() == id && *parent == owner).then_some(*task)
                    });
                    if let Some(target) = target {
                        self.scheduler
                            .handle()
                            .request_cancel(target)
                            .map_err(submit_error)?;
                    }
                    Ok(LuaScalar::Boolean(target.is_some()))
                }
                (3003, [LuaScalar::Integer(period), LuaScalar::Integer(callback)])
                    if (10..=60_000).contains(period) =>
                {
                    let callback = self.checked_callback_id(owner, *callback)?;
                    let milliseconds = u64::try_from(*period)
                        .map_err(|_| ExecutorError::InvalidYield("invalid timer period".into()))?;
                    match self
                        .scheduler
                        .every(owner, Duration::from_millis(milliseconds))
                    {
                        Ok(timer) => {
                            let Ok(id) = i64::try_from(timer.get()) else {
                                self.scheduler.cancel_timer(timer);
                                self.lua().remove_callback(owner, callback);
                                return Err(ExecutorError::InvalidYield(
                                    "timer IDs exhausted Lua integer range".into(),
                                ));
                            };
                            self.callback_timers.insert(
                                timer,
                                CallbackTimer {
                                    owner,
                                    callback,
                                    active: None,
                                },
                            );
                            Ok(LuaScalar::Integer(id))
                        }
                        Err(error) => {
                            self.lua().remove_callback(owner, callback);
                            Err(error.into())
                        }
                    }
                }
                (3004, [LuaScalar::Integer(id)]) if *id > 0 => {
                    let timer = TimerId(
                        u64::try_from(*id)
                            .map_err(|_| ExecutorError::InvalidYield("invalid timer ID".into()))?,
                    );
                    let belongs_to_owner = self
                        .callback_timers
                        .get(&timer)
                        .is_some_and(|record| record.owner == owner);
                    if belongs_to_owner {
                        self.stop_callback_timer(timer)?;
                    }
                    Ok(LuaScalar::Boolean(belongs_to_owner))
                }
                _ => Err(ExecutorError::InvalidYield(
                    "invalid or foreign callback request".into(),
                )),
            }
        })();
        let input = match result {
            Ok(value) => LuaInput::Values(vec![LuaScalar::Boolean(true), value]),
            Err(error) => LuaInput::Values(vec![
                LuaScalar::Boolean(false),
                LuaScalar::Nil,
                LuaScalar::Nil,
                LuaScalar::Bytes(b"TASK_CALLBACK_ERROR".to_vec()),
                LuaScalar::Bytes(format!("{error:?}").into_bytes()),
            ]),
        };
        self.resume_inputs.insert(owner, input);
        self.scheduler.yield_budget(owner)?;
        Ok(())
    }

    fn checked_callback_id(&self, owner: TaskToken, value: i64) -> Result<u64, ExecutorError> {
        u64::try_from(value)
            .ok()
            .filter(|id| *id != 0 && self.lua().owns_callback(owner, *id))
            .ok_or_else(|| ExecutorError::InvalidYield("invalid or foreign callback ID".into()))
    }

    fn apply_event(&mut self, event: &SchedulerEvent) -> Result<(), ExecutorError> {
        match event {
            SchedulerEvent::HostReady(completion) => {
                let pending = self
                    .pending_host
                    .remove(&completion.request_id)
                    .ok_or_else(|| {
                        ExecutorError::InvalidYield(
                            "host completion has no pending request".to_owned(),
                        )
                    })?;
                if pending.task != completion.task {
                    return Err(ExecutorError::InvalidYield(
                        "host completion task mismatch".to_owned(),
                    ));
                }
                let input = host_resume_input(pending.opcode, &completion.result)?;
                self.resume_inputs.insert(completion.task, input);
            }
            SchedulerEvent::HostTimedOut { request, task } => {
                if let Some(pending) = self.pending_host.remove(request) {
                    self.backend.cancel(*request, *task, pending.cancel_mode);
                }
                self.resume_inputs.insert(
                    *task,
                    LuaInput::Values(vec![
                        LuaScalar::Boolean(false),
                        LuaScalar::Nil,
                        LuaScalar::Nil,
                        LuaScalar::Bytes(b"HOST_TIMEOUT".to_vec()),
                        LuaScalar::Bytes(b"host request timed out".to_vec()),
                    ]),
                );
            }
            SchedulerEvent::HostCancelled { request, task } => {
                if let Some(pending) = self.pending_host.remove(request) {
                    self.backend.cancel(*request, *task, pending.cancel_mode);
                }
                self.resume_inputs.remove(task);
            }
            SchedulerEvent::TimerReady {
                timer,
                task,
                interval,
                ..
            } => {
                if let Some(record) = self.callback_timers.get(timer).copied() {
                    if record.active.is_none()
                        && self.scheduler.task_state(record.owner) == Some(TaskState::Running)
                    {
                        match self.start_callback(record.owner, record.callback, Some(*timer)) {
                            Ok(child) => {
                                if let Some(record) = self.callback_timers.get_mut(timer) {
                                    record.active = Some(child);
                                }
                            }
                            Err(error) => {
                                self.stop_callback_timer(*timer)?;
                                self.scheduler.fail(
                                    record.owner,
                                    TaskFailure {
                                        code: "TIMER_START_FAILED".into(),
                                        message: format!("{error:?}"),
                                    },
                                )?;
                            }
                        }
                    }
                } else if !interval && self.scheduler.task_state(*task) == Some(TaskState::Running)
                {
                    self.resume_inputs.insert(*task, LuaInput::None);
                }
            }
            SchedulerEvent::TaskFinished { task, state } => {
                self.resume_inputs.remove(task);
                if let Some((owner, timer)) = self.callback_tasks.remove(task) {
                    if let Some(timer) = timer {
                        if let Some(record) = self.callback_timers.get_mut(&timer) {
                            record.active = None;
                        }
                        if *state == TaskState::Completed
                            && self.scheduler.task_state(owner) == Some(TaskState::Running)
                            && self.callback_timers.contains_key(&timer)
                        {
                            self.scheduler.complete_timer_callback(timer)?;
                        } else {
                            self.stop_callback_timer(timer)?;
                        }
                    }
                    if *state == TaskState::Failed
                        && self.scheduler.task_state(owner) == Some(TaskState::Running)
                    {
                        let error = self
                            .scheduler
                            .task_outcome(*task)
                            .and_then(|outcome| outcome.error.clone())
                            .unwrap_or(TaskFailure {
                                code: "CALLBACK_FAILED".into(),
                                message: "callback failed".into(),
                            });
                        self.scheduler.fail(owner, error)?;
                    }
                    self.scheduler.discard_outcome(*task)?;
                }
                let timers = self
                    .callback_timers
                    .iter()
                    .filter_map(|(timer, record)| (record.owner == *task).then_some(*timer))
                    .collect::<Vec<_>>();
                for timer in timers {
                    self.stop_callback_timer(timer)?;
                }
            }
            SchedulerEvent::ResourceReleased(resource) => match resource.kind() {
                ResourceKind::Frame => {
                    self.frames
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner)
                        .release_resource(*resource);
                }
                ResourceKind::CaptureSeries => {
                    self.frames
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner)
                        .release_series_resource(*resource);
                }
                ResourceKind::GlyphDictionary => {
                    self.dictionaries
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner)
                        .release_resource(*resource);
                }
                _ => {}
            },
            SchedulerEvent::FinalizerFailed { .. }
            | SchedulerEvent::LateHostResultDiscarded { .. } => {}
        }
        Ok(())
    }

    fn allocate_request_id(&mut self) -> Result<RequestId, ExecutorError> {
        let id = self.next_request_id;
        self.next_request_id = self
            .next_request_id
            .checked_add(1)
            .ok_or(ExecutorError::RequestIdExhausted)?;
        Ok(RequestId(id))
    }
}

fn contract(opcode: u32) -> Option<&'static ApiContract> {
    API_CONTRACTS
        .binary_search_by_key(&opcode, |contract| contract.opcode)
        .ok()
        .map(|index| &API_CONTRACTS[index])
}

fn required_capability(opcode: u32) -> Option<&'static str> {
    match opcode {
        OP_SCREEN_CAPTURE_SERIES_FRAME
        | OP_SCREEN_CACHE_SERIES_FRAME
        | OP_SCREEN_RELEASE_SERIES => {
            contract(OP_SCREEN_BEGIN_SERIES).map(|contract| contract.capability)
        }
        _ => contract(opcode).map(|contract| contract.capability),
    }
}

fn valid_capability(value: &str) -> bool {
    if value.len() > 128 {
        return false;
    }
    let mut parts = value.split('.');
    let valid_part = |part: &str| {
        part.bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase())
            && part
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
    };
    matches!((parts.next(), parts.next()), (Some(first), Some(second)) if
        valid_part(first) && valid_part(second) && parts.all(valid_part))
}

fn integer_args<const N: usize>(args: &[LuaScalar]) -> Result<[i64; N], String> {
    if args.len() != N {
        return Err(format!("expected {N} integer arguments"));
    }
    let mut values = [0_i64; N];
    for (index, argument) in args.iter().enumerate() {
        let LuaScalar::Integer(value) = argument else {
            return Err(format!("argument {} must be an integer", index + 1));
        };
        values[index] = *value;
    }
    Ok(values)
}

fn decode_rgb(value: i64) -> Result<Color, String> {
    let rgb = u32::try_from(value)
        .ok()
        .filter(|rgb| *rgb <= 0x00ff_ffff)
        .ok_or_else(|| "RGB is invalid".to_owned())?;
    Ok(Color {
        red: ((rgb >> 16) & 0xff) as u8,
        green: ((rgb >> 8) & 0xff) as u8,
        blue: (rgb & 0xff) as u8,
        alpha: 255,
    })
}

const fn encode_rgb(color: Color) -> u32 {
    (color.red as u32) << 16 | (color.green as u32) << 8 | color.blue as u32
}

fn decode_tolerance(value: i64) -> Result<ColorTolerance, String> {
    let tolerance = u8::try_from(value).map_err(|_| "tolerance is invalid".to_owned())?;
    Ok(ColorTolerance {
        red: tolerance,
        green: tolerance,
        blue: tolerance,
        alpha: 0,
    })
}

fn decode_point(x: i64, y: i64) -> Result<PixelPoint, String> {
    Ok(PixelPoint {
        x: u32::try_from(x).map_err(|_| "x coordinate is invalid".to_owned())?,
        y: u32::try_from(y).map_err(|_| "y coordinate is invalid".to_owned())?,
    })
}

fn decode_result_limit(value: i64) -> Result<usize, String> {
    usize::try_from(value)
        .ok()
        .filter(|limit| (1..=MAX_LUA_COLOR_RESULTS).contains(limit))
        .ok_or_else(|| format!("result limit must be between 1 and {MAX_LUA_COLOR_RESULTS}"))
}

fn legacy_coordinate(value: i64, name: &str) -> Result<u32, String> {
    u32::try_from(value).map_err(|_| format!("legacy {name} is invalid"))
}

fn legacy_percentage(value: i64) -> Result<u8, String> {
    u8::try_from(value)
        .ok()
        .filter(|percentage| *percentage <= 100)
        .ok_or_else(|| "legacy match percentage must be between 0 and 100".to_owned())
}

fn legacy_text<'a>(value: &'a [u8], name: &str) -> Result<&'a str, String> {
    if value.is_empty() || value.len() > 32_768 {
        return Err(format!("{name} length is invalid"));
    }
    std::str::from_utf8(value).map_err(|_| format!("{name} must be UTF-8"))
}

fn decode_frame_handle(encoded: i64) -> Result<FrameHandle, String> {
    let encoded = u64::try_from(encoded).map_err(|_| "frame handle is invalid".to_owned())?;
    let local_id = encoded & ((1_u64 << 56) - 1);
    let resource_id = ResourceId::try_new(ResourceKind::Frame, local_id)
        .filter(|resource| resource.get() == encoded)
        .ok_or_else(|| "frame handle is invalid".to_owned())?;
    Ok(FrameHandle {
        frame_id: local_id,
        resource_id,
    })
}

fn decode_series_handle(encoded: i64) -> Result<CaptureSeriesHandle, String> {
    let encoded = u64::try_from(encoded).map_err(|_| "series handle is invalid".to_owned())?;
    let local_id = encoded & ((1_u64 << 56) - 1);
    let resource_id = ResourceId::try_new(ResourceKind::CaptureSeries, local_id)
        .filter(|resource| resource.get() == encoded)
        .ok_or_else(|| "series handle is invalid".to_owned())?;
    Ok(CaptureSeriesHandle {
        series_id: local_id,
        resource_id,
    })
}

fn encode_series_handle(handle: CaptureSeriesHandle) -> Result<i64, String> {
    i64::try_from(handle.resource_id.get())
        .map_err(|_| "series handle exceeds the Lua integer range".to_owned())
}

fn decode_dictionary_handle(encoded: i64) -> Result<DictionaryHandle, String> {
    let encoded = u64::try_from(encoded).map_err(|_| "dictionary handle is invalid".to_owned())?;
    let local_id = encoded & ((1_u64 << 56) - 1);
    let resource_id = ResourceId::try_new(ResourceKind::GlyphDictionary, local_id)
        .filter(|resource| resource.get() == encoded)
        .ok_or_else(|| "dictionary handle is invalid".to_owned())?;
    Ok(DictionaryHandle {
        dictionary_id: local_id,
        resource_id,
    })
}

fn decode_search_direction(direction: i64) -> Result<automation_core::SearchOrder, String> {
    use automation_core::SearchOrder;
    match direction {
        0 => Ok(SearchOrder::TopLeftToBottomRight),
        1 => Ok(SearchOrder::TopRightToBottomLeft),
        2 => Ok(SearchOrder::BottomLeftToTopRight),
        3 => Ok(SearchOrder::BottomRightToTopLeft),
        4 => Ok(SearchOrder::CenterOut),
        _ => Err("search direction must be 0..=4".to_owned()),
    }
}

fn search_options(left: i64, top: i64, right: i64, bottom: i64) -> Result<SearchOptions, String> {
    let roi = PixelRect {
        left: u32::try_from(left).map_err(|_| "ROI left is invalid".to_owned())?,
        top: u32::try_from(top).map_err(|_| "ROI top is invalid".to_owned())?,
        right: u32::try_from(right).map_err(|_| "ROI right is invalid".to_owned())?,
        bottom: u32::try_from(bottom).map_err(|_| "ROI bottom is invalid".to_owned())?,
    };
    if roi.left >= roi.right || roi.top >= roi.bottom {
        return Err("ROI is empty".to_owned());
    }
    Ok(SearchOptions {
        roi,
        step_x: 1,
        step_y: 1,
        order: automation_core::SearchOrder::TopLeftToBottomRight,
        max_pixel_comparisons: 100_000_000,
    })
}

fn host_failure_input(code: &str, message: &str) -> LuaInput {
    LuaInput::Values(vec![
        LuaScalar::Boolean(false),
        LuaScalar::Nil,
        LuaScalar::Nil,
        LuaScalar::Bytes(code.as_bytes().to_vec()),
        LuaScalar::Bytes(message.as_bytes().to_vec()),
    ])
}

fn point_input(point: Option<(u32, u32)>) -> LuaInput {
    let (x, y) = point.map_or((LuaScalar::Nil, LuaScalar::Nil), |(x, y)| {
        (
            LuaScalar::Integer(i64::from(x)),
            LuaScalar::Integer(i64::from(y)),
        )
    });
    LuaInput::Values(vec![LuaScalar::Boolean(true), x, y])
}

fn points_input(points: &[PixelPoint]) -> Result<LuaInput, String> {
    let mut values = Vec::with_capacity(2 + points.len() * 2);
    values.push(LuaScalar::Boolean(true));
    values.push(LuaScalar::Integer(
        i64::try_from(points.len()).map_err(|_| "legacy result count is too large".to_owned())?,
    ));
    for point in points {
        values.push(LuaScalar::Integer(i64::from(point.x)));
        values.push(LuaScalar::Integer(i64::from(point.y)));
    }
    Ok(LuaInput::Values(values))
}

fn host_resume_input(opcode: u32, result: &HostResult) -> Result<LuaInput, ExecutorError> {
    match result {
        HostResult::Success(payload) if opcode == 1200 => Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Bytes(payload.clone()),
        ])),
        HostResult::Failure { code, message } => Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(false),
            LuaScalar::Nil,
            LuaScalar::Nil,
            LuaScalar::Bytes(code.as_bytes().to_vec()),
            LuaScalar::Bytes(message.as_bytes().to_vec()),
        ])),
        HostResult::Success(payload) if opcode == OP_SYSTEM_GET_SCREEN_SIZE => {
            if payload.len() != 8 {
                return Err(ExecutorError::InvalidYield(
                    "screen size payload must be 8 bytes".to_owned(),
                ));
            }
            let width = u32::from_le_bytes(payload[0..4].try_into().expect("length checked"));
            let height = u32::from_le_bytes(payload[4..8].try_into().expect("length checked"));
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(i64::from(width)),
                LuaScalar::Integer(i64::from(height)),
            ]))
        }
        HostResult::Success(payload) if opcode == OP_SYSTEM_ELAPSED_REALTIME_MILLIS => {
            if payload.len() != 8 {
                return Err(ExecutorError::InvalidYield(
                    "elapsed realtime payload must be 8 bytes".to_owned(),
                ));
            }
            let milliseconds =
                u64::from_le_bytes(payload.as_slice().try_into().expect("length checked"));
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(i64::try_from(milliseconds).map_err(|_| {
                    ExecutorError::InvalidYield(
                        "elapsed realtime exceeds Lua integer range".to_owned(),
                    )
                })?),
            ]))
        }
        HostResult::Success(payload)
            if matches!(
                opcode,
                OP_INPUT_TAP
                    | OP_INPUT_SWIPE
                    | OP_INPUT_KEY_EVENT
                    | OP_INPUT_POINTER_DOWN
                    | OP_INPUT_POINTER_MOVE
                    | OP_INPUT_POINTER_UP
                    | OP_INPUT_TAP_SCREEN
                    | OP_INPUT_POINTER_DOWN_SCREEN
                    | OP_SCREEN_RELEASE
            ) =>
        {
            if !payload.is_empty() {
                return Err(ExecutorError::InvalidYield(
                    "void host response must be empty".to_owned(),
                ));
            }
            Ok(LuaInput::Boolean(true))
        }
        HostResult::Success(payload) if matches!(opcode, OP_SCREEN_CAPTURE | OP_SCREEN_CACHE) => {
            if payload.len() != 8 {
                return Err(ExecutorError::InvalidYield(
                    "resource handle payload must be 8 bytes".to_owned(),
                ));
            }
            let handle = i64::from_le_bytes(payload.as_slice().try_into().expect("length checked"));
            if handle <= 0 {
                return Err(ExecutorError::InvalidYield(
                    "resource handle must be positive".to_owned(),
                ));
            }
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(handle),
            ]))
        }
        HostResult::Success(payload) if opcode == OP_SCREEN_CAPTURE_SERIES_FRAME => {
            series_capture_resume_input(payload)
        }
        HostResult::Success(payload)
            if matches!(opcode, OP_SCREEN_FIND_COLOR | OP_SCREEN_FIND_IMAGE) =>
        {
            if payload.is_empty() {
                return Ok(LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Nil,
                    LuaScalar::Nil,
                ]));
            }
            if payload.len() != 8 {
                return Err(ExecutorError::InvalidYield(
                    "vision point payload must be empty or 8 bytes".to_owned(),
                ));
            }
            let x = u32::from_le_bytes(payload[0..4].try_into().expect("length checked"));
            let y = u32::from_le_bytes(payload[4..8].try_into().expect("length checked"));
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(i64::from(x)),
                LuaScalar::Integer(i64::from(y)),
            ]))
        }
        HostResult::Success(payload) if opcode == 5108 => {
            if payload.is_empty() {
                return Ok(LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Nil,
                ]));
            }
            if payload.len() != 10 {
                return Err(ExecutorError::InvalidYield(
                    "gray result length invalid".into(),
                ));
            }
            let x = u32::from_le_bytes(payload[0..4].try_into().expect("length"));
            let y = u32::from_le_bytes(payload[4..8].try_into().expect("length"));
            let score = u16::from_le_bytes(payload[8..10].try_into().expect("length"));
            if score > 1000 {
                return Err(ExecutorError::InvalidYield("gray score invalid".into()));
            }
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(i64::from(x)),
                LuaScalar::Integer(i64::from(y)),
                LuaScalar::Integer(i64::from(score)),
            ]))
        }
        HostResult::Success(payload) if opcode == 6101 => {
            if !(2..=1026).contains(&payload.len())
                || payload[2..]
                    .iter()
                    .any(|byte| !byte.is_ascii_alphanumeric())
            {
                return Err(ExecutorError::InvalidYield("OCR result invalid".into()));
            }
            let score = u16::from_le_bytes(payload[..2].try_into().expect("length"));
            if score > 1000 {
                return Err(ExecutorError::InvalidYield("OCR score invalid".into()));
            }
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(payload[2..].to_vec()),
                LuaScalar::Integer(i64::from(score)),
            ]))
        }
        HostResult::Success(_) => Err(ExecutorError::UnknownOpcode(opcode)),
    }
}

fn series_capture_resume_input(payload: &[u8]) -> Result<LuaInput, ExecutorError> {
    let Some(status) = payload.first().copied() else {
        return Err(ExecutorError::InvalidYield(
            "series capture response is empty".to_owned(),
        ));
    };
    match status {
        0 if payload.len() == 1 => Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Integer(0),
        ])),
        1 if payload.len() == 22 => {
            let capture = u64::from_le_bytes(payload[1..9].try_into().expect("length checked"));
            let timestamp = u64::from_le_bytes(payload[9..17].try_into().expect("length checked"));
            let finished = payload[17];
            let dropped = u32::from_le_bytes(payload[18..22].try_into().expect("length checked"));
            if capture == 0 || timestamp > i64::MAX as u64 || finished > 1 {
                return Err(ExecutorError::InvalidYield(
                    "series frame response is invalid".to_owned(),
                ));
            }
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(1),
                LuaScalar::Integer(i64::try_from(capture).expect("validated capture")),
                LuaScalar::Integer(i64::try_from(timestamp).expect("validated timestamp")),
                LuaScalar::Boolean(finished == 1),
                LuaScalar::Integer(i64::from(dropped)),
                LuaScalar::Boolean(false),
            ]))
        }
        2 if payload.len() == 7 => {
            let partial = payload[1];
            let dropped = u32::from_le_bytes(payload[2..6].try_into().expect("length checked"));
            let reason = payload[6];
            if partial > 1 || reason > 3 {
                return Err(ExecutorError::InvalidYield(
                    "series completion response is invalid".to_owned(),
                ));
            }
            Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(2),
                LuaScalar::Nil,
                LuaScalar::Nil,
                LuaScalar::Boolean(true),
                LuaScalar::Integer(i64::from(dropped)),
                LuaScalar::Boolean(partial == 1),
            ]))
        }
        _ => Err(ExecutorError::InvalidYield(
            "series capture response has an invalid shape".to_owned(),
        )),
    }
}

fn submit_error(error: SubmitError) -> ExecutorError {
    ExecutorError::Lua(format!("completion queue rejected host result: {error:?}"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VirtualHostMode {
    Immediate,
    NeverComplete,
    FailDispatch,
}

#[derive(Debug)]
pub struct VirtualHost {
    pub screen_width: u32,
    pub screen_height: u32,
    pub mode: VirtualHostMode,
    requests: Vec<HostRequest>,
    cancelled: Vec<RequestId>,
}

impl VirtualHost {
    #[must_use]
    pub const fn new(screen_width: u32, screen_height: u32) -> Self {
        Self {
            screen_width,
            screen_height,
            mode: VirtualHostMode::Immediate,
            requests: Vec::new(),
            cancelled: Vec::new(),
        }
    }

    #[must_use]
    pub fn requests(&self) -> &[HostRequest] {
        &self.requests
    }

    #[must_use]
    pub fn cancelled(&self) -> &[RequestId] {
        &self.cancelled
    }
}

impl HostBackend for VirtualHost {
    fn dispatch(
        &mut self,
        request: &HostRequest,
        completion: &SchedulerHandle,
    ) -> Result<(), String> {
        self.requests.push(request.clone());
        match self.mode {
            VirtualHostMode::Immediate => {
                let payload = match request.opcode {
                    OP_SYSTEM_GET_SCREEN_SIZE if request.args.is_empty() => {
                        let mut payload = Vec::with_capacity(8);
                        payload.extend_from_slice(&self.screen_width.to_le_bytes());
                        payload.extend_from_slice(&self.screen_height.to_le_bytes());
                        payload
                    }
                    OP_SYSTEM_ELAPSED_REALTIME_MILLIS if request.args.is_empty() => {
                        0_u64.to_le_bytes().to_vec()
                    }
                    OP_INPUT_TAP | OP_INPUT_TAP_SCREEN | OP_INPUT_POINTER_DOWN_SCREEN
                        if request.args.len() == 2 =>
                    {
                        Vec::new()
                    }
                    OP_INPUT_SWIPE if request.args.len() == 5 => Vec::new(),
                    OP_INPUT_KEY_EVENT if request.args.len() == 1 => Vec::new(),
                    OP_INPUT_POINTER_DOWN | OP_INPUT_POINTER_MOVE if request.args.len() == 2 => {
                        Vec::new()
                    }
                    OP_INPUT_POINTER_UP if request.args.is_empty() => Vec::new(),
                    _ => return Err("unsupported virtual host request".to_owned()),
                };
                completion
                    .submit_host_completion(HostCompletion {
                        request_id: request.request_id,
                        task: request.task,
                        result: HostResult::Success(payload),
                    })
                    .map_err(|error| format!("completion queue full: {error:?}"))
            }
            VirtualHostMode::NeverComplete => Ok(()),
            VirtualHostMode::FailDispatch => Err("virtual dispatch failed".to_owned()),
        }
    }

    fn cancel(&mut self, request_id: RequestId, _task: TaskToken, _cancel_mode: CancelMode) {
        self.cancelled.push(request_id);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use automation_core::{FrameFormat, FrameMetadata, FramePool, FramePoolConfig, Rotation};
    use glyph_ocr::{encode_dictionary, GlyphSource};
    use lua_runtime::{LuaRuntimeConfig, LuaScalar};
    use runtime_scheduler::{
        ManualClock, MonoTime, RequestId, SchedulerConfig, SchedulerPoll, TaskGeneration, TaskId,
        TaskState, TaskToken,
    };
    use script_api::CancelMode;

    use super::{
        ExecutorConfig, ExternalHostEvent, ExternalHostQueue, HostBackend, HostRequest,
        RuntimeExecutor, VirtualHost, VirtualHostMode, OP_INPUT_TAP, OP_SCREEN_CAPTURE,
    };

    #[derive(Debug)]
    struct OneCaptureHost {
        capture_id: u64,
    }

    #[derive(Debug)]
    struct NativeVisionTestHost {
        capture_id: u64,
    }
    impl HostBackend for NativeVisionTestHost {
        fn dispatch(
            &mut self,
            request: &HostRequest,
            completion: &runtime_scheduler::SchedulerHandle,
        ) -> Result<(), String> {
            let payload = match request.opcode {
                OP_SCREEN_CAPTURE => self.capture_id.to_le_bytes().to_vec(),
                5108 => {
                    let input = request.native_vision.as_ref().expect("typed leased input");
                    assert!(input.template.is_some());
                    assert_eq!(input.region.right, 1);
                    [
                        0u32.to_le_bytes().to_vec(),
                        0u32.to_le_bytes().to_vec(),
                        999u16.to_le_bytes().to_vec(),
                    ]
                    .concat()
                }
                6101 => {
                    let input = request.native_vision.as_ref().expect("typed leased input");
                    assert!(input.template.is_none());
                    assert_eq!(input.frame.pixels.len(), 4);
                    [998u16.to_le_bytes().to_vec(), b"AbZ019".to_vec()].concat()
                }
                _ => panic!("unexpected opcode"),
            };
            completion
                .submit_host_completion(runtime_scheduler::HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result: runtime_scheduler::HostResult::Success(payload),
                })
                .map_err(|e| format!("{e:?}"))
        }
        fn cancel(&mut self, _: RequestId, _: TaskToken, _: CancelMode) {}
    }
    #[test]
    fn native_vision_lua_uses_owned_frame_views_and_decodes_bounded_results() {
        let frames = Arc::new(std::sync::Mutex::new(FramePool::new(
            FramePoolConfig::default(),
        )));
        let capture = frames
            .lock()
            .unwrap()
            .publish_capture(
                FrameMetadata {
                    width: 1,
                    height: 1,
                    row_stride: 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[1, 2, 3, 255],
            )
            .unwrap();
        let mut executor = RuntimeExecutor::new_with_frames(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            NativeVisionTestHost {
                capture_id: capture.0,
            },
            frames,
        )
        .unwrap();
        let task=executor.start_entry_chunk(b"return function() local f=Screen.cache(Screen.capture()); local r=Screen.findGray(f,f,900,0,0,1,1); gray_score=r.scorePermille; local o=Ocr.alphanumeric(f,0,0,1,1,500); ocr_text=o.text; Screen.release(f) end","native-vision").unwrap();
        executor.drive().unwrap();
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("gray_score").unwrap(),
            LuaScalar::Integer(999)
        );
        assert_eq!(
            executor.lua().global_scalar("ocr_text").unwrap(),
            LuaScalar::Bytes(b"AbZ019".to_vec())
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }
    #[test]
    fn malformed_native_results_never_become_lua_values() {
        use runtime_scheduler::HostResult;
        assert!(super::host_resume_input(5108, &HostResult::Success(vec![0; 9])).is_err());
        assert!(super::host_resume_input(6101, &HostResult::Success(vec![0, 0, b'$'])).is_err());
        assert!(super::host_resume_input(6101, &HostResult::Success(vec![255, 255])).is_err());
        assert!(super::host_resume_input(6101, &HostResult::Success(vec![0; 1027])).is_err());
    }

    impl HostBackend for OneCaptureHost {
        fn dispatch(
            &mut self,
            request: &HostRequest,
            completion: &runtime_scheduler::SchedulerHandle,
        ) -> Result<(), String> {
            if request.opcode != OP_SCREEN_CAPTURE || !request.args.is_empty() {
                return Err("unsupported test host request".to_owned());
            }
            completion
                .submit_host_completion(runtime_scheduler::HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result: runtime_scheduler::HostResult::Success(
                        self.capture_id.to_le_bytes().to_vec(),
                    ),
                })
                .map_err(|error| format!("completion rejected: {error:?}"))
        }

        fn cancel(&mut self, _request_id: RequestId, _task: TaskToken, _cancel_mode: CancelMode) {}
    }

    fn executor(
        clock: &ManualClock,
        host: VirtualHost,
    ) -> RuntimeExecutor<ManualClock, VirtualHost> {
        RuntimeExecutor::new(
            clock.clone(),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            host,
        )
        .expect("executor")
    }

    #[test]
    fn screen_size_sleep_and_math_form_an_event_driven_loop() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1080, 1920));
        let task = executor
            .start_entry_chunk(
                br"
                    return function()
                        local size = System.getScreenSize()
                        Task.sleep(20)
                        r0_result = size.width + size.height + Math.distance(0, 0, 3, 4)
                    end
                ",
                "closed-loop",
            )
            .expect("start");
        let waiting = executor.drive().expect("drive to timer");
        assert_eq!(
            waiting.next,
            SchedulerPoll::Deadline(MonoTime::from_nanos(20_000_000))
        );
        assert_eq!(executor.backend().requests().len(), 1);

        clock.advance(Duration::from_millis(20));
        executor.drive().expect("drive to completion");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("r0_result").expect("result"),
            LuaScalar::Number(3005.0)
        );
    }

    #[test]
    fn real_interval_callbacks_are_non_reentrant_and_preserve_parent_sleep() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner=runtime.start_entry_chunk(br"return function()
            count=0; active=0; peak=0
            local timer=Timer.every(10,function() active=active+1; peak=math.max(peak,active); count=count+1; Task.sleep(25); active=active-1 end)
            Task.sleep(100)
            assert(Timer.cancel(timer)); done=true
        end", "timer-non-reentrant").unwrap();
        runtime.drive().unwrap();
        for _ in 0..10 {
            clock.advance(Duration::from_millis(10));
            runtime.drive().unwrap();
        }
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("peak").unwrap(),
            LuaScalar::Integer(1)
        );
        assert_eq!(
            runtime.lua().global_scalar("done").unwrap(),
            LuaScalar::Boolean(true)
        );
        assert!(runtime.callback_timers.is_empty());
        assert!(runtime.callback_tasks.is_empty());
        assert_eq!(runtime.lua().task_count(), 0);
    }

    #[test]
    fn hundreds_of_timer_callbacks_reap_outcomes_and_owner_stop_cleans_children() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner=runtime.start_entry_chunk(br"return function() count=0; Timer.every(10,function() count=count+1 end); Task.sleep(60000) end", "timer-reap").unwrap();
        runtime.drive().unwrap();
        for _ in 0..400 {
            clock.advance(Duration::from_millis(10));
            runtime.drive().unwrap();
        }
        assert_eq!(
            runtime.lua().global_scalar("count").unwrap(),
            LuaScalar::Integer(400)
        );
        runtime.scheduler.handle().request_cancel(owner).unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Cancelled));
        assert!(runtime.callback_timers.is_empty());
        assert!(runtime.callback_tasks.is_empty());
    }

    #[test]
    fn callback_failure_propagates_and_spawn_is_owner_scoped() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner=runtime.start_entry_chunk(br"return function() Task.spawn(function() error('child-failed') end); Task.sleep(500) end", "task-fail").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Failed));
        assert!(runtime
            .task_failure(owner)
            .unwrap()
            .message
            .contains("child-failed"));
        assert!(runtime.callback_tasks.is_empty());
        let owner=runtime.start_entry_chunk(br"return function() local child=Task.spawn(function() Task.sleep(500) end); assert(not Task.cancel(999999)); assert(Task.cancel(child)); assert(not Timer.cancel(999999)); end", "task-cancel").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert!(runtime.callback_tasks.is_empty());
    }

    #[test]
    fn timers_do_not_clobber_pending_host_resume_values_and_pause_shifts_deadlines() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner=runtime.start_entry_chunk(br"return function() ticks=0; Timer.every(10,function() ticks=ticks+1; local size=System.getScreenSize(); assert(size.width==720) end); Task.sleep(100); end", "timer-pause").unwrap();
        runtime.drive().unwrap();
        runtime.pause().unwrap();
        clock.advance(Duration::from_millis(100));
        runtime.drive().unwrap();
        assert_eq!(
            runtime.lua().global_scalar("ticks").unwrap(),
            LuaScalar::Integer(0)
        );
        runtime.resume().unwrap();
        clock.advance(Duration::from_millis(10));
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Running));
        assert_eq!(
            runtime.lua().global_scalar("ticks").unwrap(),
            LuaScalar::Integer(1)
        );
        runtime.scheduler.handle().request_stop();
        runtime.drive().unwrap();
        assert!(runtime.callback_timers.is_empty());
    }

    #[test]
    fn denied_callback_registration_does_not_exhaust_function_registry() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        runtime.set_allowed_capabilities(&[]).unwrap();
        let owner = runtime
            .start_entry_chunk(
                br"return function()
            for n=1,300 do
                local ok,err=pcall(Task.spawn,function() end)
                assert(not ok and string.find(err,'capability core.task',1,true))
                ok,err=pcall(Timer.every,10,function() end)
                assert(not ok and string.find(err,'capability core.task',1,true))
            end
            completed=true
        end",
                "denied-callback-registry",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("completed").unwrap(),
            LuaScalar::Boolean(true)
        );
        assert!(runtime.callback_tasks.is_empty());
        assert!(runtime.callback_timers.is_empty());
    }

    #[test]
    fn raw_screen_input_opcodes_return_void_success() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner=runtime.start_entry_chunk(br"return function() Input.tapScreen(10,20); Input.pointerDownScreen(30,40); Input.pointerUp(); end", "raw-input").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(runtime.backend().requests()[0].opcode, 4006);
        assert_eq!(runtime.backend().requests()[1].opcode, 4007);
    }

    #[test]
    fn host_timeout_cancels_backend_and_fails_script() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut host = VirtualHost::new(1080, 1920);
        host.mode = VirtualHostMode::NeverComplete;
        let mut executor = executor(&clock, host);
        let task = executor
            .start_entry_chunk(b"return function() System.getScreenSize() end", "timeout")
            .expect("start");
        assert!(matches!(
            executor.drive().expect("wait").next,
            SchedulerPoll::Deadline(_)
        ));
        clock.advance(Duration::from_secs(1));
        executor.drive().expect("timeout resumes with error");
        assert_eq!(executor.task_state(task), Some(TaskState::Failed));
        assert_eq!(executor.backend().cancelled().len(), 1);
    }

    #[test]
    fn stop_uses_priority_channel_and_cancels_pending_host() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut host = VirtualHost::new(1, 1);
        host.mode = VirtualHostMode::NeverComplete;
        let mut executor = executor(&clock, host);
        let task = executor
            .start_entry_chunk(b"return function() System.getScreenSize() end", "stop")
            .expect("start");
        executor.drive().expect("host wait");
        executor.handle().request_stop();
        let report = executor.drive().expect("stop");
        assert_eq!(report.next, SchedulerPoll::Stopped);
        assert_eq!(executor.task_state(task), Some(TaskState::Cancelled));
        assert_eq!(executor.backend().cancelled().len(), 1);
    }

    #[test]
    fn input_api_validates_then_completes_through_host_queue() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1080, 1920));
        let task = executor
            .start_entry_chunk(
                b"return function() Input.tap(12, 34); Input.swipe(1, 2, 3, 4, 50); Input.pointerDown(5, 6); Input.pointerMove(7, 8); Input.pointerUp(); input_done = true end",
                "input-api",
            )
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(executor.backend().requests().len(), 5);
        assert_eq!(
            executor.lua().global_scalar("input_done").expect("result"),
            LuaScalar::Boolean(true)
        );
    }

    #[test]
    fn script_logs_are_bounded_and_drained_without_host_dispatch() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1, 1));
        let task = executor
            .start_entry_chunk(
                b"return function() Log.info('captured'); Log.warn('missing\\nretry'); Log.error(7) end",
                "script-log",
            )
            .expect("start");
        executor.drive().expect("drive logs");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert!(executor.backend().requests().is_empty());
        assert_eq!(
            executor.drain_script_logs(),
            vec![
                "脚本/INFO: captured".to_owned(),
                "脚本/WARN: missing\\nretry".to_owned(),
                "脚本/ERROR: 7".to_owned(),
            ]
        );
        assert!(executor.drain_script_logs().is_empty());
    }

    #[test]
    fn runtime_prompts_are_separate_from_developer_logs() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1, 1));
        let task = executor
            .start_entry_chunk(
                b"return function() Prompt.show('running'); Log.info('internal log') end",
                "script-prompt",
            )
            .expect("start");
        executor.drive().expect("drive prompt");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert!(executor.backend().requests().is_empty());
        assert_eq!(
            executor.drain_script_prompts(),
            vec![r#"{"kind":"window","message":"running"}"#.to_owned()]
        );
        assert_eq!(
            executor.drain_script_logs(),
            vec!["脚本/INFO: internal log".to_owned()]
        );
    }

    #[test]
    fn ui_items_updates_selection_and_rejects_invalid_state() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(1, 1));
        runtime
            .seed_ui_values(std::collections::BTreeMap::from([(
                "choice".to_owned(),
                "old".to_owned(),
            )]))
            .unwrap();
        let owner = runtime.start_entry_chunk(br"return function() UI.command('choice','items','A\nB'); picked=UI.getValue('choice') end", "ui-items").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("picked").unwrap(),
            LuaScalar::Bytes(b"A".to_vec())
        );
        let mut invalid = executor(&clock, VirtualHost::new(1, 1));
        invalid
            .start_entry_chunk(
                b"return function() UI.command('choice','enabled','maybe') end",
                "ui-invalid",
            )
            .unwrap();
        assert!(invalid.drive().is_err());
    }

    #[test]
    fn ui_initial_values_and_edits_do_not_create_events() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(1, 1));
        runtime
            .seed_ui_values(std::collections::BTreeMap::from([(
                "input".to_owned(),
                "initial".to_owned(),
            )]))
            .unwrap();
        for i in 0..200 {
            runtime.update_ui_value("input", &i.to_string()).unwrap();
        }
        assert!(runtime.ui_events.is_empty());
        let owner = runtime
            .start_entry_chunk(
                b"return function() latest=UI.getValue('input') end",
                "ui-sync",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("latest").unwrap(),
            LuaScalar::Bytes(b"199".to_vec())
        );
        assert!(runtime
            .seed_ui_values(std::collections::BTreeMap::new())
            .is_err());
        assert!(runtime.update_ui_value("bad/id", "x").is_err());
    }

    #[test]
    fn ui_wait_is_event_driven_and_preserves_queued_event_values() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(720, 1280));
        let owner = runtime.start_entry_chunk(br"return function() first=UI.waitEvent(); one=UI.getValue('button'); second=UI.waitEvent(); two=UI.getValue('button'); UI.setValue('label','done') end", "ui-events").unwrap();
        runtime.drive().unwrap();
        assert!(runtime.backend().requests().is_empty());
        assert!(runtime.ui_waiting.is_some());
        runtime.push_ui_event("button", "click", "one").unwrap();
        runtime.push_ui_event("button", "click", "two").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("one").unwrap(),
            LuaScalar::Bytes(b"one".to_vec())
        );
        assert_eq!(
            runtime.lua().global_scalar("two").unwrap(),
            LuaScalar::Bytes(b"two".to_vec())
        );
        assert_eq!(runtime.drain_script_prompts().len(), 1);
    }

    #[test]
    fn ui_queue_has_backpressure_and_wait_is_cancelable() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(1, 1));
        for _ in 0..64 {
            runtime.push_ui_event("button", "click", "").unwrap();
        }
        assert!(runtime.push_ui_event("button", "click", "").is_err());
        runtime.ui_events.clear();
        let owner = runtime
            .start_entry_chunk(b"return function() UI.waitEvent() end", "ui-cancel")
            .unwrap();
        runtime.drive().unwrap();
        runtime.scheduler.handle().request_cancel(owner).unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Cancelled));
        assert!(runtime.pending_host.is_empty());
    }

    #[test]
    fn ui_api_does_not_bypass_capabilities() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = executor(&clock, VirtualHost::new(1, 1));
        runtime
            .set_allowed_capabilities(&["core.task".to_owned()])
            .unwrap();
        let owner = runtime
            .start_entry_chunk(
                b"return function() allowed=pcall(UI.getValue,'button') end",
                "ui-capabilities",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("allowed").unwrap(),
            LuaScalar::Boolean(false)
        );
    }

    #[test]
    fn popup_toast_is_typed_and_carries_a_bounded_auto_dismiss_style() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1, 1));
        let task = executor
            .start_entry_chunk(
                b"return function() Prompt.toast('short', '{\"widthPx\":100,\"heightPx\":30,\"durationMs\":1500,\"fontPx\":18,\"backgroundColor\":\"#CC102030\"}') end",
                "script-toast",
            )
            .expect("start");
        executor.drive().expect("drive toast");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        let events = executor.drain_script_prompts();
        assert_eq!(events.len(), 1);
        let event: serde_json::Value = serde_json::from_str(&events[0]).expect("typed event");
        assert_eq!(event["kind"], "toast");
        assert_eq!(event["message"], "short");
        assert_eq!(event["style"]["durationMs"], 1500);
        assert_eq!(event["style"]["widthPx"], 100);
        assert_eq!(event["style"]["heightPx"], 30);
        assert_eq!(event["style"]["fontPx"], 18);
        assert_eq!(event["style"]["backgroundColor"], "#CC102030");
    }

    #[test]
    fn external_queue_prioritizes_stop_and_cancellation_over_business_work() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let executor = executor(&clock, VirtualHost::new(1, 1));
        let queue = ExternalHostQueue::new(2, 2).expect("queue");
        let task = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        };
        for id in [1, 2] {
            queue
                .dispatch(
                    HostRequest {
                        request_id: RequestId(id),
                        task,
                        opcode: OP_INPUT_TAP,
                        cancel_mode: CancelMode::Cooperative,
                        timeout: Duration::from_secs(1),
                        args: vec![LuaScalar::Integer(1), LuaScalar::Integer(2)],
                        native_vision: None,
                    },
                    executor.handle(),
                )
                .expect("dispatch");
        }
        queue.cancel(RequestId(2), task, CancelMode::Cooperative);
        assert!(matches!(
            queue.wait_next(),
            ExternalHostEvent::Cancel {
                request_id: RequestId(2),
                ..
            }
        ));
        assert!(matches!(
            queue.wait_next(),
            ExternalHostEvent::Dispatch {
                request: HostRequest {
                    request_id: RequestId(1),
                    ..
                },
                ..
            }
        ));
        queue.request_stop();
        assert!(matches!(queue.wait_next(), ExternalHostEvent::Stop));
    }

    #[test]
    fn external_queue_interrupt_is_non_terminal() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let executor = executor(&clock, VirtualHost::new(1, 1));
        let queue = ExternalHostQueue::new(1, 1).expect("queue");
        let task = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        };
        queue.interrupt();
        assert!(matches!(queue.wait_next(), ExternalHostEvent::Interrupted));
        queue.task_finished(task);
        assert!(matches!(
            queue.wait_next(),
            ExternalHostEvent::TaskFinished(finished) if finished == task
        ));
        queue
            .dispatch(
                HostRequest {
                    request_id: RequestId(1),
                    task,
                    opcode: OP_INPUT_TAP,
                    cancel_mode: CancelMode::Cooperative,
                    timeout: Duration::from_secs(1),
                    args: vec![LuaScalar::Integer(1), LuaScalar::Integer(2)],
                    native_vision: None,
                },
                executor.handle(),
            )
            .expect("queue remains usable");
        assert!(matches!(
            queue.wait_next(),
            ExternalHostEvent::Dispatch { .. }
        ));
    }

    #[test]
    fn active_backend_can_consume_only_its_exact_cancellation() {
        let queue = ExternalHostQueue::new(1, 2).expect("queue");
        let task = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(2),
        };
        queue.cancel(RequestId(7), task, CancelMode::Cooperative);
        assert_eq!(
            queue.take_cancellation(
                RequestId(7),
                TaskToken {
                    id: TaskId(1),
                    generation: TaskGeneration(1),
                },
            ),
            None
        );
        assert_eq!(
            queue.take_cancellation(RequestId(7), task),
            Some(CancelMode::Cooperative)
        );
        assert_eq!(queue.take_cancellation(RequestId(7), task), None);
    }

    #[test]
    fn screen_cache_find_and_release_use_task_scoped_frame_leases() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(2, 1));
        let capture = executor
            .frame_pool()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 2,
                    height: 1,
                    row_stride: 8,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[0, 0, 0, 255, 10, 20, 30, 255],
            )
            .expect("capture");
        let source = format!(
            "return function() local frame = Screen.cache({}); local point = Screen.findColor(frame, 0x0a141e, 0, 0, 0, 2, 1); Screen.release(frame); found_x = point.x end",
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "screen-api")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("found_x").expect("result"),
            LuaScalar::Integer(1)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn one_frame_capture_series_round_trips_lua_and_cleans_reservation() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let frames = Arc::new(std::sync::Mutex::new(FramePool::new(
            FramePoolConfig::default(),
        )));
        let capture = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 1,
                    height: 1,
                    row_stride: 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 123,
                    snapshot_id: 1,
                },
                &[10, 20, 30, 255],
            )
            .expect("capture");
        let mut executor = RuntimeExecutor::new_with_frames(
            clock,
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            OneCaptureHost {
                capture_id: capture.0,
            },
            Arc::clone(&frames),
        )
        .expect("executor");
        let task = executor
            .start_entry_chunk(
                b"return function() local result=Screen.captureSeries(1,1000,10,false); series_count=#result.frames; series_timestamp=result.frames[1].timestampNanos; Screen.release(result.frames[1].frame) end",
                "one-frame-series",
            )
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("series_count").expect("count"),
            LuaScalar::Integer(1)
        );
        assert_eq!(
            executor
                .lua()
                .global_scalar("series_timestamp")
                .expect("timestamp"),
            LuaScalar::Integer(123)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn cancelling_sleeping_capture_series_releases_frames_and_reserved_budget() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let frames = Arc::new(std::sync::Mutex::new(FramePool::new(
            FramePoolConfig::default(),
        )));
        let capture = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 1,
                    height: 1,
                    row_stride: 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 123,
                    snapshot_id: 1,
                },
                &[10, 20, 30, 255],
            )
            .expect("capture");
        let mut executor = RuntimeExecutor::new_with_frames(
            clock,
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            OneCaptureHost {
                capture_id: capture.0,
            },
            Arc::clone(&frames),
        )
        .expect("executor");
        let task = executor
            .start_entry_chunk(
                b"return function() Screen.captureSeries(2,1000,1,false) end",
                "cancel-series",
            )
            .expect("start");
        executor.drive().expect("reach series timer");
        assert_eq!(executor.task_state(task), Some(TaskState::Running));
        assert_eq!(
            frames
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .series_count(),
            1
        );
        executor.handle().request_cancel(task).expect("cancel");
        executor.drive().expect("cleanup");
        assert_eq!(executor.task_state(task), Some(TaskState::Cancelled));
        assert_eq!(executor.resources().resource_count(), 0);
        let frames = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        assert_eq!(frames.series_count(), 0);
        assert_eq!(frames.reserved_series_bytes(), 0);
    }

    #[test]
    fn extended_pixel_api_round_trips_lua_tables_and_releases_the_frame() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(3, 2));
        let capture = executor
            .frame_pool()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 3,
                    height: 2,
                    row_stride: 12,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[
                    10, 20, 30, 255, 1, 2, 3, 255, 10, 20, 30, 255, 40, 50, 60, 255, 1, 2, 3, 255,
                    10, 20, 30, 255,
                ],
            )
            .expect("capture");
        let source = format!(
            r"return function()
                local frame = Screen.cache({})
                sampled_rgb = Screen.getColor(frame, 0, 0)
                compared = Screen.compareColor(frame, 0, 0, 0x0a141e, 0)
                local multi = Screen.findMultiColor(frame, 0x28323c, 0, {{{{x=0,y=-1,rgb=0x0a141e,tolerance=0}}}}, 0, 0, 3, 2)
                multi_x, multi_y = multi.x, multi.y
                color_count = Screen.countColor(frame, 0x0a141e, 0, 0, 0, 3, 2, 3)
                local all = Screen.findAllColor(frame, 0x0a141e, 0, 0, 0, 3, 2, 3)
                all_count, second_x, second_y = #all, all[2].x, all[2].y
                Screen.release(frame)
            end",
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "extended-pixel-api")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("sampled_rgb").expect("rgb"),
            LuaScalar::Integer(0x000a_141e)
        );
        assert_eq!(
            executor.lua().global_scalar("compared").expect("compare"),
            LuaScalar::Boolean(true)
        );
        assert_eq!(
            executor.lua().global_scalar("multi_x").expect("multi x"),
            LuaScalar::Integer(0)
        );
        assert_eq!(
            executor.lua().global_scalar("multi_y").expect("multi y"),
            LuaScalar::Integer(1)
        );
        assert_eq!(
            executor.lua().global_scalar("color_count").expect("count"),
            LuaScalar::Integer(3)
        );
        assert_eq!(
            executor
                .lua()
                .global_scalar("all_count")
                .expect("all count"),
            LuaScalar::Integer(3)
        );
        assert_eq!(
            executor.lua().global_scalar("second_x").expect("second x"),
            LuaScalar::Integer(2)
        );
        assert_eq!(
            executor.lua().global_scalar("second_y").expect("second y"),
            LuaScalar::Integer(0)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn explicit_legacy_namespace_round_trips_all_four_compatibility_functions() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(3, 2));
        let capture = executor
            .frame_pool()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 3,
                    height: 2,
                    row_stride: 12,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[
                    10, 20, 30, 255, 1, 2, 3, 255, 10, 20, 30, 255, 40, 50, 60, 255, 1, 2, 3, 255,
                    10, 20, 30, 255,
                ],
            )
            .expect("capture");
        let source = format!(
            r#"return function()
                local frame = Screen.cache({})
                local found = Legacy.duoDianZhaoSe(frame, 0, 0, 3, 2, "(40,50,60)-(0,0,0)#(0,-1)|(10,20,30)-(0,0,0)", 1, 100)
                legacy_count = found.count
                legacy_first_x, legacy_first_y = found.first.x, found.first.y
                legacy_point_x, legacy_point_y = found.points[1].x, found.points[1].y
                legacy_compared = Legacy.duoDianBiSe(frame, "(0,0)|(10,20,30)-(0,0,0)#(1,0)|(9,9,9)-(0,0,0)", 50)
                legacy_color_count = Legacy.getRectColorNum(frame, 0, 0, 3, 2, "(10,20,30)-(0,0,0)#(40,50,60)-(0,0,0)")
                legacy_rgb = Legacy.getRgbColor(0x12, 0x34, 0x56)
                legacy_global_absent = DuoDianZhaoSe == nil
                Screen.release(frame)
            end"#,
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "legacy-pixel-api")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        for name in ["legacy_count", "legacy_compared"] {
            assert_eq!(
                executor.lua().global_scalar(name).expect(name),
                LuaScalar::Integer(1)
            );
        }
        for name in [
            "legacy_first_x",
            "legacy_point_x",
            "legacy_first_y",
            "legacy_point_y",
        ] {
            let expected = i64::from(!name.ends_with('x'));
            assert_eq!(
                executor.lua().global_scalar(name).expect(name),
                LuaScalar::Integer(expected)
            );
        }
        assert_eq!(
            executor
                .lua()
                .global_scalar("legacy_color_count")
                .expect("color count"),
            LuaScalar::Integer(4)
        );
        assert_eq!(
            executor.lua().global_scalar("legacy_rgb").expect("rgb"),
            LuaScalar::Integer(0x12_34_56)
        );
        assert_eq!(
            executor
                .lua()
                .global_scalar("legacy_global_absent")
                .expect("explicit namespace"),
            LuaScalar::Boolean(true)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn legacy_namespace_rejects_relaxed_parameter_text_before_pixel_work() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1, 1));
        let capture = executor
            .frame_pool()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 1,
                    height: 1,
                    row_stride: 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[255, 0, 0, 255],
            )
            .expect("capture");
        let source = format!(
            "return function() local frame=Screen.cache({}); Legacy.duoDianZhaoSe(frame,0,0,1,1,' (255,0,0)-(0,0,0)',1,100) end",
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "strict-legacy-text")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Failed));
    }

    #[test]
    fn multi_color_lua_wrapper_rejects_more_than_sixty_four_samples() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(1, 1));
        let task = executor
            .start_entry_chunk(
                br"return function()
                    local samples = {}
                    for index = 1, 65 do
                        samples[index] = {x=0,y=0,rgb=0,tolerance=0}
                    end
                    Screen.findMultiColor(1, 0, 0, samples, 0, 0, 1, 1)
                end",
                "too-many-color-samples",
            )
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Failed));
    }

    #[test]
    fn registered_project_image_can_be_loaded_and_used_as_template() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(2, 1));
        let pool = executor.frame_pool();
        let mut pool = pool
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        pool.register_template(
            "assets/images/target.png",
            FrameMetadata {
                width: 1,
                height: 1,
                row_stride: 4,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 0,
                snapshot_id: 1,
            },
            Arc::from([10, 20, 30, 255]),
        )
        .expect("template");
        let capture = pool
            .publish_capture(
                FrameMetadata {
                    width: 2,
                    height: 1,
                    row_stride: 8,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &[0, 0, 0, 255, 10, 20, 30, 255],
            )
            .expect("capture");
        drop(pool);
        let source = format!(
            "return function() local screen=Screen.cache({}); local template=Screen.loadImage('assets/images/target.png'); local point=Screen.findImage(screen, template, 0, 1000, 0, 0, 2, 1); found_template_x=point.x; Screen.release(template); Screen.release(screen) end",
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "project-image")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor
                .lua()
                .global_scalar("found_template_x")
                .expect("result"),
            LuaScalar::Integer(1)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn modern_template_directions_and_old_default_execute_through_lua() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(3, 3));
        let pool = executor.frame_pool();
        let mut pool = pool.lock().unwrap();
        let metadata = FrameMetadata {
            width: 1,
            height: 1,
            row_stride: 4,
            pixel_stride: 4,
            format: FrameFormat::Rgba8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos: 0,
            snapshot_id: 1,
        };
        pool.register_template(
            "assets/images/same.png",
            metadata,
            Arc::from([10, 20, 30, 255]),
        )
        .unwrap();
        let capture = pool
            .publish_capture(
                FrameMetadata {
                    width: 3,
                    height: 3,
                    row_stride: 12,
                    ..metadata
                },
                &[10, 20, 30, 255].repeat(9),
            )
            .unwrap();
        drop(pool);
        let task = executor
            .start_entry_chunk(
                format!(
                    r"return function()
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/same.png')
            local expected={{{{0,0}},{{2,0}},{{0,2}},{{2,2}},{{1,1}}}}
            for direction=0,4 do
                local p=Screen.findImage(f,t,0,1000,0,0,3,3,direction)
                assert(p.x==expected[direction+1][1] and p.y==expected[direction+1][2])
            end
            local old=Screen.findImage(f,t,0,1000,0,0,3,3); assert(old.x==0 and old.y==0)
            Screen.release(t); Screen.release(f)
        end",
                    capture.0
                )
                .as_bytes(),
                "ordered-template",
            )
            .unwrap();
        executor.drive().unwrap();
        assert_eq!(
            executor.task_state(task),
            Some(TaskState::Completed),
            "{:?}",
            executor.task_failure(task)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }

    #[test]
    fn modern_template_direction_decoder_is_not_the_legacy_enum() {
        assert_eq!(
            super::decode_search_direction(0).unwrap(),
            automation_core::SearchOrder::TopLeftToBottomRight
        );
        assert!(super::decode_search_direction(-1).is_err());
        assert!(super::decode_search_direction(5).is_err());
    }

    #[test]
    fn registered_dictionary_recognizes_a_cached_frame_and_releases_all_leases() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = executor(&clock, VirtualHost::new(7, 3));
        let dictionary = encode_dictionary(&[
            GlyphSource {
                label: "A".to_owned(),
                width: 3,
                height: 3,
                packed_bits: vec![0b0101_0111, 0b1000_0000],
            },
            GlyphSource {
                label: "I".to_owned(),
                width: 1,
                height: 3,
                packed_bits: vec![0b1110_0000],
            },
        ])
        .expect("dictionary");
        executor
            .dictionary_store()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .register("dictionaries/main.asglyph", &dictionary)
            .expect("register dictionary");

        let mut pixels = vec![0_u8; 7 * 3 * 4];
        for (x, y) in [
            (1, 0),
            (0, 1),
            (2, 1),
            (0, 2),
            (1, 2),
            (2, 2),
            (6, 0),
            (6, 1),
            (6, 2),
        ] {
            let offset = (y * 7 + x) * 4;
            pixels[offset..offset + 4].copy_from_slice(&[255, 255, 255, 255]);
        }
        let capture = executor
            .frame_pool()
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture(
                FrameMetadata {
                    width: 7,
                    height: 3,
                    row_stride: 28,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &pixels,
            )
            .expect("capture");
        let source = format!(
            "return function() local frame=Screen.cache({}); local dictionary=Ocr.loadDictionary('dictionaries/main.asglyph'); local result=Ocr.glyph(frame,dictionary,0xffffff,0,1000,0,0,7,3,3); ocr_text=result.text; ocr_coverage=result.coveragePermille; ocr_average=result.averageScorePermille; Ocr.releaseDictionary(dictionary); Screen.release(frame) end",
            capture.0
        );
        let task = executor
            .start_entry_chunk(source.as_bytes(), "glyph-ocr")
            .expect("start");
        executor.drive().expect("drive");
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            executor.lua().global_scalar("ocr_text").expect("text"),
            LuaScalar::Bytes(b"A I".to_vec())
        );
        assert_eq!(
            executor
                .lua()
                .global_scalar("ocr_coverage")
                .expect("coverage"),
            LuaScalar::Integer(1_000)
        );
        assert_eq!(
            executor
                .lua()
                .global_scalar("ocr_average")
                .expect("average"),
            LuaScalar::Integer(1_000)
        );
        assert_eq!(executor.resources().resource_count(), 0);
    }
}
