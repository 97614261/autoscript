use std::sync::Arc;

use root_protocol::{
    priority_control_key, Capabilities, Command, Frame, SecureChannel, StatusCode,
};

use super::priority::InputFence;
use super::{
    CommandDispatcher, DaemonError, DaemonSession, DaemonSessionConfig, DispatchError,
    DispatchResponse, SessionActivity,
};

struct Dispatcher(Arc<InputFence>);

impl CommandDispatcher for Dispatcher {
    fn dispatch(&mut self, frame: &Frame) -> Result<DispatchResponse, DispatchError> {
        if frame.command == Command::Cancel {
            self.0
                .cancel_through(u64::from_le_bytes(frame.payload[..8].try_into().unwrap()));
        }
        if frame.command == Command::Capture {
            let mut payload = 1_u64.to_le_bytes().to_vec();
            payload.extend_from_slice(&1_u32.to_le_bytes());
            payload.extend_from_slice(&1_u32.to_le_bytes());
            payload.extend_from_slice(&4_u32.to_le_bytes());
            payload.extend_from_slice(&1_u32.to_le_bytes());
            payload.extend_from_slice(&4_u64.to_le_bytes());
            return Ok(DispatchResponse {
                status: StatusCode::Ok,
                payload,
            });
        }
        Ok(DispatchResponse::success())
    }
}

fn endpoint(
    key: [u8; 32],
    activity: &Arc<SessionActivity>,
    fence: &Arc<InputFence>,
) -> (SecureChannel, DaemonSession<Dispatcher>) {
    let client = SecureChannel::new(key).unwrap();
    let daemon = DaemonSession::new(
        key,
        10_234,
        DaemonSessionConfig {
            expected_runner_uid: 10_234,
            server_capabilities: Capabilities::from_bits(
                Capabilities::CAPTURE_RAW | Capabilities::INPUT_PRIORITY_STOP,
            ),
            required_client_capabilities: 0,
            idle_timeout_ms: 300_000,
            now_ms: 0,
        },
        Dispatcher(fence.clone()),
    )
    .unwrap()
    .with_shared_activity(activity.clone());
    (client, daemon)
}

fn hello(client: &mut SecureChannel, daemon: &mut DaemonSession<Dispatcher>, now: u64) {
    let packet = client
        .encode(
            Command::Hello,
            1,
            &(Capabilities::CAPTURE_RAW | Capabilities::INPUT_PRIORITY_STOP).to_le_bytes(),
        )
        .unwrap();
    let reply = daemon.receive(&packet, now).unwrap();
    assert_eq!(client.decode(&reply).unwrap().status, StatusCode::Ok);
}

#[test]
fn continuous_business_keeps_silent_priority_alive_and_late_stop_cancels() {
    let activity = Arc::new(SessionActivity::default());
    let fence = Arc::new(InputFence::default());
    let key = [17; 32];
    let (mut client, mut business) = endpoint(key, &activity, &fence);
    let (mut controller, mut stop) = endpoint(priority_control_key(&key), &activity, &fence);
    hello(&mut client, &mut business, 0);
    hello(&mut controller, &mut stop, 0);
    // Six old idle windows pass without any priority traffic or real-time sleeps.
    for index in 1..=9 {
        let now = index * 200_000;
        let capture = client.encode(Command::Capture, index + 1, &[]).unwrap();
        let reply = business.receive(&capture, now).unwrap();
        assert_eq!(client.decode(&reply).unwrap().status, StatusCode::Ok);
        assert_eq!(stop.remaining_idle_ms(now), 300_000);
        stop.check_idle(now).unwrap();
    }
    let active = fence.begin(99).unwrap();
    let cancel = controller
        .encode(Command::Cancel, 2, &99_u64.to_le_bytes())
        .unwrap();
    let reply = stop.receive(&cancel, 1_800_001).unwrap();
    assert_eq!(controller.decode(&reply).unwrap().status, StatusCode::Ok);
    assert!(active.is_cancelled());
    business.check_idle(2_100_000).unwrap();
    assert_eq!(
        business.check_idle(2_100_001),
        Err(DaemonError::IdleTimeout)
    );
    assert_eq!(stop.check_idle(2_100_001), Err(DaemonError::IdleTimeout));
}

#[test]
fn shared_activity_cannot_extend_an_unfinished_handshake() {
    let activity = Arc::new(SessionActivity::default());
    let fence = Arc::new(InputFence::default());
    let (mut client, mut ready) = endpoint([17; 32], &activity, &fence);
    let (_, mut untrusted) = endpoint([19; 32], &activity, &fence);
    hello(&mut client, &mut ready, 0);
    ready
        .receive(&client.encode(Command::Capture, 2, &[]).unwrap(), 299_999)
        .unwrap();
    assert_eq!(untrusted.remaining_idle_ms(300_000), 0);
    assert_eq!(untrusted.check_idle(300_000), Err(DaemonError::IdleTimeout));
    ready.check_idle(300_000).unwrap();
}

#[test]
fn tampered_replayed_and_duplicate_hello_messages_do_not_refresh_shared_activity() {
    let activity = Arc::new(SessionActivity::default());
    let fence = Arc::new(InputFence::default());
    let (mut client, mut daemon) = endpoint([17; 32], &activity, &fence);
    hello(&mut client, &mut daemon, 0);
    let capture = client.encode(Command::Capture, 2, &[]).unwrap();
    let mut tampered = capture.clone();
    tampered[8] ^= 1;
    assert!(daemon.receive(&tampered, 200_000).is_err());
    assert_eq!(activity.last_ms(), 0);
    daemon.receive(&capture, 200_001).unwrap();
    assert!(daemon.receive(&capture, 400_000).is_err());
    assert_eq!(activity.last_ms(), 200_001);
    let duplicate = client
        .encode(Command::Hello, 3, &0_u64.to_le_bytes())
        .unwrap();
    assert_eq!(
        daemon.receive(&duplicate, 400_001),
        Err(DaemonError::DuplicateHello)
    );
    assert_eq!(activity.last_ms(), 200_001);
    assert_eq!(daemon.check_idle(500_001), Err(DaemonError::IdleTimeout));
}

#[test]
fn shared_clock_never_moves_backwards_and_closed_sessions_stay_closed() {
    let activity = Arc::new(SessionActivity::default());
    activity.record(200_000);
    activity.record(100_000);
    assert_eq!(activity.last_ms(), 200_000);
    let fence = Arc::new(InputFence::default());
    let (mut client, mut daemon) = endpoint([17; 32], &activity, &fence);
    hello(&mut client, &mut daemon, 0);
    assert_eq!(daemon.check_idle(500_000), Err(DaemonError::IdleTimeout));
    activity.record(500_001);
    assert_eq!(daemon.remaining_idle_ms(500_001), 0);
    assert_eq!(daemon.check_idle(500_001), Err(DaemonError::SessionClosed));
}

#[test]
fn short_pause_allows_stop_and_fresh_input_but_real_idle_requires_a_new_session() {
    let activity = Arc::new(SessionActivity::default());
    let fence = Arc::new(InputFence::default());
    let (mut client, mut business) = endpoint([17; 32], &activity, &fence);
    let (mut controller, mut stop) = endpoint(priority_control_key(&[17; 32]), &activity, &fence);
    hello(&mut client, &mut business, 0);
    hello(&mut controller, &mut stop, 0);
    business
        .receive(&client.encode(Command::Capture, 2, &[]).unwrap(), 200_000)
        .unwrap();
    // Runtime pause needs no heartbeat. A short silent pause still permits priority stop.
    let old = fence.begin(2).unwrap();
    stop.receive(
        &controller
            .encode(Command::Cancel, 2, &2_u64.to_le_bytes())
            .unwrap(),
        400_000,
    )
    .unwrap();
    assert!(old.is_cancelled());
    fence.finish(2);
    assert!(fence.begin(2).is_none());
    let fresh = fence.begin(3).unwrap();
    assert!(!fresh.is_cancelled());
    fence.finish(3);
    business
        .receive(&client.encode(Command::Capture, 3, &[]).unwrap(), 400_001)
        .unwrap();
    assert_eq!(business.check_idle(700_001), Err(DaemonError::IdleTimeout));
    assert_eq!(stop.check_idle(700_001), Err(DaemonError::IdleTimeout));
    assert_eq!(
        stop.receive(
            &controller
                .encode(Command::Cancel, 3, &3_u64.to_le_bytes())
                .unwrap(),
            700_002
        ),
        Err(DaemonError::SessionClosed)
    );
}

#[test]
fn restarted_authenticated_session_rejects_old_business_and_control_packets() {
    let old_activity = Arc::new(SessionActivity::default());
    let old_fence = Arc::new(InputFence::default());
    let (mut old, mut old_business) = endpoint([17; 32], &old_activity, &old_fence);
    let (mut old_control, mut old_stop) =
        endpoint(priority_control_key(&[17; 32]), &old_activity, &old_fence);
    hello(&mut old, &mut old_business, 0);
    hello(&mut old_control, &mut old_stop, 0);
    let old_capture = old.encode(Command::Capture, 2, &[]).unwrap();
    let old_cancel = old_control
        .encode(Command::Cancel, 2, &99_u64.to_le_bytes())
        .unwrap();
    old_fence.fail_closed();
    let activity = Arc::new(SessionActivity::default());
    let fence = Arc::new(InputFence::default());
    let (mut client, mut business) = endpoint([23; 32], &activity, &fence);
    let (mut controller, mut stop) = endpoint(priority_control_key(&[23; 32]), &activity, &fence);
    hello(&mut client, &mut business, 0);
    hello(&mut controller, &mut stop, 0);
    let active = fence.begin(2).unwrap();
    assert!(business.receive(&old_capture, 10).is_err());
    assert!(stop.receive(&old_cancel, 10).is_err());
    assert!(!active.is_cancelled());
    assert_eq!(activity.last_ms(), 0);
    let reply = business
        .receive(&client.encode(Command::Capture, 2, &[]).unwrap(), 11)
        .unwrap();
    assert_eq!(client.decode(&reply).unwrap().status, StatusCode::Ok);
    stop.receive(
        &controller
            .encode(Command::Cancel, 2, &2_u64.to_le_bytes())
            .unwrap(),
        12,
    )
    .unwrap();
    assert!(active.is_cancelled());
}
