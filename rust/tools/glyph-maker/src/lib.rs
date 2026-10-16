use glyph_ocr::{decode_dictionary, encode_dictionary, DictionaryFormatError, GlyphSource};
use serde::Deserialize;

pub const MAX_SOURCE_BYTES: usize = 8 * 1024 * 1024;
const MAX_PREVIEW_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct MakerDocument {
    format_version: u32,
    glyphs: Vec<MakerGlyph>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
struct MakerGlyph {
    label: String,
    rows: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MakerOutput {
    pub dictionary: Vec<u8>,
    pub preview_html: Vec<u8>,
    pub glyph_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MakerError {
    SourceTooLarge,
    InvalidJson(String),
    UnsupportedFormatVersion(u32),
    EmptyRows { glyph_index: usize },
    NonRectangular { glyph_index: usize },
    InvalidPixel { glyph_index: usize, byte: u8 },
    InvalidDimensions { glyph_index: usize },
    EmptyGlyph { glyph_index: usize },
    Dictionary(DictionaryFormatError),
    PreviewTooLarge,
}

/// Compiles a bounded JSON glyph source into canonical `ASGLYPH v1` and an HTML preview.
/// Empty outer rows and columns are removed so runtime segmentation and stored shapes agree.
///
/// # Errors
///
/// Rejects oversized/malformed JSON, non-rectangular grids, pixels other than `.` and `#`, empty
/// glyphs, invalid labels/dimensions, or outputs exceeding the preview budget.
pub fn compile_source(source: &[u8]) -> Result<MakerOutput, MakerError> {
    if source.len() > MAX_SOURCE_BYTES {
        return Err(MakerError::SourceTooLarge);
    }
    let document: MakerDocument = serde_json::from_slice(source)
        .map_err(|error| MakerError::InvalidJson(error.to_string()))?;
    if document.format_version != 1 {
        return Err(MakerError::UnsupportedFormatVersion(
            document.format_version,
        ));
    }
    let glyphs = document
        .glyphs
        .iter()
        .enumerate()
        .map(|(index, glyph)| compile_glyph(index, glyph))
        .collect::<Result<Vec<_>, _>>()?;
    let dictionary = encode_dictionary(&glyphs).map_err(MakerError::Dictionary)?;
    let decoded = decode_dictionary(&dictionary).map_err(MakerError::Dictionary)?;
    let preview_html = render_preview(decoded.glyphs())?;
    Ok(MakerOutput {
        dictionary,
        preview_html,
        glyph_count: glyphs.len(),
    })
}

fn compile_glyph(index: usize, glyph: &MakerGlyph) -> Result<GlyphSource, MakerError> {
    let Some(width) = glyph.rows.first().map(String::len) else {
        return Err(MakerError::EmptyRows { glyph_index: index });
    };
    if width == 0 {
        return Err(MakerError::EmptyRows { glyph_index: index });
    }
    if glyph.rows.iter().any(|row| row.len() != width) {
        return Err(MakerError::NonRectangular { glyph_index: index });
    }
    if width > 256 || glyph.rows.len() > 256 {
        return Err(MakerError::InvalidDimensions { glyph_index: index });
    }
    let mut foreground = Vec::with_capacity(width * glyph.rows.len());
    for byte in glyph.rows.iter().flat_map(|row| row.bytes()) {
        foreground.push(match byte {
            b'.' => false,
            b'#' => true,
            other => {
                return Err(MakerError::InvalidPixel {
                    glyph_index: index,
                    byte: other,
                });
            }
        });
    }
    let (left, top, right, bottom) = occupied_bounds(&foreground, width, glyph.rows.len())
        .ok_or(MakerError::EmptyGlyph { glyph_index: index })?;
    let cropped_width = right - left;
    let cropped_height = bottom - top;
    let bit_count = cropped_width * cropped_height;
    let mut packed_bits = vec![0_u8; bit_count.div_ceil(8)];
    for y in 0..cropped_height {
        for x in 0..cropped_width {
            if foreground[(top + y) * width + left + x] {
                let bit = y * cropped_width + x;
                packed_bits[bit / 8] |= 0x80 >> (bit % 8);
            }
        }
    }
    Ok(GlyphSource {
        label: glyph.label.clone(),
        width: u16::try_from(cropped_width)
            .map_err(|_| MakerError::InvalidDimensions { glyph_index: index })?,
        height: u16::try_from(cropped_height)
            .map_err(|_| MakerError::InvalidDimensions { glyph_index: index })?,
        packed_bits,
    })
}

fn occupied_bounds(
    pixels: &[bool],
    width: usize,
    height: usize,
) -> Option<(usize, usize, usize, usize)> {
    let mut left = width;
    let mut top = height;
    let mut right = 0;
    let mut bottom = 0;
    for y in 0..height {
        for x in 0..width {
            if pixels[y * width + x] {
                left = left.min(x);
                top = top.min(y);
                right = right.max(x + 1);
                bottom = bottom.max(y + 1);
            }
        }
    }
    (left < right && top < bottom).then_some((left, top, right, bottom))
}

fn render_preview(glyphs: &[glyph_ocr::GlyphBitmap]) -> Result<Vec<u8>, MakerError> {
    let mut html = String::from(
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ASGLYPH预览</title><style>body{font:14px system-ui;margin:16px;background:#f4f6f8;color:#1f2933}.glyph{display:inline-block;vertical-align:top;background:#fff;border:1px solid #ccd3da;border-radius:6px;padding:8px;margin:4px}pre{font:16px/1 monospace;margin:6px 0 0;letter-spacing:2px}</style></head><body><h1>ASGLYPH v1 字库预览</h1>",
    );
    for glyph in glyphs {
        html.push_str("<section class=\"glyph\"><strong>");
        escape_html_into(&glyph.label, &mut html);
        html.push_str("</strong><div>");
        html.push_str(&glyph.width.to_string());
        html.push('×');
        html.push_str(&glyph.height.to_string());
        html.push_str("</div><pre>");
        for y in 0..glyph.height {
            for x in 0..glyph.width {
                html.push(if glyph.foreground(x, y) { '█' } else { '·' });
            }
            html.push('\n');
        }
        html.push_str("</pre></section>");
        if html.len() > MAX_PREVIEW_BYTES {
            return Err(MakerError::PreviewTooLarge);
        }
    }
    html.push_str("</body></html>");
    Ok(html.into_bytes())
}

fn escape_html_into(value: &str, output: &mut String) {
    for character in value.chars() {
        match character {
            '&' => output.push_str("&amp;"),
            '<' => output.push_str("&lt;"),
            '>' => output.push_str("&gt;"),
            '"' => output.push_str("&quot;"),
            '\'' => output.push_str("&#39;"),
            other => output.push(other),
        }
    }
}

#[cfg(test)]
mod tests {
    use glyph_ocr::decode_dictionary;

    use super::{compile_source, MakerError};

    #[test]
    fn outer_whitespace_is_cropped_and_output_is_deterministic() {
        let source = br#"{"formatVersion":1,"glyphs":[{"label":"A","rows":[".....","..#..",".#.#.",".###.","....."]}]}"#;
        let first = compile_source(source).expect("compile");
        let second = compile_source(source).expect("compile again");
        assert_eq!(first, second);
        let dictionary = decode_dictionary(&first.dictionary).expect("decode");
        assert_eq!(dictionary.glyphs()[0].width, 3);
        assert_eq!(dictionary.glyphs()[0].height, 3);
        assert!(std::str::from_utf8(&first.preview_html)
            .expect("preview UTF-8")
            .contains("ASGLYPH v1"));
    }

    #[test]
    fn malformed_grids_fail_closed() {
        let non_rectangular =
            br###"{"formatVersion":1,"glyphs":[{"label":"A","rows":["#","##"]}]}"###;
        assert!(matches!(
            compile_source(non_rectangular),
            Err(MakerError::NonRectangular { .. })
        ));
        let invalid_pixel = br#"{"formatVersion":1,"glyphs":[{"label":"A","rows":["x"]}]}"#;
        assert!(matches!(
            compile_source(invalid_pixel),
            Err(MakerError::InvalidPixel { byte: b'x', .. })
        ));
    }

    #[test]
    fn preview_escapes_labels() {
        let source = br##"{"formatVersion":1,"glyphs":[{"label":"<A>","rows":["#"]}]}"##;
        let output = compile_source(source).expect("compile");
        let preview = std::str::from_utf8(&output.preview_html).expect("UTF-8");
        assert!(preview.contains("&lt;A&gt;"));
        assert!(!preview.contains("<strong><A>"));
    }
}
