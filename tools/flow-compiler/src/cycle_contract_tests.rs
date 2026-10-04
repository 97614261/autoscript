use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn errors(kind: &str, args: Value, children: Value, capabilities: Value) -> Vec<CompileError> {
    errors_with_api(kind, args, children, capabilities, "1.7")
}

fn errors_with_api(
    kind: &str,
    args: Value,
    children: Value,
    capabilities: Value,
    api: &str,
) -> Vec<CompileError> {
    let mut manifest_json = json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.7",
        "projectId":"contract-gates","name":"Contract gates","sourceMode":"visual",
        "entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl",
            "rootBlockId":"root","params":[],"returns":null}],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    });
    manifest_json["capabilities"] = capabilities;
    manifest_json["runtimeApi"] = json!(api);
    let manifest = parse_project_manifest(&serde_json::to_vec(&manifest_json).unwrap()).unwrap();
    let mut envelope = json!({"flowSchemaVersion":1,"nodeId":"candidate","blockId":"root",
        "parentId":null,"orderKey":"a0","kind":kind,"nodeVersion":1,"depth":0});
    envelope["args"] = args;
    if !children.is_null() {
        envelope["childBlocks"] = children;
    }
    let source = serde_json::to_string(&envelope).unwrap() + "\n";
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
    .expect_err("invalid contract must be rejected")
}

#[test]
fn cycle_bounds_unknown_fields_and_capability_are_enforced() {
    for args in [
        json!({}),
        json!({"yieldMs":0}),
        json!({"yieldMs":1001}),
        json!({"yieldMs":1,"durationMs":0}),
        json!({"yieldMs":1,"durationMs":86_400_001}),
        json!({"yieldMs":1,"maxIterations":100}),
        json!({"yieldMs":1,"durationMs":10,"durationVariable":"seconds"}),
        json!({"yieldMs":1,"durationUnit":"seconds"}),
    ] {
        assert!(errors(
            "control.cycle",
            args,
            json!({"body":"body"}),
            json!(["core.task"])
        )
        .iter()
        .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. })));
    }
    assert!(errors("control.cycle", json!({"yieldMs":1}), json!({"body":"body"}), json!([]))
        .iter().any(|error| matches!(error, CompileError::MissingCapability { capability, .. } if capability == "core.task")));
    assert!(errors(
        "control.cycle",
        json!({"yieldMs":1}),
        json!({"wrong":"body"}),
        json!(["core.task"])
    )
    .iter()
    .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. })));
}

#[test]
fn status_and_continue_do_not_accept_untyped_or_unowned_actions() {
    for args in [
        json!({}),
        json!({"success":1}),
        json!({"success":"true"}),
        json!({"success":true,"other":1}),
    ] {
        assert!(errors("flow.status.return", args, Value::Null, json!([]))
            .iter()
            .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. })));
    }
    assert!(
        errors("control.continue", json!({}), Value::Null, json!([]))
            .iter()
            .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. }))
    );
    assert!(errors(
        "flow.call.branch",
        json!({"targetFlowId":"main","arguments":{}}),
        json!({"success":"success"}),
        json!([])
    )
    .iter()
    .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. })));
}

#[test]
fn dynamic_output_rejects_untyped_sources_unknown_code_and_missing_capability() {
    assert!(errors_with_api(
        "task.message",
        json!({"channel":"popup","expression":"\"ok\""}),
        Value::Null,
        json!(["core.task"]),
        "1.5"
    )
    .iter()
    .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. })));
    for args in [
        json!({}),
        json!({"channel":"popup","expression":"missing"}),
        json!({"channel":"popup","expression":"123"}),
        json!({"channel":"popup","expression":"os.execute(\"x\")"}),
        json!({"channel":"other","expression":"\"ok\""}),
        json!({"channel":"popup","expression":"\"ok\"","extra":1}),
        json!({"channel":"popup","expression":"x".repeat(1025)}),
    ] {
        assert!(
            errors("task.message", args, Value::Null, json!(["core.task"]))
                .iter()
                .any(|error| matches!(error, CompileError::InvalidNodeArguments { .. }))
        );
    }
    assert!(errors(
        "task.message",
        json!({"channel":"popup","expression":"\"ok\""}),
        Value::Null,
        json!([])
    )
    .iter()
    .any(|error| matches!(error, CompileError::MissingCapability { .. })));
}
