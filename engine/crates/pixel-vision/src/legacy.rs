use super::{Color, ColorSpec, ColorTolerance, FixedPatternSample, PatternSample, PixelPoint};
use std::fmt::Write as _;

pub const MAX_LEGACY_PATTERN_SAMPLES: usize = 65;
pub const MAX_LEGACY_FIXED_SAMPLES: usize = 256;
pub const MAX_LEGACY_COLOR_SPECS: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LegacyParseError {
    Empty,
    UnexpectedByte { offset: usize },
    InvalidInteger { offset: usize },
    IntegerOutOfRange { offset: usize },
    TooManyItems { maximum: usize },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LegacyFormatError {
    Empty,
    TooManyItems { maximum: usize },
    InvalidAnchorOffset,
    UnsupportedAlpha,
}

/// Serializes structured relative samples to the strict legacy grammar.
///
/// The first sample is the anchor and therefore must have a zero offset. Output deliberately
/// omits the optional trailing `#`, producing the same canonical form as the old copy UI.
///
/// # Errors
///
/// Rejects empty/oversized patterns, a non-zero anchor offset, or alpha data that the legacy
/// grammar cannot represent without loss.
pub fn format_relative_pattern(samples: &[PatternSample]) -> Result<String, LegacyFormatError> {
    validate_item_count(samples.len(), MAX_LEGACY_PATTERN_SAMPLES)?;
    if samples[0].offset_x != 0 || samples[0].offset_y != 0 {
        return Err(LegacyFormatError::InvalidAnchorOffset);
    }
    let mut output = String::new();
    write_color_spec(&mut output, samples[0].color, samples[0].tolerance)?;
    for sample in &samples[1..] {
        write!(output, "#({},{})|", sample.offset_x, sample.offset_y)
            .expect("writing to String cannot fail");
        write_color_spec(&mut output, sample.color, sample.tolerance)?;
    }
    Ok(output)
}

/// Serializes structured absolute samples to the strict legacy grammar.
///
/// # Errors
///
/// Rejects empty/oversized input or alpha data that cannot be represented losslessly.
pub fn format_fixed_pattern(samples: &[FixedPatternSample]) -> Result<String, LegacyFormatError> {
    validate_item_count(samples.len(), MAX_LEGACY_FIXED_SAMPLES)?;
    let mut output = String::new();
    for (index, sample) in samples.iter().enumerate() {
        if index != 0 {
            output.push('#');
        }
        write!(output, "({},{})|", sample.point.x, sample.point.y)
            .expect("writing to String cannot fail");
        write_color_spec(&mut output, sample.color, sample.tolerance)?;
    }
    Ok(output)
}

/// Serializes structured alternative colors to the strict legacy grammar.
///
/// # Errors
///
/// Rejects empty/oversized input or alpha data that cannot be represented losslessly.
pub fn format_color_list(colors: &[ColorSpec]) -> Result<String, LegacyFormatError> {
    validate_item_count(colors.len(), MAX_LEGACY_COLOR_SPECS)?;
    let mut output = String::new();
    for (index, spec) in colors.iter().enumerate() {
        if index != 0 {
            output.push('#');
        }
        write_color_spec(&mut output, spec.color, spec.tolerance)?;
    }
    Ok(output)
}

fn validate_item_count(count: usize, maximum: usize) -> Result<(), LegacyFormatError> {
    if count == 0 {
        return Err(LegacyFormatError::Empty);
    }
    if count > maximum {
        return Err(LegacyFormatError::TooManyItems { maximum });
    }
    Ok(())
}

fn write_color_spec(
    output: &mut String,
    color: Color,
    tolerance: ColorTolerance,
) -> Result<(), LegacyFormatError> {
    if color.alpha != 255 || tolerance.alpha != 0 {
        return Err(LegacyFormatError::UnsupportedAlpha);
    }
    write!(
        output,
        "({},{},{})-({},{},{})",
        color.red, color.green, color.blue, tolerance.red, tolerance.green, tolerance.blue
    )
    .expect("writing to String cannot fail");
    Ok(())
}

/// Parses legacy relative multi-color syntax. The first item is the anchor color and every later
/// item begins with a signed offset: `(r,g,b)-(dr,dg,db)#(dx,dy)|(r,g,b)-(dr,dg,db)`.
///
/// # Errors
///
/// Rejects whitespace, missing delimiters, invalid ranges, empty items, and more than 65 samples.
pub fn parse_relative_pattern(input: &str) -> Result<Vec<PatternSample>, LegacyParseError> {
    let mut parser = Parser::new(input)?;
    let anchor = parser.color_spec()?;
    let mut samples = vec![PatternSample {
        offset_x: 0,
        offset_y: 0,
        color: anchor.color,
        tolerance: anchor.tolerance,
    }];
    while parser.next_item()? {
        if samples.len() == MAX_LEGACY_PATTERN_SAMPLES {
            return Err(LegacyParseError::TooManyItems {
                maximum: MAX_LEGACY_PATTERN_SAMPLES,
            });
        }
        let (offset_x, offset_y) = parser.signed_pair()?;
        parser.expect(b'|')?;
        let spec = parser.color_spec()?;
        samples.push(PatternSample {
            offset_x,
            offset_y,
            color: spec.color,
            tolerance: spec.tolerance,
        });
    }
    Ok(samples)
}

/// Parses legacy absolute multi-point syntax.
///
/// # Errors
///
/// Rejects whitespace, negative/out-of-range coordinates, invalid colors or more than 256 points.
pub fn parse_fixed_pattern(input: &str) -> Result<Vec<FixedPatternSample>, LegacyParseError> {
    let mut parser = Parser::new(input)?;
    let mut samples = Vec::new();
    loop {
        if samples.len() == MAX_LEGACY_FIXED_SAMPLES {
            return Err(LegacyParseError::TooManyItems {
                maximum: MAX_LEGACY_FIXED_SAMPLES,
            });
        }
        let (x, y) = parser.unsigned_pair()?;
        parser.expect(b'|')?;
        let spec = parser.color_spec()?;
        samples.push(FixedPatternSample {
            point: PixelPoint { x, y },
            color: spec.color,
            tolerance: spec.tolerance,
        });
        if !parser.next_item()? {
            return Ok(samples);
        }
    }
}

/// Parses the legacy region-color list `(r,g,b)-(dr,dg,db)#...`.
///
/// # Errors
///
/// Rejects whitespace, malformed/range-invalid channels or more than 64 colors.
pub fn parse_color_list(input: &str) -> Result<Vec<ColorSpec>, LegacyParseError> {
    let mut parser = Parser::new(input)?;
    let mut colors = Vec::new();
    loop {
        if colors.len() == MAX_LEGACY_COLOR_SPECS {
            return Err(LegacyParseError::TooManyItems {
                maximum: MAX_LEGACY_COLOR_SPECS,
            });
        }
        colors.push(parser.color_spec()?);
        if !parser.next_item()? {
            return Ok(colors);
        }
    }
}

struct Parser<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Parser<'a> {
    fn new(input: &'a str) -> Result<Self, LegacyParseError> {
        if input.is_empty() {
            return Err(LegacyParseError::Empty);
        }
        Ok(Self {
            bytes: input.as_bytes(),
            offset: 0,
        })
    }

    fn expect(&mut self, wanted: u8) -> Result<(), LegacyParseError> {
        if self.bytes.get(self.offset) != Some(&wanted) {
            return Err(LegacyParseError::UnexpectedByte {
                offset: self.offset,
            });
        }
        self.offset += 1;
        Ok(())
    }

    fn next_item(&mut self) -> Result<bool, LegacyParseError> {
        if self.offset == self.bytes.len() {
            return Ok(false);
        }
        self.expect(b'#')?;
        // The original generator emits a trailing '#', while its copy UI strips it. Both forms
        // are canonical legacy output; consecutive separators remain invalid.
        Ok(self.offset != self.bytes.len())
    }

    fn signed_pair(&mut self) -> Result<(i32, i32), LegacyParseError> {
        self.expect(b'(')?;
        let first = self.signed_integer()?;
        self.expect(b',')?;
        let second = self.signed_integer()?;
        self.expect(b')')?;
        Ok((first, second))
    }

    fn unsigned_pair(&mut self) -> Result<(u32, u32), LegacyParseError> {
        self.expect(b'(')?;
        let first = self.unsigned_integer(u64::from(u32::MAX))?;
        self.expect(b',')?;
        let second = self.unsigned_integer(u64::from(u32::MAX))?;
        self.expect(b')')?;
        Ok((
            u32::try_from(first).map_err(|_| LegacyParseError::IntegerOutOfRange {
                offset: self.offset,
            })?,
            u32::try_from(second).map_err(|_| LegacyParseError::IntegerOutOfRange {
                offset: self.offset,
            })?,
        ))
    }

    fn color_spec(&mut self) -> Result<ColorSpec, LegacyParseError> {
        let (red, green, blue) = self.byte_triplet()?;
        self.expect(b'-')?;
        let (red_tolerance, green_tolerance, blue_tolerance) = self.byte_triplet()?;
        Ok(ColorSpec {
            color: Color {
                red,
                green,
                blue,
                alpha: 255,
            },
            tolerance: ColorTolerance {
                red: red_tolerance,
                green: green_tolerance,
                blue: blue_tolerance,
                alpha: 0,
            },
        })
    }

    fn byte_triplet(&mut self) -> Result<(u8, u8, u8), LegacyParseError> {
        self.expect(b'(')?;
        let first = self.byte_integer()?;
        self.expect(b',')?;
        let second = self.byte_integer()?;
        self.expect(b',')?;
        let third = self.byte_integer()?;
        self.expect(b')')?;
        Ok((first, second, third))
    }

    fn byte_integer(&mut self) -> Result<u8, LegacyParseError> {
        let start = self.offset;
        let value = self.unsigned_integer(u64::from(u8::MAX))?;
        u8::try_from(value).map_err(|_| LegacyParseError::IntegerOutOfRange { offset: start })
    }

    fn signed_integer(&mut self) -> Result<i32, LegacyParseError> {
        let start = self.offset;
        let negative = self.bytes.get(self.offset) == Some(&b'-');
        if negative {
            self.offset += 1;
        }
        let maximum = if negative {
            i64::from(i32::MAX).cast_unsigned() + 1
        } else {
            i64::from(i32::MAX).cast_unsigned()
        };
        let magnitude = self.unsigned_integer(maximum)?;
        let value = if negative {
            -i64::try_from(magnitude)
                .map_err(|_| LegacyParseError::IntegerOutOfRange { offset: start })?
        } else {
            i64::try_from(magnitude)
                .map_err(|_| LegacyParseError::IntegerOutOfRange { offset: start })?
        };
        i32::try_from(value).map_err(|_| LegacyParseError::IntegerOutOfRange { offset: start })
    }

    fn unsigned_integer(&mut self, maximum: u64) -> Result<u64, LegacyParseError> {
        let start = self.offset;
        let mut value = 0_u64;
        let mut digits = 0_usize;
        while let Some(byte @ b'0'..=b'9') = self.bytes.get(self.offset).copied() {
            value = value
                .checked_mul(10)
                .and_then(|number| number.checked_add(u64::from(byte - b'0')))
                .ok_or(LegacyParseError::IntegerOutOfRange { offset: start })?;
            if value > maximum {
                return Err(LegacyParseError::IntegerOutOfRange { offset: start });
            }
            self.offset += 1;
            digits += 1;
        }
        if digits == 0 {
            return Err(LegacyParseError::InvalidInteger { offset: start });
        }
        Ok(value)
    }
}

#[cfg(test)]
mod tests {
    use super::{
        format_color_list, format_fixed_pattern, format_relative_pattern, parse_color_list,
        parse_fixed_pattern, parse_relative_pattern, LegacyFormatError, LegacyParseError,
    };
    use crate::{Color, ColorSpec, ColorTolerance, PatternSample, PixelPoint};

    #[test]
    fn parses_generator_and_copy_ui_relative_forms() {
        let value = "(255,0,1)-(10,11,12)#(3,-2)|(0,255,0)-(5,6,7)#";
        let samples = parse_relative_pattern(value).expect("legacy relative pattern");
        assert_eq!(samples.len(), 2);
        assert_eq!((samples[1].offset_x, samples[1].offset_y), (3, -2));
        assert_eq!(samples[0].color.red, 255);
        assert_eq!(samples[1].tolerance.blue, 7);
    }

    #[test]
    fn parses_fixed_points_and_region_colors() {
        let fixed = parse_fixed_pattern("(211,322)|(255,255,255)-(50,50,50)#(0,1)|(1,2,3)-(4,5,6)")
            .expect("fixed pattern");
        assert_eq!(fixed[0].point, PixelPoint { x: 211, y: 322 });
        assert_eq!(fixed[1].color.blue, 3);
        let colors =
            parse_color_list("(255,255,255)-(50,50,50)#(255,0,0)-(1,2,3)").expect("region colors");
        assert_eq!(colors.len(), 2);
        assert_eq!(colors[1].tolerance.green, 2);
    }

    #[test]
    fn rejects_relaxed_or_ambiguous_syntax() {
        assert!(matches!(
            parse_relative_pattern(" (1,2,3)-(0,0,0)"),
            Err(LegacyParseError::UnexpectedByte { offset: 0 })
        ));
        assert!(matches!(
            parse_relative_pattern("(256,2,3)-(0,0,0)"),
            Err(LegacyParseError::IntegerOutOfRange { .. })
        ));
        assert!(parse_fixed_pattern("(-1,2)|(1,2,3)-(0,0,0)").is_err());
        assert!(parse_color_list("(1,2,3)-(0,0,0)##(4,5,6)-(0,0,0)").is_err());
    }

    #[test]
    fn structured_values_round_trip_through_canonical_legacy_text() {
        let relative_text = "(255,0,1)-(10,11,12)#(3,-2)|(0,255,0)-(5,6,7)#";
        let relative = parse_relative_pattern(relative_text).expect("relative parse");
        let canonical = format_relative_pattern(&relative).expect("relative format");
        assert_eq!(canonical, "(255,0,1)-(10,11,12)#(3,-2)|(0,255,0)-(5,6,7)");
        assert_eq!(parse_relative_pattern(&canonical), Ok(relative));

        let fixed = parse_fixed_pattern("(211,322)|(255,255,255)-(50,50,50)#(0,1)|(1,2,3)-(4,5,6)")
            .expect("fixed parse");
        let fixed_text = format_fixed_pattern(&fixed).expect("fixed format");
        assert_eq!(parse_fixed_pattern(&fixed_text), Ok(fixed));

        let colors =
            parse_color_list("(255,255,255)-(50,50,50)#(255,0,0)-(1,2,3)").expect("color parse");
        let color_text = format_color_list(&colors).expect("color format");
        assert_eq!(parse_color_list(&color_text), Ok(colors));
    }

    #[test]
    fn formatter_rejects_lossy_or_noncanonical_structures() {
        let invalid_anchor = [PatternSample {
            offset_x: 1,
            offset_y: 0,
            color: Color {
                red: 1,
                green: 2,
                blue: 3,
                alpha: 255,
            },
            tolerance: ColorTolerance::EXACT,
        }];
        assert_eq!(
            format_relative_pattern(&invalid_anchor),
            Err(LegacyFormatError::InvalidAnchorOffset)
        );
        let alpha = [ColorSpec {
            color: Color {
                red: 1,
                green: 2,
                blue: 3,
                alpha: 128,
            },
            tolerance: ColorTolerance::EXACT,
        }];
        assert_eq!(
            format_color_list(&alpha),
            Err(LegacyFormatError::UnsupportedAlpha)
        );
        assert_eq!(format_fixed_pattern(&[]), Err(LegacyFormatError::Empty));
    }
}
