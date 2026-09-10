const MAGIC: &[u8; 8] = b"ASGLYPH\0";
const VERSION: u16 = 1;
const HEADER_BYTES: usize = 16;
const ENTRY_HEADER_BYTES: usize = 12;
const MAX_FILE_BYTES: usize = 8 * 1024 * 1024;
const MAX_GLYPHS: usize = 65_535;
const MAX_LABEL_BYTES: usize = 32;
const MAX_DIMENSION: u16 = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphBitmap {
    pub label: String,
    pub width: u16,
    pub height: u16,
    bits: Vec<u8>,
}

impl GlyphBitmap {
    #[must_use]
    pub fn foreground(&self, x: u16, y: u16) -> bool {
        if x >= self.width || y >= self.height {
            return false;
        }
        let bit = usize::from(y) * usize::from(self.width) + usize::from(x);
        self.bits[bit / 8] & (0x80 >> (bit % 8)) != 0
    }

    #[must_use]
    pub fn packed_bits(&self) -> &[u8] {
        &self.bits
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphDictionary {
    glyphs: Vec<GlyphBitmap>,
}

impl GlyphDictionary {
    #[must_use]
    pub fn glyphs(&self) -> &[GlyphBitmap] {
        &self.glyphs
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphSource {
    pub label: String,
    pub width: u16,
    pub height: u16,
    pub packed_bits: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DictionaryFormatError {
    FileTooLarge,
    Truncated,
    InvalidMagic,
    UnsupportedVersion(u16),
    InvalidReserved,
    InvalidGlyphCount,
    InvalidLabel,
    InvalidDimensions,
    InvalidBitmapLength,
    NonCanonicalPadding,
    EmptyGlyph,
    TrailingBytes,
}

/// Decodes the canonical `ASGLYPH` v1 format with strict size and shape limits.
///
/// # Errors
///
/// Rejects malformed, non-canonical, unsupported, oversized, or empty glyph dictionaries.
pub fn decode_dictionary(bytes: &[u8]) -> Result<GlyphDictionary, DictionaryFormatError> {
    if bytes.len() > MAX_FILE_BYTES {
        return Err(DictionaryFormatError::FileTooLarge);
    }
    if bytes.len() < HEADER_BYTES {
        return Err(DictionaryFormatError::Truncated);
    }
    if bytes.get(0..8) != Some(MAGIC) {
        return Err(DictionaryFormatError::InvalidMagic);
    }
    let version = read_u16(bytes, 8)?;
    if version != VERSION {
        return Err(DictionaryFormatError::UnsupportedVersion(version));
    }
    if read_u16(bytes, 10)? != 0 {
        return Err(DictionaryFormatError::InvalidReserved);
    }
    let count = usize::try_from(read_u32(bytes, 12)?)
        .map_err(|_| DictionaryFormatError::InvalidGlyphCount)?;
    if count == 0 || count > MAX_GLYPHS {
        return Err(DictionaryFormatError::InvalidGlyphCount);
    }
    let mut offset = HEADER_BYTES;
    let mut glyphs = Vec::with_capacity(count);
    for _ in 0..count {
        let (glyph, next) = decode_glyph(bytes, offset)?;
        glyphs.push(glyph);
        offset = next;
    }
    if offset != bytes.len() {
        return Err(DictionaryFormatError::TrailingBytes);
    }
    Ok(GlyphDictionary { glyphs })
}

fn decode_glyph(
    bytes: &[u8],
    offset: usize,
) -> Result<(GlyphBitmap, usize), DictionaryFormatError> {
    if bytes.len().saturating_sub(offset) < ENTRY_HEADER_BYTES {
        return Err(DictionaryFormatError::Truncated);
    }
    let label_len = usize::from(read_u16(bytes, offset)?);
    let width = read_u16(bytes, offset + 2)?;
    let height = read_u16(bytes, offset + 4)?;
    if read_u16(bytes, offset + 6)? != 0 {
        return Err(DictionaryFormatError::InvalidReserved);
    }
    let bitmap_len = usize::try_from(read_u32(bytes, offset + 8)?)
        .map_err(|_| DictionaryFormatError::InvalidBitmapLength)?;
    validate_shape(label_len, width, height, bitmap_len)?;
    let label_start = offset + ENTRY_HEADER_BYTES;
    let bitmap_start = label_start
        .checked_add(label_len)
        .ok_or(DictionaryFormatError::Truncated)?;
    let next = bitmap_start
        .checked_add(bitmap_len)
        .ok_or(DictionaryFormatError::Truncated)?;
    let label_bytes = bytes
        .get(label_start..bitmap_start)
        .ok_or(DictionaryFormatError::Truncated)?;
    let label = std::str::from_utf8(label_bytes)
        .ok()
        .filter(|label| valid_label(label))
        .ok_or(DictionaryFormatError::InvalidLabel)?
        .to_owned();
    let bits = bytes
        .get(bitmap_start..next)
        .ok_or(DictionaryFormatError::Truncated)?
        .to_vec();
    validate_bits(&bits, width, height)?;
    Ok((
        GlyphBitmap {
            label,
            width,
            height,
            bits,
        },
        next,
    ))
}

/// Encodes maker output into canonical `ASGLYPH` v1 bytes.
///
/// # Errors
///
/// Rejects the same invalid labels, dimensions, padding, and file limits as the decoder.
pub fn encode_dictionary(glyphs: &[GlyphSource]) -> Result<Vec<u8>, DictionaryFormatError> {
    if glyphs.is_empty() || glyphs.len() > MAX_GLYPHS {
        return Err(DictionaryFormatError::InvalidGlyphCount);
    }
    let mut output = Vec::new();
    output.extend_from_slice(MAGIC);
    output.extend_from_slice(&VERSION.to_le_bytes());
    output.extend_from_slice(&0_u16.to_le_bytes());
    output.extend_from_slice(
        &u32::try_from(glyphs.len())
            .map_err(|_| DictionaryFormatError::InvalidGlyphCount)?
            .to_le_bytes(),
    );
    for glyph in glyphs {
        encode_glyph(&mut output, glyph)?;
        if output.len() > MAX_FILE_BYTES {
            return Err(DictionaryFormatError::FileTooLarge);
        }
    }
    Ok(output)
}

fn encode_glyph(output: &mut Vec<u8>, glyph: &GlyphSource) -> Result<(), DictionaryFormatError> {
    let label_len = glyph.label.len();
    validate_shape(
        label_len,
        glyph.width,
        glyph.height,
        glyph.packed_bits.len(),
    )?;
    if !valid_label(&glyph.label) {
        return Err(DictionaryFormatError::InvalidLabel);
    }
    validate_bits(&glyph.packed_bits, glyph.width, glyph.height)?;
    output.extend_from_slice(
        &u16::try_from(label_len)
            .map_err(|_| DictionaryFormatError::InvalidLabel)?
            .to_le_bytes(),
    );
    output.extend_from_slice(&glyph.width.to_le_bytes());
    output.extend_from_slice(&glyph.height.to_le_bytes());
    output.extend_from_slice(&0_u16.to_le_bytes());
    output.extend_from_slice(
        &u32::try_from(glyph.packed_bits.len())
            .map_err(|_| DictionaryFormatError::InvalidBitmapLength)?
            .to_le_bytes(),
    );
    output.extend_from_slice(glyph.label.as_bytes());
    output.extend_from_slice(&glyph.packed_bits);
    Ok(())
}

fn validate_shape(
    label_len: usize,
    width: u16,
    height: u16,
    bitmap_len: usize,
) -> Result<(), DictionaryFormatError> {
    if label_len == 0 || label_len > MAX_LABEL_BYTES {
        return Err(DictionaryFormatError::InvalidLabel);
    }
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(DictionaryFormatError::InvalidDimensions);
    }
    let expected = (usize::from(width) * usize::from(height)).div_ceil(8);
    if bitmap_len != expected {
        return Err(DictionaryFormatError::InvalidBitmapLength);
    }
    Ok(())
}

fn validate_bits(bits: &[u8], width: u16, height: u16) -> Result<(), DictionaryFormatError> {
    let bit_count = usize::from(width) * usize::from(height);
    let padding = bits.len() * 8 - bit_count;
    if padding > 0
        && bits
            .last()
            .is_some_and(|last| last & ((1 << padding) - 1) != 0)
    {
        return Err(DictionaryFormatError::NonCanonicalPadding);
    }
    if bits.iter().all(|byte| *byte == 0) {
        return Err(DictionaryFormatError::EmptyGlyph);
    }
    Ok(())
}

fn valid_label(label: &str) -> bool {
    !label.is_empty() && !label.chars().any(char::is_control)
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, DictionaryFormatError> {
    bytes
        .get(offset..offset + 2)
        .and_then(|value| value.try_into().ok())
        .map(u16::from_le_bytes)
        .ok_or(DictionaryFormatError::Truncated)
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, DictionaryFormatError> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or(DictionaryFormatError::Truncated)
}

#[cfg(test)]
mod tests {
    use super::{decode_dictionary, encode_dictionary, DictionaryFormatError, GlyphSource};

    fn source() -> GlyphSource {
        GlyphSource {
            label: "A".to_owned(),
            width: 3,
            height: 3,
            packed_bits: vec![0b0101_0111, 0b1000_0000],
        }
    }

    #[test]
    fn canonical_fixture_round_trips_exactly() {
        let bytes = encode_dictionary(&[source()]).expect("encode");
        let dictionary = decode_dictionary(&bytes).expect("decode");
        assert_eq!(dictionary.glyphs()[0].label, "A");
        assert!(dictionary.glyphs()[0].foreground(1, 0));
        assert_eq!(
            encode_dictionary(&[GlyphSource {
                label: dictionary.glyphs()[0].label.clone(),
                width: dictionary.glyphs()[0].width,
                height: dictionary.glyphs()[0].height,
                packed_bits: dictionary.glyphs()[0].packed_bits().to_vec(),
            }]),
            Ok(bytes)
        );
    }

    #[test]
    fn non_zero_padding_and_trailing_bytes_are_rejected() {
        let mut source = source();
        source.packed_bits[1] |= 1;
        assert_eq!(
            encode_dictionary(&[source]),
            Err(DictionaryFormatError::NonCanonicalPadding)
        );
        let mut bytes = encode_dictionary(&[super::tests::source()]).expect("encode");
        bytes.push(0);
        assert_eq!(
            decode_dictionary(&bytes),
            Err(DictionaryFormatError::TrailingBytes)
        );
    }
}
