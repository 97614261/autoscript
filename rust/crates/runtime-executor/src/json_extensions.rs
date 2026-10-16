use lua_runtime::LuaScalar;
use serde_json::Value;
pub(crate) fn execute(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    if args.len()
        != if matches!(opcode, 14009 | 14010) {
            2
        } else {
            1
        }
    {
        return Err("invalid JSON argument count");
    }
    let Some(LuaScalar::Bytes(bytes)) = args.first() else {
        return Err("JSON requires UTF-8 string");
    };
    if bytes.len() > 65_536 {
        return Err("JSON exceeds budget");
    }
    let source = std::str::from_utf8(bytes).map_err(|_| "JSON must be UTF-8")?;
    if opcode == 14007 {
        return Ok(LuaScalar::Integer(i64::from(
            crate::data_runtime::parse(source).is_ok(),
        )));
    }
    let value = crate::data_runtime::parse(source)?;
    if opcode == 14008 {
        return Ok(LuaScalar::Bytes(
            match &value {
                Value::Null => "null",
                Value::Bool(_) => "boolean",
                Value::Number(n) if n.is_i64() => "integer",
                Value::Number(_) => "number",
                Value::String(_) => "string",
                Value::Array(_) => "array",
                Value::Object(_) => "object",
            }
            .as_bytes()
            .to_vec(),
        ));
    }
    let Some(LuaScalar::Bytes(key)) = args.get(1) else {
        return Err("JSON key must be string");
    };
    let key = std::str::from_utf8(key).map_err(|_| "key must be UTF-8")?;
    if key.is_empty() || key.len() > 128 || key.chars().any(char::is_control) {
        return Err("invalid JSON key");
    }
    let found = match &value {
        Value::Object(v) => v.get(key),
        Value::Array(v) => {
            let index = key
                .parse::<usize>()
                .ok()
                .filter(|n| *n > 0 && n.to_string() == key)
                .ok_or("index must be decimal text from 1")?;
            v.get(index - 1)
        }
        _ => return Err("JSON key requires collection/object"),
    };
    match opcode {
        14009 => Ok(LuaScalar::Integer(i64::from(found.is_some()))),
        14010 => crate::data_runtime::encode(found.unwrap_or(&Value::Null)),
        _ => Err("unknown JSON operation"),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn b(s: &str) -> LuaScalar {
        LuaScalar::Bytes(s.as_bytes().to_vec())
    }
    #[test]
    fn nested_values_missing_and_null_stay_distinct() {
        assert_eq!(
            execute(14009, &[b("{\"x\":null}"), b("x")]).unwrap(),
            LuaScalar::Integer(1)
        );
        assert_eq!(
            execute(14009, &[b("{}"), b("x")]).unwrap(),
            LuaScalar::Integer(0)
        );
        assert_eq!(
            execute(14010, &[b("{\"x\":{\"a\":[1]}}"), b("x")]).unwrap(),
            b("{\"a\":[1]}")
        );
        assert_eq!(execute(14008, &[b("[]")]).unwrap(), b("array"));
        assert_eq!(execute(14007, &[b("{")]).unwrap(), LuaScalar::Integer(0));
        assert!(crate::data_runtime::execute(14001, &[b("{\"x\":{}}"), b("x")]).is_err());
    }
}
