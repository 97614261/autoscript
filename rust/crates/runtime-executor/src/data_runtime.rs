//! Bounded JSON data operations. Values are data; no Lua evaluation or metamethods are involved.

use lua_runtime::{LuaInput, LuaScalar};
use serde_json::Value;

pub(crate) const MAX_DATA_BYTES: usize = 65_536;
const MAX_DATA_DEPTH: usize = 16;
const MAX_DATA_ITEMS: usize = 1024;

fn argument(args: &[LuaScalar], index: usize) -> Result<&str, &'static str> {
    let Some(LuaScalar::Bytes(bytes)) = args.get(index) else {
        return Err("data argument must be a string");
    };
    if bytes.len() > MAX_DATA_BYTES {
        return Err("data exceeds 64 KiB");
    }
    std::str::from_utf8(bytes).map_err(|_| "data must be UTF-8")
}

fn validate(value: &Value, depth: usize, items: &mut usize) -> Result<(), &'static str> {
    if depth > MAX_DATA_DEPTH {
        return Err("data depth exceeds 16");
    }
    let child_count = match value {
        Value::Array(values) => values.len(),
        Value::Object(values) => {
            if values
                .keys()
                .any(|key| key.len() > 128 || key.is_empty() || key.chars().any(char::is_control))
            {
                return Err("object key must be bounded text");
            }
            values.len()
        }
        _ => return Ok(()),
    };
    *items = items
        .checked_add(child_count)
        .ok_or("data item count overflow")?;
    if *items > MAX_DATA_ITEMS {
        return Err("data items exceed 1024");
    }
    // Traverse borrowed collections directly; validation must not allocate a
    // parallel child vector at every nesting level.
    match value {
        Value::Array(values) => {
            for child in values {
                validate(child, depth + 1, items)?;
            }
        }
        Value::Object(values) => {
            for child in values.values() {
                validate(child, depth + 1, items)?;
            }
        }
        _ => unreachable!(),
    }
    Ok(())
}

pub(super) fn parse(text: &str) -> Result<Value, &'static str> {
    if text.len() > MAX_DATA_BYTES {
        return Err("data exceeds 64 KiB");
    }
    // Bound nesting before the JSON parser allocates a recursive value.
    let (mut depth, mut quoted, mut escaped) = (0usize, false, false);
    for byte in text.bytes() {
        if quoted {
            if escaped {
                escaped = false;
            } else if byte == b'\\' {
                escaped = true;
            } else if byte == b'"' {
                quoted = false;
            }
        } else if byte == b'"' {
            quoted = true;
        } else if matches!(byte, b'[' | b'{') {
            depth += 1;
            if depth > MAX_DATA_DEPTH {
                return Err("data depth exceeds 16");
            }
        } else if matches!(byte, b']' | b'}') {
            depth = depth.saturating_sub(1);
        }
    }
    let value: Value = serde_json::from_str(text).map_err(|_| "invalid JSON data")?;
    validate(&value, 0, &mut 0)?;
    Ok(value)
}

fn primitive(text: &str) -> Result<Value, &'static str> {
    let value = parse(text)?;
    if value.is_array() || value.is_object() {
        return Err("operation requires JSON primitive");
    }
    Ok(value)
}

pub(super) fn encode(value: &Value) -> Result<LuaScalar, &'static str> {
    validate(value, 0, &mut 0)?;
    let bytes = serde_json::to_vec(value).map_err(|_| "JSON encoding failed")?;
    if bytes.len() > MAX_DATA_BYTES {
        return Err("data result exceeds 64 KiB");
    }
    Ok(LuaScalar::Bytes(bytes))
}

fn index(key: &str, length: usize) -> Result<usize, &'static str> {
    let parsed = key
        .parse::<usize>()
        .map_err(|_| "array index must be decimal text from 1")?;
    if parsed == 0 || parsed > length || parsed.to_string() != key {
        return Err("array index is out of range");
    }
    Ok(parsed - 1)
}

pub(crate) fn execute(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let expected = match opcode {
        14000 | 14005 | 14006 => 1,
        14001 | 14003 | 14004 => 2,
        14002 => 3,
        _ => return Err("unknown data operation"),
    };
    if args.len() != expected {
        return Err("invalid data argument count");
    }
    if opcode == 14006 {
        let Value::String(value) = primitive(argument(args, 0)?)? else {
            return Err("Data.text requires JSON string");
        };
        return Ok(LuaScalar::Bytes(value.into_bytes()));
    }
    let mut container = parse(argument(args, 0)?)?;
    if !container.is_array() && !container.is_object() {
        return Err("data must be JSON collection or object");
    }
    if opcode == 14000 {
        let length = container
            .as_array()
            .map(Vec::len)
            .or_else(|| container.as_object().map(serde_json::Map::len))
            .unwrap_or(0);
        return Ok(LuaScalar::Integer(
            i64::try_from(length).map_err(|_| "data length overflow")?,
        ));
    }
    if opcode == 14005 {
        let keys = match &container {
            Value::Array(values) => (1..=values.len())
                .map(|index| Value::String(index.to_string()))
                .collect(),
            Value::Object(values) => values.keys().cloned().map(Value::String).collect(),
            _ => unreachable!(),
        };
        return encode(&Value::Array(keys));
    }
    if opcode == 14004 {
        let value = primitive(argument(args, 1)?)?;
        let Some(values) = container.as_array_mut() else {
            return Err("Data.append requires collection");
        };
        values.push(value);
        return encode(&container);
    }
    let key = argument(args, 1)?;
    if key.is_empty() || key.len() > 128 || key.chars().any(char::is_control) {
        return Err("invalid data key");
    }
    let new_value = if opcode == 14002 {
        Some(primitive(argument(args, 2)?)?)
    } else {
        None
    };
    let value = match &mut container {
        Value::Array(values) => {
            let index = index(key, values.len())?;
            match opcode {
                14001 => values[index].clone(),
                14002 => {
                    values[index] = new_value.unwrap();
                    Value::Null
                }
                14003 => {
                    values.remove(index);
                    Value::Null
                }
                _ => unreachable!(),
            }
        }
        Value::Object(values) => match opcode {
            14001 => values.get(key).cloned().unwrap_or(Value::Null),
            14002 => {
                values.insert(key.into(), new_value.unwrap());
                Value::Null
            }
            14003 => {
                values.remove(key);
                Value::Null
            }
            _ => unreachable!(),
        },
        _ => unreachable!(),
    };
    if opcode == 14001 {
        if value.is_array() || value.is_object() {
            return Err("Data.get result is not a primitive");
        }
        encode(&value)
    } else {
        encode(&container)
    }
}

pub(crate) fn resume(result: Result<LuaScalar, &'static str>) -> LuaInput {
    match result {
        Ok(value) => LuaInput::Values(vec![LuaScalar::Boolean(true), value]),
        Err(message) => LuaInput::Values(vec![
            LuaScalar::Boolean(false),
            LuaScalar::Nil,
            LuaScalar::Nil,
            LuaScalar::Bytes(b"DATA_INVALID".to_vec()),
            LuaScalar::Bytes(message.as_bytes().to_vec()),
        ]),
    }
}

pub(crate) fn validate_file_request(opcode: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    if args.len() != if opcode == 14101 { 2 } else { 1 } {
        return Err("invalid file argument count");
    }
    let path = argument(args, 0)?;
    if path.len() > 256
        || (path.is_empty() && opcode != 14102)
        || (!path.is_empty()
            && (path.split('/').count() > 16
                || path.split('/').any(|part| {
                    part.is_empty()
                        || part == "."
                        || part == ".."
                        || part.starts_with('.')
                        || part.ends_with('.')
                        || part.ends_with(' ')
                        || part.chars().any(|c| {
                            c.is_control()
                                || matches!(c, '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')
                        })
                })))
    {
        return Err("file path must stay inside project data");
    }
    if opcode == 14101 {
        argument(args, 1)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn bytes(value: &str) -> LuaScalar {
        LuaScalar::Bytes(value.as_bytes().to_vec())
    }
    #[test]
    fn borrowed_validation_retains_nested_item_key_and_depth_budgets() {
        let array = Value::Array(vec![Value::Null; 1024]);
        assert!(encode(&array).is_ok());
        assert_eq!(
            encode(&Value::Array(vec![array])),
            Err("data items exceed 1024")
        );
        let object = serde_json::json!({"a": [null, {"b": [1, 2]}], "c": true});
        let mut count = 0;
        validate(&object, 0, &mut count).unwrap();
        assert_eq!(count, 7);
        for key in ["", "\n", &"x".repeat(129)] {
            let value = Value::Object([(key.to_owned(), Value::Null)].into_iter().collect());
            assert_eq!(encode(&value), Err("object key must be bounded text"));
        }
        assert_eq!(
            validate(&Value::Null, 17, &mut 0),
            Err("data depth exceeds 16")
        );
        let mut overflow = usize::MAX;
        assert_eq!(
            validate(&serde_json::json!([null]), 0, &mut overflow),
            Err("data item count overflow")
        );
    }

    #[test]
    fn collections_objects_primitive_types_and_unicode_execute() {
        assert_eq!(
            execute(14000, &[bytes("[1,true,\"汉字\"]")]).unwrap(),
            LuaScalar::Integer(3)
        );
        assert_eq!(
            execute(14001, &[bytes("{\"text\":\"汉字\"}"), bytes("text")]).unwrap(),
            bytes("\"汉字\"")
        );
        assert_eq!(execute(14006, &[bytes("\"汉字\"")]).unwrap(), bytes("汉字"));
        assert_eq!(
            execute(14002, &[bytes("[1,true]"), bytes("2"), bytes("\"new\"")]).unwrap(),
            bytes("[1,\"new\"]")
        );
        assert_eq!(
            execute(14003, &[bytes("[1,true]"), bytes("1")]).unwrap(),
            bytes("[true]")
        );
        assert_eq!(
            execute(14004, &[bytes("[]"), bytes("false")]).unwrap(),
            bytes("[false]")
        );
        assert_eq!(
            execute(14005, &[bytes("{\"b\":1,\"a\":2}")]).unwrap(),
            bytes("[\"a\",\"b\"]")
        );
        assert_eq!(
            execute(14001, &[bytes("{}"), bytes("missing")]).unwrap(),
            bytes("null")
        );
    }

    #[test]
    fn malformed_deep_large_nonprimitive_and_path_escape_are_rejected() {
        for text in ["[", "NaN", "1e999", "1", "null"] {
            assert!(execute(14000, &[bytes(text)]).is_err());
        }
        assert!(execute(
            14000,
            &[bytes(&format!("{}0{}", "[".repeat(17), "]".repeat(17)))]
        )
        .is_err());
        assert!(execute(14000, &[bytes(&format!("[{}]", vec!["0"; 1025].join(",")))]).is_err());
        assert!(execute(
            14004,
            &[bytes("[]"), bytes(&format!("\"{}\"", "字".repeat(22_000)))]
        )
        .is_err());
        assert!(execute(14001, &[bytes("[[1]]"), bytes("1")]).is_err());
        assert!(execute(14002, &[bytes("[1]"), bytes("0"), bytes("1")]).is_err());
        assert!(execute(14006, &[bytes("1")]).is_err());
        for path in [
            "../secret",
            "/source",
            "x/../../secret",
            "x\\secret",
            "C:/secret",
            ".trash/state",
            "x//y",
        ] {
            assert!(validate_file_request(14100, &[bytes(path)]).is_err());
        }
        assert!(validate_file_request(14102, &[bytes("")]).is_ok());
        assert!(
            validate_file_request(14101, &[bytes("state/settings.json"), bytes("汉字")]).is_ok()
        );
    }

    #[test]
    fn sandbox_api_executes_data_without_exposing_lua_tables() {
        use crate::{ExecutorConfig, ExternalHost, ExternalHostQueue, RuntimeExecutor};
        use lua_runtime::LuaRuntimeConfig;
        use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = RuntimeExecutor::new(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            ExternalHost::new(queue),
        )
        .unwrap();
        let task = runtime
            .start_entry_chunk(
                br#"return function()
            collection=Data.append('[1]', 'true')
            count=Data.length(collection)
            item=Data.get(collection,'2')
            object=Data.set('{}','label','"hello"')
            decoded=Data.text(Data.get(object,'label'))
            keys=Data.keys(object)
            removed=Data.remove(collection,'1')
        end"#,
                "data-runtime",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        for (name, expected) in [
            ("collection", "[1,true]"),
            ("item", "true"),
            ("decoded", "hello"),
            ("keys", "[\"label\"]"),
            ("removed", "[true]"),
        ] {
            assert_eq!(runtime.lua().global_scalar(name).unwrap(), bytes(expected));
        }
        assert_eq!(
            runtime.lua().global_scalar("count").unwrap(),
            LuaScalar::Integer(2)
        );
    }

    #[test]
    fn file_host_waits_for_completion_and_rejects_paths_before_dispatch() {
        use crate::{
            ExecutorConfig, ExternalHost, ExternalHostEvent, ExternalHostQueue, RuntimeExecutor,
        };
        use lua_runtime::LuaRuntimeConfig;
        use runtime_scheduler::{
            HostCompletion, HostResult, ManualClock, MonoTime, SchedulerConfig, TaskState,
        };
        use std::time::Duration;
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = RuntimeExecutor::new(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            ExternalHost::new(queue.clone()),
        )
        .unwrap();
        let task = runtime
            .start_entry_chunk(
                b"return function() read=Files.read('state/value.txt') end",
                "files-runtime",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.lua().global_scalar("read").unwrap(), LuaScalar::Nil);
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("file read was not dispatched")
        };
        assert_eq!(request.opcode, 14100);
        assert_eq!(request.timeout, Duration::from_secs(3));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"hello".to_vec()),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(runtime.lua().global_scalar("read").unwrap(), bytes("hello"));
        let invalid = runtime
            .start_entry_chunk(
                b"return function() Files.remove('../escape') end",
                "bad-path",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(invalid), Some(TaskState::Failed));
        assert!(queue.wait_next_timeout(Duration::from_millis(10)).is_none());
    }
}
