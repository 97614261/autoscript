//! Inert, bounded match-result data. No frame pointers, shell, expressions or native objects.
use lua_runtime::LuaScalar;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct MatchItem {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
    pub score_permille: u16,
    pub path: String,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct MatchResults {
    pub version: u8,
    pub count: usize,
    pub truncated: bool,
    pub matches: Vec<MatchItem>,
}
impl MatchResults {
    pub(crate) fn decode(bytes: &[u8]) -> Result<Self, String> {
        if bytes.len() > 65_536 {
            return Err("match result exceeds budget".into());
        }
        let result: Self =
            serde_json::from_slice(bytes).map_err(|_| "invalid match result JSON")?;
        result.validate()?;
        Ok(result)
    }
    fn validate(&self) -> Result<(), String> {
        if self.version != 1
            || self.count != self.matches.len()
            || self.count > 64
            || (self.truncated && self.count == 0)
            || self.matches.iter().any(|m| {
                m.width == 0
                    || m.height == 0
                    || m.score_permille > 1000
                    || m.path.is_empty()
                    || m.path.len() > 256
                    || m.path.chars().any(char::is_control)
            })
        {
            return Err("invalid match result shape".into());
        }
        Ok(())
    }
    pub fn encode(&self) -> Result<Vec<u8>, String> {
        self.validate()?;
        let bytes = serde_json::to_vec(self).map_err(|_| "match result encoding failed")?;
        if bytes.len() > 65_536 {
            return Err("match results exceed byte budget".into());
        }
        Ok(bytes)
    }
    pub(crate) fn field_value(&self, index: i64, field: &str) -> Result<serde_json::Value, String> {
        if matches!(field, "count" | "truncated") {
            if index != 0 {
                return Err("aggregate property requires index 0".into());
            }
            return Ok(if field == "count" {
                serde_json::json!(self.count)
            } else {
                serde_json::json!(u8::from(self.truncated))
            });
        }
        let i = usize::try_from(index)
            .ok()
            .filter(|i| *i > 0 && *i <= self.count)
            .ok_or("match index out of range")?;
        let item = &self.matches[i - 1];
        Ok(match field {
            "x" => serde_json::json!(item.x),
            "y" => serde_json::json!(item.y),
            "width" => serde_json::json!(item.width),
            "height" => serde_json::json!(item.height),
            "scorePermille" => serde_json::json!(item.score_permille),
            "path" => serde_json::json!(item.path),
            _ => return Err("unknown match property".into()),
        })
    }
}
pub(crate) fn value(args: &[LuaScalar]) -> Result<LuaScalar, String> {
    let [LuaScalar::Bytes(bytes), LuaScalar::Integer(index), LuaScalar::Bytes(field)] = args else {
        return Err("matchValue expects result, index and field".into());
    };
    if bytes.len() > 65_536 || field.len() > 32 {
        return Err("match result exceeds budget".into());
    }
    let result = MatchResults::decode(bytes)?;
    let field = std::str::from_utf8(field).map_err(|_| "invalid result field")?;
    let value = result.field_value(*index, field)?;
    Ok(LuaScalar::Bytes(
        serde_json::to_vec(&value).map_err(|_| "match value encoding failed")?,
    ))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_versions_shapes_and_fields_are_rejected() {
        for bytes in [
            br#"{"version":2,"count":0,"truncated":false,"matches":[]}"#.as_slice(),
            br#"{"version":1,"count":1,"truncated":false,"matches":[]}"#,
            br#"{"version":1,"count":0,"truncated":true,"matches":[]}"#,
            br#"{"version":1,"count":0,"truncated":false,"matches":[],"execute":"anything"}"#,
        ] {
            assert!(value(&[
                LuaScalar::Bytes(bytes.to_vec()),
                LuaScalar::Integer(0),
                LuaScalar::Bytes(b"count".to_vec())
            ])
            .is_err());
        }
        let empty = MatchResults {
            version: 1,
            count: 0,
            truncated: false,
            matches: vec![],
        }
        .encode()
        .unwrap();
        assert_eq!(
            value(&[
                LuaScalar::Bytes(empty),
                LuaScalar::Integer(0),
                LuaScalar::Bytes(b"count".to_vec())
            ])
            .unwrap(),
            LuaScalar::Bytes(b"0".to_vec())
        );
    }
    #[test]
    fn properties_and_bounds_are_data_only() {
        let r = MatchResults {
            version: 1,
            count: 1,
            truncated: true,
            matches: vec![MatchItem {
                x: 2,
                y: 3,
                width: 4,
                height: 5,
                score_permille: 900,
                path: "assets/images/a.png".into(),
            }],
        }
        .encode()
        .unwrap();
        let call = |index, field: &str| {
            value(&[
                LuaScalar::Bytes(r.clone()),
                LuaScalar::Integer(index),
                LuaScalar::Bytes(field.as_bytes().to_vec()),
            ])
        };
        assert_eq!(call(0, "count").unwrap(), LuaScalar::Bytes(b"1".to_vec()));
        assert_eq!(
            call(0, "truncated").unwrap(),
            LuaScalar::Bytes(b"1".to_vec())
        );
        assert_eq!(call(1, "x").unwrap(), LuaScalar::Bytes(b"2".to_vec()));
        assert!(call(0, "x").is_err());
        assert!(call(2, "x").is_err());
        assert!(call(1, "execute").is_err());
        assert_eq!(
            call(1, "path").unwrap(),
            LuaScalar::Bytes(b"\"assets/images/a.png\"".to_vec())
        );
        assert!(value(&[
            LuaScalar::Bytes(vec![b'x'; 65_537]),
            LuaScalar::Integer(0),
            LuaScalar::Bytes(b"count".to_vec())
        ])
        .is_err());
    }
}
