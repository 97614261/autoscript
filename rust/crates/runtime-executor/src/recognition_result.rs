//! Fixed recognition shapes; intentionally independent of the generic Data item budget.
use lua_runtime::{LuaInput, LuaScalar};
use runtime_scheduler::TaskToken;
use serde_json::{json, Value};

/// One validated immutable result, never a frame or a producer cache. Exact task/bytes/type key.
#[derive(Default)]
pub(crate) struct ResultCache {
    entry: Option<(TaskToken, Vec<u8>, String, PreparedResult)>,
    #[cfg(test)]
    parses: usize,
}
impl std::fmt::Debug for ResultCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResultCache")
            .field("occupied", &self.entry.is_some())
            .finish_non_exhaustive()
    }
}
enum PreparedResult {
    Images(crate::template_results::MatchResults),
    Other(Value),
}
impl ResultCache {
    pub(crate) fn clear_task(&mut self, task: TaskToken) {
        if self.entry.as_ref().is_some_and(|entry| entry.0 == task) {
            self.entry = None;
        }
    }
    pub(crate) fn read(&mut self, task: TaskToken, args: &[LuaScalar]) -> Result<LuaInput, String> {
        if self.entry.as_ref().is_some_and(|entry| entry.0 != task) {
            self.entry = None;
        }
        let (bytes, shape, index, field) = arguments(args)?;
        if !self
            .entry
            .as_ref()
            .is_some_and(|entry| entry.0 == task && entry.1 == bytes && entry.2 == shape)
        {
            self.entry = None;
            let parsed = if shape == "IMAGES" {
                PreparedResult::Images(crate::template_results::MatchResults::decode(bytes)?)
            } else {
                let root = parse_shallow(bytes)?;
                validate_root(&root, shape)?;
                PreparedResult::Other(root)
            };
            self.entry = Some((task, bytes.to_vec(), shape.to_owned(), parsed));
            #[cfg(test)]
            {
                self.parses += 1;
            }
        }
        match &self
            .entry
            .as_ref()
            .ok_or("RECOGNITION_RESULT_CACHE_EMPTY")?
            .3
        {
            PreparedResult::Images(result) => Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                image_value(result, index, field)?,
            ])),
            PreparedResult::Other(root) => read_root(root, shape, index, field),
        }
    }
}

fn number(value: &Value, key: &str, max: u64) -> Result<u64, String> {
    value
        .get(key)
        .and_then(Value::as_u64)
        .filter(|v| *v <= max)
        .ok_or_else(|| "RECOGNITION_RESULT_INVALID_FIELD".into())
}
fn item_valid(item: &Value, shape: &str) -> Result<(), String> {
    let keys: &[&str] = match shape {
        "COLORS" => &["x", "y"],
        "SCALED_IMAGE" => &[
            "x",
            "y",
            "width",
            "height",
            "scorePermille",
            "scalePermille",
        ],
        "COMPONENTS" => &["area", "left", "top", "right", "bottom"],
        "TEXT_ITEM" => &["text", "left", "top", "right", "bottom"],
        "GLYPH_ITEM" => &[
            "text",
            "left",
            "top",
            "right",
            "bottom",
            "score",
            "recognized",
        ],
        _ => return Err("RECOGNITION_RESULT_INVALID_SHAPE".into()),
    };
    let object = item.as_object().ok_or("RECOGNITION_RESULT_INVALID_SHAPE")?;
    if object.len() != keys.len() || keys.iter().any(|key| !object.contains_key(*key)) {
        return Err("RECOGNITION_RESULT_INVALID_SHAPE".into());
    }
    for key in keys {
        match *key {
            "text" => {
                if !item[*key]
                    .as_str()
                    .is_some_and(|v| !v.is_empty() && v.len() <= 256)
                {
                    return Err("RECOGNITION_RESULT_INVALID_TEXT".into());
                }
            }
            "recognized" => {
                if !item[*key].is_boolean() {
                    return Err("RECOGNITION_RESULT_INVALID_FIELD".into());
                }
            }
            "score" | "scorePermille" => {
                number(item, key, 1000)?;
            }
            "area" => {
                if number(item, key, 4_194_304)? == 0 {
                    return Err("RECOGNITION_RESULT_INVALID_FIELD".into());
                }
            }
            _ => {
                number(item, key, 32768)?;
            }
        }
    }
    if shape == "SCALED_IMAGE" {
        if number(item, "width", 4096)? == 0
            || number(item, "height", 4096)? == 0
            || !(250..=4000).contains(&number(item, "scalePermille", 4000)?)
        {
            return Err("RECOGNITION_RESULT_INVALID_FIELD".into());
        }
    } else if shape != "COLORS"
        && (item["left"].as_u64() >= item["right"].as_u64()
            || item["top"].as_u64() >= item["bottom"].as_u64())
    {
        return Err("RECOGNITION_RESULT_INVALID_BOUNDS".into());
    }
    Ok(())
}

fn arguments(args: &[LuaScalar]) -> Result<(&[u8], &str, i64, &str), String> {
    let [LuaScalar::Bytes(bytes), LuaScalar::Bytes(shape), LuaScalar::Integer(index), LuaScalar::Bytes(field)] =
        args
    else {
        return Err("RECOGNITION_RESULT_INVALID_ARGUMENTS".into());
    };
    if bytes.len() > 65_536 || shape.len() > 32 || field.len() > 32 {
        return Err("RECOGNITION_RESULT_BUDGET".into());
    }
    let shape = std::str::from_utf8(shape).map_err(|_| "RECOGNITION_RESULT_INVALID_SHAPE")?;
    if ![
        "IMAGES",
        "GLYPH_SUMMARY",
        "GLYPH_ITEM",
        "TEXT_ITEM",
        "COLORS",
        "COMPONENTS",
        "FRAME_DIFF",
        "SCREEN_POINT",
        "SCALED_IMAGE",
    ]
    .contains(&shape)
    {
        return Err("RECOGNITION_RESULT_INVALID_SHAPE".into());
    }
    let field = std::str::from_utf8(field).map_err(|_| "RECOGNITION_RESULT_INVALID_FIELD")?;
    Ok((bytes, shape, *index, field))
}

#[cfg(test)]
pub(crate) fn read(args: &[LuaScalar]) -> Result<LuaInput, String> {
    let (bytes, shape, index, field) = arguments(args)?;
    if shape == "IMAGES" {
        return Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            image_value(
                &crate::template_results::MatchResults::decode(bytes)?,
                index,
                field,
            )?,
        ]));
    }
    let root = parse_shallow(bytes)?;
    validate_root(&root, shape)?;
    read_root(&root, shape, index, field)
}

fn image_value(
    result: &crate::template_results::MatchResults,
    index: i64,
    field: &str,
) -> Result<LuaScalar, String> {
    let numeric = |key: &str| -> Result<u64, String> {
        result
            .field_value(index, key)?
            .as_u64()
            .ok_or_else(|| "RECOGNITION_RESULT_INVALID_FIELD".into())
    };
    let value = match field {
        "centerX" => json!(numeric("x")? + numeric("width")? / 2),
        "centerY" => json!(numeric("y")? + numeric("height")? / 2),
        _ => result.field_value(index, field)?,
    };
    Ok(LuaScalar::Bytes(
        serde_json::to_vec(&value).map_err(|_| "RECOGNITION_RESULT_ENCODING_FAILED")?,
    ))
}

fn parse_shallow(bytes: &[u8]) -> Result<Value, String> {
    // Limit nesting before serde allocation; do not weaken Data.parse for recognition output.
    let (mut depth, mut quoted, mut escaped) = (0usize, false, false);
    for byte in bytes {
        if quoted {
            if escaped {
                escaped = false;
            } else if *byte == b'\\' {
                escaped = true;
            } else if *byte == b'"' {
                quoted = false;
            }
        } else if *byte == b'"' {
            quoted = true;
        } else if matches!(*byte, b'[' | b'{') {
            depth += 1;
            if depth > 4 {
                return Err("RECOGNITION_RESULT_BUDGET".into());
            }
        } else if matches!(*byte, b']' | b'}') {
            depth = depth.saturating_sub(1);
        }
    }
    serde_json::from_slice(bytes).map_err(|_| "RECOGNITION_RESULT_INVALID_JSON".into())
}

fn validate_root(root: &Value, shape: &str) -> Result<(), String> {
    if matches!(shape, "FRAME_DIFF" | "SCREEN_POINT") {
        let fields: &[(&str, u64)] = if shape == "FRAME_DIFF" {
            &[
                ("changedPixels", 4_194_304),
                ("totalPixels", 4_194_304),
                ("ratioPermille", 1000),
                ("fresh", 1),
                ("elapsedUs", i64::MAX as u64),
            ]
        } else {
            &[
                ("x", 32768),
                ("y", 32768),
                ("screenWidth", 32768),
                ("screenHeight", 32768),
            ]
        };
        if root.as_object().is_none_or(|r| r.len() != fields.len()) {
            return Err("RECOGNITION_RESULT_INVALID_SHAPE".into());
        }
        for (field, max) in fields {
            number(root, field, *max)?;
        }
        if shape == "FRAME_DIFF" {
            let total = number(root, "totalPixels", 4_194_304)?;
            let changed = number(root, "changedPixels", total)?;
            if total == 0 || number(root, "ratioPermille", 1000)? != changed * 1000 / total {
                return Err("RECOGNITION_RESULT_INVALID_FIELD".into());
            }
        } else if number(root, "x", 32768)? >= number(root, "screenWidth", 32768)?
            || number(root, "y", 32768)? >= number(root, "screenHeight", 32768)?
        {
            return Err("RECOGNITION_RESULT_INVALID_BOUNDS".into());
        }
        return Ok(());
    }
    let glyph = matches!(shape, "GLYPH_SUMMARY" | "GLYPH_ITEM");
    let items = if glyph {
        let object = root.as_object().ok_or("RECOGNITION_RESULT_INVALID_SHAPE")?;
        if object.len() != 4 || root["text"].as_str().is_none_or(|v| v.len() > 32768) {
            return Err("RECOGNITION_RESULT_INVALID_SHAPE".into());
        }
        number(root, "coveragePermille", 1000)?;
        number(root, "averageScorePermille", 1000)?;
        root["matches"].as_array()
    } else {
        root.as_array()
    }
    .ok_or("RECOGNITION_RESULT_INVALID_SHAPE")?;
    let limit = if shape == "SCALED_IMAGE" {
        1
    } else if matches!(shape, "COLORS" | "COMPONENTS") {
        256
    } else {
        2048
    };
    if items.len() > limit {
        return Err("RECOGNITION_RESULT_BUDGET".into());
    }
    for item in items {
        item_valid(item, if glyph { "GLYPH_ITEM" } else { shape })?;
    }
    Ok(())
}

fn read_root(root: &Value, shape: &str, index: i64, field: &str) -> Result<LuaInput, String> {
    if matches!(shape, "FRAME_DIFF" | "SCREEN_POINT") {
        if index != 0 {
            return Err("RECOGNITION_RESULT_AGGREGATE_INDEX".into());
        }
        let value = root
            .get(field)
            .filter(|v| v.is_u64())
            .ok_or("RECOGNITION_RESULT_INVALID_FIELD")?;
        return Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Bytes(value.to_string().into_bytes()),
        ]));
    }
    let items = if matches!(shape, "GLYPH_SUMMARY" | "GLYPH_ITEM") {
        root["matches"].as_array()
    } else {
        root.as_array()
    }
    .ok_or("RECOGNITION_RESULT_INVALID_SHAPE")?;
    let value = if field == "count" {
        if index != 0 {
            return Err("RECOGNITION_RESULT_AGGREGATE_INDEX".into());
        }
        json!(items.len())
    } else if shape == "GLYPH_SUMMARY" {
        if index != 0 || !matches!(field, "text" | "coveragePermille" | "averageScorePermille") {
            return Err("RECOGNITION_RESULT_INVALID_FIELD".into());
        }
        root[field].clone()
    } else {
        let index = usize::try_from(index)
            .ok()
            .filter(|v| *v > 0 && *v <= items.len())
            .ok_or("RECOGNITION_RESULT_INDEX_OUT_OF_RANGE")?;
        let item = &items[index - 1];
        match field {
            "centerX" | "centerY" if shape == "SCALED_IMAGE" => {
                let (a, b) = if field == "centerX" {
                    ("x", "width")
                } else {
                    ("y", "height")
                };
                json!(number(item, a, 32768)? + number(item, b, 4096)? / 2)
            }
            "centerX" | "centerY" if shape != "COLORS" => {
                let (a, b) = if field == "centerX" {
                    ("left", "right")
                } else {
                    ("top", "bottom")
                };
                json!(
                    number(item, a, 32768)?
                        + (number(item, b, 32768)? - number(item, a, 32768)?) / 2
                )
            }
            "recognized" if shape == "GLYPH_ITEM" => json!(u8::from(
                item[field]
                    .as_bool()
                    .ok_or("RECOGNITION_RESULT_INVALID_FIELD")?
            )),
            _ => item
                .get(field)
                .filter(|v| v.is_string() || v.is_number())
                .cloned()
                .ok_or("RECOGNITION_RESULT_MISSING_FIELD")?,
        }
    };
    let encoded = serde_json::to_vec(&value).map_err(|_| "RECOGNITION_RESULT_ENCODING_FAILED")?;
    Ok(LuaInput::Values(vec![
        LuaScalar::Boolean(true),
        LuaScalar::Bytes(encoded),
    ]))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn read_value(value: &Value, shape: &str, index: i64, field: &str) -> Result<LuaInput, String> {
        read(&[
            LuaScalar::Bytes(serde_json::to_vec(value).unwrap()),
            LuaScalar::Bytes(shape.as_bytes().to_vec()),
            LuaScalar::Integer(index),
            LuaScalar::Bytes(field.as_bytes().to_vec()),
        ])
    }
    #[test]
    fn new_shapes_remain_exact_bounded_and_separate() {
        let difference =
            json!({"changedPixels":1,"totalPixels":2,"ratioPermille":500,"fresh":0,"elapsedUs":10});
        assert!(read_value(&difference, "FRAME_DIFF", 0, "fresh").is_ok());
        assert!(read_value(&difference, "FRAME_DIFF", 1, "fresh").is_err());
        assert!(read_value(&difference, "SCREEN_POINT", 0, "x").is_err());
        let point = json!({"x":3,"y":4,"screenWidth":8,"screenHeight":6});
        assert!(read_value(&point, "SCREEN_POINT", 0, "x").is_ok());
        let scaled =
            json!([{"x":2,"y":3,"width":4,"height":6,"scorePermille":900,"scalePermille":1500}]);
        assert!(read_value(&scaled, "SCALED_IMAGE", 1, "centerX").is_ok());
        assert!(read_value(&scaled, "IMAGES", 1, "x").is_err());
        assert!(read_value(&json!([scaled[0], scaled[0]]), "SCALED_IMAGE", 1, "x").is_err());
        assert!(read_value(&json!({"changedPixels":1,"totalPixels":2,"ratioPermille":501,"fresh":1,"elapsedUs":10}),"FRAME_DIFF",0,"fresh").is_err());
    }
    #[test]
    fn large_valid_results_do_not_use_data_item_budget() {
        let value = json!((0..256)
            .map(|i| json!({"area":1,"left":i,"top":0,"right":i+1,"bottom":1}))
            .collect::<Vec<_>>());
        assert!(read_value(&value, "COMPONENTS", 256, "centerX").is_ok());
        assert!(crate::data_runtime::parse(&value.to_string()).is_err());
        assert!(read_value(&value, "COLORS", 1, "x").is_err());
        assert!(read_value(&value, "COMPONENTS", 257, "area").is_err());
        assert!(read_value(&value, "COMPONENTS", 0, "count").is_ok());
    }
    #[test]
    fn missing_malformed_and_nested_results_are_rejected() {
        assert!(read_value(&json!([{"x":1}]), "COLORS", 1, "y").is_err());
        assert!(read_value(&json!([{"x":1,"y":2}]), "COLORS", 1, "text").is_err());
        assert!(read_value(&json!([[[[[0]]]]]), "COLORS", 1, "x").is_err());
        assert!(read_value(&json!([]), "COLORS", 1, "x").is_err());
        assert!(read_value(&json!([]), "COLORS", 0, "count").is_ok());
    }

    #[test]
    fn cache_reuses_only_exact_task_bytes_and_shape_and_clears_owner() {
        use runtime_scheduler::{TaskGeneration, TaskId};
        let owner = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        };
        let other = TaskToken {
            generation: TaskGeneration(2),
            ..owner
        };
        let args = |x, index, field: &str| {
            vec![
                LuaScalar::Bytes(json!([{"x":x,"y":2}]).to_string().into_bytes()),
                LuaScalar::Bytes(b"COLORS".to_vec()),
                LuaScalar::Integer(index),
                LuaScalar::Bytes(field.as_bytes().to_vec()),
            ]
        };
        let mut cache = ResultCache::default();
        cache.read(owner, &args(1, 1, "x")).unwrap();
        cache.read(owner, &args(1, 1, "y")).unwrap();
        assert_eq!(cache.parses, 1);
        assert!(cache.read(owner, &args(1, 2, "x")).is_err());
        assert!(cache.read(owner, &args(1, 1, "text")).is_err());
        assert_eq!(cache.parses, 1);
        cache.read(owner, &args(2, 1, "x")).unwrap();
        assert_eq!(cache.parses, 2);
        cache.read(other, &args(2, 1, "x")).unwrap();
        assert_eq!(cache.parses, 3);
        cache.clear_task(owner);
        assert!(cache.entry.is_some());
        cache.clear_task(other);
        assert!(cache.entry.is_none());
        let mut invalid = args(2, 1, "x");
        invalid[1] = LuaScalar::Bytes(b"COMPONENTS".to_vec());
        assert!(cache.read(other, &invalid).is_err());
        assert!(cache.entry.is_none());
    }

    #[test]
    fn cached_image_centers_preserve_version_validation_and_never_overflow_u32() {
        use runtime_scheduler::{TaskGeneration, TaskId};
        let mut cache = ResultCache::default();
        let owner = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        };
        let args = |version, field: &str| {
            vec![LuaScalar::Bytes(json!({"version":version,"count":1,"truncated":false,"matches":[{"x":4_294_967_295_u64,"y":3,"width":4,"height":5,"scorePermille":900,"path":"a.png"}]}).to_string().into_bytes()),LuaScalar::Bytes(b"IMAGES".to_vec()),LuaScalar::Integer(1),LuaScalar::Bytes(field.as_bytes().to_vec())]
        };
        assert_eq!(
            cache.read(owner, &args(1, "centerX")).unwrap(),
            LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(b"4294967297".to_vec())
            ])
        );
        cache.read(owner, &args(1, "centerY")).unwrap();
        assert_eq!(cache.parses, 1);
        assert!(cache.read(owner, &args(2, "centerX")).is_err());
        assert!(cache.entry.is_none());
    }
}
