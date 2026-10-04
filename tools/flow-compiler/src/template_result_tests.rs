use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn args() -> Value {
    json!({"frameVariable":"frame","autoCapture":true,"imagePaths":["assets/images/a.png"],"tolerance":0,"similarityPermille":1000,"region":{"left":0,"top":0,"right":2,"bottom":2},"direction":0,"limit":2,"resultVariable":"matches"})
}
fn compile(
    a: Value,
    property: Option<Value>,
    api: &str,
    result_type: &str,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    compile_caps(
        a,
        property,
        api,
        result_type,
        json!(["vision.template", "screen.capture", "core.task"]),
    )
}
fn compile_caps(
    a: Value,
    property: Option<Value>,
    api: &str,
    result_type: &str,
    caps: Value,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let m = parse_project_manifest(&serde_json::to_vec(&json!({"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"all-templates","name":"Templates","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"variables":[{"name":"matches","scope":"flow","flowId":"main","type":result_type},{"name":"count","scope":"flow","flowId":"main","type":"integer"}],"resources":[{"kind":"image","path":"assets/images/a.png"}],"capabilities":caps,"design":{"width":2,"height":2,"scaleMode":"letterbox","orientationPolicy":"follow"}})).unwrap()).unwrap();
    let mut nodes = vec![
        json!({"kind":"vision.findallimages","args":a,"childBlocks":{"success":"yes","failure":"no"}}),
        json!({"kind":"task.log","blockId":"yes","parentId":"n0","depth":1,"args":{"level":"info","message":"found"}}),
        json!({"kind":"task.log","blockId":"no","parentId":"n0","depth":1,"args":{"level":"info","message":"empty"}}),
    ];
    if let Some(p) = property {
        nodes.push(json!({"kind":"vision.result","args":p}));
    }
    let text = nodes.into_iter().enumerate().map(|(i, mut v)| { let o=v.as_object_mut().unwrap();
        for (k,v) in json!({"flowSchemaVersion":1,"nodeId":format!("n{i}"),"blockId":"root","parentId":null,"depth":0,"orderKey":format!("a{i}"),"nodeVersion":1}).as_object().unwrap() { o.entry(k.clone()).or_insert(v.clone()); }
        serde_json::to_string(&v).unwrap()+"\n"
    }).collect::<String>();
    let report = load_jsonl(
        text.as_bytes(),
        LoadOptions {
            flow_id: "main",
            root_block_id: "root",
            flow_schema_version: 1,
            supported_nodes: SUPPORTED_NODE_VERSIONS,
        },
    );
    compile_project(
        &m,
        &[FlowSource {
            flow_id: "main",
            exact_bytes: text.as_bytes(),
            report: &report,
        }],
    )
}
#[test]
fn templates_freeze_mutually_exclusive_branches_and_release_on_error() {
    let code = compile(
        args(),
        Some(
            json!({"sourceVariable":"matches","index":0,"field":"count","targetVariable":"count"}),
        ),
        "1.7",
        "string",
    )
    .unwrap()
    .main_lua;
    for (count, expected) in [(0, "empty"), (2, "found")] {
        let lua = mlua::Lua::new();
        lua.load("Prompt={toast=function()end}").exec().unwrap();
        lua.globals().set("matched", count).unwrap();
        lua.load("released=0; calls=0; Screen={capture=function()return 1 end,cache=function(f)return f end,release=function()released=released+1 end,findAllImages=function()calls=calls+1;return '{}' end,matchValue=function()return tostring(matched)end};Log={info=function(s)result=s end}").exec().unwrap();
        lua.load(&code)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(lua.globals().get::<String>("result").unwrap(), expected);
        assert_eq!(lua.globals().get::<i32>("released").unwrap(), 1);
        assert_eq!(lua.globals().get::<i32>("calls").unwrap(), 1);
    }
    let lua = mlua::Lua::new();
    lua.load("Prompt={toast=function()end}").exec().unwrap();
    lua.load("released=0;Screen={capture=function()return 1 end,cache=function(f)return f end,release=function()released=released+1 end,findAllImages=function()error('bounded failure')end};Log={info=function()error('branch executed on error')end}").exec().unwrap();
    assert!(lua
        .load(&code)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .is_err());
    assert_eq!(lua.globals().get::<i32>("released").unwrap(), 1);
}
#[test]
fn templates_require_bounded_declared_resources_and_typed_outputs() {
    assert!(compile(args(), None, "1.6", "string").is_err());
    assert!(compile(args(), None, "1.7", "integer").is_err());
    for (key, value) in [
        ("limit", json!(0)),
        ("limit", json!(65)),
        ("imagePaths", json!(["assets/images/missing.png"])),
        (
            "imagePaths",
            json!(["assets/images/a.png", "assets/images/a.png"]),
        ),
        ("unknown", json!(1)),
    ] {
        let mut a = args();
        a[key] = value;
        assert!(compile(a, None, "1.7", "string").is_err());
    }
    for p in [
        json!({"sourceVariable":"matches","index":1,"field":"count","targetVariable":"count"}),
        json!({"sourceVariable":"matches","index":0,"field":"x","targetVariable":"count"}),
        json!({"sourceVariable":"matches","index":1,"field":"path","targetVariable":"count"}),
    ] {
        assert!(compile(args(), Some(p), "1.7", "string").is_err());
    }
    let mut borrowed = args();
    borrowed["autoCapture"] = json!(false);
    assert!(compile(borrowed, None, "1.7", "string").is_err());
}

#[test]
fn multi_result_actions_are_bounded_ordered_and_frames_released_before_input() {
    for (all, count) in [(false, 1), (true, 2)] {
        let mut a = args();
        a["successAction"] = json!("pressRelease");
        a["allMatches"] = json!(all);
        a["offsetX"] = json!(-2);
        a["offsetY"] = json!(3);
        a["beforeWaitMs"] = json!(7);
        a["afterWaitMs"] = json!(9);
        a["actionDurationMs"] = json!(100);
        assert!(compile(a.clone(), None, "1.7", "string").is_err()); // input permission is not inferred
        let bundle = compile_caps(
            a,
            None,
            "1.7",
            "string",
            json!([
                "vision.template",
                "screen.capture",
                "input.basic",
                "core.task"
            ]),
        )
        .unwrap();
        let lua = mlua::Lua::new();
        lua.load("Prompt={toast=function()end}; released=0; downs=0; ups=0; waits={}; Task={sleep=function(ms)table.insert(waits,ms);if ms~=7 then assert(released==1)end end}; Screen={capture=function() assert(waits[1]==7);return 1 end,cache=function(f)return f end,release=function()released=released+1 end,findAllImages=function()return '{}'end,matchValue=function(r,i,p)if p=='count'then return '2' end; return tostring(i*10)end}; Input={pointerDownScreen=function(x,y)assert(released==1); downs=downs+1; assert(x==downs*10-2 and y==downs*10+3)end,pointerUp=function()ups=ups+1 end}; Log={info=function(s)result=s end}").exec().unwrap();
        lua.load(bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(lua.globals().get::<usize>("downs").unwrap(), count);
        assert_eq!(lua.globals().get::<usize>("ups").unwrap(), count);
        let waits = lua
            .globals()
            .get::<mlua::Table>("waits")
            .unwrap()
            .sequence_values::<i32>()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(
            waits,
            if all {
                vec![7, 100, 100, 9]
            } else {
                vec![7, 100, 9]
            }
        );
    }
    for patch in [
        json!({"successAction":"hold","allMatches":true}),
        json!({"beforeWaitMs":60001}),
        json!({"actionDurationMs":5001}),
    ] {
        let mut a = args();
        for (k, v) in patch.as_object().unwrap() {
            a[k] = v.clone();
        }
        assert!(compile_caps(
            a,
            None,
            "1.7",
            "string",
            json!([
                "vision.template",
                "screen.capture",
                "input.basic",
                "core.task"
            ])
        )
        .is_err());
    }
}
