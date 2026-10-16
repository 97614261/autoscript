use serde_json::Value;
pub(crate) const KINDS: &[&str] = &[
    "task.stop",
    "task.restart",
    "task.logenabled",
    "task.promptenabled",
];
#[derive(Debug, Clone)]
pub(crate) enum Operation {
    Stop,
    Restart,
    Log(bool),
    Prompt(bool),
}
pub(crate) fn parse(kind: &str, args: &Value) -> Result<Operation, &'static str> {
    let map = args
        .as_object()
        .ok_or("script control arguments must be object")?;
    if kind == "task.stop" || kind == "task.restart" {
        return if map.is_empty() {
            Ok(if kind == "task.restart" {
                Operation::Restart
            } else {
                Operation::Stop
            })
        } else {
            Err("stop takes no arguments")
        };
    }
    if map.len() != 1 {
        return Err("script switch requires only enabled");
    }
    let enabled = map
        .get("enabled")
        .and_then(Value::as_bool)
        .ok_or("enabled must be boolean")?;
    match kind {
        "task.logenabled" => Ok(Operation::Log(enabled)),
        "task.promptenabled" => Ok(Operation::Prompt(enabled)),
        _ => Err("unknown script control"),
    }
}
impl Operation {
    pub(crate) fn render(&self) -> String {
        match self {
            Self::Stop => "Task.stop()".into(),
            Self::Restart => "Task.restart()".into(),
            Self::Log(enabled) => format!("Log.setEnabled({enabled})"),
            Self::Prompt(enabled) => format!("Prompt.setEnabled({enabled})"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn restart_is_a_fixed_no_argument_operation() {
        assert_eq!(
            parse("task.restart", &serde_json::json!({}))
                .unwrap()
                .render(),
            "Task.restart()"
        );
        assert!(parse("task.restart", &serde_json::json!({"source":"other.lua"})).is_err());
        assert!(parse("task.restart", &serde_json::json!([])).is_err());
    }
}
