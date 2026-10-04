use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(
    kind: &str,
    args: Value,
    capabilities: &[&str],
    api: &str,
    variables: Value,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let manifest = parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"data-test","name":"Data",
        "sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "variables":variables,"capabilities":capabilities,"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let source = serde_json::to_vec(&json!({"flowSchemaVersion":1,"nodeId":"data","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":kind,"nodeVersion":1,"args":args})).unwrap();
    let report = load_jsonl(
        &source,
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
            exact_bytes: &source,
            report: &report,
        }],
    )
}

#[test]
fn file_storage_permission_is_explicit_and_old_runtime_is_rejected() {
    let args = json!({"path":"state/value.txt","resultVariable":"out"});
    assert!(compile(
        "files.read",
        args.clone(),
        &["storage.data"],
        "1.7",
        json!([])
    )
    .is_ok());
    let denied = compile("files.read", args.clone(), &["core.task"], "1.7", json!([])).unwrap_err();
    assert!(denied.iter().any(|error| matches!(error, CompileError::MissingCapability { capability, .. } if capability=="storage.data")));
    assert!(compile("files.read", args, &["storage.data"], "1.6", json!([])).is_err());
    assert!(compile(
        "data.length",
        json!({"json":"[]","resultVariable":"out"}),
        &[],
        "1.7",
        json!([])
    )
    .is_err());
}

#[test]
fn data_source_and_result_types_and_paths_fail_before_execution() {
    let variables = json!([{"name":"source","scope":"global","type":"integer"}, {"name":"out","scope":"global","type":"integer"}]);
    assert!(compile(
        "data.length",
        json!({"json":"[]","jsonVariable":"source","resultVariable":"out"}),
        &["core.task"],
        "1.7",
        variables.clone()
    )
    .is_err());
    assert!(compile(
        "data.get",
        json!({"json":"[1]","key":"1","resultVariable":"out"}),
        &["core.task"],
        "1.7",
        variables
    )
    .is_err());
    assert!(compile(
        "files.remove",
        json!({"path":"../source.lua"}),
        &["storage.data"],
        "1.7",
        json!([])
    )
    .is_err());
}
