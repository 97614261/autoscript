//! Data operations accept bounded text operands, not executable expressions or file handles.
use serde_json::Value;

#[derive(Debug, Clone)]
pub(crate) struct Operand {
    pub literal: String,
    pub variable: Option<String>,
}

#[derive(Debug, Clone)]
pub(crate) struct DataOperation {
    pub api: &'static str,
    pub operands: Vec<Operand>,
    pub result_variable: Option<String>,
    pub integer_result: bool,
}

fn variable_name(value: &str) -> bool {
    let mut bytes = value.bytes();
    value.len() <= 64
        && bytes
            .next()
            .is_some_and(|byte| byte.is_ascii_alphabetic() || byte == b'_')
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

pub(crate) fn parse(kind: &str, args: &Value) -> Result<DataOperation, &'static str> {
    let (api, fields, has_result): (&str, &[(&str, usize)], bool) = match kind {
        "data.length" => ("Data.length", &[("json", 65_536)], true),
        "data.get" => ("Data.get", &[("json", 65_536), ("key", 128)], true),
        "data.set" => (
            "Data.set",
            &[("json", 65_536), ("key", 128), ("valueJson", 65_536)],
            true,
        ),
        "data.remove" => ("Data.remove", &[("json", 65_536), ("key", 128)], true),
        "data.append" => (
            "Data.append",
            &[("json", 65_536), ("valueJson", 65_536)],
            true,
        ),
        "data.keys" => ("Data.keys", &[("json", 65_536)], true),
        "data.text" => ("Data.text", &[("valueJson", 65_536)], true),
        "files.read" => ("Files.read", &[("path", 256)], true),
        "files.write" => ("Files.write", &[("path", 256), ("text", 65_536)], false),
        "files.list" => ("Files.list", &[("path", 256)], true),
        "files.remove" => ("Files.remove", &[("path", 256)], false),
        _ => return Err("unknown data operation"),
    };
    let map = args.as_object().ok_or("data arguments must be an object")?;
    for key in map.keys() {
        if !((has_result && key == "resultVariable")
            || fields
                .iter()
                .any(|(field, _)| key == field || key.strip_suffix("Variable") == Some(*field)))
        {
            return Err("unknown data argument");
        }
    }
    let operands = fields
        .iter()
        .map(|(field, limit)| {
            let literal = map
                .get(*field)
                .and_then(Value::as_str)
                .ok_or("data literal must be text")?;
            if literal.len() > *limit {
                return Err("data literal exceeds its byte limit");
            }
            let variable = map
                .get(&format!("{field}Variable"))
                .map(|value| {
                    value
                        .as_str()
                        .filter(|name| variable_name(name))
                        .map(str::to_owned)
                        .ok_or("invalid data source variable")
                })
                .transpose()?;
            if *field == "path" && variable.is_none() {
                validate_path(literal, kind == "files.list")?;
            }
            Ok(Operand {
                literal: literal.to_owned(),
                variable,
            })
        })
        .collect::<Result<Vec<_>, &'static str>>()?;
    let result_variable = if has_result {
        Some(
            map.get("resultVariable")
                .and_then(Value::as_str)
                .filter(|name| variable_name(name))
                .ok_or("data result requires a valid variable")?
                .to_owned(),
        )
    } else {
        None
    };
    Ok(DataOperation {
        api,
        operands,
        result_variable,
        integer_result: kind == "data.length",
    })
}

fn validate_path(path: &str, allow_empty: bool) -> Result<(), &'static str> {
    if path.is_empty() {
        return if allow_empty {
            Ok(())
        } else {
            Err("file path cannot be empty")
        };
    }
    if path.split('/').count() > 16
        || path.split('/').any(|part| {
            part.is_empty()
                || part.starts_with('.')
                || part.ends_with('.')
                || part.ends_with(' ')
                || part.chars().any(|c| {
                    c.is_control() || matches!(c, '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')
                })
        })
    {
        return Err("file path must stay inside project data");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::parse;
    use serde_json::json;

    #[test]
    fn validates_exact_operands_results_and_project_relative_paths() {
        assert!(parse(
            "data.get",
            &json!({"json":"[]","key":"1","resultVariable":"out"})
        )
        .is_ok());
        assert!(parse("files.list", &json!({"path":"","resultVariable":"out"})).is_ok());
        for path in [
            "../secret",
            "/secret",
            "C:/secret",
            "a\\b",
            "a//b",
            ".secret",
        ] {
            assert!(parse("files.read", &json!({"path":path,"resultVariable":"out"})).is_err());
        }
        assert!(parse(
            "files.write",
            &json!({"path":"state.json","text":"ok","resultVariable":"out"})
        )
        .is_err());
        assert!(parse(
            "data.get",
            &json!({"json":"[]","key":"1","resultVariable":"bad-name"})
        )
        .is_err());
        assert!(parse(
            "data.get",
            &json!({"json":"[]","key":"1","resultVariable":"out","shell":"x"})
        )
        .is_err());
        assert!(parse(
            "data.get",
            &json!({"json":"[]","key":"1","keyVariable":12,"resultVariable":"out"})
        )
        .is_err());
    }
}
