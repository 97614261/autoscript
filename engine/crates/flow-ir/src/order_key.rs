use std::fmt;

pub const ORDER_KEY_ALPHABET: &[u8; 62] =
    b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
pub const ORDER_KEY_MAX_LENGTH: usize = 64;
const REBALANCE_MIN_WIDTH: usize = 4;
const REBALANCE_MIN_GAP: u128 = 16;
const ORDER_KEY_BASE: i16 = 62;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OrderKeyError {
    InvalidKey(String),
    InvalidBounds,
    NoSpace,
    TooLong,
    TooManyKeys,
}

impl fmt::Display for OrderKeyError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

/// Returns a deterministic byte-ordered key strictly between the optional bounds.
///
/// Bounds use ASCII byte ordering and may be absent. Existing adjacent prefix bounds such as
/// `a` and `a0` have no representable key and return [`OrderKeyError::NoSpace`]. Exhausting the
/// maximum key length returns [`OrderKeyError::TooLong`]. Both outcomes require the caller to
/// atomically rebalance only that block.
///
/// # Errors
///
/// Returns an error for invalid keys, reversed/equal bounds, an undivided prefix edge, or a result
/// longer than the protocol limit.
pub fn between_order_keys(
    previous: Option<&str>,
    next: Option<&str>,
) -> Result<String, OrderKeyError> {
    if let Some(key) = previous {
        validate_key(key)?;
    }
    if let Some(key) = next {
        validate_key(key)?;
    }
    if previous
        .zip(next)
        .is_some_and(|(left, right)| left >= right)
    {
        return Err(OrderKeyError::InvalidBounds);
    }

    let lower = previous.unwrap_or("").as_bytes();
    let upper = next.map(str::as_bytes);
    let mut lower_index = 0;
    let mut upper_index = 0;
    let mut upper_is_bounded = upper.is_some();
    let mut output = Vec::new();
    loop {
        let low = digit_at(lower, lower_index).map_or(-1, i16::from);
        let high = if upper_is_bounded {
            upper
                .and_then(|value| digit_at(value, upper_index))
                .map_or(-1, i16::from)
        } else {
            ORDER_KEY_BASE
        };

        if low == high && low >= 0 {
            output.push(alphabet_byte(low)?);
            lower_index += 1;
            upper_index += 1;
        } else if high - low > 1 {
            let middle =
                usize::try_from(low.midpoint(high)).map_err(|_| OrderKeyError::InvalidBounds)?;
            output.push(ORDER_KEY_ALPHABET[middle]);
            if middle == 0 {
                output.push(ORDER_KEY_ALPHABET[ORDER_KEY_ALPHABET.len() / 2]);
            }
            break;
        } else if low == -1 && high == 0 {
            output.push(ORDER_KEY_ALPHABET[0]);
            upper_index += 1;
        } else if low >= 0 {
            output.push(alphabet_byte(low)?);
            lower_index += 1;
            upper_is_bounded = false;
        } else {
            return Err(OrderKeyError::NoSpace);
        }
        if output.len() >= ORDER_KEY_MAX_LENGTH {
            return Err(OrderKeyError::TooLong);
        }
    }

    if output.len() > ORDER_KEY_MAX_LENGTH {
        return Err(OrderKeyError::TooLong);
    }
    let result = String::from_utf8(output).map_err(|_| OrderKeyError::InvalidBounds)?;
    if previous.is_some_and(|bound| bound >= result.as_str())
        || next.is_some_and(|bound| result.as_str() >= bound)
    {
        return Err(OrderKeyError::NoSpace);
    }
    Ok(result)
}

fn alphabet_byte(index: i16) -> Result<u8, OrderKeyError> {
    usize::try_from(index)
        .ok()
        .and_then(|index| ORDER_KEY_ALPHABET.get(index).copied())
        .ok_or(OrderKeyError::InvalidBounds)
}

/// Generates evenly spaced, fixed-width keys for an atomic single-block rebalance.
///
/// # Errors
///
/// Returns [`OrderKeyError::TooManyKeys`] when the requested cardinality cannot fit within the
/// 64-byte protocol limit with the required gap.
pub fn rebalance_order_keys(count: usize) -> Result<Vec<String>, OrderKeyError> {
    if count == 0 {
        return Ok(Vec::new());
    }
    let slots = u128::try_from(count)
        .ok()
        .and_then(|value| value.checked_add(1))
        .ok_or(OrderKeyError::TooManyKeys)?;
    let mut width = REBALANCE_MIN_WIDTH;
    let mut capacity = alphabet_capacity(width).ok_or(OrderKeyError::TooManyKeys)?;
    while capacity / slots < REBALANCE_MIN_GAP {
        width += 1;
        if width > ORDER_KEY_MAX_LENGTH {
            return Err(OrderKeyError::TooManyKeys);
        }
        capacity = alphabet_capacity(width).ok_or(OrderKeyError::TooManyKeys)?;
    }
    let step = capacity / slots;
    (1..=count)
        .map(|index| {
            let value = step
                .checked_mul(u128::try_from(index).map_err(|_| OrderKeyError::TooManyKeys)?)
                .ok_or(OrderKeyError::TooManyKeys)?;
            encode_fixed(value, width)
        })
        .collect()
}

fn validate_key(key: &str) -> Result<(), OrderKeyError> {
    if key.is_empty()
        || key.len() > ORDER_KEY_MAX_LENGTH
        || key.bytes().any(|byte| digit(byte).is_none())
    {
        Err(OrderKeyError::InvalidKey(key.to_owned()))
    } else {
        Ok(())
    }
}

fn digit_at(key: &[u8], index: usize) -> Option<u8> {
    key.get(index).and_then(|&byte| digit(byte))
}

fn digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'A'..=b'Z' => Some(byte - b'A' + 10),
        b'a'..=b'z' => Some(byte - b'a' + 36),
        _ => None,
    }
}

fn alphabet_capacity(width: usize) -> Option<u128> {
    let base = u128::try_from(ORDER_KEY_ALPHABET.len()).ok()?;
    (0..width).try_fold(1_u128, |capacity, _| capacity.checked_mul(base))
}

fn encode_fixed(mut value: u128, width: usize) -> Result<String, OrderKeyError> {
    let base = u128::try_from(ORDER_KEY_ALPHABET.len()).expect("alphabet length fits u128");
    let mut output = vec![ORDER_KEY_ALPHABET[0]; width];
    for byte in output.iter_mut().rev() {
        let digit = usize::try_from(value % base).map_err(|_| OrderKeyError::TooManyKeys)?;
        *byte = ORDER_KEY_ALPHABET[digit];
        value /= base;
    }
    if value != 0 {
        return Err(OrderKeyError::TooManyKeys);
    }
    String::from_utf8(output).map_err(|_| OrderKeyError::TooManyKeys)
}

#[cfg(test)]
mod tests {
    use super::{
        between_order_keys, rebalance_order_keys, OrderKeyError, ORDER_KEY_ALPHABET,
        ORDER_KEY_MAX_LENGTH, REBALANCE_MIN_GAP, REBALANCE_MIN_WIDTH,
    };
    use serde::Deserialize;

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Protocol {
        schema_version: u32,
        alphabet: String,
        comparison: String,
        max_length: usize,
        generation: String,
        rebalance: Rebalance,
        vectors: Vec<Vector>,
        no_space_vectors: Vec<Vector>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase", deny_unknown_fields)]
    struct Rebalance {
        scope: String,
        minimum_width: usize,
        minimum_gap: u128,
    }

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Vector {
        previous: Option<String>,
        next: Option<String>,
        #[serde(default)]
        result: Option<String>,
    }

    fn protocol() -> Protocol {
        serde_json::from_str(include_str!(
            "../../../../schema/flow-envelope-schema/order-key-v1.json"
        ))
        .expect("valid shared orderKey protocol")
    }

    #[test]
    fn fixed_between_vectors_are_stable() {
        let protocol = protocol();
        assert_eq!(protocol.schema_version, 1);
        assert_eq!(protocol.alphabet.as_bytes(), ORDER_KEY_ALPHABET);
        assert_eq!(protocol.comparison, "unsigned_ascii_byte_lexicographic");
        assert_eq!(
            protocol.generation,
            "deterministic_midpoint_with_prefix_rebalance"
        );
        assert_eq!(protocol.max_length, ORDER_KEY_MAX_LENGTH);
        assert_eq!(protocol.rebalance.scope, "single_block_atomic");
        assert_eq!(protocol.rebalance.minimum_width, REBALANCE_MIN_WIDTH);
        assert_eq!(protocol.rebalance.minimum_gap, REBALANCE_MIN_GAP);
        for vector in protocol.vectors {
            assert_eq!(
                between_order_keys(vector.previous.as_deref(), vector.next.as_deref()).as_deref(),
                Ok(vector.result.as_deref().expect("success vector result"))
            );
        }
    }

    #[test]
    fn adjacent_prefix_requires_rebalance() {
        for vector in protocol().no_space_vectors {
            assert_eq!(
                between_order_keys(vector.previous.as_deref(), vector.next.as_deref()),
                Err(OrderKeyError::NoSpace)
            );
        }
    }

    #[test]
    fn rebalance_is_sorted_fixed_width_and_repeatable() {
        let first = rebalance_order_keys(100).expect("key space");
        let second = rebalance_order_keys(100).expect("same key space");
        assert_eq!(first, second);
        assert!(first.windows(2).all(|pair| pair[0] < pair[1]));
        assert!(first.iter().all(|key| key.len() == 4));
    }
}
