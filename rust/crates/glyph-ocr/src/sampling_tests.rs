use super::*;
use crate::{decode_dictionary, encode_dictionary, GlyphSource};

fn reference_compare(binary: &BinaryImage, rect: GlyphRect, glyph: &GlyphBitmap) -> u16 {
    let width = rect.right - rect.left;
    let height = rect.bottom - rect.top;
    let mut mismatches = 0_u64;
    for gy in 0..glyph.height {
        for gx in 0..glyph.width {
            let x = rect.left + u32::from(gx) * width / u32::from(glyph.width);
            let y = rect.top + u32::from(gy) * height / u32::from(glyph.height);
            if binary.foreground(x, y) != glyph.foreground(gx, gy) {
                mismatches += 1;
            }
        }
    }
    let cells = u64::from(glyph.width) * u64::from(glyph.height);
    let shape = 1000 - mismatches * 1000 / cells;
    let delta = width.abs_diff(u32::from(glyph.width)) + height.abs_diff(u32::from(glyph.height));
    let base = width.max(u32::from(glyph.width)) + height.max(u32::from(glyph.height));
    u16::try_from(
        (shape * 4 + 1000_u64.saturating_sub(u64::from(delta) * 1000 / u64::from(base))) / 5,
    )
    .unwrap()
}

fn dictionary(width: u16, height: u16) -> GlyphDictionary {
    let cells = usize::from(width) * usize::from(height);
    let mut bits = vec![0; cells.div_ceil(8)];
    for bit in 0..cells {
        if bit % 7 < 3 {
            bits[bit / 8] |= 0x80 >> (bit % 8);
        }
    }
    decode_dictionary(
        &encode_dictionary(&[GlyphSource {
            label: "A".to_owned(),
            width,
            height,
            packed_bits: bits,
        }])
        .unwrap(),
    )
    .unwrap()
}

#[test]
fn sampled_scores_match_reference_for_offsets_shapes_and_packed_tails() {
    let binary = BinaryImage {
        width: 300,
        height: 270,
        pixels: (0..300 * 270).map(|i| i % 13 < 4).collect(),
    };
    for width in [1, 3, 9, 32, 128, 256] {
        for height in [1, 3, 17, 256] {
            let dictionary = dictionary(width, height);
            let glyph = &dictionary.glyphs()[0];
            for (w, h) in [(1, 1), (3, 7), (100, 60), (256, 256)] {
                let rect = GlyphRect {
                    left: 7,
                    top: 3,
                    right: 7 + w,
                    bottom: 3 + h,
                };
                assert_eq!(
                    compare(&binary, rect, glyph),
                    reference_compare(&binary, rect, glyph)
                );
            }
        }
    }
}

#[test]
fn contiguous_binarization_matches_checked_reference_with_padding_alpha_and_palette() {
    let bytes: Vec<_> = (0..83 * 64)
        .map(|i| u8::try_from(i * 13 % 256).unwrap())
        .collect();
    let extra = [GlyphColorRule {
        rgb: 0x00ff_ff00,
        tolerance: 31,
    }];
    for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
        let image = ImageView {
            width: 19,
            height: 64,
            row_stride: 83,
            format,
            pixels: &bytes,
        };
        let options = GlyphOcrOptions {
            roi: PixelRect {
                left: 2,
                top: 3,
                right: 18,
                bottom: 60,
            },
            foreground: Color {
                red: 50,
                green: 90,
                blue: 110,
                alpha: 255,
            },
            tolerance: ColorTolerance {
                red: 70,
                green: 10,
                blue: 30,
                alpha: 0,
            },
            minimum_similarity_permille: 900,
            space_gap_columns: 3,
        };
        let actual = binarize(image, options, &extra, &|| false).unwrap();
        let mut expected = Vec::new();
        for y in options.roi.top..options.roi.bottom {
            for x in options.roi.left..options.roi.right {
                let p = read_pixel(image, x, y).unwrap();
                expected.push(
                    matches_color(p, options.foreground, options.tolerance)
                        || (p.alpha == 255
                            && p.red.abs_diff(255) <= 31
                            && p.green.abs_diff(255) <= 31
                            && p.blue <= 31),
                );
            }
        }
        assert_eq!(actual.pixels, expected);
        assert!(matches!(
            binarize(image, options, &extra, &|| true),
            Err(GlyphOcrError::Cancelled)
        ));
    }
}

#[test]
#[ignore = "alternating glyph sampling CPU benchmark; not a device/script result"]
fn benchmark_glyph_sampling() {
    let dictionary = dictionary(32, 48);
    let glyph = &dictionary.glyphs()[0];
    let binary = BinaryImage {
        width: 300,
        height: 100,
        pixels: (0..30000).map(|i| i % 13 < 4).collect(),
    };
    let rect = GlyphRect {
        left: 10,
        top: 10,
        right: 48,
        bottom: 70,
    };
    let mut times = [Vec::new(), Vec::new()];
    for iteration in 0..111 {
        for variant in [iteration % 2, 1 - iteration % 2] {
            let start = std::time::Instant::now();
            for _ in 0..1000 {
                let score = if variant == 0 {
                    reference_compare(std::hint::black_box(&binary), rect, glyph)
                } else {
                    compare(std::hint::black_box(&binary), rect, glyph)
                };
                std::hint::black_box(score);
            }
            if iteration >= 11 {
                times[variant].push(start.elapsed().as_micros());
            }
        }
    }
    for (name, mut samples) in ["reference", "prepared-coordinates"].into_iter().zip(times) {
        samples.sort_unstable();
        println!(
            "glyph-{name}-1000: P50={} P95={} P99={}us",
            samples[49], samples[94], samples[98]
        );
    }
}
