//! Bounded Unicode text and finite-automaton regex; no executable expressions.
use lua_runtime::LuaScalar;
use serde_json::Value;
const MAX: usize = 65_536;
fn text(args: &[LuaScalar], index: usize) -> Result<&str, &'static str> {
    let Some(LuaScalar::Bytes(v)) = args.get(index) else {
        return Err("text argument must be UTF-8 string");
    };
    if v.len() > MAX {
        return Err("text exceeds 64 KiB");
    }
    std::str::from_utf8(v).map_err(|_| "text must be UTF-8")
}
fn count(args: &[LuaScalar], index: usize, maximum: usize) -> Result<usize, &'static str> {
    let Some(LuaScalar::Integer(v)) = args.get(index) else {
        return Err("index/count must be integer");
    };
    usize::try_from(*v)
        .ok()
        .filter(|v| *v <= maximum)
        .ok_or("index/count outside budget")
}
fn output(value: String) -> Result<LuaScalar, &'static str> {
    if value.len() > MAX {
        return Err("text result exceeds 64 KiB");
    }
    Ok(LuaScalar::Bytes(value.into_bytes()))
}
fn number(value: usize) -> Result<LuaScalar, &'static str> {
    Ok(LuaScalar::Integer(
        i64::try_from(value).map_err(|_| "text count overflow")?,
    ))
}
fn start(text: &str, value: usize) -> Result<usize, &'static str> {
    if value == 0 {
        return Err("text index starts at 1");
    }
    // Include the legal one-past-end position without first scanning the whole
    // string. Inserting/finding near the front should not traverse the tail.
    text.char_indices()
        .map(|(i, _)| i)
        .chain(std::iter::once(text.len()))
        .nth(value - 1)
        .ok_or("text index out of range")
}
fn slice(text: &str, offset: usize, length: usize) -> String {
    let begin = text
        .char_indices()
        .nth(offset)
        .map_or(text.len(), |(i, _)| i);
    let tail = &text[begin..];
    let end = tail
        .char_indices()
        .nth(length)
        .map_or(tail.len(), |(i, _)| i);
    tail[..end].to_owned()
}
pub(crate) fn execute(opcode: u32, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let expected = match opcode {
        14200 | 14218 | 14220 | 14221 => 1,
        14209..=14213 | 14215 | 14219 => 3,
        14201..=14208 | 14214 | 14216 | 14217 | 14222..=14224 => 2,
        _ => return Err("unknown text operation"),
    };
    if args.len() != expected {
        return Err("invalid text argument count");
    }
    validate_text_limits(opcode, args)?;
    let source = text(args, 0)?;
    match opcode {
        14200 => number(source.chars().count()),
        14201..=14204 => compare(opcode, source, text(args, 1)?),
        14205 => split(source, text(args, 1)?),
        14206 => join(source, text(args, 1)?),
        14207 | 14208 => {
            let n = count(args, 1, MAX)?;
            let offset = if opcode == 14208 {
                source.chars().count().saturating_sub(n)
            } else {
                0
            };
            output(slice(source, offset, n))
        }
        14209 | 14211 => {
            let at = count(args, 1, MAX + 1)?;
            let begin = start(source, at)?;
            let n = count(args, 2, MAX)?;
            if opcode == 14209 {
                output(slice(&source[begin..], 0, n))
            } else {
                let end = begin
                    + source[begin..]
                        .char_indices()
                        .nth(n)
                        .map_or(source.len() - begin, |(i, _)| i);
                output(format!("{}{}", &source[..begin], &source[end..]))
            }
        }
        14210 => {
            let begin = start(source, count(args, 1, MAX + 1)?)?;
            output(format!(
                "{}{}{}",
                &source[..begin],
                text(args, 2)?,
                &source[begin..]
            ))
        }
        14212 | 14213 => {
            let needle = text(args, 1)?;
            if needle.is_empty() {
                return Err("replacement needle cannot be empty");
            }
            let replacement = text(args, 2)?;
            let occurrences = if opcode == 14213 {
                usize::from(source.contains(needle))
            } else {
                source.matches(needle).count()
            };
            let length = source
                .len()
                .checked_sub(occurrences * needle.len())
                .and_then(|v| v.checked_add(occurrences.checked_mul(replacement.len())?))
                .ok_or("replacement overflow")?;
            if length > MAX {
                return Err("replacement exceeds 64 KiB");
            }
            output(if opcode == 14213 {
                source.replacen(needle, replacement, 1)
            } else {
                source.replace(needle, replacement)
            })
        }
        14214 => {
            let n = count(args, 1, 1024)?;
            if source.len().checked_mul(n).is_none_or(|v| v > MAX) {
                return Err("repeat exceeds 64 KiB");
            }
            output(source.repeat(n))
        }
        14215..=14217 => marked(opcode, source, args),
        14218 => output(source.trim().into()),
        14219 => {
            let at = count(args, 2, MAX + 1)?;
            let begin = start(source, at)?;
            number(
                source[begin..]
                    .find(text(args, 1)?)
                    .map_or(0, |i| source[..begin + i].chars().count() + 1),
            )
        }
        14220 => output(source.to_lowercase()),
        14221 => output(source.to_uppercase()),
        14222..=14224 => regex(opcode, source, text(args, 1)?),
        _ => Err("unknown text operation"),
    }
}
fn validate_text_limits(opcode: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    for (index, arg) in args.iter().enumerate() {
        if let LuaScalar::Bytes(value) = arg {
            let limit = if (14222..=14224).contains(&opcode) {
                if index == 0 {
                    4096
                } else {
                    128
                }
            } else if (index == 1 && matches!(opcode,14201..=14206|14212|14213|14215..=14217|14219))
                || (opcode == 14215 && index == 2)
            {
                4096
            } else {
                MAX
            };
            if value.len() > limit {
                return Err("text field exceeds byte budget");
            }
        }
    }
    Ok(())
}
fn compare(opcode: u32, source: &str, needle: &str) -> Result<LuaScalar, &'static str> {
    match opcode {
        14201 => number(usize::from(source.contains(needle))),
        14202 => number(usize::from(source.starts_with(needle))),
        14203 => number(usize::from(source.ends_with(needle))),
        _ => {
            if needle.is_empty() {
                return Err("count needle cannot be empty");
            }
            number(source.matches(needle).count())
        }
    }
}
fn split(source: &str, separator: &str) -> Result<LuaScalar, &'static str> {
    let values: Vec<String> = if separator.is_empty() {
        source.chars().take(1025).map(|c| c.to_string()).collect()
    } else {
        source
            .split(separator)
            .take(1025)
            .map(str::to_owned)
            .collect()
    };
    if values.len() > 1024 {
        return Err("split exceeds 1024 items");
    }
    crate::data_runtime::encode(&Value::Array(
        values.into_iter().map(Value::String).collect(),
    ))
}
fn join(source: &str, separator: &str) -> Result<LuaScalar, &'static str> {
    let Value::Array(values) = crate::data_runtime::parse(source)? else {
        return Err("join requires JSON string array");
    };
    let values = values
        .iter()
        .map(|v| v.as_str().ok_or("join elements must be strings"))
        .collect::<Result<Vec<_>, _>>()?;
    let size = values
        .iter()
        .map(|v| v.len())
        .sum::<usize>()
        .checked_add(
            separator
                .len()
                .checked_mul(values.len().saturating_sub(1))
                .ok_or("join overflow")?,
        )
        .ok_or("join overflow")?;
    if size > MAX {
        return Err("join exceeds 64 KiB");
    }
    output(values.join(separator))
}
fn marked(opcode: u32, source: &str, args: &[LuaScalar]) -> Result<LuaScalar, &'static str> {
    let needle = text(args, 1)?;
    if needle.is_empty() {
        return Err("marker cannot be empty");
    }
    let Some(at) = source.find(needle) else {
        return output(String::new());
    };
    if opcode == 14216 {
        return output(source[..at].into());
    }
    let tail = &source[at + needle.len()..];
    if opcode == 14217 {
        return output(tail.into());
    }
    let right = text(args, 2)?;
    if right.is_empty() {
        return Err("marker cannot be empty");
    }
    output(tail.find(right).map_or("", |at| &tail[..at]).into())
}
fn regex(opcode: u32, source: &str, pattern: &str) -> Result<LuaScalar, &'static str> {
    if source.len() > 4096 || pattern.is_empty() || pattern.len() > 128 {
        return Err("regex text/pattern exceeds budget");
    }
    let regex = regex::RegexBuilder::new(pattern)
        .size_limit(1_048_576)
        .dfa_size_limit(262_144)
        .nest_limit(32)
        .build()
        .map_err(|_| "invalid or oversized regex")?;
    if opcode == 14222 {
        return number(usize::from(regex.is_match(source)));
    }
    if opcode == 14223 {
        return output(regex.find(source).map_or("", |m| m.as_str()).into());
    }
    let matches = regex
        .find_iter(source)
        .take(65)
        .map(|m| Value::String(m.as_str().into()))
        .collect::<Vec<_>>();
    if matches.len() > 64 {
        return Err("regex results exceed 64");
    }
    crate::data_runtime::encode(&Value::Array(matches))
}
#[cfg(test)]
mod tests {
    use super::*;
    fn b(s: &str) -> LuaScalar {
        LuaScalar::Bytes(s.as_bytes().to_vec())
    }
    #[test]
    fn indexed_text_matches_unicode_reference_at_every_boundary() {
        for text in ["", "a", "中文😀", "a\u{301}🦀z", "\0\r\n", "abcd中文😀abcd"] {
            let size = text.chars().count();
            for at in 0..=size + 3 {
                let reference = if at == 0 {
                    Err("text index starts at 1")
                } else if at == size + 1 {
                    Ok(text.len())
                } else {
                    text.char_indices()
                        .nth(at - 1)
                        .map(|(i, _)| i)
                        .ok_or("text index out of range")
                };
                assert_eq!(start(text, at), reference, "{text:?} at {at}");
                for length in 0..=size + 3 {
                    assert_eq!(
                        slice(text, at, length),
                        text.chars().skip(at).take(length).collect::<String>()
                    );
                }
            }
        }
        assert_eq!(start("a", usize::MAX), Err("text index out of range"));
        assert_eq!(slice("中文😀", usize::MAX, usize::MAX), "");
        assert_eq!(slice("中文😀", 0, usize::MAX), "中文😀");
        assert_eq!(
            execute(
                14209,
                &[b("中文😀"), LuaScalar::Integer(4), LuaScalar::Integer(1)]
            )
            .unwrap(),
            b("")
        );
        assert_eq!(
            execute(14210, &[b("中文😀"), LuaScalar::Integer(4), b("x")]).unwrap(),
            b("中文😀x")
        );
        assert!(execute(
            14209,
            &[
                b(&"a".repeat(MAX + 1)),
                LuaScalar::Integer(1),
                LuaScalar::Integer(1)
            ]
        )
        .is_err());
        assert!(execute(
            14209,
            &[b("x"), LuaScalar::Integer(1), LuaScalar::Integer(65_537)]
        )
        .is_err());
    }
    #[test]
    fn unicode_indexes_join_split_and_regex_are_real() {
        assert_eq!(
            execute(14200, &[b("中文😀")]).unwrap(),
            LuaScalar::Integer(3)
        );
        assert_eq!(
            execute(
                14209,
                &[b("中文😀"), LuaScalar::Integer(2), LuaScalar::Integer(2)]
            )
            .unwrap(),
            b("文😀")
        );
        assert_eq!(
            execute(14210, &[b("中文"), LuaScalar::Integer(2), b("😀")]).unwrap(),
            b("中😀文")
        );
        assert_eq!(
            execute(
                14211,
                &[b("中文😀"), LuaScalar::Integer(2), LuaScalar::Integer(1)]
            )
            .unwrap(),
            b("中😀")
        );
        assert_eq!(
            execute(14205, &[b("甲,,乙,"), b(",")]).unwrap(),
            b("[\"甲\",\"\",\"乙\",\"\"]")
        );
        assert_eq!(
            execute(14206, &[b("[\"甲\",\"乙\"]"), b("/")]).unwrap(),
            b("甲/乙")
        );
        assert_eq!(
            execute(14215, &[b("a[中文]b"), b("["), b("]")]).unwrap(),
            b("中文")
        );
        assert_eq!(
            execute(14224, &[b("A12 B34"), b("[0-9]+")]).unwrap(),
            b("[\"12\",\"34\"]")
        );
        assert!(execute(14224, &[b(&"a ".repeat(65)), b("a")]).is_err());
        assert!(execute(14222, &[b("aaaa"), b("(a)\\1")]).is_err());
        assert!(execute(14214, &[b(&"x".repeat(65_536)), LuaScalar::Integer(2)]).is_err());
        assert!(execute(
            14209,
            &[b("中文"), LuaScalar::Integer(0), LuaScalar::Integer(1)]
        )
        .is_err());
        assert!(execute(14206, &[b("[1]"), b(",")]).is_err());
    }
}
