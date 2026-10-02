//! Narrow JNI boundary backed by generation-checked opaque handles.

mod input_runtime;
mod template_preview;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use automation_core::{
    AutomationBackend, BackendError, FrameFormat, FrameMetadata, FramePool, InputArbiterConfig,
    InputCommand, Rotation,
};
#[cfg(target_os = "android")]
use automation_core::{CaptureSeriesHandle, SeriesPublish};
use coordinate::{CoordinateSnapshot, DesignPoint, ScaleMode, Size};
use engine_core::{EngineSession, EngineSessionConfig, EngineState};
use input_runtime::{InputControl, InputRuntime, InputRuntimeError};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring};
use jni::{JNIEnv, JavaVM};
use lua_runtime::LuaScalar;
use runtime_executor::{ExternalHostEvent, ExternalHostQueue, HostRequest};
mod native_vision;
use runtime_scheduler::{
    HostCompletion, HostResult, MonoTime, RequestId, SchedulerEvent, SchedulerHandle,
    SchedulerPoll, TaskToken,
};
#[cfg(target_os = "android")]
use runtime_scheduler::{ResourceId, ResourceKind};

const STATE_IDLE: i32 = 1;
/// Explicit, low-frequency file preview. Validate arrays before allocating native copies.
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeTestTemplate(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    width: jint,
    height: jint,
    screen: JByteArray<'_>,
    template_width: jint,
    template_height: jint,
    template: JByteArray<'_>,
    tolerance: jint,
    similarity: jint,
) -> jni::sys::jintArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let read =
            |w: i32, h: i32, bytes: &JByteArray<'_>| -> Result<(u32, u32, Vec<u8>), String> {
                if !(1..=4096).contains(&w)
                    || !(1..=4096).contains(&h)
                    || i64::from(w) * i64::from(h) > 4_194_304
                {
                    return Err("invalid preview dimensions".into());
                }
                let expected = w * h * 4;
                if env.get_array_length(bytes).map_err(|e| e.to_string())? != expected {
                    return Err("invalid preview buffer".into());
                }
                Ok((
                    u32::try_from(w).map_err(|e| e.to_string())?,
                    u32::try_from(h).map_err(|e| e.to_string())?,
                    env.convert_byte_array(bytes).map_err(|e| e.to_string())?,
                ))
            };
        let (w, h, frame) = read(width, height, &screen)?;
        let (tw, th, template) = read(template_width, template_height, &template)?;
        let reply = template_preview::match_template(
            template_preview::image(w, h, &frame),
            template_preview::image(tw, th, &template),
            tolerance,
            similarity,
        );
        let array = env.new_int_array(4).map_err(|e| e.to_string())?;
        env.set_int_array_region(&array, 0, &reply)
            .map_err(|e| e.to_string())?;
        Ok::<_, String>(array.into_raw())
    }));
    result
        .ok()
        .and_then(Result::ok)
        .unwrap_or(std::ptr::null_mut())
}

const STATE_RUNNING: i32 = 2;
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeConfigureUiValues(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    json: JString<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let json: String = env.get_string(&json).map_err(|e| e.to_string())?.into();
        if json.len() > 262144 {
            return Err("UI configuration exceeds limit".into());
        }
        let values: std::collections::BTreeMap<String, String> =
            serde_json::from_str(&json).map_err(|e| e.to_string())?;
        get_session(u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?)?
            .seed_ui_values(values)
    }));
    finish_jni(&result)
}
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativePushUiEvent(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    id: JString<'_>,
    event: JString<'_>,
    value: JString<'_>,
    dispatch: jboolean,
) -> jint {
    let result =
        catch_unwind(AssertUnwindSafe(|| {
            let id: String = env.get_string(&id).map_err(|e| e.to_string())?.into();
            let event: String = env.get_string(&event).map_err(|e| e.to_string())?.into();
            let value: String = env.get_string(&value).map_err(|e| e.to_string())?.into();
            if id.len() > 64 || event.len() > 16 || value.len() > 8192 {
                return Err("UI event exceeds limit".into());
            }
            get_session(u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?)?
                .push_ui_event(id, event, value, dispatch != 0)
        }));
    finish_jni(&result)
}
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeInputFeatures(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        u64::try_from(handle)
            .ok()
            .and_then(|h| get_session(h).ok())
            .map_or(0, |s| i32::try_from(s.input_features()).unwrap_or(0))
    }))
    .unwrap_or(0)
}
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeStep(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    boot_nanos: jlong,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        let boot_nanos = u64::try_from(boot_nanos).map_err(|_| "invalid boot clock".to_owned())?;
        get_session(handle)?.request_step(boot_nanos)
    }));
    finish_jni(&result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeDebugSnapshot(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).ok()?;
        let snapshot = get_session(handle).ok()?.debug_snapshot().ok()??;
        env.new_string(snapshot).ok().map(JString::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
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
const MAX_PREVIEW_CAPTURE_BYTES: usize = 20 * 1024 * 1024;

/// A one-shot raw frame requested by Studio, kept separate from script-owned frame handles.
struct PreviewCapture {
    width: u32,
    height: u32,
    row_stride: u32,
    format: u8,
    pixels: Vec<u8>,
}

struct PreviewCaptureError {
    message: String,
    connection_lost: bool,
}

struct RootDispatch {
    result: HostResult,
    connection_lost: bool,
}

#[cfg(target_os = "android")]
const OP_SYSTEM_GET_SCREEN_SIZE: u32 = 2_000;
#[cfg(target_os = "android")]
const OP_SYSTEM_ELAPSED_REALTIME_MILLIS: u32 = 2_001;
const OP_INPUT_TAP: u32 = 4_000;
const OP_INPUT_SWIPE: u32 = 4_001;
const OP_INPUT_KEY_EVENT: u32 = 4_002;
const OP_INPUT_POINTER_DOWN: u32 = 4_003;
const OP_INPUT_POINTER_MOVE: u32 = 4_004;
const OP_INPUT_POINTER_UP: u32 = 4_005;
const OP_INPUT_TAP_SCREEN: u32 = 4_006;
const OP_INPUT_POINTER_DOWN_SCREEN: u32 = 4_007;
#[cfg(target_os = "android")]
const OP_SCREEN_CAPTURE: u32 = 5_000;
#[cfg(target_os = "android")]
const OP_SCREEN_CAPTURE_SERIES_FRAME: u32 = 5_004;

enum SessionCommand {
    UiSeed {
        values: std::collections::BTreeMap<String, String>,
        response: SyncSender<Result<(), String>>,
    },
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
    DrainScriptLogs {
        response: SyncSender<Vec<String>>,
    },
    DrainScriptPrompts {
        response: SyncSender<Vec<String>>,
    },
    DebugSnapshot {
        response: SyncSender<Option<String>>,
    },
    UiEvent {
        id: String,
        event: String,
        value: String,
        dispatch: bool,
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
    ResetInput {
        response: SyncSender<()>,
    },
    CapturePreview {
        response: SyncSender<Result<PreviewCapture, String>>,
    },
    InputFeatures {
        response: SyncSender<u32>,
    },
    Shutdown,
}

#[cfg(target_os = "android")]
mod platform_root {
    use std::path::Path;
    use std::time::Duration;

    use super::{
        AutomationBackend, BackendError, CaptureSeriesHandle, CoordinateSnapshot, FrameFormat,
        FrameMetadata, FramePool, HostRequest, HostResult, InputCommand, LuaScalar, PreviewCapture,
        PreviewCaptureError, RequestId, ResourceId, ResourceKind, RootDispatch, Rotation,
        SeriesPublish, TaskToken, OP_SCREEN_CAPTURE, OP_SCREEN_CAPTURE_SERIES_FRAME,
        OP_SYSTEM_ELAPSED_REALTIME_MILLIS, OP_SYSTEM_GET_SCREEN_SIZE,
    };

    pub type Client = root_client::AndroidRootClient;
    pub fn input_features(client: &Client) -> u32 {
        client.input_features()
    }

    pub struct InputBackend<'a> {
        client: &'a mut Client,
        connection_lost: bool,
    }

    impl<'a> InputBackend<'a> {
        pub fn new(client: &'a mut Client) -> Self {
            Self {
                client,
                connection_lost: false,
            }
        }

        pub const fn connection_lost(&self) -> bool {
            self.connection_lost
        }

        fn map_client_error(&mut self, error: root_client::ClientError) -> BackendError {
            self.connection_lost |= error.is_connection_lost();
            BackendError {
                code: "ROOT_INPUT_FAILED",
                message: format!("Root input backend rejected the request: {error:?}"),
                retryable: false,
                connection_lost: error.is_connection_lost(),
            }
        }
    }

    impl AutomationBackend for InputBackend<'_> {
        fn supports_pointer_input(&self) -> bool {
            self.client.input_features() & 2 != 0
        }
        fn dispatch_input(
            &mut self,
            _request: RequestId,
            _task: TaskToken,
            commands: &[InputCommand],
        ) -> Result<(), BackendError> {
            for command in commands {
                let result = match command {
                    InputCommand::Tap { x, y } => self.client.tap(*x, *y),
                    InputCommand::Swipe {
                        from_x,
                        from_y,
                        to_x,
                        to_y,
                        duration_ms,
                    } => self
                        .client
                        .swipe((*from_x, *from_y), (*to_x, *to_y), *duration_ms),
                    InputCommand::KeyEvent { key_code } => self.client.key_event(*key_code),
                    InputCommand::PointerDown { pointer_id, x, y } => {
                        self.client.pointer_down(*pointer_id, *x, *y)
                    }
                    InputCommand::PointerMove { pointer_id, x, y } => {
                        self.client.pointer_move(*pointer_id, *x, *y)
                    }
                    InputCommand::PointerUp { pointer_id } => self.client.pointer_up(*pointer_id),
                    InputCommand::Delay { milliseconds } => {
                        std::thread::sleep(Duration::from_millis(u64::from(*milliseconds)));
                        Ok(())
                    }
                };
                result.map_err(|error| self.map_client_error(error))?;
            }
            Ok(())
        }

        fn cancel_input(&mut self, request: RequestId) {
            if let Some(stop) = self.client.priority_stop() {
                stop.request_stop();
                return;
            }
            if let Err(error) = self.client.cancel(request.get()) {
                self.connection_lost |= error.is_connection_lost();
            }
        }

        fn release_pointers(&mut self, pointer_ids: &[u8]) -> Result<(), BackendError> {
            if let Some(stop) = self.client.priority_stop() {
                stop.wait_clean(Duration::from_secs(8))
                    .map_err(|code| BackendError {
                        code,
                        message: "Root pointer cleanup was not confirmed".into(),
                        retryable: false,
                        connection_lost: true,
                    })?;
            }
            for pointer_id in pointer_ids {
                self.client
                    .pointer_up(*pointer_id)
                    .map_err(|error| self.map_client_error(error))?;
            }
            Ok(())
        }
    }

    pub fn connect(path: &str, key: [u8; 32], timeout: Duration) -> Result<Client, String> {
        root_client::connect_android(Path::new(path), key, timeout)
            .map_err(|error| format!("{error:?}"))
    }
    pub fn clean_and_resume(client: &Client) -> Result<(), String> {
        if let Some(stop) = client.priority_stop() {
            stop.wait_clean(Duration::from_secs(8))
                .and_then(|()| stop.resume())
                .map_err(str::to_owned)?;
        }
        Ok(())
    }
    pub fn wait_clean(client: &Client) -> bool {
        client
            .priority_stop()
            .is_none_or(|stop| stop.wait_clean(Duration::from_secs(8)).is_ok())
    }

    pub fn capture_preview(client: &mut Client) -> Result<PreviewCapture, PreviewCaptureError> {
        client
            .capture()
            .map(|capture| PreviewCapture {
                width: capture.width,
                height: capture.height,
                row_stride: capture.row_stride,
                format: match capture.format {
                    root_client::RawCaptureFormat::Rgba8888 => 1,
                    root_client::RawCaptureFormat::Bgra8888 => 2,
                },
                pixels: capture.pixels,
            })
            .map_err(|error| PreviewCaptureError {
                message: format!("RootDaemon failed to capture preview: {error:?}"),
                connection_lost: error.is_connection_lost(),
            })
    }

    pub fn shutdown(client: &mut Client) -> Result<(), String> {
        client.shutdown().map_err(|error| format!("{error:?}"))
    }

    pub fn dispatch(
        client: &mut Client,
        request: &HostRequest,
        display: (u32, u32),
        _coordinates: CoordinateSnapshot,
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
            OP_SYSTEM_ELAPSED_REALTIME_MILLIS if request.args.is_empty() => {
                success((timestamp_nanos / 1_000_000).to_le_bytes().to_vec())
            }
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
}

#[cfg(not(target_os = "android"))]
mod platform_root {
    use std::time::Duration;

    use super::{
        AutomationBackend, BackendError, CoordinateSnapshot, FramePool, HostRequest, HostResult,
        InputCommand, PreviewCapture, PreviewCaptureError, RequestId, RootDispatch, TaskToken,
    };

    pub struct Client;
    pub fn input_features(_client: &Client) -> u32 {
        0
    }

    pub struct InputBackend<'a> {
        _client: &'a mut Client,
    }

    impl<'a> InputBackend<'a> {
        pub fn new(client: &'a mut Client) -> Self {
            Self { _client: client }
        }

        pub const fn connection_lost(&self) -> bool {
            true
        }
    }

    impl AutomationBackend for InputBackend<'_> {
        fn dispatch_input(
            &mut self,
            _request: RequestId,
            _task: TaskToken,
            _commands: &[InputCommand],
        ) -> Result<(), BackendError> {
            Err(unavailable())
        }

        fn cancel_input(&mut self, _request: RequestId) {}

        fn release_pointers(&mut self, _pointer_ids: &[u8]) -> Result<(), BackendError> {
            Err(unavailable())
        }
    }

    fn unavailable() -> BackendError {
        BackendError {
            code: "ROOT_UNAVAILABLE",
            message: "RootDaemon is only available on Android".to_owned(),
            retryable: false,
            connection_lost: true,
        }
    }

    pub fn connect(_path: &str, _key: [u8; 32], _timeout: Duration) -> Result<Client, String> {
        Err("RootDaemon is only available on Android".to_owned())
    }
    pub fn clean_and_resume(_client: &Client) -> Result<(), String> {
        Ok(())
    }
    pub fn wait_clean(_client: &Client) -> bool {
        true
    }

    pub fn capture_preview(_client: &mut Client) -> Result<PreviewCapture, PreviewCaptureError> {
        Err(PreviewCaptureError {
            message: "RootDaemon is only available on Android".to_owned(),
            connection_lost: true,
        })
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

fn prepare_input_commands(
    request: &HostRequest,
    coordinates: CoordinateSnapshot,
) -> Option<Result<Vec<InputCommand>, RootDispatch>> {
    let result = match request.opcode {
        OP_INPUT_TAP_SCREEN | OP_INPUT_POINTER_DOWN_SCREEN => {
            let [LuaScalar::Integer(x), LuaScalar::Integer(y)] = request.args.as_slice() else {
                return Some(Err(input_failure("invalid raw screen coordinates")));
            };
            let point = i32::try_from(*x)
                .ok()
                .zip(i32::try_from(*y).ok())
                .filter(|(x, y)| {
                    *x >= 0
                        && *y >= 0
                        && f64::from(*x) < f64::from(coordinates.display_size.width)
                        && f64::from(*y) < f64::from(coordinates.display_size.height)
                })
                .ok_or(());
            point.map(|(x, y)| {
                vec![if request.opcode == OP_INPUT_TAP_SCREEN {
                    InputCommand::Tap { x, y }
                } else {
                    InputCommand::PointerDown {
                        pointer_id: 0,
                        x,
                        y,
                    }
                }]
            })
        }
        OP_INPUT_TAP => {
            let [LuaScalar::Integer(x), LuaScalar::Integer(y)] = request.args.as_slice() else {
                return Some(Err(input_failure("invalid tap arguments")));
            };
            map_design_point(coordinates, *x, *y).map(|(x, y)| vec![InputCommand::Tap { x, y }])
        }
        OP_INPUT_SWIPE => {
            let [LuaScalar::Integer(x1), LuaScalar::Integer(y1), LuaScalar::Integer(x2), LuaScalar::Integer(y2), LuaScalar::Integer(duration)] =
                request.args.as_slice()
            else {
                return Some(Err(input_failure("invalid swipe arguments")));
            };
            let Ok(duration_ms) = u32::try_from(*duration) else {
                return Some(Err(input_failure("invalid swipe duration")));
            };
            if !(1..=60_000).contains(&duration_ms) {
                return Some(Err(input_failure("swipe duration is outside 1ms..60s")));
            }
            map_design_point(coordinates, *x1, *y1).and_then(|(from_x, from_y)| {
                map_design_point(coordinates, *x2, *y2).map(|(to_x, to_y)| {
                    vec![InputCommand::Swipe {
                        from_x,
                        from_y,
                        to_x,
                        to_y,
                        duration_ms,
                    }]
                })
            })
        }
        OP_INPUT_KEY_EVENT => {
            let [LuaScalar::Integer(key_code)] = request.args.as_slice() else {
                return Some(Err(input_failure("invalid key event arguments")));
            };
            u32::try_from(*key_code)
                .map(|key_code| vec![InputCommand::KeyEvent { key_code }])
                .map_err(|_| ())
        }
        OP_INPUT_POINTER_DOWN | OP_INPUT_POINTER_MOVE => {
            let [LuaScalar::Integer(x), LuaScalar::Integer(y)] = request.args.as_slice() else {
                return Some(Err(input_failure("invalid pointer coordinates")));
            };
            map_design_point(coordinates, *x, *y).map(|(x, y)| {
                let command = if request.opcode == OP_INPUT_POINTER_DOWN {
                    InputCommand::PointerDown {
                        pointer_id: 0,
                        x,
                        y,
                    }
                } else {
                    InputCommand::PointerMove {
                        pointer_id: 0,
                        x,
                        y,
                    }
                };
                vec![command]
            })
        }
        OP_INPUT_POINTER_UP => {
            if !request.args.is_empty() {
                return Some(Err(input_failure("invalid pointer up arguments")));
            }
            Ok(vec![InputCommand::PointerUp { pointer_id: 0 }])
        }
        _ => return None,
    };
    Some(
        result.map_err(|()| input_failure("input coordinate is outside the visible design canvas")),
    )
}

fn map_design_point(coordinates: CoordinateSnapshot, x: i64, y: i64) -> Result<(i32, i32), ()> {
    let x = i32::try_from(x).map_err(|_| ())?;
    let y = i32::try_from(y).map_err(|_| ())?;
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

fn input_failure(message: &str) -> RootDispatch {
    RootDispatch {
        result: HostResult::Failure {
            code: "ROOT_INPUT_FAILED".to_owned(),
            message: message.to_owned(),
        },
        connection_lost: false,
    }
}

fn input_runtime_result(result: Result<(), InputRuntimeError>) -> RootDispatch {
    match result {
        Ok(()) => RootDispatch {
            result: HostResult::Success(Vec::new()),
            connection_lost: false,
        },
        Err(InputRuntimeError::Expired) => RootDispatch {
            result: HostResult::Failure {
                code: "HOST_TIMEOUT".to_owned(),
                message: "input request expired before dispatch".to_owned(),
            },
            connection_lost: false,
        },
        Err(InputRuntimeError::Cancelled) => RootDispatch {
            result: HostResult::Failure {
                code: "HOST_CANCELLED".to_owned(),
                message: "input request was cancelled".to_owned(),
            },
            connection_lost: false,
        },
        Err(InputRuntimeError::Interrupted) => RootDispatch {
            result: HostResult::Failure {
                code: "HOST_CANCELLED".to_owned(),
                message: "input request was interrupted by stop".to_owned(),
            },
            connection_lost: false,
        },
        Err(InputRuntimeError::Arbitration(error)) => RootDispatch {
            result: HostResult::Failure {
                code: "INPUT_ARBITRATION_FAILED".to_owned(),
                message: format!("input arbiter rejected the request: {error:?}"),
            },
            connection_lost: false,
        },
        Err(InputRuntimeError::Backend(error)) => RootDispatch {
            result: HostResult::Failure {
                code: error.code.to_owned(),
                message: error.message,
            },
            connection_lost: error.connection_lost,
        },
    }
}

#[derive(Debug)]
struct NativeSession {
    vision: Arc<native_vision::VisionHub>,
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
    input_stop_requested: Arc<AtomicBool>,
    wake_callback: Arc<Mutex<Option<WakeCallback>>>,
}

#[derive(Debug, Clone, Copy)]
enum PauseControl {
    Pause(u64),
    Resume(u64),
    Step(u64),
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
    vision: Arc<native_vision::VisionHub>,
    host_queue: ExternalHostQueue,
    display_width: Arc<AtomicU32>,
    display_height: Arc<AtomicU32>,
    wake_callback: Arc<Mutex<Option<WakeCallback>>>,
    frames: Arc<Mutex<FramePool>>,
    boot_nanos: Arc<AtomicU64>,
    snapshot_id: Arc<AtomicU64>,
    coordinates: Arc<Mutex<CoordinateSnapshot>>,
    attached: Arc<AtomicBool>,
    input_config: InputArbiterConfig,
    input_stop_requested: Arc<AtomicBool>,
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
        let vision = Arc::new(native_vision::VisionHub::default());
        host_queue.set_cancel_listener(vision.clone());
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
        let (root_control, root_receiver) = mpsc::sync_channel(4);
        let root_attached = Arc::new(AtomicBool::new(false));
        let input_stop_requested = Arc::new(AtomicBool::new(false));
        let root_worker = spawn_root_worker(
            root_receiver,
            RootWorkerContext {
                vision: vision.clone(),
                host_queue: host_queue.clone(),
                display_width: Arc::clone(&display_width),
                display_height: Arc::clone(&display_height),
                wake_callback: Arc::clone(&wake_callback),
                frames: Arc::clone(&frames),
                boot_nanos: Arc::clone(&boot_nanos),
                snapshot_id: Arc::clone(&snapshot_id),
                coordinates: Arc::clone(&coordinates),
                attached: Arc::clone(&root_attached),
                input_config: engine_config.input,
                input_stop_requested: Arc::clone(&input_stop_requested),
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
            vision,
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
            input_stop_requested,
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
        self.input_stop_requested.store(true, Ordering::Release);
        self.vision.stop();
        self.stop_handle
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .request_stop();
        self.state.store(STATE_STOPPING, Ordering::Release);
        self.next_wake.store(0, Ordering::Release);
        // A full business queue is not an error: the worker checks this atomic flag before
        // taking every subsequent business command.
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        self.host_queue.interrupt();
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
        if self.vision.cleanup_status().1 {
            return Some("ROOT_STOP_CLEANUP_FAILED: 未确认触点清理成功，已禁止继续输入；请重新连接 RootDaemon".into());
        }
        self.last_diagnostic
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }

    fn request_step(&self, boot_nanos: u64) -> Result<(), String> {
        if self.state.load(Ordering::Acquire) != STATE_PAUSED {
            return Err("SESSION_NOT_PAUSED".into());
        }
        let mut request = self
            .pause_request
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if request.is_some() {
            return Err("SESSION_CONTROL_PENDING".into());
        }
        *request = Some(PauseControl::Step(boot_nanos));
        drop(request);
        let _ = self.commands.try_send(SessionCommand::ControlWake);
        Ok(())
    }

    fn debug_snapshot(&self) -> Result<Option<String>, String> {
        let (response, receiver) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::DebugSnapshot { response })
            .map_err(|e| e.to_string())?;
        receiver
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|e| e.to_string())
    }
    fn push_ui_event(
        &self,
        id: String,
        event: String,
        value: String,
        dispatch: bool,
    ) -> Result<(), String> {
        let (response, receiver) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::UiEvent {
                id,
                event,
                value,
                dispatch,
                response,
            })
            .map_err(|e| e.to_string())?;
        receiver
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|e| e.to_string())?
    }
    fn seed_ui_values(
        &self,
        values: std::collections::BTreeMap<String, String>,
    ) -> Result<(), String> {
        let (response, receiver) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::UiSeed { values, response })
            .map_err(|e| e.to_string())?;
        receiver
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|e| e.to_string())?
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
        let (input_response, input_wait) = mpsc::sync_channel(1);
        self.root_control
            .try_send(RootControl::ResetInput {
                response: input_response,
            })
            .map_err(|error| format!("Root input reset queue rejected request: {error}"))?;
        self.host_queue.interrupt();
        input_wait
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("Root input reset timed out: {error}"))?;
        if self.vision.cleanup_status().1 {
            return Err(
                "ROOT_INPUT_NOT_CLEAN: touch cleanup was not confirmed; reconnect RootDaemon"
                    .into(),
            );
        }
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

    fn drain_script_logs(&self) -> Result<Vec<String>, String> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return Err("SESSION_CLOSED".to_owned());
        }
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::DrainScriptLogs {
                response: response_tx,
            })
            .map_err(|error| format!("engine command queue rejected log drain: {error}"))?;
        response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("engine log drain timed out: {error}"))
    }

    fn drain_script_prompts(&self) -> Result<Vec<String>, String> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return Err("SESSION_CLOSED".to_owned());
        }
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.commands
            .try_send(SessionCommand::DrainScriptPrompts {
                response: response_tx,
            })
            .map_err(|error| format!("engine command queue rejected prompt drain: {error}"))?;
        response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("engine prompt drain timed out: {error}"))
    }

    fn input_features(&self) -> u32 {
        if !self.root_attached.load(Ordering::Acquire) {
            return 0;
        }
        let (response, receiver) = mpsc::sync_channel(1);
        if self
            .root_control
            .try_send(RootControl::InputFeatures { response })
            .is_err()
        {
            return 0;
        }
        receiver.recv_timeout(COMMAND_TIMEOUT).unwrap_or(0)
    }

    fn capture_preview(&self) -> Result<Vec<u8>, String> {
        if self.shutdown_requested.load(Ordering::Acquire) {
            return Err("SESSION_CLOSED".to_owned());
        }
        if !self.root_attached.load(Ordering::Acquire) {
            return Err("ROOT_BACKEND_NOT_READY".to_owned());
        }
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        self.root_control
            .try_send(RootControl::CapturePreview {
                response: response_tx,
            })
            .map_err(|error| format!("Root preview queue rejected request: {error}"))?;
        // The Root worker may be parked waiting for a script-host event.
        self.host_queue.interrupt();
        let capture = response_rx
            .recv_timeout(COMMAND_TIMEOUT)
            .map_err(|error| format!("Root preview timed out: {error}"))??;
        if capture.pixels.len() > MAX_PREVIEW_CAPTURE_BYTES {
            return Err("PREVIEW_CAPTURE_TOO_LARGE".to_owned());
        }
        let expected = usize::try_from(capture.row_stride)
            .ok()
            .and_then(|row_stride| {
                usize::try_from(capture.height)
                    .ok()
                    .and_then(|height| row_stride.checked_mul(height))
            })
            .ok_or_else(|| "PREVIEW_CAPTURE_INVALID".to_owned())?;
        if capture.width == 0
            || capture.height == 0
            || capture.row_stride < capture.width.saturating_mul(4)
            || capture.pixels.len() != expected
            || !matches!(capture.format, 1 | 2)
        {
            return Err("PREVIEW_CAPTURE_INVALID".to_owned());
        }
        let mut encoded = Vec::with_capacity(
            16usize
                .checked_add(capture.pixels.len())
                .ok_or_else(|| "PREVIEW_CAPTURE_TOO_LARGE".to_owned())?,
        );
        encoded.extend_from_slice(&capture.width.to_le_bytes());
        encoded.extend_from_slice(&capture.height.to_le_bytes());
        encoded.extend_from_slice(&capture.row_stride.to_le_bytes());
        encoded.push(capture.format);
        encoded.extend_from_slice(&[0, 0, 0]);
        encoded.extend_from_slice(&capture.pixels);
        Ok(encoded)
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
        self.input_stop_requested.store(true, Ordering::Release);
        self.vision.stop();
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
            Some(PauseControl::Step(boot_nanos)) => {
                let result = engine.step(boot_nanos);
                publish_control_result(engine, result, "ENGINE_STEP_FAILED", signals);
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
            let result = match engine.pump(boot_nanos) {
                Ok(report) => {
                    for event in report.events {
                        if let SchedulerEvent::TaskFinished { task, .. } = event {
                            signals.host_queue.task_finished(task);
                        }
                    }
                    Ok(())
                }
                // Android wake callbacks are asynchronous. A queued wake may arrive after the
                // preceding pump has already completed or failed the root task. That late pump is
                // an idempotent no-op and must not replace the real terminal state/diagnostic.
                Err(engine_core::EngineSessionError::NotRunning)
                    if matches!(engine.state(), EngineState::Stopped | EngineState::Failed) =>
                {
                    Ok(())
                }
                Err(error) => Err(format!("{error:?}")),
            };
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
        SessionCommand::DrainScriptLogs { response } => {
            let _ = response.send(engine.drain_script_logs());
        }
        SessionCommand::DrainScriptPrompts { response } => {
            let _ = response.send(engine.drain_script_prompts());
        }
        SessionCommand::UiEvent {
            id,
            event,
            value,
            dispatch,
            response,
        } => {
            let result = (if dispatch {
                engine.push_ui_event(&id, &event, &value)
            } else {
                engine.update_ui_value(&id, &value)
            })
            .map_err(|e| format!("{e:?}"));
            publish_engine_state(engine, signals);
            let _ = response.send(result);
        }
        SessionCommand::UiSeed { values, response } => {
            let _ = response.send(engine.seed_ui_values(values).map_err(|e| format!("{e:?}")));
        }
        SessionCommand::DebugSnapshot { response } => {
            let snapshot = engine.debug_snapshot().map(|snapshot| {
                let variables = snapshot.variables.into_iter().map(|(scope, name, scalar, truncated)| {
                    let (kind, value) = match scalar {
                        LuaScalar::Nil => ("nil", String::new()),
                        LuaScalar::Boolean(value) => ("boolean", value.to_string()),
                        LuaScalar::Integer(value) => ("integer", value.to_string()),
                        LuaScalar::Number(value) => ("number", value.to_string()),
                        LuaScalar::Bytes(value) => ("string", String::from_utf8_lossy(&value).into_owned()),
                    };
                    serde_json::json!({"scope":scope,"name":name,"type":kind,"value":value,"truncated":truncated})
                }).collect::<Vec<_>>();
                serde_json::json!({"flowId":snapshot.flow_id,"nodeId":snapshot.node_id,"variables":variables}).to_string()
            });
            let _ = response.send(snapshot);
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
    let mut input = InputRuntime::new(context.input_config);
    loop {
        if context.input_stop_requested.swap(false, Ordering::AcqRel) {
            if let Some(connected) = client.as_mut() {
                let priority_clean = platform_root::wait_clean(connected);
                let mut backend = platform_root::InputBackend::new(connected);
                let result = input.stop(&mut backend);
                context
                    .vision
                    .cleanup_finished(priority_clean && result.is_ok());
                if backend.connection_lost()
                    || matches!(
                        result,
                        Err(InputRuntimeError::Backend(BackendError {
                            connection_lost: true,
                            ..
                        }))
                    )
                {
                    client = None;
                    notify_root_disconnected(context);
                }
            } else {
                input.abandon();
                context.vision.cleanup_finished(true);
            }
            notify_root_wake(context);
        }

        if client.is_none() {
            // The previous RootDaemon connection has gone away; its daemon-side disconnect
            // cleanup releases any active touch. Discard the matching local leases before a
            // fresh client is attached so a stale move/up cannot leak into the next session.
            input.abandon();
            client = wait_for_root_client(receiver, context, &mut input);
            if client.is_none() {
                return;
            }
            input.reset();
            continue;
        }

        match receiver.try_recv() {
            Ok(RootControl::Attach { response, .. }) => {
                let _ = response.send(Err("RootDaemon is already attached".to_owned()));
                continue;
            }
            Ok(RootControl::Disconnect { response }) => {
                if let Some(mut connected) = client.take() {
                    let mut backend = platform_root::InputBackend::new(&mut connected);
                    let clean = input.stop(&mut backend).is_ok();
                    let _ = platform_root::shutdown(&mut connected);
                    context.vision.detach_root(clean);
                    input.abandon();
                    input.reset();
                }
                context.attached.store(false, Ordering::Release);
                let _ = response.send(());
                continue;
            }
            Ok(RootControl::ResetInput { response }) => {
                if let Some(connected) = client.as_mut() {
                    if !platform_root::wait_clean(connected) {
                        context.vision.cleanup_finished(false);
                        let _ = response.send(());
                        continue;
                    }
                    let mut backend = platform_root::InputBackend::new(connected);
                    if input.stop(&mut backend).is_err() {
                        context.vision.cleanup_finished(false);
                        let _ = response.send(());
                        continue;
                    }
                    if platform_root::clean_and_resume(connected).is_err() {
                        context.vision.cleanup_finished(false);
                        let _ = response.send(());
                        continue;
                    }
                }
                input.reset();
                context.input_stop_requested.store(false, Ordering::Release);
                let _ = response.send(());
                continue;
            }
            Ok(RootControl::CapturePreview { response }) => {
                let result =
                    platform_root::capture_preview(client.as_mut().expect("attached client"));
                match result {
                    Ok(capture) => {
                        let _ = response.send(Ok(capture));
                    }
                    Err(error) => {
                        if error.connection_lost {
                            client = None;
                            notify_root_disconnected(context);
                        }
                        let _ = response.send(Err(error.message));
                    }
                }
                continue;
            }
            Ok(RootControl::InputFeatures { response }) => {
                let _ = response.send(client.as_ref().map_or(0, platform_root::input_features));
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

        let host_event = match input.next_lease_timeout() {
            Some(timeout) => match context.host_queue.wait_next_timeout(timeout) {
                Some(event) => event,
                None => {
                    if let Some(connected) = client.as_mut() {
                        let mut backend = platform_root::InputBackend::new(connected);
                        let result = input.expire_leases(Instant::now(), &mut backend);
                        if backend.connection_lost()
                            || matches!(
                                result,
                                Err(InputRuntimeError::Backend(BackendError {
                                    connection_lost: true,
                                    ..
                                }))
                            )
                        {
                            client = None;
                            notify_root_disconnected(context);
                        }
                    }
                    continue;
                }
            },
            None => context.host_queue.wait_next(),
        };
        match host_event {
            ExternalHostEvent::Dispatch {
                request,
                completion,
            } => {
                let coordinates = *context
                    .coordinates
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                let dispatch = if request.native_vision.is_some() {
                    RootDispatch {
                        result: context.vision.dispatch(&request, context),
                        connection_lost: false,
                    }
                } else {
                    match prepare_input_commands(&request, coordinates) {
                        Some(Ok(commands)) => {
                            context.vision.begin_input(&request);
                            let now =
                                MonoTime::from_nanos(context.boot_nanos.load(Ordering::Acquire));
                            if let Some(expires_at) = now.checked_add(request.timeout) {
                                let mut backend = platform_root::InputBackend::new(
                                    client.as_mut().expect("attached client"),
                                );
                                let result = input.dispatch_with_wait(
                                    request.request_id,
                                    request.task,
                                    now,
                                    expires_at,
                                    commands,
                                    &mut backend,
                                    || {
                                        if context.input_stop_requested.load(Ordering::Acquire) {
                                            InputControl::Stop
                                        } else if context
                                            .host_queue
                                            .take_cancellation(request.request_id, request.task)
                                            .is_some()
                                        {
                                            InputControl::Cancel
                                        } else {
                                            InputControl::Continue
                                        }
                                    },
                                    |duration, control| {
                                        let Some(deadline) = Instant::now().checked_add(duration)
                                        else {
                                            return InputControl::Stop;
                                        };
                                        loop {
                                            let requested = control();
                                            if requested != InputControl::Continue {
                                                return requested;
                                            }
                                            let Some(remaining) =
                                                deadline.checked_duration_since(Instant::now())
                                            else {
                                                return control();
                                            };
                                            context.host_queue.wait_input_control(
                                                request.request_id,
                                                request.task,
                                                remaining,
                                            );
                                        }
                                    },
                                );
                                let backend_lost = backend.connection_lost();
                                let mut rearm_failed = false;
                                if !context.input_stop_requested.load(Ordering::Acquire) {
                                    // Only a completed priority cleanup can re-arm a cancelled task.
                                    rearm_failed = !context.vision.rearm_input(
                                        client.as_ref().expect("attached client"),
                                        &context.input_stop_requested,
                                    );
                                    if rearm_failed {
                                        context.vision.cleanup_finished(false);
                                    }
                                }
                                let mut dispatch = input_runtime_result(result);
                                dispatch.connection_lost |= backend_lost || rearm_failed;
                                dispatch
                            } else {
                                input_failure("input timeout overflow")
                            }
                        }
                        Some(Err(dispatch)) => dispatch,
                        None => platform_root::dispatch(
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
                        ),
                    }
                };
                context.vision.end_input();
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
                let mut backend =
                    platform_root::InputBackend::new(client.as_mut().expect("attached client"));
                let result = input.cancel(request_id, &mut backend);
                if backend.connection_lost()
                    || matches!(
                        result,
                        Err(InputRuntimeError::Backend(BackendError {
                            connection_lost: true,
                            ..
                        }))
                    )
                {
                    client = None;
                    notify_root_disconnected(context);
                }
            }
            ExternalHostEvent::TaskFinished(task) => {
                let mut backend =
                    platform_root::InputBackend::new(client.as_mut().expect("attached client"));
                let result = input.release_task(task, &mut backend);
                if backend.connection_lost()
                    || matches!(
                        result,
                        Err(InputRuntimeError::Backend(BackendError {
                            connection_lost: true,
                            ..
                        }))
                    )
                {
                    client = None;
                    notify_root_disconnected(context);
                }
            }
            ExternalHostEvent::Interrupted => {}
            ExternalHostEvent::Stop => {
                if let Some(mut connected) = client.take() {
                    let mut backend = platform_root::InputBackend::new(&mut connected);
                    let _ = input.stop(&mut backend);
                    let _ = platform_root::shutdown(&mut connected);
                } else {
                    input.abandon();
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
    input: &mut InputRuntime,
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
                    #[cfg(target_os = "android")]
                    {
                        context.vision.attach_root(&client);
                        if let Some(stop) = client.priority_stop() {
                            let wake = context.wake_callback.clone();
                            stop.set_listener(Arc::new(move || {
                                if let Some(callback) = wake
                                    .lock()
                                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                                    .as_ref()
                                {
                                    callback.notify();
                                }
                            }));
                        }
                    }
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
            RootControl::ResetInput { response } => {
                input.reset();
                context.input_stop_requested.store(false, Ordering::Release);
                let _ = response.send(());
            }
            RootControl::CapturePreview { response } => {
                let _ = response.send(Err("ROOT_BACKEND_NOT_READY".to_owned()));
            }
            RootControl::InputFeatures { response } => {
                let _ = response.send(0);
            }
            RootControl::Shutdown => return None,
        }
    }
}

fn notify_root_disconnected(context: &RootWorkerContext) {
    context.vision.detach_root(false);
    context.attached.store(false, Ordering::Release);
    let callback = context
        .wake_callback
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(callback) = callback.as_ref() {
        callback.notify_root_disconnected();
    }
}

fn notify_root_wake(context: &RootWorkerContext) {
    if let Some(callback) = context
        .wake_callback
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .as_ref()
    {
        callback.notify();
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
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeSetVisionListener(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    listener: JObject<'_>,
) -> jint {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        get_session(handle)?.vision.set(&env, listener)
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
        let session = get_session(handle)?;
        let state = session.state.load(Ordering::Acquire);
        let (pending, failed) = session.vision.cleanup_status();
        Ok::<_, String>(if failed {
            STATE_FAILED
        } else if pending && state == STATE_STOPPED {
            STATE_STOPPING
        } else {
            state
        })
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
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeCapturePreview(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        get_session(handle)?.capture_preview()
    }));
    let Ok(Ok(bytes)) = result else {
        return std::ptr::null_mut();
    };
    env.byte_array_from_slice(&bytes)
        .map_or(std::ptr::null_mut(), JByteArray::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeDrainScriptLogs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jobjectArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        get_session(handle)?.drain_script_logs()
    }));
    let lines = result
        .unwrap_or_else(|_| Ok(Vec::new()))
        .unwrap_or_else(|_| Vec::new());
    let Ok(length) = i32::try_from(lines.len()) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_object_array(length, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (index, line) in lines.iter().enumerate() {
        let Ok(value) = env.new_string(line) else {
            return std::ptr::null_mut();
        };
        if env
            .set_object_array_element(&array, index as i32, value)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeDrainScriptPrompts(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jobjectArray {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let handle = u64::try_from(handle).map_err(|_| "SESSION_CLOSED".to_owned())?;
        get_session(handle)?.drain_script_prompts()
    }));
    let messages = result
        .unwrap_or_else(|_| Ok(Vec::new()))
        .unwrap_or_else(|_| Vec::new());
    let Ok(length) = i32::try_from(messages.len()) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_object_array(length, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (index, message) in messages.iter().enumerate() {
        let Ok(value) = env.new_string(message) else {
            return std::ptr::null_mut();
        };
        if env
            .set_object_array_element(&array, index as i32, value)
            .is_err()
        {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
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
    fn late_pump_after_completion_is_an_idempotent_no_op() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
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
            .expect("complete");
        assert_eq!(session.state.load(Ordering::Acquire), STATE_STOPPED);

        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 1,
                response,
            })
            .expect("late pump");

        assert_eq!(session.state.load(Ordering::Acquire), STATE_STOPPED);
        assert_eq!(session.last_diagnostic(), None);
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
    fn native_session_drains_script_logs_after_pump() {
        let handle = create_session(720, 1280).expect("create");
        let session = get_session(handle).expect("lookup");
        session
            .request(|response| SessionCommand::Start {
                source: b"return function() Log.info('bridge') end".to_vec(),
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

        assert_eq!(
            session.drain_script_logs().expect("drain"),
            vec!["脚本/INFO: bridge".to_owned()]
        );
        assert!(session
            .drain_script_logs()
            .expect("second drain")
            .is_empty());
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

        session
            .request(|response| SessionCommand::Pump {
                boot_nanos: 1,
                response,
            })
            .expect("late pump after failure");
        assert_eq!(session.state.load(Ordering::Acquire), STATE_FAILED);
        assert_eq!(
            session.last_diagnostic().as_deref(),
            Some(diagnostic.as_str())
        );

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
