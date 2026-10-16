use crate::{analyze::declared_variable_type, model::CompileError};
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::{Map, Value};
pub(crate) const KINDS: &[&str] = &[
    "input.pastetext",
    "floating.show",
    "floating.hide",
    "floating.close",
    "floating.bounds",
    "clipboard.gettext",
    "clipboard.settext",
    "app.info",
    "app.open",
    "input.record",
    "input.playrecording",
    "audio.play",
    "audio.stop",
    "audio.getvolume",
    "audio.setvolume",
];
#[derive(Debug, Clone)]
pub(crate) struct Operation {
    kind: String,
    args: Map<String, Value>,
}

pub(crate) fn parse(kind: &str, value: &Value) -> Result<Operation, &'static str> {
    let args = value
        .as_object()
        .ok_or("runtime tool options must be object")?;
    if kind.starts_with("floating.") {
        crate::floating_tools::parse(kind, args)?;
        return Ok(Operation {
            kind: kind.into(),
            args: args.clone(),
        });
    }
    let keys: &[&str] = match kind {
        "input.record" => &["durationMs", "eventBudget", "resultVariable"],
        "input.playrecording" => &["recordingVariable"],
        "audio.play" => &["path"],
        "audio.stop" => &[],
        "audio.getvolume" | "clipboard.gettext" => &["resultVariable"],
        "audio.setvolume" => &["volume"],
        "clipboard.settext" | "input.pastetext" => &["text"],
        "app.info" => &["packageName", "resultVariable"],
        "app.open" => &["packageName"],
        _ => return Err("unknown runtime tool"),
    };
    let optional = matches!(kind, "clipboard.settext" | "input.pastetext")
        && args.contains_key("textVariable");
    if args.len() != keys.len() + usize::from(optional)
        || keys.iter().any(|key| !args.contains_key(*key))
    {
        return Err("unexpected runtime tool options");
    }
    if optional
        && args["textVariable"]
            .as_str()
            .is_none_or(|v| v.is_empty() || v.len() > 64)
    {
        return Err("clipboard source variable invalid");
    }
    if matches!(kind, "clipboard.settext" | "input.pastetext")
        && args["text"].as_str().is_none_or(|v| v.len() > 4096)
    {
        return Err("clipboard text exceeds limit");
    }
    if kind.starts_with("app.") {
        let name = args["packageName"].as_str().ok_or("package required")?;
        if name.len() > 255
            || name.split('.').count() < 2
            || !name.split('.').all(|p| {
                p.as_bytes().first().is_some_and(u8::is_ascii_alphabetic)
                    && p.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
            })
        {
            return Err("package invalid");
        }
    }
    for key in keys {
        if key.ends_with("Variable")
            && args[*key]
                .as_str()
                .is_none_or(|v| v.is_empty() || v.len() > 128)
        {
            return Err("variable required");
        }
    }
    let integer = |key: &str, min: i64, max: i64| {
        args[key].as_i64().is_some_and(|v| (min..=max).contains(&v))
    };
    if kind == "input.record"
        && (!integer("durationMs", 1000, 60000) || !integer("eventBudget", 128, 512))
    {
        return Err("recording limits invalid");
    }
    if kind == "audio.setvolume" && !integer("volume", 0, 100) {
        return Err("volume invalid");
    }
    if kind == "audio.play" {
        let path = args["path"].as_str().ok_or("audio path required")?;
        if path.is_empty()
            || path.len() > 256
            || path.split('/').any(|p| {
                p.is_empty()
                    || p.starts_with('.')
                    || p.ends_with(['.', ' '])
                    || p.chars()
                        .any(|c| c.is_control() || "\\:*?\"<>|".contains(c))
            })
        {
            return Err("private audio path invalid");
        }
        if !["wav", "mp3", "ogg", "flac", "m4a"]
            .iter()
            .any(|ext| path.to_ascii_lowercase().ends_with(&format!(".{ext}")))
        {
            return Err("audio extension invalid");
        }
    }
    Ok(Operation {
        kind: kind.into(),
        args: args.clone(),
    })
}
impl Operation {
    pub(crate) fn validate(
        &self,
        manifest: &ProjectManifest,
        flow: &str,
    ) -> Result<(), &'static str> {
        if self.kind.starts_with("floating.") {
            return crate::floating_tools::validate(&self.args, manifest, flow);
        }
        if self.kind == "audio.play" {
            let path = self.args["path"].as_str().ok_or("audio path required")?;
            if path.starts_with("assets/audio/") {
                if !manifest
                    .resources
                    .iter()
                    .any(|r| r.kind == flow_ir::ProjectResourceKind::Audio && r.path == path)
                {
                    return Err("audio resource must be declared");
                }
            } else if !manifest.capabilities.iter().any(|c| c == "storage.data") {
                return Err("private audio requires storage.data");
            }
        }
        for (key, expected) in [
            (
                "resultVariable",
                if self.kind == "audio.getvolume" {
                    ProjectVariableType::Integer
                } else {
                    ProjectVariableType::String
                },
            ),
            ("recordingVariable", ProjectVariableType::String),
            ("textVariable", ProjectVariableType::String),
        ] {
            if let Some(name) = self.args.get(key).and_then(Value::as_str) {
                if declared_variable_type(manifest, flow, name) != Some(expected) {
                    return Err("declared output/input variable type mismatch");
                }
            }
        }
        Ok(())
    }
    pub(crate) fn render(
        &self,
        quote: impl Fn(&str) -> Result<String, CompileError>,
    ) -> Result<String, CompileError> {
        if self.kind.starts_with("floating.") {
            return crate::floating_tools::render(&self.kind, &self.args, quote);
        }
        let text = |key: &str| quote(self.args[key].as_str().unwrap_or_default());
        Ok(match self.kind.as_str() {
            "input.record" => format!(
                "__vars[{}]=Input.record({},{})",
                text("resultVariable")?,
                self.args["durationMs"],
                self.args["eventBudget"]
            ),
            "input.playrecording" => format!(
                "Input.playRecording(__vars[{}])",
                text("recordingVariable")?
            ),
            "audio.play" => format!("Audio.play({})", text("path")?),
            "audio.stop" => "Audio.stop()".into(),
            "audio.getvolume" => format!("__vars[{}]=Audio.getVolume()", text("resultVariable")?),
            "clipboard.gettext" => {
                format!("__vars[{}]=Clipboard.getText()", text("resultVariable")?)
            }
            "clipboard.settext" => format!(
                "Clipboard.setText({})",
                if self.args.contains_key("textVariable") {
                    format!("__vars[{}]", text("textVariable")?)
                } else {
                    text("text")?
                }
            ),
            "input.pastetext" => format!(
                "Input.pasteText({})",
                if self.args.contains_key("textVariable") {
                    format!("__vars[{}]", text("textVariable")?)
                } else {
                    text("text")?
                }
            ),
            "app.info" => format!(
                "__vars[{}]=Apps.info({})",
                text("resultVariable")?,
                text("packageName")?
            ),
            "app.open" => format!("Apps.open({})", text("packageName")?),
            _ => format!("Audio.setVolume({})", self.args["volume"]),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn all_runtime_tools_render_fixed_calls_and_reject_extra_options() {
        let cases = [
            (
                "input.record",
                json!({"durationMs":1000,"eventBudget":128,"resultVariable":"recording"}),
                "Input.record(1000,128)",
            ),
            (
                "input.playrecording",
                json!({"recordingVariable":"recording"}),
                "Input.playRecording(",
            ),
            (
                "audio.play",
                json!({"path":"audio/test.wav"}),
                "Audio.play(",
            ),
            ("audio.stop", json!({}), "Audio.stop()"),
            (
                "audio.getvolume",
                json!({"resultVariable":"volume"}),
                "Audio.getVolume()",
            ),
            (
                "audio.setvolume",
                json!({"volume":50}),
                "Audio.setVolume(50)",
            ),
        ];
        for (kind, mut args, expected) in cases {
            let operation = parse(kind, &args).unwrap();
            let rendered = operation
                .render(|v| Ok(serde_json::to_string(v).unwrap()))
                .unwrap();
            assert!(rendered.contains(expected), "{rendered}");
            args.as_object_mut()
                .unwrap()
                .insert("unexpected".into(), json!(true));
            assert!(parse(kind, &args).is_err());
        }
    }

    #[test]
    fn recording_volume_and_private_paths_have_strict_limits() {
        for duration in [999, 60001] {
            assert!(parse(
                "input.record",
                &json!({"durationMs":duration,"eventBudget":128,"resultVariable":"recording"})
            )
            .is_err());
        }
        for volume in [-1, 101] {
            assert!(parse("audio.setvolume", &json!({"volume":volume})).is_err());
        }
        for path in [
            "../test.wav",
            "/test.wav",
            "audio/test.exe",
            "audio\\test.wav",
        ] {
            assert!(parse("audio.play", &json!({"path":path})).is_err());
        }
    }
}
