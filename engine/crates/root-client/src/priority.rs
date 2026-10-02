//! Independent, bounded control worker. Its mutex never waits on the input response.
#[cfg(any(target_os = "android", test))]
use crate::{PacketTransport, RootClient};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopStatus {
    Ready,
    Pending,
    Clean,
    Failed,
    Closed,
}
#[derive(Debug)]
pub(crate) struct Gate {
    pub last_input: u64,
    pub status: StopStatus,
    generation: u64,
    closed: bool,
}
pub(crate) struct Shared {
    pub gate: Mutex<Gate>,
    changed: Condvar,
    listener: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
}
impl std::fmt::Debug for Shared {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StopShared")
            .field("gate", &self.gate)
            .finish_non_exhaustive()
    }
}
#[derive(Clone, Debug)]
pub struct PriorityStop(Arc<Shared>);
impl PriorityStop {
    pub fn set_listener(&self, listener: Arc<dyn Fn() + Send + Sync>) {
        *self
            .0
            .listener
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(listener);
    }
    pub fn request_stop(&self) {
        let mut gate = self
            .0
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if gate.status != StopStatus::Ready || gate.closed {
            return;
        }
        gate.status = StopStatus::Pending;
        gate.generation = gate.generation.saturating_add(1);
        self.0.changed.notify_all();
    }
    pub fn status(&self) -> StopStatus {
        self.0
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .status
    }
    pub fn wait_clean(&self, timeout: Duration) -> Result<(), &'static str> {
        let deadline = Instant::now() + timeout;
        let mut gate = self
            .0
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        while gate.status == StopStatus::Pending {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                gate.status = StopStatus::Failed;
                self.0.changed.notify_all();
                return Err("ROOT_STOP_TIMEOUT");
            }
            gate = self
                .0
                .changed
                .wait_timeout(gate, remaining)
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .0;
        }
        if matches!(gate.status, StopStatus::Ready | StopStatus::Clean) {
            Ok(())
        } else {
            Err("ROOT_STOP_CLEANUP_FAILED")
        }
    }
    pub fn resume(&self) -> Result<(), &'static str> {
        let mut gate = self
            .0
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if gate.closed || !matches!(gate.status, StopStatus::Ready | StopStatus::Clean) {
            return Err("ROOT_INPUT_NOT_CLEAN");
        }
        gate.status = StopStatus::Ready;
        Ok(())
    }
    pub(crate) fn shared(&self) -> &Shared {
        &self.0
    }
    pub(crate) fn close(&self) {
        let mut gate = self
            .0
            .gate
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        gate.closed = true;
        gate.status = StopStatus::Closed;
        self.0.changed.notify_all();
    }
}
#[cfg(any(target_os = "android", test))]
pub(crate) fn spawn<T: PacketTransport + Send + 'static>(
    mut control: RootClient<T>,
) -> Result<PriorityStop, std::io::Error> {
    let handle = PriorityStop(Arc::new(Shared {
        gate: Mutex::new(Gate {
            last_input: 0,
            status: StopStatus::Ready,
            generation: 0,
            closed: false,
        }),
        changed: Condvar::new(),
        listener: Mutex::new(None),
    }));
    let shared = handle.0.clone();
    std::thread::Builder::new()
        .name("root-stop-control".into())
        .spawn(move || {
            let mut seen = 0;
            loop {
                let (cutoff, generation) = {
                    let mut gate = shared
                        .gate
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    while !gate.closed
                        && (gate.status != StopStatus::Pending || gate.generation == seen)
                    {
                        gate = shared
                            .changed
                            .wait(gate)
                            .unwrap_or_else(std::sync::PoisonError::into_inner);
                    }
                    if gate.closed {
                        return;
                    }
                    (gate.last_input, gate.generation)
                };
                let success = control.cancel(cutoff).is_ok();
                let mut gate = shared
                    .gate
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                seen = generation;
                if gate.generation == generation && gate.status == StopStatus::Pending {
                    gate.status = if success {
                        StopStatus::Clean
                    } else {
                        StopStatus::Failed
                    };
                }
                shared.changed.notify_all();
                drop(gate);
                let listener = shared
                    .listener
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .clone();
                if let Some(listener) = listener {
                    listener();
                }
            }
        })?;
    Ok(handle)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ClientError, TransportError};
    use root_protocol::{Capabilities, Command, SecureChannel, StatusCode};
    use std::sync::mpsc::{self, Receiver};
    struct Transport {
        channel: SecureChannel,
        reply: Option<Vec<u8>>,
        seen: Arc<Mutex<Vec<Command>>>,
        release: Option<Receiver<()>>,
        pending_cancel: bool,
        fail: bool,
    }
    impl PacketTransport for Transport {
        fn send_packet(&mut self, packet: &[u8]) -> Result<(), TransportError> {
            let frame = self
                .channel
                .decode(packet)
                .expect("contiguous authenticated sequence");
            self.seen.lock().unwrap().push(frame.command);
            self.pending_cancel = frame.command == Command::Cancel;
            let payload = if frame.command == Command::Hello {
                frame.payload
            } else {
                Vec::new()
            };
            let status = if self.fail && self.pending_cancel {
                StatusCode::BackendFailure
            } else {
                StatusCode::Ok
            };
            self.reply = Some(
                self.channel
                    .encode_response(frame.command, frame.request_id, status, &payload)
                    .unwrap(),
            );
            Ok(())
        }
        fn receive_packet(&mut self) -> Result<Vec<u8>, TransportError> {
            if self.pending_cancel {
                if let Some(release) = self.release.take() {
                    release.recv_timeout(Duration::from_secs(2)).unwrap();
                }
            }
            Ok(self.reply.take().unwrap())
        }
    }
    fn connection(
        key: [u8; 32],
        caps: u64,
        release: Option<Receiver<()>>,
        fail: bool,
    ) -> (RootClient<Transport>, Arc<Mutex<Vec<Command>>>) {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let transport = Transport {
            channel: SecureChannel::new(key).unwrap(),
            reply: None,
            seen: seen.clone(),
            release,
            pending_cancel: false,
            fail,
        };
        (
            RootClient::connect(transport, key, Capabilities::from_bits(caps)).unwrap(),
            seen,
        )
    }
    #[test]
    fn repeated_stop_blocks_input_until_cleanup_and_resume_preserves_sequence() {
        let (send, receive) = mpsc::channel();
        let (control, seen) = connection(
            [5; 32],
            Capabilities::INPUT_PRIORITY_STOP,
            Some(receive),
            false,
        );
        let stop = spawn(control).unwrap();
        let (mut input, input_seen) = connection(
            [7; 32],
            Capabilities::INPUT_BASIC | Capabilities::INPUT_POINTER_SINGLE,
            None,
            false,
        );
        input.priority = Some(stop.clone());
        input.pointer_down(0, 1, 2).unwrap();
        stop.request_stop();
        stop.request_stop();
        assert!(stop.resume().is_err());
        assert!(matches!(
            input.tap(3, 4),
            Err(ClientError::Remote(StatusCode::Cancelled))
        ));
        assert_eq!(input_seen.lock().unwrap().len(), 2); // Hello + down; blocked tap never advances wire sequence.
        send.send(()).unwrap();
        stop.wait_clean(Duration::from_secs(2)).unwrap();
        input.pointer_up(0).unwrap();
        assert_eq!(input_seen.lock().unwrap().len(), 2);
        stop.resume().unwrap();
        input.tap(3, 4).unwrap();
        assert_eq!(
            seen.lock()
                .unwrap()
                .iter()
                .filter(|v| **v == Command::Cancel)
                .count(),
            1
        );
    }
    #[test]
    fn failed_release_never_rearms_input() {
        let (control, _) = connection([5; 32], Capabilities::INPUT_PRIORITY_STOP, None, true);
        let stop = spawn(control).unwrap();
        stop.request_stop();
        assert!(stop.wait_clean(Duration::from_secs(2)).is_err());
        assert_eq!(stop.status(), StopStatus::Failed);
        assert!(stop.resume().is_err());
        stop.close();
    }
    #[test]
    fn late_success_does_not_rearm_after_timeout() {
        let (send, receive) = mpsc::channel();
        let (control, _) = connection(
            [5; 32],
            Capabilities::INPUT_PRIORITY_STOP,
            Some(receive),
            false,
        );
        let stop = spawn(control).unwrap();
        stop.request_stop();
        assert!(stop.wait_clean(Duration::ZERO).is_err());
        send.send(()).unwrap();
        assert!(stop.resume().is_err());
        stop.close();
    }
    #[test]
    fn control_key_prevents_cross_channel_replay() {
        let key = [3; 32];
        let packet = SecureChannel::new(key)
            .unwrap()
            .encode(Command::Cancel, 2, &2u64.to_le_bytes())
            .unwrap();
        assert!(
            SecureChannel::new(root_protocol::priority_control_key(&key))
                .unwrap()
                .decode(&packet)
                .is_err()
        );
    }
}
