//! Bounded pure operations shared by scripts and compiled visual blocks.
use lua_runtime::LuaScalar;
use serde_json::Value;
use std::sync::OnceLock;
const MAX: usize = 65_536;
type ResultValue = Result<LuaScalar, &'static str>;

fn text(value: &LuaScalar, max: usize) -> Result<&str, &'static str> {
    match value {
        LuaScalar::Bytes(bytes) if bytes.len() <= max => {
            std::str::from_utf8(bytes).map_err(|_| "text must be UTF-8")
        }
        _ => Err("text exceeds budget or has wrong type"),
    }
}
fn integer(value: &LuaScalar, min: i64, max: i64) -> Result<usize, &'static str> {
    match value {
        LuaScalar::Integer(n) if (min..=max).contains(n) => {
            usize::try_from(*n).map_err(|_| "integer outside budget")
        }
        _ => Err("expected bounded exact integer"),
    }
}
fn numeric(value: f64) -> ResultValue {
    if !value.is_finite() || value.abs() > 1e12 {
        return Err("numeric result outside finite budget");
    }
    Ok(LuaScalar::Number(value))
}
fn append(out: &mut String, value: &str) -> Result<(), &'static str> {
    if value.len() > MAX - out.len() {
        return Err("text result exceeds 64 KiB");
    }
    out.push_str(value);
    Ok(())
}
fn bytes(value: String) -> LuaScalar {
    LuaScalar::Bytes(value.into_bytes())
}

#[cfg(test)]
pub(crate) fn execute(opcode: u32, args: &[LuaScalar]) -> ResultValue {
    execute_with_regex(opcode, args, crate::regex_cache::compile)
}

pub(crate) fn execute_with_regex(
    opcode: u32,
    args: &[LuaScalar],
    compile: impl FnOnce(&str) -> crate::regex_cache::Compiled,
) -> ResultValue {
    match (opcode, args) {
        (1022, [op, value]) => {
            let a = crate::utility_runtime::number(value)?;
            let result = match text(op, 16)? {
                "abs" => a.abs(),
                "ceil" => a.ceil(),
                "floor" => a.floor(),
                "sqrt" => a.sqrt(),
                "ln" => a.ln(),
                "log10" => a.log10(),
                "exp" => a.exp(),
                "sin" => a.sin(),
                "cos" => a.cos(),
                "tan" => a.tan(),
                "asin" => a.asin(),
                "acos" => a.acos(),
                "atan" => a.atan(),
                "deg" => a.to_degrees(),
                "rad" => a.to_radians(),
                _ => return Err("unknown unary operation"),
            };
            numeric(result)
        }
        (1023, [op, left, right]) => {
            let a = crate::utility_runtime::number(left)?;
            let b = crate::utility_runtime::number(right)?;
            let result = match text(op, 16)? {
                "min" => a.min(b),
                "max" => a.max(b),
                "pow" => a.powf(b),
                "atan2" if a != 0.0 || b != 0.0 => a.atan2(b),
                // Base one yields a non-finite result, rejected by numeric().
                "log" if a > 0.0 && b > 0.0 => a.log(b),
                _ => return Err("unknown binary operation or invalid domain"),
            };
            numeric(result)
        }
        (1024, [value, digits]) => numeric(rounded(value, digits)?),
        (14225, [value, digits, width]) => format_number(value, digits, width),
        (14226, [source, pattern, replacement]) => regex_replace(
            text(source, 4096)?,
            text(pattern, 128)?,
            text(replacement, 4096)?,
            compile,
        ),
        (14227, [source, mode]) => filter(text(source, MAX)?, text(mode, 24)?),
        (14228, [source]) => Ok(bytes(text(source, MAX)?.chars().rev().collect())),
        (14237, [source, index]) => number_at(text(source, 4096)?, integer(index, 1, 64)?),
        (14011, [value, mode]) => encode(text(value, MAX)?, text(mode, 8)?),
        (14012, [source, mode]) => sort(text(source, MAX)?, text(mode, 16)?),
        _ => Err("invalid pure utility operation or argument count"),
    }
}

fn number_at(source: &str, index: usize) -> ResultValue {
    static PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    let pattern = PATTERN.get_or_init(|| {
        regex::Regex::new(r"[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)")
            .expect("fixed bounded decimal pattern")
    });
    let mut selected = "";
    // Finish scanning even after finding the item: an over-budget source fails as a whole.
    for (offset, value) in pattern.find_iter(source).enumerate() {
        if offset >= 64 {
            return Err("number extraction exceeds 64 items");
        }
        if offset + 1 == index {
            selected = value.as_str();
        }
    }
    Ok(bytes(selected.to_owned()))
}
fn rounded(value: &LuaScalar, digits: &LuaScalar) -> Result<f64, &'static str> {
    let value = crate::utility_runtime::number(value)?;
    let digits = integer(digits, 0, 9)?;
    let power = 10_f64.powi(i32::try_from(digits).map_err(|_| "invalid digits")?);
    let result = (value * power).round() / power;
    if !result.is_finite() || result.abs() > 1e12 {
        return Err("rounded result exceeds budget");
    }
    Ok(if result == 0.0 { 0.0 } else { result })
}
fn format_number(value: &LuaScalar, digits: &LuaScalar, width: &LuaScalar) -> ResultValue {
    let value = rounded(value, digits)?;
    let digits = integer(digits, 0, 9)?;
    let width = integer(width, 1, 32)?;
    let formatted = format!("{:.*}", digits, value.abs());
    let integer_length = formatted.find('.').unwrap_or(formatted.len());
    let padding = width.saturating_sub(integer_length);
    Ok(bytes(format!(
        "{}{}{}",
        if value < 0.0 { "-" } else { "" },
        "0".repeat(padding),
        formatted
    )))
}
fn regex_replace(
    source: &str,
    pattern: &str,
    replacement: &str,
    compile: impl FnOnce(&str) -> crate::regex_cache::Compiled,
) -> ResultValue {
    if pattern.is_empty() {
        return Err("regex pattern cannot be empty");
    }
    let regex = compile(pattern)?;
    let mut result = String::with_capacity(source.len());
    let mut end = 0;
    for (index, matched) in regex.find_iter(source).enumerate() {
        if index >= 1024 {
            return Err("regex replacements exceed 1024");
        }
        append(&mut result, &source[end..matched.start()])?;
        append(&mut result, replacement)?;
        end = matched.end();
    }
    append(&mut result, &source[end..])?;
    Ok(bytes(result))
}
fn filter(source: &str, mode: &str) -> ResultValue {
    let (keep, class) = if let Some(class) = mode.strip_prefix("keep") {
        (true, class)
    } else if let Some(class) = mode.strip_prefix("remove") {
        (false, class)
    } else {
        return Err("unknown filter mode");
    };
    if !matches!(class, "Digits" | "Letters" | "Alnum" | "Han" | "Whitespace") {
        return Err("unknown character class");
    }
    // Compile only the selected fixed Unicode property, never a user pattern.
    let han = if class == "Han" {
        static HAN: OnceLock<Result<regex::Regex, regex::Error>> = OnceLock::new();
        Some(
            HAN.get_or_init(|| regex::Regex::new(r"\p{Han}"))
                .as_ref()
                .map_err(|_| "Unicode class unavailable")?,
        )
    } else {
        None
    };
    let mut result = String::with_capacity(source.len());
    let mut encoded = [0u8; 4];
    for ch in source.chars() {
        let selected = match class {
            "Digits" => ch.is_ascii_digit(),
            "Letters" => ch.is_ascii_alphabetic(),
            "Alnum" => ch.is_ascii_alphanumeric(),
            "Whitespace" => ch.is_whitespace(),
            "Han" => han
                .as_ref()
                .is_some_and(|r| r.is_match(ch.encode_utf8(&mut encoded))),
            _ => unreachable!(),
        };
        if selected == keep {
            result.push(ch);
        }
    }
    Ok(bytes(result))
}
fn encode(source: &str, mode: &str) -> ResultValue {
    let value = match mode {
        "text" => {
            // Check escaped size before serialization allocates a larger buffer.
            let length = source
                .chars()
                .try_fold(2usize, |length, ch| {
                    let bytes = match ch {
                        '"' | '\\' | '\n' | '\r' | '\t' | '\u{8}' | '\u{c}' => 2,
                        ch if ch.is_control() && ch <= '\u{1f}' => 6,
                        ch => ch.len_utf8(),
                    };
                    length.checked_add(bytes).filter(|length| *length <= MAX)
                })
                .ok_or("encoded JSON exceeds 64 KiB")?;
            debug_assert!(length <= MAX);
            Value::String(source.into())
        }
        "json" => crate::data_runtime::parse(source)?,
        _ => return Err("unknown JSON encoding mode"),
    };
    crate::data_runtime::encode(&value)
}
fn sort(source: &str, mode: &str) -> ResultValue {
    let (numbers, descending) = match mode {
        "numberAsc" => (true, false),
        "numberDesc" => (true, true),
        "textAsc" => (false, false),
        "textDesc" => (false, true),
        _ => return Err("unknown sort mode"),
    };
    let Value::Array(values) = crate::data_runtime::parse(source)? else {
        return Err("sort requires JSON array");
    };
    let sorted = if numbers {
        let mut values = values
            .into_iter()
            .map(|value| {
                let n = value
                    .as_f64()
                    .filter(|n| n.is_finite() && n.abs() <= 1e12)
                    .ok_or("sort requires bounded numbers only")?;
                Ok((n, value))
            })
            .collect::<Result<Vec<_>, &'static str>>()?;
        values.sort_by(|a, b| {
            let order = a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal);
            if descending {
                order.reverse()
            } else {
                order
            }
        });
        values.into_iter().map(|(_, value)| value).collect()
    } else {
        let mut values = values
            .into_iter()
            .map(|value| match value {
                Value::String(text) => Ok(text),
                _ => Err("sort requires strings only"),
            })
            .collect::<Result<Vec<_>, _>>()?;
        values.sort_by(|a, b| if descending { b.cmp(a) } else { a.cmp(b) });
        values.into_iter().map(Value::String).collect()
    };
    crate::data_runtime::encode(&Value::Array(sorted))
}
