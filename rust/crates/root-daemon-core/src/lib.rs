//! Transport-independent state machine for the privileged `RootDaemon` endpoint.

pub mod priority;
pub mod recording;

#[cfg(test)]
mod session_idle_tests;

use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};

use root_protocol::{
    Capabilities, Command, Frame, FrameKind, HandshakeError, PeerIdentity, ProtocolError,
    SecureChannel, StatusCode, PROTOCOL_VERSION,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionState {
    AwaitingHello,
    Ready,
    Closed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DaemonError {
    Handshake(HandshakeError),
    Protocol(ProtocolError),
    HelloRequired,
    DuplicateHello,
    IdleTimeout,
    SessionClosed,
    DispatchFailed,
    UnexpectedResponse,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DispatchError;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DispatchResponse {
    pub status: StatusCode,
    pub payload: Vec<u8>,
}

impl DispatchResponse {
    #[must_use]
    pub const fn success() -> Self {
        Self {
            status: StatusCode::Ok,
            payload: Vec::new(),
        }
    }
}

impl From<HandshakeError> for DaemonError {
    fn from(value: HandshakeError) -> Self {
        Self::Handshake(value)
    }
}

impl From<ProtocolError> for DaemonError {
    fn from(value: ProtocolError) -> Self {
        Self::Protocol(value)
    }
}

/// Receives only already-authenticated, shape-checked, whitelisted commands.
pub trait CommandDispatcher {
    /// Optional fixed reason for a failed stream dispatch; never includes process text.
    fn capture_failure_reason(&self) -> Option<root_protocol::CaptureFailure> {
        None
    }
    /// Executes one command. Arbitrary shell strings are absent from the protocol type.
    ///
    /// # Errors
    ///
    /// Returns an error when the selected backend cannot execute the command.
    fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError>;

    /// Fills one stream payload, including its fixed tag/offset prefix, without intermediate chunks.
    /// # Errors
    /// Returns backend failure; production implementations must retain priority cancellation checks.
    fn fill_capture_chunk(
        &mut self,
        frame: &Frame,
        prefix: &[u8; 5],
        output: &mut Vec<u8>,
    ) -> Result<StatusCode, DispatchError> {
        output.clear();
        let response = self.dispatch(frame)?;
        if response.status == StatusCode::Ok {
            let length = frame
                .payload
                .get(12..16)
                .and_then(|bytes| bytes.try_into().ok())
                .map(u32::from_le_bytes)
                .ok_or(DispatchError)?;
            if length == 0 || length > 60 * 1024 || response.payload.len() != length as usize {
                return Ok(StatusCode::BackendFailure);
            }
            output.extend_from_slice(prefix);
            output.extend_from_slice(&response.payload);
        }
        Ok(response.status)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DaemonSessionConfig {
    pub expected_runner_uid: u32,
    pub server_capabilities: Capabilities,
    pub required_client_capabilities: u64,
    pub idle_timeout_ms: u64,
    pub now_ms: u64,
}

/// Shared only by authenticated connections belonging to one daemon and clock origin.
#[derive(Debug, Default)]
pub struct SessionActivity(AtomicU64);

impl SessionActivity {
    fn record(&self, now_ms: u64) {
        self.0.fetch_max(now_ms, Ordering::AcqRel);
    }

    fn last_ms(&self) -> u64 {
        self.0.load(Ordering::Acquire)
    }
}

pub struct DaemonSession<D> {
    channel: SecureChannel,
    dispatcher: D,
    state: SessionState,
    server_capabilities: Capabilities,
    required_client_capabilities: u64,
    negotiated_capabilities: Capabilities,
    idle_timeout_ms: u64,
    last_activity_ms: u64,
    shared_activity: Option<Arc<SessionActivity>>,
    capture_stream: Option<CaptureStreamState>,
    capture_payload: Vec<u8>,
    pending_transfer_summary: Option<(u64, u64)>,
}

struct CaptureStreamState {
    request_id: u64,
    capture_id: u64,
    offset: u32,
    length: u32,
    deadline_ms: u64,
}

impl<D: CommandDispatcher> DaemonSession<D> {
    /// Creates a connection after validating the kernel-provided peer UID.
    ///
    /// # Errors
    ///
    /// Returns an error if `peer_uid` differs from the Runner UID or the key is invalid.
    pub fn new(
        key: [u8; 32],
        peer_uid: u32,
        config: DaemonSessionConfig,
        dispatcher: D,
    ) -> Result<Self, DaemonError> {
        PeerIdentity {
            expected_uid: config.expected_runner_uid,
        }
        .validate(peer_uid)?;
        Ok(Self {
            channel: SecureChannel::new(key)?,
            dispatcher,
            state: SessionState::AwaitingHello,
            server_capabilities: config.server_capabilities,
            required_client_capabilities: config.required_client_capabilities,
            negotiated_capabilities: Capabilities::from_bits(0),
            idle_timeout_ms: config.idle_timeout_ms,
            last_activity_ms: config.now_ms,
            shared_activity: None,
            capture_stream: None,
            capture_payload: Vec::new(),
            pending_transfer_summary: None,
        })
    }

    #[must_use]
    pub const fn state(&self) -> SessionState {
        self.state
    }

    #[must_use]
    pub const fn negotiated_capabilities(&self) -> Capabilities {
        self.negotiated_capabilities
    }

    #[must_use]
    pub const fn dispatcher(&self) -> &D {
        &self.dispatcher
    }

    /// Attach before Hello; unauthenticated connections never inherit or refresh activity.
    #[must_use]
    pub fn with_shared_activity(mut self, activity: Arc<SessionActivity>) -> Self {
        self.shared_activity = Some(activity);
        self
    }

    /// Remaining monotonic idle time, also used for the next-packet socket wait.
    #[must_use]
    pub fn remaining_idle_ms(&self, now_ms: u64) -> u64 {
        if self.state == SessionState::Closed {
            return 0;
        }
        let last = if self.state == SessionState::Ready {
            self.shared_activity
                .as_ref()
                .map_or(self.last_activity_ms, |activity| {
                    self.last_activity_ms.max(activity.last_ms())
                })
        } else {
            self.last_activity_ms
        };
        self.idle_timeout_ms
            .saturating_sub(now_ms.saturating_sub(last))
    }

    fn record_activity(&mut self, now_ms: u64) {
        self.last_activity_ms = self.last_activity_ms.max(now_ms);
        if let Some(activity) = &self.shared_activity {
            activity.record(now_ms);
        }
    }

    /// Checks the monotonic idle deadline without polling.
    ///
    /// # Errors
    ///
    /// Closes the session and returns [`DaemonError::IdleTimeout`] at the deadline.
    pub fn check_idle(&mut self, now_ms: u64) -> Result<(), DaemonError> {
        if self.state == SessionState::Closed {
            return Err(DaemonError::SessionClosed);
        }
        if self.remaining_idle_ms(now_ms) == 0 {
            self.state = SessionState::Closed;
            return Err(DaemonError::IdleTimeout);
        }
        Ok(())
    }

    /// Decodes and routes one complete packet.
    ///
    /// # Errors
    ///
    /// Rejects invalid authentication, sequence, handshake order, timeout, or dispatch failure.
    pub fn receive(&mut self, packet: &[u8], now_ms: u64) -> Result<Vec<u8>, DaemonError> {
        if self.capture_stream.is_some() || self.pending_transfer_summary.is_some() {
            return Err(DaemonError::UnexpectedResponse);
        }
        self.check_idle(now_ms)?;
        let frame = self.channel.decode(packet)?;
        if frame.kind != FrameKind::Request {
            return Err(DaemonError::UnexpectedResponse);
        }
        let mut response = match self.state {
            SessionState::AwaitingHello => {
                if frame.command != Command::Hello {
                    return Err(DaemonError::HelloRequired);
                }
                let payload: [u8; 8] = frame
                    .payload
                    .as_slice()
                    .try_into()
                    .map_err(|_| ProtocolError::InvalidCommandPayload(Command::Hello))?;
                let bits = u64::from_le_bytes(payload);
                let hello = root_protocol::Hello {
                    protocol_version: PROTOCOL_VERSION,
                    capabilities: Capabilities::from_bits(bits),
                };
                hello.validate(self.required_client_capabilities)?;
                self.negotiated_capabilities =
                    Capabilities::from_bits(bits & self.server_capabilities.bits());
                self.state = SessionState::Ready;
                DispatchResponse {
                    status: StatusCode::Ok,
                    payload: self.negotiated_capabilities.bits().to_le_bytes().to_vec(),
                }
            }
            SessionState::Ready => {
                if frame.command == Command::Hello {
                    return Err(DaemonError::DuplicateHello);
                }
                let response = if command_capability(frame.command)
                    .is_some_and(|capability| !self.negotiated_capabilities.supports(capability))
                    || (frame.command == Command::RecordingStart
                        && root_protocol::recording::RecordingConfig::decode(&frame.payload)
                            .is_ok_and(|c| c.deadline_success)
                        && !self
                            .negotiated_capabilities
                            .supports(Capabilities::INPUT_RECORDING_TIMED))
                {
                    DispatchResponse {
                        status: StatusCode::BackendUnavailable,
                        payload: Vec::new(),
                    }
                } else {
                    self.dispatcher
                        .dispatch(&frame)
                        .unwrap_or(DispatchResponse {
                            status: StatusCode::BackendFailure,
                            payload: Vec::new(),
                        })
                };
                if frame.command == Command::Shutdown {
                    self.state = SessionState::Closed;
                }
                response
            }
            SessionState::Closed => return Err(DaemonError::SessionClosed),
        };
        self.record_activity(now_ms);
        if matches!(frame.command, Command::Capture | Command::CaptureStream)
            && response.status == StatusCode::BackendFailure
            && (!self
                .negotiated_capabilities
                .supports(Capabilities::CAPTURE_FAILURE_DETAILS)
                || root_protocol::CaptureFailure::decode(&response.payload)
                    == Some(root_protocol::CaptureFailure::ControlUnavailable)
                    && !self
                        .negotiated_capabilities
                        .supports(Capabilities::CAPTURE_CONTROL_DETAILS))
        {
            response.payload.clear();
        }
        if frame.command == Command::CaptureStream && response.status == StatusCode::Ok {
            self.begin_capture_stream(frame.request_id, &mut response.payload, now_ms)?;
        }
        self.channel
            .encode_response(
                frame.command,
                frame.request_id,
                response.status,
                &response.payload,
            )
            .map_err(DaemonError::Protocol)
    }

    fn begin_capture_stream(
        &mut self,
        request_id: u64,
        payload: &mut Vec<u8>,
        now_ms: u64,
    ) -> Result<(), DaemonError> {
        if payload.len() == 97
            && !self
                .negotiated_capabilities
                .supports(Capabilities::CAPTURE_STAGE_TIMINGS)
        {
            payload.truncate(49);
        }
        if !matches!(payload.len(), 49 | 97) || payload[0] != 0 {
            return Err(DaemonError::DispatchFailed);
        }
        let id = u64::from_le_bytes(
            payload[1..9]
                .try_into()
                .map_err(|_| DaemonError::DispatchFailed)?,
        );
        let length = u64::from_le_bytes(
            payload[25..33]
                .try_into()
                .map_err(|_| DaemonError::DispatchFailed)?,
        );
        if id == 0 || length == 0 || length > 64 * 1024 * 1024 {
            return Err(DaemonError::DispatchFailed);
        }
        self.capture_stream = Some(CaptureStreamState {
            request_id,
            capture_id: id,
            offset: 0,
            length: u32::try_from(length).map_err(|_| DaemonError::DispatchFailed)?,
            deadline_ms: now_ms.saturating_add(10_000),
        });
        if self
            .negotiated_capabilities
            .supports(Capabilities::CAPTURE_TRANSFER_TIMINGS)
        {
            self.pending_transfer_summary = Some((request_id, length.div_ceil(60 * 1024)));
        }
        Ok(())
    }

    /// Emits the next authenticated bounded capture packet without another client request.
    /// Backpressure is the caller's synchronous socket write; no frame-sized response queue.
    ///
    /// # Errors
    /// Returns a dispatch/encoding error; incomplete streams must close their connection.
    pub fn next_capture_packet(&mut self, now_ms: u64) -> Result<Option<Vec<u8>>, DaemonError> {
        let mut packet = Vec::new();
        Ok(self
            .next_capture_packet_into(now_ms, &mut packet)?
            .then_some(packet))
    }

    /// Reuses one authenticated wire buffer; no valid bytes remain on failure or end of stream.
    /// # Errors
    /// Returns a backend/protocol error; incomplete streams must close their connection.
    pub fn next_capture_packet_into(
        &mut self,
        now_ms: u64,
        packet: &mut Vec<u8>,
    ) -> Result<bool, DaemonError> {
        packet.clear();
        self.capture_payload.clear();
        let Some(mut stream) = self.capture_stream.take() else {
            return Ok(false);
        };
        let length = (stream.length - stream.offset).min(60 * 1024);
        let mut request = stream.capture_id.to_le_bytes().to_vec();
        request.extend_from_slice(&stream.offset.to_le_bytes());
        request.extend_from_slice(&length.to_le_bytes());
        let mut prefix = [1, 0, 0, 0, 0];
        prefix[1..].copy_from_slice(&stream.offset.to_le_bytes());
        let result = if now_ms >= stream.deadline_ms {
            Ok(StatusCode::Cancelled)
        } else {
            self.dispatcher.fill_capture_chunk(
                &Frame {
                    kind: FrameKind::Request,
                    command: Command::CaptureChunk,
                    status: StatusCode::Ok,
                    request_id: stream.request_id,
                    sequence: 0,
                    payload: request,
                },
                &prefix,
                &mut self.capture_payload,
            )
        };
        let mut status = result
            .as_ref()
            .copied()
            .unwrap_or(StatusCode::BackendFailure);
        if status == StatusCode::Ok
            && (self.capture_payload.len() != length as usize + 5
                || self.capture_payload.get(..5) != Some(prefix.as_slice()))
        {
            status = StatusCode::BackendFailure;
        }
        if status != StatusCode::Ok {
            self.capture_payload.clear();
            if status == StatusCode::BackendFailure
                && self
                    .negotiated_capabilities
                    .supports(Capabilities::CAPTURE_FAILURE_DETAILS)
            {
                if let Some(reason) = self.dispatcher.capture_failure_reason().filter(|reason| {
                    *reason != root_protocol::CaptureFailure::ControlUnavailable
                        || self
                            .negotiated_capabilities
                            .supports(Capabilities::CAPTURE_CONTROL_DETAILS)
                }) {
                    self.capture_payload
                        .extend_from_slice(&(reason as u32).to_le_bytes());
                }
            }
        }
        let encoded = self.channel.encode_response_into(
            Command::CaptureStream,
            stream.request_id,
            status,
            &self.capture_payload,
            packet,
        );
        self.capture_payload.clear();
        let complete =
            status != StatusCode::Ok || encoded.is_err() || stream.offset + length == stream.length;
        if complete {
            if status != StatusCode::Ok || encoded.is_err() {
                self.pending_transfer_summary = None;
            }
            let _ = self.dispatcher.dispatch(&Frame {
                kind: FrameKind::Request,
                command: Command::ReleaseCapture,
                status: StatusCode::Ok,
                request_id: stream.request_id,
                sequence: 0,
                payload: stream.capture_id.to_le_bytes().to_vec(),
            });
        } else {
            stream.offset += length;
            self.capture_stream = Some(stream);
        }
        if result.is_err() {
            packet.clear();
            return Err(DaemonError::DispatchFailed);
        }
        encoded?;
        self.record_activity(now_ms);
        Ok(true)
    }

    /// Emits the negotiated authenticated summary only after the complete pixel stream.
    /// # Errors
    /// Rejects premature completion or out-of-bound timing values; the caller must close on error.
    pub fn finish_capture_transfer_into(
        &mut self,
        summary: root_protocol::CaptureTransferSummary,
        packet: &mut Vec<u8>,
    ) -> Result<bool, DaemonError> {
        packet.clear();
        if self.capture_stream.is_some() {
            return Err(DaemonError::UnexpectedResponse);
        }
        let Some((request_id, expected_packets)) = self.pending_transfer_summary.take() else {
            return Ok(false);
        };
        let payload = summary.encode();
        if summary.packets != expected_packets
            || root_protocol::CaptureTransferSummary::decode(&payload).is_none()
        {
            self.state = SessionState::Closed;
            return Err(DaemonError::DispatchFailed);
        }
        self.channel.encode_response_into(
            Command::CaptureStream,
            request_id,
            StatusCode::Ok,
            &payload,
            packet,
        )?;
        Ok(true)
    }
}

const fn command_capability(command: Command) -> Option<u64> {
    match command {
        Command::Tap | Command::Swipe | Command::KeyEvent => Some(Capabilities::INPUT_BASIC),
        Command::PointerDown | Command::PointerMove | Command::PointerUp => {
            Some(Capabilities::INPUT_POINTER_SINGLE)
        }
        Command::GetWindowBounds => Some(Capabilities::WINDOW_BOUNDS),
        Command::RecordingStart | Command::RecordingFinish => Some(Capabilities::INPUT_RECORDING),
        Command::Capture | Command::CaptureChunk | Command::ReleaseCapture => {
            Some(Capabilities::CAPTURE_RAW)
        }
        Command::CaptureStream => Some(Capabilities::CAPTURE_STREAM | Capabilities::CAPTURE_RAW),
        Command::Hello | Command::Cancel | Command::Ping | Command::Shutdown => None,
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn capture_failure_details_are_stripped_for_legacy_clients() {
        struct FailedCapture;
        impl CommandDispatcher for FailedCapture {
            fn dispatch(&mut self, _: &Frame) -> Result<DispatchResponse, DispatchError> {
                Ok(DispatchResponse {
                    status: root_protocol::StatusCode::BackendFailure,
                    payload: (root_protocol::CaptureFailure::Deadline as u32)
                        .to_le_bytes()
                        .to_vec(),
                })
            }
        }
        for details in [false, true] {
            let key = [19; 32];
            let bits = Capabilities::CAPTURE_RAW
                | Capabilities::CAPTURE_STREAM
                | if details {
                    Capabilities::CAPTURE_FAILURE_DETAILS
                } else {
                    0
                };
            let mut config = config();
            config.server_capabilities = Capabilities::from_bits(bits);
            config.required_client_capabilities = 0;
            let mut daemon =
                DaemonSession::new(key, config.expected_runner_uid, config, FailedCapture).unwrap();
            let mut client = SecureChannel::new(key).unwrap();
            let response = daemon
                .receive(
                    &client
                        .encode(Command::Hello, 1, &bits.to_le_bytes())
                        .unwrap(),
                    101,
                )
                .unwrap();
            client.decode(&response).unwrap();
            let response = daemon
                .receive(&client.encode(Command::CaptureStream, 2, &[]).unwrap(), 102)
                .unwrap();
            let frame = client.decode(&response).unwrap();
            assert_eq!(frame.status, root_protocol::StatusCode::BackendFailure);
            assert_eq!(frame.payload.len(), if details { 4 } else { 0 });
            assert!(daemon.next_capture_packet(103).unwrap().is_none());
        }
    }
    use root_protocol::{Capabilities, Command, Frame, ProtocolError, SecureChannel};

    use super::{
        CommandDispatcher, DaemonError, DaemonSession, DaemonSessionConfig, DispatchError,
        DispatchResponse, SessionState,
    };

    #[derive(Default)]
    struct RecordingDispatcher(Vec<Command>);

    impl CommandDispatcher for RecordingDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            self.0.push(frame.command);
            Ok(DispatchResponse::success())
        }
    }

    fn session() -> (SecureChannel, DaemonSession<RecordingDispatcher>) {
        let key = [17; 32];
        let sender = SecureChannel::new(key).expect("sender");
        let receiver = DaemonSession::new(key, 10_234, config(), RecordingDispatcher::default())
            .expect("session");
        (sender, receiver)
    }

    fn config() -> DaemonSessionConfig {
        DaemonSessionConfig {
            expected_runner_uid: 10_234,
            server_capabilities: Capabilities::from_bits(
                Capabilities::INPUT_BASIC | Capabilities::WINDOW_BOUNDS,
            ),
            required_client_capabilities: Capabilities::INPUT_BASIC,
            idle_timeout_ms: 5_000,
            now_ms: 100,
        }
    }

    #[test]
    fn requires_hello_then_negotiates_and_dispatches() {
        let (mut sender, mut receiver) = session();
        let early = sender.encode(Command::Ping, 1, &[]).expect("ping");
        assert_eq!(
            receiver.receive(&early, 101),
            Err(DaemonError::HelloRequired)
        );
        assert!(receiver.dispatcher().0.is_empty());

        let (mut sender, mut receiver) = session();
        let hello = sender
            .encode(
                Command::Hello,
                1,
                &(Capabilities::INPUT_BASIC | Capabilities::INPUT_POINTER_SINGLE).to_le_bytes(),
            )
            .expect("hello");
        let hello_response = receiver.receive(&hello, 101).expect("handshake");
        let response = sender.decode(&hello_response).expect("hello response");
        assert_eq!(response.kind, root_protocol::FrameKind::Response);
        assert_eq!(response.status, root_protocol::StatusCode::Ok);
        assert_eq!(receiver.state(), SessionState::Ready);
        assert_eq!(
            receiver.negotiated_capabilities().bits(),
            Capabilities::INPUT_BASIC
        );
        let pointer = sender
            .encode(Command::PointerDown, 2, &[0; 12])
            .expect("pointer");
        let response = receiver
            .receive(&pointer, 102)
            .expect("capability response");
        assert_eq!(
            sender.decode(&response).expect("pointer response").status,
            root_protocol::StatusCode::BackendUnavailable
        );
        assert!(receiver.dispatcher().0.is_empty());

        let ping = sender.encode(Command::Ping, 3, &[]).expect("ping");
        let response = receiver.receive(&ping, 103).expect("dispatch");
        assert_eq!(
            sender.decode(&response).expect("ping response").status,
            root_protocol::StatusCode::Ok
        );
        assert_eq!(receiver.dispatcher().0, vec![Command::Ping]);
    }

    #[test]
    fn tamper_and_replay_never_reach_dispatcher() {
        let (mut sender, mut receiver) = session();
        let hello = sender
            .encode(Command::Hello, 1, &Capabilities::INPUT_BASIC.to_le_bytes())
            .expect("hello");
        receiver.receive(&hello, 101).expect("handshake");
        let ping = sender.encode(Command::Ping, 2, &[]).expect("ping");
        let mut tampered = ping.clone();
        tampered[8] ^= 1;
        assert_eq!(
            receiver.receive(&tampered, 102),
            Err(DaemonError::Protocol(ProtocolError::AuthenticationFailed))
        );
        assert!(receiver.dispatcher().0.is_empty());
        receiver.receive(&ping, 103).expect("valid ping");
        assert!(matches!(
            receiver.receive(&ping, 104),
            Err(DaemonError::Protocol(
                ProtocolError::UnexpectedSequence { .. }
            ))
        ));
        assert_eq!(receiver.dispatcher().0, vec![Command::Ping]);
    }

    #[test]
    fn idle_timeout_and_shutdown_are_terminal() {
        let (_, mut receiver) = session();
        assert_eq!(receiver.check_idle(5_100), Err(DaemonError::IdleTimeout));
        assert_eq!(receiver.state(), SessionState::Closed);

        let (mut sender, mut receiver) = session();
        let hello = sender
            .encode(Command::Hello, 1, &Capabilities::INPUT_BASIC.to_le_bytes())
            .expect("hello");
        receiver.receive(&hello, 101).expect("handshake");
        let shutdown = sender.encode(Command::Shutdown, 2, &[]).expect("shutdown");
        receiver.receive(&shutdown, 102).expect("shutdown");
        assert_eq!(receiver.state(), SessionState::Closed);
        assert_eq!(receiver.dispatcher().0, vec![Command::Shutdown]);
        assert_eq!(receiver.check_idle(103), Err(DaemonError::SessionClosed));
    }

    #[test]
    fn wrong_kernel_peer_uid_fails_before_a_session_exists() {
        let result = DaemonSession::new([1; 32], 0, config(), RecordingDispatcher::default());
        assert!(matches!(
            result,
            Err(DaemonError::Handshake(
                root_protocol::HandshakeError::PeerUidMismatch
            ))
        ));
    }

    struct StreamDispatcher {
        commands: Vec<Command>,
        invalid: bool,
    }

    struct DirectStreamDispatcher {
        pixels: Vec<u8>,
        fills: usize,
        releases: usize,
        fault: u8,
    }
    impl CommandDispatcher for DirectStreamDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            if frame.command == Command::ReleaseCapture {
                self.releases += 1;
                return Ok(DispatchResponse::success());
            }
            assert_eq!(frame.command, Command::CaptureStream);
            let mut metadata = vec![0; 97];
            metadata[1..9].copy_from_slice(&1_u64.to_le_bytes());
            metadata[25..33]
                .copy_from_slice(&u64::try_from(self.pixels.len()).unwrap().to_le_bytes());
            Ok(DispatchResponse {
                status: root_protocol::StatusCode::Ok,
                payload: metadata,
            })
        }
        fn fill_capture_chunk(
            &mut self,
            frame: &Frame,
            prefix: &[u8; 5],
            output: &mut Vec<u8>,
        ) -> Result<root_protocol::StatusCode, DispatchError> {
            self.fills += 1;
            let offset = u32::from_le_bytes(frame.payload[8..12].try_into().unwrap()) as usize;
            let length = u32::from_le_bytes(frame.payload[12..16].try_into().unwrap()) as usize;
            output.clear();
            output.extend_from_slice(prefix);
            output.extend_from_slice(&self.pixels[offset..offset + length]);
            match self.fault {
                1 => Err(DispatchError),
                2 => Ok(root_protocol::StatusCode::Cancelled),
                3 => {
                    output[0] = 9;
                    Ok(root_protocol::StatusCode::Ok)
                }
                4 => {
                    output.pop();
                    Ok(root_protocol::StatusCode::Ok)
                }
                _ => Ok(root_protocol::StatusCode::Ok),
            }
        }
    }
    #[test]
    fn direct_stream_reuses_buffers_and_negotiates_metadata_without_changing_wire_pixels() {
        for details in [false, true] {
            let key = [75; 32];
            let base = Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM;
            let mut cfg = config();
            cfg.server_capabilities =
                Capabilities::from_bits(base | Capabilities::CAPTURE_STAGE_TIMINGS);
            cfg.required_client_capabilities = 0;
            let mut daemon = DaemonSession::new(
                key,
                10_234,
                cfg,
                DirectStreamDispatcher {
                    pixels: vec![36; 150_000],
                    fills: 0,
                    releases: 0,
                    fault: 0,
                },
            )
            .unwrap();
            let mut sender = SecureChannel::new(key).unwrap();
            let mut decoder = SecureChannel::new(key).unwrap();
            let requested = base
                | if details {
                    Capabilities::CAPTURE_STAGE_TIMINGS
                } else {
                    0
                };
            let hello = daemon
                .receive(
                    &sender
                        .encode(Command::Hello, 1, &requested.to_le_bytes())
                        .unwrap(),
                    101,
                )
                .unwrap();
            decoder.decode(&hello).unwrap();
            let mut packet = Vec::with_capacity(70 * 1024);
            let address = packet.as_ptr();
            for capture in 0..2 {
                let metadata = daemon
                    .receive(
                        &sender
                            .encode(Command::CaptureStream, capture + 2, &[])
                            .unwrap(),
                        102,
                    )
                    .unwrap();
                assert_eq!(
                    decoder.decode(&metadata).unwrap().payload.len(),
                    if details { 97 } else { 49 }
                );
                let mut pixels = Vec::new();
                while daemon.next_capture_packet_into(103, &mut packet).unwrap() {
                    assert_eq!(packet.as_ptr(), address);
                    let frame = decoder.decode_view(&packet).unwrap();
                    assert_eq!(frame.request_id, capture + 2);
                    assert_eq!(frame.payload[0], 1);
                    let offset = u32::from_le_bytes(frame.payload[1..5].try_into().unwrap());
                    assert_eq!(usize::try_from(offset).unwrap(), pixels.len());
                    pixels.extend_from_slice(&frame.payload[5..]);
                }
                assert!(packet.is_empty());
                assert_eq!(pixels, daemon.dispatcher().pixels);
            }
            assert_eq!(daemon.dispatcher().fills, 6);
            assert_eq!(daemon.dispatcher().releases, 2);
        }
    }

    #[test]
    fn direct_stream_faults_release_frames_clear_invalid_data_and_do_not_queue_pixels() {
        for fault in 1..=4 {
            let key = [76; 32];
            let mut cfg = config();
            cfg.server_capabilities =
                Capabilities::from_bits(Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM);
            cfg.required_client_capabilities = 0;
            let mut daemon = DaemonSession::new(
                key,
                10_234,
                cfg,
                DirectStreamDispatcher {
                    pixels: vec![37; 100_000],
                    fills: 0,
                    releases: 0,
                    fault,
                },
            )
            .unwrap();
            let mut sender = SecureChannel::new(key).unwrap();
            let mut decoder = SecureChannel::new(key).unwrap();
            decoder
                .decode(
                    &daemon
                        .receive(
                            &sender
                                .encode(
                                    Command::Hello,
                                    1,
                                    &cfg.server_capabilities.bits().to_le_bytes(),
                                )
                                .unwrap(),
                            101,
                        )
                        .unwrap(),
                )
                .unwrap();
            decoder
                .decode(
                    &daemon
                        .receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 102)
                        .unwrap(),
                )
                .unwrap();
            let mut packet = vec![99; 100];
            let result = daemon.next_capture_packet_into(103, &mut packet);
            if fault == 1 {
                assert_eq!(result, Err(DaemonError::DispatchFailed));
                assert!(packet.is_empty());
            } else {
                assert_eq!(result, Ok(true));
                let frame = decoder.decode(&packet).unwrap();
                assert_eq!(
                    frame.status,
                    if fault == 2 {
                        root_protocol::StatusCode::Cancelled
                    } else {
                        root_protocol::StatusCode::BackendFailure
                    }
                );
                assert!(frame.payload.is_empty());
            }
            assert_eq!(daemon.dispatcher().releases, 1);
            assert!(daemon.capture_payload.is_empty());
            assert_eq!(daemon.next_capture_packet_into(104, &mut packet), Ok(false));
            assert!(packet.is_empty());
        }
    }
    impl CommandDispatcher for StreamDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            self.commands.push(frame.command);
            let payload = if frame.command == Command::CaptureStream {
                let mut metadata = vec![0; 49];
                metadata[1..9].copy_from_slice(&1_u64.to_le_bytes());
                metadata[25..33].copy_from_slice(
                    &(if self.invalid {
                        64 * 1024 * 1024 + 1
                    } else {
                        8_u64
                    })
                    .to_le_bytes(),
                );
                metadata
            } else if frame.command == Command::CaptureChunk {
                vec![1; 8]
            } else {
                Vec::new()
            };
            Ok(DispatchResponse {
                status: root_protocol::StatusCode::Ok,
                payload,
            })
        }
    }
    #[test]
    fn transfer_summary_rejects_early_completion_wrong_count_and_timing_overflow() {
        for fault in 0..3 {
            let key = [81; 32];
            let mut cfg = config();
            cfg.server_capabilities = Capabilities::from_bits(
                Capabilities::CAPTURE_RAW
                    | Capabilities::CAPTURE_STREAM
                    | Capabilities::CAPTURE_TRANSFER_TIMINGS,
            );
            cfg.required_client_capabilities = 0;
            let mut daemon = DaemonSession::new(
                key,
                10_234,
                cfg,
                StreamDispatcher {
                    commands: Vec::new(),
                    invalid: false,
                },
            )
            .unwrap();
            let mut sender = SecureChannel::new(key).unwrap();
            daemon
                .receive(
                    &sender
                        .encode(
                            Command::Hello,
                            1,
                            &cfg.server_capabilities.bits().to_le_bytes(),
                        )
                        .unwrap(),
                    101,
                )
                .unwrap();
            daemon
                .receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 102)
                .unwrap();
            let mut packet = vec![99];
            if fault != 0 {
                assert_eq!(daemon.next_capture_packet_into(103, &mut packet), Ok(true));
                assert_eq!(daemon.next_capture_packet_into(104, &mut packet), Ok(false));
            }
            let summary = root_protocol::CaptureTransferSummary {
                encode_us: if fault == 2 { 10_000_001 } else { 10 },
                write_us: 20,
                packets: if fault == 1 { 2 } else { 1 },
            };
            assert!(daemon
                .finish_capture_transfer_into(summary, &mut packet)
                .is_err());
            assert!(packet.is_empty());
            if fault != 0 {
                assert_eq!(daemon.state(), SessionState::Closed);
            }
        }
    }
    #[test]
    fn stream_deadline_releases_staging_and_rejects_interleaved_requests() {
        let key = [49; 32];
        let mut config = config();
        config.server_capabilities =
            Capabilities::from_bits(Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM);
        config.required_client_capabilities = 0;
        let mut daemon = DaemonSession::new(
            key,
            10_234,
            config,
            StreamDispatcher {
                commands: Vec::new(),
                invalid: false,
            },
        )
        .unwrap();
        let mut sender = SecureChannel::new(key).unwrap();
        daemon
            .receive(
                &sender
                    .encode(
                        Command::Hello,
                        1,
                        &config.server_capabilities.bits().to_le_bytes(),
                    )
                    .unwrap(),
                101,
            )
            .unwrap();
        daemon
            .receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 102)
            .unwrap();
        assert!(matches!(
            daemon.receive(&sender.encode(Command::Ping, 3, &[]).unwrap(), 103),
            Err(DaemonError::UnexpectedResponse)
        ));
        let packet = daemon.next_capture_packet(10_102).unwrap().unwrap();
        // Parse after consuming the HELLO and metadata responses is tested end-to-end by root-client.
        assert!(!packet.is_empty());
        assert!(daemon.next_capture_packet(10_103).unwrap().is_none());
        assert_eq!(
            daemon.dispatcher().commands,
            vec![Command::CaptureStream, Command::ReleaseCapture]
        );
    }

    #[test]
    fn stream_requires_both_negotiated_capabilities_and_rejects_oversized_metadata() {
        for (bits, invalid) in [
            (Capabilities::CAPTURE_RAW, false),
            (
                Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM,
                true,
            ),
        ] {
            let key = [50; 32];
            let mut config = config();
            config.server_capabilities = Capabilities::from_bits(bits);
            config.required_client_capabilities = 0;
            let mut daemon = DaemonSession::new(
                key,
                10_234,
                config,
                StreamDispatcher {
                    commands: Vec::new(),
                    invalid,
                },
            )
            .unwrap();
            let mut sender = SecureChannel::new(key).unwrap();
            daemon
                .receive(
                    &sender
                        .encode(Command::Hello, 1, &bits.to_le_bytes())
                        .unwrap(),
                    101,
                )
                .unwrap();
            let result =
                daemon.receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 102);
            if invalid {
                assert!(matches!(result, Err(DaemonError::DispatchFailed)));
            } else {
                assert!(result.is_ok());
                assert!(daemon.dispatcher().commands.is_empty());
            }
        }
    }
}
