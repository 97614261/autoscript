//! Authenticated, replay-resistant protocol shared by Runner and the future `RootDaemon` process.

use sha2::{Digest, Sha256};

pub mod recording;

#[cfg(test)]
mod auth_benchmark;

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
    pub const INPUT_RECORDING: u64 = 1 << 6;
    pub const CAPTURE_STREAM: u64 = 1 << 7;
    pub const CAPTURE_STAGE_TIMINGS: u64 = 1 << 8;
    pub const CAPTURE_FAILURE_DETAILS: u64 = 1 << 9;
    pub const CAPTURE_TRANSFER_TIMINGS: u64 = 1 << 10;
    pub const CAPTURE_CONTROL_DETAILS: u64 = 1 << 11;
    pub const INPUT_RECORDING_TIMED: u64 = 1 << 12;

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
    RecordingStart = 15,
    RecordingFinish = 16,
    CaptureStream = 17,
}

/// Fixed diagnostic codes only: no process output, paths or screen content on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u32)]
pub enum CaptureFailure {
    Deadline = 1,
    ProcessStart = 2,
    PipeRead = 3,
    ProcessExit = 4,
    InvalidFrame = 5,
    IdExhausted = 6,
    ControlUnavailable = 7,
}

impl CaptureFailure {
    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        match u32::from_le_bytes(bytes.try_into().ok()?) {
            1 => Some(Self::Deadline),
            2 => Some(Self::ProcessStart),
            3 => Some(Self::PipeRead),
            4 => Some(Self::ProcessExit),
            5 => Some(Self::InvalidFrame),
            6 => Some(Self::IdExhausted),
            7 => Some(Self::ControlUnavailable),
            _ => None,
        }
    }

    #[must_use]
    pub const fn code(self) -> &'static str {
        match self {
            Self::Deadline => "CAPTURE_DEADLINE",
            Self::ProcessStart => "CAPTURE_PROCESS_START",
            Self::PipeRead => "CAPTURE_PIPE_READ",
            Self::ProcessExit => "CAPTURE_PROCESS_EXIT",
            Self::InvalidFrame => "CAPTURE_INVALID_FRAME",
            Self::IdExhausted => "CAPTURE_ID_EXHAUSTED",
            Self::ControlUnavailable => "CAPTURE_CONTROL_UNAVAILABLE",
        }
    }
}

/// Optional authenticated end-of-stream summary; totals include data packets only.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureTransferSummary {
    pub encode_us: u64,
    pub write_us: u64,
    pub packets: u64,
}

impl CaptureTransferSummary {
    #[must_use]
    pub fn encode(self) -> [u8; 25] {
        let mut payload = [0; 25];
        payload[0] = 2;
        for (bytes, value) in
            payload[1..]
                .chunks_exact_mut(8)
                .zip([self.encode_us, self.write_us, self.packets])
        {
            bytes.copy_from_slice(&value.to_le_bytes());
        }
        payload
    }

    #[must_use]
    pub fn decode(payload: &[u8]) -> Option<Self> {
        if payload.len() != 25 || payload[0] != 2 {
            return None;
        }
        let summary = Self {
            encode_us: u64::from_le_bytes(payload[1..9].try_into().ok()?),
            write_us: u64::from_le_bytes(payload[9..17].try_into().ok()?),
            packets: u64::from_le_bytes(payload[17..25].try_into().ok()?),
        };
        (summary.encode_us <= 10_000_000
            && summary.write_us <= 10_000_000
            && (1..=1_093).contains(&summary.packets))
        .then_some(summary)
    }
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
            15 => Some(Self::RecordingStart),
            16 => Some(Self::RecordingFinish),
            17 => Some(Self::CaptureStream),
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

/// Verified frame view whose payload cannot outlive its authenticated packet buffer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameView<'a> {
    pub kind: FrameKind,
    pub command: Command,
    pub status: StatusCode,
    pub request_id: u64,
    pub sequence: u64,
    pub payload: &'a [u8],
}

impl From<FrameView<'_>> for Frame {
    fn from(frame: FrameView<'_>) -> Self {
        Self {
            kind: frame.kind,
            command: frame.command,
            status: frame.status,
            request_id: frame.request_id,
            sequence: frame.sequence,
            payload: frame.payload.to_vec(),
        }
    }
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

    /// Encodes a response into reusable bounded packet storage.
    /// # Errors
    /// Rejects invalid payloads, oversized messages or sequence exhaustion; clears valid bytes.
    pub fn encode_response_into(
        &mut self,
        command: Command,
        request_id: u64,
        status: StatusCode,
        payload: &[u8],
        bytes: &mut Vec<u8>,
    ) -> Result<(), ProtocolError> {
        self.encode_frame_into(
            FrameKind::Response,
            command,
            status,
            request_id,
            payload,
            bytes,
        )
    }

    fn encode_frame(
        &mut self,
        kind: FrameKind,
        command: Command,
        status: StatusCode,
        request_id: u64,
        payload: &[u8],
    ) -> Result<Vec<u8>, ProtocolError> {
        let mut bytes = Vec::new();
        self.encode_frame_into(kind, command, status, request_id, payload, &mut bytes)?;
        Ok(bytes)
    }

    fn encode_frame_into(
        &mut self,
        kind: FrameKind,
        command: Command,
        status: StatusCode,
        request_id: u64,
        payload: &[u8],
        bytes: &mut Vec<u8>,
    ) -> Result<(), ProtocolError> {
        bytes.clear();
        validate_command_payload(kind, command, status, payload)?;
        if payload.len() > MAX_PAYLOAD_BYTES {
            return Err(ProtocolError::PayloadTooLarge);
        }
        let sequence = self.next_send_sequence;
        self.next_send_sequence = sequence
            .checked_add(1)
            .ok_or(ProtocolError::SequenceExhausted)?;
        bytes.reserve(HEADER_BYTES + payload.len() + TAG_BYTES);
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
        let tag = hmac_sha256(&self.key, bytes);
        bytes.extend_from_slice(&tag);
        Ok(())
    }

    /// Authenticates, length-checks and replay-checks one complete packet before exposing payload.
    ///
    /// # Errors
    ///
    /// Returns an error for malformed, unauthenticated, unknown or replayed packets.
    pub fn decode(&mut self, bytes: &[u8]) -> Result<Frame, ProtocolError> {
        self.decode_view(bytes).map(Frame::from)
    }

    /// Verifies the same wire, shape, authentication and replay checks as [`Self::decode`],
    /// but borrows the payload instead of allocating a second packet-sized buffer.
    ///
    /// # Errors
    /// Returns an error for malformed, unauthenticated, unknown or replayed packets.
    pub fn decode_view<'a>(&mut self, bytes: &'a [u8]) -> Result<FrameView<'a>, ProtocolError> {
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
        Ok(FrameView {
            kind,
            command,
            status,
            request_id,
            sequence,
            payload: &bytes[HEADER_BYTES..signed_len],
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
        (FrameKind::Request, StatusCode::Ok, Command::KeyEvent | Command::PointerUp) => {
            payload.len() == 4
        }
        (FrameKind::Request, StatusCode::Ok, Command::CaptureChunk)
        | (FrameKind::Response, StatusCode::Ok, Command::GetWindowBounds) => payload.len() == 16,
        (FrameKind::Response, StatusCode::Ok, Command::Capture) => payload.len() == 32,
        (
            FrameKind::Response,
            StatusCode::BackendFailure,
            Command::Capture | Command::CaptureStream,
        ) => payload.is_empty() || CaptureFailure::decode(payload).is_some(),
        (FrameKind::Response, StatusCode::Ok, Command::CaptureChunk) => !payload.is_empty(),
        (FrameKind::Response, StatusCode::Ok, Command::CaptureStream) => {
            matches!(payload.first(), Some(0)) && matches!(payload.len(), 49 | 97)
                || matches!(payload.first(), Some(1))
                    && payload.len() > 5
                    && payload.len() <= 60 * 1024 + 5
                || CaptureTransferSummary::decode(payload).is_some()
        }
        (FrameKind::Request, StatusCode::Ok, Command::RecordingStart) => {
            recording::RecordingConfig::decode(payload).is_ok()
        }
        (FrameKind::Request, StatusCode::Ok, Command::RecordingFinish) => {
            recording::RecordingFinish::decode(payload).is_ok()
        }
        (FrameKind::Response, StatusCode::Ok, Command::RecordingFinish) => {
            recording::RecordingResult::decode(payload).is_ok()
        }
        (
            FrameKind::Request,
            StatusCode::Ok,
            Command::GetWindowBounds
            | Command::Ping
            | Command::Shutdown
            | Command::Capture
            | Command::CaptureStream,
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
    #[test]
    fn transfer_summary_is_fixed_bounded_and_authenticated() {
        let summary = super::CaptureTransferSummary {
            encode_us: 123,
            write_us: 456,
            packets: 60,
        };
        let payload = summary.encode();
        assert_eq!(
            super::CaptureTransferSummary::decode(&payload),
            Some(summary)
        );
        for length in 0..25 {
            assert!(super::CaptureTransferSummary::decode(&payload[..length]).is_none());
        }
        let mut sender = super::SecureChannel::new([39; 32]).unwrap();
        let mut receiver = super::SecureChannel::new([39; 32]).unwrap();
        let packet = sender
            .encode_response(
                super::Command::CaptureStream,
                2,
                super::StatusCode::Ok,
                &payload,
            )
            .unwrap();
        assert_eq!(receiver.decode(&packet).unwrap().payload, payload);
        assert!(receiver.decode(&packet).is_err());
        for invalid in [
            super::CaptureTransferSummary {
                packets: 0,
                ..summary
            },
            super::CaptureTransferSummary {
                packets: 1_094,
                ..summary
            },
            super::CaptureTransferSummary {
                encode_us: 10_000_001,
                ..summary
            },
            super::CaptureTransferSummary {
                write_us: 10_000_001,
                ..summary
            },
        ] {
            assert!(super::CaptureTransferSummary::decode(&invalid.encode()).is_none());
            assert!(sender
                .encode_response(
                    super::Command::CaptureStream,
                    2,
                    super::StatusCode::Ok,
                    &invalid.encode()
                )
                .is_err());
        }
    }
    #[test]
    fn capture_failures_accept_only_fixed_allowlisted_codes() {
        use super::{CaptureFailure, Command, SecureChannel, StatusCode};
        let mut sender = SecureChannel::new([18; 32]).unwrap();
        let mut receiver = SecureChannel::new([18; 32]).unwrap();
        for reason in [
            CaptureFailure::Deadline,
            CaptureFailure::ProcessStart,
            CaptureFailure::PipeRead,
            CaptureFailure::ProcessExit,
            CaptureFailure::InvalidFrame,
            CaptureFailure::IdExhausted,
        ] {
            let bytes = (reason as u32).to_le_bytes();
            assert_eq!(CaptureFailure::decode(&bytes), Some(reason));
            let packet = sender
                .encode_response(
                    Command::CaptureStream,
                    2,
                    StatusCode::BackendFailure,
                    &bytes,
                )
                .unwrap();
            assert_eq!(receiver.decode(&packet).unwrap().payload, bytes);
        }
        for payload in [&[0, 0, 0, 0][..], &[8, 0, 0, 0], &[1], &[1, 0, 0, 0, 0]] {
            assert!(sender
                .encode_response(
                    Command::CaptureStream,
                    2,
                    StatusCode::BackendFailure,
                    payload
                )
                .is_err());
        }
        assert!(sender
            .encode_response(
                Command::Tap,
                2,
                StatusCode::BackendFailure,
                &1_u32.to_le_bytes()
            )
            .is_err());
    }
    use super::StatusCode;
    use super::{
        hmac_sha256, Capabilities, Command, HandshakeError, Hello, PeerIdentity, ProtocolError,
        SecureChannel, PROTOCOL_VERSION,
    };

    #[test]
    fn reusable_response_encoding_is_wire_identical_and_failures_clear_storage() {
        let key = [73; 32];
        let mut owned = SecureChannel::new(key).unwrap();
        let mut reused = SecureChannel::new(key).unwrap();
        let mut bytes = Vec::with_capacity(super::MAX_PAYLOAD_BYTES + 64);
        let address = bytes.as_ptr();
        for length in [60 * 1024 + 5, 9, 6, 60 * 1024 + 5] {
            let mut payload = vec![7; length];
            payload[0] = 1;
            let expected = owned
                .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &payload)
                .unwrap();
            reused
                .encode_response_into(
                    Command::CaptureStream,
                    2,
                    StatusCode::Ok,
                    &payload,
                    &mut bytes,
                )
                .unwrap();
            assert_eq!(bytes, expected);
            assert_eq!(bytes.as_ptr(), address);
            assert!(reused
                .encode_response_into(
                    Command::CaptureStream,
                    2,
                    StatusCode::Ok,
                    &[1; 5],
                    &mut bytes
                )
                .is_err());
            assert!(bytes.is_empty());
        }
        reused.next_send_sequence = u64::MAX;
        assert_eq!(
            reused.encode_response_into(Command::Ping, 3, StatusCode::Ok, &[], &mut bytes),
            Err(ProtocolError::SequenceExhausted)
        );
        assert!(bytes.is_empty());
    }

    #[test]
    #[ignore = "explicit synthetic packet encoder benchmark, not Android capture"]
    fn benchmark_capture_send_buffers() {
        let pixels = vec![37; 720 * 1280 * 4];
        let mut payload = Vec::new();
        let mut packet = Vec::new();
        let mut times = [Vec::new(), Vec::new()];
        for iteration in 0..100 {
            for variant in if iteration % 2 == 0 { [0, 1] } else { [1, 0] } {
                let mut channel = SecureChannel::new([74; 32]).unwrap();
                let start = std::time::Instant::now();
                for (index, chunk) in pixels.chunks(60 * 1024).enumerate() {
                    let offset = u32::try_from(index * 60 * 1024).unwrap();
                    if variant == 0 {
                        let mut copied_chunk = chunk.to_vec();
                        let mut prefixed = Vec::with_capacity(chunk.len() + 5);
                        prefixed.push(1);
                        prefixed.extend_from_slice(&offset.to_le_bytes());
                        prefixed.append(&mut copied_chunk);
                        let encoded = channel
                            .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &prefixed)
                            .unwrap();
                        std::hint::black_box(encoded);
                    } else {
                        payload.clear();
                        payload.push(1);
                        payload.extend_from_slice(&offset.to_le_bytes());
                        payload.extend_from_slice(chunk);
                        channel
                            .encode_response_into(
                                Command::CaptureStream,
                                2,
                                StatusCode::Ok,
                                &payload,
                                &mut packet,
                            )
                            .unwrap();
                        std::hint::black_box(&packet);
                    }
                }
                times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
            }
        }
        for (name, mut values) in ["allocated", "reused"].into_iter().zip(times) {
            values.sort_by(f64::total_cmp);
            println!("capture-send-{name}: host={}, payload=720x1280x4, chunks=60x60KiB, HMAC-SHA256, N=100, P50={:.3} P95={:.3} P99={:.3} ms", std::env::consts::ARCH, values[49], values[94], values[98]);
        }
    }

    #[test]
    fn borrowed_decode_matches_owned_checks_and_points_into_authenticated_packet() {
        let key = [72; 32];
        let mut sender = SecureChannel::new(key).unwrap();
        let mut owned = SecureChannel::new(key).unwrap();
        let mut borrowed = SecureChannel::new(key).unwrap();
        for size in [6, 49, 60 * 1024 + 5] {
            let mut payload = vec![77; size];
            payload[0] = 1;
            let packet = sender
                .encode_response(Command::CaptureStream, 25, StatusCode::Ok, &payload)
                .unwrap();
            let frame = borrowed.decode_view(&packet).unwrap();
            assert_eq!(frame.payload.as_ptr(), packet[32..].as_ptr());
            assert_eq!(super::Frame::from(frame), owned.decode(&packet).unwrap());
            assert!(matches!(
                borrowed.decode_view(&packet),
                Err(ProtocolError::UnexpectedSequence { .. })
            ));
        }
        let packet = sender.encode(Command::Tap, 26, &[0; 8]).unwrap();
        let mut tampered = packet.clone();
        tampered[33] ^= 1;
        assert_eq!(
            borrowed.decode_view(&tampered),
            Err(ProtocolError::AuthenticationFailed)
        );
        assert_eq!(
            super::Frame::from(borrowed.decode_view(&packet).unwrap()),
            owned.decode(&packet).unwrap()
        );
    }

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

    #[test]
    fn capture_stream_keeps_packet_shapes_authentication_and_replay_protection() {
        let mut sender = SecureChannel::new([48; 32]).unwrap();
        let mut receiver = SecureChannel::new([48; 32]).unwrap();
        assert!(sender
            .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &[0; 48])
            .is_err());
        assert!(sender
            .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &[1; 5])
            .is_err());
        let mut metadata = vec![0; 49];
        let packet = sender
            .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &metadata)
            .unwrap();
        assert_eq!(receiver.decode(&packet).unwrap().payload, metadata);
        assert!(receiver.decode(&packet).is_err());
        metadata[0] = 1;
        let mut packet = sender
            .encode_response(Command::CaptureStream, 2, StatusCode::Ok, &metadata)
            .unwrap();
        packet[40] ^= 1;
        assert!(receiver.decode(&packet).is_err());
    }
}
