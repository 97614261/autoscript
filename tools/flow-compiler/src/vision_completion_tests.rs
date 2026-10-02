use crate::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn compile(
    args: Value,
    api: &str,
    version: u32,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    compile_repeated(args, api, version, false)
}

fn compile_repeated(
    args: Value,
    api: &str,
    version: u32,
    repeat: bool,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    compile_with_found_type(args, api, version, repeat, None)
}

fn compile_with_found_type(
    args: Value,
    api: &str,
    version: u32,
    repeat: bool,
    found_type: Option<&str>,
) -> Result<crate::GenerationBundle, Vec<CompileError>> {
    let parameter_type = found_type.and_then(|kind| kind.strip_prefix("parameter:"));
    let manifest = parse_project_manifest(&serde_json::to_vec(&json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":api,"projectId":"vision-new","name":"Vision",
        "sourceMode":"visual","entryFlowId":"main",
        "flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":parameter_type.map(|kind| vec![json!({"name":"found","type":kind,"required":false})]).unwrap_or_default(),"returns":null}],
        "variables":found_type.map(|kind| if parameter_type.is_some() {
            vec![json!({"name":"found","scope":"global","type":"string"})]
        } else { vec![json!({"name":"found","scope":"flow","flowId":"main","type":kind})] }).unwrap_or_default(),
        "resources":[{"kind":"image","path":"assets/images/z.png"},{"kind":"image","path":"assets/images/a.png"}],"capabilities":["screen.capture","vision.template","input.basic","core.task"],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    })).unwrap()).unwrap();
    let image = json!({"flowSchemaVersion":1,"nodeId":"image","blockId":if repeat {"body"} else {"root"},
        "parentId":if repeat {json!("repeat")} else {Value::Null},"depth":if repeat {1} else {0},"orderKey":"a0","kind":"vision.findimage","nodeVersion":version,"args":args});
    let source = if repeat {
        format!("{}\n{}\n",json!({"flowSchemaVersion":1,"nodeId":"repeat","blockId":"root","parentId":null,"depth":0,"orderKey":"a0","kind":"control.repeat","nodeVersion":1,"args":{"times":7},"childBlocks":{"body":"body"}}),image).into_bytes()
    } else {
        serde_json::to_vec(&image).unwrap()
    };
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

fn arguments() -> Value {
    json!({"frameVariable":"frame","imagePath":"assets/images/a.png","tolerance":0,"similarityPermille":900,
        "region":{"left":0,"top":0,"right":720,"bottom":1280},"foundVariable":"found","xVariable":"x","yVariable":"y"})
}

#[test]
fn ordered_variable_region_compiles_as_values_not_source() {
    let mut args = arguments();
    args["direction"] = json!(3);
    args["leftVariable"] = json!("roiLeft");
    let bundle = compile(args, "1.7", 2).unwrap();
    let lua = String::from_utf8(bundle.main_lua).unwrap();
    assert!(lua.contains("Screen.findImages(__frame,"));
    assert!(lua.contains(",0,900,__vars[\"roiLeft\"],0,720,1280,3)"));
}

#[test]
fn old_nodes_and_migrated_nodes_keep_the_eight_argument_call() {
    for version in [1, 2] {
        let bundle = compile(arguments(), "1.5", version).unwrap();
        assert!(String::from_utf8(bundle.main_lua)
            .unwrap()
            .contains("__template,0,900,0,0,720,1280)"));
    }
}

#[test]
fn extended_options_reject_old_runtime_old_nodes_and_injected_variable_names() {
    let mut args = arguments();
    args["direction"] = json!(1);
    assert!(compile(args.clone(), "1.6", 2).is_err());
    assert!(compile(args.clone(), "1.7", 1).is_err());
    args["leftVariable"] = json!("x]; Input.tap(1,2)");
    assert!(compile(args, "1.7", 2).is_err());
    let mut args = arguments();
    args["direction"] = json!(5);
    assert!(compile(args, "1.7", 2).is_err());
}

#[test]
fn folders_freeze_sorted_resources_and_all_success_actions_are_rendered() {
    for (action, expected) in [
        ("none", "end;"),
        ("tap", "Input.tapScreen(__x,__y)"),
        ("hold", "Input.pointerDownScreen(__x,__y)"),
        ("tapWait", "Task.sleep(250)"),
        ("pressRelease", "Input.pointerUp()"),
    ] {
        let mut args = arguments();
        args["imageDirectory"] = json!("assets/images/");
        args["frequency"] = json!(3);
        args["autoCapture"] = json!(true);
        args["successAction"] = json!(action);
        args["actionDurationMs"] = json!(250);
        args["templateVariable"] = json!("matchedPath");
        args["scoreVariable"] = json!("score");
        args["imageVariable"] = json!("crop");
        let lua = String::from_utf8(compile(args, "1.7", 2).unwrap().main_lua).unwrap();
        assert!(
            lua.find("assets/images/a.png").unwrap() < lua.find("assets/images/z.png").unwrap()
        );
        assert!(lua.contains("__recognition_visit(\"main:image\", 3)"));
        assert!(lua.contains("Screen.cache(Screen.capture())"));
        assert!(lua.contains("Screen.crop(__frame"));
        assert!(lua.contains(expected));
    }
}

#[test]
fn invalid_extended_options_and_aliased_outputs_are_rejected() {
    for (key, value) in [
        ("frequency", json!(0)),
        ("frequency", json!(31)),
        ("actionDurationMs", json!(60001)),
        ("successAction", json!("shell")),
        ("imageDirectory", json!("assets/images/../")),
        ("imageVariable", json!("frame")),
        ("scoreVariable", json!("found")),
        ("leftVariable", json!("x")),
        ("imagePaths", json!(["assets/images/missing.png"])),
    ] {
        let mut args = arguments();
        args[key] = value;
        assert!(compile(args, "1.7", 2).is_err(), "{key}");
    }
}

#[test]
fn generated_frequency_skips_capture_input_and_clears_stale_results() {
    let mut args = arguments();
    args["autoCapture"] = json!(true);
    args["frequency"] = json!(3);
    args["successAction"] = json!("tap");
    args["imageVariable"] = json!("crop");
    let bundle = compile_repeated(args, "1.7", 2, true).unwrap();
    let lua = mlua::Lua::new();
    lua.load("System={elapsedRealtimeMillis=function() return 0 end}")
        .exec()
        .unwrap();
    lua.load(r"Prompt={configure=function() end}; captures=0; searches=0; clicks=0; crops=0; releases=0; Screen={capture=function() captures=captures+1; return 1 end,cache=function(frame) return frame end,findImages=function() searches=searches+1; if searches==2 then return nil end; return {x=10,y=20,width=2,height=2,path='assets/images/a.png',scorePermille=1000} end,crop=function() crops=crops+1; return 2 end,release=function() releases=releases+1 end}; Input={tapScreen=function(x,y) assert(x==10 and y==20); clicks=clicks+1 end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(lua.globals().get::<u32>("captures").unwrap(), 3);
    assert_eq!(lua.globals().get::<u32>("searches").unwrap(), 3);
    assert_eq!(lua.globals().get::<u32>("clicks").unwrap(), 2);
    assert_eq!(lua.globals().get::<u32>("crops").unwrap(), 2);
    assert_eq!(lua.globals().get::<u32>("releases").unwrap(), 4);
}

#[test]
fn declared_numeric_found_outputs_use_one_zero_and_old_implicit_outputs_keep_booleans() {
    for kind in [
        None,
        Some("integer"),
        Some("number"),
        Some("parameter:integer"),
        Some("parameter:boolean"),
    ] {
        let mut args = arguments();
        args["autoCapture"] = json!(true);
        args["frequency"] = json!(3);
        let bundle = compile_with_found_type(args, "1.7", 2, true, kind).unwrap();
        let lua = mlua::Lua::new();
        lua.load("Prompt={configure=function() end};System={elapsedRealtimeMillis=function() return 0 end};results={};searches=0;__autoscript_debug_event=function(kind,flow,path,value) if kind=='imageSearch' then table.insert(results,value) end end;Screen={capture=function() return 1 end,cache=function(frame) return frame end,release=function() end,findImages=function() searches=searches+1; if searches==2 then return nil end;return {x=10,y=20} end}").exec().unwrap();
        lua.load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        let results: mlua::Table = lua.globals().get("results").unwrap();
        if kind.is_some() && kind != Some("parameter:boolean") {
            assert_eq!(
                results
                    .sequence_values::<i64>()
                    .collect::<Result<Vec<_>, _>>()
                    .unwrap(),
                vec![1, 0, 0, 0, 0, 0, 1]
            );
        } else {
            assert_eq!(
                results
                    .sequence_values::<bool>()
                    .collect::<Result<Vec<_>, _>>()
                    .unwrap(),
                vec![true, false, false, false, false, false, true]
            );
        }
    }
    assert!(compile_with_found_type(arguments(), "1.7", 2, false, Some("string")).is_err());
    assert!(
        compile_with_found_type(arguments(), "1.7", 2, false, Some("parameter:string")).is_err()
    );
}
