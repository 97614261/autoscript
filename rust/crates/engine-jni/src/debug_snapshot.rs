use engine_core::EngineState;
use lua_runtime::{LuaDebugSnapshot, LuaScalar};

pub(crate) const MAX_DEBUG_JSON_BYTES: usize = 192 * 1024;

pub(crate) fn encode(snapshot: LuaDebugSnapshot, state: EngineState) -> String {
    let runtime_state = match state {
        EngineState::Created | EngineState::Starting => crate::STATE_IDLE,
        EngineState::Running => crate::STATE_RUNNING,
        EngineState::Paused => crate::STATE_PAUSED,
        EngineState::Stopping => crate::STATE_STOPPING,
        EngineState::Stopped => crate::STATE_STOPPED,
        EngineState::Failed => crate::STATE_FAILED,
    };
    let mut rows = Vec::new();
    let mut truncated = snapshot.variables_truncated;
    // Keep space for bounded identifiers, metadata, separators and JSON escaping.
    let mut remaining = MAX_DEBUG_JSON_BYTES - 4096;
    for value in snapshot.variables {
        let (kind, text) = match value.value {
            Some(LuaScalar::Nil) => ("nil", String::new()),
            Some(LuaScalar::Boolean(value)) => ("boolean", value.to_string()),
            Some(LuaScalar::Integer(value)) => ("integer", value.to_string()),
            Some(LuaScalar::Number(value)) => ("number", value.to_string()),
            Some(LuaScalar::Bytes(value)) => {
                ("string", String::from_utf8_lossy(&value).into_owned())
            }
            None => (
                "unsupported",
                value.unsupported_type.unwrap_or("unknown").to_owned(),
            ),
        };
        let row = serde_json::json!({
            "scope":value.scope,"name":value.name,"type":kind,"value":text,
            "truncated":value.truncated,"declaredType":value.declared_type.unwrap_or_default(),
        });
        let bytes = row.to_string().len() + 1;
        if bytes > remaining {
            truncated = true;
            break;
        }
        remaining -= bytes;
        rows.push(row);
    }
    serde_json::json!({
        "flowId":snapshot.flow_id,"nodeId":snapshot.node_id,"variables":rows,
        "variablesTruncated":truncated,"runtimeState":runtime_state,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use lua_runtime::LuaDebugValue;

    #[test]
    fn escaped_values_obey_the_wire_limit_and_report_omitted_rows() {
        let snapshot = LuaDebugSnapshot {
            flow_id: "main".into(),
            node_id: "node".into(),
            variables_truncated: false,
            variables: (0..128)
                .map(|index| LuaDebugValue {
                    scope: "local".into(),
                    name: format!("value{index}"),
                    value: Some(LuaScalar::Bytes(vec![0; 1024])),
                    declared_type: Some("string".into()),
                    unsupported_type: None,
                    truncated: false,
                })
                .collect(),
        };
        let encoded = encode(snapshot, EngineState::Paused);
        assert!(encoded.len() <= MAX_DEBUG_JSON_BYTES);
        let decoded: serde_json::Value = serde_json::from_str(&encoded).unwrap();
        assert_eq!(decoded["runtimeState"], crate::STATE_PAUSED);
        assert_eq!(decoded["variablesTruncated"], true);
        assert!(decoded["variables"].as_array().unwrap().len() < 128);
        assert_eq!(
            decoded["variables"][0]["value"].as_str().unwrap().len(),
            1024
        );
    }
}
