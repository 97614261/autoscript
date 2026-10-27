//! Length-delimited stream transport shared by the `RootDaemon` binary and client.

use std::io::{self, Read, Write};
use std::time::{Duration, Instant};

pub mod capture_watchdog;
pub mod tap;

#[cfg(test)]
mod deadline_tests;

pub const MAX_WIRE_PACKET_BYTES: usize = 70 * 1024;

/// Validates the private bridge's fixed BGRA geometry before starting any pixel transfer.
#[must_use]
pub fn decode_capture_bridge_header(bytes: &[u8]) -> Option<[u32; 4]> {
    if bytes.len() != 20 {
        return None;
    }
    let mut words = [0; 5];
    for (word, bytes) in words.iter_mut().zip(bytes.chunks_exact(4)) {
        *word = u32::from_be_bytes(bytes.try_into().ok()?);
    }
    let [width, height, stride, format, length] = words;
    (width > 0
        && height > 0
        && width <= 16_384
        && height <= 16_384
        && format == 2
        && stride == width * 4
        && u64::from(stride) * u64::from(height) == u64::from(length)
        && length <= 64 * 1024 * 1024)
        .then_some([width, height, stride, length])
}

#[cfg(test)]
mod capture_header_tests {
    use super::*;
    #[test]
    fn exact_original_pixel_geometry_is_required_before_pipeline_allocation() {
        let words = [720_u32, 1280, 2880, 2, 3_686_400];
        let bytes = words
            .into_iter()
            .flat_map(u32::to_be_bytes)
            .collect::<Vec<_>>();
        assert_eq!(
            decode_capture_bridge_header(&bytes),
            Some([720, 1280, 2880, 3_686_400])
        );
        for length in 0..20 {
            assert!(decode_capture_bridge_header(&bytes[..length]).is_none());
        }
        for (index, invalid) in [
            (0, 0),
            (0, 16_385),
            (1, 0),
            (1, 16_385),
            (2, 2881),
            (3, 1),
            (4, 3_686_399),
        ] {
            let mut changed = words;
            changed[index] = invalid;
            let bytes = changed
                .into_iter()
                .flat_map(u32::to_be_bytes)
                .collect::<Vec<_>>();
            assert!(decode_capture_bridge_header(&bytes).is_none());
        }
        let bytes = [16_384_u32, 16_384, 65_536, 2, 1_073_741_824]
            .into_iter()
            .flat_map(u32::to_be_bytes)
            .collect::<Vec<_>>();
        assert!(decode_capture_bridge_header(&bytes).is_none());
    }
}

/// Decodes the fixed big-endian bridge timing trailer plus overlapping pipe receive time.
#[must_use]
pub fn decode_capture_stage_trailer(bytes: &[u8], receive_us: u64) -> Option<[u64; 6]> {
    if bytes.len() != 40 || receive_us > 10_000_000 {
        return None;
    }
    let mut stages = [0; 6];
    for (index, value) in bytes.chunks_exact(8).enumerate() {
        stages[index] = u64::from_be_bytes(value.try_into().ok()?);
        if stages[index] > 10_000_000 {
            return None;
        }
    }
    stages[5] = receive_us;
    Some(stages)
}

#[derive(Debug)]
pub enum TransportError {
    Io(io::Error),
    EmptyPacket,
    PacketTooLarge(usize),
}

impl From<io::Error> for TransportError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

/// Reads one little-endian length-delimited authenticated protocol packet.
///
/// # Errors
///
/// Returns an I/O, empty packet, or configured size-limit error.
pub fn read_packet(reader: &mut impl Read) -> Result<Vec<u8>, TransportError> {
    let mut packet = Vec::new();
    read_packet_into(reader, &mut packet)?;
    Ok(packet)
}

/// Receives a bounded packet into reusable storage. Failed reads leave no valid payload.
///
/// # Errors
/// Returns the same I/O, empty packet or configured size-limit errors as [`read_packet`].
pub fn read_packet_into(
    reader: &mut impl Read,
    packet: &mut Vec<u8>,
) -> Result<(), TransportError> {
    packet.clear();
    let mut length = [0_u8; 4];
    reader.read_exact(&mut length)?;
    let length = usize::try_from(u32::from_le_bytes(length))
        .map_err(|_| TransportError::PacketTooLarge(usize::MAX))?;
    if length == 0 {
        return Err(TransportError::EmptyPacket);
    }
    if length > MAX_WIRE_PACKET_BYTES {
        return Err(TransportError::PacketTooLarge(length));
    }
    packet.resize(length, 0);
    if let Err(error) = reader.read_exact(packet) {
        packet.clear();
        return Err(error.into());
    }
    Ok(())
}

/// Waits for a new packet using shared authenticated idle time, then an absolute packet deadline.
/// `read` must apply the supplied nonzero timeout to each socket read.
/// Only an empty wait may resume after timeout; partial packets always fail closed.
///
/// # Errors
/// Returns timeout, EOF, invalid length or I/O errors. Failed packets are never returned.
pub fn read_packet_with_deadlines(
    read: impl FnMut(&mut [u8], Duration) -> io::Result<usize>,
    idle_remaining: impl FnMut() -> Duration,
    packet_timeout: Duration,
) -> Result<Vec<u8>, TransportError> {
    let started = Instant::now();
    read_packet_with_clock(read, idle_remaining, packet_timeout, || started.elapsed())
}

fn timeout_error() -> io::Error {
    io::Error::new(
        io::ErrorKind::TimedOut,
        "packet or session deadline exceeded",
    )
}

fn read_packet_with_clock(
    mut read: impl FnMut(&mut [u8], Duration) -> io::Result<usize>,
    mut idle_remaining: impl FnMut() -> Duration,
    packet_timeout: Duration,
    mut now: impl FnMut() -> Duration,
) -> Result<Vec<u8>, TransportError> {
    let mut length = [0; 4];
    loop {
        let remaining = idle_remaining();
        if remaining.is_zero() {
            return Err(timeout_error().into());
        }
        match read(&mut length[..1], remaining) {
            Ok(0) => return Err(io::Error::from(io::ErrorKind::UnexpectedEof).into()),
            Ok(_) => break,
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::TimedOut
                        | io::ErrorKind::WouldBlock
                        | io::ErrorKind::Interrupted
                ) => {}
            Err(error) => return Err(error.into()),
        }
    }
    let deadline = now().saturating_add(packet_timeout);
    read_until_deadline(&mut read, &mut length[1..], deadline, &mut now)?;
    let length = usize::try_from(u32::from_le_bytes(length))
        .map_err(|_| TransportError::PacketTooLarge(usize::MAX))?;
    if length == 0 {
        return Err(TransportError::EmptyPacket);
    }
    if length > MAX_WIRE_PACKET_BYTES {
        return Err(TransportError::PacketTooLarge(length));
    }
    let mut packet = vec![0; length];
    read_until_deadline(&mut read, &mut packet, deadline, &mut now)?;
    Ok(packet)
}

fn read_until_deadline(
    read: &mut impl FnMut(&mut [u8], Duration) -> io::Result<usize>,
    mut bytes: &mut [u8],
    deadline: Duration,
    now: &mut impl FnMut() -> Duration,
) -> io::Result<()> {
    while !bytes.is_empty() {
        let remaining = deadline.saturating_sub(now());
        if remaining.is_zero() {
            return Err(timeout_error());
        }
        match read(bytes, remaining) {
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(length) => bytes = &mut bytes[length..],
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error),
        }
    }
    if now() >= deadline {
        return Err(timeout_error());
    }
    Ok(())
}

/// Writes one bounded packet and flushes it as a single protocol message.
///
/// # Errors
///
/// Returns an I/O, empty packet, or configured size-limit error.
pub fn write_packet(writer: &mut impl Write, packet: &[u8]) -> Result<(), TransportError> {
    if packet.is_empty() {
        return Err(TransportError::EmptyPacket);
    }
    if packet.len() > MAX_WIRE_PACKET_BYTES {
        return Err(TransportError::PacketTooLarge(packet.len()));
    }
    let length =
        u32::try_from(packet.len()).map_err(|_| TransportError::PacketTooLarge(packet.len()))?;
    writer.write_all(&length.to_le_bytes())?;
    writer.write_all(packet)?;
    writer.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::{
        read_packet, read_packet_into, write_packet, TransportError, MAX_WIRE_PACKET_BYTES,
    };

    #[test]
    fn timing_trailer_is_big_endian_bounded_and_never_accepts_partial_data() {
        let values = [123_u64, 456, 789, 10, 1_000];
        let bytes: Vec<u8> = values
            .iter()
            .flat_map(|value| value.to_be_bytes())
            .collect();
        assert_eq!(
            super::decode_capture_stage_trailer(&bytes, 3_000),
            Some([123, 456, 789, 10, 1_000, 3_000])
        );
        for length in 0..40 {
            assert!(super::decode_capture_stage_trailer(&bytes[..length], 0).is_none());
        }
        let mut invalid = bytes.clone();
        invalid.extend_from_slice(&[0]);
        assert!(super::decode_capture_stage_trailer(&invalid, 0).is_none());
        invalid = bytes.clone();
        invalid[0..8].copy_from_slice(&10_000_001_u64.to_be_bytes());
        assert!(super::decode_capture_stage_trailer(&invalid, 0).is_none());
        assert!(super::decode_capture_stage_trailer(&bytes, 10_000_001).is_none());
    }

    #[test]
    fn reusable_packet_preserves_storage_and_clears_length_on_failed_reads() {
        let mut packet = Vec::with_capacity(MAX_WIRE_PACKET_BYTES);
        let address = packet.as_ptr();
        for size in [60 * 1024 + 69, 64, 8, 60 * 1024 + 69] {
            let expected = vec![37; size];
            let mut wire = Vec::new();
            write_packet(&mut wire, &expected).unwrap();
            read_packet_into(&mut Cursor::new(wire), &mut packet).unwrap();
            assert_eq!(packet, expected);
            assert_eq!(packet.as_ptr(), address);
        }
        for wire in [
            Vec::new(),
            0_u32.to_le_bytes().to_vec(),
            u32::try_from(MAX_WIRE_PACKET_BYTES + 1)
                .unwrap()
                .to_le_bytes()
                .to_vec(),
            vec![8, 0, 0, 0, 1, 2],
        ] {
            assert!(read_packet_into(&mut Cursor::new(wire), &mut packet).is_err());
            assert!(packet.is_empty());
            assert_eq!(packet.as_ptr(), address);
        }
    }

    #[test]
    fn packet_round_trips_with_partial_reader_semantics() {
        let mut wire = Vec::new();
        write_packet(&mut wire, b"authenticated-frame").expect("write");
        assert_eq!(
            read_packet(&mut Cursor::new(wire)).expect("read"),
            b"authenticated-frame"
        );
    }

    #[test]
    fn empty_and_oversized_packets_fail_before_allocation() {
        assert!(matches!(
            read_packet(&mut Cursor::new(0_u32.to_le_bytes())),
            Err(TransportError::EmptyPacket)
        ));
        let oversized = u32::try_from(MAX_WIRE_PACKET_BYTES + 1)
            .expect("fixture")
            .to_le_bytes();
        assert!(matches!(
            read_packet(&mut Cursor::new(oversized)),
            Err(TransportError::PacketTooLarge(_))
        ));
    }
}
