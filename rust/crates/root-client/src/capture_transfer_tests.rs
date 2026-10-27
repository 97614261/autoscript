use std::collections::VecDeque;
use std::io;

use root_daemon::TransportError;
use root_daemon_core::{
    CommandDispatcher, DaemonSession, DaemonSessionConfig, DispatchError, DispatchResponse,
};
use root_protocol::{
    Capabilities, CaptureFailure, CaptureTransferSummary, Command, Frame, SecureChannel, StatusCode,
};

use super::{ClientError, PacketTransport, RootClient};

const KEY: [u8; 32] = [38; 32];
const BYTES: u64 = 64_000;
const BASE: u64 = Capabilities::CAPTURE_RAW
    | Capabilities::CAPTURE_STREAM
    | Capabilities::CAPTURE_FAILURE_DETAILS;

#[derive(Default)]
enum CaptureMode {
    #[default]
    Buffered,
    Pipeline,
}

#[derive(Default)]
struct CaptureFixture {
    mode: CaptureMode,
    chunks: u64,
    fail_initial: bool,
    fail_second_chunk: bool,
    pixel: u8,
    patterned: bool,
    cancel_at_chunk: Option<u64>,
}

impl CommandDispatcher for CaptureFixture {
    fn dispatch_capture_stream(
        &mut self,
        frame: &Frame,
        pipelined: bool,
    ) -> Result<DispatchResponse, DispatchError> {
        self.mode = if pipelined {
            CaptureMode::Pipeline
        } else {
            CaptureMode::Buffered
        };
        self.dispatch(frame)
    }
    fn capture_failure_reason(&self) -> Option<CaptureFailure> {
        (self.fail_initial || self.fail_second_chunk).then_some(CaptureFailure::ControlUnavailable)
    }

    fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
        let mut response = DispatchResponse::success();
        match frame.command {
            Command::CaptureStream if self.cancel_at_chunk == Some(0) => {
                self.cancel_at_chunk = None;
                response.status = StatusCode::Cancelled;
            }
            Command::Capture | Command::CaptureStream if self.fail_initial => {
                response.status = StatusCode::BackendFailure;
                response.payload = (CaptureFailure::ControlUnavailable as u32)
                    .to_le_bytes()
                    .to_vec();
            }
            Command::CaptureStream => {
                response.payload.push(0);
                response.payload.extend_from_slice(&1_u64.to_le_bytes());
                for value in [16_000_u32, 1, 64_000, 2] {
                    response.payload.extend_from_slice(&value.to_le_bytes());
                }
                response.payload.extend_from_slice(&BYTES.to_le_bytes());
                response.payload.extend_from_slice(&30_u64.to_le_bytes());
                response.payload.extend_from_slice(&2_u32.to_le_bytes());
                response.payload.extend_from_slice(&0_u32.to_le_bytes());
                if matches!(self.mode, CaptureMode::Pipeline) {
                    response.payload[0] = 3;
                    response.payload[33..41].fill(0);
                }
                if let Some(region) =
                    root_protocol::capture_region::CaptureRegionRequest::decode(&frame.payload)
                {
                    response.payload[0] = 5;
                    response.payload.extend_from_slice(
                        &root_protocol::capture_region::CaptureRegionMetadata {
                            request: region,
                            rotation: Some(0),
                            regional_readback: true,
                        }
                        .encode(),
                    );
                }
            }
            Command::CaptureChunk => {
                self.chunks += 1;
                if self.cancel_at_chunk == Some(self.chunks) {
                    response.status = StatusCode::Cancelled;
                } else if self.fail_second_chunk && self.chunks == 2 {
                    response.status = StatusCode::BackendFailure;
                } else {
                    let count = u32::from_le_bytes(frame.payload[12..16].try_into().unwrap());
                    response.payload = vec![
                        if self.pixel == 0 { 37 } else { self.pixel };
                        usize::try_from(count).unwrap()
                    ];
                    if self.patterned {
                        let offset = u32::from_le_bytes(frame.payload[8..12].try_into().unwrap());
                        for (index, pixel) in response.payload.iter_mut().enumerate() {
                            *pixel =
                                u8::try_from((u64::from(offset) + index as u64) % 251).unwrap();
                        }
                    }
                }
            }
            Command::ReleaseCapture if matches!(self.mode, CaptureMode::Pipeline) => {
                response.payload = root_protocol::CapturePipelineCompletion {
                    capture_id: 1,
                    pixel_bytes: BYTES,
                    acquisition_us: 77,
                    stages_us: [2, 3, 4, 5, 6, 7],
                    transfer: CaptureTransferSummary {
                        encode_us: 0,
                        write_us: 0,
                        packets: 2,
                    },
                }
                .encode()
                .to_vec();
            }
            _ => {}
        }
        Ok(response)
    }
}

#[test]
fn initial_authenticated_empty_cancellation_keeps_connection_for_clean_stop_and_new_frame() {
    let mut client = client(
        BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS,
        Fault::None,
        CaptureFixture {
            cancel_at_chunk: Some(0),
            pixel: 93,
            ..CaptureFixture::default()
        },
    );
    assert!(matches!(
        client.capture(),
        Err(ClientError::Remote(StatusCode::Cancelled))
    ));
    assert!(!client.closed);
    client.ping().unwrap();
    let frame = client.capture().unwrap();
    assert!(frame.pixels.iter().all(|value| *value == 93));
    client.ping().unwrap();
}

#[test]
fn cancellation_after_metadata_still_closes_connection() {
    let fixture = CaptureFixture {
        cancel_at_chunk: Some(2),
        ..CaptureFixture::default()
    };
    let mut client = client(
        BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS,
        Fault::None,
        fixture,
    );
    assert!(client.capture().unwrap_err().is_connection_lost());
    assert!(client.closed);
    assert!(client.ping().is_err());
    assert!(matches!(
        super::capture_error(Capabilities::from_bits(BASE), StatusCode::Cancelled, &[255]),
        ClientError::InvalidCapture
    ));
}

#[test]
fn failed_partial_capture_never_survives_a_fresh_authenticated_connection() {
    let capabilities =
        BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS | Capabilities::CAPTURE_CONTROL_DETAILS;
    let mut broken = client(
        capabilities,
        Fault::None,
        CaptureFixture {
            fail_second_chunk: true,
            ..CaptureFixture::default()
        },
    );
    assert!(broken.capture().unwrap_err().is_connection_lost());
    assert!(broken.closed);
    assert!(broken.capture().is_err());
    let mut replacement = client(
        capabilities,
        Fault::None,
        CaptureFixture {
            pixel: 93,
            ..CaptureFixture::default()
        },
    );
    let capture = replacement.capture().unwrap();
    assert!(!capture.pixels.is_empty());
    assert!(capture.pixels.iter().all(|value| *value == 93));
    replacement.ping().unwrap();
}

#[derive(Clone, Copy)]
enum Fault {
    WrongRegion,
    None,
    TamperedData,
    Missing,
    Tampered,
    WrongRequest,
    WrongCount,
    Replayed,
    Truncated,
    Early,
    WrongIdentity,
    WrongLength,
    DowngradedSummary,
    WrongOffset,
}

struct Transport {
    daemon: DaemonSession<CaptureFixture>,
    queue: VecDeque<Vec<u8>>,
    fault: Fault,
    summaries: usize,
    receives: usize,
}

impl Transport {
    fn new(bits: u64, fault: Fault, fixture: CaptureFixture) -> Self {
        Self {
            daemon: DaemonSession::new(
                KEY,
                1000,
                DaemonSessionConfig {
                    expected_runner_uid: 1000,
                    server_capabilities: Capabilities::from_bits(bits),
                    required_client_capabilities: 0,
                    idle_timeout_ms: 300_000,
                    now_ms: 0,
                },
                fixture,
            )
            .unwrap(),
            queue: VecDeque::new(),
            fault,
            summaries: 0,
            receives: 0,
        }
    }

    fn mutate_signed_summary(&mut self) {
        let mut decoder = SecureChannel::new(KEY).unwrap();
        let mut encoder = SecureChannel::new(KEY).unwrap();
        // Hello was consumed by the client, so reproduce its response sequence on both channels.
        let hello = encoder
            .encode_response(
                Command::Hello,
                1,
                StatusCode::Ok,
                &self.daemon.negotiated_capabilities().bits().to_le_bytes(),
            )
            .unwrap();
        decoder.decode(&hello).unwrap();
        let last = self.queue.len() - 1;
        for (index, packet) in self.queue.iter_mut().enumerate() {
            let mut frame = decoder.decode(packet).unwrap();
            if index == 0
                && frame.payload.first() == Some(&5)
                && matches!(self.fault, Fault::WrongRegion)
            {
                let offset = frame.payload.len() - 32;
                let mut region = root_protocol::capture_region::CaptureRegionMetadata::decode(
                    &frame.payload[offset..],
                )
                .unwrap();
                region.request.left += 1;
                region.request.right += 1;
                frame.payload[offset..].copy_from_slice(&region.encode());
            }
            if index == last {
                if let Some(mut completion) =
                    root_protocol::CapturePipelineCompletion::decode(&frame.payload)
                {
                    if matches!(self.fault, Fault::WrongIdentity) {
                        completion.capture_id += 1;
                    }
                    if matches!(self.fault, Fault::WrongLength) {
                        completion.pixel_bytes -= 1;
                    }
                    frame.payload = completion.encode().to_vec();
                    if matches!(self.fault, Fault::DowngradedSummary) {
                        frame.payload = completion.transfer.encode().to_vec();
                    }
                }
            }
            if index == 1 && matches!(self.fault, Fault::WrongOffset) {
                frame.payload[1..5].copy_from_slice(&1_u32.to_le_bytes());
            }
            if index == last && matches!(self.fault, Fault::WrongRequest) {
                frame.request_id += 1;
            }
            if index == last && matches!(self.fault, Fault::WrongCount) {
                frame.payload = CaptureTransferSummary {
                    encode_us: 100,
                    write_us: 200,
                    packets: 3,
                }
                .encode()
                .to_vec();
            }
            if index == 1 && matches!(self.fault, Fault::Early) {
                frame.payload = CaptureTransferSummary {
                    encode_us: 100,
                    write_us: 200,
                    packets: 2,
                }
                .encode()
                .to_vec();
            }
            *packet = encoder
                .encode_response(
                    frame.command,
                    frame.request_id,
                    frame.status,
                    &frame.payload,
                )
                .unwrap();
        }
    }
}

fn region_request() -> root_protocol::capture_region::CaptureRegionRequest {
    root_protocol::capture_region::CaptureRegionRequest {
        screen_width: 16_384,
        screen_height: 1,
        left: 17,
        top: 0,
        right: 16_017,
        bottom: 1,
    }
}

#[test]
fn region_stream_is_fresh_request_bound_and_leaves_next_full_capture_unchanged() {
    let mut client = client(
        BASE | Capabilities::CAPTURE_REGION_REQUIREMENTS,
        Fault::None,
        CaptureFixture::default(),
    );
    for _ in 0..2 {
        let capture = client.capture_region(region_request()).unwrap();
        assert_eq!(capture.geometry.request, region_request());
        assert!(capture.geometry.regional_readback);
        assert_eq!(
            capture.capture.pixels,
            vec![37; usize::try_from(BYTES).unwrap()]
        );
        assert_eq!(capture.capture.timings.transfer_details.unwrap().packets, 2);
        client.ping().unwrap();
    }
    let frame = client.capture().unwrap();
    assert_eq!(frame.pixels.len(), usize::try_from(BYTES).unwrap());
    client.ping().unwrap();
}

#[test]
fn unsupported_region_is_rejected_without_sending_or_closing_full_capture() {
    let mut client = client(BASE, Fault::None, CaptureFixture::default());
    assert!(matches!(
        client.capture_region(region_request()),
        Err(ClientError::Remote(StatusCode::BackendUnavailable))
    ));
    assert!(!client.closed);
    client.capture().unwrap();
    client.ping().unwrap();
}

#[test]
fn region_transfer_rejects_wrong_roi_tampering_offsets_missing_summary_and_cancellation() {
    for fault in [
        Fault::WrongRegion,
        Fault::TamperedData,
        Fault::WrongOffset,
        Fault::Missing,
        Fault::WrongCount,
        Fault::Replayed,
        Fault::WrongRequest,
        Fault::Truncated,
        Fault::Early,
    ] {
        let mut client = client(
            BASE | Capabilities::CAPTURE_REGION_REQUIREMENTS,
            fault,
            CaptureFixture::default(),
        );
        assert!(client.capture_region(region_request()).is_err());
        assert!(client.closed);
    }
    let mut initial_cancel = client(
        BASE | Capabilities::CAPTURE_REGION_REQUIREMENTS,
        Fault::None,
        CaptureFixture {
            cancel_at_chunk: Some(0),
            ..CaptureFixture::default()
        },
    );
    assert!(matches!(
        initial_cancel.capture_region(region_request()),
        Err(ClientError::Remote(StatusCode::Cancelled))
    ));
    assert!(!initial_cancel.closed);
    initial_cancel.capture_region(region_request()).unwrap();
    for chunk in [1, 2] {
        let mut client = client(
            BASE | Capabilities::CAPTURE_REGION_REQUIREMENTS,
            Fault::None,
            CaptureFixture {
                cancel_at_chunk: Some(chunk),
                ..CaptureFixture::default()
            },
        );
        assert!(client
            .capture_region(region_request())
            .unwrap_err()
            .is_connection_lost());
        assert!(client.closed);
        assert!(client.ping().is_err());
    }
}

impl PacketTransport for Transport {
    fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
        self.queue
            .push_back(self.daemon.receive(packet, 1).unwrap());
        while let Some(packet) = self.daemon.next_capture_packet(2).unwrap() {
            let mut packet = packet;
            if matches!(self.fault, Fault::TamperedData) && self.queue.len() == 1 {
                packet[32 + 5] ^= 1;
            }
            self.queue.push_back(packet);
        }
        let mut summary = Vec::new();
        if self
            .daemon
            .finish_capture_transfer_into(
                CaptureTransferSummary {
                    encode_us: 100,
                    write_us: 200,
                    packets: 2,
                },
                &mut summary,
            )
            .unwrap()
        {
            self.summaries += 1;
            self.queue.push_back(summary);
            match self.fault {
                Fault::None | Fault::TamperedData => {}
                Fault::Missing => {
                    self.queue.pop_back();
                }
                Fault::Tampered => {
                    *self.queue.back_mut().unwrap().last_mut().unwrap() ^= 1;
                }
                Fault::Truncated => {
                    self.queue.back_mut().unwrap().pop();
                }
                Fault::Replayed => {
                    let previous = self.queue[self.queue.len() - 2].clone();
                    *self.queue.back_mut().unwrap() = previous;
                }
                Fault::WrongRequest
                | Fault::WrongCount
                | Fault::Early
                | Fault::WrongIdentity
                | Fault::WrongLength
                | Fault::DowngradedSummary
                | Fault::WrongRegion
                | Fault::WrongOffset => {
                    self.mutate_signed_summary();
                }
            }
        }
        Ok(())
    }

    fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
        self.receives += 1;
        self.queue
            .pop_front()
            .ok_or_else(|| io::Error::from(io::ErrorKind::UnexpectedEof).into())
    }
}

fn client(bits: u64, fault: Fault, fixture: CaptureFixture) -> RootClient<Transport> {
    RootClient::connect_with_required(
        Transport::new(bits, fault, fixture),
        KEY,
        Capabilities::from_bits(
            BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS
                | Capabilities::CAPTURE_REGION
                | Capabilities::CAPTURE_CONTROL_DETAILS
                | Capabilities::CAPTURE_PIPELINE_REQUIREMENTS,
        ),
        BASE,
    )
    .unwrap()
}

#[test]
fn pipeline_requires_commit_and_final_identity_length_request_sequence_and_offsets() {
    let bits = BASE | Capabilities::CAPTURE_PIPELINE_REQUIREMENTS;
    let mut good = client(
        bits,
        Fault::None,
        CaptureFixture {
            patterned: true,
            ..CaptureFixture::default()
        },
    );
    let capture = good.capture().unwrap();
    assert_eq!(capture.timings.acquisition_us, 77);
    assert_eq!(capture.timings.stages_us, Some([2, 3, 4, 5, 6, 7]));
    assert_eq!(
        capture.pixels,
        (0..BYTES)
            .map(|i| u8::try_from(i % 251).unwrap())
            .collect::<Vec<_>>()
    );
    good.ping().unwrap();
    for fault in [
        Fault::TamperedData,
        Fault::Missing,
        Fault::Tampered,
        Fault::WrongRequest,
        Fault::WrongCount,
        Fault::Replayed,
        Fault::Truncated,
        Fault::Early,
        Fault::WrongIdentity,
        Fault::WrongLength,
        Fault::DowngradedSummary,
        Fault::WrongOffset,
    ] {
        let mut broken = client(bits, fault, CaptureFixture::default());
        assert!(broken.capture().unwrap_err().is_connection_lost());
        assert!(broken.closed);
        assert!(broken.ping().is_err());
    }
    for chunk in [1, 2] {
        let mut broken = client(
            bits,
            Fault::None,
            CaptureFixture {
                cancel_at_chunk: Some(chunk),
                ..CaptureFixture::default()
            },
        );
        assert!(broken.capture().unwrap_err().is_connection_lost());
    }
}

#[test]
fn negotiated_transfer_summary_is_consumed_and_preserves_pixels_and_next_response() {
    let mut client = client(
        BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS,
        Fault::None,
        CaptureFixture::default(),
    );
    for _ in 0..2 {
        let capture = client.capture().unwrap();
        assert_eq!(capture.pixels, vec![37; usize::try_from(BYTES).unwrap()]);
        let details = capture.timings.transfer_details.unwrap();
        assert_eq!(
            (details.encode_us, details.write_us, details.packets),
            (100, 200, 2)
        );
        client.ping().unwrap();
    }
    assert_eq!(client.transport.summaries, 2);
}

#[test]
fn older_peer_has_no_summary_reads_or_synthetic_zero_details() {
    let mut client = client(BASE, Fault::None, CaptureFixture::default());
    let capture = client.capture().unwrap();
    assert!(capture.timings.transfer_details.is_none());
    assert_eq!(client.transport.summaries, 0);
    assert_eq!(client.transport.receives, 4); // Hello, metadata, two data packets.
    client.ping().unwrap();
}

#[test]
fn authenticated_append_preserves_chunk_boundary_and_partial_last_chunk() {
    for bits in [BASE, BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS] {
        let mut client = client(
            bits,
            Fault::None,
            CaptureFixture {
                patterned: true,
                ..CaptureFixture::default()
            },
        );
        for _ in 0..8 {
            let capture = client.capture().unwrap();
            assert_eq!(capture.pixels.len(), usize::try_from(BYTES).unwrap());
            for (index, pixel) in capture.pixels.iter().enumerate() {
                assert_eq!(*pixel, u8::try_from(index % 251).unwrap());
            }
            client.ping().unwrap();
        }
    }
}

#[test]
fn unauthenticated_chunk_is_never_returned_as_a_partial_frame() {
    for bits in [BASE, BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS] {
        let mut client = client(bits, Fault::TamperedData, CaptureFixture::default());
        assert!(client.capture().unwrap_err().is_connection_lost());
        assert!(client.closed);
        assert!(client.capture().is_err());
    }
}

#[test]
fn corrupt_missing_replayed_early_or_mismatched_summary_closes_entire_capture() {
    for fault in [
        Fault::Missing,
        Fault::Tampered,
        Fault::WrongRequest,
        Fault::WrongCount,
        Fault::Replayed,
        Fault::Truncated,
        Fault::Early,
    ] {
        let mut client = client(
            BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS,
            fault,
            CaptureFixture::default(),
        );
        let error = client.capture().unwrap_err();
        assert!(error.is_connection_lost());
        assert!(client.closed);
        assert!(client.capture().is_err());
    }
}

#[test]
fn initial_and_mid_stream_control_failures_are_typed_only_after_negotiation() {
    for control in [false, true] {
        for mid_stream in [false, true] {
            let mut client = client(
                BASE | Capabilities::CAPTURE_TRANSFER_TIMINGS
                    | if control {
                        Capabilities::CAPTURE_CONTROL_DETAILS
                    } else {
                        0
                    },
                Fault::None,
                CaptureFixture {
                    fail_initial: !mid_stream,
                    fail_second_chunk: mid_stream,
                    ..CaptureFixture::default()
                },
            );
            let error = client.capture().unwrap_err();
            assert_eq!(
                error.capture_failure_code(),
                if control {
                    "CAPTURE_CONTROL_UNAVAILABLE"
                } else {
                    "CAPTURE_BACKEND_FAILED"
                }
            );
            assert!(error.is_connection_lost());
            assert_eq!(client.transport.summaries, 0);
        }
    }
}

#[test]
fn unsolicited_control_reason_is_rejected_without_negotiation() {
    let reason = (CaptureFailure::ControlUnavailable as u32).to_le_bytes();
    let error = super::capture_error(
        Capabilities::from_bits(BASE),
        StatusCode::BackendFailure,
        &reason,
    );
    assert_eq!(error.capture_failure_code(), "CAPTURE_CORRELATION");
    let error = ClientError::CaptureFailed(CaptureFailure::ControlUnavailable);
    assert!(error.is_connection_lost());
}
#[test]
fn android_default_keeps_the_measured_buffered_capture_path() {
    let capabilities = super::default_android_capabilities();
    assert!(capabilities.supports(
        root_protocol::Capabilities::CAPTURE_RAW
            | root_protocol::Capabilities::CAPTURE_STREAM
            | root_protocol::Capabilities::CAPTURE_STAGE_TIMINGS
            | root_protocol::Capabilities::CAPTURE_TRANSFER_TIMINGS
    ));
    assert!(!capabilities.supports(root_protocol::Capabilities::CAPTURE_PIPELINED));
}
