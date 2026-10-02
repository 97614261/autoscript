//! Authenticated, replay-resistant protocol shared by Runner and the future `RootDaemon` process.

use sha2::{Digest, Sha256};

pub const PROTOCOL_VERSION: u16 = 3;
pub const MAX_PAYLOAD_BYTES: usize = 64 * 1024;
const MAGIC: [u8; 4] = *b"ASRD";
const HEADER_BYTES: usize = 32;
const TAG_BYTES: usize = 32;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Capabilities(u64);

impl Capabilities {
    pub const INPUT_BASIC: u64 = 1 << 0;
    pub const INPUT_MULTI_TOUCH: u64 = 1 << 1;
    pub const WINDOW_BOUNDS: u64 = 1 << 2;
    pub const CAPTURE_RAW: u64 = 1 << 3;
    pub const INPUT_POINTER_SINGLE: u64 = 1 << 4;
    pub const INPUT_PRIORITY_STOP: u64 = 1 << 5;

    #[must_use]
    pub const fn from_bits(bits: u64) -> Self {
        Self(bits)
    }

    #[must_use]
    pub const fn bits(self) -> u64 {
        self.0
    }

    #[must_use]
    pub const fn supports(self, capability: u64) -> bool {
        self.0 & capability == capability
    }
}

/// Domain-separated control channel key; packets cannot replay between input and stop sockets.
#[must_use]
pub fn priority_control_key(session_key: &[u8; 32]) -> [u8; 32] {
    hmac_sha256(session_key, b"autoscript/root/priority-stop/v1")
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Hello {
    pub protocol_version: u16,
    pub capabilities: Capabilities,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HandshakeError {
    UnsupportedVersion,
    MissingCapability,
    PeerUidMismatch,
}

impl Hello {
    /// Validates protocol compatibility and a required capability bit set.
    ///
    /// # Errors
    ///
    /// Returns an error for a different protocol version or missing capabilities.
    pub fn validate(self, required_capability: u64) -> Result<(), HandshakeError> {
        if self.protocol_version != PROTOCOL_VERSION {
            return Err(HandshakeError::UnsupportedVersion);
        }
        if !self.capabilities.supports(required_capability) {
            return Err(HandshakeError::MissingCapability);
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PeerIdentity {
    pub expected_uid: u32,
}

impl PeerIdentity {
    /// Validates the UID obtained from `SO_PEERCRED`; packet-claimed identity is never trusted.
    ///
    /// # Errors
    ///
    /// Returns [`HandshakeError::PeerUidMismatch`] for another operating-system UID.
    pub const fn validate(self, peer_uid: u32) -> Result<(), HandshakeError> {
        if peer_uid == self.expected_uid {
            Ok(())
        } else {
            Err(HandshakeError::PeerUidMismatch)
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum Command {
    Hello = 1,
    Tap = 2,
    Swipe = 3,
    KeyEvent = 4,
    GetWindowBounds = 5,
    Cancel = 6,
    Ping = 7,
    Shutdown = 8,
    Capture = 9,
    CaptureChunk = 10,
    ReleaseCapture = 11,
    PointerDown = 12,
    PointerMove = 13,
    PointerUp = 14,
}

impl Command {
    const fn from_wire(value: u16) -> Option<Self> {
        match value {
            1 => Some(Self::Hello),
            2 => Some(Self::Tap),
            3 => Some(Self::Swipe),
            4 => Some(Self::KeyEvent),
            5 => Some(Self::GetWindowBounds),
            6 => Some(Self::Cancel),
            7 => Some(Self::Ping),
            8 => Some(Self::Shutdown),
            9 => Some(Self::Capture),
            10 => Some(Self::CaptureChunk),
            11 => Some(Self::ReleaseCapture),
            12 => Some(Self::PointerDown),
            13 => Some(Self::PointerMove),
            14 => Some(Self::PointerUp),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameKind {
    Request = 0,
    Response = 1,
}

impl FrameKind {
    const fn from_wire(value: u8) -> Option<Self> {
        match value {
            0 => Some(Self::Request),
            1 => Some(Self::Response),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u16)]
pub enum StatusCode {
    Ok = 0,
    InvalidRequest = 1,
    BackendUnavailable = 2,
    BackendFailure = 3,
    Cancelled = 4,
}

impl StatusCode {
    const fn from_wire(value: u16) -> Option<Self> {
        match value {
            0 => Some(Self::Ok),
            1 => Some(Self::InvalidRequest),
            2 => Some(Self::BackendUnavailable),
            3 => Some(Self::BackendFailure),
            4 => Some(Self::Cancelled),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub kind: FrameKind,
    pub command: Command,
    pub status: StatusCode,
    pub request_id: u64,
    pub sequence: u64,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProtocolError {
    WeakSessionKey,
    PayloadTooLarge,
    Truncated,
    InvalidMagic,
    UnsupportedVersion(u16),
    UnknownCommand(u16),
    UnknownFrameKind(u8),
    UnknownStatus(u16),
    InvalidRequestStatus,
    InvalidLength,
    AuthenticationFailed,
    UnexpectedSequence { expected: u64, actual: u64 },
    SequenceExhausted,
    InvalidCommandPayload(Command),
}

#[derive(Debug, Clone)]
pub struct SecureChannel {
    key: [u8; 32],
    next_send_sequence: u64,
    next_receive_sequence: u64,
}

impl SecureChannel {
    /// Creates directional counters from a host-generated random 256-bit session key.
    ///
    /// # Errors
    ///
    /// Rejects the all-zero sentinel key.
    pub fn new(key: [u8; 32]) -> Result<Self, ProtocolError> {
        if key.iter().all(|byte| *byte == 0) {
            return Err(ProtocolError::WeakSessionKey);
        }
        Ok(Self {
            key,
            next_send_sequence: 1,
            next_receive_sequence: 1,
        })
    }

    /// Encodes and authenticates one whitelisted structured command.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid payloads, oversized messages or sequence exhaustion.
    pub fn encode(
        &mut self,
        command: Command,
        request_id: u64,
        payload: &[u8],
    ) -> Result<Vec<u8>, ProtocolError> {
        self.encode_frame(
            FrameKind::Request,
            command,
            StatusCode::Ok,
            request_id,
            payload,
        )
    }

    /// Encodes an authenticated response correlated to a request identifier.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid response payload, size, or sequence exhaustion.
    pub fn encode_response(
        &mut self,
        command: Command,
        request_id: u64,
        status: StatusCode,
        payload: &[u8],
    ) -> Result<Vec<u8>, ProtocolError> {
        self.encode_frame(FrameKind::Response, command, status, request_id, payload)
    }

    fn encode_frame(
        &mut self,
        kind: FrameKind,
        command: Command,
        status: StatusCode,
        request_id: u64,
        payload: &[u8],
    ) -> Result<Vec<u8>, ProtocolError> {
        validate_command_payload(kind, command, status, payload)?;
        if payload.len() > MAX_PAYLOAD_BYTES {
            return Err(ProtocolError::PayloadTooLarge);
        }
        let sequence = self.next_send_sequence;
        self.next_send_sequence = sequence
            .checked_add(1)
            .ok_or(ProtocolError::SequenceExhausted)?;
        let mut bytes = Vec::with_capacity(HEADER_BYTES + payload.len() + TAG_BYTES);
        bytes.extend_from_slice(&MAGIC);
        bytes.extend_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        bytes.push(kind as u8);
        bytes.push(0);
        bytes.extend_from_slice(&(command as u16).to_le_bytes());
        bytes.extend_from_slice(&(status as u16).to_le_bytes());
        bytes.extend_from_slice(&request_id.to_le_bytes());
        bytes.extend_from_slice(&sequence.to_le_bytes());
        bytes.extend_from_slice(
            &u32::try_from(payload.len())
                .map_err(|_| ProtocolError::PayloadTooLarge)?
                .to_le_bytes(),
        );
        bytes.extend_from_slice(payload);
        let tag = hmac_sha256(&self.key, &bytes);
        bytes.extend_from_slice(&tag);
        Ok(bytes)
    }

    /// Authenticates, length-checks and replay-checks one complete packet before exposing payload.
    ///
    /// # Errors
    ///
    /// Returns an error for malformed, unauthenticated, unknown or replayed packets.
    pub fn decode(&mut self, bytes: &[u8]) -> Result<Frame, ProtocolError> {
        if bytes.len() < HEADER_BYTES + TAG_BYTES {
            return Err(ProtocolError::Truncated);
        }
        if bytes[0..4] != MAGIC {
            return Err(ProtocolError::InvalidMagic);
        }
        let version = read_u16(bytes, 4)?;
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }
        let kind_wire = *bytes.get(6).ok_or(ProtocolError::Truncated)?;
        let kind =
            FrameKind::from_wire(kind_wire).ok_or(ProtocolError::UnknownFrameKind(kind_wire))?;
        if bytes.get(7) != Some(&0) {
            return Err(ProtocolError::InvalidLength);
        }
        let command_wire = read_u16(bytes, 8)?;
        let status_wire = read_u16(bytes, 10)?;
        let status =
            StatusCode::from_wire(status_wire).ok_or(ProtocolError::UnknownStatus(status_wire))?;
        if kind == FrameKind::Request && status != StatusCode::Ok {
            return Err(ProtocolError::InvalidRequestStatus);
        }
        let request_id = read_u64(bytes, 12)?;
        let sequence = read_u64(bytes, 20)?;
        let payload_len =
            usize::try_from(read_u32(bytes, 28)?).map_err(|_| ProtocolError::InvalidLength)?;
        if payload_len > MAX_PAYLOAD_BYTES || bytes.len() != HEADER_BYTES + payload_len + TAG_BYTES
        {
            return Err(ProtocolError::InvalidLength);
        }
        let signed_len = HEADER_BYTES + payload_len;
        let expected_tag = hmac_sha256(&self.key, &bytes[..signed_len]);
        if !constant_time_eq(&expected_tag, &bytes[signed_len..]) {
            return Err(ProtocolError::AuthenticationFailed);
        }
        let command =
            Command::from_wire(command_wire).ok_or(ProtocolError::UnknownCommand(command_wire))?;
        if sequence != self.next_receive_sequence {
            return Err(ProtocolError::UnexpectedSequence {
                expected: self.next_receive_sequence,
                actual: sequence,
            });
        }
        validate_command_payload(kind, command, status, &bytes[HEADER_BYTES..signed_len])?;
        self.next_receive_sequence = self
            .next_receive_sequence
            .checked_add(1)
            .ok_or(ProtocolError::SequenceExhausted)?;
        Ok(Frame {
            kind,
            command,
            status,
            request_id,
            sequence,
            payload: bytes[HEADER_BYTES..signed_len].to_vec(),
        })
    }
}

fn validate_command_payload(
    kind: FrameKind,
    command: Command,
    status: StatusCode,
    payload: &[u8],
) -> Result<(), ProtocolError> {
    let valid = match (kind, status, command) {
        (
            FrameKind::Request,
            StatusCode::Ok,
            Command::Hello | Command::Tap | Command::Cancel | Command::ReleaseCapture,
        )
        | (FrameKind::Response, StatusCode::Ok, Command::Hello) => payload.len() == 8,
        (FrameKind::Request, StatusCode::Ok, Command::Swipe) => payload.len() == 20,
        (FrameKind::Request, StatusCode::Ok, Command::PointerDown | Command::PointerMove) => {
            payload.len() == 12
        }
        (FrameKind::Request, StatusCode::Ok, Command::KeyEvent) => payload.len() == 4,
        (FrameKind::Request, StatusCode::Ok, Command::PointerUp) => payload.len() == 4,
        (FrameKind::Request, StatusCode::Ok, Command::CaptureChunk)
        | (FrameKind::Response, StatusCode::Ok, Command::GetWindowBounds) => payload.len() == 16,
        (FrameKind::Response, StatusCode::Ok, Command::Capture) => payload.len() == 32,
        (FrameKind::Response, StatusCode::Ok, Command::CaptureChunk) => !payload.is_empty(),
        (
            FrameKind::Request,
            StatusCode::Ok,
            Command::GetWindowBounds | Command::Ping | Command::Shutdown | Command::Capture,
        )
        | (FrameKind::Response, _, _) => payload.is_empty(),
        (FrameKind::Request, _, _) => false,
    };
    if valid {
        Ok(())
    } else {
        Err(ProtocolError::InvalidCommandPayload(command))
    }
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, ProtocolError> {
    bytes
        .get(offset..offset + 2)
        .and_then(|value| value.try_into().ok())
        .map(u16::from_le_bytes)
        .ok_or(ProtocolError::Truncated)
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, ProtocolError> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or(ProtocolError::Truncated)
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, ProtocolError> {
    bytes
        .get(offset..offset + 8)
        .and_then(|value| value.try_into().ok())
        .map(u64::from_le_bytes)
        .ok_or(ProtocolError::Truncated)
}

fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    let normalized_key: [u8; 32];
    let key = if key.len() > 64 {
        normalized_key = Sha256::digest(key).into();
        normalized_key.as_slice()
    } else {
        key
    };
    let mut inner_pad = [0x36; 64];
    let mut outer_pad = [0x5c; 64];
    for (index, byte) in key.iter().enumerate() {
        inner_pad[index] ^= byte;
        outer_pad[index] ^= byte;
    }
    let mut inner = Sha256::new();
    inner.update(inner_pad);
    inner.update(message);
    let inner_hash = inner.finalize();
    let mut outer = Sha256::new();
    outer.update(outer_pad);
    outer.update(inner_hash);
    outer.finalize().into()
}

fn constant_time_eq(expected: &[u8; 32], actual: &[u8]) -> bool {
    if actual.len() != expected.len() {
        return false;
    }
    expected
        .iter()
        .zip(actual)
        .fold(0_u8, |difference, (left, right)| {
            difference | (left ^ right)
        })
        == 0
}

#[cfg(test)]
mod tests {
    use super::{
        hmac_sha256, Capabilities, Command, HandshakeError, Hello, PeerIdentity, ProtocolError,
        SecureChannel, PROTOCOL_VERSION,
    };

    #[test]
    fn rejects_missing_capability_and_wrong_os_peer() {
        let hello = Hello {
            protocol_version: PROTOCOL_VERSION,
            capabilities: Capabilities::from_bits(Capabilities::INPUT_BASIC),
        };
        assert_eq!(
            hello.validate(Capabilities::WINDOW_BOUNDS),
            Err(HandshakeError::MissingCapability)
        );
        assert_eq!(
            PeerIdentity {
                expected_uid: 10234
            }
            .validate(0),
            Err(HandshakeError::PeerUidMismatch)
        );
    }

    #[test]
    fn authenticated_frame_round_trips_and_replay_is_rejected() {
        let key = [7; 32];
        let mut sender = SecureChannel::new(key).expect("sender");
        let mut receiver = SecureChannel::new(key).expect("receiver");
        let packet = sender
            .encode(Command::Tap, 42, &[1, 0, 0, 0, 2, 0, 0, 0])
            .expect("encode");
        let frame = receiver.decode(&packet).expect("decode");
        assert_eq!(frame.command, Command::Tap);
        assert_eq!(frame.request_id, 42);
        assert!(matches!(
            receiver.decode(&packet),
            Err(ProtocolError::UnexpectedSequence { .. })
        ));
    }

    #[test]
    fn tampering_and_unknown_commands_fail_closed() {
        let key = [9; 32];
        let mut sender = SecureChannel::new(key).expect("sender");
        let mut receiver = SecureChannel::new(key).expect("receiver");
        let mut tampered = sender.encode(Command::Ping, 1, &[]).expect("packet");
        tampered[8] ^= 1;
        assert_eq!(
            receiver.decode(&tampered),
            Err(ProtocolError::AuthenticationFailed)
        );

        let mut unknown = sender.encode(Command::Ping, 2, &[]).expect("packet");
        unknown[8..10].copy_from_slice(&999_u16.to_le_bytes());
        let signed_len = unknown.len() - 32;
        let tag = hmac_sha256(&key, &unknown[..signed_len]);
        unknown[signed_len..].copy_from_slice(&tag);
        assert_eq!(
            receiver.decode(&unknown),
            Err(ProtocolError::UnknownCommand(999))
        );
    }

    #[test]
    fn payload_shape_is_checked_before_dispatch() {
        let mut sender = SecureChannel::new([3; 32]).expect("sender");
        assert_eq!(
            sender.encode(Command::Tap, 1, &[0; 7]),
            Err(ProtocolError::InvalidCommandPayload(Command::Tap))
        );
        assert_eq!(
            sender.encode(Command::PointerDown, 2, &[0; 11]),
            Err(ProtocolError::InvalidCommandPayload(Command::PointerDown))
        );
        sender
            .encode(Command::PointerDown, 3, &[0; 12])
            .expect("pointer payload");
        sender
            .encode(Command::PointerUp, 4, &[0; 4])
            .expect("pointer release payload");
    }

    #[test]
    fn hmac_matches_rfc_4231_test_case_one() {
        let actual = hmac_sha256(&[0x0b; 20], b"Hi There");
        assert_eq!(
            actual,
            [
                0xb0, 0x34, 0x4c, 0x61, 0xd8, 0xdb, 0x38, 0x53, 0x5c, 0xa8, 0xaf, 0xce, 0xaf, 0x0b,
                0xf1, 0x2b, 0x88, 0x1d, 0xc2, 0x00, 0xc9, 0x83, 0x3d, 0xa7, 0x26, 0xe9, 0x37, 0x6c,
                0x2e, 0x32, 0xcf, 0xf7,
            ]
        );
    }
}
