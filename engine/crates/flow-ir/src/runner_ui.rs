use crate::project::{RunnerUi, RunnerUiFieldKind};
use crate::{ProjectManifest, ProjectVariableScope, ProjectVariableType, ValueType};
use serde_json::Value;
use std::collections::{HashMap, HashSet};

pub fn validate_references(manifest: &ProjectManifest) -> Result<(), String> {
    let Some(ui) = manifest.runner_ui.as_ref().filter(|u| u.version == Some(2)) else {
        return Ok(());
    };
    if !manifest.capabilities.iter().any(|c| c == "ui.control") {
        return Err("界面需要 project.json 声明 ui.control 能力".into());
    }
    let version = manifest.runtime_api.split('.').collect::<Vec<_>>();
    if version.len() != 2 || version[0] != "1" || version[1].parse::<u32>().map_or(true, |v| v < 7)
    {
        return Err("v2 界面需要 Runtime API 1.7 或兼容更新版本".into());
    }
    let uses_events = ui.fields.iter().filter_map(|f| f.ui.as_ref()).any(|p| {
        p.get("binding")
            .and_then(Value::as_str)
            .is_some_and(|b| !b.starts_with("param:"))
            || p.get("events")
                .and_then(Value::as_object)
                .is_some_and(|events| {
                    events
                        .values()
                        .any(|a| a.as_str().is_some_and(|a| a.starts_with("flow:")))
                })
    });
    if uses_events && !manifest.capabilities.iter().any(|c| c == "core.task") {
        return Err("界面插件事件需要 core.task 能力".into());
    }
    let mut bound = HashSet::new();
    for field in &ui.fields {
        let p = field.ui.as_ref().ok_or("missing UI presentation")?;
        if text(p, "control")? == "IMAGE" {
            let path = field
                .initial_value
                .as_str()
                .ok_or("image path must be text")?;
            if !path.is_empty()
                && !manifest
                    .resources
                    .iter()
                    .any(|r| r.path == path && r.kind == crate::ProjectResourceKind::Image)
            {
                return Err("界面图片必须是项目已声明的图片资源".into());
            }
        }
        if let Some(binding) = p.get("binding").and_then(Value::as_str) {
            if !bound.insert(binding) {
                return Err("不能将多个控件绑定同一变量".into());
            }
            let parts = binding.split(':').collect::<Vec<_>>();
            if parts[0] == "param" {
                let entry = manifest
                    .flows
                    .iter()
                    .find(|f| Some(&f.flow_id) == manifest.entry_flow_id.as_ref())
                    .ok_or("绑定参数需要入口插件")?;
                let param = entry
                    .params
                    .iter()
                    .find(|p| p.name == parts[1])
                    .ok_or("绑定参数不存在")?;
                if !ui_type_matches(field.kind, param.value_type) {
                    return Err("绑定参数类型不匹配".into());
                }
            } else {
                let variable = manifest
                    .variables
                    .iter()
                    .find(|v| {
                        v.name == *parts.last().unwrap_or(&"")
                            && if parts[0] == "global" {
                                v.scope == ProjectVariableScope::Global
                            } else {
                                v.scope == ProjectVariableScope::Flow
                                    && v.flow_id.as_deref() == Some(parts[1])
                            }
                    })
                    .ok_or("绑定变量不存在")?;
                let compatible = match field.kind {
                    RunnerUiFieldKind::Text | RunnerUiFieldKind::Choice => {
                        variable.value_type == ProjectVariableType::String
                    }
                    RunnerUiFieldKind::Integer | RunnerUiFieldKind::Boolean => matches!(
                        variable.value_type,
                        ProjectVariableType::Integer | ProjectVariableType::Number
                    ),
                };
                if !compatible {
                    return Err("绑定变量类型不匹配".into());
                }
            }
        }
        for action in p
            .get("events")
            .and_then(Value::as_object)
            .ok_or("missing UI events")?
            .values()
        {
            if let Some(target) = action.as_str().and_then(|a| a.strip_prefix("flow:")) {
                let flow = manifest
                    .flows
                    .iter()
                    .find(|f| f.flow_id == target)
                    .ok_or("事件插件不存在")?;
                for parameter in &flow.params {
                    let compatible = match parameter.name.as_str() {
                        "controlId" | "event" => parameter.value_type == ValueType::String,
                        "value" => ui_type_matches(field.kind, parameter.value_type),
                        _ => !parameter.required,
                    };
                    if !compatible {
                        return Err("事件插件参数需为 controlId/event 字符串、value 对应控件类型；其他参数不能必填".into());
                    }
                }
            }
        }
    }
    Ok(())
}
fn ui_type_matches(kind: RunnerUiFieldKind, target: ValueType) -> bool {
    match kind {
        RunnerUiFieldKind::Text | RunnerUiFieldKind::Choice => target == ValueType::String,
        RunnerUiFieldKind::Integer => matches!(target, ValueType::Integer | ValueType::Number),
        RunnerUiFieldKind::Boolean => target == ValueType::Boolean,
    }
}

fn id(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s.bytes()
            .enumerate()
            .all(|(i, b)| b.is_ascii_alphabetic() || b == b'_' || i > 0 && b.is_ascii_digit())
}
fn color(s: &str) -> bool {
    s.len() == 9 && s.starts_with('#') && s[1..].bytes().all(|b| b.is_ascii_hexdigit())
}
fn text<'a>(v: &'a Value, k: &str) -> Result<&'a str, String> {
    v.get(k)
        .and_then(Value::as_str)
        .ok_or_else(|| format!("missing UI {k}"))
}
fn range(v: &Value, k: &str, lo: i64, hi: i64) -> Result<(), String> {
    if v.get(k)
        .and_then(Value::as_i64)
        .is_some_and(|n| (lo..=hi).contains(&n))
    {
        Ok(())
    } else {
        Err(format!("invalid UI {k}"))
    }
}
#[allow(clippy::too_many_lines)] // One explicit whitelist for every presentation property.
pub fn validate_layout(ui: &RunnerUi) -> Result<(), String> {
    if ui.version.is_none() {
        return if ui.pages.is_empty() && ui.fields.iter().all(|f| f.ui.is_none()) {
            Ok(())
        } else {
            Err("UI extensions require version 2".into())
        };
    }
    if ui.version != Some(2) || !(1..=16).contains(&ui.pages.len()) {
        return Err("invalid UI version/pages".into());
    }
    let mut pages = HashSet::new();
    for p in &ui.pages {
        let keys = p.as_object().ok_or("page must be object")?;
        if keys.len() != 5
            || keys.keys().any(|k| {
                !matches!(
                    k.as_str(),
                    "id" | "title" | "width" | "height" | "background"
                )
            })
        {
            return Err("invalid page keys".into());
        }
        let name = text(p, "id")?;
        if !id(name)
            || !pages.insert(name)
            || !(1..=64).contains(&text(p, "title")?.chars().count())
            || !color(text(p, "background")?)
        {
            return Err("invalid page".into());
        }
        range(p, "width", 100, 4096)?;
        range(p, "height", 100, 8192)?;
    }
    let fields = ui
        .fields
        .iter()
        .map(|f| (f.id.as_str(), f))
        .collect::<HashMap<_, _>>();
    for f in &ui.fields {
        let p = f.ui.as_ref().ok_or("v2 control missing presentation")?;
        let map = p.as_object().ok_or("presentation must be object")?;
        if map.keys().any(|k| {
            !matches!(
                k.as_str(),
                "control"
                    | "pageId"
                    | "parentId"
                    | "x"
                    | "y"
                    | "width"
                    | "height"
                    | "fontPx"
                    | "textColor"
                    | "background"
                    | "alignment"
                    | "visible"
                    | "enabled"
                    | "binding"
                    | "events"
            )
        }) {
            return Err("unknown presentation field".into());
        }
        let control = text(p, "control")?;
        if matches!(control, "SLIDER" | "PROGRESS") {
            let low = f.minimum.unwrap_or(0);
            let high = f.maximum.unwrap_or(100);
            if !(-1_000_000_000..=1_000_000_000).contains(&low)
                || !(-1_000_000_000..=1_000_000_000).contains(&high)
                || high < low
                || high - low > 1_000_000
                || control == "PROGRESS" && (low != 0 || !(1..=1_000_000).contains(&high))
            {
                return Err("invalid slider/progress range".into());
            }
        }
        if p.get("parentId")
            .is_some_and(|v| !v.is_null() && !v.is_string())
        {
            return Err("parentId must be text or null".into());
        }
        let compatible = match f.kind {
            RunnerUiFieldKind::Text => matches!(
                control,
                "INPUT" | "TEXTAREA" | "LABEL" | "BUTTON" | "IMAGE" | "CONTAINER" | "NAVIGATION"
            ),
            RunnerUiFieldKind::Integer => matches!(control, "NUMBER" | "SLIDER" | "PROGRESS"),
            RunnerUiFieldKind::Boolean => control == "CHECKBOX",
            RunnerUiFieldKind::Choice => matches!(control, "RADIO" | "SELECT" | "LIST"),
        };
        if !compatible
            || !pages.contains(text(p, "pageId")?)
            || !color(text(p, "textColor")?)
            || !color(text(p, "background")?)
            || !matches!(text(p, "alignment")?, "left" | "center" | "right")
        {
            return Err("invalid presentation".into());
        }
        for (key, lo, hi) in [
            ("x", 0, 4096),
            ("y", 0, 8192),
            ("width", 20, 4096),
            ("height", 20, 8192),
            ("fontPx", 8, 160),
        ] {
            range(p, key, lo, hi)?;
        }
        for key in ["visible", "enabled"] {
            if !p.get(key).is_some_and(Value::is_boolean) {
                return Err("invalid UI state".into());
            }
        }
        if let Some(b) = p.get("binding").filter(|v| !v.is_null()) {
            let parts = b
                .as_str()
                .ok_or("binding must be text")?
                .split(':')
                .collect::<Vec<_>>();
            if !(parts.len() == 2 && matches!(parts[0], "global" | "param") && id(parts[1])
                || parts.len() == 3
                    && parts[0] == "flow"
                    && !parts[1].is_empty()
                    && parts[1].len() <= 128
                    && id(parts[2]))
            {
                return Err("invalid binding".into());
            }
        }
        let mut parent = p.get("parentId").and_then(Value::as_str);
        let mut seen = HashSet::from([f.id.as_str()]);
        while let Some(name) = parent {
            if !seen.insert(name) || seen.len() > 9 {
                return Err("UI container cycle/depth".into());
            }
            let owner = fields
                .get(name)
                .ok_or("missing UI parent")?
                .ui
                .as_ref()
                .ok_or("missing parent UI")?;
            if text(owner, "control")? != "CONTAINER"
                || text(owner, "pageId")? != text(p, "pageId")?
            {
                return Err("UI parent mismatch".into());
            }
            parent = owner.get("parentId").and_then(Value::as_str);
        }
        let events = p
            .get("events")
            .and_then(Value::as_object)
            .ok_or("missing events")?;
        for (key, action) in events {
            let a = action.as_str().ok_or("event action must be text")?;
            if !matches!(key.as_str(), "click" | "longClick" | "change" | "selection")
                || !(matches!(a, "run" | "close" | "minimize")
                    || a.strip_prefix("page:").is_some_and(|id| pages.contains(id))
                    || a.strip_prefix("flow:").is_some_and(|id| {
                        !id.is_empty()
                            && id.len() <= 128
                            && id
                                .bytes()
                                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-'))
                    }))
            {
                return Err("invalid UI event".into());
            }
        }
    }
    Ok(())
}
