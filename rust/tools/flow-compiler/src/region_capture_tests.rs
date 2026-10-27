use super::*;
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::json;

fn project() -> ProjectManifest {
    parse_project_manifest(&serde_json::to_vec(&json!({"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.8","projectId":"region","name":"region","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"variables":[{"name":"frame","scope":"global","type":"image"}],"capabilities":["screen.capture"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}})).unwrap()).unwrap()
}
fn compile(
    project: &ProjectManifest,
    args: &serde_json::Value,
) -> Result<GenerationBundle, Vec<CompileError>> {
    let source=json!({"flowSchemaVersion":1,"nodeId":"region","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":"screen.captureregion","nodeVersion":1,"args":args}).to_string()+"\n";
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
        project,
        &[FlowSource {
            flow_id: "main",
            exact_bytes: source.as_bytes(),
            report: &report,
        }],
    )
}
#[test]
fn regional_block_compiles_to_one_capture_and_gates_old_api_capability_and_type() {
    let args = json!({"resultVariable":"frame","left":149,"top":407,"right":546,"bottom":879});
    let project = project();
    let bundle = compile(&project, &args).unwrap();
    let source = std::str::from_utf8(&bundle.main_lua).unwrap();
    assert_eq!(source.matches("Screen.captureRegion(").count(), 1);
    assert!(!source.contains("Screen.capture()"));
    let lua = mlua::Lua::new();
    lua.load("calls=0; Prompt={toast=function()end}; Screen={captureRegion=function(l,t,r,b) assert(l==149 and t==407 and r==546 and b==879); calls=calls+1;return 7 end,cache=function(v) assert(v==7);return v end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(lua.globals().get::<u32>("calls").unwrap(), 1);
    let mut old = project.clone();
    old.runtime_api = "1.7".into();
    assert!(compile(&old, &args).is_err());
    let mut denied = project.clone();
    denied.capabilities.clear();
    assert!(compile(&denied, &args).is_err());
    let mut untyped = project.clone();
    untyped.variables.clear();
    assert!(compile(&untyped, &args).is_err());
    let mut invalid = args;
    invalid["right"] = json!(149);
    assert!(compile(&project, &invalid).is_err());
}
