#[cfg(target_os = "android")]
mod input_recording;

#[cfg(target_os = "android")]
mod android {
    use std::ffi::CString;
    use std::fs;
    use std::io::{self, Read, Write};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{FileTypeExt, PermissionsExt};
    use std::os::unix::net::{UnixListener, UnixStream};
    use std::path::{Path, PathBuf};
    use std::process::{
        Child, ChildStdin, ChildStdout, Command as ProcessCommand, ExitStatus, Stdio,
    };
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use root_daemon::{read_packet_with_deadlines, write_packet, TransportError};
    use root_daemon_core::priority::{InputCancellation, InputFence};
    use root_daemon_core::{
        CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
        SessionActivity, SessionState,
    };
    use root_protocol::{Capabilities, CaptureFailure, Command, Frame, InputFailure, StatusCode};

    const INPUT_BINARY: &str = "/system/bin/input";
    const GETPROP_BINARY: &str = "/system/bin/getprop";
    const SCREENCAP_BINARY: &str = "/system/bin/screencap";
    const MAX_CAPTURE_BYTES: usize = 64 * 1024 * 1024;
    const MAX_CAPTURE_CHUNK_BYTES: usize = 60 * 1024;
    const CAPTURE_TIMEOUT: Duration = Duration::from_secs(5);
    const PACKET_TIMEOUT: Duration = Duration::from_secs(8);

    #[derive(Clone)]
    struct RootSessionLifetime {
        started: Instant,
        idle: Duration,
        activity: Arc<SessionActivity>,
    }

    impl RootSessionLifetime {
        fn now_ms(&self) -> u64 {
            u64::try_from(self.started.elapsed().as_millis()).unwrap_or(u64::MAX)
        }

        fn read<D: CommandDispatcher>(
            &self,
            stream: &mut UnixStream,
            session: &DaemonSession<D>,
        ) -> Result<Vec<u8>, TransportError> {
            read_packet_with_deadlines(
                |bytes, remaining| {
                    stream.set_read_timeout(Some(remaining))?;
                    stream.read(bytes)
                },
                || Duration::from_millis(session.remaining_idle_ms(self.now_ms())),
                PACKET_TIMEOUT,
            )
        }
    }
    // Emulator input injection can be rejected while a host-originated pointer is unwinding.
    // Only retry complete, stateless commands; low-level pointer commands must stay single-shot.
    const INPUT_RETRY_DELAYS: [Duration; 2] =
        [Duration::from_millis(80), Duration::from_millis(200)];

    pub fn run() -> Result<(), String> {
        let arguments = Arguments::parse()?;
        prepare_socket_path(&arguments.socket)?;
        prepare_ready_path(&arguments.ready_file, &arguments.socket)?;
        let key = read_session_key(&arguments.key_file)?;
        let listener = UnixListener::bind(&arguments.socket).map_err(io_error)?;
        let mut endpoint_guard =
            EndpointGuard::new(arguments.socket.clone(), arguments.ready_file.clone());
        set_socket_owner(&arguments.socket, arguments.expected_uid)?;
        fs::set_permissions(&arguments.socket, fs::Permissions::from_mode(0o600))
            .map_err(io_error)?;
        let control_path = arguments.socket.with_extension("ctl");
        prepare_socket_path(&control_path)?;
        let control_listener = UnixListener::bind(&control_path).map_err(io_error)?;
        endpoint_guard.control = Some(control_path.clone());
        set_socket_owner(&control_path, arguments.expected_uid)?;
        fs::set_permissions(&control_path, fs::Permissions::from_mode(0o600)).map_err(io_error)?;
        fs::write(&arguments.ready_file, []).map_err(io_error)?;
        let (mut stream, _) = listener.accept().map_err(io_error)?;
        fs::remove_file(&arguments.ready_file).map_err(io_error)?;
        stream
            .set_read_timeout(Some(arguments.idle_timeout))
            .map_err(io_error)?;
        stream
            .set_write_timeout(Some(Duration::from_secs(8)))
            .map_err(io_error)?;
        let peer_uid = peer_uid(&stream).map_err(io_error)?;
        let lifetime = RootSessionLifetime {
            started: Instant::now(),
            idle: arguments.idle_timeout,
            activity: Arc::new(SessionActivity::default()),
        };
        let input_bridge = arguments
            .apk
            .as_deref()
            .and_then(|apk| RootInputProcess::start(apk).ok());
        let mut capability_bits = Capabilities::INPUT_BASIC
            | Capabilities::CAPTURE_RAW
            | Capabilities::CAPTURE_STREAM
            | Capabilities::CAPTURE_STAGE_TIMINGS
            | Capabilities::CAPTURE_FAILURE_DETAILS
            | Capabilities::CAPTURE_TRANSFER_TIMINGS
            | Capabilities::CAPTURE_CONTROL_DETAILS
            | Capabilities::CAPTURE_PIPELINED
            | Capabilities::CAPTURE_REGION
            | Capabilities::INPUT_FAILURE_DETAILS
            | Capabilities::INPUT_PRIORITY_STOP;
        if input_bridge.is_some() || supports_pointer_commands() {
            capability_bits |= Capabilities::INPUT_POINTER_SINGLE;
        }
        if input_bridge.is_some() {
            capability_bits |= Capabilities::INPUT_MULTI_TOUCH;
        }
        let recording = Arc::new(super::input_recording::RecordingHub::default());
        let dispatcher = Arc::new(Mutex::new(AndroidDispatcher {
            input_bridge,
            capture_bridge: arguments
                .apk
                .as_deref()
                .and_then(|apk| RootInputProcess::start_capture(apk).ok()),
            next_capture_id: 0,
            capture: None,
            reusable_capture_pixels: Vec::new(),
            capture_read_wait_us: 0,
            active_pointer: None,
            multi_active: std::collections::BTreeMap::new(),
            pointer_owner: 0,
            input_failure: None,
            cancellation: InputCancellation::default(),
            recording: recording.clone(),
        }));
        let fence = Arc::new(InputFence::default());
        // This guard is owned by the business connection, not the detached control thread.
        // EOF must still release pointers even while the control listener holds another Arc.
        let _disconnect_cleanup = DisconnectCleanup {
            dispatcher: dispatcher.clone(),
            fence: fence.clone(),
        };
        start_priority_server(
            control_listener,
            root_protocol::priority_control_key(&key),
            arguments.expected_uid,
            lifetime.clone(),
            dispatcher.clone(),
            fence.clone(),
            recording,
        )?;
        capability_bits |= Capabilities::INPUT_RECORDING | Capabilities::INPUT_RECORDING_TIMED;
        let mut session = DaemonSession::new(
            key,
            peer_uid,
            DaemonSessionConfig {
                expected_runner_uid: arguments.expected_uid,
                server_capabilities: Capabilities::from_bits(capability_bits),
                required_client_capabilities: 0,
                idle_timeout_ms: u64::try_from(arguments.idle_timeout.as_millis())
                    .map_err(|error| error.to_string())?,
                now_ms: lifetime.now_ms(),
            },
            SharedDispatcher { dispatcher, fence },
        )
        .map_err(|error| format!("daemon handshake initialization failed: {error:?}"))?
        .with_shared_activity(lifetime.activity.clone());
        let mut capture_packet = Vec::new();
        while session.state() != SessionState::Closed {
            let packet = match lifetime.read(&mut stream, &session) {
                Ok(packet) => packet,
                Err(root_daemon::TransportError::Io(error))
                    if matches!(
                        error.kind(),
                        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock
                    ) =>
                {
                    return Err("daemon connection idle timeout".to_owned());
                }
                Err(error) => return Err(format!("daemon transport failed: {error:?}")),
            };
            let response = session
                .receive(&packet, lifetime.now_ms())
                .map_err(|error| format!("daemon protocol failed: {error:?}"))?;
            write_packet(&mut stream, &response)
                .map_err(|error| format!("daemon response failed: {error:?}"))?;
            let mut transfer = root_protocol::CaptureTransferSummary {
                encode_us: 0,
                write_us: 0,
                packets: 0,
            };
            loop {
                let encode_started = Instant::now();
                if !session
                    .next_capture_packet_into(lifetime.now_ms(), &mut capture_packet)
                    .map_err(|e| format!("capture stream failed: {e:?}"))?
                {
                    break;
                }
                transfer.encode_us = transfer.encode_us.saturating_add(
                    elapsed_micros(encode_started)
                        .saturating_sub(session.take_capture_read_wait_us()),
                );
                let write_started = Instant::now();
                write_packet(&mut stream, &capture_packet)
                    .map_err(|e| format!("capture stream write failed: {e:?}"))?;
                transfer.write_us = transfer
                    .write_us
                    .saturating_add(elapsed_micros(write_started));
                transfer.packets += 1;
            }
            if session
                .finish_capture_transfer_into(transfer, &mut capture_packet)
                .map_err(|e| format!("capture summary failed: {e:?}"))?
            {
                write_packet(&mut stream, &capture_packet)
                    .map_err(|e| format!("capture summary write failed: {e:?}"))?;
            }
        }
        drop(endpoint_guard);
        Ok(())
    }

    struct Arguments {
        socket: PathBuf,
        expected_uid: u32,
        key_file: PathBuf,
        ready_file: PathBuf,
        idle_timeout: Duration,
        apk: Option<PathBuf>,
    }

    fn elapsed_micros(started: Instant) -> u64 {
        u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX)
    }

    impl Arguments {
        fn parse() -> Result<Self, String> {
            let values = std::env::args().skip(1).collect::<Vec<_>>();
            if !matches!(values.len(), 5 | 6) {
                return Err(
                    "usage: root-daemon <socket-path> <runner-uid> <key-file> <ready-file> <idle-ms> [installed-apk]"
                        .to_owned(),
                );
            }
            let socket = PathBuf::from(&values[0]);
            let expected_uid = values[1]
                .parse::<u32>()
                .map_err(|_| "invalid Runner UID".to_owned())?;
            let key_file = PathBuf::from(&values[2]);
            let ready_file = PathBuf::from(&values[3]);
            let idle_millis = values[4]
                .parse::<u64>()
                .map_err(|_| "invalid idle timeout".to_owned())?;
            if !(1_000..=300_000).contains(&idle_millis) {
                return Err("idle timeout outside 1s..5min".to_owned());
            }
            Ok(Self {
                socket,
                expected_uid,
                key_file,
                ready_file,
                idle_timeout: Duration::from_millis(idle_millis),
                apk: values.get(5).map(PathBuf::from),
            })
        }
    }

    fn prepare_socket_path(path: &Path) -> Result<(), String> {
        let text = path
            .to_str()
            .ok_or_else(|| "socket path is not UTF-8".to_owned())?;
        if !path.is_absolute()
            || (!text.starts_with("/data/user/") && !text.starts_with("/data/data/"))
        {
            return Err("socket must be inside Runner private app data".to_owned());
        }
        if let Ok(metadata) = fs::symlink_metadata(path) {
            if !metadata.file_type().is_socket() {
                return Err("refusing to replace a non-socket path".to_owned());
            }
            fs::remove_file(path).map_err(io_error)?;
        }
        Ok(())
    }

    fn set_socket_owner(path: &Path, uid: u32) -> Result<(), String> {
        let path = CString::new(path.as_os_str().as_encoded_bytes())
            .map_err(|_| "socket path contains a NUL byte".to_owned())?;
        // SAFETY: `path` is a live NUL-terminated string and `chown` does not retain it.
        let result = unsafe { libc::chown(path.as_ptr(), uid, uid) };
        if result == 0 {
            Ok(())
        } else {
            Err(io_error(io::Error::last_os_error()))
        }
    }

    fn prepare_ready_path(ready_path: &Path, socket_path: &Path) -> Result<(), String> {
        if !ready_path.is_absolute() || ready_path.parent() != socket_path.parent() {
            return Err("ready file must share the private socket directory".to_owned());
        }
        if fs::symlink_metadata(ready_path).is_ok() {
            return Err("refusing to replace an existing ready file".to_owned());
        }
        Ok(())
    }

    struct EndpointGuard {
        socket: PathBuf,
        ready_file: PathBuf,
        control: Option<PathBuf>,
    }

    impl EndpointGuard {
        const fn new(socket: PathBuf, ready_file: PathBuf) -> Self {
            Self {
                socket,
                ready_file,
                control: None,
            }
        }
    }

    impl Drop for EndpointGuard {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.ready_file);
            let _ = fs::remove_file(&self.socket);
            if let Some(control) = self.control.as_ref() {
                let _ = fs::remove_file(control);
            }
        }
    }

    fn read_session_key(path: &Path) -> Result<[u8; 32], String> {
        let metadata = fs::symlink_metadata(path).map_err(io_error)?;
        if !metadata.file_type().is_file() || metadata.file_type().is_symlink() {
            return Err("session key path must be a regular file".to_owned());
        }
        let bytes = fs::read(path).map_err(io_error)?;
        fs::remove_file(path).map_err(io_error)?;
        let key: [u8; 32] = bytes
            .try_into()
            .map_err(|_| "session key must contain exactly 32 bytes".to_owned())?;
        if key.iter().all(|byte| *byte == 0) {
            return Err("session key cannot be all zero".to_owned());
        }
        Ok(key)
    }

    fn peer_uid(stream: &UnixStream) -> io::Result<u32> {
        let mut credentials = libc::ucred {
            pid: 0,
            uid: 0,
            gid: 0,
        };
        let mut length = std::mem::size_of::<libc::ucred>() as libc::socklen_t;
        // SAFETY: `credentials` and `length` are valid writable pointers for the exact
        // `SO_PEERCRED` structure, and the borrowed stream keeps the fd alive for this call.
        let result = unsafe {
            libc::getsockopt(
                stream.as_raw_fd(),
                libc::SOL_SOCKET,
                libc::SO_PEERCRED,
                std::ptr::addr_of_mut!(credentials).cast(),
                std::ptr::addr_of_mut!(length),
            )
        };
        if result == 0 && usize::try_from(length).ok() == Some(std::mem::size_of::<libc::ucred>()) {
            Ok(credentials.uid)
        } else {
            Err(io::Error::last_os_error())
        }
    }

    #[derive(Default)]
    struct AndroidDispatcher {
        capture_bridge: Option<RootInputProcess>,
        next_capture_id: u64,
        capture: Option<StagedCapture>,
        /// Reuses the last released bridge frame allocation without retaining pixels as a frame.
        /// The buffer is only moved back after an explicit ReleaseCapture, so an active frame
        /// never aliases a subsequent capture.
        reusable_capture_pixels: Vec<u8>,
        active_pointer: Option<(u8, i32, i32)>,
        multi_active: std::collections::BTreeMap<u8, (i32, i32)>,
        input_bridge: Option<RootInputProcess>,
        pointer_owner: u64,
        input_failure: Option<InputFailure>,
        cancellation: InputCancellation,
        recording: Arc<super::input_recording::RecordingHub>,
        capture_read_wait_us: u64,
    }

    struct SharedDispatcher {
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
    }
    struct DisconnectCleanup {
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
    }
    impl Drop for DisconnectCleanup {
        fn drop(&mut self) {
            self.fence.cancel_through(u64::MAX);
            if let Ok(mut dispatcher) = self.dispatcher.lock() {
                dispatcher.recording.cancel_all();
                dispatcher.cancellation = InputCancellation::default();
                if let Some((_, x, y)) = dispatcher.active_pointer {
                    if dispatcher.inject_pointer(1, x, y) == StatusCode::Ok {
                        dispatcher.active_pointer = None;
                    } else {
                        self.fence.fail_closed();
                    }
                }
                dispatcher.input_bridge.take(); // EOF/finally is an additional bounded cleanup attempt.
            }
        }
    }
    fn input_command(command: Command) -> bool {
        matches!(
            command,
            Command::Tap
                | Command::Swipe
                | Command::KeyEvent
                | Command::PointerDown
                | Command::PointerMove
                | Command::PointerUp
                | Command::RecordingStart
        )
    }
    impl CommandDispatcher for SharedDispatcher {
        fn take_capture_read_wait_us(&mut self) -> u64 {
            self.dispatcher
                .lock()
                .map_or(0, |mut d| std::mem::take(&mut d.capture_read_wait_us))
        }
        fn dispatch_capture_stream(
            &mut self,
            frame: &Frame,
            pipelined: bool,
        ) -> Result<DispatchResponse, DispatchError> {
            self.dispatch_with_pipeline(frame, pipelined)
        }
        fn capture_failure_reason(&self) -> Option<CaptureFailure> {
            self.fence
                .is_failed()
                .then_some(CaptureFailure::ControlUnavailable)
        }
        fn fill_capture_chunk(
            &mut self,
            frame: &Frame,
            prefix: &[u8; 5],
            output: &mut Vec<u8>,
        ) -> Result<StatusCode, DispatchError> {
            output.clear();
            let mut dispatcher = self.dispatcher.lock().map_err(|_| DispatchError)?;
            if !self.fence.request_allowed(frame.request_id) {
                return Ok(if self.fence.is_failed() {
                    StatusCode::BackendFailure
                } else {
                    StatusCode::Cancelled
                });
            }
            Ok(dispatcher.fill_capture_chunk(&frame.payload, prefix, output))
        }

        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            self.dispatch_with_pipeline(frame, false)
        }
    }
    impl SharedDispatcher {
        fn dispatch_with_pipeline(
            &mut self,
            frame: &Frame,
            pipelined: bool,
        ) -> Result<DispatchResponse, DispatchError> {
            let mut dispatcher = self.dispatcher.lock().map_err(|_| DispatchError)?;
            if frame.command == Command::CaptureChunk
                && !self.fence.request_allowed(frame.request_id)
            {
                return Ok(empty_response(StatusCode::Cancelled));
            }
            let capture = matches!(frame.command, Command::Capture | Command::CaptureStream);
            if !input_command(frame.command) && !capture {
                let response = dispatcher.dispatch(frame);
                if frame.command == Command::ReleaseCapture {
                    self.fence.finish(frame.request_id);
                }
                return response;
            }
            if dispatcher.recording.active() && !capture {
                return Ok(empty_response(StatusCode::InvalidRequest));
            }
            let Some(token) = self.fence.begin(frame.request_id) else {
                if capture && self.fence.is_failed() {
                    return Ok(capture_failure_response(CaptureFailure::ControlUnavailable));
                }
                if self.fence.is_failed() && frame.command != Command::RecordingStart {
                    return Ok(DispatchResponse {
                        status: StatusCode::BackendFailure,
                        payload: (InputFailure::ControlUnavailable as u32)
                            .to_le_bytes()
                            .to_vec(),
                    });
                }
                return Ok(empty_response(if self.fence.is_failed() {
                    StatusCode::BackendFailure
                } else {
                    StatusCode::Cancelled
                }));
            };
            dispatcher.cancellation = token.clone();
            dispatcher.pointer_owner = frame.request_id;
            let result =
                if pipelined && frame.command == Command::CaptureStream && frame.payload.is_empty()
                {
                    Ok(dispatcher.capture(true, true, None))
                } else {
                    dispatcher.dispatch(frame)
                };
            // Keep the watchdog's cancellation token owned by the stream until its trailer/release.
            if !result
                .as_ref()
                .is_ok_and(|r| r.status == StatusCode::Ok && r.payload.first() == Some(&3))
            {
                self.fence.finish(frame.request_id);
            }
            if token.is_cancelled() {
                self.fence.finish(frame.request_id);
                if capture {
                    if let Some(id) = dispatcher.capture.as_ref().map(|capture| capture.id) {
                        // No metadata was sent: abandon this stream and its private bridge bytes.
                        let _ = dispatcher.release_capture(&id.to_le_bytes());
                    }
                }
                if capture && self.fence.is_failed() {
                    return Ok(capture_failure_response(CaptureFailure::ControlUnavailable));
                }
                if frame.command == Command::RecordingStart {
                    dispatcher.recording.cancel_all();
                }
                Ok(empty_response(StatusCode::Cancelled))
            } else {
                result
            }
        }
    }
    struct StopDispatcher {
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
        recording: Arc<super::input_recording::RecordingHub>,
    }
    impl CommandDispatcher for StopDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            if frame.command == Command::RecordingFinish {
                let finish = root_protocol::recording::RecordingFinish::decode(&frame.payload)
                    .map_err(|_| DispatchError)?;
                return Ok(match self.recording.finish(finish) {
                    Ok(result) => DispatchResponse {
                        status: StatusCode::Ok,
                        payload: result.encode(),
                    },
                    Err(_) => empty_response(StatusCode::InvalidRequest),
                });
            }
            if frame.command != Command::Cancel {
                return Ok(empty_response(StatusCode::InvalidRequest));
            }
            let cutoff = read_u64(&frame.payload, 0);
            // Recording has its own token; even an otherwise stale runtime-stop must cancel it.
            self.recording.cancel_all();
            // Stale stop acknowledges without waiting for a newer long gesture's mutex.
            if !self.fence.cancel_through(cutoff) {
                return Ok(empty_response(StatusCode::Ok));
            }
            let mut dispatcher = self.dispatcher.lock().map_err(|_| DispatchError)?;
            if self.fence.is_failed() {
                return Ok(empty_response(StatusCode::BackendFailure));
            }
            let mut status = StatusCode::Ok;
            if dispatcher.pointer_owner <= cutoff {
                if let Some((_, x, y)) = dispatcher.active_pointer {
                    dispatcher.cancellation = InputCancellation::default();
                    status = dispatcher.inject_pointer(1, x, y);
                    if status == StatusCode::Ok {
                        dispatcher.active_pointer = None;
                    }
                }
            }
            if status != StatusCode::Ok {
                self.fence.fail_closed();
            }
            Ok(empty_response(status))
        }
    }
    fn start_priority_server(
        listener: UnixListener,
        key: [u8; 32],
        uid: u32,
        lifetime: RootSessionLifetime,
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
        recording: Arc<super::input_recording::RecordingHub>,
    ) -> Result<(), String> {
        std::thread::Builder::new()
            .name("root-priority-stop".into())
            .spawn(move || {
                let result = (|| -> Result<(), String> {
                    let (mut stream, _) = listener.accept().map_err(io_error)?;
                    stream
                        .set_write_timeout(Some(Duration::from_secs(8)))
                        .map_err(io_error)?;
                    let mut session = DaemonSession::new(
                        key,
                        peer_uid(&stream).map_err(io_error)?,
                        DaemonSessionConfig {
                            expected_runner_uid: uid,
                            server_capabilities: Capabilities::from_bits(
                                Capabilities::INPUT_PRIORITY_STOP | Capabilities::INPUT_RECORDING,
                            ),
                            required_client_capabilities: Capabilities::INPUT_PRIORITY_STOP,
                            idle_timeout_ms: u64::try_from(lifetime.idle.as_millis())
                                .map_err(|e| e.to_string())?,
                            now_ms: lifetime.now_ms(),
                        },
                        StopDispatcher {
                            dispatcher: dispatcher.clone(),
                            fence: fence.clone(),
                            recording: recording.clone(),
                        },
                    )
                    .map_err(|_| "stop handshake failed")?
                    .with_shared_activity(lifetime.activity.clone());
                    loop {
                        let request = lifetime
                            .read(&mut stream, &session)
                            .map_err(|_| "stop transport failed")?;
                        let reply = session
                            .receive(&request, lifetime.now_ms())
                            .map_err(|_| "stop protocol failed")?;
                        write_packet(&mut stream, &reply).map_err(|_| "stop response failed")?;
                    }
                })();
                if result.is_err() {
                    fence.fail_closed();
                    recording.cancel_all();
                    // Cancellation unblocks active dispatch before pointer/process cleanup takes its lock.
                    drop(DisconnectCleanup { dispatcher, fence });
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    struct StagedCapture {
        region: Option<root_protocol::capture_region::CaptureRegionMetadata>,
        id: u64,
        width: u32,
        height: u32,
        row_stride: u32,
        format: u8,
        pixels: Vec<u8>,
        stages_us: Option<[u64; 6]>,
        pipeline: Option<BridgeCapturePipeline>,
    }

    fn crop_region_capture(
        mut capture: StagedCapture,
        region: Option<root_protocol::capture_region::CaptureRegionRequest>,
    ) -> Result<StagedCapture, CaptureFailure> {
        let Some(region) = region else {
            return Ok(capture);
        };
        if capture.region.is_some() {
            return Ok(capture);
        }
        if capture.pipeline.is_some()
            || capture.width != region.screen_width
            || capture.height != region.screen_height
        {
            return Err(CaptureFailure::InvalidFrame);
        }
        let width = region.right - region.left;
        let height = region.bottom - region.top;
        let row_bytes = width as usize * 4;
        for row in 0..height as usize {
            let start = (region.top as usize + row) * capture.row_stride as usize
                + region.left as usize * 4;
            capture
                .pixels
                .copy_within(start..start + row_bytes, row * row_bytes);
        }
        capture.pixels.truncate(row_bytes * height as usize);
        capture.width = width;
        capture.height = height;
        capture.row_stride = width * 4;
        capture.region = Some(root_protocol::capture_region::CaptureRegionMetadata {
            request: region,
            rotation: None,
            regional_readback: false,
        });
        Ok(capture)
    }

    struct BridgeCapturePipeline {
        length: usize,
        received: usize,
        started: Instant,
        receive_started: Instant,
        deadline: Instant,
        watchdog: CaptureWatchdogLease,
        acquisition_us: Option<u64>,
    }

    impl CommandDispatcher for AndroidDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            match frame.command {
                Command::RecordingStart => {
                    if self.active_pointer.is_some() {
                        return Ok(empty_response(StatusCode::InvalidRequest));
                    }
                    let config = root_protocol::recording::RecordingConfig::decode(&frame.payload)
                        .map_err(|_| DispatchError)?;
                    return Ok(match self.recording.start(config) {
                        Ok(()) => DispatchResponse::success(),
                        Err(1) => empty_response(StatusCode::BackendUnavailable),
                        Err(_) => empty_response(StatusCode::InvalidRequest),
                    });
                }
                Command::RecordingFinish => return Ok(empty_response(StatusCode::InvalidRequest)),
                Command::Capture | Command::CaptureStream => {
                    let region =
                        root_protocol::capture_region::CaptureRegionRequest::decode(&frame.payload);
                    return Ok(self.capture(
                        frame.command == Command::CaptureStream,
                        false,
                        region,
                    ));
                }
                Command::CaptureChunk => return Ok(self.capture_chunk(&frame.payload)),
                Command::ReleaseCapture => return Ok(self.release_capture(&frame.payload)),
                _ => {}
            }
            self.input_failure = None;
            let response = match frame.command {
                Command::Tap | Command::Swipe if self.active_pointer.is_some() => {
                    StatusCode::InvalidRequest
                }
                Command::Tap => {
                    let x = read_i32(&frame.payload, 0);
                    let y = read_i32(&frame.payload, 4);
                    self.active_pointer = Some((0, x, y));
                    let status = if self.input_bridge.is_some() {
                        let cancellation = self.cancellation.clone();
                        root_daemon::tap::inject_tap(
                            || cancellation.is_cancelled(),
                            |action| self.inject_pointer(action, x, y),
                        )
                    } else {
                        let (status, _, reason) = run_input_once(
                            &["tap".to_owned(), x.to_string(), y.to_string()],
                            &self.cancellation,
                        );
                        self.input_failure = reason;
                        status
                    };
                    if status == StatusCode::Ok {
                        self.active_pointer = None;
                    }
                    status
                }
                Command::Swipe => {
                    let duration = read_u32(&frame.payload, 16);
                    self.active_pointer =
                        Some((0, read_i32(&frame.payload, 8), read_i32(&frame.payload, 12)));
                    let (status, _, reason) = run_input_once(
                        &[
                            "swipe".to_owned(),
                            read_i32(&frame.payload, 0).to_string(),
                            read_i32(&frame.payload, 4).to_string(),
                            read_i32(&frame.payload, 8).to_string(),
                            read_i32(&frame.payload, 12).to_string(),
                            duration.to_string(),
                        ],
                        &self.cancellation,
                    );
                    self.input_failure = reason;
                    if status == StatusCode::Ok {
                        self.active_pointer = None;
                    }
                    status
                }
                Command::KeyEvent => run_input_retryable(
                    &[
                        "keyevent".to_owned(),
                        read_u32(&frame.payload, 0).to_string(),
                    ],
                    &self.cancellation,
                ),
                Command::PointerDown => self.pointer_down(&frame.payload),
                Command::PointerMove => self.pointer_move(&frame.payload),
                Command::PointerUp => self.pointer_up(&frame.payload),
                Command::GetWindowBounds => StatusCode::BackendUnavailable,
                Command::Cancel => StatusCode::InvalidRequest,
                Command::Ping | Command::Shutdown => StatusCode::Ok,
                Command::Hello
                | Command::Capture
                | Command::CaptureStream
                | Command::CaptureChunk
                | Command::ReleaseCapture
                | Command::RecordingStart
                | Command::RecordingFinish => StatusCode::InvalidRequest,
            };
            Ok(DispatchResponse {
                status: response,
                payload: if matches!(
                    response,
                    StatusCode::BackendFailure | StatusCode::BackendUnavailable
                ) && matches!(
                    frame.command,
                    Command::Tap
                        | Command::Swipe
                        | Command::KeyEvent
                        | Command::PointerDown
                        | Command::PointerMove
                        | Command::PointerUp
                ) {
                    (self
                        .input_failure
                        .unwrap_or(InputFailure::InjectionRejected) as u32)
                        .to_le_bytes()
                        .to_vec()
                } else {
                    Vec::new()
                },
            })
        }
    }

    impl AndroidDispatcher {
        fn pointer_down(&mut self, payload: &[u8]) -> StatusCode {
            let Some((pointer_id, x, y)) = parse_pointer_position(payload) else {
                return StatusCode::InvalidRequest;
            };
            if self.input_bridge.is_some() {
                if x < 0
                    || y < 0
                    || self.multi_active.contains_key(&pointer_id)
                    || self.multi_active.len() >= 5
                    || self.multi_active.is_empty() && self.active_pointer.is_some()
                {
                    return StatusCode::InvalidRequest;
                }
                self.multi_active.insert(pointer_id, (x, y));
                self.active_pointer = Some((pointer_id, x, y));
                return self.inject_multi(0, pointer_id, x, y);
            }
            if pointer_id != 0 {
                return StatusCode::BackendUnavailable;
            }
            if self.active_pointer.is_some() {
                return StatusCode::InvalidRequest;
            }
            self.active_pointer = Some((pointer_id, x, y));
            let status = self.inject_pointer(0, x, y);
            if status != StatusCode::Ok && self.inject_pointer(1, x, y) == StatusCode::Ok {
                self.active_pointer = None;
            }
            status
        }

        fn pointer_move(&mut self, payload: &[u8]) -> StatusCode {
            let Some((pointer_id, x, y)) = parse_pointer_position(payload) else {
                return StatusCode::InvalidRequest;
            };
            if self.input_bridge.is_some() {
                if x < 0 || y < 0 || !self.multi_active.contains_key(&pointer_id) {
                    return StatusCode::InvalidRequest;
                }
                let status = self.inject_multi(2, pointer_id, x, y);
                if status == StatusCode::Ok {
                    self.multi_active.insert(pointer_id, (x, y));
                    self.active_pointer = Some((pointer_id, x, y));
                }
                return status;
            }
            if !self
                .active_pointer
                .is_some_and(|(active_id, _, _)| active_id == pointer_id)
            {
                return StatusCode::InvalidRequest;
            }
            let status = self.inject_pointer(2, x, y);
            if status == StatusCode::Ok {
                self.active_pointer = Some((pointer_id, x, y));
            }
            status
        }

        fn pointer_up(&mut self, payload: &[u8]) -> StatusCode {
            let Some(pointer_id) = parse_pointer_id(payload) else {
                return StatusCode::InvalidRequest;
            };
            if self.input_bridge.is_some() {
                let Some(&(x, y)) = self.multi_active.get(&pointer_id) else {
                    return StatusCode::InvalidRequest;
                };
                let status = self.inject_multi(1, pointer_id, x, y);
                if status == StatusCode::Ok {
                    self.multi_active.remove(&pointer_id);
                    self.active_pointer = self
                        .multi_active
                        .iter()
                        .next()
                        .map(|(&id, &(x, y))| (id, x, y));
                }
                return status;
            }
            let Some((active_id, x, y)) = self.active_pointer else {
                return StatusCode::InvalidRequest;
            };
            if active_id != pointer_id {
                return StatusCode::InvalidRequest;
            }
            let status = self.inject_pointer(1, x, y);
            if status == StatusCode::Ok {
                self.active_pointer = None;
            }
            status
        }

        fn inject_pointer(&mut self, action: i32, x: i32, y: i32) -> StatusCode {
            if action == 1 && !self.multi_active.is_empty() {
                let status = self.inject_multi(3, 0, 0, 0);
                if status == StatusCode::Ok {
                    self.multi_active.clear();
                }
                return status;
            }
            if let Some(bridge) = self.input_bridge.as_mut() {
                return match bridge.inject(action, x, y) {
                    Ok(status) => status,
                    Err(error) => {
                        self.input_failure = Some(if error.kind() == io::ErrorKind::TimedOut {
                            InputFailure::Deadline
                        } else {
                            InputFailure::BridgeTransport
                        });
                        StatusCode::BackendUnavailable
                    }
                };
            }
            run_pointer(
                match action {
                    0 => "DOWN",
                    1 => "UP",
                    _ => "MOVE",
                },
                x,
                y,
                &self.cancellation,
            )
        }

        fn inject_multi(&mut self, action: i32, id: u8, x: i32, y: i32) -> StatusCode {
            if action == 3 {
                let points = self
                    .multi_active
                    .iter()
                    .map(|(&id, &(x, y))| (id, x, y))
                    .collect::<Vec<_>>();
                return self
                    .input_bridge
                    .as_mut()
                    .and_then(|bridge| bridge.cancel_pointers(&points).ok())
                    .unwrap_or(StatusCode::BackendUnavailable);
            }
            self.input_bridge
                .as_mut()
                .and_then(|bridge| {
                    bridge
                        .inject(0x10000 | (i32::from(id) << 8) | action, x, y)
                        .ok()
                })
                .unwrap_or(StatusCode::BackendUnavailable)
        }

        fn capture(
            &mut self,
            streamed: bool,
            pipelined: bool,
            region: Option<root_protocol::capture_region::CaptureRegionRequest>,
        ) -> DispatchResponse {
            if self.cancellation.is_cancelled() {
                return empty_response(StatusCode::Cancelled);
            }
            let started = Instant::now();
            let id = self.next_capture_id.checked_add(1);
            let Some(id) = id else {
                return capture_failure_response(CaptureFailure::IdExhausted);
            };
            let mut backend = 1_u32;
            let bridge_capture = self.capture_bridge.as_mut().and_then(|bridge| {
                if pipelined {
                    bridge
                        .begin_capture_pipeline(id, self.cancellation.clone(), started)
                        .ok()
                } else {
                    bridge
                        .capture(
                            id,
                            self.cancellation.clone(),
                            &mut self.reusable_capture_pixels,
                            region,
                        )
                        .ok()
                }
            });
            let capture = if let Some(capture) = bridge_capture {
                backend = 2;
                Ok(capture)
            } else {
                // A broken/unsupported bridge is disabled for this connection, not retried per frame.
                self.capture_bridge.take();
                if self.cancellation.is_cancelled() {
                    return empty_response(StatusCode::Cancelled);
                }
                let remaining = CAPTURE_TIMEOUT.saturating_sub(started.elapsed());
                if remaining.is_zero() {
                    return capture_failure_response(CaptureFailure::Deadline);
                }
                run_screencap(id, self.cancellation.clone(), remaining)
            };
            let capture = match capture.and_then(|capture| crop_region_capture(capture, region)) {
                Ok(capture) => capture,
                Err(reason) => {
                    // Stop wins over backend errors; diagnostics cannot revive a request.
                    if self.cancellation.is_cancelled() {
                        return empty_response(StatusCode::Cancelled);
                    }
                    return capture_failure_response(if started.elapsed() >= CAPTURE_TIMEOUT {
                        CaptureFailure::Deadline
                    } else {
                        reason
                    });
                }
            };
            if self.cancellation.is_cancelled() {
                if capture.pipeline.is_some() {
                    // Metadata is still private: discard the unread bridge stream as well.
                    if let Some(bridge) = self.capture_bridge.as_mut() {
                        bridge.abort_capture();
                    }
                }
                return empty_response(StatusCode::Cancelled);
            }
            self.next_capture_id = id;
            let byte_length = u64::try_from(
                capture
                    .pipeline
                    .as_ref()
                    .map_or(capture.pixels.len(), |p| p.length),
            )
            .expect("capture is bounded");
            let mut payload = Vec::with_capacity(32);
            payload.extend_from_slice(&capture.id.to_le_bytes());
            payload.extend_from_slice(&capture.width.to_le_bytes());
            payload.extend_from_slice(&capture.height.to_le_bytes());
            payload.extend_from_slice(&capture.row_stride.to_le_bytes());
            payload.push(capture.format);
            payload.extend_from_slice(&[0, 0, 0]);
            payload.extend_from_slice(&byte_length.to_le_bytes());
            if streamed {
                payload.extend_from_slice(
                    &(if capture.pipeline.is_some() {
                        0
                    } else {
                        u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX)
                    })
                    .to_le_bytes(),
                );
                payload.extend_from_slice(&backend.to_le_bytes());
                payload.extend_from_slice(&[0; 4]);
                payload.insert(0, if capture.pipeline.is_some() { 3 } else { 0 });
                if let Some(stages) = capture.stages_us {
                    for duration in stages {
                        payload.extend_from_slice(&duration.to_le_bytes());
                    }
                }
                if let Some(region) = capture.region {
                    payload[0] = 5;
                    payload.extend_from_slice(&region.encode());
                }
            }
            self.capture = Some(capture);
            DispatchResponse {
                status: StatusCode::Ok,
                payload,
            }
        }

        fn capture_chunk(&mut self, payload: &[u8]) -> DispatchResponse {
            let mut output = Vec::new();
            let status = self.fill_capture_chunk(payload, &[], &mut output);
            DispatchResponse {
                status,
                payload: output,
            }
        }

        fn fill_capture_chunk(
            &mut self,
            payload: &[u8],
            prefix: &[u8],
            output: &mut Vec<u8>,
        ) -> StatusCode {
            output.clear();
            self.capture_read_wait_us = 0;
            let id = read_u64(payload, 0);
            let offset = usize::try_from(read_u32(payload, 8)).unwrap_or(usize::MAX);
            let length = usize::try_from(read_u32(payload, 12)).unwrap_or(usize::MAX);
            let Some(capture) = self.capture.as_mut().filter(|capture| capture.id == id) else {
                return StatusCode::InvalidRequest;
            };
            if length == 0 || length > MAX_CAPTURE_CHUNK_BYTES {
                return StatusCode::InvalidRequest;
            }
            let Some(end) = offset.checked_add(length) else {
                return StatusCode::InvalidRequest;
            };
            if let Some(pipeline) = capture.pipeline.as_mut() {
                if offset != pipeline.received
                    || end > pipeline.length
                    || pipeline.acquisition_us.is_some()
                {
                    return StatusCode::InvalidRequest;
                }
                if self.cancellation.is_cancelled() {
                    return StatusCode::Cancelled;
                }
                output.extend_from_slice(prefix);
                output.resize(prefix.len() + length, 0);
                let Some(bridge) = self.capture_bridge.as_mut() else {
                    return StatusCode::BackendFailure;
                };
                let read_started = Instant::now();
                let read = (|| -> io::Result<()> {
                    bridge.read_bounded(
                        &mut output[prefix.len()..],
                        pipeline.deadline.saturating_duration_since(Instant::now()),
                    )?;
                    pipeline.received = end;
                    if end == pipeline.length {
                        let mut trailer = [0; 40];
                        bridge.read_bounded(
                            &mut trailer,
                            pipeline.deadline.saturating_duration_since(Instant::now()),
                        )?;
                        capture.stages_us = Some(
                            root_daemon::decode_capture_stage_trailer(
                                &trailer,
                                elapsed_micros(pipeline.receive_started),
                            )
                            .ok_or_else(|| io::Error::other("invalid capture timings"))?,
                        );
                        pipeline.acquisition_us = Some(elapsed_micros(pipeline.started));
                        pipeline.watchdog.finish();
                    }
                    Ok(())
                })();
                self.capture_read_wait_us = elapsed_micros(read_started);
                if read.is_err() {
                    pipeline.watchdog.finish();
                    bridge.abort_capture();
                    output.clear();
                    return if self.cancellation.is_cancelled() {
                        StatusCode::Cancelled
                    } else {
                        StatusCode::BackendFailure
                    };
                }
                if self.cancellation.is_cancelled() {
                    output.clear();
                    return StatusCode::Cancelled;
                }
                return StatusCode::Ok;
            }
            let Some(chunk) = capture.pixels.get(offset..end) else {
                return StatusCode::InvalidRequest;
            };
            output.extend_from_slice(prefix);
            output.extend_from_slice(chunk);
            StatusCode::Ok
        }

        fn release_capture(&mut self, payload: &[u8]) -> DispatchResponse {
            let id = read_u64(payload, 0);
            if self
                .capture
                .as_ref()
                .is_some_and(|capture| capture.id == id)
            {
                // Release is the ownership hand-off point. Until now the frame remains
                // addressable by CaptureChunk and must not be reused by another request.
                if let Some(capture) = self.capture.take() {
                    if let Some(pipeline) = capture.pipeline {
                        if let (Some(acquisition_us), Some(stages_us)) =
                            (pipeline.acquisition_us, capture.stages_us)
                        {
                            return DispatchResponse {
                                status: StatusCode::Ok,
                                payload: root_protocol::CapturePipelineCompletion {
                                    capture_id: id,
                                    pixel_bytes: pipeline.length as u64,
                                    acquisition_us,
                                    stages_us,
                                    transfer: root_protocol::CaptureTransferSummary {
                                        encode_us: 0,
                                        write_us: 0,
                                        packets: (pipeline.length as u64)
                                            .div_ceil(MAX_CAPTURE_CHUNK_BYTES as u64),
                                    },
                                }
                                .encode()
                                .to_vec(),
                            };
                        }
                        pipeline.watchdog.finish();
                        if let Some(bridge) = self.capture_bridge.as_mut() {
                            bridge.abort_capture();
                        }
                        return empty_response(StatusCode::BackendFailure);
                    }
                    self.reusable_capture_pixels = capture.pixels;
                }
                DispatchResponse::success()
            } else {
                empty_response(StatusCode::InvalidRequest)
            }
        }
    }

    impl Drop for AndroidDispatcher {
        fn drop(&mut self) {
            self.recording.cancel_all();
            if let Some((_, x, y)) = self.active_pointer.take() {
                self.cancellation = InputCancellation::default();
                let _ = self.inject_pointer(1, x, y);
            }
        }
    }

    use root_daemon::capture_watchdog::{CaptureWatchdog, CaptureWatchdogLease};

    struct RootInputProcess {
        apk: PathBuf,
        child: Child,
        input: Option<ChildStdin>,
        output: ChildStdout,
        capture_watchdog: Option<CaptureWatchdog>,
        failed: bool,
    }

    impl RootInputProcess {
        fn abort_capture(&mut self) {
            if let Some(watchdog) = self.capture_watchdog.as_ref() {
                watchdog.finish_active();
            }
            self.failed = true;
            self.input.take();
            let _ = self.child.kill();
            let _ = self.child.wait();
        }

        fn begin_capture_pipeline(
            &mut self,
            id: u64,
            cancellation: InputCancellation,
            started: Instant,
        ) -> io::Result<StagedCapture> {
            if self.failed || cancellation.is_cancelled() {
                return Err(io::Error::other("capture bridge unavailable"));
            }
            let pid = i32::try_from(self.child.id())
                .map_err(|_| io::Error::other("invalid capture PID"))?;
            let deadline = started + CAPTURE_TIMEOUT;
            let watchdog = self
                .capture_watchdog
                .as_ref()
                .ok_or_else(|| io::Error::other("capture watchdog unavailable"))?
                .arm(pid, cancellation, deadline)?;
            let result = (|| -> io::Result<_> {
                let input = self
                    .input
                    .as_mut()
                    .ok_or_else(|| io::Error::other("capture pipe closed"))?;
                input.write_all(&2_u32.to_be_bytes())?;
                input.flush()?;
                let mut status = [0; 4];
                self.read_bounded(
                    &mut status,
                    deadline.saturating_duration_since(Instant::now()),
                )?;
                if u32::from_be_bytes(status) != 0 {
                    return Err(io::Error::other("capture rejected"));
                }
                let mut header = [0; 20];
                self.read_bounded(
                    &mut header,
                    deadline.saturating_duration_since(Instant::now()),
                )?;
                let geometry = root_daemon::decode_capture_bridge_header(&header)
                    .ok_or_else(|| io::Error::other("invalid capture header"))?;
                Ok(geometry)
            })();
            match result {
                Ok([width, height, row_stride, length]) => Ok(StagedCapture {
                    region: None,
                    id,
                    width,
                    height,
                    row_stride,
                    format: 2,
                    pixels: Vec::new(),
                    stages_us: None,
                    pipeline: Some(BridgeCapturePipeline {
                        length: length as usize,
                        received: 0,
                        started,
                        receive_started: Instant::now(),
                        deadline,
                        watchdog,
                        acquisition_us: None,
                    }),
                }),
                Err(error) => {
                    watchdog.finish();
                    self.abort_capture();
                    Err(error)
                }
            }
        }

        fn start(apk: &Path) -> io::Result<Self> {
            Self::start_entry(
                apk,
                "com.autoscript.runtime.service.RootInputBridge",
                0x41534932,
                false,
            )
        }

        fn start_capture(apk: &Path) -> io::Result<Self> {
            Self::start_entry(
                apk,
                "com.autoscript.runtime.service.RootCaptureBridge",
                0x41534331,
                true,
            )
        }

        fn start_entry(
            apk: &Path,
            entry: &str,
            magic: u32,
            capture_watchdog: bool,
        ) -> io::Result<Self> {
            let name = apk
                .to_str()
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid APK path"))?;
            if !apk.is_absolute()
                || !name.starts_with("/data/app/")
                || apk.extension().is_none_or(|value| value != "apk")
                || !fs::symlink_metadata(apk)?.is_file()
                || fs::metadata(apk)?.permissions().mode() & 0o002 != 0
            {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "bridge requires an installed APK",
                ));
            }
            // Allocate the persistent watchdog before spawning the bridge so a worker-start
            // failure cannot leave an unowned app_process child behind.
            let capture_watchdog = capture_watchdog
                .then(|| {
                    CaptureWatchdog::new(|pid| {
                        // SAFETY: the lease completion gate finishes before this owned
                        // child can be reaped, so its PID cannot yet be reused.
                        unsafe {
                            libc::kill(pid, libc::SIGKILL);
                        }
                    })
                })
                .transpose()?;
            let mut child = ProcessCommand::new("/system/bin/app_process")
                .env("CLASSPATH", apk)
                .arg("/system/bin")
                .arg(entry)
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::null())
                .spawn()?;
            let input = child
                .stdin
                .take()
                .ok_or_else(|| io::Error::other("bridge stdin unavailable"))?;
            let output = child
                .stdout
                .take()
                .ok_or_else(|| io::Error::other("bridge stdout unavailable"))?;
            let mut bridge = Self {
                apk: apk.to_path_buf(),
                child,
                input: Some(input),
                output,
                capture_watchdog,
                failed: false,
            };
            let mut hello = [0; 4];
            bridge.read_bounded(&mut hello, Duration::from_secs(5))?;
            if u32::from_be_bytes(hello) != magic {
                return Err(io::Error::other("invalid bridge handshake"));
            }
            Ok(bridge)
        }

        fn capture(
            &mut self,
            id: u64,
            cancellation: InputCancellation,
            reusable_pixels: &mut Vec<u8>,
            region: Option<root_protocol::capture_region::CaptureRegionRequest>,
        ) -> io::Result<StagedCapture> {
            if self.failed || cancellation.is_cancelled() {
                return Err(io::Error::other("capture bridge unavailable"));
            }
            let pid = i32::try_from(self.child.id())
                .map_err(|_| io::Error::other("invalid capture PID"))?;
            let deadline = Instant::now() + CAPTURE_TIMEOUT;
            let watchdog = self
                .capture_watchdog
                .as_ref()
                .ok_or_else(|| io::Error::other("capture watchdog unavailable"))?
                .arm(pid, cancellation, deadline)?;
            let result = (|| {
                let input = self
                    .input
                    .as_mut()
                    .ok_or_else(|| io::Error::other("capture pipe closed"))?;
                input.write_all(&(if region.is_some() { 3_u32 } else { 2 }).to_be_bytes())?;
                if let Some(region) = region {
                    for word in region.encode().chunks_exact(4) {
                        input.write_all(
                            &u32::from_le_bytes(word.try_into().expect("fixed word")).to_be_bytes(),
                        )?;
                    }
                }
                input.flush()?;
                let mut status = [0; 4];
                self.read_bounded(&mut status, CAPTURE_TIMEOUT)?;
                if u32::from_be_bytes(status) != 0 {
                    return Err(io::Error::other("capture rejected"));
                }
                let mut header = [0; 20];
                self.read_bounded(&mut header, CAPTURE_TIMEOUT)?;
                let word = |offset| {
                    u32::from_be_bytes(header[offset..offset + 4].try_into().expect("fixed header"))
                };
                let (width, height, row_stride, format, length) =
                    (word(0), word(4), word(8), word(12), word(16));
                if width == 0
                    || height == 0
                    || width > 16_384
                    || height > 16_384
                    || format != 2
                    || row_stride != width * 4
                    || u64::from(row_stride) * u64::from(height) != u64::from(length)
                    || length as usize > MAX_CAPTURE_BYTES
                {
                    return Err(io::Error::other("invalid capture header"));
                }
                let geometry = if let Some(region) = region {
                    let mut bytes = [0; 16];
                    self.read_bounded(&mut bytes, CAPTURE_TIMEOUT)?;
                    let word = |offset| {
                        u32::from_be_bytes(
                            bytes[offset..offset + 4].try_into().expect("fixed word"),
                        )
                    };
                    if word(0) != region.screen_width
                        || word(4) != region.screen_height
                        || word(8) > 3
                        || word(12) != 1
                        || width != region.right - region.left
                        || height != region.bottom - region.top
                    {
                        return Err(io::Error::other("invalid region geometry"));
                    }
                    Some(root_protocol::capture_region::CaptureRegionMetadata {
                        request: region,
                        rotation: Some(word(8)),
                        regional_readback: true,
                    })
                } else {
                    None
                };
                // Keep the allocation owned by the dispatcher between captures. The bytes are
                // only moved out after the complete bounded read succeeds.
                reusable_pixels.resize(length as usize, 0);
                let receive_started = Instant::now();
                self.read_bounded(reusable_pixels, CAPTURE_TIMEOUT)?;
                let mut trailer = [0; 40];
                self.read_bounded(&mut trailer, CAPTURE_TIMEOUT)?;
                let stages_us = root_daemon::decode_capture_stage_trailer(
                    &trailer,
                    u64::try_from(receive_started.elapsed().as_micros()).unwrap_or(u64::MAX),
                )
                .ok_or_else(|| io::Error::other("invalid capture timings"))?;
                let pixels = std::mem::take(reusable_pixels);
                Ok(StagedCapture {
                    region: geometry,
                    id,
                    width,
                    height,
                    row_stride,
                    format: 2,
                    pixels,
                    stages_us: Some(stages_us),
                    pipeline: None,
                })
            })();
            watchdog.finish();
            if result.is_err() {
                self.failed = true;
                self.input.take();
                let _ = self.child.kill();
                let _ = self.child.wait();
            }
            result
        }

        fn read_bounded(&mut self, output: &mut [u8], timeout: Duration) -> io::Result<()> {
            let deadline = Instant::now()
                .checked_add(timeout)
                .ok_or_else(|| io::Error::other("bridge deadline overflow"))?;
            let mut offset = 0;
            while offset < output.len() {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(io::Error::new(
                        io::ErrorKind::TimedOut,
                        "input bridge timed out",
                    ));
                }
                let mut fd = libc::pollfd {
                    fd: self.output.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                };
                // SAFETY: fd refers to the owned child pipe, and pollfd is valid for exactly one entry.
                let ready = unsafe {
                    libc::poll(
                        std::ptr::addr_of_mut!(fd),
                        1,
                        i32::try_from(remaining.as_millis())
                            .unwrap_or(i32::MAX)
                            .max(1),
                    )
                };
                if ready < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(error);
                }
                if ready == 0 {
                    continue;
                }
                let read = self.output.read(&mut output[offset..])?;
                if read == 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "input bridge exited",
                    ));
                }
                offset += read;
            }
            Ok(())
        }

        fn inject(&mut self, action: i32, x: i32, y: i32) -> io::Result<StatusCode> {
            let mut request = [0; 12];
            request[..4].copy_from_slice(&action.to_be_bytes());
            request[4..8].copy_from_slice(&x.to_be_bytes());
            request[8..].copy_from_slice(&y.to_be_bytes());
            self.input_request(&request)
        }
        fn cancel_pointers(&mut self, points: &[(u8, i32, i32)]) -> io::Result<StatusCode> {
            if points.is_empty() || points.len() > 5 {
                return Err(io::Error::other("invalid cancellation snapshot"));
            }
            let mut request = Vec::with_capacity(12 + points.len() * 12);
            request.extend_from_slice(&0x10004_i32.to_be_bytes());
            request.extend_from_slice(
                &i32::try_from(points.len())
                    .expect("five contacts")
                    .to_be_bytes(),
            );
            request.extend_from_slice(&0_i32.to_be_bytes());
            for &(id, x, y) in points {
                request.extend_from_slice(&i32::from(id).to_be_bytes());
                request.extend_from_slice(&x.to_be_bytes());
                request.extend_from_slice(&y.to_be_bytes());
            }
            self.input_request(&request)
        }
        fn input_request(&mut self, request: &[u8]) -> io::Result<StatusCode> {
            if self.failed {
                // A fresh bridge may send UP even without an acknowledged DOWN, to clear an
                // uncertain injection. The arbiter flushes this release before new business input.
                *self = Self::start(&self.apk)?;
            }
            let result = (|| {
                let input = self
                    .input
                    .as_mut()
                    .ok_or_else(|| io::Error::other("input bridge closed"))?;
                input.write_all(request)?;
                input.flush()?;
                let mut reply = [0; 4];
                self.read_bounded(&mut reply, Duration::from_secs(2))?;
                match i32::from_be_bytes(reply) {
                    0 => Ok(StatusCode::Ok),
                    1 => Ok(StatusCode::InvalidRequest),
                    3 => Ok(StatusCode::BackendFailure),
                    _ => Err(io::Error::other("invalid bridge response")),
                }
            })();
            if result.is_err() {
                self.failed = true;
                self.input.take();
                let _ = self.child.kill();
                let _ = self.child.wait();
            }
            result
        }
    }

    impl Drop for RootInputProcess {
        fn drop(&mut self) {
            // Stop the deadline worker before killing/reaping the child. This keeps the
            // watchdog's PID ownership fence valid even during bridge teardown.
            self.capture_watchdog.take();
            self.input.take();
            if !self.failed {
                // EOF lets the bridge release its pointer; bound exit even if Android Binder hangs.
                let mut byte = [0];
                let _ = self.read_bounded(&mut byte, Duration::from_millis(500));
                let _ = self.child.kill();
                let _ = self.child.wait();
            }
        }
    }

    // The watchdog must finish before the child is reaped: an owned zombie pins its PID.
    struct BoundedChild {
        process: Child,
        finished: Arc<AtomicBool>,
        timed_out: Arc<AtomicBool>,
        watchdog: Option<std::thread::JoinHandle<()>>,
        reaped: bool,
    }

    impl BoundedChild {
        fn spawn_cancellable(
            command: &mut ProcessCommand,
            timeout: Duration,
            cancellation: InputCancellation,
        ) -> io::Result<Self> {
            let mut process = command.spawn()?;
            let pid =
                i32::try_from(process.id()).map_err(|_| io::Error::other("invalid child PID"))?;
            let finished = Arc::new(AtomicBool::new(false));
            let timed_out = Arc::new(AtomicBool::new(false));
            let watchdog_finished = Arc::clone(&finished);
            let watchdog_timeout = Arc::clone(&timed_out);
            let deadline = Instant::now() + timeout;
            let watchdog = match std::thread::Builder::new()
                .name("root-child-deadline".into())
                .spawn(move || {
                    cancellation.register_watchdog(std::thread::current());
                    loop {
                        if watchdog_finished.load(Ordering::Acquire) {
                            return;
                        }
                        let now = Instant::now();
                        if now >= deadline || cancellation.is_cancelled() {
                            break;
                        }
                        std::thread::park_timeout(deadline - now);
                    }
                    if !watchdog_finished.load(Ordering::Acquire) {
                        watchdog_timeout.store(!cancellation.is_cancelled(), Ordering::Release);
                        // SAFETY: this nonzero PID belongs to our unreaped child. No other code reaps
                        // it before finish_watchdog joins this thread, including the timeout path.
                        unsafe {
                            libc::kill(pid, libc::SIGKILL);
                        }
                    }
                }) {
                Ok(thread) => thread,
                Err(error) => {
                    let _ = process.kill();
                    let _ = process.wait();
                    return Err(error);
                }
            };
            Ok(Self {
                process,
                finished,
                timed_out,
                watchdog: Some(watchdog),
                reaped: false,
            })
        }

        fn finish_watchdog(&mut self) {
            self.finished.store(true, Ordering::Release);
            if let Some(thread) = self.watchdog.take() {
                thread.thread().unpark();
                let _ = thread.join();
            }
        }

        fn wait(&mut self) -> io::Result<ExitStatus> {
            let mut info = std::mem::MaybeUninit::<libc::siginfo_t>::uninit();
            loop {
                // SAFETY: waitid writes siginfo_t and observes only our owned child. WNOWAIT is
                // essential: it prevents PID reuse until the watchdog is joined below.
                let result = unsafe {
                    libc::waitid(
                        libc::P_PID,
                        self.process.id(),
                        info.as_mut_ptr(),
                        libc::WEXITED | libc::WNOWAIT,
                    )
                };
                if result == 0 {
                    break;
                }
                let error = io::Error::last_os_error();
                if error.kind() != io::ErrorKind::Interrupted {
                    return Err(error);
                }
            }
            self.finish_watchdog();
            let result = self.process.wait();
            if result.is_ok() {
                self.reaped = true;
            }
            result
        }
    }

    impl Drop for BoundedChild {
        fn drop(&mut self) {
            self.finish_watchdog();
            if !self.reaped {
                let _ = self.process.kill();
                let _ = self.process.wait();
            }
        }
    }

    fn run_screencap(
        id: u64,
        cancellation: InputCancellation,
        timeout: Duration,
    ) -> Result<StagedCapture, CaptureFailure> {
        let mut child = BoundedChild::spawn_cancellable(
            ProcessCommand::new(SCREENCAP_BINARY)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null()),
            timeout,
            cancellation,
        )
        .map_err(|_| CaptureFailure::ProcessStart)?;
        let result = (|| {
            let stdout = child
                .process
                .stdout
                .take()
                .ok_or(CaptureFailure::PipeRead)?;
            let limit = u64::try_from(MAX_CAPTURE_BYTES + 17).expect("constant fits u64");
            let mut bytes = Vec::new();
            stdout
                .take(limit)
                .read_to_end(&mut bytes)
                .map_err(|_| CaptureFailure::PipeRead)?;
            if !child
                .wait()
                .map_err(|_| CaptureFailure::ProcessExit)?
                .success()
            {
                return Err(CaptureFailure::ProcessExit);
            }
            if bytes.len() > MAX_CAPTURE_BYTES + 16 {
                return Err(CaptureFailure::InvalidFrame);
            }
            let width = read_u32_checked(&bytes, 0).map_err(|()| CaptureFailure::InvalidFrame)?;
            let height = read_u32_checked(&bytes, 4).map_err(|()| CaptureFailure::InvalidFrame)?;
            let android_format =
                read_u32_checked(&bytes, 8).map_err(|()| CaptureFailure::InvalidFrame)?;
            if width == 0 || height == 0 {
                return Err(CaptureFailure::InvalidFrame);
            }
            let row_stride = width.checked_mul(4).ok_or(CaptureFailure::InvalidFrame)?;
            let pixel_bytes = usize::try_from(row_stride)
                .ok()
                .and_then(|stride| {
                    usize::try_from(height)
                        .ok()
                        .and_then(|height| stride.checked_mul(height))
                })
                .ok_or(CaptureFailure::InvalidFrame)?;
            if pixel_bytes > MAX_CAPTURE_BYTES {
                return Err(CaptureFailure::InvalidFrame);
            }
            let header_bytes = if bytes.len() == pixel_bytes + 16 {
                16
            } else if bytes.len() == pixel_bytes + 12 {
                12
            } else {
                return Err(CaptureFailure::InvalidFrame);
            };
            let format = match android_format {
                1 | 2 => 1,
                5 => 2,
                _ => return Err(CaptureFailure::InvalidFrame),
            };
            let pixels = bytes.split_off(header_bytes);
            Ok(StagedCapture {
                region: None,
                id,
                width,
                height,
                row_stride,
                format,
                pixels,
                pipeline: None,
                stages_us: None,
            })
        })();
        result
    }

    fn empty_response(status: StatusCode) -> DispatchResponse {
        DispatchResponse {
            status,
            payload: Vec::new(),
        }
    }

    fn capture_failure_response(reason: CaptureFailure) -> DispatchResponse {
        DispatchResponse {
            status: StatusCode::BackendFailure,
            payload: (reason as u32).to_le_bytes().to_vec(),
        }
    }

    fn run_input(arguments: &[String], cancellation: &InputCancellation) -> StatusCode {
        run_input_once(arguments, cancellation).0
    }

    fn run_input_once(
        arguments: &[String],
        cancellation: &InputCancellation,
    ) -> (StatusCode, bool, Option<InputFailure>) {
        if cancellation.is_cancelled() {
            return (StatusCode::Cancelled, false, None);
        }
        let timeout = if arguments.first().is_some_and(|value| value == "swipe") {
            Duration::from_millis(
                arguments
                    .last()
                    .and_then(|value| value.parse::<u64>().ok())
                    .unwrap_or(0)
                    .min(60_000)
                    + 2_000,
            )
        } else {
            Duration::from_secs(2)
        };
        let mut child = match BoundedChild::spawn_cancellable(
            ProcessCommand::new(INPUT_BINARY)
                .args(arguments)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null()),
            timeout,
            cancellation.clone(),
        ) {
            Ok(child) => child,
            Err(_) => {
                return (
                    StatusCode::BackendUnavailable,
                    false,
                    Some(InputFailure::ProcessStart),
                )
            }
        };
        let result = child.wait();
        if cancellation.is_cancelled() {
            return (StatusCode::Cancelled, false, None);
        }
        let timed_out = child.timed_out.load(Ordering::Acquire);
        match result {
            Ok(status) if status.success() && !timed_out => (StatusCode::Ok, false, None),
            Ok(_) => (
                StatusCode::BackendFailure,
                !timed_out,
                Some(if timed_out {
                    InputFailure::Deadline
                } else {
                    InputFailure::ProcessExit
                }),
            ),
            Err(_) => (
                StatusCode::BackendUnavailable,
                false,
                Some(InputFailure::ProcessExit),
            ),
        }
    }

    fn run_input_retryable(arguments: &[String], cancellation: &InputCancellation) -> StatusCode {
        let (mut status, mut retryable, _) = run_input_once(arguments, cancellation);
        for delay in INPUT_RETRY_DELAYS {
            if !retryable {
                break;
            }
            std::thread::sleep(delay);
            (status, retryable, _) = run_input_once(arguments, cancellation);
        }
        status
    }

    fn run_pointer(action: &str, x: i32, y: i32, cancellation: &InputCancellation) -> StatusCode {
        run_input(
            &[
                "touchscreen".to_owned(),
                "motionevent".to_owned(),
                action.to_owned(),
                x.to_string(),
                y.to_string(),
            ],
            cancellation,
        )
    }

    fn supports_pointer_commands() -> bool {
        ProcessCommand::new(GETPROP_BINARY)
            .arg("ro.build.version.sdk")
            .output()
            .ok()
            .filter(|output| output.status.success())
            .and_then(|output| String::from_utf8(output.stdout).ok())
            .and_then(|sdk| sdk.trim().parse::<u32>().ok())
            .is_some_and(|sdk| sdk >= 26)
    }

    fn parse_pointer_position(payload: &[u8]) -> Option<(u8, i32, i32)> {
        let pointer_id = parse_pointer_id(payload)?;
        Some((pointer_id, read_i32(payload, 4), read_i32(payload, 8)))
    }

    fn parse_pointer_id(payload: &[u8]) -> Option<u8> {
        let pointer_id = *payload.first()?;
        (pointer_id <= 4 && payload.get(1..4) == Some(&[0, 0, 0])).then_some(pointer_id)
    }

    fn read_i32(bytes: &[u8], offset: usize) -> i32 {
        i32::from_le_bytes(
            bytes[offset..offset + 4]
                .try_into()
                .expect("validated payload"),
        )
    }

    fn read_u32(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(
            bytes[offset..offset + 4]
                .try_into()
                .expect("validated payload"),
        )
    }

    fn read_u64(bytes: &[u8], offset: usize) -> u64 {
        u64::from_le_bytes(
            bytes[offset..offset + 8]
                .try_into()
                .expect("validated payload"),
        )
    }

    fn read_u32_checked(bytes: &[u8], offset: usize) -> Result<u32, ()> {
        bytes
            .get(offset..offset + 4)
            .and_then(|value| value.try_into().ok())
            .map(u32::from_le_bytes)
            .ok_or(())
    }

    fn io_error(error: io::Error) -> String {
        error.to_string()
    }
}

#[cfg(target_os = "android")]
fn main() {
    if let Err(error) = android::run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

#[cfg(not(target_os = "android"))]
fn main() {
    eprintln!("root-daemon is only executable on Android");
}
