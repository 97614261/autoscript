use std::cell::Cell;
use std::collections::VecDeque;
use std::io::{self, ErrorKind};
use std::rc::Rc;
use std::time::Duration;

use super::{read_packet_with_clock, TransportError, MAX_WIRE_PACKET_BYTES};

struct Step {
    delay_ms: u64,
    result: Result<Vec<u8>, ErrorKind>,
    authenticated_activity: Option<u64>,
}

impl Step {
    fn bytes(delay_ms: u64, bytes: &[u8]) -> Self {
        Self {
            delay_ms,
            result: Ok(bytes.to_vec()),
            authenticated_activity: None,
        }
    }

    fn error(delay_ms: u64, kind: ErrorKind) -> Self {
        Self {
            delay_ms,
            result: Err(kind),
            authenticated_activity: None,
        }
    }
}

struct Socket {
    now: Rc<Cell<u64>>,
    activity: Rc<Cell<u64>>,
    steps: VecDeque<Step>,
    waits: Vec<Duration>,
}

impl Socket {
    fn new(steps: impl IntoIterator<Item = Step>) -> Self {
        Self {
            now: Rc::new(Cell::new(0)),
            activity: Rc::new(Cell::new(0)),
            steps: steps.into_iter().collect(),
            waits: Vec::new(),
        }
    }

    fn read(&mut self, bytes: &mut [u8], timeout: Duration) -> io::Result<usize> {
        assert!(!timeout.is_zero());
        self.waits.push(timeout);
        let step = self
            .steps
            .pop_front()
            .expect("unexpected read or partial-packet retry");
        let remaining_ms = u64::try_from(timeout.as_millis()).unwrap();
        self.now
            .set(self.now.get() + step.delay_ms.min(remaining_ms));
        if step.delay_ms > remaining_ms {
            return Err(ErrorKind::TimedOut.into());
        }
        if let Some(activity) = step.authenticated_activity {
            self.activity.set(activity);
        }
        let data = step.result.map_err(io::Error::from)?;
        assert!(data.len() <= bytes.len());
        bytes[..data.len()].copy_from_slice(&data);
        Ok(data.len())
    }

    fn receive(&mut self) -> Result<Vec<u8>, TransportError> {
        let now = self.now.clone();
        let activity = self.activity.clone();
        let packet_clock = now.clone();
        read_packet_with_clock(
            |bytes, timeout| self.read(bytes, timeout),
            || {
                Duration::from_millis(
                    300_000_u64.saturating_sub(now.get().saturating_sub(activity.get())),
                )
            },
            Duration::from_secs(8),
            || Duration::from_millis(packet_clock.get()),
        )
    }
}

fn is_timeout(result: &Result<Vec<u8>, TransportError>) -> bool {
    matches!(result, Err(TransportError::Io(error)) if error.kind() == ErrorKind::TimedOut)
}

#[test]
fn silent_socket_survives_multiple_idle_windows_only_with_authenticated_activity() {
    let mut socket = Socket::new([
        Step {
            delay_ms: 300_000,
            result: Err(ErrorKind::WouldBlock),
            authenticated_activity: Some(299_999),
        },
        Step {
            delay_ms: 299_999,
            result: Err(ErrorKind::TimedOut),
            authenticated_activity: Some(599_998),
        },
        Step {
            delay_ms: 299_999,
            result: Err(ErrorKind::WouldBlock),
            authenticated_activity: Some(899_997),
        },
        Step::bytes(1, &[3]),
        Step::bytes(2, &[0, 0, 0]),
        Step::bytes(3, b"abc"),
    ]);
    assert_eq!(socket.receive().unwrap(), b"abc");
    assert_eq!(socket.waits[0], Duration::from_mins(5));
    assert_eq!(socket.waits[1], Duration::from_millis(299_999));
    assert_eq!(socket.waits[4], Duration::from_secs(8));
    assert_eq!(socket.waits[5], Duration::from_millis(7_998));
}

#[test]
fn truly_idle_socket_expires_without_reading_another_packet() {
    for kind in [ErrorKind::TimedOut, ErrorKind::WouldBlock] {
        let mut socket = Socket::new([Step::error(300_000, kind)]);
        assert!(is_timeout(&socket.receive()));
        assert_eq!(socket.waits.len(), 1);
    }
}

#[test]
fn partial_header_and_payload_timeouts_never_retry_even_when_business_is_active() {
    for prefix in [
        vec![Step::bytes(0, &[3])],
        vec![
            Step::bytes(0, &[3]),
            Step::bytes(0, &[0, 0, 0]),
            Step::bytes(0, b"a"),
        ],
    ] {
        for kind in [ErrorKind::TimedOut, ErrorKind::WouldBlock] {
            let mut steps = prefix
                .iter()
                .map(|step| Step {
                    delay_ms: step.delay_ms,
                    result: step.result.clone(),
                    authenticated_activity: None,
                })
                .collect::<Vec<_>>();
            steps.push(Step {
                delay_ms: 1,
                result: Err(kind),
                authenticated_activity: Some(1),
            });
            let mut socket = Socket::new(steps);
            assert!(
                matches!(socket.receive(), Err(TransportError::Io(error)) if error.kind() == kind)
            );
            assert!(socket.steps.is_empty());
        }
    }
}

#[test]
fn dribbling_header_and_payload_share_one_absolute_packet_deadline() {
    let mut socket = Socket::new([
        Step::bytes(0, &[3]),
        Step::bytes(3_000, &[0]),
        Step::bytes(3_000, &[0, 0]),
        Step::bytes(1_000, b"a"),
        Step::bytes(2_000, b"bc"),
    ]);
    assert!(is_timeout(&socket.receive()));
    assert_eq!(socket.now.get(), 8_000);
    assert_eq!(socket.waits.last(), Some(&Duration::from_secs(1)));
}

#[test]
fn completion_at_exact_deadline_is_not_accepted() {
    let mut socket = Socket::new([
        Step::bytes(0, &[3]),
        Step::bytes(0, &[0, 0, 0]),
        Step::bytes(8_000, b"abc"),
    ]);
    assert!(is_timeout(&socket.receive()));
}

#[test]
fn eof_and_connection_errors_fail_at_every_packet_stage() {
    for prefix in [
        vec![],
        vec![Step::bytes(0, &[3])],
        vec![Step::bytes(0, &[3]), Step::bytes(0, &[0, 0, 0])],
    ] {
        for error in [
            None,
            Some(ErrorKind::ConnectionReset),
            Some(ErrorKind::BrokenPipe),
        ] {
            let mut steps = prefix
                .iter()
                .map(|step| Step {
                    delay_ms: step.delay_ms,
                    result: step.result.clone(),
                    authenticated_activity: None,
                })
                .collect::<Vec<_>>();
            steps.push(error.map_or_else(|| Step::bytes(0, &[]), |kind| Step::error(0, kind)));
            let mut socket = Socket::new(steps);
            assert!(matches!(socket.receive(), Err(TransportError::Io(found))
                if found.kind() == error.unwrap_or(ErrorKind::UnexpectedEof)));
        }
    }
}

#[test]
fn interrupted_reads_preserve_header_progress_but_do_not_extend_deadlines() {
    let mut socket = Socket::new([
        Step::error(1, ErrorKind::Interrupted),
        Step::bytes(1, &[3]),
        Step::bytes(1, &[0]),
        Step::error(1, ErrorKind::Interrupted),
        Step::bytes(1, &[0, 0]),
        Step::bytes(1, b"abc"),
    ]);
    assert_eq!(socket.receive().unwrap(), b"abc");
    assert_eq!(socket.waits[4], Duration::from_millis(7_998));
}

#[test]
fn invalid_lengths_are_rejected_before_reading_or_allocating_payload() {
    for length in [0_u32, u32::try_from(MAX_WIRE_PACKET_BYTES + 1).unwrap()] {
        let header = length.to_le_bytes();
        let mut socket = Socket::new([Step::bytes(0, &header[..1]), Step::bytes(0, &header[1..])]);
        assert!(matches!(
            socket.receive(),
            Err(TransportError::EmptyPacket | TransportError::PacketTooLarge(_))
        ));
        assert_eq!(socket.waits.len(), 2);
    }
}
