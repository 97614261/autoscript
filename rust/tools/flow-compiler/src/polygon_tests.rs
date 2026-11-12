use crate::{compile_project, FlowSource, GenerationBundle, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

const POLYGON: &str = "[[0,0],[4,0],[0,4]]";
fn compile(api: &str, polygon: Value, dynamic: bool) -> Result<GenerationBundle, String> {
    let manifest=parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"polygon","name":"Polygon",
        "sourceMode":"visual","entryFlowId":"main",
        "flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "resources":[{"kind":"image","path":"assets/images/a.png"}],
        "variables":[{"name":"frame","type":"image","scope":"global"},
            {"name":"found","type":"integer","scope":"global"},{"name":"x","type":"integer","scope":"global"},
            {"name":"y","type":"integer","scope":"global"},{"name":"result1","type":"integer","scope":"global"},
            {"name":"result2","type":"string","scope":"global"},{"name":"matches","type":"string","scope":"global"}],
        "capabilities":["vision.pixel","vision.template","screen.capture","core.task"],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let kinds = [
        ("vision.findcolor", 1),
        ("vision.countcolor", 1),
        ("vision.findallcolor", 1),
        ("vision.findmulticolor", 1),
        ("vision.findimage", 1),
        ("vision.findimage", 3),
        ("vision.findallimages", 1),
    ];
    let source=kinds.into_iter().enumerate().map(|(index,(kind,version))|{
        let mut args=json!({"frameVariable":"frame","region":{"left":0,"top":0,"right":4,"bottom":4},"polygon":polygon});
        match kind {
            "vision.findcolor"|"vision.countcolor"|"vision.findallcolor"=>{
                args["rgb"]=json!(16777215);args["tolerance"]=json!(0);
                if kind!="vision.findcolor" {args["limit"]=json!(2);args["resultVariable"]=json!(format!("result{index}"));}
            },
            "vision.findmulticolor"=>{args["anchorRgb"]=json!(16777215);args["anchorTolerance"]=json!(0);
                args["samples"]=json!([{"x":1,"y":0,"rgb":16777215,"tolerance":0}]);},
            _=>{args["tolerance"]=json!(0);args["similarityPermille"]=json!(1000);
                if kind=="vision.findallimages" {args["imagePaths"]=json!(["assets/images/a.png"]);args["autoCapture"]=json!(false);
                    args["direction"]=json!(0);args["limit"]=json!(2);args["resultVariable"]=json!("matches");}
                else {args["imagePath"]=json!("assets/images/a.png");
                    if version==3 {args["frequency"]=json!(2);}
                    if dynamic {args["leftVariable"]=json!("left");}}
            }
        }
        if matches!(kind,"vision.findcolor"|"vision.findmulticolor"|"vision.findimage") {
            args["foundVariable"]=json!("found");args["xVariable"]=json!("x");args["yVariable"]=json!("y");
        }
        let mut node=json!({"flowSchemaVersion":1,"nodeId":format!("node-{index}"),"blockId":"root","parentId":null,
            "orderKey":format!("a{index}"),"kind":kind,"nodeVersion":version,"depth":0,"args":args});
        if kind=="vision.findallimages" || version==3 {node["childBlocks"]=json!({"success":format!("yes{index}"),"failure":format!("no{index}")});}
        node.to_string()+"\n"
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
fn polygon_compiler_routes_all_seven_calls_with_correct_optional_slots() {
    let code = compile("1.8", json!(POLYGON), false).unwrap().main_lua;
    let lua = mlua::Lua::new();
    lua.globals().set("expected", POLYGON).unwrap();
    lua.load(r#"
        calls=0
        local function check(n,...)
            assert(select('#',...)==n,select('#',...)); assert(select(n,...)==expected);calls=calls+1
        end
        Prompt={configure=function()end,toast=function()end}
        System={elapsedRealtimeMillis=function()return 0 end}
        Screen={polygonRegionVersion=1,colorLimitsVersion=1,loadImage=function()return 2 end,release=function()end}
        function Screen.findColor(...)check(9,...)end
        function Screen.countColor(...)check(10,...)return 0 end
        function Screen.findAllColor(...)check(10,...)return {}end
        function Screen.findMultiColor(...)check(9,...)end
        function Screen.findImage(...)check(10,...)end
        function Screen.findImages(...)check(10,...)end
        function Screen.findAllImages(...)check(11,...)return '{"version":1,"count":0,"truncated":false,"matches":[]}'end
        function Screen.matchValue()return 0 end
    "#).exec().unwrap();
    lua.load(&code)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(lua.globals().get::<i32>("calls").unwrap(), 7);
    lua.load("Screen.polygonRegionVersion=nil").exec().unwrap();
    let error = lua
        .load(&code)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap_err()
        .to_string();
    assert!(error.contains("POLYGON_REGION_UNSUPPORTED"), "{error}");
}

#[test]
fn polygon_compiler_rejects_old_runtime_invalid_shapes_and_dynamic_bounds() {
    assert!(compile("1.7", json!(POLYGON), false)
        .unwrap_err()
        .contains("runtimeApi 1.8"));
    for polygon in [
        json!(true),
        json!("[[0,0],[4,4],[0,4],[4,0]]"),
        json!("[]"),
        json!("[[0,0],[5,0],[0,4]]"),
    ] {
        assert!(compile("1.8", polygon, false).is_err());
    }
    assert!(compile("1.8", json!(POLYGON), true)
        .unwrap_err()
        .contains("范围变量"));
}

#[test]
fn polygon_empty_arguments_keep_rectangular_calls_and_have_no_capability_guard() {
    let code = compile("1.7", json!(""), false).unwrap().main_lua;
    assert!(!String::from_utf8(code)
        .unwrap()
        .contains("polygonRegionVersion"));
}
