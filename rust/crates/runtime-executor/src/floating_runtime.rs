use lua_runtime::LuaScalar;
use serde_json::Value;

pub(crate) fn name(value: &str) -> bool {
    value.len() <= 32
        && value
            .as_bytes()
            .first()
            .is_some_and(|c| c.is_ascii_alphabetic() || *c == b'_')
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'_')
}
pub(crate) fn shapes(text: &str) -> Result<(), &'static str> {
    if text.len() > 8192 {
        return Err("drawing exceeds budget");
    }
    let value: Value = serde_json::from_str(text).map_err(|_| "drawing JSON invalid")?;
    let rows = value
        .as_array()
        .filter(|v| v.len() <= 32)
        .ok_or("drawing must be at most 32 shapes")?;
    for row in rows {
        let object = row.as_object().ok_or("shape must be object")?;
        let kind = object
            .get("kind")
            .and_then(Value::as_str)
            .ok_or("shape kind required")?;
        let fields: &[&str] = match kind {
            "line" => &["x1", "y1", "x2", "y2", "strokePx"],
            "rect" => &["x", "y", "width", "height"],
            "circle" => &["x", "y", "radius"],
            _ => return Err("shape kind invalid"),
        };
        if object.len() != fields.len() + 2
            || !object
                .get("color")
                .and_then(Value::as_str)
                .is_some_and(color)
        {
            return Err("shape fields invalid");
        }
        for key in fields {
            let n = object
                .get(*key)
                .and_then(Value::as_u64)
                .ok_or("shape coordinate must be integer")?;
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
pub(crate) fn color(value: &str) -> bool {
    matches!(value.len(), 7 | 9)
        && value.starts_with('#')
        && value[1..].bytes().all(|c| c.is_ascii_hexdigit())
}
pub(crate) fn validate(op: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    let text = |index: usize, max: usize| match args.get(index) {
        Some(LuaScalar::Bytes(v)) if v.len() <= max => {
            std::str::from_utf8(v).map_err(|_| "floating text invalid")
        }
        _ => Err("floating text invalid"),
    };
    if !name(text(0, 32)?) {
        return Err("floating name invalid");
    }
    if (17001..=17003).contains(&op) {
        return if args.len() == 1 {
            Ok(())
        } else {
            Err("floating argument count")
        };
    }
    if op != 17000 || args.len() != 11 {
        return Err("floating argument count");
    }
    for (index, min, max) in [
        (1, -1, 8192),
        (2, -1, 8192),
        (3, 1, 2048),
        (4, 1, 2048),
        (6, 8, 96),
    ] {
        if !matches!(args.get(index),Some(LuaScalar::Integer(v)) if (min..=max).contains(v)) {
            return Err("floating size or position invalid");
        }
    }
    text(5, 4096)?;
    if !color(text(7, 9)?) || !color(text(8, 9)?) {
        return Err("floating color invalid");
    }
    let image = text(9, 256)?;
    if !image.is_empty() {
        if !image.starts_with("assets/images/") {
            return Err("floating image must be declared project image");
        }
        super::data_runtime::validate_file_request(
            14100,
            &[LuaScalar::Bytes(image.as_bytes().to_vec())],
        )?;
    }
    shapes(text(10, 8192)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shapes_are_fixed_bounded_data_not_executable_drawing() {
        assert!(shapes(
            r##"[{"kind":"rect","x":0,"y":1,"width":10,"height":20,"color":"#FF66CCFF"}]"##
        )
        .is_ok());
        assert!(shapes(r#"[{"kind":"shell","command":"reboot"}]"#).is_err());
        assert!(shapes(
            r##"[{"kind":"line","x1":0,"y1":1,"x2":2,"y2":3,"strokePx":33,"color":"#FF66CCFF"}]"##
        )
        .is_err());
        assert!(!name("../window"));
        assert!(!color("red"));
    }
}
