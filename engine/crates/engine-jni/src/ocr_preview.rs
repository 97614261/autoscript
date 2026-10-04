//! Independent offline OCR: no Lua session, dictionary store or script resource is changed.
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use glyph_ocr::{decode_dictionary, recognize_cancellable, GlyphOcrError, GlyphOcrOptions};
use pixel_vision::{Color, ColorTolerance, ImageView, PixelRect};
use serde::Serialize;

static CANCELLED: OnceLock<Mutex<VecDeque<i64>>> = OnceLock::new();

pub(crate) fn cancel(id: i64) {
    let mut ids = CANCELLED
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if !ids.contains(&id) {
        ids.push_back(id);
    }
    while ids.len() > 64 {
        ids.pop_front();
    }
}

fn is_cancelled(id: i64) -> bool {
    CANCELLED
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .contains(&id)
}

#[derive(Serialize, Debug)]
pub(crate) struct Reply {
    pub status: i32,
    pub text: String,
    pub score: u16,
    pub coverage: u16,
}

impl Reply {
    fn failure(status: i32) -> Self {
        Self {
            status,
            text: String::new(),
            score: 0,
            coverage: 0,
        }
    }
}

pub(crate) fn recognize(
    id: i64,
    image: ImageView<'_>,
    dictionary: &[u8],
    options: &[i32],
) -> Reply {
    let start = Instant::now();
    let cancelled = || is_cancelled(id) || start.elapsed() >= Duration::from_secs(5);
    let result = (|| {
        if id <= 0
            || options.len() != 8
            || image.width > 4096
            || image.height > 4096
            || u64::from(image.width) * u64::from(image.height) > 4_194_304
        {
            return Err(GlyphOcrError::InvalidOptions);
        }
        let values: Vec<u32> = options
            .iter()
            .map(|n| u32::try_from(*n))
            .collect::<Result<_, _>>()
            .map_err(|_| GlyphOcrError::InvalidOptions)?;
        if values[4] > 1000 || values[5] > 0xFF_FFFF || values[6] > 255 || values[7] > 65535 {
            return Err(GlyphOcrError::InvalidOptions);
        }
        let dictionary =
            decode_dictionary(dictionary).map_err(|_| GlyphOcrError::InvalidOptions)?;
        let foreground = values[5];
        let tolerance = u8::try_from(values[6]).map_err(|_| GlyphOcrError::InvalidOptions)?;
        recognize_cancellable(
            image,
            &dictionary,
            GlyphOcrOptions {
                roi: PixelRect {
                    left: values[0],
                    top: values[1],
                    right: values[2],
                    bottom: values[3],
                },
                foreground: Color {
                    red: ((foreground >> 16) & 255) as u8,
                    green: ((foreground >> 8) & 255) as u8,
                    blue: (foreground & 255) as u8,
                    alpha: 255,
                },
                tolerance: ColorTolerance {
                    red: tolerance,
                    green: tolerance,
                    blue: tolerance,
                    alpha: 255,
                },
                minimum_similarity_permille: u16::try_from(values[4])
                    .map_err(|_| GlyphOcrError::InvalidOptions)?,
                space_gap_columns: u16::try_from(values[7])
                    .map_err(|_| GlyphOcrError::InvalidOptions)?,
            },
            cancelled,
        )
    })();
    let reply = if is_cancelled(id) {
        Reply::failure(6)
    } else if start.elapsed() >= Duration::from_secs(5) {
        Reply::failure(7)
    } else {
        match result {
            Ok(result) if result.text.len() <= 65_536 => Reply {
                status: i32::from(result.total_glyphs == 0),
                text: result.text,
                score: result.average_score_permille,
                coverage: result.coverage_permille,
            },
            Err(
                GlyphOcrError::PixelBudgetExceeded
                | GlyphOcrError::SegmentLimitExceeded
                | GlyphOcrError::ComparisonBudgetExceeded,
            ) => Reply::failure(5),
            Err(GlyphOcrError::Cancelled) => Reply::failure(6),
            _ => Reply::failure(2),
        }
    };
    CANCELLED
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .retain(|value| *value != id);
    reply
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::template_preview::image;
    use glyph_ocr::{encode_dictionary, GlyphSource};

    #[test]
    fn actual_dictionary_text_and_cancellation_are_not_simulated() {
        let dictionary = encode_dictionary(&[GlyphSource {
            label: "A".into(),
            width: 1,
            height: 1,
            packed_bits: vec![0x80],
        }])
        .unwrap();
        let pixels = [255, 255, 255, 255];
        let args = [0, 0, 1, 1, 1000, 0xFF_FFFF, 0, 0];
        let found = recognize(10001, image(1, 1, &pixels), &dictionary, &args);
        assert_eq!(
            (
                found.status,
                found.text.as_str(),
                found.score,
                found.coverage
            ),
            (0, "A", 1000, 1000)
        );
        cancel(10002);
        assert_eq!(
            recognize(10002, image(1, 1, &pixels), &dictionary, &args).status,
            6
        );
        assert_eq!(
            recognize(10003, image(1, 1, &pixels), b"invalid", &args).status,
            2
        );
    }

    #[test]
    fn glyph_computation_has_cooperative_checkpoints() {
        let dictionary = glyph_ocr::decode_dictionary(
            &encode_dictionary(&[GlyphSource {
                label: "A".into(),
                width: 1,
                height: 1,
                packed_bits: vec![0x80],
            }])
            .unwrap(),
        )
        .unwrap();
        let checks = std::cell::Cell::new(0_u32);
        let options = GlyphOcrOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: 1,
                bottom: 3,
            },
            foreground: Color {
                red: 255,
                green: 255,
                blue: 255,
                alpha: 255,
            },
            tolerance: ColorTolerance {
                red: 0,
                green: 0,
                blue: 0,
                alpha: 255,
            },
            minimum_similarity_permille: 1000,
            space_gap_columns: 0,
        };
        let result = recognize_cancellable(image(1, 3, &[255; 12]), &dictionary, options, || {
            checks.set(checks.get() + 1);
            checks.get() >= 3
        });
        assert_eq!(result, Err(GlyphOcrError::Cancelled));
        assert_eq!(checks.get(), 3);
    }
}
