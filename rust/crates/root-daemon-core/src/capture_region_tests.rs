use super::*;
use root_protocol::capture_region::{CaptureRegionMetadata, CaptureRegionRequest};

fn region() -> CaptureRegionRequest {
    CaptureRegionRequest {
        screen_width: 720,
        screen_height: 1280,
        left: 149,
        top: 407,
        right: 151,
        bottom: 408,
    }
}
fn metadata() -> Vec<u8> {
    let mut payload = vec![0; 49];
    payload[0] = 5;
    payload[1..9].copy_from_slice(&1_u64.to_le_bytes());
    for (offset, value) in [(9, 2_u32), (13, 1), (17, 8), (21, 2), (41, 2)] {
        payload[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
    }
    payload[25..33].copy_from_slice(&8_u64.to_le_bytes());
    payload.extend_from_slice(
        &CaptureRegionMetadata {
            request: region(),
            rotation: Some(0),
            regional_readback: true,
        }
        .encode(),
    );
    payload
}
struct Regional {
    calls: usize,
}
impl CommandDispatcher for Regional {
    fn dispatch(&mut self, _: &Frame) -> Result<DispatchResponse, DispatchError> {
        self.calls += 1;
        Ok(DispatchResponse {
            status: StatusCode::Ok,
            payload: metadata(),
        })
    }
}

#[test]
fn region_requires_all_capabilities_before_dispatch() {
    let bits = Capabilities::CAPTURE_REGION_REQUIREMENTS;
    for missing in [
        Capabilities::CAPTURE_RAW,
        Capabilities::CAPTURE_STREAM,
        Capabilities::CAPTURE_STAGE_TIMINGS,
        Capabilities::CAPTURE_TRANSFER_TIMINGS,
        Capabilities::CAPTURE_REGION,
    ] {
        let requested = bits & !missing;
        let mut daemon = DaemonSession::new(
            [31; 32],
            1000,
            DaemonSessionConfig {
                expected_runner_uid: 1000,
                server_capabilities: Capabilities::from_bits(bits),
                required_client_capabilities: 0,
                idle_timeout_ms: 20_000,
                now_ms: 0,
            },
            Regional { calls: 0 },
        )
        .unwrap();
        let mut client = SecureChannel::new([31; 32]).unwrap();
        let hello = daemon
            .receive(
                &client
                    .encode(Command::Hello, 1, &requested.to_le_bytes())
                    .unwrap(),
                1,
            )
            .unwrap();
        client.decode(&hello).unwrap();
        let reply = daemon
            .receive(
                &client
                    .encode(Command::CaptureStream, 2, &region().encode())
                    .unwrap(),
                2,
            )
            .unwrap();
        assert_eq!(
            client.decode(&reply).unwrap().status,
            StatusCode::BackendUnavailable
        );
        assert_eq!(daemon.dispatcher.calls, 0);
    }
}

#[test]
fn provenance_requires_exact_request_size_backend_and_valid_geometry() {
    let request = region().encode();
    let good = metadata();
    assert!(validate_stream_region(&request, &good).is_ok());
    assert!(validate_stream_region(&[], &good).is_err());
    assert!(validate_stream_region(&request, &[0; 49]).is_err());
    for length in 0..good.len() {
        assert!(validate_stream_region(&request, &good[..length]).is_err());
    }
    for (offset, value) in [
        (9, 3_u32),
        (41, 1),
        (49 + 8, 150),
        (49 + 24, 4),
        (49 + 28, 9),
    ] {
        let mut bad = good.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(
            validate_stream_region(&request, &bad).is_err(),
            "offset {offset}"
        );
    }
}
