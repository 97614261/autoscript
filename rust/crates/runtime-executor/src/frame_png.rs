//! A dependency-free PNG encoder: lossless RGBA and bounded stored DEFLATE blocks.
use crate::vision_extensions::rgba;
use automation_core::FrameView;
use std::sync::OnceLock;

pub(crate) const MAX_PNG_BYTES: usize = 20 * 1024 * 1024;
const MAX_PIXELS: u64 = 4 * 1024 * 1024;

fn crc(bytes: &[u8]) -> u32 {
    static TABLE: OnceLock<[u32; 256]> = OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut table = [0; 256];
        for (i, item) in table.iter_mut().enumerate() {
            let mut value = u32::try_from(i).expect("CRC table index");
            for _ in 0..8 {
                value = (value >> 1) ^ (0xedb8_8320_u32.wrapping_mul(value & 1));
            }
            *item = value;
        }
        table
    });
    let mut value = !0_u32;
    for byte in bytes {
        value = table[((value.to_le_bytes()[0]) ^ byte) as usize] ^ (value >> 8);
    }
    !value
}
fn chunk(output: &mut Vec<u8>, kind: [u8; 4], data: &[u8]) {
    output
        .extend_from_slice(&(u32::try_from(data.len()).expect("bounded PNG chunk")).to_be_bytes());
    let start = output.len();
    output.extend_from_slice(&kind);
    output.extend_from_slice(data);
    output.extend_from_slice(&crc(&output[start..]).to_be_bytes());
}

pub(crate) fn encode(
    frame: &FrameView,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Vec<u8>, String> {
    let (w, h) = (frame.metadata.width, frame.metadata.height);
    if w == 0 || h == 0 || u64::from(w) * u64::from(h) > MAX_PIXELS {
        return Err("PNG_PIXEL_BUDGET".into());
    }
    let mut raw = Vec::with_capacity((w as usize * 4 + 1) * h as usize);
    for y in 0..h {
        if cancelled() {
            return Err("PNG_CANCELLED".into());
        }
        raw.push(0);
        for x in 0..w {
            raw.extend_from_slice(&rgba(frame, x, y));
        }
    }
    let mut output = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut header = Vec::with_capacity(13);
    header.extend_from_slice(&w.to_be_bytes());
    header.extend_from_slice(&h.to_be_bytes());
    header.extend_from_slice(&[8, 6, 0, 0, 0]);
    chunk(&mut output, *b"IHDR", &header);
    let (mut a, mut b) = (1_u32, 0_u32);
    let total = raw.len().div_ceil(65535);
    for (index, data) in raw.chunks(65535).enumerate() {
        if cancelled() {
            return Err("PNG_CANCELLED".into());
        }
        let mut deflate = Vec::with_capacity(data.len() + 11);
        if index == 0 {
            deflate.extend_from_slice(&[0x78, 0x01]);
        }
        deflate.push(u8::from(index + 1 == total));
        let count = u16::try_from(data.len()).expect("stored deflate block");
        deflate.extend_from_slice(&count.to_le_bytes());
        deflate.extend_from_slice(&(!count).to_le_bytes());
        deflate.extend_from_slice(data);
        for part in data.chunks(5552) {
            for byte in part {
                a += u32::from(*byte);
                b += a;
            }
            a %= 65521;
            b %= 65521;
        }
        if index + 1 == total {
            deflate.extend_from_slice(&((b << 16) | a).to_be_bytes());
        }
        chunk(&mut output, *b"IDAT", &deflate);
        if output.len() > MAX_PNG_BYTES - 12 {
            return Err("PNG_BYTE_BUDGET".into());
        }
    }
    chunk(&mut output, *b"IEND", &[]);
    if cancelled() {
        return Err("PNG_CANCELLED".into());
    }
    Ok(output)
}

#[cfg(test)]
mod tests {
    #[test]
    fn crc_matches_the_standard_check_vector() {
        assert_eq!(super::crc(b"123456789"), 0xcbf4_3926);
        assert_eq!(super::crc(b"IEND"), 0xae42_6082);
    }
}
