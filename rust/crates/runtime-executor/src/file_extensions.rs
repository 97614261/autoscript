//! Strict typed private-data calls. The root and identity are never script arguments.
use lua_runtime::{LuaInput, LuaScalar};
use serde_json::Value;
const MAX_WIRE: usize = 393_728;
pub(crate) fn validate(opcode: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    let contract = script_api::API_CONTRACTS
        .iter()
        .find(|c| c.opcode == opcode)
        .ok_or("unknown file operation")?;
    if args.len() != contract.params.len() {
        return Err("invalid file argument count");
    }
    for (param, arg) in contract.params.iter().zip(args) {
        match (param.type_name, arg) {
            ("string", LuaScalar::Bytes(v)) => {
                let value = std::str::from_utf8(v).map_err(|_| "file text must be UTF-8")?;
                let max = match param.name {
                    "path" | "destination" => 256,
                    "key" => 128,
                    _ => 65_536,
                };
                if value.len() > max {
                    return Err("file argument exceeds budget");
                }
                if matches!(param.name, "path" | "destination") {
                    super::data_runtime::validate_file_request(
                        14100,
                        &[LuaScalar::Bytes(v.clone())],
                    )?;
                }
                if param.name == "key" && (value.is_empty() || value.chars().any(char::is_control))
                {
                    return Err("invalid data key");
                }
                if param.name == "valueJson" {
                    super::data_runtime::parse(value)?;
                }
                if param.name == "text"
                    && matches!(opcode, 14118 | 14121)
                    && value.contains(['\r', '\n'])
                {
                    return Err("line text must be single line");
                }
            }
            ("integer", LuaScalar::Integer(v)) => {
                if (param.name == "line" && !(1..=65_537).contains(v))
                    || (param.name == "delta"
                        && !(-9_007_199_254_740_991..=9_007_199_254_740_991).contains(v))
                {
                    return Err("file integer outside range");
                }
            }
            _ => return Err("invalid file argument type"),
        }
    }
    Ok(())
}
pub(crate) fn resume(opcode: u32, payload: &[u8]) -> Result<LuaInput, &'static str> {
    if payload.len() > MAX_WIRE {
        return Err("file response exceeds budget");
    }
    let text = std::str::from_utf8(payload).map_err(|_| "file response must be UTF-8")?;
    let value: Value = serde_json::from_str(text).map_err(|_| "invalid file scalar response")?;
    let contract = script_api::API_CONTRACTS
        .iter()
        .find(|c| c.opcode == opcode)
        .ok_or("unknown file response")?;
    let scalar = match (contract.return_type, value) {
        ("string", Value::String(text)) if text.len() <= 65_536 => {
            LuaScalar::Bytes(text.into_bytes())
        }
        ("integer", Value::Number(n)) => {
            let value = n.as_i64().ok_or("invalid file integer response")?;
            let valid = match opcode {
                14111 | 14112 | 14124 => matches!(value, 0 | 1),
                14120 => (0..=65_536).contains(&value),
                14126 => true,
                14140 => (0..=64 * 1024 * 1024).contains(&value),
                _ => value == 1,
            };
            if !valid {
                return Err("invalid file operation result");
            }
            LuaScalar::Integer(value)
        }
        _ => return Err("file response type mismatch"),
    };
    Ok(LuaInput::Values(vec![LuaScalar::Boolean(true), scalar]))
}
#[cfg(test)]
mod tests {
    use super::*;
    fn b(value: &str) -> LuaScalar {
        LuaScalar::Bytes(value.as_bytes().to_vec())
    }
    #[test]
    fn file_boundaries_enforce_contract_and_exact_scalar_response() {
        assert!(validate(
            14118,
            &[b("folder/a.txt"), LuaScalar::Integer(1), b("中文")]
        )
        .is_ok());
        assert!(validate(
            14118,
            &[b("folder/a.txt"), LuaScalar::Integer(1), b("a\nb")]
        )
        .is_err());
        assert!(validate(14114, &[b("a"), b("../b")]).is_err());
        assert!(validate(14126, &[b("a"), b("count"), LuaScalar::Number(1.0)]).is_err());
        assert!(validate(14122, &[b("a"), b("count"), b("{bad}")]).is_err());
        assert!(validate(14111, &[b("a"), b("extra")]).is_err());
        assert!(resume(14111, b"2").is_err());
        assert!(resume(14117, b"[]").is_err());
        assert!(resume(14117, b"1").is_err());
        assert!(resume(14120, b"-1").is_err());
        assert!(resume(14126, b"9223372036854775807").is_ok());
    }
}
