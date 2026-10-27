use crate::{compile_project, CompileError, FlowSource, GenerationBundle, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(
    kind: &str,
    version: u32,
    branches: Value,
    frequency: u32,
    numeric: bool,
) -> Result<GenerationBundle, Vec<CompileError>> {
    let mut variables = vec![];
    if numeric || kind == "vision.findgray" {
        variables.push(json!({"name":"found","scope":"global","type":"integer"}));
    }
    let manifest = parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.7","projectId":"branches","name":"Branches",
        "sourceMode":"visual","entryFlowId":"main",
        "flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "variables":variables,"resources":[{"kind":"image","path":"assets/images/a.png"}],
        "capabilities":["screen.capture","vision.template","vision.opencv","core.task"],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let mut args = json!({"frameVariable":"frame","imagePath":"assets/images/a.png","autoCapture":true,
        "similarityPermille":900,"region":{"left":0,"top":0,"right":720,"bottom":1280},
        "foundVariable":"found","xVariable":"x","yVariable":"y"});
    if kind == "vision.findimage" {
        args["tolerance"] = json!(0);
        args["frequency"] = json!(frequency);
    } else {
        args["scoreVariable"] = json!("score");
    }
    let mut image = json!({"flowSchemaVersion":1,"nodeId":"image","blockId":"body","parentId":"loop",
        "depth":1,"orderKey":"a0","kind":kind,"nodeVersion":version,"args":args});
    if !branches.is_null() {
        image["childBlocks"] = branches.clone();
    }
    let mut nodes = vec![
        json!({"flowSchemaVersion":1,"nodeId":"loop","blockId":"root","parentId":null,
        "depth":0,"orderKey":"a0","kind":"control.repeat","nodeVersion":1,"args":{"times":7},"childBlocks":{"body":"body"}}),
        image,
    ];
    if let Some(slots) = branches.as_object() {
        for (i, (slot, block)) in slots.iter().enumerate() {
            nodes.push(json!({"flowSchemaVersion":1,"nodeId":format!("log{i}"),"blockId":block,"parentId":"image",
                "depth":2,"orderKey":"a0","kind":"task.log","nodeVersion":1,"args":{"level":"info","message":slot}}));
        }
    }
    let source = nodes
        .iter()
        .map(|node| format!("{node}\n"))
        .collect::<String>();
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

fn runtime(found: bool, error: bool) -> mlua::Lua {
    let lua = mlua::Lua::new();
    lua.globals().set("hit", found).unwrap();
    lua.globals().set("fail", error).unwrap();
    lua.load(r#"
        captures=0; searches=0; successes=0; failures=0
        Prompt={configure=function()end,toast=function()end}
        System={elapsedRealtimeMillis=function()return 0 end}
        local function search()
            searches=searches+1
            if fail then error('CANCELLED') end
            if hit then return {x=1,y=2,scorePermille=1000} end
        end
        Screen={capture=function()captures=captures+1;return 1 end,cache=function(f)return f end,
            loadImage=function()return 2 end,release=function()end,
            findImage=search,findImages=search,findGray=search}
        Log={info=function(s)if s=='success' then successes=successes+1 else failures=failures+1 end end}
    "#).exec().unwrap();
    lua
}

#[test]
fn recognition_branches_execute_exactly_one_for_boolean_and_numeric_found() {
    for (kind, version) in [("vision.findimage", 3), ("vision.findgray", 2)] {
        for numeric in [true, false] {
            let code = compile(
                kind,
                version,
                json!({"success":"yes","failure":"no"}),
                1,
                numeric,
            )
            .unwrap()
            .main_lua;
            for found in [true, false] {
                let lua = runtime(found, false);
                lua.load(&code)
                    .eval::<mlua::Function>()
                    .unwrap()
                    .call::<()>(())
                    .unwrap();
                assert_eq!(
                    lua.globals().get::<i32>("successes").unwrap(),
                    if found { 7 } else { 0 }
                );
                assert_eq!(
                    lua.globals().get::<i32>("failures").unwrap(),
                    if found { 0 } else { 7 }
                );
            }
        }
    }
}

#[test]
fn recognition_branches_frequency_skip_runs_neither_branch_and_does_not_double_visit() {
    let code = compile(
        "vision.findimage",
        3,
        json!({"success":"yes","failure":"no"}),
        3,
        true,
    )
    .unwrap()
    .main_lua;
    for found in [true, false] {
        let lua = runtime(found, false);
        lua.load(&code)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(lua.globals().get::<i32>("searches").unwrap(), 3);
        assert_eq!(lua.globals().get::<i32>("captures").unwrap(), 3);
        assert_eq!(
            lua.globals().get::<i32>("successes").unwrap(),
            if found { 3 } else { 0 }
        );
        assert_eq!(
            lua.globals().get::<i32>("failures").unwrap(),
            if found { 0 } else { 3 }
        );
    }
}

#[test]
fn recognition_branches_errors_and_cancellation_never_run_failure() {
    for (kind, version, frequency) in [
        ("vision.findimage", 3, 1),
        ("vision.findimage", 3, 3),
        ("vision.findgray", 2, 1),
    ] {
        let code = compile(
            kind,
            version,
            json!({"success":"yes","failure":"no"}),
            frequency,
            true,
        )
        .unwrap()
        .main_lua;
        let lua = runtime(false, true);
        assert!(lua
            .load(&code)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .is_err());
        assert_eq!(lua.globals().get::<i32>("successes").unwrap(), 0);
        assert_eq!(lua.globals().get::<i32>("failures").unwrap(), 0);
    }
}

#[test]
fn recognition_branches_keep_legacy_leaves_and_reject_partial_unknown_or_old_version_slots() {
    for (kind, old, new) in [("vision.findimage", 2, 3), ("vision.findgray", 1, 2)] {
        assert!(compile(kind, old, Value::Null, 1, true).is_ok());
        assert!(compile(kind, new, Value::Null, 1, true).is_ok());
        for slots in [
            json!({"success":"yes"}),
            json!({"success":"yes","failure":"no","body":"extra"}),
        ] {
            assert!(compile(kind, new, slots, 1, true).is_err());
        }
        assert!(compile(kind, old, json!({"success":"yes","failure":"no"}), 1, true).is_err());
    }
}
