//! Scalar input bindings retain names; an explicit '=' prefix denotes a bounded expression.
use flow_ir::{ProjectManifest, ProjectVariableType};
use serde_json::Value;
use std::collections::BTreeSet;

pub(crate) fn valid(source: &str) -> bool {
    flow_ir::valid_user_name(source)
        || source
            .strip_prefix('=')
            .is_some_and(|s| crate::variable_expression::render(s).is_ok())
}

pub(crate) fn reference_key(key: &str) -> bool {
    key == "variable" || key == "sourceName" || key.ends_with("Variable")
}

pub(crate) fn allows(kind: &str, key: &str) -> bool {
    match (kind, key) {
        ("control.repeat", "timesVariable")
        | ("task.sleep", "millisecondsVariable")
        | ("control.while" | "control.cycle", "durationVariable")
        | ("control.if" | "control.while", "variable" | "valueVariable")
        | ("control.loopcheck", "limitVariable")
        | ("variable.copy", "sourceName")
        | (
            "vision.findimage",
            "leftVariable" | "topVariable" | "rightVariable" | "bottomVariable",
        )
        | ("screen.mappoint", "xVariable" | "yVariable")
        | (
            "task.log" | "task.prompt" | "task.runprompt" | "ui.set" | "ui.command",
            "valueVariable",
        )
        | ("clipboard.settext" | "input.pastetext" | "floating.show", "textVariable") => true,
        (kind, key) if crate::utility_operations::spec(kind).is_some() => {
            crate::utility_operations::spec(kind)
                .unwrap()
                .fields
                .iter()
                .any(|f| key.strip_suffix("Variable") == Some(f.name))
        }
        (
            "data.length" | "data.get" | "data.set" | "data.remove" | "data.append" | "data.keys"
            | "data.text" | "files.read" | "files.write" | "files.list" | "files.remove",
            key,
        ) => matches!(
            key,
            "jsonVariable" | "keyVariable" | "valueJsonVariable" | "pathVariable" | "textVariable"
        ),
        _ => false,
    }
}

pub(crate) fn valid_fields(kind: &str, args: &Value) -> bool {
    match args {
        Value::Object(object) => object.iter().all(|(key, value)| {
            (!reference_key(key)
                || !value.as_str().is_some_and(|s| s.starts_with('='))
                || allows(kind, key))
                && (!(value.is_object() || value.is_array()) || valid_fields(kind, value))
        }),
        Value::Array(values) => values.iter().all(|v| valid_fields(kind, v)),
        _ => true,
    }
}

pub(crate) fn validate_types(
    kind: &str,
    args: &Value,
    manifest: &ProjectManifest,
    flow: &str,
) -> Result<(), &'static str> {
    match args {
        Value::Object(object) => {
            for (key, value) in object {
                if reference_key(key) {
                    if let Some(source) = value.as_str().and_then(|s| s.strip_prefix('=')) {
                        let actual = infer(source, manifest, flow)?;
                        let expected = match (kind, key.as_str()) {
                            ("control.repeat", "timesVariable")
                            | ("task.sleep", "millisecondsVariable") => {
                                Some(ProjectVariableType::Integer)
                            }
                            (
                                "vision.findimage",
                                "leftVariable" | "topVariable" | "rightVariable" | "bottomVariable",
                            )
                            | ("screen.mappoint", "xVariable" | "yVariable") => {
                                Some(ProjectVariableType::Integer)
                            }
                            ("control.loopcheck", "limitVariable") if args["metric"] == "count" => {
                                Some(ProjectVariableType::Integer)
                            }
                            (
                                "clipboard.settext" | "input.pastetext" | "floating.show",
                                "textVariable",
                            ) => Some(ProjectVariableType::String),
                            _ => None,
                        };
                        if expected.is_some_and(|expected| expected != actual) {
                            return Err("输入表达式结果类型不匹配，请先转换");
                        }
                        if matches!(
                            (kind, key.as_str()),
                            ("control.cycle" | "control.while", "durationVariable")
                                | ("control.loopcheck", "limitVariable")
                        ) && actual == ProjectVariableType::String
                        {
                            return Err("循环数值输入需要数值结果，请先转换");
                        }
                    }
                }
                if value.is_object() || value.is_array() {
                    validate_types(kind, value, manifest, flow)?;
                }
            }
        }
        Value::Array(values) => {
            for value in values {
                validate_types(kind, value, manifest, flow)?;
            }
        }
        _ => {}
    }
    Ok(())
}

pub fn input_expressions(args: &Value) -> BTreeSet<String> {
    fn visit(value: &Value, result: &mut BTreeSet<String>) {
        match value {
            Value::Object(object) => {
                for (key, child) in object {
                    if reference_key(key) {
                        if let Some(source) = child.as_str().filter(|s| s.starts_with('=')) {
                            result.insert(source.to_owned());
                        }
                    }
                    if child.is_object() || child.is_array() {
                        visit(child, result);
                    }
                }
            }
            Value::Array(values) => {
                for child in values {
                    visit(child, result);
                }
            }
            _ => {}
        }
    }
    let mut result = BTreeSet::new();
    visit(args, &mut result);
    result
}

pub(crate) fn infer(
    source: &str,
    manifest: &ProjectManifest,
    flow: &str,
) -> Result<ProjectVariableType, &'static str> {
    crate::variable_expression::infer(source, |name| {
        crate::analyze::loop_declared_type(manifest, flow, name)
    })
}
