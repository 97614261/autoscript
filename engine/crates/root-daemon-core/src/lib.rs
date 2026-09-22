//! Transport-independent state machine for the privileged `RootDaemon` endpoint.

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
    /// Executes one command. Arbitrary shell strings are absent from the protocol type.
    ///
    /// # Errors
    ///
    /// Returns an error when the selected backend cannot execute the command.
    fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DaemonSessionConfig {
    pub expected_runner_uid: u32,
    pub server_capabilities: Capabilities,
    pub required_client_capabilities: u64,
    pub idle_timeout_ms: u64,
    pub now_ms: u64,
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

    /// Checks the monotonic idle deadline without polling.
    ///
    /// # Errors
    ///
    /// Closes the session and returns [`DaemonError::IdleTimeout`] at the deadline.
    pub fn check_idle(&mut self, now_ms: u64) -> Result<(), DaemonError> {
        if self.state == SessionState::Closed {
            return Err(DaemonError::SessionClosed);
        }
        if now_ms.saturating_sub(self.last_activity_ms) >= self.idle_timeout_ms {
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
        self.check_idle(now_ms)?;
        let frame = self.channel.decode(packet)?;
        if frame.kind != FrameKind::Request {
            return Err(DaemonError::UnexpectedResponse);
        }
        let response = match self.state {
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
        self.last_activity_ms = now_ms;
        self.channel
            .encode_response(
                frame.command,
                frame.request_id,
                response.status,
                &response.payload,
            )
            .map_err(DaemonError::Protocol)
    }
}

const fn command_capability(command: Command) -> Option<u64> {
    match command {
        Command::Tap | Command::Swipe | Command::KeyEvent => Some(Capabilities::INPUT_BASIC),
        Command::PointerDown | Command::PointerMove | Command::PointerUp => {
            Some(Capabilities::INPUT_POINTER_SINGLE)
        }
        Command::GetWindowBounds => Some(Capabilities::WINDOW_BOUNDS),
        Command::Capture | Command::CaptureChunk | Command::ReleaseCapture => {
            Some(Capabilities::CAPTURE_RAW)
        }
        Command::Hello | Command::Cancel | Command::Ping | Command::Shutdown => None,
    }
}

#[cfg(test)]
mod tests {
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
}
