use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(
    kind: &str,
    args: Value,
    caps: Vec<&str>,
    api: &str,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let manifest=parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"native-test","name":"Native",
        "sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "variables":[],"resources":[{"kind":"image","path":"assets/images/a.png"}],"capabilities":caps,
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let source=serde_json::to_vec(&json!({"flowSchemaVersion":1,"nodeId":"native","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":kind,"nodeVersion":1,"args":args})).unwrap();
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
fn gray() -> Value {
    json!({"frameVariable":"frame","imagePath":"assets/images/a.png","similarityPermille":900,"region":{"left":0,"top":0,"right":720,"bottom":1280},"foundVariable":"found","xVariable":"x","yVariable":"y","scoreVariable":"score"})
}
fn ocr() -> Value {
    json!({"frameVariable":"frame","region":{"left":0,"top":0,"right":320,"bottom":48},"minimumConfidencePermille":500,"textVariable":"text","scoreVariable":"score"})
}
#[test]
fn native_calls_freeze_real_api_and_release_auto_frames() {
    let gray = String::from_utf8(
        compile(
            "vision.findgray",
            gray(),
            vec!["vision.opencv", "vision.template", "screen.capture"],
            "1.7",
        )
        .unwrap()
        .main_lua,
    )
    .unwrap();
    assert!(gray.contains("Screen.findGray(__f,__t,900,0,0,720,1280)"));
    assert!(gray.contains("__r and 1 or 0"));
    assert!(gray.contains("Screen.release(__t)"));
    assert!(gray.contains("Screen.release(__f)"));
    let ocr = String::from_utf8(
        compile(
            "ocr.alphanumeric",
            ocr(),
            vec!["ocr.onnx", "screen.capture"],
            "1.7",
        )
        .unwrap()
        .main_lua,
    )
    .unwrap();
    assert!(ocr.contains("Ocr.alphanumeric(__f,0,0,320,48,500)"));
    assert!(ocr.contains("averageScorePermille"));
}
#[test]
fn missing_permissions_old_api_aliases_and_invalid_scores_fail_compilation() {
    assert!(compile(
        "vision.findgray",
        gray(),
        vec!["vision.opencv", "screen.capture"],
        "1.7"
    )
    .is_err());
    assert!(compile(
        "ocr.alphanumeric",
        ocr(),
        vec!["ocr.onnx", "screen.capture"],
        "1.6"
    )
    .is_err());
    let mut alias = gray();
    alias["foundVariable"] = json!("frame");
    assert!(compile(
        "vision.findgray",
        alias,
        vec!["vision.opencv", "vision.template", "screen.capture"],
        "1.7"
    )
    .is_err());
    let mut bad = ocr();
    bad["minimumConfidencePermille"] = json!(1001);
    assert!(compile(
        "ocr.alphanumeric",
        bad,
        vec!["ocr.onnx", "screen.capture"],
        "1.7"
    )
    .is_err());
}
#[test]
fn borrowed_frame_mode_does_not_capture_or_release_user_frame() {
    let mut args = ocr();
    args["autoCapture"] = json!(false);
    let lua = String::from_utf8(
        compile(
            "ocr.alphanumeric",
            args,
            vec!["ocr.onnx", "screen.capture"],
            "1.7",
        )
        .unwrap()
        .main_lua,
    )
    .unwrap();
    assert!(!lua.contains("Screen.capture()"));
    assert!(!lua.contains("Screen.release(__f)"));
    assert!(lua.contains("local __f=__vars[\"frame\"]"));
}
