#[cfg(target_os = "android")]
mod android {
    use std::ffi::CString;
    use std::fs;
    use std::io::{self, Read};
    use std::os::fd::AsRawFd;
    use std::os::unix::fs::{FileTypeExt, PermissionsExt};
    use std::os::unix::net::{UnixListener, UnixStream};
    use std::path::{Path, PathBuf};
    use std::process::{Command as ProcessCommand, Stdio};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;
    use std::time::{Duration, Instant};

    use root_daemon::{read_packet, write_packet};
    use root_daemon_core::{
        CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
        SessionState,
    };
    use root_protocol::{Capabilities, Command, Frame, StatusCode};

    const INPUT_BINARY: &str = "/system/bin/input";
    const SCREENCAP_BINARY: &str = "/system/bin/screencap";
    const MAX_CAPTURE_BYTES: usize = 64 * 1024 * 1024;
    const MAX_CAPTURE_CHUNK_BYTES: usize = 60 * 1024;
    const CAPTURE_TIMEOUT: Duration = Duration::from_secs(5);

    pub fn run() -> Result<(), String> {
        let arguments = Arguments::parse()?;
        prepare_socket_path(&arguments.socket)?;
        prepare_ready_path(&arguments.ready_file, &arguments.socket)?;
        let key = read_session_key(&arguments.key_file)?;
        let listener = UnixListener::bind(&arguments.socket).map_err(io_error)?;
        let endpoint_guard =
            EndpointGuard::new(arguments.socket.clone(), arguments.ready_file.clone());
        set_socket_owner(&arguments.socket, arguments.expected_uid)?;
        fs::set_permissions(&arguments.socket, fs::Permissions::from_mode(0o600))
            .map_err(io_error)?;
        fs::write(&arguments.ready_file, []).map_err(io_error)?;
        let (mut stream, _) = listener.accept().map_err(io_error)?;
        fs::remove_file(&arguments.ready_file).map_err(io_error)?;
        stream
            .set_read_timeout(Some(arguments.idle_timeout))
            .map_err(io_error)?;
        let peer_uid = peer_uid(&stream).map_err(io_error)?;
        let started = Instant::now();
        let mut session = DaemonSession::new(
            key,
            peer_uid,
            DaemonSessionConfig {
                expected_runner_uid: arguments.expected_uid,
                server_capabilities: Capabilities::from_bits(
                    Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW,
                ),
                required_client_capabilities: 0,
                idle_timeout_ms: u64::try_from(arguments.idle_timeout.as_millis())
                    .map_err(|error| error.to_string())?,
                now_ms: 0,
            },
            AndroidDispatcher::default(),
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
    }

    impl Arguments {
        fn parse() -> Result<Self, String> {
            let values = std::env::args().skip(1).collect::<Vec<_>>();
            if values.len() != 5 {
                return Err(
                    "usage: root-daemon <socket-path> <runner-uid> <key-file> <ready-file> <idle-ms>"
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
    }

    impl EndpointGuard {
        const fn new(socket: PathBuf, ready_file: PathBuf) -> Self {
            Self { socket, ready_file }
        }
    }

    impl Drop for EndpointGuard {
        fn drop(&mut self) {
            let _ = fs::remove_file(&self.ready_file);
            let _ = fs::remove_file(&self.socket);
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
                    run_input(&["tap".to_owned(), x.to_string(), y.to_string()])
                }
                Command::Swipe => {
                    let duration = read_u32(&frame.payload, 16);
                    run_input(&[
                        "swipe".to_owned(),
                        read_i32(&frame.payload, 0).to_string(),
                        read_i32(&frame.payload, 4).to_string(),
                        read_i32(&frame.payload, 8).to_string(),
                        read_i32(&frame.payload, 12).to_string(),
                        duration.to_string(),
                    ])
                }
                Command::KeyEvent => run_input(&[
                    "keyevent".to_owned(),
                    read_u32(&frame.payload, 0).to_string(),
                ]),
                Command::GetWindowBounds => StatusCode::BackendUnavailable,
                Command::Cancel => StatusCode::Ok,
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

    fn run_screencap(id: u64) -> Result<StagedCapture, ()> {
        let mut child = ProcessCommand::new(SCREENCAP_BINARY)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| ())?;
        let finished = Arc::new(AtomicBool::new(false));
        let watchdog_finished = Arc::clone(&finished);
        let child_pid = child.id();
        let watchdog = std::thread::spawn(move || {
            std::thread::park_timeout(CAPTURE_TIMEOUT);
            if !watchdog_finished.load(Ordering::Acquire) {
                // SAFETY: a non-zero PID came directly from the still-owned child. Until it is
                // waited, an exited child remains a zombie and its PID cannot be reused.
                unsafe {
                    libc::kill(i32::try_from(child_pid).unwrap_or(i32::MAX), libc::SIGKILL);
                }
            }
        });
        let result = (|| {
            let stdout = child.stdout.take().ok_or(())?;
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
        finished.store(true, Ordering::Release);
        watchdog.thread().unpark();
        let _ = watchdog.join();
        result
    }

    fn empty_response(status: StatusCode) -> DispatchResponse {
        DispatchResponse {
            status,
            payload: Vec::new(),
        }
    }

    fn run_input(arguments: &[String]) -> StatusCode {
        match ProcessCommand::new(INPUT_BINARY).args(arguments).status() {
            Ok(status) if status.success() => StatusCode::Ok,
            Ok(_) => StatusCode::BackendFailure,
            Err(_) => StatusCode::BackendUnavailable,
        }
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
