//! Authenticated, request-correlated client for the privileged `RootDaemon`.

use root_daemon::TransportError;
use root_protocol::{Capabilities, Command, FrameKind, ProtocolError, SecureChannel, StatusCode};

pub mod priority;
pub use priority::{PriorityStop, StopStatus};

#[cfg(test)]
mod capture_transfer_tests;

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
    pub timings: CaptureTimings,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawRegionCapture {
    pub capture: RawCapture,
    pub geometry: root_protocol::capture_region::CaptureRegionMetadata,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct CaptureTimings {
    /// 0: legacy peer; 1: bounded screencap; 2: persistent `SurfaceControl` bridge.
    pub backend: u32,
    pub acquisition_us: u64,
    pub transfer_us: u64,
    pub streamed: bool,
    pub pipelined: bool,
    /// Optional system capture, software readback, pixel read, encode, bridge write and receive.
    /// Receive overlaps bridge processing; do not add these to the total or each other.
    pub stages_us: Option<[u64; 6]>,
    pub transfer_details: Option<CaptureTransferTimings>,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct CaptureTransferTimings {
    pub encode_us: u64,
    pub write_us: u64,
    pub receive_us: u64,
    pub verify_us: u64,
    pub append_us: u64,
    pub packets: u32,
}

struct CaptureStreamMetadata {
    region: Option<root_protocol::capture_region::CaptureRegionMetadata>,
    capture_id: u64,
    pipelined: bool,
    width: u32,
    height: u32,
    row_stride: u32,
    length: usize,
    format: RawCaptureFormat,
    backend: u32,
    acquisition_us: u64,
    stages_us: Option<[u64; 6]>,
}

impl CaptureStreamMetadata {
    fn commit(
        &mut self,
        bytes: &[u8],
    ) -> Result<root_protocol::CaptureTransferSummary, ClientError> {
        if self.pipelined {
            let completion = root_protocol::CapturePipelineCompletion::decode(bytes)
                .filter(|c| c.capture_id == self.capture_id && c.pixel_bytes == self.length as u64)
                .ok_or(ClientError::InvalidCapture)?;
            self.acquisition_us = completion.acquisition_us;
            self.stages_us = Some(completion.stages_us);
            Ok(completion.transfer)
        } else {
            root_protocol::CaptureTransferSummary::decode(bytes).ok_or(ClientError::InvalidCapture)
        }
    }
    fn decode(metadata: &[u8], capabilities: Capabilities) -> Result<Self, ClientError> {
        let region = if metadata.first() == Some(&5) {
            if !capabilities.supports(Capabilities::CAPTURE_REGION_REQUIREMENTS)
                || !matches!(metadata.len(), 81 | 129)
            {
                return Err(ClientError::InvalidCapture);
            }
            Some(
                root_protocol::capture_region::CaptureRegionMetadata::decode(
                    &metadata[metadata.len() - 32..],
                )
                .ok_or(ClientError::InvalidCapture)?,
            )
        } else {
            None
        };
        let metadata = if region.is_some() {
            &metadata[..metadata.len() - 32]
        } else {
            metadata
        };
        let pipelined = metadata.first() == Some(&3);
        if pipelined
            && (!capabilities.supports(Capabilities::CAPTURE_PIPELINE_REQUIREMENTS)
                || metadata.len() != 49)
        {
            return Err(ClientError::InvalidCapture);
        }
        if !matches!(metadata.len(), 49 | 97)
            || (!pipelined && metadata[0] != if region.is_some() { 5 } else { 0 })
        {
            return Err(ClientError::InvalidCapture);
        }
        let acquisition_us = read_u64(metadata, 33)?;
        let backend = read_u32(metadata, 41)?;
        if acquisition_us > 10_000_000
            || !(1..=2).contains(&backend)
            || metadata[45..49] != [0; 4]
            || pipelined && (acquisition_us != 0 || backend != 2)
        {
            return Err(ClientError::InvalidCapture);
        }
        let stages_us = if metadata.len() == 97 {
            if backend != 2 || !capabilities.supports(Capabilities::CAPTURE_STAGE_TIMINGS) {
                return Err(ClientError::InvalidCapture);
            }
            let mut stages = [0; 6];
            for (index, value) in stages.iter_mut().enumerate() {
                *value = read_u64(metadata, 49 + index * 8)?;
                if *value > 10_000_000 {
                    return Err(ClientError::InvalidCapture);
                }
            }
            Some(stages)
        } else {
            None
        };
        let base = &metadata[1..33];
        let width = read_u32(base, 8)?;
        let height = read_u32(base, 12)?;
        let row_stride = read_u32(base, 16)?;
        let length =
            usize::try_from(read_u64(base, 24)?).map_err(|_| ClientError::CaptureTooLarge)?;
        if read_u64(base, 0)? == 0
            || width == 0
            || height == 0
            || row_stride < width.saturating_mul(4)
            || base[21..24] != [0; 3]
            || length == 0
            || length > MAX_CAPTURE_BYTES
            || u64::from(row_stride) * u64::from(height) != length as u64
        {
            return Err(ClientError::InvalidCapture);
        }
        let format = match base[20] {
            1 => RawCaptureFormat::Rgba8888,
            2 => RawCaptureFormat::Bgra8888,
            _ => return Err(ClientError::InvalidCapture),
        };
        if region.is_some_and(|r| {
            !r.matches_dimensions(width, height) || r.regional_readback != (backend == 2)
        }) {
            return Err(ClientError::InvalidCapture);
        }
        Ok(Self {
            region,
            capture_id: read_u64(base, 0)?,
            pipelined,
            width,
            height,
            row_stride,
            length,
            format,
            backend,
            acquisition_us,
            stages_us,
        })
    }
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

    /// Optional allocation-reusing receive path; old transports retain their existing behavior.
    ///
    /// # Errors
    /// Returns a transport error. No partial packet remains valid after failure.
    fn receive_packet_into(&mut self, packet: &mut Vec<u8>) -> Result<(), TransportError> {
        packet.clear();
        *packet = self.receive_packet()?;
        Ok(())
    }
}

#[cfg(target_os = "android")]
impl PacketTransport for std::os::unix::net::UnixStream {
    fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        root_daemon::write_packet(self, packet)
    }

    fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
        root_daemon::read_packet(self)
    }

    fn receive_packet_into(&mut self, packet: &mut Vec<u8>) -> Result<(), TransportError> {
        root_daemon::read_packet_into(self, packet)
    }
}

#[derive(Debug)]
pub enum ClientError {
    Transport(TransportError),
    Protocol(ProtocolError),
    UnexpectedResponse,
    RequestIdExhausted,
    Remote(StatusCode),
    CaptureFailed(root_protocol::CaptureFailure),
    InputFailed(root_protocol::InputFailure),
    InvalidDuration,
    InvalidPointerId,
    UnsupportedCapability,
    InvalidRecording,
    InvalidCapture,
    CaptureTooLarge,
    Closed,
    IncompleteCapture(Box<ClientError>),
}

impl ClientError {
    /// Content-free input reason; never expose raw transport/process diagnostics.
    #[must_use]
    pub const fn input_failure_code(&self) -> &'static str {
        match self {
            Self::InputFailed(reason) => reason.code(),
            Self::Transport(_) => "ROOT_INPUT_TRANSPORT",
            Self::Protocol(_) => "ROOT_INPUT_PROTOCOL",
            Self::UnexpectedResponse => "ROOT_INPUT_CORRELATION",
            Self::Remote(StatusCode::Cancelled) => "ROOT_INPUT_CANCELLED",
            Self::Remote(StatusCode::BackendUnavailable) => "ROOT_INPUT_BACKEND_UNAVAILABLE",
            Self::Closed => "ROOT_INPUT_CONNECTION_CLOSED",
            _ => "ROOT_INPUT_FAILED",
        }
    }
    /// Stable, non-sensitive capture diagnostics; never format arbitrary transport text.
    #[must_use]
    pub fn capture_failure_code(&self) -> &'static str {
        match self {
            Self::IncompleteCapture(error) => error.capture_failure_code(),
            Self::CaptureFailed(reason) => reason.code(),
            Self::Transport(_) => "CAPTURE_TRANSPORT",
            Self::Protocol(_) => "CAPTURE_PROTOCOL",
            Self::UnexpectedResponse => "CAPTURE_CORRELATION",
            Self::Remote(StatusCode::Cancelled) => "CAPTURE_CANCELLED",
            Self::Remote(StatusCode::BackendUnavailable) => "CAPTURE_BACKEND_UNAVAILABLE",
            Self::Remote(_) => "CAPTURE_BACKEND_FAILED",
            Self::InvalidCapture => "CAPTURE_INVALID_FRAME",
            Self::CaptureTooLarge => "CAPTURE_BUDGET",
            Self::Closed => "CAPTURE_CONNECTION_CLOSED",
            _ => "CAPTURE_REQUEST_FAILED",
        }
    }

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
                | Self::IncompleteCapture(_)
                | Self::CaptureFailed(root_protocol::CaptureFailure::ControlUnavailable)
                | Self::InputFailed(root_protocol::InputFailure::ControlUnavailable)
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
    priority: Option<PriorityStop>,
    receive_buffer: Vec<u8>,
}

impl<T> Drop for RootClient<T> {
    fn drop(&mut self) {
        if let Some(control) = self.priority.as_ref() {
            control.close();
        }
    }
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
        Self::connect_with_required(
            transport,
            key,
            requested_capabilities,
            requested_capabilities.bits(),
        )
    }

    /// Connects while allowing desired capabilities to be absent from an older Android backend.
    ///
    /// # Errors
    ///
    /// Returns an error when any required capability is not negotiated.
    pub fn connect_with_required(
        transport: T,
        key: [u8; 32],
        requested_capabilities: Capabilities,
        required_capabilities: u64,
    ) -> Result<Self, ClientError> {
        let mut client = Self {
            transport,
            channel: SecureChannel::new(key)?,
            next_request_id: 1,
            negotiated_capabilities: Capabilities::from_bits(0),
            closed: false,
            priority: None,
            receive_buffer: Vec::new(),
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
        if !negotiated.supports(required_capabilities) {
            return Err(ClientError::UnexpectedResponse);
        }
        client.negotiated_capabilities = negotiated;
        Ok(client)
    }

    #[must_use]
    pub const fn negotiated_capabilities(&self) -> Capabilities {
        self.negotiated_capabilities
    }
    pub fn priority_stop(&self) -> Option<PriorityStop> {
        self.priority.clone()
    }

    /// Editor backend flags, derived only from the authenticated handshake.
    #[must_use]
    pub fn input_features(&self) -> u32 {
        u32::from(
            self.negotiated_capabilities
                .supports(Capabilities::INPUT_BASIC),
        ) | (u32::from(
            self.negotiated_capabilities
                .supports(Capabilities::INPUT_POINTER_SINGLE),
        ) << 1)
            | (u32::from(
                self.negotiated_capabilities
                    .supports(Capabilities::INPUT_RECORDING)
                    && self.priority.is_some(),
            ) << 2)
            | (u32::from(
                self.negotiated_capabilities
                    .supports(Capabilities::INPUT_MULTI_TOUCH),
            ) << 3)
    }

    /// Starts a bounded real evdev capture. No file paths or command strings cross this boundary.
    ///
    /// # Errors
    /// Rejects invalid limits, unavailable recording capability, busy/stopped sessions
    /// or any authenticated transport/daemon failure.
    pub fn begin_recording(
        &mut self,
        config: root_protocol::recording::RecordingConfig,
    ) -> Result<(), ClientError> {
        config
            .validate()
            .map_err(|_| ClientError::InvalidRecording)?;
        if config.deadline_success
            && !self
                .negotiated_capabilities
                .supports(Capabilities::INPUT_RECORDING_TIMED)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        if !self
            .negotiated_capabilities
            .supports(Capabilities::INPUT_RECORDING)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        let priority = self
            .priority
            .clone()
            .ok_or(ClientError::UnsupportedCapability)?;
        {
            let mut gate = priority
                .shared()
                .gate
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if gate.recording_token != 0 || gate.status != StopStatus::Ready || gate.closed {
                return Err(ClientError::Remote(StatusCode::Cancelled));
            }
            gate.recording_token = config.token;
        }
        let result = self.transact_empty(Command::RecordingStart, &config.encode());
        if result.is_err() {
            let mut gate = priority
                .shared()
                .gate
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if gate.recording_token == config.token {
                gate.recording_token = 0;
            }
        }
        result
    }

    #[cfg(any(target_os = "android", test))]
    fn finish_recording_control(
        &mut self,
        finish: root_protocol::recording::RecordingFinish,
    ) -> Result<root_protocol::recording::RecordingResult, ClientError> {
        if !self
            .negotiated_capabilities
            .supports(Capabilities::INPUT_RECORDING)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        let bytes = self.transact(Command::RecordingFinish, &finish.encode())?;
        let result = root_protocol::recording::RecordingResult::decode(&bytes)
            .map_err(|_| ClientError::InvalidRecording)?;
        if result.token != finish.token {
            return Err(ClientError::InvalidRecording);
        }
        Ok(result)
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

    /// Starts one single-pointer gesture in physical display coordinates.
    ///
    /// # Errors
    ///
    /// Returns an error for a non-zero pointer ID or a rejected daemon command.
    pub fn pointer_down(&mut self, pointer_id: u8, x: i32, y: i32) -> Result<(), ClientError> {
        self.pointer_position(Command::PointerDown, pointer_id, x, y)
    }

    /// Moves the active single pointer.
    ///
    /// # Errors
    ///
    /// Returns an error for a non-zero pointer ID or a rejected daemon command.
    pub fn pointer_move(&mut self, pointer_id: u8, x: i32, y: i32) -> Result<(), ClientError> {
        self.pointer_position(Command::PointerMove, pointer_id, x, y)
    }

    /// Releases the active single pointer at its last daemon-tracked position.
    ///
    /// # Errors
    ///
    /// Returns an error for a non-zero pointer ID or a rejected daemon command.
    pub fn pointer_up(&mut self, pointer_id: u8) -> Result<(), ClientError> {
        if pointer_id > 4 {
            return Err(ClientError::InvalidPointerId);
        }
        if pointer_id != 0
            && !self
                .negotiated_capabilities
                .supports(Capabilities::INPUT_MULTI_TOUCH)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        if !self
            .negotiated_capabilities
            .supports(Capabilities::INPUT_POINTER_SINGLE)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        self.transact_empty(Command::PointerUp, &[pointer_id, 0, 0, 0])
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
        if self
            .negotiated_capabilities
            .supports(Capabilities::CAPTURE_STREAM)
        {
            let result = self.capture_stream();
            if matches!(result, Err(ClientError::Remote(StatusCode::Cancelled))) {
                // Before metadata there is no stream to drain; keep the priority ACK alive.
                return result;
            }
            if result.is_err() {
                // Never let a partial stream be interpreted as a reply to the next request.
                self.closed = true;
            }
            return result.map_err(incomplete_capture);
        }
        let started = std::time::Instant::now();
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
        // Keep one packet buffer in the client and write each chunk into the final
        // allocation directly.  The old path allocated a temporary Vec for every
        // CaptureChunk response, which was particularly visible on large screens.
        let mut pixels = vec![0; byte_length];
        let mut request = Vec::with_capacity(16);
        let mut offset_usize = 0usize;
        while offset_usize < byte_length {
            let remaining = byte_length - offset_usize;
            let requested_length = remaining.min(CAPTURE_CHUNK_BYTES);
            let offset = u32::try_from(offset_usize).map_err(|_| ClientError::CaptureTooLarge)?;
            let requested =
                u32::try_from(requested_length).map_err(|_| ClientError::CaptureTooLarge)?;
            request.clear();
            request.extend_from_slice(&capture_id.to_le_bytes());
            request.extend_from_slice(&offset.to_le_bytes());
            request.extend_from_slice(&requested.to_le_bytes());
            let request_id = self.send_request(Command::CaptureChunk, &request)?;
            self.receive_capture_chunk(
                request_id,
                requested_length,
                &mut pixels[offset_usize..offset_usize + requested_length],
            )?;
            offset_usize += requested_length;
        }
        self.transact_empty(Command::ReleaseCapture, &capture_id.to_le_bytes())?;
        Ok(RawCapture {
            width,
            height,
            row_stride,
            format,
            pixels,
            timings: CaptureTimings {
                transfer_us: elapsed_us(started),
                ..CaptureTimings::default()
            },
        })
    }

    fn capture_stream(&mut self) -> Result<RawCapture, ClientError> {
        let started = std::time::Instant::now();
        let request_id = self.send_request(Command::CaptureStream, &[])?;
        let metadata = self.receive_response(Command::CaptureStream, request_id)?;
        self.capture_stream_data(started, request_id, &metadata, None)
            .map_err(incomplete_capture)
    }

    /// Fresh region pixels; unsupported peers are rejected without changing capture semantics.
    /// # Errors
    /// Rejects unsupported capability, invalid geometry, correlation or incomplete transfer.
    pub fn capture_region(
        &mut self,
        region: root_protocol::capture_region::CaptureRegionRequest,
    ) -> Result<RawRegionCapture, ClientError> {
        if !region.valid() {
            return Err(ClientError::InvalidCapture);
        }
        if !self
            .negotiated_capabilities
            .supports(Capabilities::CAPTURE_REGION_REQUIREMENTS)
        {
            return Err(ClientError::Remote(StatusCode::BackendUnavailable));
        }
        let result = (|| {
            let started = std::time::Instant::now();
            let request_id = self.send_request(Command::CaptureStream, &region.encode())?;
            let metadata = self.receive_response(Command::CaptureStream, request_id)?;
            let geometry = CaptureStreamMetadata::decode(&metadata, self.negotiated_capabilities)?
                .region
                .filter(|g| g.request == region)
                .ok_or(ClientError::InvalidCapture)?;
            let capture = self
                .capture_stream_data(started, request_id, &metadata, Some(region))
                .map_err(incomplete_capture)?;
            Ok(RawRegionCapture { capture, geometry })
        })();
        if matches!(result, Err(ClientError::Remote(StatusCode::Cancelled))) {
            return result;
        }
        if result.is_err() {
            self.closed = true;
        }
        result.map_err(incomplete_capture)
    }

    fn capture_stream_data(
        &mut self,
        started: std::time::Instant,
        request_id: u64,
        metadata: &[u8],
        region: Option<root_protocol::capture_region::CaptureRegionRequest>,
    ) -> Result<RawCapture, ClientError> {
        let mut metadata = CaptureStreamMetadata::decode(metadata, self.negotiated_capabilities)?;
        if metadata.region.map(|r| r.request) != region {
            return Err(ClientError::InvalidCapture);
        }
        let CaptureStreamMetadata {
            pipelined,
            width,
            height,
            row_stride,
            length,
            format,
            backend,
            ..
        } = metadata;
        let transfer_started = std::time::Instant::now();
        let observe_transfer = self
            .negotiated_capabilities
            .supports(Capabilities::CAPTURE_TRANSFER_TIMINGS);
        let mut details = CaptureTransferTimings::default();
        // Append only authenticated, contiguous chunks; do not zero the whole frame first.
        let mut pixels = Vec::with_capacity(length);
        let mut received = 0usize;
        while received < length {
            if started.elapsed() >= std::time::Duration::from_secs(10) {
                return Err(ClientError::InvalidCapture);
            }
            let stage_started = observe_transfer.then(std::time::Instant::now);
            self.transport
                .receive_packet_into(&mut self.receive_buffer)?;
            if let Some(started) = stage_started {
                details.receive_us = details.receive_us.saturating_add(elapsed_us(started));
            }
            let stage_started = observe_transfer.then(std::time::Instant::now);
            let frame = self.channel.decode_view(&self.receive_buffer)?;
            if frame.kind != FrameKind::Response
                || frame.command != Command::CaptureStream
                || frame.request_id != request_id
            {
                return Err(ClientError::UnexpectedResponse);
            }
            if frame.status != StatusCode::Ok {
                return Err(capture_error(
                    self.negotiated_capabilities,
                    frame.status,
                    frame.payload,
                ));
            }
            let payload = frame.payload;
            let chunk_length = (length - received).min(CAPTURE_CHUNK_BYTES);
            if payload.len() <= 5
                || payload[0] != 1
                || usize::try_from(read_u32(payload, 1)?).ok() != Some(received)
                || payload.len() - 5 != chunk_length
            {
                return Err(ClientError::InvalidCapture);
            }
            if let Some(started) = stage_started {
                details.verify_us = details.verify_us.saturating_add(elapsed_us(started));
            }
            let stage_started = observe_transfer.then(std::time::Instant::now);
            pixels.extend_from_slice(&payload[5..]);
            received += chunk_length;
            if let Some(started) = stage_started {
                details.append_us = details.append_us.saturating_add(elapsed_us(started));
                details.packets += 1;
            }
        }
        if observe_transfer {
            if started.elapsed() >= std::time::Duration::from_secs(10) {
                return Err(ClientError::InvalidCapture);
            }
            let summary = self.receive_response(Command::CaptureStream, request_id)?;
            if started.elapsed() >= std::time::Duration::from_secs(10) {
                return Err(ClientError::InvalidCapture);
            }
            let summary = metadata.commit(&summary)?;
            if summary.packets != u64::from(details.packets) {
                return Err(ClientError::InvalidCapture);
            }
            details.encode_us = summary.encode_us;
            details.write_us = summary.write_us;
        }
        Ok(RawCapture {
            width,
            height,
            row_stride,
            format,
            pixels,
            timings: CaptureTimings {
                backend,
                acquisition_us: metadata.acquisition_us,
                transfer_us: elapsed_us(transfer_started),
                streamed: true,
                pipelined,
                stages_us: metadata.stages_us,
                transfer_details: observe_transfer.then_some(details),
            },
        })
    }

    /// Receives one capture chunk without allocating a temporary response Vec.
    fn receive_capture_chunk(
        &mut self,
        request_id: u64,
        expected_length: usize,
        destination: &mut [u8],
    ) -> Result<(), ClientError> {
        if destination.len() != expected_length {
            return Err(ClientError::InvalidCapture);
        }
        self.transport
            .receive_packet_into(&mut self.receive_buffer)?;
        let frame = self.channel.decode_view(&self.receive_buffer)?;
        if frame.kind != FrameKind::Response
            || frame.command != Command::CaptureChunk
            || frame.request_id != request_id
        {
            return Err(ClientError::UnexpectedResponse);
        }
        if frame.status != StatusCode::Ok {
            return Err(capture_error(
                self.negotiated_capabilities,
                frame.status,
                frame.payload,
            ));
        }
        if frame.payload.len() != expected_length {
            return Err(ClientError::InvalidCapture);
        }
        destination.copy_from_slice(frame.payload);
        Ok(())
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

    fn pointer_position(
        &mut self,
        command: Command,
        pointer_id: u8,
        x: i32,
        y: i32,
    ) -> Result<(), ClientError> {
        if pointer_id > 4 {
            return Err(ClientError::InvalidPointerId);
        }
        if pointer_id != 0
            && !self
                .negotiated_capabilities
                .supports(Capabilities::INPUT_MULTI_TOUCH)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        if !self
            .negotiated_capabilities
            .supports(Capabilities::INPUT_POINTER_SINGLE)
        {
            return Err(ClientError::UnsupportedCapability);
        }
        let mut payload = Vec::with_capacity(12);
        payload.extend_from_slice(&[pointer_id, 0, 0, 0]);
        payload.extend_from_slice(&x.to_le_bytes());
        payload.extend_from_slice(&y.to_le_bytes());
        self.transact_empty(command, &payload)
    }

    fn transact(&mut self, command: Command, payload: &[u8]) -> Result<Vec<u8>, ClientError> {
        if command == Command::PointerUp
            && !self.closed
            && self.priority.as_ref().is_some_and(|priority| {
                priority
                    .shared()
                    .gate
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .status
                    == StopStatus::Clean
            })
        {
            return Ok(Vec::new());
        }
        let request_id = self.send_request(command, payload)?;
        self.receive_response(command, request_id)
    }

    fn send_request(&mut self, command: Command, payload: &[u8]) -> Result<u64, ClientError> {
        if self.closed {
            return Err(ClientError::Closed);
        }
        let request_id = self.next_request_id;
        self.next_request_id = request_id
            .checked_add(1)
            .ok_or(ClientError::RequestIdExhausted)?;
        if matches!(
            command,
            Command::Tap
                | Command::Swipe
                | Command::KeyEvent
                | Command::PointerDown
                | Command::PointerMove
                | Command::PointerUp
                | Command::RecordingStart
                | Command::Capture
                | Command::CaptureStream
        ) {
            if let Some(priority) = self.priority.as_ref() {
                let mut gate = priority
                    .shared()
                    .gate
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                let recording_start = command == Command::RecordingStart
                    && payload
                        .get(..8)
                        .and_then(|token| token.try_into().ok())
                        .map(u64::from_le_bytes)
                        == Some(gate.recording_token);
                if gate.status != StopStatus::Ready
                    || (gate.recording_token != 0
                        && !recording_start
                        && !matches!(command, Command::Capture | Command::CaptureStream))
                {
                    // Priority ACK already released every old pointer; ordinary cleanup is idempotent.
                    if gate.status == StopStatus::Failed
                        && matches!(command, Command::Capture | Command::CaptureStream)
                    {
                        return Err(ClientError::CaptureFailed(
                            root_protocol::CaptureFailure::ControlUnavailable,
                        ));
                    }
                    return Err(ClientError::Remote(if gate.status == StopStatus::Failed {
                        StatusCode::BackendFailure
                    } else {
                        StatusCode::Cancelled
                    }));
                }
                gate.last_input = request_id;
                let packet = self.channel.encode(command, request_id, payload)?;
                self.transport.send_packet(&packet)?;
            } else {
                let packet = self.channel.encode(command, request_id, payload)?;
                self.transport.send_packet(&packet)?;
            }
        } else {
            let packet = self.channel.encode(command, request_id, payload)?;
            self.transport.send_packet(&packet)?;
        }
        Ok(request_id)
    }

    fn receive_response(
        &mut self,
        command: Command,
        request_id: u64,
    ) -> Result<Vec<u8>, ClientError> {
        self.transport
            .receive_packet_into(&mut self.receive_buffer)?;
        let frame = self.channel.decode_view(&self.receive_buffer)?;
        if frame.kind != FrameKind::Response
            || frame.command != command
            || frame.request_id != request_id
        {
            return Err(ClientError::UnexpectedResponse);
        }
        if frame.status != StatusCode::Ok {
            if matches!(
                command,
                Command::Tap
                    | Command::Swipe
                    | Command::KeyEvent
                    | Command::PointerDown
                    | Command::PointerMove
                    | Command::PointerUp
            ) && !frame.payload.is_empty()
            {
                if !self
                    .negotiated_capabilities
                    .supports(Capabilities::INPUT_FAILURE_DETAILS)
                {
                    return Err(ClientError::UnexpectedResponse);
                }
                return Err(root_protocol::InputFailure::decode(frame.payload)
                    .map_or(ClientError::UnexpectedResponse, ClientError::InputFailed));
            }
            if matches!(command, Command::Capture | Command::CaptureStream)
                && !frame.payload.is_empty()
            {
                return Err(capture_error(
                    self.negotiated_capabilities,
                    frame.status,
                    frame.payload,
                ));
            }
            return Err(ClientError::Remote(frame.status));
        }
        Ok(frame.payload.to_vec())
    }
}

fn incomplete_capture(error: ClientError) -> ClientError {
    match error {
        ClientError::IncompleteCapture(_) => error,
        _ => ClientError::IncompleteCapture(Box::new(error)),
    }
}

fn elapsed_us(started: std::time::Instant) -> u64 {
    u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX)
}

fn capture_error(capabilities: Capabilities, status: StatusCode, payload: &[u8]) -> ClientError {
    if payload.is_empty() {
        return ClientError::Remote(status);
    }
    if !capabilities.supports(Capabilities::CAPTURE_FAILURE_DETAILS) {
        return ClientError::UnexpectedResponse;
    }
    let Some(reason) = root_protocol::CaptureFailure::decode(payload) else {
        return ClientError::InvalidCapture;
    };
    if reason == root_protocol::CaptureFailure::ControlUnavailable
        && !capabilities.supports(Capabilities::CAPTURE_CONTROL_DETAILS)
    {
        return ClientError::UnexpectedResponse;
    }
    ClientError::CaptureFailed(reason)
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

#[cfg(any(target_os = "android", test))]
fn default_android_capabilities() -> Capabilities {
    // Pipeline negotiation stays opt-in until complete-workflow tail gains are stable.
    Capabilities::from_bits(
        Capabilities::INPUT_BASIC
            | Capabilities::INPUT_POINTER_SINGLE
            | Capabilities::INPUT_MULTI_TOUCH
            | Capabilities::INPUT_PRIORITY_STOP
            | Capabilities::CAPTURE_RAW
            | Capabilities::CAPTURE_STREAM
            | Capabilities::CAPTURE_STAGE_TIMINGS
            | Capabilities::CAPTURE_FAILURE_DETAILS
            | Capabilities::CAPTURE_TRANSFER_TIMINGS
            | Capabilities::CAPTURE_CONTROL_DETAILS
            | Capabilities::CAPTURE_REGION
            | Capabilities::INPUT_RECORDING_TIMED
            | Capabilities::INPUT_FAILURE_DETAILS
            | Capabilities::INPUT_RECORDING,
    )
}

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
        .set_write_timeout(Some(timeout.min(std::time::Duration::from_secs(2))))
        .map_err(|error| ClientError::Transport(TransportError::Io(error)))?;
    let mut client = RootClient::connect_with_required(
        stream,
        key,
        default_android_capabilities(),
        Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW,
    )?;
    if client
        .negotiated_capabilities()
        .supports(Capabilities::INPUT_PRIORITY_STOP)
    {
        let control = std::os::unix::net::UnixStream::connect(socket_path.with_extension("ctl"))
            .map_err(|e| ClientError::Transport(TransportError::Io(e)))?;
        control
            .set_read_timeout(Some(std::time::Duration::from_secs(8)))
            .map_err(|e| ClientError::Transport(TransportError::Io(e)))?;
        control
            .set_write_timeout(Some(std::time::Duration::from_secs(2)))
            .map_err(|e| ClientError::Transport(TransportError::Io(e)))?;
        let control = RootClient::connect_with_required(
            control,
            root_protocol::priority_control_key(&key),
            Capabilities::from_bits(
                Capabilities::INPUT_PRIORITY_STOP | Capabilities::INPUT_RECORDING,
            ),
            Capabilities::INPUT_PRIORITY_STOP,
        )?;
        client.priority = Some(
            priority::spawn(control).map_err(|e| ClientError::Transport(TransportError::Io(e)))?,
        );
    }
    Ok(client)
}

#[cfg(test)]
mod tests {
    #[test]
    fn input_details_require_negotiation_and_are_not_retried() {
        use root_protocol::SecureChannel;
        struct FailureTransport {
            peer: SecureChannel,
            response: Vec<u8>,
            details: bool,
            taps: usize,
        }
        impl PacketTransport for FailureTransport {
            fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
                let frame = self.peer.decode(packet).unwrap();
                let (status, payload) = if frame.command == Command::Hello {
                    (
                        StatusCode::Ok,
                        (Capabilities::INPUT_BASIC
                            | if self.details {
                                Capabilities::INPUT_FAILURE_DETAILS
                            } else {
                                0
                            })
                        .to_le_bytes()
                        .to_vec(),
                    )
                } else {
                    self.taps += 1;
                    (
                        StatusCode::BackendUnavailable,
                        (root_protocol::InputFailure::Deadline as u32)
                            .to_le_bytes()
                            .to_vec(),
                    )
                };
                self.response = self
                    .peer
                    .encode_response(frame.command, frame.request_id, status, &payload)
                    .unwrap();
                Ok(())
            }
            fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
                Ok(std::mem::take(&mut self.response))
            }
        }
        for details in [false, true] {
            let key = [142; 32];
            let mut client = RootClient::connect_with_required(
                FailureTransport {
                    peer: SecureChannel::new(key).unwrap(),
                    response: Vec::new(),
                    details,
                    taps: 0,
                },
                key,
                Capabilities::from_bits(
                    Capabilities::INPUT_BASIC | Capabilities::INPUT_FAILURE_DETAILS,
                ),
                Capabilities::INPUT_BASIC,
            )
            .unwrap();
            let error = client.tap(1, 1).unwrap_err();
            assert_eq!(
                error.input_failure_code(),
                if details {
                    "ROOT_INPUT_DEADLINE"
                } else {
                    "ROOT_INPUT_CORRELATION"
                }
            );
            assert_eq!(client.transport.taps, 1);
            assert_eq!(error.is_connection_lost(), !details);
        }
        assert!(
            ClientError::InputFailed(root_protocol::InputFailure::ControlUnavailable)
                .is_connection_lost()
        );
    }
    #[test]
    fn capture_failure_details_are_typed_negotiated_and_close_partial_streams() {
        struct FailureTransport {
            peer: root_protocol::SecureChannel,
            response: Vec<u8>,
            details: bool,
        }
        impl PacketTransport for FailureTransport {
            fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
                let request = self.peer.decode(packet).unwrap();
                let (status, payload) = if request.command == Command::Hello {
                    let bits = Capabilities::INPUT_BASIC
                        | Capabilities::CAPTURE_RAW
                        | Capabilities::CAPTURE_STREAM
                        | if self.details {
                            Capabilities::CAPTURE_FAILURE_DETAILS
                        } else {
                            0
                        };
                    (StatusCode::Ok, bits.to_le_bytes().to_vec())
                } else {
                    (
                        StatusCode::BackendFailure,
                        (root_protocol::CaptureFailure::Deadline as u32)
                            .to_le_bytes()
                            .to_vec(),
                    )
                };
                self.response = self
                    .peer
                    .encode_response(request.command, request.request_id, status, &payload)
                    .unwrap();
                Ok(())
            }
            fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
                Ok(std::mem::take(&mut self.response))
            }
        }
        for details in [false, true] {
            let key = [20; 32];
            let mut client = RootClient::connect_with_required(
                FailureTransport {
                    peer: root_protocol::SecureChannel::new(key).unwrap(),
                    response: Vec::new(),
                    details,
                },
                key,
                Capabilities::from_bits(
                    Capabilities::INPUT_BASIC
                        | Capabilities::CAPTURE_RAW
                        | Capabilities::CAPTURE_STREAM
                        | Capabilities::CAPTURE_FAILURE_DETAILS,
                ),
                Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM,
            )
            .unwrap();
            let error = client.capture().unwrap_err();
            assert_eq!(
                error.capture_failure_code(),
                if details {
                    "CAPTURE_DEADLINE"
                } else {
                    "CAPTURE_CORRELATION"
                }
            );
            assert!(error.is_connection_lost());
            assert!(matches!(
                client.capture(),
                Err(ClientError::IncompleteCapture(_))
            ));
        }
        assert_eq!(
            ClientError::Remote(StatusCode::BackendFailure).capture_failure_code(),
            "CAPTURE_BACKEND_FAILED"
        );
        assert_eq!(
            ClientError::Remote(StatusCode::Cancelled).capture_failure_code(),
            "CAPTURE_CANCELLED"
        );
        assert_eq!(
            ClientError::Protocol(root_protocol::ProtocolError::AuthenticationFailed)
                .capture_failure_code(),
            "CAPTURE_PROTOCOL"
        );
    }
    use std::collections::VecDeque;

    use root_daemon_core::{
        CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
    };
    use root_protocol::{Capabilities, Command, Frame, StatusCode};

    use super::{ClientError, PacketTransport, RootClient, TransportError};

    #[derive(Default)]
    struct RecordingDispatcher(Vec<Command>, usize, bool);

    impl CommandDispatcher for RecordingDispatcher {
        fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
            self.0.push(frame.command);
            if matches!(frame.command, Command::Capture | Command::CaptureStream) {
                let length = if self.1 == 0 { 8 } else { self.1 };
                let mut payload = Vec::with_capacity(32);
                payload.extend_from_slice(&1_u64.to_le_bytes());
                let wire_length = u32::try_from(length).map_err(|_| DispatchError)?;
                payload.extend_from_slice(&(wire_length / 4).to_le_bytes());
                payload.extend_from_slice(&1_u32.to_le_bytes());
                payload.extend_from_slice(&wire_length.to_le_bytes());
                payload.extend_from_slice(&[1, 0, 0, 0]);
                payload.extend_from_slice(&(length as u64).to_le_bytes());
                if frame.command == Command::CaptureStream {
                    payload.extend_from_slice(&123_u64.to_le_bytes());
                    payload.extend_from_slice(&2_u32.to_le_bytes());
                    payload.extend_from_slice(&[0; 4]);
                    payload.insert(0, 0);
                }
                return Ok(DispatchResponse {
                    status: StatusCode::Ok,
                    payload,
                });
            }
            if frame.command == Command::CaptureChunk {
                let offset = super::read_u32(&frame.payload, 8).expect("offset") as usize;
                let length = super::read_u32(&frame.payload, 12).expect("length") as usize;
                if self.2 && offset != 0 {
                    return Ok(DispatchResponse {
                        status: StatusCode::Cancelled,
                        payload: Vec::new(),
                    });
                }
                let pattern = [1, 2, 3, 255, 4, 5, 6, 255];
                return Ok(DispatchResponse {
                    status: StatusCode::Ok,
                    payload: (offset..offset + length)
                        .map(|index| pattern[index % pattern.len()])
                        .collect(),
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
            while let Some(packet) = self
                .daemon
                .next_capture_packet(self.now_ms)
                .expect("stream")
            {
                self.responses.push_back(packet);
            }
            Ok(())
        }

        fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
            Ok(self.responses.pop_front().expect("response"))
        }

        fn receive_packet_into(&mut self, packet: &mut Vec<u8>) -> Result<(), TransportError> {
            // Exercise the same bounded, length-delimited reader as Android's UnixStream.
            let response = self.receive_packet()?;
            let mut wire = Vec::new();
            root_daemon::write_packet(&mut wire, &response)?;
            root_daemon::read_packet_into(&mut std::io::Cursor::new(wire), packet)
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
                    Capabilities::INPUT_BASIC
                        | Capabilities::INPUT_POINTER_SINGLE
                        | Capabilities::CAPTURE_RAW,
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
            Capabilities::from_bits(
                Capabilities::INPUT_BASIC
                    | Capabilities::INPUT_POINTER_SINGLE
                    | Capabilities::CAPTURE_RAW,
            ),
        )
        .expect("client")
    }

    fn connect_stream(length: usize, cancel: bool) -> RootClient<LoopbackTransport> {
        let capabilities =
            Capabilities::from_bits(Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM);
        let key = [45; 32];
        let daemon = DaemonSession::new(
            key,
            42,
            DaemonSessionConfig {
                expected_runner_uid: 42,
                server_capabilities: capabilities,
                required_client_capabilities: 0,
                idle_timeout_ms: 20_000,
                now_ms: 0,
            },
            RecordingDispatcher(Vec::new(), length, cancel),
        )
        .expect("daemon");
        RootClient::connect(
            LoopbackTransport {
                daemon,
                responses: VecDeque::new(),
                now_ms: 0,
            },
            key,
            capabilities,
        )
        .expect("client")
    }

    #[test]
    fn stream_uses_one_capture_request_and_releases_without_a_client_round_trip() {
        let mut client = connect_stream(720 * 1280 * 4, false);
        let capture = client.capture().expect("stream capture");
        assert_eq!(capture.pixels.len(), 720 * 1280 * 4);
        assert_eq!(&capture.pixels[..8], &[1, 2, 3, 255, 4, 5, 6, 255]);
        assert_eq!(capture.timings.acquisition_us, 123);
        assert_eq!(capture.timings.backend, 2);
        assert!(capture.timings.streamed);
        assert_eq!(client.next_request_id, 3); // HELLO + one capture only.
        let commands = &client.transport.daemon.dispatcher().0;
        assert_eq!(
            commands
                .iter()
                .filter(|command| **command == Command::CaptureChunk)
                .count(),
            60
        );
        assert_eq!(commands.last(), Some(&Command::ReleaseCapture));
        client
            .transact(Command::Ping, &[])
            .expect("next request remains aligned");
    }

    #[test]
    fn consecutive_stream_captures_reuse_the_authenticated_receive_buffer() {
        let mut client = connect_stream(720 * 1280 * 4, false);
        let first = client.capture().expect("first capture");
        let address = client.receive_buffer.as_ptr();
        let capacity = client.receive_buffer.capacity();
        client.transact(Command::Ping, &[]).expect("small packet");
        assert_eq!(client.receive_buffer.as_ptr(), address);
        let second = client.capture().expect("second capture");
        assert_eq!(second.pixels, first.pixels);
        assert_eq!(client.receive_buffer.as_ptr(), address);
        assert_eq!(client.receive_buffer.capacity(), capacity);
        client.transact(Command::Ping, &[]).expect("aligned stream");
        assert_eq!(client.receive_buffer.as_ptr(), address);
    }

    #[test]
    fn cancelled_partial_stream_is_not_reusable_and_daemon_releases_pixels() {
        let mut client = connect_stream(100_000, true);
        let error = client.capture().expect_err("cancelled stream");
        assert!(error.is_connection_lost());
        assert!(matches!(error, ClientError::IncompleteCapture(_)));
        assert!(client
            .capture()
            .expect_err("closed stream")
            .is_connection_lost());
        assert_eq!(
            client.transport.daemon.dispatcher().0.last(),
            Some(&Command::ReleaseCapture)
        );
    }

    struct MalformedStreamTransport {
        peer: root_protocol::SecureChannel,
        responses: VecDeque<Vec<u8>>,
        fault: u8,
    }
    impl PacketTransport for MalformedStreamTransport {
        fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
            let frame = self.peer.decode(packet).expect("request authentication");
            let capabilities = (Capabilities::CAPTURE_RAW
                | Capabilities::CAPTURE_STREAM
                | if self.fault >= 5 && self.fault != 7 {
                    Capabilities::CAPTURE_STAGE_TIMINGS
                } else {
                    0
                })
            .to_le_bytes();
            if frame.command == Command::Hello {
                self.responses.push_back(
                    self.peer
                        .encode_response(
                            Command::Hello,
                            frame.request_id,
                            StatusCode::Ok,
                            &capabilities,
                        )
                        .unwrap(),
                );
            } else {
                let mut metadata = RecordingDispatcher::default()
                    .dispatch(&frame)
                    .unwrap()
                    .payload;
                if self.fault == 4 {
                    metadata[25..33].copy_from_slice(&(64_u64 * 1024 * 1024 + 1).to_le_bytes());
                }
                if self.fault >= 5 {
                    for value in [1_u64, 2, 3, 4, 5, 6] {
                        metadata.extend_from_slice(&value.to_le_bytes());
                    }
                    if self.fault == 6 {
                        metadata[49..57].copy_from_slice(&10_000_001_u64.to_le_bytes());
                    }
                    if self.fault == 8 {
                        metadata[41..45].copy_from_slice(&1_u32.to_le_bytes());
                    }
                }
                self.responses.push_back(
                    self.peer
                        .encode_response(
                            Command::CaptureStream,
                            frame.request_id,
                            StatusCode::Ok,
                            &metadata,
                        )
                        .unwrap(),
                );
                if self.fault != 3 {
                    let mut payload = vec![1];
                    payload.extend_from_slice(&u32::from(self.fault == 1).to_le_bytes());
                    payload.extend_from_slice(&[1, 2, 3, 255, 4, 5, 6, 255]);
                    let mut reply = self
                        .peer
                        .encode_response(
                            Command::CaptureStream,
                            frame.request_id + u64::from(self.fault == 2),
                            StatusCode::Ok,
                            &payload,
                        )
                        .unwrap();
                    if self.fault == 0 {
                        reply[40] ^= 1;
                    }
                    self.responses.push_back(reply);
                }
            }
            Ok(())
        }
        fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
            self.responses.pop_front().ok_or_else(|| {
                TransportError::Io(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "truncated synthetic stream",
                ))
            })
        }
    }
    #[test]
    fn capture_details_require_negotiation_and_validate_bounds_and_backend() {
        for fault in 5..=8 {
            let key = [77; 32];
            let mut client = RootClient::connect_with_required(
                MalformedStreamTransport {
                    peer: root_protocol::SecureChannel::new(key).unwrap(),
                    responses: VecDeque::new(),
                    fault,
                },
                key,
                Capabilities::from_bits(
                    Capabilities::CAPTURE_RAW
                        | Capabilities::CAPTURE_STREAM
                        | Capabilities::CAPTURE_STAGE_TIMINGS,
                ),
                Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM,
            )
            .unwrap();
            let result = client.capture();
            if fault == 5 {
                let capture = result.unwrap();
                assert_eq!(capture.timings.stages_us, Some([1, 2, 3, 4, 5, 6]));
                assert_eq!(capture.pixels, [1, 2, 3, 255, 4, 5, 6, 255]);
            } else {
                assert!(result.unwrap_err().is_connection_lost());
                assert!(client.closed);
            }
        }
        assert_eq!(
            connect_stream(8, false)
                .capture()
                .unwrap()
                .timings
                .stages_us,
            None
        );
    }

    #[test]
    fn streamed_pixels_reject_tampering_offsets_request_mismatch_truncation_and_oversize() {
        for fault in 0..5 {
            let key = [51; 32];
            let transport = MalformedStreamTransport {
                peer: root_protocol::SecureChannel::new(key).unwrap(),
                responses: VecDeque::new(),
                fault,
            };
            let mut client = RootClient::connect(
                transport,
                key,
                Capabilities::from_bits(Capabilities::CAPTURE_RAW | Capabilities::CAPTURE_STREAM),
            )
            .unwrap();
            assert!(client
                .capture()
                .expect_err("untrusted frame")
                .is_connection_lost());
            assert!(client.closed);
        }
    }

    fn connect_without_pointer_capability() -> RootClient<LoopbackTransport> {
        let key = [32; 32];
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
        RootClient::connect_with_required(
            LoopbackTransport {
                daemon,
                responses: VecDeque::new(),
                now_ms: 0,
            },
            key,
            Capabilities::from_bits(
                Capabilities::INPUT_BASIC
                    | Capabilities::INPUT_POINTER_SINGLE
                    | Capabilities::CAPTURE_RAW,
            ),
            Capabilities::INPUT_BASIC | Capabilities::CAPTURE_RAW,
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
        client.pointer_down(0, 10, 20).expect("pointer down");
        client.pointer_move(0, 11, 21).expect("pointer move");
        client.pointer_up(0).expect("pointer up");
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
    fn optional_pointer_capability_does_not_block_basic_android_seven_input() {
        let mut client = connect_without_pointer_capability();
        assert_eq!(client.input_features(), 1);
        assert_eq!(connect().input_features(), 3);
        client.tap(1, 2).expect("basic input remains available");
        assert!(matches!(
            client.pointer_down(0, 1, 2),
            Err(ClientError::UnsupportedCapability)
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
