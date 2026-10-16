//! Script interaction dialogs use the saved page identity, never a runtime file path.
use flow_ir::{FlowNode, ProjectManifest};
use serde::Deserialize;

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct DialogArgs {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub message: String,
    #[serde(default)]
    pub confirm_text: String,
    #[serde(default)]
    pub cancel_text: String,
    #[serde(default)]
    pub page_id: String,
    #[serde(default)]
    pub block_outside: bool,
    #[serde(default)]
    pub timeout_ms: u64,
    #[serde(default)]
    pub result_variable: Option<String>,
}
pub(crate) fn parse(
    kind: &str,
    node: &FlowNode,
    manifest: &ProjectManifest,
) -> Result<DialogArgs, &'static str> {
    let keys: &[&str] = match kind {
        "ui.confirm" => &[
            "title",
            "message",
            "confirmText",
            "cancelText",
            "timeoutMs",
            "resultVariable",
        ],
        "ui.dialog" => &["pageId", "blockOutside", "timeoutMs", "resultVariable"],
        _ => &[],
    };
    let object = node
        .envelope
        .args
        .as_object()
        .ok_or("invalid dialog arguments")?;
    if object.keys().any(|key| !keys.contains(&key.as_str())) {
        return Err("unexpected dialog argument");
    }
    let args: DialogArgs = serde_json::from_value(node.envelope.args.clone())
        .map_err(|_| "invalid dialog arguments")?;
    if kind == "ui.dialogclose" {
        return Ok(args);
    }
    let required = if kind == "ui.confirm" {
        &keys[..5]
    } else {
        &keys[..3]
    };
    if required.iter().any(|key| !object.contains_key(*key))
        || !(1000..=1_800_000).contains(&args.timeout_ms)
    {
        return Err("对话框等待期限应为1000～1800000ms");
    }
    if args
        .result_variable
        .as_deref()
        .is_some_and(|v| !crate::analyze::valid_variable_name(v))
    {
        return Err("invalid dialog result variable");
    }
    if kind == "ui.confirm" {
        if args.result_variable.is_none() {
            return Err("确认框需要字符结果变量");
        }
        for (text, maximum) in [
            (&args.title, 192),
            (&args.message, 4096),
            (&args.confirm_text, 96),
            (&args.cancel_text, 96),
        ] {
            if text.is_empty() || text.len() > maximum || text.contains('\0') {
                return Err("确认框文字为空或超过限制");
            }
        }
    } else if !manifest.runner_ui.as_ref().is_some_and(|ui| {
        ui.pages.iter().any(|page| {
            page.get("id").and_then(serde_json::Value::as_str) == Some(args.page_id.as_str())
        })
    }) {
        return Err("请选择已保存的设计页面");
    }
    Ok(args)
}

#[cfg(test)]
mod tests {
    use crate::{compile_project, FlowSource, SUPPORTED_NODE_VERSIONS};
    use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
    use serde_json::{json, Value};

    fn project() -> Value {
        json!({
            "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.7","projectId":"dialog-test","name":"Dialog",
            "sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
            "capabilities":["ui.control"],"variables":[{"name":"choice","type":"string","scope":"global"}],
            "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"},
            "runnerUi":{"version":2,"description":null,"pages":[{"id":"main","title":"Main","width":480,"height":320,"background":"#FFFFFFFF"}],"fields":[
                {"id":"label","label":"Label","kind":"text","required":false,"initialValue":"","minimum":null,"maximum":null,"options":[],
                    "ui":{"control":"LABEL","pageId":"main","x":0,"y":0,"width":160,"height":32,"fontPx":16,"textColor":"#FF202938","background":"#FFFFFFFF","alignment":"center","visible":true,"enabled":true,"events":{}}}
            ]}
        })
    }
    fn nodes(args: Value) -> String {
        [("ui.confirm",args), ("ui.dialog",json!({"pageId":"main","blockOutside":true,"timeoutMs":1000,"resultVariable":"choice"})), ("ui.dialogclose",json!({}))]
            .into_iter().enumerate().map(|(i,(kind,args))| json!({"flowSchemaVersion":1,"nodeId":format!("n{i}"),"blockId":"root","parentId":null,
                "depth":0,"nodeVersion":1,"orderKey":format!("a{i}"),"kind":kind,"args":args}).to_string()+"\n").collect()
    }
    fn compile(
        manifest: &Value,
        source: &str,
    ) -> Result<crate::GenerationBundle, Vec<crate::CompileError>> {
        let manifest = parse_project_manifest(&serde_json::to_vec(manifest).unwrap()).unwrap();
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
    }
    fn confirm() -> Value {
        json!({"title":"Confirm","message":"Continue?","confirmText":"OK","cancelText":"Cancel","timeoutMs":1000,"resultVariable":"choice"})
    }
    #[test]
    fn dialog_blocks_compile_to_real_api_and_typed_variables() {
        let bundle = compile(&project(), &nodes(confirm())).unwrap();
        let lua = String::from_utf8(bundle.main_lua).unwrap();
        assert!(lua.contains("UI.confirm("));
        assert!(lua.contains("UI.showDialogAndWait(\"main\",true,1000)"));
        assert!(lua.contains("UI.closeDialog()"));
        assert!(lua.contains("__vars[\"choice\"] = UI.confirm("));
    }
    #[test]
    fn capabilities_pages_result_types_and_limits_are_hard_gates() {
        let source = nodes(confirm());
        let mut manifest = project();
        manifest["capabilities"] = json!([]);
        manifest.as_object_mut().unwrap().remove("runnerUi");
        assert!(compile(&manifest, &source).is_err());
        manifest = project();
        manifest["variables"][0]["type"] = json!("integer");
        assert!(compile(&manifest, &source).is_err());
        manifest = project();
        manifest["runnerUi"]["pages"][0]["id"] = json!("other");
        manifest["runnerUi"]["fields"][0]["ui"]["pageId"] = json!("other");
        assert!(compile(&manifest, &source).is_err());
        let mut args = confirm();
        args["timeoutMs"] = json!(999);
        assert!(compile(&project(), &nodes(args)).is_err());
        args = confirm();
        args["message"] = json!("汉".repeat(1366));
        assert!(compile(&project(), &nodes(args)).is_err());
        args = confirm();
        args["pageId"] = json!("main");
        assert!(compile(&project(), &nodes(args)).is_err());
    }
}
