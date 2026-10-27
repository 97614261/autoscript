//! Validate typed platform/network requests before the narrow Android callback.
use lua_runtime::{LuaInput, LuaScalar};
fn text(value: &LuaScalar, max: usize) -> Result<&str, &'static str> {
    match value {
        LuaScalar::Bytes(value) if value.len() <= max => {
            std::str::from_utf8(value).map_err(|_| "text must be UTF-8")
        }
        _ => Err("invalid bounded text"),
    }
}
pub(crate) fn validate(op: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    match (op, args) {
        (
            18006,
            [name, path, LuaScalar::Integer(x), LuaScalar::Integer(y), LuaScalar::Integer(w), LuaScalar::Integer(h), LuaScalar::Integer(drag)],
        ) => {
            if !crate::floating_runtime::name(text(name, 32)?)
                || !text(path, 256)?.starts_with("assets/images/")
                || !(-1..=8192).contains(x)
                || !(-1..=8192).contains(y)
                || !(1..=2048).contains(w)
                || !(1..=2048).contains(h)
                || !(0..=1).contains(drag)
            {
                return Err("invalid interactive image options");
            }
            crate::data_runtime::validate_file_request(14100, std::slice::from_ref(path))
        }
        (18007, [name, LuaScalar::Integer(timeout)])
            if crate::floating_runtime::name(text(name, 32)?) && (0..=60000).contains(timeout) =>
        {
            Ok(())
        }
        (18008, [name]) if crate::floating_runtime::name(text(name, 32)?) => Ok(()),
        (18004, []) => Ok(()),
        (18005, [value]) if !text(value, 4096)?.contains('\0') => Ok(()),
        (18000, [section])
            if [
                "device",
                "display",
                "battery",
                "network",
                "storage",
                "permissions",
            ]
            .contains(&text(section, 16)?) =>
        {
            Ok(())
        }
        (18001, [LuaScalar::Integer(enabled)]) if (0..=1).contains(enabled) => Ok(()),
        (18002, [url]) => validate_url(text(url, 2048)?),
        (
            18003,
            [method, url, headers, body, LuaScalar::Integer(timeout), LuaScalar::Integer(limit)],
        ) => {
            let method = text(method, 8)?;
            if !["GET", "POST", "PUT", "OPTIONS", "DELETE", "HEAD"].contains(&method)
                || !(100..=30000).contains(timeout)
                || !(1..=16384).contains(limit)
            {
                return Err("HTTP options outside budget");
            }
            validate_url(text(url, 2048)?)?;
            if ["GET", "HEAD"].contains(&method) && !text(body, 8192)?.is_empty() {
                return Err("GET/HEAD cannot have body");
            }
            text(body, 8192)?;
            let headers = crate::data_runtime::parse(text(headers, 4096)?)?;
            let headers = headers
                .as_object()
                .filter(|value| value.len() <= 32)
                .ok_or("HTTP headers must be bounded object")?;
            for (key, value) in headers {
                if key.len() > 64
                    || !key
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&c))
                    || value.as_str().is_none_or(|value| {
                        value.len() > 1024 || value.chars().any(char::is_control)
                    })
                {
                    return Err("invalid HTTP header");
                }
                if [
                    "host",
                    "connection",
                    "content-length",
                    "transfer-encoding",
                    "accept-encoding",
                    "expect",
                    "upgrade",
                    "te",
                    "trailer",
                ]
                .contains(&key.to_ascii_lowercase().as_str())
                    || key.to_ascii_lowercase().starts_with("proxy-")
                {
                    return Err("restricted HTTP header");
                }
            }
            Ok(())
        }
        _ => Err("invalid platform/network arguments"),
    }
}
fn validate_url(url: &str) -> Result<(), &'static str> {
    let authority = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .ok_or("URL requires http/https")?;
    if authority.is_empty()
        || authority
            .split(['/', '?', '#'])
            .next()
            .is_none_or(|v| v.is_empty() || v.contains('@'))
        || url.chars().any(|c| c.is_control() || c.is_whitespace())
    {
        return Err("invalid or credential-bearing URL");
    }
    Ok(())
}
pub(crate) fn response(op: u32, bytes: Vec<u8>) -> Result<LuaInput, &'static str> {
    let value = if matches!(op, 18001 | 18002 | 18005 | 18006 | 18008) {
        if !bytes.is_empty() {
            return Err("unexpected platform response");
        }
        LuaScalar::Integer(1)
    } else {
        if bytes.len() > 32768 {
            return Err("platform response exceeds budget");
        }
        let source = std::str::from_utf8(&bytes).map_err(|_| "platform response must be UTF-8")?;
        let value = crate::data_runtime::parse(source)?;
        let object = value
            .as_object()
            .ok_or("platform response requires JSON object")?;
        if op == 18007
            && (object.len() != 7
                || !object
                    .get("kind")
                    .and_then(serde_json::Value::as_str)
                    .is_some_and(|v| ["click", "drag", "timeout", "closed"].contains(&v))
                || !["x", "y", "localX", "localY"].iter().all(|key| {
                    object
                        .get(*key)
                        .and_then(serde_json::Value::as_u64)
                        .is_some_and(|v| v <= 32768)
                })
                || !["sequence", "dropped"].iter().all(|key| {
                    object
                        .get(*key)
                        .and_then(serde_json::Value::as_u64)
                        .is_some_and(|v| i64::try_from(v).is_ok())
                }))
        {
            return Err("invalid interactive event response");
        }
        if op == 18003
            && (object.len() != 4
                || object
                    .get("status")
                    .and_then(serde_json::Value::as_u64)
                    .is_none_or(|v| !(100..=599).contains(&v))
                || object.get("headers").is_none_or(|v| !v.is_object())
                || object
                    .get("bodyBase64")
                    .and_then(serde_json::Value::as_str)
                    .is_none_or(|v| {
                        v.len() > 21848
                            || !v
                                .bytes()
                                .all(|c| c.is_ascii_alphanumeric() || b"+/=".contains(&c))
                    })
                || object
                    .get("elapsedMs")
                    .and_then(serde_json::Value::as_u64)
                    .is_none_or(|v| v > 35000))
        {
            return Err("invalid HTTP response shape");
        }
        LuaScalar::Bytes(bytes)
    };
    Ok(LuaInput::Values(vec![LuaScalar::Boolean(true), value]))
}
