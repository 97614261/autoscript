use glyph_ocr::GlyphOcrResult;
use lua_runtime::{LuaInput, LuaScalar};
use serde::Deserialize;
use serde_json::json;

pub(crate) fn encode(result: GlyphOcrResult) -> Result<LuaInput, String> {
    let matches: Vec<_> = result.matches.into_iter().map(|m| json!({"text":m.text,"left":m.rect.left,"top":m.rect.top,"right":m.rect.right,"bottom":m.rect.bottom,"score":m.score_permille,"recognized":m.recognized})).collect();
    output(
        &json!({"text":result.text,"coveragePermille":result.coverage_permille,"averageScorePermille":result.average_score_permille,"matches":matches}),
    )
}
fn output(value: &serde_json::Value) -> Result<LuaInput, String> {
    let bytes = serde_json::to_vec(value).map_err(|_| "invalid result")?;
    if bytes.len() > 65536 {
        return Err("OCR result exceeds byte budget".into());
    }
    Ok(LuaInput::Values(vec![
        LuaScalar::Boolean(true),
        LuaScalar::Bytes(bytes),
    ]))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Details {
    text: String,
    #[serde(rename = "coveragePermille")]
    coverage: u16,
    #[serde(rename = "averageScorePermille")]
    average: u16,
    matches: Vec<Match>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Match {
    text: String,
    left: u32,
    top: u32,
    right: u32,
    bottom: u32,
    score: u16,
    recognized: bool,
}
pub(crate) fn find(args: &[LuaScalar]) -> Result<LuaInput, String> {
    let [LuaScalar::Bytes(details), LuaScalar::Bytes(target)] = args else {
        return Err("findText requires JSON and target text".into());
    };
    if details.len() > 65536 || target.is_empty() || target.len() > 256 {
        return Err("text result exceeds budget".into());
    }
    let target = std::str::from_utf8(target).map_err(|_| "target must be UTF-8")?;
    if target.chars().any(char::is_whitespace) {
        return Err("target must be a contiguous glyph sequence without whitespace".into());
    }
    let details: Details = serde_json::from_slice(details).map_err(|_| "invalid OCR details")?;
    if details.text.len() > 32768
        || details.coverage > 1000
        || details.average > 1000
        || details.matches.len() > 2048
        || details.matches.iter().any(|m| {
            m.text.is_empty()
                || m.text.len() > 256
                || m.score > 1000
                || m.left >= m.right
                || m.top >= m.bottom
                || m.right > 32768
                || m.bottom > 32768
        })
    {
        return Err("invalid OCR details bounds".into());
    }
    let mut result = Vec::new();
    for (start, first) in details.matches.iter().enumerate() {
        if !first.recognized {
            continue;
        }
        let mut text = String::new();
        let (mut top, mut bottom, mut right) = (first.top, first.bottom, first.left);
        let (mut union_top, mut union_bottom) = (first.top, first.bottom);
        for item in &details.matches[start..] {
            if !item.recognized || item.left < right || item.top >= bottom || item.bottom <= top {
                break;
            }
            text.push_str(&item.text);
            right = item.right;
            top = top.max(item.top);
            bottom = bottom.min(item.bottom);
            union_top = union_top.min(item.top);
            union_bottom = union_bottom.max(item.bottom);
            if !target.starts_with(&text) {
                break;
            }
            if text == target {
                result.push(json!({"left":first.left,"top":union_top,"right":right,"bottom":union_bottom,"text":target}));
                break;
            }
        }
        if result.len() == 256 {
            break;
        }
    }
    output(&json!(result))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn search(details: &serde_json::Value, target: &str) -> Vec<serde_json::Value> {
        let LuaInput::Values(output) = find(&[
            LuaScalar::Bytes(details.to_string().into_bytes()),
            LuaScalar::Bytes(target.as_bytes().to_vec()),
        ])
        .unwrap() else {
            panic!()
        };
        let LuaScalar::Bytes(bytes) = &output[1] else {
            panic!()
        };
        serde_json::from_slice(bytes).unwrap()
    }

    #[test]
    fn unknown_glyphs_outside_target_do_not_veto_real_contiguous_matches() {
        let matches: Vec<_> = [("?", false), ("A", true), ("B", true), ("?", false)]
            .into_iter()
            .enumerate()
            .map(|(index, (text, recognized))| {
                json!({"text":text,"recognized":recognized,"left":index*3,"top":0,"right":index*3+2,"bottom":4,"score":900})
            })
            .collect();
        let mut details = json!({"text":"?AB?","coveragePermille":500,"averageScorePermille":900,"matches":matches});
        let result = search(&details, "AB");
        assert_eq!(result.len(), 1);
        assert_eq!(result[0]["left"], 3);
        assert_eq!(result[0]["right"], 8);
        assert!(search(&details, "?").is_empty());
        assert!(search(&details, "?AB").is_empty());
        details["matches"][0]["recognized"] = json!(true);
        assert_eq!(search(&details, "?").len(), 1);
        assert_eq!(search(&details, "?AB").len(), 1);
        details["matches"][1]["recognized"] = json!(false);
        assert!(search(&details, "AB").is_empty());
    }

    #[test]
    fn unsupported_targets_and_malformed_details_are_rejected_not_matched() {
        let details =
            json!({"text":"A","coveragePermille":1000,"averageScorePermille":1000,"matches":[]});
        for target in ["", "A B", "A\nB", &"A".repeat(257)] {
            assert!(find(&[
                LuaScalar::Bytes(details.to_string().into_bytes()),
                LuaScalar::Bytes(target.as_bytes().to_vec()),
            ])
            .is_err());
        }
        assert!(find(&[
            LuaScalar::Bytes(b"{}".to_vec()),
            LuaScalar::Bytes(b"A".to_vec()),
        ])
        .is_err());
    }

    #[test]
    fn text_bounds_include_taller_intermediate_glyphs() {
        let matches: Vec<_> = [("A", 1, 2, 6), ("B", 4, 1, 8), ("C", 7, 2, 6)]
            .into_iter()
            .map(|(text, left, top, bottom)| json!({"text":text,"left":left,"top":top,"right":left+2,"bottom":bottom,"score":1000,"recognized":true}))
            .collect();
        let details = json!({"text":"ABC","coveragePermille":1000,"averageScorePermille":1000,"matches":matches});
        let LuaInput::Values(values) = find(&[
            LuaScalar::Bytes(details.to_string().into_bytes()),
            LuaScalar::Bytes(b"ABC".to_vec()),
        ])
        .unwrap() else {
            panic!()
        };
        let LuaScalar::Bytes(bytes) = &values[1] else {
            panic!()
        };
        let rectangles: serde_json::Value = serde_json::from_slice(bytes).unwrap();
        assert_eq!(rectangles[0]["top"], 1);
        assert_eq!(rectangles[0]["bottom"], 8);
    }
    #[test]
    fn searches_contiguous_recognized_glyphs_without_crossing_rows_or_unknowns() {
        let make = |recognized, top| {
            json!({"text":"AB","coveragePermille":1000,"averageScorePermille":1000,"matches":[{"text":"A","left":1,"top":1,"right":3,"bottom":4,"score":1000,"recognized":true},{"text":"B","left":4,"top":top,"right":6,"bottom":top+3,"score":1000,"recognized":recognized}]}).to_string()
        };
        for (recognized, top, expected) in [(true, 1, 1), (false, 1, 0), (true, 5, 0)] {
            let LuaInput::Values(output) = find(&[
                LuaScalar::Bytes(make(recognized, top).into_bytes()),
                LuaScalar::Bytes(b"AB".to_vec()),
            ])
            .unwrap() else {
                panic!()
            };
            let LuaScalar::Bytes(output) = &output[1] else {
                panic!()
            };
            assert_eq!(
                serde_json::from_slice::<Vec<serde_json::Value>>(output)
                    .unwrap()
                    .len(),
                expected
            );
        }
    }
}
