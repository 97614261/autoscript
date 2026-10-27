use pixel_vision::{Color, ColorTolerance, ImageView, PixelFormat, PixelRect};

use crate::palette::{validate_additional_colors, GlyphColorRule, MAX_COLOR_COMPARISONS};
use crate::{GlyphBitmap, GlyphDictionary};

#[cfg(test)]
#[path = "sampling_tests.rs"]
mod sampling_tests;

const MAX_ROI_PIXELS: u64 = 4 * 1024 * 1024;
const MAX_SEGMENTS: usize = 2_048;
const MAX_GLYPH_COMPARISONS: u64 = 64 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlyphOcrOptions {
    pub roi: PixelRect,
    pub foreground: Color,
    pub tolerance: ColorTolerance,
    pub minimum_similarity_permille: u16,
    pub space_gap_columns: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlyphRect {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphMatch {
    pub text: String,
    pub rect: GlyphRect,
    pub score_permille: u16,
    pub recognized: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GlyphOcrResult {
    pub text: String,
    pub matches: Vec<GlyphMatch>,
    pub recognized_glyphs: u32,
    pub total_glyphs: u32,
    pub coverage_permille: u16,
    pub average_score_permille: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GlyphOcrError {
    InvalidImage,
    InvalidRegion,
    InvalidOptions,
    PixelBudgetExceeded,
    SegmentLimitExceeded,
    ComparisonBudgetExceeded,
    Cancelled,
}

#[derive(Debug)]
struct BinaryImage {
    width: u32,
    height: u32,
    pixels: Vec<bool>,
}

impl BinaryImage {
    fn foreground(&self, x: u32, y: u32) -> bool {
        if x >= self.width || y >= self.height {
            return false;
        }
        // BinaryImage is constructed only after the bounded ROI geometry is validated.
        self.pixels[y as usize * self.width as usize + x as usize]
    }
}

/// Recognizes fixed-font text in deterministic top-to-bottom, left-to-right order.
///
/// # Errors
///
/// Rejects malformed frames/regions/options and fails closed on pixel, segment, or comparison
/// budget exhaustion.
pub fn recognize(
    image: ImageView<'_>,
    dictionary: &GlyphDictionary,
    options: GlyphOcrOptions,
) -> Result<GlyphOcrResult, GlyphOcrError> {
    recognize_cancellable(image, dictionary, options, || false)
}

/// The same recognizer with bounded cancellation checkpoints between rows, lines and glyphs.
///
/// # Errors
/// Returns the ordinary recognition errors, or `Cancelled` when the caller revokes the request.
pub fn recognize_cancellable(
    image: ImageView<'_>,
    dictionary: &GlyphDictionary,
    options: GlyphOcrOptions,
    cancelled: impl Fn() -> bool,
) -> Result<GlyphOcrResult, GlyphOcrError> {
    recognize_palette_cancellable(image, dictionary, options, &[], cancelled)
}

/// Recognizes the union of the primary foreground and explicit additional colors.
/// # Errors
/// Rejects duplicate/invalid palettes and excessive color work, retaining ordinary cancellation.
pub fn recognize_palette_cancellable(
    image: ImageView<'_>,
    dictionary: &GlyphDictionary,
    options: GlyphOcrOptions,
    additional: &[GlyphColorRule],
    cancelled: impl Fn() -> bool,
) -> Result<GlyphOcrResult, GlyphOcrError> {
    check_cancelled(&cancelled)?;
    validate(image, options)?;
    let primary = u32::from(options.foreground.red) << 16
        | u32::from(options.foreground.green) << 8
        | u32::from(options.foreground.blue);
    validate_additional_colors(primary, additional).map_err(|_| GlyphOcrError::InvalidOptions)?;
    if u64::from(options.roi.width())
        * u64::from(options.roi.height())
        * (additional.len() as u64 + 1)
        > MAX_COLOR_COMPARISONS
    {
        return Err(GlyphOcrError::PixelBudgetExceeded);
    }
    let binary = binarize(image, options, additional, &cancelled)?;
    let lines = occupied_runs(binary.height, |y| {
        (0..binary.width).any(|x| binary.foreground(x, y))
    });
    let mut text = String::new();
    let mut matches = Vec::new();
    let mut comparisons = 0_u64;
    for (line_index, (top, bottom)) in lines.into_iter().enumerate() {
        check_cancelled(&cancelled)?;
        if line_index > 0 {
            text.push('\n');
        }
        recognize_line(
            &binary,
            dictionary,
            options,
            top,
            bottom,
            &mut comparisons,
            &mut text,
            &mut matches,
            &cancelled,
        )?;
    }
    summarize(text, matches)
}

#[allow(clippy::too_many_arguments)]
fn recognize_line(
    binary: &BinaryImage,
    dictionary: &GlyphDictionary,
    options: GlyphOcrOptions,
    top: u32,
    bottom: u32,
    comparisons: &mut u64,
    text: &mut String,
    matches: &mut Vec<GlyphMatch>,
    cancelled: &impl Fn() -> bool,
) -> Result<(), GlyphOcrError> {
    let segments = occupied_runs(binary.width, |x| {
        (top..bottom).any(|y| binary.foreground(x, y))
    });
    if matches.len().saturating_add(segments.len()) > MAX_SEGMENTS {
        return Err(GlyphOcrError::SegmentLimitExceeded);
    }
    let mut previous_right = None;
    for (left, right) in segments {
        check_cancelled(cancelled)?;
        if previous_right.is_some_and(|previous| {
            left.saturating_sub(previous) >= u32::from(options.space_gap_columns)
                && options.space_gap_columns > 0
        }) {
            text.push(' ');
        }
        let rect = GlyphRect {
            left,
            top,
            right,
            bottom,
        };
        let (label, score) = best_match(binary, rect, dictionary, comparisons, cancelled)?;
        let recognized = score >= options.minimum_similarity_permille;
        let output = if recognized { label } else { "?" };
        text.push_str(output);
        matches.push(GlyphMatch {
            text: output.to_owned(),
            rect: GlyphRect {
                left: rect.left + options.roi.left,
                top: rect.top + options.roi.top,
                right: rect.right + options.roi.left,
                bottom: rect.bottom + options.roi.top,
            },
            score_permille: score,
            recognized,
        });
        previous_right = Some(right);
    }
    Ok(())
}

fn best_match<'a>(
    binary: &BinaryImage,
    rect: GlyphRect,
    dictionary: &'a GlyphDictionary,
    comparisons: &mut u64,
    cancelled: &impl Fn() -> bool,
) -> Result<(&'a str, u16), GlyphOcrError> {
    let mut best_label = "?";
    let mut best_score = 0;
    for glyph in dictionary.glyphs() {
        check_cancelled(cancelled)?;
        let cells = u64::from(glyph.width) * u64::from(glyph.height);
        *comparisons = comparisons
            .checked_add(cells)
            .ok_or(GlyphOcrError::ComparisonBudgetExceeded)?;
        if *comparisons > MAX_GLYPH_COMPARISONS {
            return Err(GlyphOcrError::ComparisonBudgetExceeded);
        }
        let score = compare(binary, rect, glyph);
        if score > best_score {
            best_score = score;
            best_label = &glyph.label;
        }
    }
    Ok((best_label, best_score))
}

fn compare(binary: &BinaryImage, rect: GlyphRect, glyph: &GlyphBitmap) -> u16 {
    let candidate_width = rect.right - rect.left;
    let candidate_height = rect.bottom - rect.top;
    let mut mismatches = 0_u64;
    let mut columns = [0_usize; 256];
    let width = usize::from(glyph.width);
    for (x, column) in columns[..width].iter_mut().enumerate() {
        *column = rect.left as usize + x * candidate_width as usize / width;
    }
    let bits = glyph.packed_bits();
    for glyph_y in 0..usize::from(glyph.height) {
        let y = rect.top as usize + glyph_y * candidate_height as usize / usize::from(glyph.height);
        let row = &binary.pixels[y * binary.width as usize..(y + 1) * binary.width as usize];
        for (glyph_x, x) in columns[..width].iter().copied().enumerate() {
            let bit = glyph_y * width + glyph_x;
            if row[x] != (bits[bit / 8] & (0x80 >> (bit % 8)) != 0) {
                mismatches += 1;
            }
        }
    }
    let cells = u64::from(glyph.width) * u64::from(glyph.height);
    let shape = 1_000_u64.saturating_sub(mismatches * 1_000 / cells);
    let width_delta = candidate_width.abs_diff(u32::from(glyph.width));
    let height_delta = candidate_height.abs_diff(u32::from(glyph.height));
    let dimension_base =
        candidate_width.max(u32::from(glyph.width)) + candidate_height.max(u32::from(glyph.height));
    let dimension = 1_000_u64
        .saturating_sub(u64::from(width_delta + height_delta) * 1_000 / u64::from(dimension_base));
    u16::try_from((shape * 4 + dimension) / 5).unwrap_or(0)
}

fn summarize(text: String, matches: Vec<GlyphMatch>) -> Result<GlyphOcrResult, GlyphOcrError> {
    let total = u32::try_from(matches.len()).map_err(|_| GlyphOcrError::SegmentLimitExceeded)?;
    let recognized = u32::try_from(matches.iter().filter(|item| item.recognized).count())
        .map_err(|_| GlyphOcrError::SegmentLimitExceeded)?;
    let coverage = if total == 0 {
        1_000
    } else {
        u16::try_from(u64::from(recognized) * 1_000 / u64::from(total)).unwrap_or(0)
    };
    let score = if total == 0 {
        1_000
    } else {
        let sum = matches
            .iter()
            .map(|item| u64::from(item.score_permille))
            .sum::<u64>();
        u16::try_from(sum / u64::from(total)).unwrap_or(0)
    };
    Ok(GlyphOcrResult {
        text,
        matches,
        recognized_glyphs: recognized,
        total_glyphs: total,
        coverage_permille: coverage,
        average_score_permille: score,
    })
}

fn occupied_runs(length: u32, mut occupied: impl FnMut(u32) -> bool) -> Vec<(u32, u32)> {
    let mut runs = Vec::new();
    let mut start = None;
    for index in 0..length {
        match (start, occupied(index)) {
            (None, true) => start = Some(index),
            (Some(first), false) => {
                runs.push((first, index));
                start = None;
            }
            _ => {}
        }
    }
    if let Some(first) = start {
        runs.push((first, length));
    }
    runs
}

fn validate(image: ImageView<'_>, options: GlyphOcrOptions) -> Result<(), GlyphOcrError> {
    let minimum_stride = usize::try_from(image.width)
        .ok()
        .and_then(|width| width.checked_mul(4))
        .ok_or(GlyphOcrError::InvalidImage)?;
    let required = image
        .row_stride
        .checked_mul(usize::try_from(image.height).map_err(|_| GlyphOcrError::InvalidImage)?)
        .ok_or(GlyphOcrError::InvalidImage)?;
    if image.width == 0
        || image.height == 0
        || image.row_stride < minimum_stride
        || image.pixels.len() < required
    {
        return Err(GlyphOcrError::InvalidImage);
    }
    if options.roi.left >= options.roi.right
        || options.roi.top >= options.roi.bottom
        || options.roi.right > image.width
        || options.roi.bottom > image.height
    {
        return Err(GlyphOcrError::InvalidRegion);
    }
    if options.minimum_similarity_permille > 1_000 {
        return Err(GlyphOcrError::InvalidOptions);
    }
    let pixels = u64::from(options.roi.width()) * u64::from(options.roi.height());
    if pixels > MAX_ROI_PIXELS {
        return Err(GlyphOcrError::PixelBudgetExceeded);
    }
    Ok(())
}

fn check_cancelled(cancelled: &impl Fn() -> bool) -> Result<(), GlyphOcrError> {
    if cancelled() {
        Err(GlyphOcrError::Cancelled)
    } else {
        Ok(())
    }
}

fn binarize(
    image: ImageView<'_>,
    options: GlyphOcrOptions,
    additional: &[GlyphColorRule],
    cancelled: &impl Fn() -> bool,
) -> Result<BinaryImage, GlyphOcrError> {
    let capacity = usize::try_from(options.roi.width())
        .ok()
        .and_then(|width| {
            usize::try_from(options.roi.height())
                .ok()
                .and_then(|height| width.checked_mul(height))
        })
        .ok_or(GlyphOcrError::PixelBudgetExceeded)?;
    let mut pixels = Vec::with_capacity(capacity);
    for y in options.roi.top..options.roi.bottom {
        check_cancelled(cancelled)?;
        let start = y as usize * image.row_stride + options.roi.left as usize * 4;
        let row = &image.pixels[start..start + options.roi.width() as usize * 4];
        for pixel in row.chunks_exact(4) {
            let (red, blue) = match image.format {
                PixelFormat::Rgba8888 => (pixel[0], pixel[2]),
                PixelFormat::Bgra8888 => (pixel[2], pixel[0]),
            };
            let actual = Color {
                red,
                green: pixel[1],
                blue,
                alpha: pixel[3],
            };
            pixels.push(
                matches_color(actual, options.foreground, options.tolerance)
                    || (actual.alpha == 255
                        && additional.iter().any(|rule| {
                            let [_, red, green, blue] = rule.rgb.to_be_bytes();
                            actual.red.abs_diff(red) <= rule.tolerance
                                && actual.green.abs_diff(green) <= rule.tolerance
                                && actual.blue.abs_diff(blue) <= rule.tolerance
                        })),
            );
        }
    }
    Ok(BinaryImage {
        width: options.roi.width(),
        height: options.roi.height(),
        pixels,
    })
}

#[cfg(test)]
fn read_pixel(image: ImageView<'_>, x: u32, y: u32) -> Result<Color, GlyphOcrError> {
    let offset = usize::try_from(y)
        .ok()
        .and_then(|y| y.checked_mul(image.row_stride))
        .and_then(|row| {
            usize::try_from(x)
                .ok()
                .and_then(|x| x.checked_mul(4))
                .and_then(|x| row.checked_add(x))
        })
        .ok_or(GlyphOcrError::InvalidImage)?;
    let pixel = image
        .pixels
        .get(offset..offset + 4)
        .ok_or(GlyphOcrError::InvalidImage)?;
    Ok(match image.format {
        PixelFormat::Rgba8888 => Color {
            red: pixel[0],
            green: pixel[1],
            blue: pixel[2],
            alpha: pixel[3],
        },
        PixelFormat::Bgra8888 => Color {
            red: pixel[2],
            green: pixel[1],
            blue: pixel[0],
            alpha: pixel[3],
        },
    })
}

fn matches_color(actual: Color, expected: Color, tolerance: ColorTolerance) -> bool {
    actual.red.abs_diff(expected.red) <= tolerance.red
        && actual.green.abs_diff(expected.green) <= tolerance.green
        && actual.blue.abs_diff(expected.blue) <= tolerance.blue
        && actual.alpha.abs_diff(expected.alpha) <= tolerance.alpha
}

#[cfg(test)]
mod tests {
    use pixel_vision::{Color, ColorTolerance, ImageView, PixelFormat, PixelRect};

    use crate::{decode_dictionary, encode_dictionary, GlyphSource};

    use super::{
        recognize, recognize_palette_cancellable, GlyphColorRule, GlyphOcrError, GlyphOcrOptions,
    };

    fn dictionary() -> crate::GlyphDictionary {
        decode_dictionary(
            &encode_dictionary(&[
                GlyphSource {
                    label: "A".to_owned(),
                    width: 3,
                    height: 3,
                    packed_bits: vec![0b0101_0111, 0b1000_0000],
                },
                GlyphSource {
                    label: "I".to_owned(),
                    width: 1,
                    height: 3,
                    packed_bits: vec![0b1110_0000],
                },
            ])
            .expect("encode"),
        )
        .expect("decode")
    }

    #[test]
    fn palette_unions_colors_with_independent_tolerance_and_preserves_single_color_calls() {
        let pixels = [255, 0, 0, 255, 0, 247, 0, 255, 255, 0, 0, 255];
        let image = ImageView {
            width: 1,
            height: 3,
            row_stride: 4,
            format: PixelFormat::Rgba8888,
            pixels: &pixels,
        };
        let options = GlyphOcrOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 3,
            },
            foreground: Color {
                red: 255,
                green: 0,
                blue: 0,
                alpha: 255,
            },
            tolerance: ColorTolerance::EXACT,
            minimum_similarity_permille: 1000,
            space_gap_columns: 0,
        };
        let extra = [GlyphColorRule {
            rgb: 0x0000_ff00,
            tolerance: 8,
        }];
        let ordinary = recognize(image, &dictionary(), options).unwrap();
        let result =
            recognize_palette_cancellable(image, &dictionary(), options, &extra, || false).unwrap();
        assert_eq!(result.text, "I");
        assert_eq!(result.coverage_permille, 1000);
        assert_ne!(ordinary.text, "I");
        assert_eq!(
            ordinary,
            recognize_palette_cancellable(image, &dictionary(), options, &[], || false).unwrap()
        );
        assert_eq!(
            recognize_palette_cancellable(image, &dictionary(), options, &extra, || true),
            Err(GlyphOcrError::Cancelled)
        );
        assert_eq!(
            recognize_palette_cancellable(
                image,
                &dictionary(),
                options,
                &[GlyphColorRule {
                    rgb: 0x00ff_0000,
                    tolerance: 8
                }],
                || false
            ),
            Err(GlyphOcrError::InvalidOptions)
        );
    }

    #[test]
    fn exact_fixture_recognizes_lines_in_stable_order() {
        let mut pixels = vec![0_u8; 7 * 7 * 4];
        for (x, y) in [
            (1, 0),
            (0, 1),
            (2, 1),
            (0, 2),
            (1, 2),
            (2, 2),
            (6, 0),
            (6, 1),
            (6, 2),
        ] {
            let offset = (y * 7 + x) * 4;
            pixels[offset..offset + 4].copy_from_slice(&[255, 255, 255, 255]);
        }
        let result = recognize(
            ImageView {
                width: 7,
                height: 7,
                row_stride: 28,
                format: PixelFormat::Rgba8888,
                pixels: &pixels,
            },
            &dictionary(),
            GlyphOcrOptions {
                roi: PixelRect {
                    left: 0,
                    top: 0,
                    right: 7,
                    bottom: 7,
                },
                foreground: Color {
                    red: 255,
                    green: 255,
                    blue: 255,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
                minimum_similarity_permille: 1_000,
                space_gap_columns: 3,
            },
        )
        .expect("ocr");
        assert_eq!(result.text, "A I");
        assert_eq!(result.coverage_permille, 1_000);
        assert_eq!(result.average_score_permille, 1_000);
    }

    #[test]
    fn studio_batch_fixture_recognizes_a_decimal_with_preserved_baseline() {
        // Exact output bytes asserted in Studio GlyphDictionaryMakerTest.
        let bytes = [
            0x41, 0x53, 0x47, 0x4c, 0x59, 0x50, 0x48, 0, 1, 0, 0, 0, 2, 0, 0, 0, 1, 0, 1, 0, 3, 0,
            0, 0, 1, 0, 0, 0, 0x31, 0xe0, 1, 0, 1, 0, 3, 0, 0, 0, 1, 0, 0, 0, 0x2e, 0x20,
        ];
        let dictionary = decode_dictionary(&bytes).expect("Studio batch dictionary");
        let mut pixels = vec![0_u8; 5 * 3 * 4];
        for (y, row) in ["#...#", "#...#", "#.#.#"].iter().enumerate() {
            for (x, byte) in row.bytes().enumerate() {
                if byte == b'#' {
                    let offset = (y * 5 + x) * 4;
                    pixels[offset..offset + 4].copy_from_slice(&[255, 255, 255, 255]);
                }
            }
        }
        let result = recognize(
            ImageView {
                width: 5,
                height: 3,
                row_stride: 20,
                format: PixelFormat::Rgba8888,
                pixels: &pixels,
            },
            &dictionary,
            GlyphOcrOptions {
                roi: PixelRect {
                    left: 0,
                    top: 0,
                    right: 5,
                    bottom: 3,
                },
                foreground: Color {
                    red: 255,
                    green: 255,
                    blue: 255,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
                minimum_similarity_permille: 1_000,
                space_gap_columns: 0,
            },
        )
        .expect("fixed decimal");
        assert_eq!(result.text, "1.1");
        assert_eq!(result.coverage_permille, 1_000);
    }
}
