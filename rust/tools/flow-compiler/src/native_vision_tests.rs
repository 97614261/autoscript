use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(
    kind: &str,
    args: Value,
    caps: Vec<&str>,
    api: &str,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    compile_disabled(kind, args, caps, api, false)
}

fn compile_disabled(
    kind: &str,
    args: Value,
    caps: Vec<&str>,
    api: &str,
    disabled: bool,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let mut source_node = json!({"flowSchemaVersion":1,"nodeId":"native","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":kind,"nodeVersion":1,"args":null,"disabled":disabled});
    source_node["args"] = args;
    compile_nodes(&[source_node], caps, api)
}

fn compile_nodes(
    nodes: &[Value],
    caps: Vec<&str>,
    api: &str,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let mut manifest_value = json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"native-test","name":"Native",
        "sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "variables":[],"resources":[{"kind":"image","path":"assets/images/a.png"}],"capabilities":null,
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    });
    manifest_value["capabilities"] = serde_json::to_value(caps).unwrap();
    let manifest = parse_project_manifest(&serde_json::to_vec(&manifest_value).unwrap()).unwrap();
    let source = nodes
        .iter()
        .map(|node| serde_json::to_string(node).unwrap() + "\n")
        .collect::<String>()
        .into_bytes();
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
fn disabled_parent_excludes_native_children_and_active_demands_are_canonical() {
    for disabled in [false, true] {
        let nodes = [
            json!({"flowSchemaVersion":1,"nodeId":"parent","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":"control.repeat","nodeVersion":1,"args":{"times":1},"childBlocks":{"body":"body"},"disabled":disabled}),
            json!({"flowSchemaVersion":1,"nodeId":"ocr","blockId":"body","parentId":"parent","depth":1,"orderKey":"a1","kind":"ocr.alphanumeric","nodeVersion":1,"args":ocr()}),
            json!({"flowSchemaVersion":1,"nodeId":"gray","blockId":"body","parentId":"parent","depth":1,"orderKey":"a2","kind":"vision.findgray","nodeVersion":1,"args":gray()}),
            json!({"flowSchemaVersion":1,"nodeId":"gray-again","blockId":"body","parentId":"parent","depth":1,"orderKey":"a3","kind":"vision.findgray","nodeVersion":1,"args":gray()}),
        ];
        let bundle = compile_nodes(
            &nodes,
            vec![
                "core.task",
                "vision.opencv",
                "vision.template",
                "ocr.onnx",
                "screen.capture",
            ],
            "1.7",
        )
        .unwrap();
        let map: crate::SourceMap = serde_json::from_slice(&bundle.source_map_json).unwrap();
        assert_eq!(
            map.vision_requirements.unwrap().components,
            if disabled {
                vec![]
            } else {
                vec![crate::VisionComponent::Opencv, crate::VisionComponent::Onnx]
            }
        );
    }
}

#[test]
fn source_map_demands_emitted_operations_not_default_capabilities() {
    let caps = vec![
        "vision.opencv",
        "vision.template",
        "ocr.onnx",
        "screen.capture",
    ];
    for (kind, args, expected) in [
        ("task.noop", json!({}), vec![]),
        (
            "vision.findgray",
            gray(),
            vec![crate::VisionComponent::Opencv],
        ),
        (
            "ocr.alphanumeric",
            ocr(),
            vec![crate::VisionComponent::Onnx],
        ),
    ] {
        let bundle = compile(kind, args.clone(), caps.clone(), "1.7").unwrap();
        let map: crate::SourceMap = serde_json::from_slice(&bundle.source_map_json).unwrap();
        let demands = map.vision_requirements.unwrap();
        assert_eq!(demands.version, 1);
        assert_eq!(demands.components, expected);
        let disabled = compile_disabled(kind, args, caps.clone(), "1.7", true).unwrap();
        let map: crate::SourceMap = serde_json::from_slice(&disabled.source_map_json).unwrap();
        assert!(map.vision_requirements.unwrap().components.is_empty());
        assert!(map.entries.is_empty());
    }
}

#[test]
fn demands_are_bound_by_the_generation_digest() {
    let bundle = compile(
        "vision.findgray",
        gray(),
        vec!["vision.opencv", "vision.template", "screen.capture"],
        "1.7",
    )
    .unwrap();
    let mut map: Value = serde_json::from_slice(&bundle.source_map_json).unwrap();
    map["visionRequirements"]["components"] = json!([]);
    assert_ne!(
        crate::digest::content_digest(&serde_json::to_vec(&map).unwrap()),
        bundle.record.source_map_digest
    );
    let old = json!({"schemaVersion":1,"generationId":"gen-0123456789abcdef0123456789abcdef","entries":[]});
    let old: crate::SourceMap = serde_json::from_value(old).unwrap();
    assert!(old.vision_requirements.is_none());
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
