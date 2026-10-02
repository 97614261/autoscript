use crate::{
    compile_project, verify_generation, FlowSource, GenerationBundle, SUPPORTED_NODE_VERSIONS,
};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions, ProjectManifest};
use serde_json::{json, Value};

fn manifest(variables: Value, params: Value) -> ProjectManifest {
    let mut manifest = json!({
        "formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.6",
        "projectId":"contract-test","name":"Contract","sourceMode":"visual","entryFlowId":"main",
        "flows":[
            {"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null},
            {"flowId":"child","path":"visual/flows/child.jsonl","rootBlockId":"block-child","params":[],"returns":null}
        ],"variables":[],"capabilities":["core.task"],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}
    });
    manifest["variables"] = variables;
    manifest["flows"][1]["params"] = params;
    parse_project_manifest(&serde_json::to_vec(&manifest).unwrap()).unwrap()
}

fn node(kind: &str, args: Value) -> Value {
    let mut node = json!({"kind":kind});
    node["args"] = args;
    node
}

fn sources(manifest: &ProjectManifest, main: &[Value], child: &[Value]) -> GenerationBundle {
    let texts = [main, child]
        .iter()
        .enumerate()
        .map(|(flow, nodes)| {
            nodes
                .iter()
                .enumerate()
                .map(|(index, node)| {
                    let id = if flow == 0 { "main" } else { "child" };
                    let mut node = node.clone();
                    let fields = node.as_object_mut().unwrap();
                    for (key, value) in
                        json!({"flowSchemaVersion":1,"nodeId":format!("{id}-{index}"),
                "blockId":format!("block-{id}"),"parentId":null,"depth":0,"nodeVersion":1,
                "orderKey":format!("a{index:02}")})
                        .as_object()
                        .unwrap()
                    {
                        fields.entry(key.clone()).or_insert(value.clone());
                    }
                    serde_json::to_string(&node).unwrap() + "\n"
                })
                .collect::<String>()
        })
        .collect::<Vec<_>>();
    let reports = ["main", "child"]
        .iter()
        .enumerate()
        .map(|(i, id)| {
            load_jsonl(
                texts[i].as_bytes(),
                LoadOptions {
                    flow_id: id,
                    root_block_id: if i == 0 { "block-main" } else { "block-child" },
                    flow_schema_version: 1,
                    supported_nodes: SUPPORTED_NODE_VERSIONS,
                },
            )
        })
        .collect::<Vec<_>>();
    let inputs = [
        FlowSource {
            flow_id: "main",
            exact_bytes: texts[0].as_bytes(),
            report: &reports[0],
        },
        FlowSource {
            flow_id: "child",
            exact_bytes: texts[1].as_bytes(),
            report: &reports[1],
        },
    ];
    let bundle = compile_project(manifest, &inputs).unwrap();
    let mut changed = manifest.clone();
    changed.debug_settings.popup_style.width_px += 1;
    assert!(verify_generation(
        &bundle.generation_json,
        &changed,
        &inputs,
        &bundle.main_lua,
        &bundle.source_map_json
    )
    .is_err());
    bundle
}

fn runtime() -> mlua::Lua {
    let lua = mlua::Lua::new();
    lua.load("Prompt={toast=function(message, style) last_style=style end}; logs={}; Log={info=function(text) table.insert(logs,text) end}").exec().unwrap();
    lua
}

fn log(name: &str) -> Value {
    node(
        "task.log",
        json!({"level":"info","message":"value","valueVariable":name}),
    )
}

#[test]
fn calculations_run_with_types_scopes_and_conversion() {
    let manifest = manifest(
        json!([
            {"name":"count","scope":"flow","flowId":"main","type":"integer"},
            {"name":"ratio","scope":"flow","flowId":"main","type":"number"},
            {"name":"label","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    let bundle = sources(
        &manifest,
        &[
            node(
                "variable.calculate",
                json!({"name":"count","expression":"2 + 3 * (4 - 1)"}),
            ),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"count + 1"}),
            ),
            node(
                "variable.calculate",
                json!({"name":"ratio","expression":"count / 5"}),
            ),
            node(
                "variable.calculate",
                json!({"name":"label","expression":"\"计数=\" .. text(count)"}),
            ),
            log("count"),
            log("ratio"),
            log("label"),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"int(\"-12.7\") + 13 % 5"}),
            ),
            log("count"),
            node(
                "variable.calculate",
                json!({"name":"label","expression":"\"\\u0000\\b\\f中文\""}),
            ),
            log("label"),
        ],
        &[],
    );
    let lua = runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    let logs: Vec<String> = lua.globals().get("logs").unwrap();
    assert_eq!(logs, ["12", "2.4", "计数=12", "-9", "\0\u{8}\u{c}中文"]);
}

#[test]
fn calculation_integer_boundaries_and_string_limit() {
    let manifest = manifest(
        json!([
            {"name":"count","scope":"flow","flowId":"main","type":"integer"},
            {"name":"text","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    let bundle = sources(
        &manifest,
        &[
            node(
                "variable.calculate",
                json!({"name":"count","expression":"-9223372036854775808"}),
            ),
            log("count"),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"int(\"9007199254740993\")"}),
            ),
            log("count"),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"count + 1"}),
            ),
            log("count"),
            node(
                "variable.set",
                json!({"name":"text","value":"x".repeat(4096)}),
            ),
            node(
                "variable.calculate",
                json!({"name":"text","expression":"text .. \"x\""}),
            ),
        ],
        &[],
    );
    let lua = runtime();
    let failure = lua
        .load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap_err();
    assert!(failure.to_string().contains("4096"));
    let logs: Vec<String> = lua.globals().get("logs").unwrap();
    assert_eq!(
        logs,
        [
            "-9223372036854775808",
            "9007199254740993",
            "9007199254740994"
        ]
    );
}

#[test]
fn calculation_compiler_rejects_unknown_scope_types_and_code_escape() {
    let manifest = manifest(
        json!([
            {"name":"result","scope":"flow","flowId":"main","type":"integer"},
            {"name":"foreign","scope":"flow","flowId":"child","type":"integer"},
            {"name":"frame","scope":"global","type":"image"},
            {"name":"label","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    for (target, expression) in [
        ("result", "foreign + 1"),
        ("result", "frame"),
        ("result", "label + 1"),
        ("result", "3 / 2"),
        ("frame", "0"),
        ("missing", "1"),
        ("result", "1; os.execute(\"x\")"),
    ] {
        let source = json!({"flowSchemaVersion":1,"nodeId":"calc","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"variable.calculate","nodeVersion":1,"depth":0,"args":{"name":target,"expression":expression}}).to_string();
        let options = LoadOptions {
            flow_id: "main",
            root_block_id: "block-main",
            flow_schema_version: 1,
            supported_nodes: SUPPORTED_NODE_VERSIONS,
        };
        let main = load_jsonl(source.as_bytes(), options);
        let child = load_jsonl(
            b"",
            LoadOptions {
                flow_id: "child",
                root_block_id: "block-child",
                ..options
            },
        );
        let failure = compile_project(
            &manifest,
            &[
                FlowSource {
                    flow_id: "main",
                    exact_bytes: source.as_bytes(),
                    report: &main,
                },
                FlowSource {
                    flow_id: "child",
                    exact_bytes: b"",
                    report: &child,
                },
            ],
        )
        .unwrap_err();
        assert!(failure.iter().any(|error| matches!(error, crate::CompileError::InvalidNodeArguments {node_id, ..} if node_id == "calc")), "{target}={expression}: {failure:?}");
    }
}

#[test]
fn calculations_fail_closed_on_runtime_errors() {
    let manifest = manifest(
        json!([
            {"name":"value","scope":"flow","flowId":"main","type":"integer"},
            {"name":"decimal","scope":"flow","flowId":"main","type":"number"}
        ]),
        json!([]),
    );
    for (target, expression, diagnostic) in [
        ("decimal", "1 / 0", "除以零"),
        ("value", "1 // 0", "除以零"),
        ("value", "1 % 0", "除以零"),
        ("value", "9223372036854775807 + 1", "溢出"),
        ("value", "int(\"-9223372036854775808\") - 1", "溢出"),
        ("value", "9223372036854775807 * 2", "溢出"),
        ("value", "-int(\"-9223372036854775808\")", "溢出"),
        ("value", "int(\"-9223372036854775808\") // -1", "溢出"),
        ("value", "int(\"oops\")", "十进制"),
        ("value", "int(\"9223372036854775808\")", "范围"),
        ("decimal", "1e308 * 10", "有限数值"),
    ] {
        let bundle = sources(
            &manifest,
            &[node(
                "variable.calculate",
                json!({"name":target,"expression":expression}),
            )],
            &[],
        );
        let lua = runtime();
        let failure = lua
            .load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap_err();
        assert!(
            failure.to_string().contains(diagnostic),
            "{expression}: {failure}"
        );
    }
}

#[test]
fn compiler_entry_fixture() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[node("task.noop", json!({}))],
        &[node("task.noop", json!({}))],
    );
    assert_eq!(
        std::str::from_utf8(&bundle.main_lua).unwrap(),
        include_str!("../../../apps/studio-android/src/test/resources/compiler-plugin-v2.lua")
    );
}

#[test]
fn designed_interface_binds_values_and_dispatches_real_plugin_events() {
    let mut manifest = manifest(
        json!([{ "name":"username","scope":"global","flowId":null,"type":"string" }]),
        json!([{ "name":"value","type":"string","required":true }]),
    );
    manifest.runtime_api = "1.7".into();
    manifest.capabilities.push("ui.control".into());
    manifest.runner_ui = Some(serde_json::from_value(json!({"description":null,"version":2,
        "pages":[{"id":"main","title":"Main","width":720,"height":960,"background":"#FFFFFFFF"}],
        "fields":[{"id":"name","label":"Name","kind":"text","required":false,"initialValue":"initial","minimum":null,"maximum":null,"options":[],
            "ui":{"control":"INPUT","pageId":"main","x":20,"y":20,"width":320,"height":72,"fontPx":28,"textColor":"#FF202938","background":"#FFFFFFFF","alignment":"center","visible":true,"enabled":true,"binding":"global:username","events":{"change":"flow:child"}}}]})).unwrap());
    flow_ir::validate_runner_ui(manifest.runner_ui.as_ref()).unwrap();
    let bundle = sources(&manifest, &[log("username")], &[log("value")]);
    let lua = runtime();
    lua.load("RunnerConfig={name='start'}; __ui_initial_globals={username='start'}; __ui_initial_locals={}; UI={setValue=function() end, getValue=function() return 'changed' end, waitEvent=function() if visited then error('test-finish') end; visited=true; return 'name:change' end}; Task={spawn=function(f) f(); return 1 end}").exec().unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    assert!(entry
        .call::<()>(())
        .unwrap_err()
        .to_string()
        .contains("test-finish"));
    assert_eq!(loop_logs(&lua), ["start", "changed"]);
    assert!(String::from_utf8(bundle.main_lua)
        .unwrap()
        .contains("Task.spawn(function() __flows[\"main\"]"));
}

#[test]
fn interface_read_write_blocks_compile_to_real_api() {
    let mut manifest = manifest(
        json!([{ "name":"value","scope":"global","flowId":null,"type":"string" }]),
        json!([]),
    );
    manifest.runtime_api = "1.7".into();
    manifest.capabilities.push("ui.control".into());
    manifest.runner_ui=Some(serde_json::from_value(json!({"description":null,"fields":[{"id":"name","label":"Name","kind":"text","required":false,"initialValue":"","minimum":null,"maximum":null,"options":[]}]})).unwrap());
    let bundle = sources(
        &manifest,
        &[
            node("ui.set", json!({"controlId":"name","value":"hello"})),
            node(
                "ui.get",
                json!({"controlId":"name","resultVariable":"value"}),
            ),
            node(
                "ui.command",
                json!({"controlId":"name","operation":"visible","value":"false"}),
            ),
            log("value"),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("UI={setValue=function(id,v) value=v end,getValue=function(id) return value end,command=function(id,op,v) assert(op=='visible' and v=='false') end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), ["hello"]);
}

#[test]
fn disabled_container_skips_invalid_arguments_and_children_without_deleting_them() {
    let mut owner = loop_node(
        "owner",
        None,
        "block-main",
        0,
        "control.repeat",
        json!({"times":-1}),
        Some("body"),
    );
    owner["disabled"] = json!(true);
    let child = loop_node(
        "child-log",
        Some("owner"),
        "body",
        1,
        "task.log",
        json!({"level":"info","message":"must-not-run"}),
        None,
    );
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[
            owner,
            child,
            node("task.log", json!({"level":"info","message":"active"})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), ["active"]);
    assert!(!String::from_utf8(bundle.main_lua)
        .unwrap()
        .contains("must-not-run"));
}

#[test]
fn run_to_selected_checkpoint_keeps_prior_side_effects_and_pauses_before_target() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[
            node("task.log", json!({"level":"info","message":"before"})),
            node("task.log", json!({"level":"info","message":"selected"})),
            node("task.log", json!({"level":"info","message":"after"})),
        ],
        &[],
    );
    let lua = runtime();
    lua.globals()
        .set("__autoscript_debug_capture", true)
        .unwrap();
    lua.globals().set("__autoscript_debug_step", false).unwrap();
    lua.globals()
        .set("__autoscript_debug_target_flow", "main")
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_target_node", "main-1")
        .unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    assert_eq!(loop_logs(&lua), ["before"]);
    let snapshot: mlua::Table = lua.globals().get("__autoscript_debug_snapshot").unwrap();
    assert_eq!(snapshot.get::<String>("nodeId").unwrap(), "main-1");
    thread.resume::<String>(()).unwrap();
    assert_eq!(loop_logs(&lua), ["before", "selected"]);
    lua.globals().set("__autoscript_debug_step", false).unwrap();
    thread.resume::<()>(()).unwrap();
    assert_eq!(loop_logs(&lua), ["before", "selected", "after"]);
}

#[test]
fn first_step_executes_selected_node_and_pauses_at_next_checkpoint() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[
            node("task.log", json!({"level":"info","message":"before"})),
            node("task.log", json!({"level":"info","message":"selected"})),
            node("task.log", json!({"level":"info","message":"after"})),
        ],
        &[],
    );
    let lua = runtime();
    lua.globals()
        .set("__autoscript_debug_capture", true)
        .unwrap();
    lua.globals().set("__autoscript_debug_step", false).unwrap();
    lua.globals()
        .set("__autoscript_debug_target_flow", "main")
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_target_node", "main-1")
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_execute_target", true)
        .unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    assert_eq!(loop_logs(&lua), ["before", "selected"]);
    let snapshot: mlua::Table = lua.globals().get("__autoscript_debug_snapshot").unwrap();
    assert_eq!(snapshot.get::<String>("nodeId").unwrap(), "main-2");
    thread.resume::<()>(()).unwrap();
    assert_eq!(loop_logs(&lua), ["before", "selected", "after"]);
    assert_eq!(thread.status(), mlua::prelude::LuaThreadStatus::Finished);
}

#[test]
fn first_step_on_last_node_finishes_without_an_extra_empty_pause() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[node("task.log", json!({"level":"info","message":"last"}))],
        &[],
    );
    let lua = runtime();
    lua.globals()
        .set("__autoscript_debug_capture", true)
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_target_flow", "main")
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_target_node", "main-0")
        .unwrap();
    lua.globals()
        .set("__autoscript_debug_execute_target", true)
        .unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    thread.resume::<()>(()).unwrap();
    assert_eq!(loop_logs(&lua), ["last"]);
    assert_eq!(thread.status(), mlua::prelude::LuaThreadStatus::Finished);
}

fn loop_node(
    id: &str,
    parent: Option<&str>,
    block: &str,
    depth: u32,
    kind: &str,
    args: Value,
    body: Option<&str>,
) -> Value {
    let mut value = node(kind, args);
    value["nodeId"] = json!(id);
    value["parentId"] = json!(parent);
    value["blockId"] = json!(block);
    value["depth"] = json!(depth);
    if let Some(body) = body {
        value["childBlocks"] = json!({"body":body});
    }
    value
}
fn loop_runtime() -> mlua::Lua {
    let lua = runtime();
    lua.load("clock=0; System={elapsedRealtimeMillis=function() return clock end}; Task={sleep=function(ms) clock=clock+ms end}").exec().unwrap();
    lua
}
fn loop_logs(lua: &mlua::Lua) -> Vec<String> {
    lua.globals()
        .get::<mlua::Table>("logs")
        .unwrap()
        .sequence_values::<String>()
        .map(Result::unwrap)
        .collect()
}

#[test]
fn positional_loop_metrics_read_current_position_and_nearest_nested_loop() {
    let project = manifest(
        json!([
            {"name":"n","scope":"flow","flowId":"main","type":"integer"},
            {"name":"seconds","scope":"flow","flowId":"main","type":"number"}
        ]),
        json!([]),
    );
    let main = vec![
        loop_node(
            "outer",
            None,
            "block-main",
            0,
            "control.repeat",
            json!({"times":2}),
            Some("outer-body"),
        ),
        loop_node(
            "inner",
            Some("outer"),
            "outer-body",
            1,
            "control.repeat",
            json!({"times":2}),
            Some("inner-body"),
        ),
        loop_node(
            "count",
            Some("inner"),
            "inner-body",
            2,
            "control.loopmetric",
            json!({"metric":"count","name":"n"}),
            None,
        ),
        loop_node(
            "log",
            Some("inner"),
            "inner-body",
            2,
            "task.log",
            json!({"level":"info","message":"value","valueVariable":"n"}),
            None,
        ),
        loop_node(
            "sleep",
            Some("inner"),
            "inner-body",
            2,
            "task.sleep",
            json!({"milliseconds":100}),
            None,
        ),
        loop_node(
            "elapsed",
            Some("inner"),
            "inner-body",
            2,
            "control.loopmetric",
            json!({"metric":"elapsed","name":"seconds","unit":"seconds"}),
            None,
        ),
        loop_node(
            "elapsedlog",
            Some("inner"),
            "inner-body",
            2,
            "task.log",
            json!({"level":"info","message":"elapsed","valueVariable":"seconds"}),
            None,
        ),
        loop_node(
            "outerCount",
            Some("outer"),
            "outer-body",
            1,
            "control.loopmetric",
            json!({"metric":"count","name":"n"}),
            None,
        ),
        loop_node(
            "outerLog",
            Some("outer"),
            "outer-body",
            1,
            "task.log",
            json!({"level":"info","message":"outer","valueVariable":"n"}),
            None,
        ),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = loop_runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(
        loop_logs(&lua),
        vec!["1", "0.1", "2", "0.2", "1", "1", "0.1", "2", "0.2", "2"]
    );
}

#[test]
fn loop_checks_break_only_nearest_loop_at_their_execution_position() {
    let project = manifest(json!([]), json!([]));
    let main = vec![
        loop_node(
            "outer",
            None,
            "block-main",
            0,
            "control.repeat",
            json!({"times":2}),
            Some("outer-body"),
        ),
        loop_node(
            "inner",
            Some("outer"),
            "outer-body",
            1,
            "control.repeat",
            json!({"times":5}),
            Some("inner-body"),
        ),
        loop_node(
            "check",
            Some("inner"),
            "inner-body",
            2,
            "control.loopcheck",
            json!({"metric":"count","limit":2}),
            None,
        ),
        loop_node(
            "innerLog",
            Some("inner"),
            "inner-body",
            2,
            "task.log",
            json!({"level":"info","message":"inner"}),
            None,
        ),
        loop_node(
            "outerLog",
            Some("outer"),
            "outer-body",
            1,
            "task.log",
            json!({"level":"info","message":"outer"}),
            None,
        ),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = loop_runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), vec!["inner", "outer", "inner", "outer"]);
    let main = vec![
        loop_node(
            "loop",
            None,
            "block-main",
            0,
            "control.repeat",
            json!({"times":5}),
            Some("body"),
        ),
        loop_node(
            "sleep",
            Some("loop"),
            "body",
            1,
            "task.sleep",
            json!({"milliseconds":50}),
            None,
        ),
        loop_node(
            "check",
            Some("loop"),
            "body",
            1,
            "control.loopcheck",
            json!({"metric":"elapsed","limit":0.1,"unit":"seconds"}),
            None,
        ),
        loop_node(
            "log",
            Some("loop"),
            "body",
            1,
            "task.log",
            json!({"level":"info","message":"before timeout"}),
            None,
        ),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = loop_runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), vec!["before timeout"]);
    assert_eq!(lua.globals().get::<u64>("clock").unwrap(), 100);
}

#[test]
fn loop_duration_unit_keeps_legacy_milliseconds_and_accepts_explicit_seconds() {
    for (duration, unit) in [
        (100.0, None),
        (0.1, Some("seconds")),
        (0.0016666666666666668, Some("minutes")),
    ] {
        let project = manifest(
            json!([{"name":"duration","scope":"flow","flowId":"main","type":"number"}]),
            json!([]),
        );
        let mut args = json!({"variable":"unused","operator":"equals","value":true,"always":true,"durationVariable":"duration","maxIterations":100});
        if let Some(unit) = unit {
            args["durationUnit"] = json!(unit);
        }
        let main = vec![
            node("variable.set", json!({"name":"duration","value":duration})),
            loop_node(
                "loop",
                None,
                "block-main",
                0,
                "control.while",
                args,
                Some("body"),
            ),
            loop_node(
                "sleep",
                Some("loop"),
                "body",
                1,
                "task.sleep",
                json!({"milliseconds":10}),
                None,
            ),
        ];
        let bundle = sources(&project, &main, &[]);
        let lua = loop_runtime();
        lua.load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(lua.globals().get::<u64>("clock").unwrap(), 100);
    }
}

#[test]
fn while_metrics_and_variable_checks_execute_with_strict_runtime_bounds() {
    let project = manifest(
        json!([
            {"name":"n","scope":"flow","flowId":"main","type":"integer"},
            {"name":"limit","scope":"flow","flowId":"main","type":"number"}
        ]),
        json!([]),
    );
    for (metric, limit, unit, expected) in [
        ("count", 2.0, "milliseconds", vec!["1", "2"]),
        ("elapsed", 0.1, "seconds", vec!["1", "2"]),
        ("elapsed", 0.0, "seconds", vec![]),
        ("elapsed", -1.0, "seconds", vec![]),
        ("elapsed", 86401.0, "seconds", vec![]),
        ("elapsed", 0.0001, "seconds", vec![]),
    ] {
        // Count thresholds require an integer declaration, not just an integral float value.
        let mut typed_project = project.clone();
        if metric == "count" {
            typed_project.variables[1].value_type = flow_ir::ProjectVariableType::Integer;
        }
        let limit_value = if metric == "count" {
            json!(2)
        } else {
            json!(limit)
        };
        let main = vec![
            node("variable.set", json!({"name":"limit","value":limit_value})),
            loop_node(
                "loop",
                None,
                "block-main",
                0,
                "control.while",
                json!({"variable":"unused","operator":"equals","value":true,"always":true,"maxIterations":4}),
                Some("body"),
            ),
            loop_node(
                "read",
                Some("loop"),
                "body",
                1,
                "control.loopmetric",
                json!({"metric":"count","name":"n"}),
                None,
            ),
            loop_node(
                "log",
                Some("loop"),
                "body",
                1,
                "task.log",
                json!({"level":"info","message":"value","valueVariable":"n"}),
                None,
            ),
            loop_node(
                "wait",
                Some("loop"),
                "body",
                1,
                "task.sleep",
                json!({"milliseconds":50}),
                None,
            ),
            loop_node(
                "check",
                Some("loop"),
                "body",
                1,
                "control.loopcheck",
                json!({"metric":metric,"limit":0,"limitVariable":"limit","unit":unit}),
                None,
            ),
        ];
        let bundle = sources(&typed_project, &main, &[]);
        let lua = loop_runtime();
        let result = lua
            .load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(());
        if expected.is_empty() {
            assert!(result.unwrap_err().to_string().contains("循环检查阈值"));
        } else {
            result.unwrap();
            assert_eq!(loop_logs(&lua), expected);
            assert_eq!(lua.globals().get::<u64>("clock").unwrap(), 100);
        }
    }
}

#[test]
fn loop_runtime_rejects_bad_variable_bounds_and_safety_limit_still_fires() {
    for duration in [0.0, -1.0, 86400001.0, 0.001, 1.5] {
        let project = manifest(
            json!([{"name":"duration","scope":"flow","flowId":"main","type":"number"}]),
            json!([]),
        );
        let main = vec![
            node("variable.set", json!({"name":"duration","value":duration})),
            loop_node(
                "loop",
                None,
                "block-main",
                0,
                "control.while",
                json!({"variable":"unused","operator":"equals","value":true,"always":true,"durationVariable":"duration","maxIterations":2}),
                Some("body"),
            ),
        ];
        let bundle = sources(&project, &main, &[]);
        let lua = loop_runtime();
        assert!(lua
            .load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap_err()
            .to_string()
            .contains("durationVariable"));
    }
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[loop_node(
            "loop",
            None,
            "block-main",
            0,
            "control.while",
            json!({"variable":"unused","operator":"equals","value":true,"always":true,"maxIterations":2}),
            Some("body"),
        )],
        &[],
    );
    let lua = loop_runtime();
    assert!(lua
        .load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap_err()
        .to_string()
        .contains("iteration limit exceeded"));
}

#[test]
fn compiler_rejects_root_metrics_and_invalid_metric_target_types() {
    let project = manifest(
        json!([{"name":"n","scope":"flow","flowId":"main","type":"integer"}]),
        json!([]),
    );
    for (parent, block, depth, args) in [
        (None, "block-main", 0, json!({"metric":"count","name":"n"})),
        (
            Some("loop"),
            "body",
            1,
            json!({"metric":"elapsed","name":"n","unit":"seconds"}),
        ),
        (
            Some("loop"),
            "body",
            1,
            json!({"metric":"count","name":"missing"}),
        ),
    ] {
        let values = [
            loop_node(
                "loop",
                None,
                "block-main",
                0,
                "control.repeat",
                json!({"times":2}),
                Some("body"),
            ),
            loop_node(
                "metric",
                parent,
                block,
                depth,
                "control.loopmetric",
                args,
                None,
            ),
        ];
        let mut text = String::new();
        for (index, mut value) in values.into_iter().enumerate() {
            value["flowSchemaVersion"] = json!(1);
            value["nodeVersion"] = json!(1);
            value["orderKey"] = json!(format!("a{index}"));
            text.push_str(&format!("{value}\n"));
        }
        let report = load_jsonl(
            text.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let child = load_jsonl(
            b"",
            LoadOptions {
                flow_id: "child",
                root_block_id: "block-child",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let inputs = [
            FlowSource {
                flow_id: "main",
                exact_bytes: text.as_bytes(),
                report: &report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: b"",
                report: &child,
            },
        ];
        assert!(compile_project(&project,&inputs).unwrap_err().iter().any(|error| matches!(error,crate::CompileError::InvalidNodeArguments{node_id,..} if node_id=="metric")));
    }
}

#[test]
fn visual_jobs_compile_to_real_callback_api_with_frozen_parameters() {
    let mut project = manifest(
        json!([]),
        json!([{ "name":"count","type":"integer","required":true }]),
    );
    project.runtime_api = "1.7".into();
    let bundle = sources(
        &project,
        &[
            node(
                "task.spawn",
                json!({"targetFlowId":"child","arguments":{"count":3},"resultVariable":"taskId"}),
            ),
            node(
                "timer.every",
                json!({"targetFlowId":"child","arguments":{"count":4},"resultVariable":"timerId","periodMs":100}),
            ),
            node("task.cancel", json!({"idVariable":"taskId"})),
            node("timer.cancel", json!({"idVariable":"timerId"})),
        ],
        &[log("count")],
    );
    let lua = runtime();
    lua.load("calls={}; Task={spawn=function(callback) table.insert(calls,'spawn'); callback(); return 2 end,cancel=function(id) assert(id==2) end}; Timer={every=function(ms,callback) assert(ms==100); table.insert(calls,'timer'); callback(); callback(); return 7 end,cancel=function(id) assert(id==7) end}").exec().unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    entry.call::<()>(()).unwrap();
    let logs: mlua::Table = lua.globals().get("logs").unwrap();
    assert_eq!(logs.len().unwrap(), 3);
    assert!(logs.get::<String>(1).unwrap().contains('3'));
    assert!(logs.get::<String>(2).unwrap().contains('4'));
}

#[test]
fn compiled_debugger_checkpoints_suspend_before_nodes_and_resume_without_extra_work() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[
            node("variable.set", json!({"name":"score","value":12})),
            log("score"),
        ],
        &[],
    );
    let lua = runtime();
    lua.globals()
        .set("__autoscript_debug_capture", true)
        .unwrap();
    lua.globals().set("__autoscript_debug_step", true).unwrap();
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    let snapshot: mlua::Table = lua.globals().get("__autoscript_debug_snapshot").unwrap();
    let values: mlua::Table = snapshot.get("locals").unwrap();
    assert_eq!(values.get::<i64>("score").unwrap(), 12);
    lua.globals().set("__autoscript_debug_step", false).unwrap();
    thread.resume::<()>(()).unwrap();
    let logs: mlua::Table = lua.globals().get("logs").unwrap();
    assert_eq!(logs.get::<String>(1).unwrap(), "12");
}

#[test]
fn global_sharing_local_shadowing_and_per_call_reset_execute() {
    let manifest = manifest(
        json!([
            {"name":"shared","scope":"global","type":"integer"},
            {"name":"shadow","scope":"global","type":"integer"},
            {"name":"shadow","scope":"flow","flowId":"child","type":"integer"}
        ]),
        json!([]),
    );
    let bundle = sources(
        &manifest,
        &[
            log("shared"),
            node("variable.set", json!({"name":"shared","value":7})),
            node("variable.set", json!({"name":"shadow","value":11})),
            node("flow.call", json!({"targetFlowId":"child","arguments":{}})),
            node("flow.call", json!({"targetFlowId":"child","arguments":{}})),
            log("shared"),
            log("shadow"),
        ],
        &[
            log("shadow"),
            node(
                "variable.copy",
                json!({"sourceName":"shared","name":"shadow"}),
            ),
            log("shadow"),
            node("variable.set", json!({"name":"shared","value":9})),
        ],
    );
    let lua = runtime();
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    for _ in 0..2 {
        entry.call::<()>(()).unwrap();
    }
    let logs: mlua::Table = lua.globals().get("logs").unwrap();
    let got = logs
        .sequence_values::<String>()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert_eq!(
        got,
        ["0", "0", "7", "0", "9", "9", "11", "0", "0", "7", "0", "9", "9", "11"]
    );
}

#[test]
fn indexed_arguments_override_named_defaults_and_are_consumed() {
    let manifest = manifest(
        json!([]),
        json!([{"name":"count","type":"integer","required":true}]),
    );
    let bundle = sources(
        &manifest,
        &[
            node("flow.argument.set", json!({"index":1,"value":9})),
            node("flow.call", json!({"targetFlowId":"child","arguments":{}})),
            node(
                "flow.return.get",
                json!({"index":1,"targetVariable":"answer"}),
            ),
            log("answer"),
            node(
                "flow.call",
                json!({"targetFlowId":"child","arguments":{"count":3}}),
            ),
            node(
                "flow.return.get",
                json!({"index":1,"targetVariable":"answer"}),
            ),
            log("answer"),
        ],
        &[
            node(
                "flow.argument.get",
                json!({"index":1,"targetVariable":"received"}),
            ),
            node(
                "flow.return.set",
                json!({"index":1,"valueVariable":"received"}),
            ),
        ],
    );
    let lua = runtime();
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    entry.call::<()>(()).unwrap();
    let logs: mlua::Table = lua.globals().get("logs").unwrap();
    assert_eq!(
        logs.sequence_values::<String>()
            .collect::<Result<Vec<_>, _>>()
            .unwrap(),
        ["9", "3"]
    );
    let tail = "  __flows[\"main\"](__entry_args or {})\n  return nil\nend\n";
    assert!(std::str::from_utf8(&bundle.main_lua)
        .unwrap()
        .ends_with(tail));
    // Editor selection replaces exactly the compiler's entry tail, never a substring in nodes.
    let selected = std::str::from_utf8(&bundle.main_lua)
        .unwrap()
        .strip_suffix(tail)
        .unwrap()
        .to_owned()
        + "  __flows[\"child\"](__entry_args or {})\n  return nil\nend\n";
    let child: mlua::Function = lua.load(selected).eval().unwrap();
    assert!(child.call::<()>(()).is_err()); // required parameter still enforced
}

#[test]
fn missing_dynamic_required_argument_and_wrong_variable_type_fail_at_runtime() {
    let manifest = manifest(
        json!([{ "name":"typed","scope":"global","type":"integer"}]),
        json!([{"name":"count","type":"integer","required":true}]),
    );
    let bundle = sources(
        &manifest,
        &[
            node("flow.call", json!({"targetFlowId":"child","arguments":{}})),
            node("flow.argument.set", json!({"index":1,"value":9})),
        ],
        &[node("task.noop", json!({}))],
    );
    let lua = runtime();
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    assert!(entry
        .call::<()>(())
        .unwrap_err()
        .to_string()
        .contains("参数 count"));
    let bundle = sources(
        &manifest,
        &[
            node("variable.set", json!({"name":"text","value":"wrong"})),
            node("variable.copy", json!({"sourceName":"text","name":"typed"})),
        ],
        &[node("task.noop", json!({}))],
    );
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    assert!(entry
        .call::<()>(())
        .unwrap_err()
        .to_string()
        .contains("变量类型不匹配"));
}
