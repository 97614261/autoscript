use crate::{compile_project, FlowSource, GenerationBundle, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(api: &str, extra: Option<Value>) -> Result<GenerationBundle, String> {
    compile_options(api, extra, false, 256, 256)
}

fn compile_options(
    api: &str,
    extra: Option<Value>,
    capture: bool,
    count_limit: u32,
    list_limit: u32,
) -> Result<GenerationBundle, String> {
    let manifest = parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"colors","name":"Colors",
        "sourceMode":"visual","entryFlowId":"main",
        "flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "capabilities": if capture { vec!["vision.pixel", "screen.capture"] } else { vec!["vision.pixel"] },
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let source = ["vision.findcolor", "vision.comparecolor", "vision.countcolor", "vision.findallcolor"]
        .into_iter().enumerate().map(|(index,kind)| {
            let mut args = json!({"frameVariable":"frame","rgb":16711680,"tolerance":8});
            if capture { args["autoCapture"]=json!(true); }
            if kind == "vision.comparecolor" { args["point"] = json!({"x":1,"y":2}); }
            else { args["region"] = json!({"left":0,"top":0,"right":20,"bottom":10}); }
            if kind == "vision.findcolor" {
                args["foundVariable"]=json!("found"); args["xVariable"]=json!("x"); args["yVariable"]=json!("y");
            } else { args["resultVariable"]=json!(format!("result{index}")); }
            if kind == "vision.countcolor" { args["limit"]=json!(count_limit); }
            if kind == "vision.findallcolor" { args["limit"]=json!(list_limit); }
            if let Some(extra) = &extra { args["additionalColors"] = extra.clone(); }
            json!({"flowSchemaVersion":1,"nodeId":format!("node-{index}"),"blockId":"root","parentId":null,
                "orderKey":format!("a{index}"),"kind":kind,"nodeVersion":1,"depth":0,"args":args}).to_string()+"\n"
        }).collect::<String>();
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
    .map_err(|e| format!("{e:?}"))
}

#[test]
fn color_counts_and_coordinate_lists_have_independent_extended_limits() {
    for capture in [false, true] {
        for count in [0, 257, 64_000_000] {
            let bundle = compile_options("1.8", None, capture, count, 2048).unwrap();
            let lua = String::from_utf8(bundle.main_lua).unwrap();
            assert_eq!(lua.matches("Screen.colorLimitsVersion ~= 1").count(), 2);
            assert_eq!(
                lua.matches("local __color_frame=").count(),
                if capture { 4 } else { 0 }
            );
        }
    }
    assert!(compile_options("1.7", None, false, 0, 256)
        .unwrap_err()
        .contains("runtimeApi 1.8"));
    assert!(compile_options("1.7", None, false, 256, 257)
        .unwrap_err()
        .contains("runtimeApi 1.8"));
    for (count, list) in [(64_000_001, 256), (256, 0), (256, 2049)] {
        assert!(compile_options("1.8", None, true, count, list).is_err());
    }
}

#[test]
fn color_groups_compile_all_four_calls_with_runtime_guard_and_original_single_color_arity() {
    let bundle = compile("1.8", Some(json!("0000FF:16;00FF00:0"))).unwrap();
    let lua = String::from_utf8(bundle.main_lua).unwrap();
    assert_eq!(lua.matches("Screen.colorGroupVersion ~= 1").count(), 4);
    for method in ["findColor", "compareColor", "countColor", "findAllColor"] {
        assert!(lua.contains(&format!("Screen.{method}(")));
    }
    assert_eq!(lua.matches(",\"0000FF:16;00FF00:0\")").count(), 4);
    let old = compile("1.5", None).unwrap();
    let empty = compile("1.5", Some(json!(""))).unwrap();
    let body = |bytes: &[u8]| {
        String::from_utf8_lossy(bytes)
            .lines()
            .filter(|line| !line.starts_with("-- generationId:"))
            .collect::<Vec<_>>()
            .join("\n")
    };
    assert_eq!(body(&old.main_lua), body(&empty.main_lua));
    assert!(!String::from_utf8(old.main_lua)
        .unwrap()
        .contains("colorGroupVersion"));
}

#[test]
fn color_groups_reject_old_runtime_duplicates_malformed_and_oversized_values() {
    assert!(compile("1.7", Some(json!("0000FF:8")))
        .unwrap_err()
        .contains("runtimeApi 1.8"));
    for extra in [
        json!("FF0000:0"),
        json!("0000FF:0;0000ff:1"),
        json!("0000FF:256"),
        json!("0000FF:0;"),
        json!(true),
        json!("A".repeat(165)),
    ] {
        assert!(compile("1.8", Some(extra.clone())).is_err(), "{extra}");
    }
}
