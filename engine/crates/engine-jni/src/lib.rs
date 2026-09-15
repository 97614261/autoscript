//! Narrow JNI boundary backed by generation-checked opaque handles.

mod visual_compile;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::Duration;

#[cfg(target_os = "android")]
use automation_core::{CaptureSeriesHandle, SeriesPublish};
use automation_core::{FrameFormat, FrameMetadata, FramePool, Rotation};
use coordinate::{CoordinateSnapshot, ScaleMode, Size};
use engine_core::{EngineSession, EngineSessionConfig, EngineState};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
#[cfg(target_os = "android")]
use lua_runtime::LuaScalar;
use runtime_executor::{ExternalHostEvent, ExternalHostQueue, HostRequest};
use runtime_scheduler::{HostCompletion, HostResult, SchedulerHandle, SchedulerPoll};
#[cfg(target_os = "android")]
use runtime_scheduler::{ResourceId, ResourceKind};

const STATE_IDLE: i32 = 1;
const STATE_RUNNING: i32 = 2;
const STATE_STOPPED: i32 = 3;
const STATE_FAILED: i32 = 4;
const STATE_STOPPING: i32 = 5;
const STATE_PAUSED: i32 = 6;
const NEXT_IDLE: i64 = -1;
const NEXT_STOPPED: i64 = -2;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(5);
const ROOT_ATTACH_GRACE: Duration = Duration::from_secs(1);
const HOST_QUEUE_CAPACITY: usize = 32;
const HOST_CANCELLATION_CAPACITY: usize = 64;
const MAX_DICTIONARY_BYTES: usize = 8 * 1024 * 1024;
const MAX_RUNTIME_DIAGNOSTIC_CHARS: usize = 4_096;
const MAX_PROJECT_CAPABILITIES: usize = 64;
const MAX_CAPABILITY_BYTES: usize = 128;

struct RootDispatch {
    result: HostResult,
    connection_lost: bool,
}

#[cfg(target_os = "android")]
const OP_SYSTEM_GET_SCREEN_SIZE: u32 = 2_000;
#[cfg(target_os = "android")]
const OP_INPUT_TAP: u32 = 4_000;
#[cfg(target_os = "android")]
const OP_INPUT_SWIPE: u32 = 4_001;
#[cfg(target_os = "android")]
const OP_INPUT_KEY_EVENT: u32 = 4_002;
#[cfg(target_os = "android")]
const OP_SCREEN_CAPTURE: u32 = 5_000;
#[cfg(target_os = "android")]
const OP_SCREEN_CAPTURE_SERIES_FRAME: u32 = 5_004;

enum SessionCommand {
    Reset {
        width: u32,
        height: u32,
        response: SyncSender<Result<SchedulerHandle, String>>,
    },
    Start {
        source: Vec<u8>,
        capabilities: Vec<String>,
        response: SyncSender<Result<(), String>>,
    },
    Pump {
        boot_nanos: u64,
        response: SyncSender<Result<(), String>>,
    },
    UpdateDisplay {
        snapshot_id: u64,
        width: u32,
        height: u32,
        response: SyncSender<Result<(), String>>,
    },
    ConfigureProject {
        snapshot_id: u64,
        design_width: u32,
        design_height: u32,
        scale_mode: ScaleMode,
        response: SyncSender<Result<(), String>>,
    },
    RegisterTemplate {
        name: String,
        width: u32,
        height: u32,
        pixels: Vec<u8>,
        response: SyncSender<Result<(), String>>,
    },
    RegisterDictionary {
        path: String,
        bytes: Vec<u8>,
        response: SyncSender<Result<(), String>>,
    },
    ControlWake,
}

enum RootControl {
    Attach {
        socket_path: String,
        key: [u8; 32],
        timeout: Duration,
        response: SyncSender<Result<(), String>>,
    },
    Disconnect {
        response: SyncSender<()>,
    },
    Shutdown,
}

#[cfg(target_os = "android")]
mod platform_root {
    use std::path::Path;
    use std::time::Duration;

    use coordinate::DesignPoint;

    use super::{
        CaptureSeriesHandle, CoordinateSnapshot, FrameFormat, FrameMetadata, FramePool,
        HostRequest, HostResult, LuaScalar, ResourceId, ResourceKind, RootDispatch, Rotation,
        SeriesPublish, OP_INPUT_KEY_EVENT, OP_INPUT_SWIPE, OP_INPUT_TAP, OP_SCREEN_CAPTURE,
        OP_SCREEN_CAPTURE_SERIES_FRAME, OP_SYSTEM_GET_SCREEN_SIZE,
    };

    pub type Client = root_client::AndroidRootClient;

    pub fn connect(path: &str, key: [u8; 32], timeout: Duration) -> Result<Client, String> {
        root_client::connect_android(Path::new(path), key, timeout)
            .map_err(|error| format!("{error:?}"))
    }

    pub fn shutdown(client: &mut Client) -> Result<(), String> {
        client.shutdown().map_err(|error| format!("{error:?}"))
    }

    pub fn dispatch(
        client: &mut Client,
        request: &HostRequest,
        display: (u32, u32),
        coordinates: CoordinateSnapshot,
        frames: &std::sync::Mutex<FramePool>,
        timestamp_nanos: u64,
        snapshot_id: u64,
    ) -> RootDispatch {
        match request.opcode {
            OP_SYSTEM_GET_SCREEN_SIZE if request.args.is_empty() => {
                let mut payload = Vec::with_capacity(8);
                payload.extend_from_slice(&display.0.to_le_bytes());
                payload.extend_from_slice(&display.1.to_le_bytes());
                success(payload)
            }
            OP_INPUT_TAP => dispatch_tap(client, &request.args, coordinates),
            OP_INPUT_SWIPE => dispatch_swipe(client, &request.args, coordinates),
            OP_INPUT_KEY_EVENT => match parse_key(&request.args) {
                Ok(key) => client
                    .key_event(key)
                    .map_or_else(input_client_failure, |()| success(Vec::new())),
                Err(()) => input_failure("invalid key event arguments"),
            },
            OP_SCREEN_CAPTURE if request.args.is_empty() => {
                capture(client, frames, timestamp_nanos, snapshot_id)
            }
            OP_SCREEN_CAPTURE_SERIES_FRAME => {
                capture_series_frame(client, frames, &request.args, timestamp_nanos, snapshot_id)
            }
            _ => failure(
                "HOST_CAPABILITY_UNAVAILABLE",
                "requested host capability is not connected",
                false,
            ),
        }
    }

    fn dispatch_tap(
        client: &mut Client,
        args: &[LuaScalar],
        coordinates: CoordinateSnapshot,
    ) -> RootDispatch {
        let Ok((x, y)) = parse_tap(args) else {
            return input_failure("invalid tap arguments");
        };
        let Ok(point) = map_design_point(coordinates, x, y) else {
            return input_failure("tap point is outside the visible design canvas");
        };
        client
            .tap(point.0, point.1)
            .map_or_else(input_client_failure, |()| success(Vec::new()))
    }

    fn dispatch_swipe(
        client: &mut Client,
        args: &[LuaScalar],
        coordinates: CoordinateSnapshot,
    ) -> RootDispatch {
        let Ok((start, end, duration)) = parse_swipe(args) else {
            return input_failure("invalid swipe arguments");
        };
        let Ok(start) = map_design_point(coordinates, start.0, start.1) else {
            return input_failure("swipe start is outside the visible design canvas");
        };
        let Ok(end) = map_design_point(coordinates, end.0, end.1) else {
            return input_failure("swipe end is outside the visible design canvas");
        };
        client
            .swipe(start, end, duration)
            .map_or_else(input_client_failure, |()| success(Vec::new()))
    }

    fn map_design_point(coordinates: CoordinateSnapshot, x: i32, y: i32) -> Result<(i32, i32), ()> {
        let point = coordinates
            .design_to_display(DesignPoint {
                x: x as f32,
                y: y as f32,
            })
            .map_err(|_| ())?;
        Ok((rounded_i32(point.x)?, rounded_i32(point.y)?))
    }

    #[allow(clippy::cast_possible_truncation)]
    fn rounded_i32(value: f32) -> Result<i32, ()> {
        let rounded = value.round();
        if !rounded.is_finite() || rounded < i32::MIN as f32 || rounded > i32::MAX as f32 {
            return Err(());
        }
        Ok(rounded as i32)
    }

    fn capture(
        client: &mut Client,
        frames: &std::sync::Mutex<FramePool>,
        timestamp_nanos: u64,
        snapshot_id: u64,
    ) -> RootDispatch {
        let capture = match client.capture() {
            Ok(capture) => capture,
            Err(error) => {
                return failure(
                    "SCREEN_CAPTURE_FAILED",
                    "RootDaemon failed to capture the screen",
                    error.is_connection_lost(),
                );
            }
        };
        let format = match capture.format {
            root_client::RawCaptureFormat::Rgba8888 => FrameFormat::Rgba8888,
            root_client::RawCaptureFormat::Bgra8888 => FrameFormat::Bgra8888,
        };
        let captured = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_capture_owned(
                FrameMetadata {
                    width: capture.width,
                    height: capture.height,
                    row_stride: capture.row_stride,
                    pixel_stride: 4,
                    format,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos,
                    snapshot_id,
                },
                std::sync::Arc::from(capture.pixels),
            );
        match captured {
            Ok(capture) => success(capture.0.to_le_bytes().to_vec()),
            Err(_) => failure(
                "SCREEN_CAPTURE_FAILED",
                "engine frame budget rejected the capture",
                false,
            ),
        }
    }

    fn capture_series_frame(
        client: &mut Client,
        frames: &std::sync::Mutex<FramePool>,
        args: &[LuaScalar],
        timestamp_nanos: u64,
        snapshot_id: u64,
    ) -> RootDispatch {
        let [LuaScalar::Integer(encoded)] = args else {
            return failure(
                "SCREEN_SERIES_INVALID",
                "series capture requires one series handle",
                false,
            );
        };
        let Ok(encoded) = u64::try_from(*encoded) else {
            return failure("SCREEN_SERIES_INVALID", "series handle is invalid", false);
        };
        let local_id = encoded & ((1_u64 << 56) - 1);
        let Some(resource_id) = ResourceId::try_new(ResourceKind::CaptureSeries, local_id)
            .filter(|resource| resource.get() == encoded)
        else {
            return failure("SCREEN_SERIES_INVALID", "series handle is invalid", false);
        };
        let capture = match client.capture() {
            Ok(capture) => capture,
            Err(error) => {
                return failure(
                    "SCREEN_CAPTURE_FAILED",
                    "RootDaemon failed to capture a series frame",
                    error.is_connection_lost(),
                );
            }
        };
        let format = match capture.format {
            root_client::RawCaptureFormat::Rgba8888 => FrameFormat::Rgba8888,
            root_client::RawCaptureFormat::Bgra8888 => FrameFormat::Bgra8888,
        };
        let published = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .publish_series_capture_owned(
                CaptureSeriesHandle {
                    series_id: local_id,
                    resource_id,
                },
                FrameMetadata {
                    width: capture.width,
                    height: capture.height,
                    row_stride: capture.row_stride,
                    pixel_stride: 4,
                    format,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos,
                    snapshot_id,
                },
                std::sync::Arc::from(capture.pixels),
            );
        match published {
            Ok(SeriesPublish::DroppedForCadence) => success(vec![0]),
            Ok(SeriesPublish::Frame {
                capture,
                timestamp_nanos,
                finished,
                dropped_frames,
            }) => {
                let mut payload = Vec::with_capacity(22);
                payload.push(1);
                payload.extend_from_slice(&capture.0.to_le_bytes());
                payload.extend_from_slice(&timestamp_nanos.to_le_bytes());
                payload.push(u8::from(finished));
                payload.extend_from_slice(&dropped_frames.to_le_bytes());
                success(payload)
            }
            Ok(SeriesPublish::Finished {
                partial,
                dropped_frames,
                reason,
            }) => {
                let mut payload = Vec::with_capacity(7);
                payload.push(2);
                payload.push(u8::from(partial));
                payload.extend_from_slice(&dropped_frames.to_le_bytes());
                payload.push(reason as u8);
                success(payload)
            }
            Err(automation_core::FrameError::Series(
                automation_core::CaptureSeriesError::GeometryChanged,
            )) => failure(
                "FRAME_GEOMETRY_CHANGED",
                "capture series geometry changed",
                false,
            ),
            Err(error) => failure(
                "SCREEN_SERIES_FAILED",
                &format!("capture series rejected frame: {error:?}"),
                false,
            ),
        }
    }

    fn success(payload: Vec<u8>) -> RootDispatch {
        RootDispatch {
            result: HostResult::Success(payload),
            connection_lost: false,
        }
    }

    fn failure(code: &str, message: &str, connection_lost: bool) -> RootDispatch {
        RootDispatch {
            result: HostResult::Failure {
                code: code.to_owned(),
                message: message.to_owned(),
            },
            connection_lost,
        }
    }

    fn parse_tap(args: &[LuaScalar]) -> Result<(i32, i32), ()> {
        let [LuaScalar::Integer(x), LuaScalar::Integer(y)] = args else {
            return Err(());
        };
        Ok((
            i32::try_from(*x).map_err(|_| ())?,
            i32::try_from(*y).map_err(|_| ())?,
        ))
    }

    fn parse_swipe(args: &[LuaScalar]) -> Result<((i32, i32), (i32, i32), u32), ()> {
        let [LuaScalar::Integer(x1), LuaScalar::Integer(y1), LuaScalar::Integer(x2), LuaScalar::Integer(y2), LuaScalar::Integer(duration)] =
            args
        else {
            return Err(());
        };
        Ok((
            (
                i32::try_from(*x1).map_err(|_| ())?,
                i32::try_from(*y1).map_err(|_| ())?,
            ),
            (
                i32::try_from(*x2).map_err(|_| ())?,
                i32::try_from(*y2).map_err(|_| ())?,
            ),
            u32::try_from(*duration).map_err(|_| ())?,
        ))
    }

    fn parse_key(args: &[LuaScalar]) -> Result<u32, ()> {
        let [LuaScalar::Integer(key)] = args else {
            return Err(());
        };
        u32::try_from(*key).map_err(|_| ())
    }

    fn input_failure(message: &str) -> RootDispatch {
        failure("ROOT_INPUT_FAILED", message, false)
    }

    fn input_client_failure(error: root_client::ClientError) -> RootDispatch {
        failure(
            "ROOT_INPUT_FAILED",
            "Root input backend rejected the request",
            error.is_connection_lost(),
        )
    }
}

#[cfg(not(target_os = "android"))]
mod platform_root {
    use std::time::Duration;

    use super::{CoordinateSnapshot, FramePool, HostRequest, HostResult, RootDispatch};

    pub struct Client;

    pub fn connect(_path: &str, _key: [u8; 32], _timeout: Duration) -> Result<Client, String> {
        Err("RootDaemon is only available on Android".to_owned())
    }

    #[allow(clippy::unnecessary_wraps)]
    pub fn shutdown(_client: &mut Client) -> Result<(), String> {
        Ok(())
    }

    pub fn dispatch(
        _client: &mut Client,
        _request: &HostRequest,
        _display: (u32, u32),
        _coordinates: CoordinateSnapshot,
        _frames: &std::sync::Mutex<FramePool>,
        _timestamp_nanos: u64,
        _snapshot_id: u64,
    ) -> RootDispatch {
        RootDispatch {
            result: HostResult::Failure {
                code: "ROOT_UNAVAILABLE".to_owned(),
                message: "RootDaemon is only available on Android".to_owned(),
            },
            connection_lost: true,
        }
    }
}

#[derive(Debug)]
struct NativeSession {
    commands: SyncSender<SessionCommand>,
    state: Arc<AtomicI32>,
    next_wake: Arc<AtomicI64>,
    last_diagnostic: Arc<Mutex<Option<String>>>,
    stop_handle: Mutex<SchedulerHandle>,
    stop_requested: Arc<AtomicBool>,
    stop_boot_nanos: Arc<AtomicU64>,
    pause_request: Arc<Mutex<Option<PauseControl>>>,
    shutdown_requested: Arc<AtomicBool>,
    worker: Mutex<Option<JoinHandle<()>>>,
    root_control: SyncSender<RootControl>,
    host_queue: ExternalHostQueue,
    root_worker: Mutex<Option<JoinHandle<()>>>,
    root_attached: Arc<AtomicBool>,
    wake_callback: Arc<Mutex<Option<WakeCallback>>>,
}

#[derive(Debug, Clone, Copy)]
enum PauseControl {
    Pause(u64),
    Resume(u64),
}

struct WakeCallback {
    vm: JavaVM,
    listener: GlobalRef,
}

impl std::fmt::Debug for WakeCallback {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("WakeCallback")
    }
}

impl WakeCallback {
    fn notify(&self) {
        self.call("onNativeWake");
    }

    fn notify_root_disconnected(&self) {
        self.call("onNativeRootDisconnected");
    }

    fn call(&self, method: &str) {
        let Ok(mut env) = self.vm.attach_current_thread_as_daemon() else {
            return;
        };
        if env
            .call_method(self.listener.as_obj(), method, "()V", &[])
            .is_err()
            || env.exception_check().unwrap_or(false)
        {
            let _ = env.exception_clear();
        }
    }
}

#[derive(Clone)]
struct RootWorkerContext {
    host_queue: ExternalHostQueue,
    display_width: Arc<AtomicU32>,
    display_height: Arc<AtomicU32>,
    wake_callback: Arc<Mutex<Option<WakeCallback>>>,
    frames: Arc<Mutex<FramePool>>,
    boot_nanos: Arc<AtomicU64>,
    snapshot_id: Arc<AtomicU64>,
    coordinates: Arc<Mutex<CoordinateSnapshot>>,
    attached: Arc<AtomicBool>,
}

fn spawn_root_worker(
    receiver: Receiver<RootControl>,
    context: RootWorkerContext,
) -> Result<JoinHandle<()>, String> {
    thread::Builder::new()
        .name("autoscript-root-io".to_owned())
        .spawn(move || root_worker_loop(&receiver, &context))
        .map_err(|error| error.to_string())
}

impl NativeSession {
    #[allow(clippy::too_many_lines)] // Session construction wires two bounded worker domains.
    fn spawn(width: u32, height: u32) -> Result<Arc<Self>, String> {
        let (commands, receiver) = mpsc::sync_channel(32);
        let host_queue = ExternalHostQueue::new(HOST_QUEUE_CAPACITY, HOST_CANCELLATION_CAPACITY)?;
        let engine_config = EngineSessionConfig::default();
        let frames = Arc::new(Mutex::new(FramePool::new(engine_config.frames)));
        let session_host_queue = host_queue.clone();
        let display_width = Arc::new(AtomicU32::new(width));
        let display_height = Arc::new(AtomicU32::new(height));
        let boot_nanos = Arc::new(AtomicU64::new(0));
        let snapshot_id = Arc::new(AtomicU64::new(1));
        let coordinates = Arc::new(Mutex::new(initial_coordinates(width, height)?));
        let engine_boot_nanos = Arc::clone(&boot_nanos);
        let engine_snapshot_id = Arc::clone(&snapshot_id);
        let wake_callback = Arc::new(Mutex::new(None));
        let engine_display_width = Arc::clone(&display_width);
        let engine_display_height = Arc::clone(&display_height);
        let engine_coordinates = Arc::clone(&coordinates);
        let (root_control, root_receiver) = mpsc::sync_channel(2);
        let root_attached = Arc::new(AtomicBool::new(false));
        let root_worker = spawn_root_worker(
            root_receiver,
            RootWorkerContext {
                host_queue: host_queue.clone(),
                display_width: Arc::clone(&display_width),
                display_height: Arc::clone(&display_height),
                wake_callback: Arc::clone(&wake_callback),
                frames: Arc::clone(&frames),
                boot_nanos: Arc::clone(&boot_nanos),
                snapshot_id: Arc::clone(&snapshot_id),
                coordinates: Arc::clone(&coordinates),
                attached: Arc::clone(&root_attached),
            },
        )?;
        let state = Arc::new(AtomicI32::new(STATE_IDLE));
        let next_wake = Arc::new(AtomicI64::new(NEXT_IDLE));
        let last_diagnostic = Arc::new(Mutex::new(None));
        let worker_state = Arc::clone(&state);
        let worker_wake = Arc::clone(&next_wake);
        let worker_diagnostic = Arc::clone(&last_diagnostic);
        let stop_requested = Arc::new(AtomicBool::new(false));
        let stop_boot_nanos = Arc::new(AtomicU64::new(0));
        let pause_request = Arc::new(Mutex::new(None));
        let shutdown_requested = Arc::new(AtomicBool::new(false));
        let worker_stop_requested = Arc::clone(&stop_requested);
        let worker_stop_boot_nanos = Arc::clone(&stop_boot_nanos);
        let worker_pause_request = Arc::clone(&pause_request);
        let worker_shutdown_requested = Arc::clone(&shutdown_requested);
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let worker = thread::Builder::new()
            .name("autoscript-lua-vm".to_owned())
            .spawn(move || {
                let mut engine = match EngineSession::new_external(
                    width,
                    height,
                    engine_config.clone(),
                    host_queue.clone(),
                    Arc::clone(&frames),
                ) {
                    Ok(engine) => engine,
                    Err(error) => {
                        let _ = ready_tx.send(Err(format!("{error:?}")));
                        worker_state.store(STATE_FAILED, Ordering::Release);
                        return;
                    }
                };
                let _ = ready_tx.send(Ok(engine.scheduler_handle()));
                worker_loop(
                    &mut engine,
                    &receiver,
                    WorkerSignals {
                        state: &worker_state,
                        next_wake: &worker_wake,
                        last_diagnostic: &worker_diagnostic,
                        stop_requested: &worker_stop_requested,
                        stop_boot_nanos: &worker_stop_boot_nanos,
                        pause_request: &worker_pause_request,
                        shutdown_requested: &worker_shutdown_requested,
                        display_width: &engine_display_width,
                        display_height: &engine_display_height,
                        boot_nanos: &engine_boot_nanos,
                        snapshot_id: &engine_snapshot_id,
                        coordinates: &engine_coordinates,
                        engine_config: &engine_config,
                        host_queue: &host_queue,
                        frames: &frames,
                    },
                );
            })
            .map_err(|error| error.to_string())?;
        let stop_handle_result = ready_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("engine worker did not initialize: {error}"))?;
        let stop_handle = match stop_handle_result {
            Ok(handle) => handle,
            Err(error) => {
                let _ = root_control.try_send(RootControl::Shutdown);
                let _ = root_worker.join();
                let _ = worker.join();
                return Err(error);
            }
        };
        Ok(Arc::new(Self {
            commands,
            state,
            next_wake,
            last_diagnostic,
            stop_handle: Mutex::new(stop_handle),
            stop_requested,
            stop_boot_nanos,
            pause_request,
            shutdown_requested,
            worker: Mutex::new(Some(worker)),
            root_control,
            host_queue: session_host_queue,
            root_worker: Mutex::new(Some(root_worker)),
            root_attached,
            wake_callback,
        }))
    }

    fn request(
        &self,
        build: impl FnOnce(SyncSender<Result<(), String>>) -> SessionCommand,
    ) -> Result<(), String> {
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.commands
            .try_send(build(response_tx))
            .map_err(|error| format!("engine command queue rejected request: {error}"))?;
        response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("engine command timed out: {error}"))?
    }

    fn request_stop(&self, boot_nanos: u64) -> Result<(), String> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return Err("SESSION_CLOSED".to_owned());
        }
        self.stop_boot_nanos.store(boot_nanos, Ordering::Release);
        self.stop_requested.store(true, Ordering::Release);
        self.stop_handle
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .request_stop();
        self.state.store(STATE_STOPPING, Ordering::Release);
        self.next_wake.store(0, Ordering::Release);
        // A full business queue is not an error: the worker checks this atomic flag before
        // taking every subsequent business command.
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        Ok(())
    }

    fn request_pause(&self, boot_nanos: u64) -> Result<(), String> {
        if self.state.load(Ordering::Acquire) != STATE_RUNNING {
            return Err("SESSION_NOT_RUNNING".to_owned());
        }
        let mut request = self
            .pause_request
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if request.is_some() {
            return Err("SESSION_CONTROL_PENDING".to_owned());
        }
        *request = Some(PauseControl::Pause(boot_nanos));
        drop(request);
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        Ok(())
    }

    fn request_resume(&self, boot_nanos: u64) -> Result<(), String> {
        if self.state.load(Ordering::Acquire) != STATE_PAUSED {
            return Err("SESSION_NOT_PAUSED".to_owned());
        }
        let mut request = self
            .pause_request
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if request.is_some() {
            return Err("SESSION_CONTROL_PENDING".to_owned());
        }
        *request = Some(PauseControl::Resume(boot_nanos));
        drop(request);
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        Ok(())
    }

    fn last_diagnostic(&self) -> Option<String> {
        self.last_diagnostic
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    fn reset(&self, width: u32, height: u32) -> Result<(), String> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return Err("SESSION_CLOSED".to_owned());
        }
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::Reset {
                width,
                height,
                response: response_tx,
            })
            .map_err(|error| format!("engine command queue rejected reset: {error}"))?;
        let handle = response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("engine reset timed out: {error}"))??;
        *self
            .stop_handle
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = handle;
        Ok(())
    }

    fn configure_project(
        &self,
        snapshot_id: u64,
        design_width: u32,
        design_height: u32,
        scale_mode: ScaleMode,
    ) -> Result<(), String> {
        self.request(|response| SessionCommand::ConfigureProject {
            snapshot_id,
            design_width,
            design_height,
            scale_mode,
            response,
        })
    }

    fn register_template(
        &self,
        name: String,
        width: u32,
        height: u32,
        pixels: Vec<u8>,
    ) -> Result<(), String> {
        self.request(|response| SessionCommand::RegisterTemplate {
            name,
            width,
            height,
            pixels,
            response,
        })
    }

    fn register_dictionary(&self, path: String, bytes: Vec<u8>) -> Result<(), String> {
        self.request(|response| SessionCommand::RegisterDictionary {
            path,
            bytes,
            response,
        })
    }

    fn attach_root(
        &self,
        socket_path: String,
        key: [u8; 32],
        timeout: Duration,
    ) -> Result<(), String> {
        if self.root_attached.swap(true, Ordering::AcqRel) {
            return Err("RootDaemon is already attached".to_owned());
        }
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        if let Err(error) = self.root_control.try_send(RootControl::Attach {
            socket_path,
            key,
            timeout,
            response: response_tx,
        }) {
            self.root_attached.store(false, Ordering::Release);
            return Err(format!("RootDaemon control queue rejected attach: {error}"));
        }
        self.host_queue.interrupt();
        let result = response_rx
            .recv_timeout(timeout.saturating_add(ROOT_ATTACH_GRACE))
            .map_err(|error| format!("RootDaemon attach timed out: {error}"));
        let result = match result {
            Ok(result) => result,
            Err(error) => {
                self.root_attached.store(false, Ordering::Release);
                let (response, _) = mpsc::sync_channel(1);
                let _ = self
                    .root_control
                    .try_send(RootControl::Disconnect { response });
                self.host_queue.interrupt();
                return Err(error);
            }
        };
        if result.is_err() {
            self.root_attached.store(false, Ordering::Release);
        }
        result
    }

    fn detach_root(&self) -> Result<(), String> {
        self.root_attached.store(false, Ordering::Release);
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.root_control
            .try_send(RootControl::Disconnect {
                response: response_tx,
            })
            .map_err(|error| format!("RootDaemon control queue rejected disconnect: {error}"))?;
        self.host_queue.interrupt();
        response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("RootDaemon disconnect timed out: {error}"))
    }

    fn set_wake_callback(&self, callback: WakeCallback) {
        *self
            .wake_callback
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(callback);
    }

    fn shutdown(&self) {
        self.stop_boot_nanos.store(0, Ordering::Release);
        self.stop_requested.store(true, Ordering::Release);
        *self
            .pause_request
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        self.shutdown_requested.store(true, Ordering::Release);
        self.stop_handle
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .request_stop();
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        self.host_queue.request_stop();
        let _ = self.root_control.try_send(RootControl::Shutdown);
        self.host_queue.interrupt();
        let root_worker = self
            .root_worker
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        if let Some(root_worker) = root_worker {
            let _ = root_worker.join();
        }
        let worker = self
            .worker
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        if let Some(worker) = worker {
            let _ = worker.join();
        }
    }
}

fn initial_coordinates(width: u32, height: u32) -> Result<CoordinateSnapshot, String> {
    let display_size = Size {
        width: f32::from(u16::try_from(width).map_err(|_| "invalid display width".to_owned())?),
        height: f32::from(u16::try_from(height).map_err(|_| "invalid display height".to_owned())?),
    };
    CoordinateSnapshot::letterbox(1, display_size, display_size)
        .map_err(|error| format!("invalid display geometry: {error:?}"))
}

#[derive(Clone, Copy)]
struct WorkerSignals<'a> {
    state: &'a AtomicI32,
    next_wake: &'a AtomicI64,
    last_diagnostic: &'a Mutex<Option<String>>,
    stop_requested: &'a AtomicBool,
    stop_boot_nanos: &'a AtomicU64,
    pause_request: &'a Mutex<Option<PauseControl>>,
    shutdown_requested: &'a AtomicBool,
    display_width: &'a AtomicU32,
    display_height: &'a AtomicU32,
    boot_nanos: &'a AtomicU64,
    snapshot_id: &'a AtomicU64,
    coordinates: &'a Mutex<CoordinateSnapshot>,
    engine_config: &'a EngineSessionConfig,
    host_queue: &'a ExternalHostQueue,
    frames: &'a Arc<Mutex<FramePool>>,
}

fn worker_loop(
    engine: &mut EngineSession,
    receiver: &Receiver<SessionCommand>,
    signals: WorkerSignals<'_>,
) {
    let WorkerSignals {
        state,
        next_wake,
        stop_requested,
        stop_boot_nanos,
        pause_request,
        shutdown_requested,
        ..
    } = signals;
    loop {
        if stop_requested.swap(false, Ordering::AcqRel) {
            *pause_request
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
            let boot_nanos = stop_boot_nanos.load(Ordering::Acquire);
            match engine.stop(boot_nanos) {
                Err(error) if engine.state() != EngineState::Stopped => {
                    *signals
                        .last_diagnostic
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(
                        runtime_diagnostic(&format!("ENGINE_STOP_FAILED: {error:?}")),
                    );
                    state.store(STATE_FAILED, Ordering::Release);
                    next_wake.store(NEXT_STOPPED, Ordering::Release);
                }
                Err(_) | Ok(_) => publish_engine_state(engine, signals),
            }
        }
        let control = pause_request
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        match control {
            Some(PauseControl::Pause(boot_nanos)) => {
                let result = engine.pause(boot_nanos);
                publish_control_result(engine, result, "ENGINE_PAUSE_FAILED", signals);
            }
            Some(PauseControl::Resume(boot_nanos)) => {
                let result = engine.resume(boot_nanos);
                publish_control_result(engine, result, "ENGINE_RESUME_FAILED", signals);
            }
            None => {}
        }
        if shutdown_requested.load(Ordering::Acquire) {
            return;
        }
        let Ok(command) = receiver.recv() else {
            return;
        };
        handle_session_command(engine, command, signals);
    }
}

fn publish_control_result(
    engine: &EngineSession,
    result: Result<(), engine_core::EngineSessionError>,
    code: &str,
    signals: WorkerSignals<'_>,
) {
    if let Err(error) = result {
        *signals
            .last_diagnostic
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) =
            Some(runtime_diagnostic(&format!("{code}: {error:?}")));
    }
    publish_engine_state(engine, signals);
}

fn handle_session_command(
    engine: &mut EngineSession,
    command: SessionCommand,
    signals: WorkerSignals<'_>,
) {
    let WorkerSignals {
        state,
        next_wake,
        boot_nanos: current_boot_nanos,
        snapshot_id: current_snapshot_id,
        ..
    } = signals;
    match command {
        SessionCommand::Reset {
            width,
            height,
            response,
        } => handle_reset_command(engine, width, height, &response, signals),
        SessionCommand::Start {
            source,
            capabilities,
            response,
        } => {
            let result = engine
                .start(&source, "android-runner", &capabilities)
                .map(|_| ())
                .map_err(|error| format!("{error:?}"));
            publish_engine_state(engine, signals);
            let _ = response.send(result);
        }
        SessionCommand::Pump {
            boot_nanos,
            response,
        } => {
            current_boot_nanos.store(boot_nanos, Ordering::Release);
            let result = engine
                .pump(boot_nanos)
                .map(|_| ())
                .map_err(|error| format!("{error:?}"));
            if let Err(error) = &result {
                *signals
                    .last_diagnostic
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner) =
                    Some(runtime_diagnostic(&format!("ENGINE_PUMP_FAILED: {error}")));
                state.store(STATE_FAILED, Ordering::Release);
                next_wake.store(NEXT_STOPPED, Ordering::Release);
            } else {
                publish_engine_state(engine, signals);
            }
            let _ = response.send(result);
        }
        SessionCommand::UpdateDisplay {
            snapshot_id,
            width,
            height,
            response,
        } => handle_display_update(engine, snapshot_id, width, height, &response, signals),
        SessionCommand::ConfigureProject {
            snapshot_id,
            design_width,
            design_height,
            scale_mode,
            response,
        } => handle_project_configuration(
            engine,
            snapshot_id,
            design_width,
            design_height,
            scale_mode,
            &response,
            signals,
        ),
        SessionCommand::RegisterTemplate {
            name,
            width,
            height,
            pixels,
            response,
        } => {
            let result = register_template_command(
                engine,
                &name,
                width,
                height,
                pixels,
                current_boot_nanos.load(Ordering::Acquire),
                current_snapshot_id.load(Ordering::Acquire),
            );
            let _ = response.send(result);
        }
        SessionCommand::RegisterDictionary {
            path,
            bytes,
            response,
        } => {
            let _ = response.send(register_dictionary_command(engine, &path, &bytes));
        }
        SessionCommand::ControlWake => {}
    }
}

fn handle_reset_command(
    engine: &mut EngineSession,
    width: u32,
    height: u32,
    response: &SyncSender<Result<SchedulerHandle, String>>,
    signals: WorkerSignals<'_>,
) {
    let result = reset_engine(engine, width, height, signals);
    if result.is_ok() {
        *signals
            .last_diagnostic
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        signals.state.store(STATE_IDLE, Ordering::Release);
        signals.next_wake.store(NEXT_IDLE, Ordering::Release);
    } else {
        signals.state.store(STATE_FAILED, Ordering::Release);
        signals.next_wake.store(NEXT_STOPPED, Ordering::Release);
    }
    let _ = response.send(result);
}

fn handle_display_update(
    engine: &mut EngineSession,
    snapshot_id: u64,
    width: u32,
    height: u32,
    response: &SyncSender<Result<(), String>>,
    signals: WorkerSignals<'_>,
) {
    let result = engine
        .update_full_display(snapshot_id, width, height)
        .map_err(|error| format!("{error:?}"));
    if result.is_ok() {
        signals.display_width.store(width, Ordering::Release);
        signals.display_height.store(height, Ordering::Release);
        signals.snapshot_id.store(snapshot_id, Ordering::Release);
        *signals
            .coordinates
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = engine.coordinate_snapshot();
    }
    publish_engine_state(engine, signals);
    let _ = response.send(result);
}

fn handle_project_configuration(
    engine: &mut EngineSession,
    snapshot_id: u64,
    design_width: u32,
    design_height: u32,
    scale_mode: ScaleMode,
    response: &SyncSender<Result<(), String>>,
    signals: WorkerSignals<'_>,
) {
    let result = engine
        .configure_project_geometry(snapshot_id, design_width, design_height, scale_mode)
        .map_err(|error| format!("{error:?}"));
    if result.is_ok() {
        signals.snapshot_id.store(snapshot_id, Ordering::Release);
        *signals
            .coordinates
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = engine.coordinate_snapshot();
    }
    publish_engine_state(engine, signals);
    let _ = response.send(result);
}

fn reset_engine(
    engine: &mut EngineSession,
    width: u32,
    height: u32,
    signals: WorkerSignals<'_>,
) -> Result<SchedulerHandle, String> {
    let unloaded = engine.state() == EngineState::Running && engine.root_task().is_none();
    if !unloaded && !matches!(engine.state(), EngineState::Stopped | EngineState::Failed) {
        return Err("engine is running or stopping".to_owned());
    }
    signals.host_queue.reset_pending()?;
    *signals
        .frames
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) =
        FramePool::new(signals.engine_config.frames);
    let replacement = EngineSession::new_external(
        width,
        height,
        signals.engine_config.clone(),
        signals.host_queue.clone(),
        Arc::clone(signals.frames),
    )
    .map_err(|error| format!("{error:?}"))?;
    let scheduler = replacement.scheduler_handle();
    *signals
        .coordinates
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = replacement.coordinate_snapshot();
    *engine = replacement;
    Ok(scheduler)
}

fn register_template_command(
    engine: &mut EngineSession,
    name: &str,
    width: u32,
    height: u32,
    pixels: Vec<u8>,
    timestamp_nanos: u64,
    snapshot_id: u64,
) -> Result<(), String> {
    let row_stride = width
        .checked_mul(4)
        .ok_or_else(|| "template row stride overflow".to_owned())?;
    engine
        .register_template(
            name,
            FrameMetadata {
                width,
                height,
                row_stride,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos,
                snapshot_id,
            },
            Arc::from(pixels),
        )
        .map_err(|error| format!("{error:?}"))
}

fn register_dictionary_command(
    engine: &mut EngineSession,
    path: &str,
    bytes: &[u8],
) -> Result<(), String> {
    engine
        .register_dictionary(path, bytes)
        .map_err(|error| format!("{error:?}"))
}

fn root_worker_loop(receiver: &Receiver<RootControl>, context: &RootWorkerContext) {
    let mut client = None;
    loop {
        if client.is_none() {
            client = wait_for_root_client(receiver, context);
            if client.is_none() {
                return;
            }
            continue;
        }

        match receiver.try_recv() {
            Ok(RootControl::Attach { response, .. }) => {
                let _ = response.send(Err("RootDaemon is already attached".to_owned()));
                continue;
            }
            Ok(RootControl::Disconnect { response }) => {
                if let Some(mut connected) = client.take() {
                    let _ = platform_root::shutdown(&mut connected);
                }
                context.attached.store(false, Ordering::Release);
                let _ = response.send(());
                continue;
            }
            Ok(RootControl::Shutdown) => {
                if let Some(mut connected) = client.take() {
                    let _ = platform_root::shutdown(&mut connected);
                }
                context.attached.store(false, Ordering::Release);
                return;
            }
            Err(mpsc::TryRecvError::Disconnected) => return,
            Err(mpsc::TryRecvError::Empty) => {}
        }

        match context.host_queue.wait_next() {
            ExternalHostEvent::Dispatch {
                request,
                completion,
            } => {
                let coordinates = *context
                    .coordinates
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                let dispatch = platform_root::dispatch(
                    client.as_mut().expect("attached client"),
                    &request,
                    (
                        context.display_width.load(Ordering::Acquire),
                        context.display_height.load(Ordering::Acquire),
                    ),
                    coordinates,
                    &context.frames,
                    context.boot_nanos.load(Ordering::Acquire),
                    context.snapshot_id.load(Ordering::Acquire),
                );
                let submitted = completion.submit_host_completion(HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result: dispatch.result,
                });
                if submitted.is_ok() {
                    let callback = context
                        .wake_callback
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    if let Some(callback) = callback.as_ref() {
                        callback.notify();
                    }
                }
                if dispatch.connection_lost {
                    client = None;
                    notify_root_disconnected(context);
                }
            }
            ExternalHostEvent::Cancel { request_id, .. } => {
                #[cfg(target_os = "android")]
                if client.as_mut().is_some_and(|client| {
                    client
                        .cancel(request_id.0)
                        .is_err_and(|error| error.is_connection_lost())
                }) {
                    client = None;
                    notify_root_disconnected(context);
                }
                #[cfg(not(target_os = "android"))]
                let _ = request_id;
            }
            ExternalHostEvent::Interrupted => {}
            ExternalHostEvent::Stop => {
                if let Some(mut connected) = client.take() {
                    let _ = platform_root::shutdown(&mut connected);
                }
                context.attached.store(false, Ordering::Release);
                return;
            }
        }
    }
}

fn wait_for_root_client(
    receiver: &Receiver<RootControl>,
    context: &RootWorkerContext,
) -> Option<platform_root::Client> {
    loop {
        let Ok(control) = receiver.recv() else {
            return None;
        };
        match control {
            RootControl::Attach {
                socket_path,
                key,
                timeout,
                response,
            } => match platform_root::connect(&socket_path, key, timeout) {
                Ok(client) => {
                    context.attached.store(true, Ordering::Release);
                    let _ = response.send(Ok(()));
                    return Some(client);
                }
                Err(error) => {
                    context.attached.store(false, Ordering::Release);
                    let _ = response.send(Err(error));
                }
            },
            RootControl::Disconnect { response } => {
                context.attached.store(false, Ordering::Release);
                let _ = response.send(());
            }
            RootControl::Shutdown => return None,
        }
    }
}

fn notify_root_disconnected(context: &RootWorkerContext) {
    context.attached.store(false, Ordering::Release);
    let callback = context
        .wake_callback
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(callback) = callback.as_ref() {
        callback.notify_root_disconnected();
    }
}

fn publish_engine_state(engine: &EngineSession, signals: WorkerSignals<'_>) {
    let state_code = if signals.stop_requested.load(Ordering::Acquire) {
        STATE_STOPPING
    } else {
        match engine.state() {
            EngineState::Created | EngineState::Starting => STATE_IDLE,
            EngineState::Running => STATE_RUNNING,
            EngineState::Paused => STATE_PAUSED,
            EngineState::Stopping => STATE_STOPPING,
            EngineState::Stopped => STATE_STOPPED,
            EngineState::Failed => STATE_FAILED,
        }
    };
    let diagnostic = if state_code == STATE_FAILED {
        let message = engine.root_failure().map_or_else(
            || "ENGINE_FAILED: 引擎进入失败状态".to_owned(),
            |failure| format!("{}: {}", failure.code, failure.message),
        );
        Some(runtime_diagnostic(&message))
    } else {
        None
    };
    *signals
        .last_diagnostic
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner) = diagnostic;
    let wake = match engine.next_wake() {
        SchedulerPoll::Runnable => 0,
        SchedulerPoll::Deadline(deadline) | SchedulerPoll::PausedUntil(deadline) => {
            i64::try_from(deadline.as_nanos()).unwrap_or(i64::MAX)
        }
        SchedulerPoll::Idle | SchedulerPoll::Paused => NEXT_IDLE,
        SchedulerPoll::Stopped => NEXT_STOPPED,
    };
    signals.state.store(state_code, Ordering::Release);
    signals.next_wake.store(wake, Ordering::Release);
}

fn runtime_diagnostic(message: &str) -> String {
    message.chars().take(MAX_RUNTIME_DIAGNOSTIC_CHARS).collect()
}

#[derive(Debug)]
struct Slot {
    generation: u32,
    session: Option<Arc<NativeSession>>,
}

#[derive(Debug, Default)]
struct Registry {
    slots: Vec<Slot>,
}

impl Registry {
    fn insert(&mut self, session: Arc<NativeSession>) -> Result<u64, String> {
        if let Some((index, slot)) = self
            .slots
            .iter_mut()
            .enumerate()
            .find(|(_, slot)| slot.session.is_none() && slot.generation < u32::MAX)
        {
            slot.generation += 1;
            slot.session = Some(session);
            return encode_handle(index, slot.generation);
        }
        let index = self.slots.len();
        self.slots.push(Slot {
            generation: 1,
            session: Some(session),
        });
        encode_handle(index, 1)
    }

    fn get(&self, handle: u64) -> Result<Arc<NativeSession>, String> {
        let (index, generation) = decode_handle(handle)?;
        self.slots
            .get(index)
            .filter(|slot| slot.generation == generation)
            .and_then(|slot| slot.session.as_ref())
            .cloned()
            .ok_or_else(|| "SESSION_CLOSED".to_owned())
    }

    fn remove(&mut self, handle: u64) -> Result<Arc<NativeSession>, String> {
        let (index, generation) = decode_handle(handle)?;
        let slot = self
            .slots
            .get_mut(index)
            .filter(|slot| slot.generation == generation)
            .ok_or_else(|| "SESSION_CLOSED".to_owned())?;
        slot.session
            .take()
            .ok_or_else(|| "SESSION_CLOSED".to_owned())
    }
}

fn encode_handle(index: usize, generation: u32) -> Result<u64, String> {
    let slot = u32::try_from(index)
        .map_err(|_| "native session registry exhausted".to_owned())?
        .checked_add(1)
        .ok_or_else(|| "native session registry exhausted".to_owned())?;
    Ok((u64::from(generation) << 32) | u64::from(slot))
}

fn decode_handle(handle: u64) -> Result<(usize, u32), String> {
    let slot = u32::try_from(handle & 0xffff_ffff).map_err(|error| error.to_string())?;
    let generation = u32::try_from(handle >> 32).map_err(|error| error.to_string())?;
    if slot == 0 || generation == 0 {
        return Err("SESSION_CLOSED".to_owned());
    }
    Ok(((slot - 1) as usize, generation))
}

fn registry() -> &'static Mutex<Registry> {
    static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

fn create_session(width: u32, height: u32) -> Result<u64, String> {
    let session = NativeSession::spawn(width, height)?;
    registry()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .insert(session)
}

fn get_session(handle: u64) -> Result<Arc<NativeSession>, String> {
    registry()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .get(handle)
}

fn destroy_session(handle: u64) -> Result<(), String> {
    let session = registry()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .remove(handle)?;
    session.shutdown();
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeCreate(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    width: jint,
    height: jint,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let width = u32::try_from(width).map_err(|_| "invalid display width".to_owned())?;
        let height = u32::try_from(height).map_err(|_| "invalid display height".to_owned())?;
        create_session(width, height)
    }));
    match result {
        Ok(Ok(handle)) => i64::try_from(handle).unwrap_or(0),
        Ok(Err(_)) | Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeSetWakeListener(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    listener: JObject<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let vm = env.get_java_vm().map_err(|error| error.to_string())?;
        let listener = env
            .new_global_ref(listener)
            .map_err(|error| error.to_string())?;
        get_session(handle)?.set_wake_callback(WakeCallback { vm, listener });
        Ok::<(), String>(())
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeRegisterTemplate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    name: JString<'_>,
    width: jint,
    height: jint,
    pixels: JByteArray<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let name = env
            .get_string(&name)
            .map_err(|error| error.to_string())?
            .into();
        let width = u32::try_from(width).map_err(|_| "invalid template width".to_owned())?;
        let height = u32::try_from(height).map_err(|_| "invalid template height".to_owned())?;
        if width == 0 || height == 0 || width > 4_096 || height > 4_096 {
            return Err("template dimensions outside 1..4096".to_owned());
        }
        let pixels = env
            .convert_byte_array(pixels)
            .map_err(|error| error.to_string())?;
        let expected = usize::try_from(width)
            .ok()
            .and_then(|width| width.checked_mul(4))
            .and_then(|stride| {
                usize::try_from(height)
                    .ok()
                    .and_then(|height| stride.checked_mul(height))
            })
            .ok_or_else(|| "template dimensions overflow".to_owned())?;
        if pixels.len() != expected {
            return Err("template RGBA byte length mismatch".to_owned());
        }
        get_session(handle)?.register_template(name, width, height, pixels)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeRegisterDictionary(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    path: JString<'_>,
    bytes: JByteArray<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let path = env
            .get_string(&path)
            .map_err(|error| error.to_string())?
            .into();
        let byte_length = usize::try_from(
            env.get_array_length(&bytes)
                .map_err(|error| error.to_string())?,
        )
        .map_err(|_| "dictionary byte length is invalid".to_owned())?;
        if byte_length == 0 || byte_length > MAX_DICTIONARY_BYTES {
            return Err("dictionary byte length outside 1..8 MiB".to_owned());
        }
        let bytes = env
            .convert_byte_array(bytes)
            .map_err(|error| error.to_string())?;
        get_session(handle)?.register_dictionary(path, bytes)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeValidateLua(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    source: JByteArray<'_>,
    chunk_name: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let source = env
            .convert_byte_array(source)
            .map_err(|error| error.to_string())?;
        let chunk_name: String = env
            .get_string(&chunk_name)
            .map_err(|error| error.to_string())?
            .into();
        lua_runtime::validate_text_chunk(&source, &chunk_name).map_err(|error| error.to_string())
    }));
    let diagnostic = match result {
        Ok(Ok(())) => String::new(),
        Ok(Err(error)) => error,
        Err(_) => "Lua validator internal failure".to_owned(),
    };
    env.new_string(diagnostic)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeCompileVisualProject(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|error| error.to_string())?
            .into();
        Ok::<_, String>(visual_compile::compile_project_directory(
            std::path::Path::new(&directory),
        ))
    }));
    let reply = match result {
        Ok(Ok(reply)) => reply,
        Ok(Err(error)) => visual_compile::internal_error_reply(&error),
        Err(_) => visual_compile::internal_error_reply("visual compiler panicked"),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeValidateVisualDraft(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
    flow_id: JString<'_>,
    draft: JByteArray<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|error| error.to_string())?
            .into();
        let flow_id: String = env
            .get_string(&flow_id)
            .map_err(|error| error.to_string())?
            .into();
        let draft = env
            .convert_byte_array(draft)
            .map_err(|error| error.to_string())?;
        Ok::<_, String>(visual_compile::validate_project_draft(
            std::path::Path::new(&directory),
            &flow_id,
            &draft,
        ))
    }));
    let reply = match result {
        Ok(Ok(reply)) => reply,
        Ok(Err(error)) => visual_compile::internal_error_reply(&error),
        Err(_) => visual_compile::internal_error_reply("visual draft validator panicked"),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeReset(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    width: jint,
    height: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let width = u32::try_from(width).map_err(|_| "invalid display width".to_owned())?;
        let height = u32::try_from(height).map_err(|_| "invalid display height".to_owned())?;
        get_session(handle)?.reset(width, height)
    }));
    finish_jni(&result)
}

fn read_string_array(
    env: &mut JNIEnv<'_>,
    array: &JObjectArray<'_>,
) -> Result<Vec<String>, String> {
    let length = env
        .get_array_length(array)
        .map_err(|error| error.to_string())?;
    let capacity = usize::try_from(length).map_err(|_| "invalid capability count".to_owned())?;
    if capacity > MAX_PROJECT_CAPABILITIES {
        return Err("more than 64 project capabilities".to_owned());
    }
    let mut result = Vec::with_capacity(capacity);
    for index in 0..length {
        let object = env
            .get_object_array_element(array, index)
            .map_err(|error| error.to_string())?;
        if object.is_null() {
            return Err("project capability must not be null".to_owned());
        }
        let value: String = env
            .get_string(&JString::from(object))
            .map_err(|error| error.to_string())?
            .into();
        if value.len() > MAX_CAPABILITY_BYTES {
            return Err("project capability exceeds 128 bytes".to_owned());
        }
        result.push(value);
    }
    Ok(result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeStart(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    source: JByteArray<'_>,
    capabilities: JObjectArray<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let source = env
            .convert_byte_array(source)
            .map_err(|error| error.to_string())?;
        let capabilities = read_string_array(&mut env, &capabilities)?;
        get_session(handle)?.request(|response| SessionCommand::Start {
            source,
            capabilities,
            response,
        })
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativePump(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    boot_nanos: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let boot_nanos = u64::try_from(boot_nanos).map_err(|_| "invalid boot clock".to_owned())?;
        get_session(handle)?.request(|response| SessionCommand::Pump {
            boot_nanos,
            response,
        })
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeUpdateDisplay(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    snapshot_id: jlong,
    width: jint,
    height: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let snapshot_id =
            u64::try_from(snapshot_id).map_err(|_| "invalid snapshot identity".to_owned())?;
        let width = u32::try_from(width).map_err(|_| "invalid display width".to_owned())?;
        let height = u32::try_from(height).map_err(|_| "invalid display height".to_owned())?;
        get_session(handle)?.request(|response| SessionCommand::UpdateDisplay {
            snapshot_id,
            width,
            height,
            response,
        })
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeConfigureProject(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    snapshot_id: jlong,
    design_width: jint,
    design_height: jint,
    scale_mode: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let snapshot_id =
            u64::try_from(snapshot_id).map_err(|_| "invalid snapshot identity".to_owned())?;
        let design_width =
            u32::try_from(design_width).map_err(|_| "invalid design width".to_owned())?;
        let design_height =
            u32::try_from(design_height).map_err(|_| "invalid design height".to_owned())?;
        let scale_mode = match scale_mode {
            0 => ScaleMode::Letterbox,
            1 => ScaleMode::Crop,
            2 => ScaleMode::Stretch,
            _ => return Err("invalid project scale mode".to_owned()),
        };
        get_session(handle)?.configure_project(snapshot_id, design_width, design_height, scale_mode)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeStop(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    boot_nanos: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let boot_nanos = u64::try_from(boot_nanos).map_err(|_| "invalid boot clock".to_owned())?;
        get_session(handle)?.request_stop(boot_nanos)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativePause(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    boot_nanos: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let boot_nanos = u64::try_from(boot_nanos).map_err(|_| "invalid boot clock".to_owned())?;
        get_session(handle)?.request_pause(boot_nanos)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeResume(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    boot_nanos: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let boot_nanos = u64::try_from(boot_nanos).map_err(|_| "invalid boot clock".to_owned())?;
        get_session(handle)?.request_resume(boot_nanos)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeAttachRoot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    socket_path: JString<'_>,
    key: JByteArray<'_>,
    timeout_millis: jint,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let socket_path = env
            .get_string(&socket_path)
            .map_err(|error| error.to_string())?
            .into();
        let key = env
            .convert_byte_array(key)
            .map_err(|error| error.to_string())?;
        let key: [u8; 32] = key
            .try_into()
            .map_err(|_| "RootDaemon key must contain exactly 32 bytes".to_owned())?;
        let timeout_millis =
            u64::try_from(timeout_millis).map_err(|_| "invalid RootDaemon timeout".to_owned())?;
        if !(100..=10_000).contains(&timeout_millis) {
            return Err("RootDaemon timeout outside 100ms..10s".to_owned());
        }
        get_session(handle)?.attach_root(socket_path, key, Duration::from_millis(timeout_millis))
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeDetachRoot(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        get_session(handle)?.detach_root()
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeState(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        Ok::<_, String>(get_session(handle)?.state.load(Ordering::Acquire))
    }));
    match result {
        Ok(Ok(state)) => state,
        Ok(Err(_)) | Err(_) => STATE_FAILED,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeLastDiagnostic(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        Ok::<_, String>(get_session(handle)?.last_diagnostic().unwrap_or_default())
    }));
    let diagnostic = match result {
        Ok(Ok(diagnostic)) => diagnostic,
        Ok(Err(error)) => runtime_diagnostic(&error),
        Err(_) => "ENGINE_DIAGNOSTIC_FAILED: native panic".to_owned(),
    };
    env.new_string(diagnostic)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeNextWakeNanos(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        Ok::<_, String>(get_session(handle)?.next_wake.load(Ordering::Acquire))
    }));
    match result {
        Ok(Ok(deadline)) => deadline,
        Ok(Err(_)) | Err(_) => NEXT_STOPPED,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        destroy_session(handle)
    }));
    finish_jni(&result)
}

fn finish_jni(result: &Result<Result<(), String>, Box<dyn std::any::Any + Send>>) -> jint {
    match result {
        Ok(Ok(())) => 0,
        Ok(Err(_)) | Err(_) => -1,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::Ordering;
    use std::time::Duration;

    use super::{
        create_session, destroy_session, get_session, SessionCommand, STATE_FAILED, STATE_IDLE,
        STATE_STOPPED, STATE_STOPPING,
    };

    #[test]
    fn generation_checked_handle_rejects_use_after_destroy() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
        session
            .request(|response| SessionCommand::Start {
                source: b"return function() Task.sleep(1) end".to_vec(),
                capabilities: vec!["core.task".to_owned()],
                response,
            })
            .expect("start");
        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 0,
                response,
            })
            .expect("pump");
        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 1_000_000,
                response,
            })
            .expect("finish");
        assert_eq!(session.state.load(Ordering::Acquire), STATE_STOPPED);
        destroy_session(handle).expect("destroy");
        assert_eq!(
            get_session(handle).expect_err("stale handle"),
            "SESSION_CLOSED"
        );
    }

    #[test]
    fn terminal_session_can_reset_and_run_again() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
        for _ in 0..2 {
            session
                .request(|response| SessionCommand::Start {
                    source: b"return function() end".to_vec(),
                    capabilities: Vec::new(),
                    response,
                })
                .expect("start");
            session
                .request(|response| SessionCommand::Pump {
                    boot_nanos: 0,
                    response,
                })
                .expect("pump");
            assert_eq!(session.state.load(Ordering::Acquire), STATE_STOPPED);
            session.reset(720, 1280).expect("reset");
        }
        destroy_session(handle).expect("destroy");
    }

    #[test]
    fn idle_session_can_reset_before_loading_a_new_project() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");

        session.reset(1080, 1920).expect("reset idle session");

        assert_eq!(session.state.load(Ordering::Acquire), STATE_IDLE);
        destroy_session(handle).expect("destroy");
    }

    #[test]
    fn failed_lua_task_publishes_and_reset_clears_runtime_diagnostic() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
        session
            .request(|response| SessionCommand::Start {
                source: b"return function() error('expected runtime failure', 0) end".to_vec(),
                capabilities: Vec::new(),
                response,
            })
            .expect("start");
        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 0,
                response,
            })
            .expect("pump");

        assert_eq!(session.state.load(Ordering::Acquire), STATE_FAILED);
        let diagnostic = session.last_diagnostic().expect("diagnostic");
        assert!(diagnostic.starts_with("LUA_RUNTIME_ERROR:"));
        assert!(diagnostic.contains("expected runtime failure"));

        session.reset(720, 1280).expect("reset");
        assert_eq!(session.state.load(Ordering::Acquire), STATE_IDLE);
        assert_eq!(session.last_diagnostic(), None);
        destroy_session(handle).expect("destroy");
    }

    #[test]
    fn stop_is_accepted_without_waiting_for_the_business_queue() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
        session
            .request(|response| SessionCommand::Start {
                source: b"return function() Task.sleep(60000) end".to_vec(),
                capabilities: vec!["core.task".to_owned()],
                response,
            })
            .expect("start");
        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 0,
                response,
            })
            .expect("pump");

        session.request_stop(1).expect("stop accepted");
        assert!(matches!(
            session.state.load(Ordering::Acquire),
            STATE_STOPPING | STATE_STOPPED
        ));
        for _ in 0..100 {
            if session.state.load(Ordering::Acquire) == STATE_STOPPED {
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        assert_eq!(session.state.load(Ordering::Acquire), STATE_STOPPED);
        destroy_session(handle).expect("destroy");
    }
}
