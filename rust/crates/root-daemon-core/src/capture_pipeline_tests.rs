use super::*;
use root_protocol::{CapturePipelineCompletion, CaptureTransferSummary};

struct PipelineFixture {
    fault: u8,
    releases: usize,
}
impl CommandDispatcher for PipelineFixture {
    fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
        let mut response = DispatchResponse::success();
        match frame.command {
            Command::CaptureStream => {
                response.payload = vec![0; 49];
                response.payload[0] = 3;
                response.payload[1..9].copy_from_slice(&42_u64.to_le_bytes());
                response.payload[25..33].copy_from_slice(&8_u64.to_le_bytes());
                response.payload[41..45].copy_from_slice(&2_u32.to_le_bytes());
            }
            Command::CaptureChunk => {
                response.payload = vec![7; 8];
            }
            Command::ReleaseCapture => {
                self.releases += 1;
                if self.fault == 1 {
                    return Err(DispatchError);
                }
                if self.fault == 2 {
                    return Ok(response);
                }
                response.payload = CapturePipelineCompletion {
                    capture_id: if self.fault == 3 { 43 } else { 42 },
                    pixel_bytes: 8,
                    acquisition_us: 30,
                    stages_us: [1; 6],
                    transfer: CaptureTransferSummary {
                        encode_us: 0,
                        write_us: 0,
                        packets: 1,
                    },
                }
                .encode()
                .to_vec();
                if self.fault == 4 {
                    response.status = StatusCode::BackendFailure;
                }
            }
            _ => {}
        }
        Ok(response)
    }
}

fn session(bits: u64, fault: u8) -> (DaemonSession<PipelineFixture>, SecureChannel) {
    let mut daemon = DaemonSession::new(
        [15; 32],
        1000,
        DaemonSessionConfig {
            expected_runner_uid: 1000,
            server_capabilities: Capabilities::from_bits(bits),
            required_client_capabilities: 0,
            idle_timeout_ms: 20_000,
            now_ms: 0,
        },
        PipelineFixture { fault, releases: 0 },
    )
    .unwrap();
    let mut sender = SecureChannel::new([15; 32]).unwrap();
    daemon
        .receive(
            &sender
                .encode(Command::Hello, 1, &bits.to_le_bytes())
                .unwrap(),
            1,
        )
        .unwrap();
    (daemon, sender)
}

#[test]
fn pipeline_requires_every_capability_and_a_successful_validated_release() {
    let bits = Capabilities::CAPTURE_PIPELINE_REQUIREMENTS;
    for missing in [
        Capabilities::CAPTURE_PIPELINED,
        Capabilities::CAPTURE_STAGE_TIMINGS,
        Capabilities::CAPTURE_TRANSFER_TIMINGS,
    ] {
        let (mut daemon, mut sender) = session(bits & !missing, 0);
        assert_eq!(
            daemon.receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 2),
            Err(DaemonError::DispatchFailed)
        );
    }
    for fault in 1..=4 {
        let (mut daemon, mut sender) = session(bits, fault);
        daemon
            .receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 2)
            .unwrap();
        let mut packet = vec![99];
        assert_eq!(
            daemon.next_capture_packet_into(3, &mut packet),
            Err(DaemonError::DispatchFailed)
        );
        assert!(packet.is_empty());
        assert_eq!(daemon.state(), SessionState::Closed);
        assert_eq!(daemon.dispatcher().releases, 1);
    }
}

#[test]
fn pipeline_has_no_commit_before_data_and_releases_once_on_deadline() {
    let bits = Capabilities::CAPTURE_PIPELINE_REQUIREMENTS;
    let (mut daemon, mut sender) = session(bits, 0);
    daemon
        .receive(&sender.encode(Command::CaptureStream, 2, &[]).unwrap(), 2)
        .unwrap();
    let mut packet = Vec::new();
    let summary = CaptureTransferSummary {
        encode_us: 2,
        write_us: 3,
        packets: 1,
    };
    assert_eq!(
        daemon.finish_capture_transfer_into(summary, &mut packet),
        Err(DaemonError::UnexpectedResponse)
    );
    assert_eq!(
        daemon.next_capture_packet_into(10_002, &mut packet),
        Ok(true)
    );
    assert_eq!(daemon.dispatcher().releases, 1);
    assert_eq!(
        daemon.finish_capture_transfer_into(summary, &mut packet),
        Ok(false)
    );
    assert!(packet.is_empty());
}
