//! Length-delimited stream transport shared by the `RootDaemon` binary and client.

use std::io::{self, Read, Write};

pub const MAX_WIRE_PACKET_BYTES: usize = 70 * 1024;

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
    let mut packet = vec![0; length];
    reader.read_exact(&mut packet)?;
    Ok(packet)
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

    use super::{read_packet, write_packet, TransportError, MAX_WIRE_PACKET_BYTES};

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
