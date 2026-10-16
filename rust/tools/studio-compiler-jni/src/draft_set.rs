//! Versioned, bounded Studio draft overlay transport; never executes source.
pub(crate) const MAX_SET_BYTES: usize = 20 * 1024 * 1024;
const MAX_SOURCE_BYTES: usize = 1024 * 1024;

pub(crate) fn decode(bytes: &[u8]) -> Result<Vec<(&str, &[u8])>, &'static str> {
    if bytes.len() > MAX_SET_BYTES {
        return Err("RESOURCE_LIMIT");
    }
    let mut input = bytes;
    if take(&mut input, 4)? != b"ZGDS" || integer(&mut input)? != 1 {
        return Err("DRAFT_SET_VERSION");
    }
    let count = integer(&mut input)?;
    if !(1..=16).contains(&count) {
        return Err("RESOURCE_LIMIT");
    }
    let mut drafts = Vec::new();
    for _ in 0..count {
        let length = usize::try_from(integer(&mut input)?).map_err(|_| "RESOURCE_LIMIT")?;
        if !(1..=64).contains(&length) {
            return Err("INVALID_FLOW_ID");
        }
        let id = std::str::from_utf8(take(&mut input, length)?).map_err(|_| "INVALID_FLOW_ID")?;
        if !id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-' | b'.'))
        {
            return Err("INVALID_FLOW_ID");
        }
        if drafts.iter().any(|(seen, _)| *seen == id) {
            return Err("DUPLICATE_FLOW_SOURCE");
        }
        let length = usize::try_from(integer(&mut input)?).map_err(|_| "RESOURCE_LIMIT")?;
        if length > MAX_SOURCE_BYTES {
            return Err("RESOURCE_LIMIT");
        }
        let source = take(&mut input, length)?;
        std::str::from_utf8(source).map_err(|_| "DRAFT_SOURCE_UTF8")?;
        drafts.push((id, source));
    }
    if !input.is_empty() {
        return Err("DRAFT_SET_TRAILING_DATA");
    }
    Ok(drafts)
}

fn take<'a>(input: &mut &'a [u8], length: usize) -> Result<&'a [u8], &'static str> {
    let (value, remaining) = input
        .split_at_checked(length)
        .ok_or("DRAFT_SET_TRUNCATED")?;
    *input = remaining;
    Ok(value)
}

fn integer(input: &mut &[u8]) -> Result<u32, &'static str> {
    let value: [u8; 4] = take(input, 4)?
        .try_into()
        .map_err(|_| "DRAFT_SET_TRUNCATED")?;
    Ok(u32::from_be_bytes(value))
}

#[cfg(test)]
mod tests {
    use super::decode;
    fn encode(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut bytes = b"ZGDS".to_vec();
        bytes.extend(1_u32.to_be_bytes());
        bytes.extend(u32::try_from(entries.len()).unwrap().to_be_bytes());
        for (id, source) in entries {
            bytes.extend(u32::try_from(id.len()).unwrap().to_be_bytes());
            bytes.extend(id.as_bytes());
            bytes.extend(u32::try_from(source.len()).unwrap().to_be_bytes());
            bytes.extend(*source);
        }
        bytes
    }
    #[test]
    fn bounded_identity_utf8_and_exact_end() {
        let bytes = encode(&[("main", b"{}"), ("other", b"[]")]);
        assert_eq!(
            decode(&bytes).unwrap(),
            vec![("main", &b"{}"[..]), ("other", &b"[]"[..])]
        );
        for end in 0..bytes.len() {
            assert!(decode(&bytes[..end]).is_err());
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(decode(&trailing).is_err());
        assert!(decode(&encode(&[("main", b"{}"), ("main", b"{}")])).is_err());
        assert!(decode(&encode(&[("../escape", b"{}")])).is_err());
        assert!(decode(&encode(&[("main", &[255])])).is_err());
        assert!(decode(&encode(&[])).is_err());
    }
}
