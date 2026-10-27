//! Strict UTF-8 codecs and pure private-relative paths. No file or network access.
use lua_runtime::LuaScalar;
const MAX: usize = 65_536;
type Output = Result<LuaScalar, &'static str>;
fn text(value: &LuaScalar, max: usize) -> Result<&str, &'static str> {
    match value {
        LuaScalar::Bytes(bytes) if bytes.len() <= max => {
            std::str::from_utf8(bytes).map_err(|_| "text must be UTF-8")
        }
        _ => Err("expected bounded UTF-8 text"),
    }
}
fn result(bytes: Vec<u8>) -> Output {
    if bytes.len() > MAX || std::str::from_utf8(&bytes).is_err() {
        return Err("result exceeds budget or is not UTF-8");
    }
    Ok(LuaScalar::Bytes(bytes))
}
fn occurrence(value: &LuaScalar) -> Result<usize, &'static str> {
    match value {
        LuaScalar::Integer(n) if (1..=1024).contains(n) => {
            usize::try_from(*n).map_err(|_| "invalid occurrence")
        }
        _ => Err("occurrence must be integer 1..1024"),
    }
}
fn nibble(byte: u8) -> Result<u8, &'static str> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        _ => Err("invalid HEX digit"),
    }
}
pub(crate) fn execute(op: u32, args: &[LuaScalar]) -> Output {
    match (op, args) {
        (14229 | 14230, [value, mode]) => {
            let mode = text(mode, 16)?;
            let form = match mode {
                "component" => false,
                "form" => true,
                _ => return Err("unknown URL codec mode"),
            };
            let source = text(value, MAX)?;
            if op == 14229 {
                url_encode(source, form)
            } else {
                url_decode(source, form)
            }
        }
        (14231, [value]) => {
            let source = text(value, MAX / 2)?;
            let mut out = Vec::with_capacity(source.len() * 2);
            for byte in source.bytes() {
                out.push(b"0123456789ABCDEF"[usize::from(byte >> 4)]);
                out.push(b"0123456789ABCDEF"[usize::from(byte & 15)]);
            }
            result(out)
        }
        (14232, [value]) => {
            let source = text(value, MAX)?.as_bytes();
            if source.len() % 2 != 0 {
                return Err("HEX requires pairs");
            }
            let mut out = Vec::with_capacity(source.len() / 2);
            for pair in source.chunks_exact(2) {
                out.push((nibble(pair[0])? << 4) | nibble(pair[1])?);
            }
            result(out)
        }
        (14233, [value]) => base64_encode(text(value, MAX)?.as_bytes()),
        (14234, [value]) => base64_decode(text(value, MAX)?.as_bytes()),
        (14235, [value, needle, n]) => {
            let source = text(value, MAX)?;
            let needle = text(needle, 4096)?;
            if needle.is_empty() {
                return Err("needle cannot be empty");
            }
            let found = source
                .match_indices(needle)
                .nth(occurrence(n)? - 1)
                .map_or(0, |(at, _)| source[..at].chars().count() + 1);
            Ok(LuaScalar::Integer(
                i64::try_from(found).map_err(|_| "index overflow")?,
            ))
        }
        (14236, [value, left, right, n]) => {
            let source = text(value, MAX)?;
            let left = text(left, 4096)?;
            let right = text(right, 4096)?;
            if left.is_empty() || right.is_empty() {
                return Err("markers cannot be empty");
            }
            let value = source
                .match_indices(left)
                .nth(occurrence(n)? - 1)
                .and_then(|(at, _)| {
                    let tail = &source[at + left.len()..];
                    tail.find(right).map(|end| &tail[..end])
                })
                .unwrap_or("");
            result(value.as_bytes().to_vec())
        }
        (14400..=14402, [value]) => {
            let path = normalize(text(value, 256)?)?;
            let selected = match op {
                14400 => path.rsplit('/').next().unwrap_or(""),
                14401 => path.rsplit_once('/').map_or("", |(parent, _)| parent),
                _ => &path,
            };
            result(selected.as_bytes().to_vec())
        }
        (14403, [left, right]) => {
            let left = normalize(text(left, 256)?)?;
            let right = normalize(text(right, 256)?)?;
            let joined = if left.is_empty() {
                right
            } else if right.is_empty() {
                left
            } else {
                format!("{left}/{right}")
            };
            result(normalize(&joined)?.into_bytes())
        }
        _ => Err("unknown codec operation or argument count"),
    }
}
fn url_encode(source: &str, form: bool) -> Output {
    let length = source
        .bytes()
        .try_fold(0usize, |size, byte| {
            size.checked_add(
                if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) || (form && byte == b' ')
                {
                    1
                } else {
                    3
                },
            )
            .filter(|size| *size <= MAX)
        })
        .ok_or("encoded URL exceeds 64 KiB")?;
    let mut out = Vec::with_capacity(length);
    for byte in source.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            out.push(byte);
        } else if form && byte == b' ' {
            out.push(b'+');
        } else {
            out.extend([
                b'%',
                b"0123456789ABCDEF"[usize::from(byte >> 4)],
                b"0123456789ABCDEF"[usize::from(byte & 15)],
            ]);
        }
    }
    result(out)
}
fn url_decode(source: &str, form: bool) -> Output {
    let source = source.as_bytes();
    let mut out = Vec::with_capacity(source.len());
    let mut at = 0;
    while at < source.len() {
        match source[at] {
            b'%' => {
                let pair = source.get(at + 1..at + 3).ok_or("incomplete URL escape")?;
                out.push((nibble(pair[0])? << 4) | nibble(pair[1])?);
                at += 3;
            }
            b'+' if form => {
                out.push(b' ');
                at += 1;
            }
            byte => {
                out.push(byte);
                at += 1;
            }
        }
    }
    result(out)
}
const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
fn base64_encode(source: &[u8]) -> Output {
    let size = source.len().div_ceil(3) * 4;
    if size > MAX {
        return Err("Base64 output exceeds 64 KiB");
    }
    let mut out = Vec::with_capacity(size);
    for chunk in source.chunks(3) {
        let a = chunk[0];
        let b = chunk.get(1).copied().unwrap_or(0);
        let c = chunk.get(2).copied().unwrap_or(0);
        out.extend([
            B64[usize::from(a >> 2)],
            B64[usize::from(((a & 3) << 4) | (b >> 4))],
            if chunk.len() > 1 {
                B64[usize::from(((b & 15) << 2) | (c >> 6))]
            } else {
                b'='
            },
            if chunk.len() > 2 {
                B64[usize::from(c & 63)]
            } else {
                b'='
            },
        ]);
    }
    result(out)
}
fn digit(byte: u8) -> Result<u8, &'static str> {
    match byte {
        b'A'..=b'Z' => Ok(byte - b'A'),
        b'a'..=b'z' => Ok(byte - b'a' + 26),
        b'0'..=b'9' => Ok(byte - b'0' + 52),
        b'+' => Ok(62),
        b'/' => Ok(63),
        _ => Err("invalid Base64 digit"),
    }
}
fn base64_decode(source: &[u8]) -> Output {
    if !source.len().is_multiple_of(4) {
        return Err("Base64 padding required");
    }
    let mut out = Vec::with_capacity(source.len() / 4 * 3);
    for (index, chunk) in source.chunks_exact(4).enumerate() {
        let a = digit(chunk[0])?;
        let b = digit(chunk[1])?;
        let final_chunk = (index + 1) * 4 == source.len();
        let c = if chunk[2] == b'=' {
            0
        } else {
            digit(chunk[2])?
        };
        let d = if chunk[3] == b'=' {
            0
        } else {
            digit(chunk[3])?
        };
        if (chunk[2] == b'=' && (chunk[3] != b'=' || b & 15 != 0))
            || (chunk[3] == b'=' && c & 3 != 0)
            || (!final_chunk && (chunk[2] == b'=' || chunk[3] == b'='))
        {
            return Err("noncanonical Base64 padding");
        }
        out.push((a << 2) | (b >> 4));
        if chunk[2] != b'=' {
            out.push((b << 4) | (c >> 2));
        }
        if chunk[3] != b'=' {
            out.push((c << 6) | d);
        }
    }
    result(out)
}
fn normalize(path: &str) -> Result<String, &'static str> {
    if path.len() > 256 || path.starts_with('/') {
        return Err("path must be private relative data path");
    }
    let mut parts = Vec::new();
    for part in path.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part.starts_with('.')
            || part.ends_with(['.', ' '])
            || part
                .chars()
                .any(|ch| ch.is_control() || "\\:*?\"<>|".contains(ch))
        {
            return Err("invalid private path segment");
        }
        parts.push(part);
        if parts.len() > 16 {
            return Err("path exceeds 16 segments");
        }
    }
    Ok(parts.join("/"))
}
