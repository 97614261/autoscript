//! Authenticated, request-correlated client for the privileged `RootDaemon`.

use root_daemon::TransportError;
use root_protocol::{Capabilities, Command, FrameKind, ProtocolError, SecureChannel, StatusCode};

const CAPTURE_CHUNK_BYTES: usize = 60 * 1024;
const MAX_CAPTURE_BYTES: usize = 64 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RawCaptureFormat {
    Rgba8888,
    Bgra8888,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawCapture {
    pub width: u32,
    pub height: u32,
    pub row_stride: u32,
    pub format: RawCaptureFormat,
    pub pixels: Vec<u8>,
}

pub trait PacketTransport {
    /// Sends one complete length-delimited protocol packet.
    ///
    /// # Errors
    ///
    /// Returns a bounded transport error without exposing a partial packet.
    fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError>;

    /// Receives one complete length-delimited protocol packet.
    ///
    /// # Errors
    ///
    /// Returns a bounded transport error without exposing a partial packet.
    fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError>;
}

#[cfg(target_os = "android")]
impl PacketTransport for std::os::unix::net::UnixStream {
    fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        root_daemon::write_packet(self, packet)
    }

    fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
        root_daemon::read_packet(self)
    }
}

#[derive(Debug)]
pub enum ClientError {
    Transport(TransportError),
    Protocol(ProtocolError),
    UnexpectedResponse,
    RequestIdExhausted,
    Remote(StatusCode),
    InvalidDuration,
    InvalidCapture,
    CaptureTooLarge,
    Closed,
}

impl ClientError {
    /// Reports whether request correlation can no longer safely continue on this connection.
    #[must_use]
    pub const fn is_connection_lost(&self) -> bool {
        matches!(
            self,
            Self::Transport(_)
                | Self::Protocol(_)
                | Self::UnexpectedResponse
                | Self::RequestIdExhausted
                | Self::Closed
        )
    }
}

impl From<TransportError> for ClientError {
    fn from(value: TransportError) -> Self {
        Self::Transport(value)
    }
}

impl From<ProtocolError> for ClientError {
    fn from(value: ProtocolError) -> Self {
        Self::Protocol(value)
    }
}

pub struct RootClient<T> {
    transport: T,
    channel: SecureChannel,
    next_request_id: u64,
    negotiated_capabilities: Capabilities,
    closed: bool,
}

impl<T: PacketTransport> RootClient<T> {
    /// Connects a transport by completing the authenticated capability handshake.
    ///
    /// # Errors
    ///
    /// Returns a transport, authentication, correlation, or remote-status error.
    pub fn connect(
        transport: T,
        key: [u8; 32],
        requested_capabilities: Capabilities,
    ) -> Result<Self, ClientError> {
        let mut client = Self {
            transport,
            channel: SecureChannel::new(key)?,
            next_request_id: 1,
            negotiated_capabilities: Capabilities::from_bits(0),
            closed: false,
        };
        let payload =
            client.transact(Command::Hello, &requested_capabilities.bits().to_le_bytes())?;
        let bits = u64::from_le_bytes(
            payload
                .as_slice()
                .try_into()
                .map_err(|_| ClientError::UnexpectedResponse)?,
        );
        let negotiated = Capabilities::from_bits(bits);
        if !negotiated.supports(requested_capabilities.bits()) {
            return Err(ClientError::UnexpectedResponse);
        }
        client.negotiated_capabilities = negotiated;
        Ok(client)
    }

    #[must_use]
    pub const fn negotiated_capabilities(&self) -> Capabilities {
        self.negotiated_capabilities
    }

    /// Injects a physical-pixel tap.
    ///
    /// # Errors
    ///
    /// Returns a transport, protocol, correlation, or backend error.
    pub fn tap(&mut self, x: i32, y: i32) -> Result<(), ClientError> {
        let mut payload = Vec::with_capacity(8);
        payload.extend_from_slice(&x.to_le_bytes());
        payload.extend_from_slice(&y.to_le_bytes());
        self.transact_empty(Command::Tap, &payload)
    }

    /// Injects a physical-pixel swipe with a bounded duration.
    ///
    /// # Errors
    ///
    /// Returns [`ClientError::InvalidDuration`] outside 1ms..60s, or a request error.
    pub fn swipe(
        &mut self,
        start: (i32, i32),
        end: (i32, i32),
        duration_ms: u32,
    ) -> Result<(), ClientError> {
        if !(1..=60_000).contains(&duration_ms) {
            return Err(ClientError::InvalidDuration);
        }
        let mut payload = Vec::with_capacity(20);
        for coordinate in [start.0, start.1, end.0, end.1] {
            payload.extend_from_slice(&coordinate.to_le_bytes());
        }
        payload.extend_from_slice(&duration_ms.to_le_bytes());
        self.transact_empty(Command::Swipe, &payload)
    }

    /// Injects one Android key code.
    ///
    /// # Errors
    ///
    /// Returns a transport, protocol, correlation, or backend error.
    pub fn key_event(&mut self, key_code: u32) -> Result<(), ClientError> {
        self.transact_empty(Command::KeyEvent, &key_code.to_le_bytes())
    }

    /// Verifies that the daemon is responsive.
    ///
    /// # Errors
    ///
    /// Returns a transport, protocol, correlation, or backend error.
    pub fn ping(&mut self) -> Result<(), ClientError> {
        self.transact_empty(Command::Ping, &[])
    }

    /// Cancels a previously issued request identifier when supported by its backend.
    ///
    /// # Errors
    ///
    /// Returns a transport, protocol, correlation, or backend error.
    pub fn cancel(&mut self, request_id: u64) -> Result<(), ClientError> {
        self.transact_empty(Command::Cancel, &request_id.to_le_bytes())
    }

    /// Captures one bounded raw frame and releases the daemon-side staging buffer.
    ///
    /// # Errors
    ///
    /// Returns a transport/protocol error, malformed metadata, or the 64MiB frame limit.
    pub fn capture(&mut self) -> Result<RawCapture, ClientError> {
        let metadata = self.transact(Command::Capture, &[])?;
        let capture_id = read_u64(&metadata, 0)?;
        let width = read_u32(&metadata, 8)?;
        let height = read_u32(&metadata, 12)?;
        let row_stride = read_u32(&metadata, 16)?;
        let format = match *metadata.get(20).ok_or(ClientError::InvalidCapture)? {
            1 => RawCaptureFormat::Rgba8888,
            2 => RawCaptureFormat::Bgra8888,
            _ => return Err(ClientError::InvalidCapture),
        };
        if metadata.get(21..24) != Some(&[0, 0, 0]) {
            return Err(ClientError::InvalidCapture);
        }
        let byte_length =
            usize::try_from(read_u64(&metadata, 24)?).map_err(|_| ClientError::CaptureTooLarge)?;
        let expected = usize::try_from(row_stride)
            .ok()
            .and_then(|stride| {
                usize::try_from(height)
                    .ok()
                    .and_then(|height| stride.checked_mul(height))
            })
            .ok_or(ClientError::InvalidCapture)?;
        if capture_id == 0
            || width == 0
            || height == 0
            || row_stride < width.saturating_mul(4)
            || byte_length != expected
        {
            return Err(ClientError::InvalidCapture);
        }
        if byte_length > MAX_CAPTURE_BYTES {
            return Err(ClientError::CaptureTooLarge);
        }
        let mut pixels = Vec::with_capacity(byte_length);
        while pixels.len() < byte_length {
            let remaining = byte_length - pixels.len();
            let requested_length = remaining.min(CAPTURE_CHUNK_BYTES);
            let offset = u32::try_from(pixels.len()).map_err(|_| ClientError::CaptureTooLarge)?;
            let requested =
                u32::try_from(requested_length).map_err(|_| ClientError::CaptureTooLarge)?;
            let mut request = Vec::with_capacity(16);
            request.extend_from_slice(&capture_id.to_le_bytes());
            request.extend_from_slice(&offset.to_le_bytes());
            request.extend_from_slice(&requested.to_le_bytes());
            let chunk = self.transact(Command::CaptureChunk, &request)?;
            if chunk.len() != requested_length {
                return Err(ClientError::InvalidCapture);
            }
            pixels.extend_from_slice(&chunk);
        }
        self.transact_empty(Command::ReleaseCapture, &capture_id.to_le_bytes())?;
        Ok(RawCapture {
            width,
            height,
            row_stride,
            format,
            pixels,
        })
    }

    /// Requests a clean daemon shutdown and permanently closes this client.
    ///
    /// # Errors
    ///
    /// Returns a transport, protocol, correlation, or backend error.
    pub fn shutdown(&mut self) -> Result<(), ClientError> {
        let result = self.transact_empty(Command::Shutdown, &[]);
        self.closed = true;
        result
    }

    fn transact_empty(&mut self, command: Command, payload: &[u8]) -> Result<(), ClientError> {
        let response = self.transact(command, payload)?;
        if response.is_empty() {
            Ok(())
        } else {
            Err(ClientError::UnexpectedResponse)
        }
    }

    fn transact(&mut self, command: Command, payload: &[u8]) -> Result<Vec<u8>, ClientError> {
        if self.closed {
            return Err(ClientError::Closed);
        }
        let request_id = self.next_request_id;
        self.next_request_id = request_id
            .checked_add(1)
            .ok_or(ClientError::RequestIdExhausted)?;
        let packet = self.channel.encode(command, request_id, payload)?;
        self.transport.send_packet(&packet)?;
        let response = self.transport.receive_packet()?;
        let frame = self.channel.decode(&response)?;
        if frame.kind != FrameKind::Response
            || frame.command != command
            || frame.request_id != request_id
        {
            return Err(ClientError::UnexpectedResponse);
        }
        if frame.status != StatusCode::Ok {
            return Err(ClientError::Remote(frame.status));
        }
        Ok(frame.payload)
    }
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, ClientError> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or(ClientError::InvalidCapture)
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, ClientError> {
    bytes
        .get(offset..offset + 8)
        .and_then(|value| value.try_into().ok())
        .map(u64::from_le_bytes)
        .ok_or(ClientError::InvalidCapture)
}

#[cfg(target_os = "android")]
pub type AndroidRootClient = RootClient<std::os::unix::net::UnixStream>;

#[cfg(target_os = "android")]
pub fn connect_android(
    socket_path: &std::path::Path,
    key: [u8; 32],
    timeout: std::time::Duration,
) -> Result<AndroidRootClient, ClientError> {
    let stream = std::os::unix::net::UnixStream::connect(socket_path)
        .map_err(|error| ClientError::Transport(TransportError::Io(error)))?;
    stream
        .set_read_timeout(Some(timeout))
        .map_err(|error| ClientError::Transport(TransportError::Io(error)))?;
    stream
        .set_write_timeout(Some(timeout))
        .map_err(|error| ClientError::Transport(TransportError::Io(error)))?;
    RootClient::connect(
        stream,
        key,
        Capabilities::from_bits(Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW),
    )
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;

    use root_daemon_core::{
        CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
    };
    use root_protocol::{Capabilities, Command, Frame, StatusCode};

    use super::{ClientError, PacketTransport, RootClient, TransportError};

    #[derive(Default)]
    struct RecordingDispatcher(Vec<Command>);

    impl CommandDispatcher for RecordingDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            self.0.push(frame.command);
            if frame.command == Command::Capture {
                let mut payload = Vec::with_capacity(32);
                payload.extend_from_slice(&1_u64.to_le_bytes());
                payload.extend_from_slice(&2_u32.to_le_bytes());
                payload.extend_from_slice(&1_u32.to_le_bytes());
                payload.extend_from_slice(&8_u32.to_le_bytes());
                payload.extend_from_slice(&[1, 0, 0, 0]);
                payload.extend_from_slice(&8_u64.to_le_bytes());
                return Ok(DispatchResponse {
                    status: StatusCode::Ok,
                    payload,
                });
            }
            if frame.command == Command::CaptureChunk {
                return Ok(DispatchResponse {
                    status: StatusCode::Ok,
                    payload: vec![1, 2, 3, 255, 4, 5, 6, 255],
                });
            }
            let status =
                if frame.command == Command::KeyEvent && frame.payload == 0_u32.to_le_bytes() {
                    StatusCode::BackendFailure
                } else {
                    StatusCode::Ok
                };
            Ok(DispatchResponse {
                status,
                payload: Vec::new(),
            })
        }
    }

    struct LoopbackTransport {
        daemon: DaemonSession<RecordingDispatcher>,
        responses: VecDeque<Vec<u8>>,
        now_ms: u64,
    }

    impl PacketTransport for LoopbackTransport {
        fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
            self.now_ms += 1;
            let response = self.daemon.receive(packet, self.now_ms).expect("daemon");
            self.responses.push_back(response);
            Ok(())
        }

        fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
            Ok(self.responses.pop_front().expect("response"))
        }
    }

    fn connect() -> RootClient<LoopbackTransport> {
        let key = [31; 32];
        let daemon = DaemonSession::new(
            key,
            10_123,
            DaemonSessionConfig {
                expected_runner_uid: 10_123,
                server_capabilities: Capabilities::from_bits(
                    Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW,
                ),
                required_client_capabilities: 0,
                idle_timeout_ms: 5_000,
                now_ms: 0,
            },
            RecordingDispatcher::default(),
        )
        .expect("daemon");
        RootClient::connect(
            LoopbackTransport {
                daemon,
                responses: VecDeque::new(),
                now_ms: 0,
            },
            key,
            Capabilities::from_bits(Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW),
        )
        .expect("client")
    }

    #[test]
    fn handshake_and_structured_input_round_trip() {
        let mut client = connect();
        assert!(client
            .negotiated_capabilities()
            .supports(Capabilities::INPUT_BASIC));
        client.tap(-2, 40).expect("tap");
        client.swipe((1, 2), (3, 4), 500).expect("swipe");
        client.ping().expect("ping");
        client.shutdown().expect("shutdown");
        assert!(matches!(client.ping(), Err(ClientError::Closed)));
    }

    #[test]
    fn duration_and_backend_failures_are_typed() {
        let mut client = connect();
        assert!(matches!(
            client.swipe((0, 0), (1, 1), 0),
            Err(ClientError::InvalidDuration)
        ));
        assert!(matches!(
            client.key_event(0),
            Err(ClientError::Remote(StatusCode::BackendFailure))
        ));
    }

    #[test]
    fn bounded_capture_is_reassembled_and_released() {
        let mut client = connect();
        let capture = client.capture().expect("capture");
        assert_eq!(
            (capture.width, capture.height, capture.row_stride),
            (2, 1, 8)
        );
        assert_eq!(capture.format, super::RawCaptureFormat::Rgba8888);
        assert_eq!(capture.pixels, [1, 2, 3, 255, 4, 5, 6, 255]);
    }
}
