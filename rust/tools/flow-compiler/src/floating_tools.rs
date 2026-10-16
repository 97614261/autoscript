use crate::model::CompileError;
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::{Map, Value};

const FIELDS: &[&str] = &[
    "name",
    "x",
    "y",
    "width",
    "height",
    "text",
    "fontPx",
    "textColor",
    "background",
    "imagePath",
    "shapesJson",
];
fn name(v: &str) -> bool {
    v.len() <= 32
        && v.as_bytes()
            .first()
            .is_some_and(|c| c.is_ascii_alphabetic() || *c == b'_')
        && v.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
}
fn color(v: &str) -> bool {
    matches!(v.len(), 7 | 9) && v.starts_with('#') && v[1..].bytes().all(|c| c.is_ascii_hexdigit())
}
pub(crate) fn parse(kind: &str, args: &Map<String, Value>) -> Result<(), &'static str> {
    let required: &[&str] = if kind == "floating.show" {
        FIELDS
    } else if kind == "floating.bounds" {
        &["name", "resultVariable"]
    } else {
        &["name"]
    };
    if required.iter().any(|key| !args.contains_key(*key))
        || args.keys().any(|key| {
            !(required.contains(&key.as_str()) || kind == "floating.show" && key == "textVariable")
        })
    {
        return Err("floating fields invalid");
    }
    if !args["name"].as_str().is_some_and(name) {
        return Err("floating name invalid");
    }
    for key in ["resultVariable", "textVariable"] {
        if let Some(v) = args.get(key) {
            if v.as_str().is_none_or(|v| v.is_empty() || v.len() > 64) {
                return Err("floating variable invalid");
            }
        }
    }
    if kind != "floating.show" {
        return Ok(());
    }
    for (key, min, max) in [
        ("x", -1, 8192),
        ("y", -1, 8192),
        ("width", 1, 2048),
        ("height", 1, 2048),
        ("fontPx", 8, 96),
    ] {
        if args[key].as_i64().is_none_or(|n| !(min..=max).contains(&n)) {
            return Err("floating coordinate invalid");
        }
    }
    if args["text"].as_str().is_none_or(|v| v.len() > 4096)
        || !args["textColor"].as_str().is_some_and(color)
        || !args["background"].as_str().is_some_and(color)
    {
        return Err("floating text or color invalid");
    }
    let image = args["imagePath"].as_str().ok_or("floating image invalid")?;
    if !image.is_empty() {
        crate::data_operations::validate_path(image, false)?;
        if !image.starts_with("assets/images/") {
            return Err("floating requires project image");
        }
    }
    let text = args["shapesJson"]
        .as_str()
        .filter(|v| v.len() <= 8192)
        .ok_or("drawing budget invalid")?;
    let shapes: Value = serde_json::from_str(text).map_err(|_| "drawing JSON invalid")?;
    for row in shapes
        .as_array()
        .filter(|v| v.len() <= 32)
        .ok_or("drawing budget invalid")?
    {
        let row = row.as_object().ok_or("shape invalid")?;
        let fields: &[&str] = match row.get("kind").and_then(Value::as_str) {
            Some("line") => &["x1", "y1", "x2", "y2", "strokePx"],
            Some("rect") => &["x", "y", "width", "height"],
            Some("circle") => &["x", "y", "radius"],
            _ => return Err("shape kind invalid"),
        };
        if row.len() != fields.len() + 2
            || !row.get("color").and_then(Value::as_str).is_some_and(color)
        {
            return Err("shape fields invalid");
        }
        for key in fields {
            let n = row
                .get(*key)
                .and_then(Value::as_u64)
                .ok_or("shape coordinate invalid")?;
            if n > 4096
                || (*key == "strokePx" && !(1..=32).contains(&n))
                || (["width", "height", "radius"].contains(key) && n == 0)
            {
                return Err("shape coordinate invalid");
            }
        }
    }
    Ok(())
}
pub(crate) fn validate(
    args: &Map<String, Value>,
    manifest: &ProjectManifest,
    flow: &str,
) -> Result<(), &'static str> {
    for key in ["resultVariable", "textVariable"] {
        if let Some(name) = args.get(key).and_then(Value::as_str) {
            if crate::analyze::declared_variable_type(manifest, flow, name)
                != Some(ProjectVariableType::String)
            {
                return Err("floating requires declared character variable");
            }
        }
    }
    if let Some(image) = args
        .get("imagePath")
        .and_then(Value::as_str)
        .filter(|v| !v.is_empty())
    {
        if !manifest
            .resources
            .iter()
            .any(|r| r.kind == flow_ir::ProjectResourceKind::Image && r.path == image)
        {
            return Err("floating image must be declared");
        }
    }
    Ok(())
}
pub(crate) fn render(
    kind: &str,
    args: &Map<String, Value>,
    quote: impl Fn(&str) -> Result<String, CompileError>,
) -> Result<String, CompileError> {
    let text = |key: &str| quote(args[key].as_str().unwrap_or_default());
    if kind == "floating.show" {
        let values = FIELDS
            .iter()
            .map(|key| {
                if *key == "text" && args.contains_key("textVariable") {
                    Ok(format!("__vars[{}]", text("textVariable")?))
                } else if let Some(v) = args[*key].as_str() {
                    quote(v)
                } else {
                    Ok(args[*key].to_string())
                }
            })
            .collect::<Result<Vec<_>, _>>()?;
        return Ok(format!("Floating.show({})", values.join(",")));
    }
    Ok(match kind {
        "floating.hide" => format!("Floating.hide({})", text("name")?),
        "floating.close" => format!("Floating.close({})", text("name")?),
        _ => format!(
            "__vars[{}]=Floating.getBounds({})",
            text("resultVariable")?,
            text("name")?
        ),
    })
}
