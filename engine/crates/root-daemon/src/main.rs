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

    use root_daemon::{read_packet, write_packet};
    use root_daemon_core::priority::{InputCancellation, InputFence};
    use root_daemon_core::{
        CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
        SessionState,
    };
    use root_protocol::{Capabilities, Command, Frame, StatusCode};

    const INPUT_BINARY: &str = "/system/bin/input";
    const GETPROP_BINARY: &str = "/system/bin/getprop";
    const SCREENCAP_BINARY: &str = "/system/bin/screencap";
    const MAX_CAPTURE_BYTES: usize = 64 * 1024 * 1024;
    const MAX_CAPTURE_CHUNK_BYTES: usize = 60 * 1024;
    const CAPTURE_TIMEOUT: Duration = Duration::from_secs(5);
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
        let started = Instant::now();
        let input_bridge = arguments
            .apk
            .as_deref()
            .and_then(|apk| RootInputProcess::start(apk).ok());
        let mut capability_bits = Capabilities::INPUT_BASIC
            | Capabilities::CAPTURE_RAW
            | Capabilities::INPUT_PRIORITY_STOP;
        if input_bridge.is_some() || supports_pointer_commands() {
            capability_bits |= Capabilities::INPUT_POINTER_SINGLE;
        }
        let dispatcher = Arc::new(Mutex::new(AndroidDispatcher {
            input_bridge,
            next_capture_id: 0,
            capture: None,
            active_pointer: None,
            pointer_owner: 0,
            cancellation: InputCancellation::default(),
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
            arguments.idle_timeout,
            dispatcher.clone(),
            fence.clone(),
        )?;
        let mut session = DaemonSession::new(
            key,
            peer_uid,
            DaemonSessionConfig {
                expected_runner_uid: arguments.expected_uid,
                server_capabilities: Capabilities::from_bits(capability_bits),
                required_client_capabilities: 0,
                idle_timeout_ms: u64::try_from(arguments.idle_timeout.as_millis())
                    .map_err(|error| error.to_string())?,
                now_ms: 0,
            },
            SharedDispatcher { dispatcher, fence },
        )
        .map_err(|error| format!("daemon handshake initialization failed: {error:?}"))?;
        while session.state() != SessionState::Closed {
            let packet = match read_packet(&mut stream) {
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
            let now_ms =
                u64::try_from(started.elapsed().as_millis()).map_err(|error| error.to_string())?;
            let response = session
                .receive(&packet, now_ms)
                .map_err(|error| format!("daemon protocol failed: {error:?}"))?;
            write_packet(&mut stream, &response)
                .map_err(|error| format!("daemon response failed: {error:?}"))?;
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
        next_capture_id: u64,
        capture: Option<StagedCapture>,
        active_pointer: Option<(u8, i32, i32)>,
        input_bridge: Option<RootInputProcess>,
        pointer_owner: u64,
        cancellation: InputCancellation,
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
        )
    }
    impl CommandDispatcher for SharedDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            let mut dispatcher = self.dispatcher.lock().map_err(|_| DispatchError)?;
            if !input_command(frame.command) {
                return dispatcher.dispatch(frame);
            }
            let Some(token) = self.fence.begin(frame.request_id) else {
                return Ok(empty_response(if self.fence.is_failed() {
                    StatusCode::BackendFailure
                } else {
                    StatusCode::Cancelled
                }));
            };
            dispatcher.cancellation = token.clone();
            dispatcher.pointer_owner = frame.request_id;
            let result = dispatcher.dispatch(frame);
            self.fence.finish(frame.request_id);
            if token.is_cancelled() {
                Ok(empty_response(StatusCode::Cancelled))
            } else {
                result
            }
        }
    }
    struct StopDispatcher {
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
    }
    impl CommandDispatcher for StopDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            if frame.command != Command::Cancel {
                return Ok(empty_response(StatusCode::InvalidRequest));
            }
            let cutoff = read_u64(&frame.payload, 0);
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
        idle: Duration,
        dispatcher: Arc<Mutex<AndroidDispatcher>>,
        fence: Arc<InputFence>,
    ) -> Result<(), String> {
        std::thread::Builder::new()
            .name("root-priority-stop".into())
            .spawn(move || {
                let result = (|| -> Result<(), String> {
                    let (mut stream, _) = listener.accept().map_err(io_error)?;
                    stream.set_read_timeout(Some(idle)).map_err(io_error)?;
                    stream
                        .set_write_timeout(Some(Duration::from_secs(8)))
                        .map_err(io_error)?;
                    let mut session = DaemonSession::new(
                        key,
                        peer_uid(&stream).map_err(io_error)?,
                        DaemonSessionConfig {
                            expected_runner_uid: uid,
                            server_capabilities: Capabilities::from_bits(
                                Capabilities::INPUT_PRIORITY_STOP,
                            ),
                            required_client_capabilities: Capabilities::INPUT_PRIORITY_STOP,
                            idle_timeout_ms: u64::try_from(idle.as_millis())
                                .map_err(|e| e.to_string())?,
                            now_ms: 0,
                        },
                        StopDispatcher {
                            dispatcher,
                            fence: fence.clone(),
                        },
                    )
                    .map_err(|_| "stop handshake failed")?;
                    let clock = Instant::now();
                    loop {
                        let request =
                            read_packet(&mut stream).map_err(|_| "stop transport failed")?;
                        let reply = session
                            .receive(
                                &request,
                                u64::try_from(clock.elapsed().as_millis()).unwrap_or(u64::MAX),
                            )
                            .map_err(|_| "stop protocol failed")?;
                        write_packet(&mut stream, &reply).map_err(|_| "stop response failed")?;
                    }
                })();
                if result.is_err() {
                    fence.fail_closed();
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    struct StagedCapture {
        id: u64,
        width: u32,
        height: u32,
        row_stride: u32,
        format: u8,
        pixels: Vec<u8>,
    }

    impl CommandDispatcher for AndroidDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            match frame.command {
                Command::Capture => return Ok(self.capture()),
                Command::CaptureChunk => return Ok(self.capture_chunk(&frame.payload)),
                Command::ReleaseCapture => return Ok(self.release_capture(&frame.payload)),
                _ => {}
            }
            let response = match frame.command {
                Command::Tap => {
                    let x = read_i32(&frame.payload, 0);
                    let y = read_i32(&frame.payload, 4);
                    self.active_pointer = Some((0, x, y));
                    let status = run_input_once(
                        &["tap".to_owned(), x.to_string(), y.to_string()],
                        &self.cancellation,
                    )
                    .0;
                    if status == StatusCode::Ok {
                        self.active_pointer = None;
                    }
                    status
                }
                Command::Swipe => {
                    let duration = read_u32(&frame.payload, 16);
                    self.active_pointer =
                        Some((0, read_i32(&frame.payload, 8), read_i32(&frame.payload, 12)));
                    let status = run_input_once(
                        &[
                            "swipe".to_owned(),
                            read_i32(&frame.payload, 0).to_string(),
                            read_i32(&frame.payload, 4).to_string(),
                            read_i32(&frame.payload, 8).to_string(),
                            read_i32(&frame.payload, 12).to_string(),
                            duration.to_string(),
                        ],
                        &self.cancellation,
                    )
                    .0;
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
                | Command::CaptureChunk
                | Command::ReleaseCapture => StatusCode::InvalidRequest,
            };
            Ok(DispatchResponse {
                status: response,
                payload: Vec::new(),
            })
        }
    }

    impl AndroidDispatcher {
        fn pointer_down(&mut self, payload: &[u8]) -> StatusCode {
            let Some((pointer_id, x, y)) = parse_pointer_position(payload) else {
                return StatusCode::InvalidRequest;
            };
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
            if let Some(bridge) = self.input_bridge.as_mut() {
                return bridge
                    .inject(action, x, y)
                    .unwrap_or(StatusCode::BackendUnavailable);
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

        fn capture(&mut self) -> DispatchResponse {
            let id = self.next_capture_id.checked_add(1);
            let Some(id) = id else {
                return empty_response(StatusCode::BackendFailure);
            };
            let Ok(mut capture) = run_screencap(id) else {
                return empty_response(StatusCode::BackendFailure);
            };
            self.next_capture_id = id;
            let byte_length = u64::try_from(capture.pixels.len()).expect("capture is bounded");
            let mut payload = Vec::with_capacity(32);
            payload.extend_from_slice(&capture.id.to_le_bytes());
            payload.extend_from_slice(&capture.width.to_le_bytes());
            payload.extend_from_slice(&capture.height.to_le_bytes());
            payload.extend_from_slice(&capture.row_stride.to_le_bytes());
            payload.push(capture.format);
            payload.extend_from_slice(&[0, 0, 0]);
            payload.extend_from_slice(&byte_length.to_le_bytes());
            capture.pixels.shrink_to_fit();
            self.capture = Some(capture);
            DispatchResponse {
                status: StatusCode::Ok,
                payload,
            }
        }

        fn capture_chunk(&self, payload: &[u8]) -> DispatchResponse {
            let id = read_u64(payload, 0);
            let offset = usize::try_from(read_u32(payload, 8)).unwrap_or(usize::MAX);
            let length = usize::try_from(read_u32(payload, 12)).unwrap_or(usize::MAX);
            let Some(capture) = self.capture.as_ref().filter(|capture| capture.id == id) else {
                return empty_response(StatusCode::InvalidRequest);
            };
            if length == 0 || length > MAX_CAPTURE_CHUNK_BYTES {
                return empty_response(StatusCode::InvalidRequest);
            }
            let Some(end) = offset.checked_add(length) else {
                return empty_response(StatusCode::InvalidRequest);
            };
            let Some(chunk) = capture.pixels.get(offset..end) else {
                return empty_response(StatusCode::InvalidRequest);
            };
            DispatchResponse {
                status: StatusCode::Ok,
                payload: chunk.to_vec(),
            }
        }

        fn release_capture(&mut self, payload: &[u8]) -> DispatchResponse {
            let id = read_u64(payload, 0);
            if self
                .capture
                .as_ref()
                .is_some_and(|capture| capture.id == id)
            {
                self.capture = None;
                DispatchResponse::success()
            } else {
                empty_response(StatusCode::InvalidRequest)
            }
        }
    }

    impl Drop for AndroidDispatcher {
        fn drop(&mut self) {
            if let Some((_, x, y)) = self.active_pointer.take() {
                self.cancellation = InputCancellation::default();
                let _ = self.inject_pointer(1, x, y);
            }
        }
    }

    struct RootInputProcess {
        apk: PathBuf,
        child: Child,
        input: Option<ChildStdin>,
        output: ChildStdout,
        failed: bool,
    }

    impl RootInputProcess {
        fn start(apk: &Path) -> io::Result<Self> {
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
            let mut child = ProcessCommand::new("/system/bin/app_process")
                .env("CLASSPATH", apk)
                .arg("/system/bin")
                .arg("com.autoscript.runtime.service.RootInputBridge")
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
                failed: false,
            };
            let mut hello = [0; 4];
            bridge.read_bounded(&mut hello, Duration::from_secs(5))?;
            if u32::from_be_bytes(hello) != 0x41534931 {
                return Err(io::Error::other("invalid bridge handshake"));
            }
            Ok(bridge)
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
                let mut request = [0; 12];
                request[..4].copy_from_slice(&action.to_be_bytes());
                request[4..8].copy_from_slice(&x.to_be_bytes());
                request[8..].copy_from_slice(&y.to_be_bytes());
                input.write_all(&request)?;
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
        fn spawn(command: &mut ProcessCommand, timeout: Duration) -> io::Result<Self> {
            Self::spawn_cancellable(command, timeout, InputCancellation::default())
        }
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

    fn run_screencap(id: u64) -> Result<StagedCapture, ()> {
        let mut child = BoundedChild::spawn(
            ProcessCommand::new(SCREENCAP_BINARY)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::null()),
            CAPTURE_TIMEOUT,
        )
        .map_err(|_| ())?;
        let result = (|| {
            let stdout = child.process.stdout.take().ok_or(())?;
            let limit = u64::try_from(MAX_CAPTURE_BYTES + 17).expect("constant fits u64");
            let mut bytes = Vec::new();
            stdout.take(limit).read_to_end(&mut bytes).map_err(|_| ())?;
            if !child.wait().map_err(|_| ())?.success() || bytes.len() > MAX_CAPTURE_BYTES + 16 {
                return Err(());
            }
            let width = read_u32_checked(&bytes, 0)?;
            let height = read_u32_checked(&bytes, 4)?;
            let android_format = read_u32_checked(&bytes, 8)?;
            let row_stride = width.checked_mul(4).ok_or(())?;
            let pixel_bytes = usize::try_from(row_stride)
                .ok()
                .and_then(|stride| {
                    usize::try_from(height)
                        .ok()
                        .and_then(|height| stride.checked_mul(height))
                })
                .ok_or(())?;
            if pixel_bytes > MAX_CAPTURE_BYTES {
                return Err(());
            }
            let header_bytes = if bytes.len() == pixel_bytes + 16 {
                16
            } else if bytes.len() == pixel_bytes + 12 {
                12
            } else {
                return Err(());
            };
            let format = match android_format {
                1 | 2 => 1,
                5 => 2,
                _ => return Err(()),
            };
            let pixels = bytes.split_off(header_bytes);
            Ok(StagedCapture {
                id,
                width,
                height,
                row_stride,
                format,
                pixels,
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

    fn run_input(arguments: &[String], cancellation: &InputCancellation) -> StatusCode {
        run_input_once(arguments, cancellation).0
    }

    fn run_input_once(
        arguments: &[String],
        cancellation: &InputCancellation,
    ) -> (StatusCode, bool) {
        if cancellation.is_cancelled() {
            return (StatusCode::Cancelled, false);
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
            Err(_) => return (StatusCode::BackendUnavailable, false),
        };
        let result = child.wait();
        if cancellation.is_cancelled() {
            return (StatusCode::Cancelled, false);
        }
        let timed_out = child.timed_out.load(Ordering::Acquire);
        match result {
            Ok(status) if status.success() && !timed_out => (StatusCode::Ok, false),
            Ok(_) => (StatusCode::BackendFailure, !timed_out),
            Err(_) => (StatusCode::BackendUnavailable, false),
        }
    }

    fn run_input_retryable(arguments: &[String], cancellation: &InputCancellation) -> StatusCode {
        let (mut status, mut retryable) = run_input_once(arguments, cancellation);
        for delay in INPUT_RETRY_DELAYS {
            if !retryable {
                break;
            }
            std::thread::sleep(delay);
            (status, retryable) = run_input_once(arguments, cancellation);
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
        (pointer_id == 0 && payload.get(1..4) == Some(&[0, 0, 0])).then_some(pointer_id)
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
