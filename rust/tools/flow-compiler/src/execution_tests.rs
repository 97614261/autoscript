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

#[test]
fn compiler_checkpoint_keeps_old_payload_on_runtime_without_details() {
    let bundle = sources(
        &manifest(json!([]), json!([])),
        &[node("task.noop", json!({}))],
        &[],
    );
    let lua = runtime();
    lua.globals()
        .set("__autoscript_run_observation", true)
        .unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    let payload = thread.resume::<mlua::MultiValue>(()).unwrap();
    assert_eq!(payload.len(), 5);
    assert_eq!(
        payload[0].as_string().unwrap().to_str().unwrap(),
        "__AUTOSCRIPT_RUN_CONTEXT_V1"
    );
    assert_eq!(payload[1].as_string().unwrap().to_str().unwrap(), "main");
    assert_eq!(payload[2].as_string().unwrap().to_str().unwrap(), "main-0");
    thread.resume::<()>(()).unwrap();
    assert!(!thread.is_resumable());
}

#[test]
fn runtime_error_reports_visual_row_in_nested_plugin_with_comments() {
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    let mut project = manifest(
        json!([{"name":"count","scope":"global","type":"number"}]),
        json!([]),
    );
    project.flows[1].path = "visual/flows/技能'测试.jsonl".into();
    let mut comment = node(
        "task.comment",
        json!({"message":"disabled row still has a number"}),
    );
    comment["disabled"] = json!(true);
    let mut repeat = node("control.repeat", json!({"times":1}));
    repeat["childBlocks"] = json!({"body":"child-body"});
    let mut failure = node(
        "variable.calculate",
        json!({"name":"count","expression":"1/0"}),
    );
    failure["blockId"] = json!("child-body");
    failure["parentId"] = json!("child-1");
    failure["depth"] = json!(1);
    let bundle = sources(
        &project,
        &[
            comment.clone(),
            node("flow.call", json!({"targetFlowId":"child","arguments":{}})),
        ],
        &[comment, repeat, failure],
    );
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(720, 1280),
    )
    .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "visual-row-diagnostic")
        .unwrap();
    for _ in 0..16 {
        runtime.drive().unwrap();
        if runtime.task_state(task) == Some(TaskState::Failed) {
            break;
        }
    }
    assert_eq!(runtime.task_state(task), Some(TaskState::Failed));
    let failure = runtime.task_failure(task).unwrap();
    assert!(
        failure
            .message
            .contains("插件 技能'测试 (child) · 第3行 · 变量计算 · 积木 child-2"),
        "{failure:?}"
    );
    assert!(!failure.message.contains("插件 main"));
    assert!(failure.message.contains("stack traceback"));
}

#[test]
fn legacy_color_capture_keeps_rgb_tolerances_and_releases_private_frames() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["vision.pixel.legacy", "screen.capture"].map(str::to_owned));
    let operation = node(
        "legacy.getrectcolornum",
        json!({"frameVariable":"unused", "autoCapture":true,
        "region":{"left":3,"top":4,"width":5,"height":6},
        "colors":[{"rgb":66051,"toleranceRed":4,"toleranceGreen":5,"toleranceBlue":6}],
        "resultVariable":"count"}),
    );
    let bundle = sources(&project, &[operation, log("count")], &[]);
    for failing in [false, true] {
        let lua = runtime();
        lua.globals().set("failing", failing).unwrap();
        lua.load(
            r#"
            captured=0;released=0
            Screen={capture=function() captured=captured+1;return 7 end,
                cache=function(f) assert(f==7);return f end,
                release=function(f) assert(f==7);released=released+1 end}
            Legacy={getRectColorNum=function(f,l,t,w,h,colors)
                assert(f==7 and l==3 and t==4 and w==5 and h==6)
                assert(colors=='(1,2,3)-(4,5,6)')
                if failing then error('expected-count-failure') end
                return 19
            end}
        "#,
        )
        .exec()
        .unwrap();
        let result = lua
            .load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(());
        assert_eq!(result.is_err(), failing);
        assert_eq!(lua.globals().get::<i64>("captured").unwrap(), 1);
        assert_eq!(lua.globals().get::<i64>("released").unwrap(), 1);
        if !failing {
            assert!(lua
                .globals()
                .get::<mlua::Table>("logs")
                .unwrap()
                .get::<String>(1)
                .unwrap()
                .ends_with("19"));
        }
    }
}

#[test]
fn color_capture_uses_a_private_frame_and_releases_on_success_and_failure() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["vision.pixel", "screen.capture"].map(str::to_owned));
    let main = [
        node(
            "vision.countcolor",
            json!({"frameVariable":"frame","autoCapture":true,
        "rgb":16711680,"tolerance":0,"region":{"left":0,"top":0,"right":100,"bottom":100},
        "limit":0,"resultVariable":"count"}),
        ),
        log("count"),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = runtime();
    lua.load("captured=0; released=0; Screen={colorLimitsVersion=1, capture=function() captured=captured+1; return 7 end, cache=function(f) return f end, release=function(f) assert(f==7); released=released+1 end, countColor=function(f,rgb,t,l,t0,r,b,limit) assert(f==7 and limit==0); return 10000 end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(lua.globals().get::<i64>("captured").unwrap(), 1);
    assert_eq!(lua.globals().get::<i64>("released").unwrap(), 1);
    assert!(lua
        .load("return tostring(logs[1])")
        .eval::<String>()
        .unwrap()
        .contains("10000"));
    lua.load("Screen.countColor=function() error('count failed') end")
        .exec()
        .unwrap();
    assert!(lua
        .load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .is_err());
    assert_eq!(lua.globals().get::<i64>("released").unwrap(), 2);
    let mut borrowed = main[0].clone();
    borrowed["args"]["autoCapture"] = json!(false);
    let saved = sources(&project, &[borrowed], &[]);
    assert!(!String::from_utf8(saved.main_lua)
        .unwrap()
        .contains("local __color_frame="));
}

#[test]
fn color_capture_outputs_execute_with_typed_json_at_the_full_list_boundary() {
    let mut project = manifest(
        json!([
            {"name":"color","scope":"global","type":"integer"},
            {"name":"found","scope":"global","type":"integer"},
            {"name":"x","scope":"global","type":"integer"},
            {"name":"y","scope":"global","type":"integer"},
            {"name":"same","scope":"global","type":"integer"},
            {"name":"count","scope":"global","type":"integer"},
            {"name":"points","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["vision.pixel", "screen.capture"].map(str::to_owned));
    let region = json!({"left":0,"top":0,"right":100,"bottom":100});
    let main = [
        node(
            "vision.getcolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "point":{"x":2,"y":3},"resultVariable":"color"}),
        ),
        node(
            "vision.findcolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "rgb":255,"tolerance":0,"region":region,
            "foundVariable":"found","xVariable":"x","yVariable":"y"}),
        ),
        node(
            "vision.comparecolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "point":{"x":2,"y":3},"rgb":255,"tolerance":0,"resultVariable":"same"}),
        ),
        node(
            "vision.findmulticolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "anchorRgb":255,"anchorTolerance":0,"samples":[{"x":1,"y":0,"rgb":255,"tolerance":0}],
            "region":region,"foundVariable":"found","xVariable":"x","yVariable":"y"}),
        ),
        node(
            "vision.countcolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "rgb":255,"tolerance":0,"region":region,"limit":0,"resultVariable":"count"}),
        ),
        node(
            "vision.findallcolor",
            json!({"frameVariable":"unused","autoCapture":true,
            "rgb":255,"tolerance":0,"region":region,"limit":2048,"resultVariable":"points"}),
        ),
        log("points"),
        log("count"),
        log("same"),
        log("color"),
        log("found"),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = runtime();
    lua.load(r#"
        captured=0; released=0
        local function frame(f) assert(f==7) end
        Screen={colorLimitsVersion=1,
            capture=function() captured=captured+1; return 7 end,
            cache=function(f) frame(f); return f end,
            release=function(f) frame(f); released=released+1 end,
            getColor=function(f,x,y) frame(f); assert(x==2 and y==3); return 255 end,
            findColor=function(f) frame(f); return {x=2,y=3} end,
            compareColor=function(f) frame(f); return true end,
            findMultiColor=function(f) frame(f); return {x=2,y=3} end,
            countColor=function(f,rgb,t,l,t0,r,b,limit) frame(f); assert(limit==0); return 10000 end,
            findAllColor=function(f,rgb,t,l,t0,r,b,limit)
                frame(f); assert(limit==2048); local points={}
                for i=1,limit do points[i]={x=i,y=32768} end
                return points
            end}
    "#).exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    let logs = lua.globals().get::<mlua::Table>("logs").unwrap();
    let first = logs.get::<String>(1).unwrap();
    let encoded = &first[first.find('[').unwrap()..];
    assert!(encoded.len() <= 65536);
    let points: Vec<Value> = serde_json::from_str(encoded).unwrap();
    assert_eq!(points.len(), 2048);
    assert_eq!(points[2047], json!({"x":2048,"y":32768}));
    for (index, value) in [(2, "10000"), (3, "1"), (4, "255"), (5, "1")] {
        assert!(logs.get::<String>(index).unwrap().ends_with(value));
    }
    assert_eq!(lua.globals().get::<i64>("captured").unwrap(), 6);
    assert_eq!(lua.globals().get::<i64>("released").unwrap(), 6);
}

#[test]
fn chinese_keys_calculations_parameters_jumps_and_empty_plugins_execute() {
    let mut project = manifest(
        json!([
            {"name":"剩余次数","scope":"global","type":"integer"},
            {"name":"识别结果","scope":"global","type":"string"}
        ]),
        json!([{ "name":"输入值", "type":"integer", "required":false }]),
    );
    project.runtime_api = "1.8".into();
    let bundle = sources(
        &project,
        &[
            node("variable.set", json!({"name":"剩余次数","value":5})),
            node(
                "variable.calculate",
                json!({"name":"剩余次数","expression":"剩余次数 - 1"}),
            ),
            node("control.goto", json!({"name":"已完成"})),
            node("variable.set", json!({"name":"剩余次数","value":0})),
            node("control.label", json!({"name":"已完成"})),
            node(
                "task.log",
                json!({"level":"info","message":"count","valueVariable":"剩余次数"}),
            ),
            node(
                "flow.call",
                json!({"targetFlowId":"child","arguments":{"输入值":7}}),
            ),
        ],
        &[node(
            "task.log",
            json!({"level":"info","message":"parameter","valueVariable":"输入值"}),
        )],
    );
    let lua = runtime();
    lua.load(bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert!(lua
        .load("return tostring(logs[1])")
        .eval::<String>()
        .unwrap()
        .contains('4'));
    assert!(lua
        .load("return tostring(logs[2])")
        .eval::<String>()
        .unwrap()
        .contains('7'));
    let empty = sources(&manifest(json!([]), json!([])), &[], &[]);
    lua.load(empty.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
}

#[test]
fn runtime_recording_and_audio_blocks_compile_and_execute_fixed_wrappers() {
    let mut project = manifest(
        json!([
            {"name":"recording","scope":"global","type":"string"},
            {"name":"volume","scope":"global","type":"integer"}
        ]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["input.basic", "audio.control", "storage.data"].map(str::to_owned));
    let bundle = sources(
        &project,
        &[
            node(
                "input.record",
                json!({"durationMs":1000,"eventBudget":128,"resultVariable":"recording"}),
            ),
            node(
                "input.playrecording",
                json!({"recordingVariable":"recording"}),
            ),
            node("audio.play", json!({"path":"audio/test.wav"})),
            node("audio.getvolume", json!({"resultVariable":"volume"})),
            node("audio.setvolume", json!({"volume":25})),
            node("audio.stop", json!({})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("calls={}; Input={record=function(duration,budget) assert(duration==1000 and budget==128); return 'recorded' end,playRecording=function(value) assert(value=='recorded'); table.insert(calls,'replay') end}; Audio={play=function(path) assert(path=='audio/test.wav'); table.insert(calls,'play') end,getVolume=function() return 37 end,setVolume=function(value) assert(value==25); table.insert(calls,'volume') end,stop=function() table.insert(calls,'stop') end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(
        lua.load("return table.concat(calls,',')")
            .eval::<String>()
            .unwrap(),
        "replay,play,volume,stop"
    );
}

#[test]
fn platform_blocks_compile_declared_text_sources_without_input_or_ui_capability() {
    let mut project = manifest(
        json!([{"name":"result","scope":"global","type":"string"}]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["device.clipboard", "device.apps"].map(str::to_owned));
    let bundle = sources(
        &project,
        &[
            node("clipboard.gettext", json!({"resultVariable":"result"})),
            node(
                "clipboard.settext",
                json!({"text":"","textVariable":"result"}),
            ),
            node(
                "app.info",
                json!({"packageName":"com.android.settings","resultVariable":"result"}),
            ),
            node("app.open", json!({"packageName":"com.android.settings"})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("Clipboard={getText=function() return '中文' end,setText=function(text) assert(text=='中文') end}; Apps={info=function(package) assert(package=='com.android.settings'); return '{\"visible\":false}' end,open=function(package) assert(package=='com.android.settings') end}").exec().unwrap();
    lua.load(bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
}

#[test]
fn floating_blocks_compile_without_borrowing_main_interface_or_capture_capabilities() {
    let mut project = manifest(
        json!([{"name":"result","scope":"global","type":"string"}]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    project.capabilities.push("ui.floating".into());
    let bundle = sources(
        &project,
        &[
            node(
                "floating.show",
                json!({"name":"panel","x":-1,"y":-1,"width":320,"height":120,"text":"hello","fontPx":20,"textColor":"#FFFFFFFF","background":"#C0202938","imagePath":"","shapesJson":"[]"}),
            ),
            node(
                "floating.bounds",
                json!({"name":"panel","resultVariable":"result"}),
            ),
            node("floating.hide", json!({"name":"panel"})),
            node("floating.close", json!({"name":"panel"})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("Floating={show=function(name,x,y,w,h,text,font,color,bg,image,shapes) assert(name=='panel' and x==-1 and y==-1 and w==320 and h==120 and text=='hello' and font==20 and image=='' and shapes=='[]') end,getBounds=function() return '{}' end,hide=function(name) assert(name=='panel') end,close=function(name) assert(name=='panel') end}").exec().unwrap();
    lua.load(bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
}

#[test]
fn randomization_compiled_waits_preserve_zero_exact_and_pointer_timing() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project.capabilities.push("input.basic".into());
    project.randomization.wait = flow_ir::WaitRandomization {
        enabled: true,
        unit: flow_ir::WaitRandomizationUnit::Milliseconds,
        min: 10,
        max: 10,
    };
    let bundle = sources(
        &project,
        &[
            node("task.sleep", json!({"milliseconds":0})),
            node("task.sleep", json!({"milliseconds":100,"exactTiming":true})),
            node("task.sleep", json!({"milliseconds":100})),
            node("input.pointerdown", json!({"x":10,"y":20})),
            node("task.sleep", json!({"milliseconds":100})),
            node("input.pointerup", json!({})),
            node("task.sleep", json!({"milliseconds":100})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("delays={}; Task={sleep=function(ms) table.insert(delays,ms) end}; Input={pointerDown=function() end,pointerUp=function() end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    lua.load("assert(table.concat(delays,',')=='0,100,110,100,110')")
        .exec()
        .unwrap();
}

#[test]
fn randomization_compiles_gesture_bounds_and_disabled_override() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project.capabilities.push("input.basic".into());
    project.randomization.touch = flow_ir::PositionRandomization {
        enabled: true,
        x_px: 5,
        y_px: 5,
    };
    let mut tap = node("input.tap", json!({"x":12,"y":34}));
    tap["randomization"] = json!({"mode":"disabled","position":{"enabled":false,"xPx":0,"yPx":0},"wait":{"enabled":false,"unit":"milliseconds","min":0,"max":0}});
    let bundle = sources(
        &project,
        &[
            node("input.pointerdown", json!({"x":0,"y":0})),
            node("task.sleep", json!({"milliseconds":1})),
            node("input.pointermove", json!({"x":719,"y":1279})),
            node("input.pointerup", json!({})),
            tap,
        ],
        &[],
    );
    let text = std::str::from_utf8(&bundle.main_lua).unwrap();
    assert!(text.contains("__random_bounds(0,0,719,1279)"));
    let lua = runtime();
    lua.load("Task={sleep=function() end}; Input={resolvePoint=function(x,y) return {x=x,y=y,width=720,height=1280,snapshotId=1} end,pointerDownScreen=function(x,y) assert(x==0 and y==0) end,pointerMoveScreen=function(x,y) assert(x==719 and y==1279) end,pointerUp=function() end,tap=function(x,y) assert(x==12 and y==34); exact=true end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert!(lua.globals().get::<bool>("exact").unwrap());
}

#[test]
fn screen_pixel_input_compiles_exact_points_and_keeps_legacy_design_calls() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project.capabilities.push("input.basic".into());
    let bundle = sources(
        &project,
        &[
            node("input.tap", json!({"x":1200,"y":300,"screenPixels":true})),
            node(
                "input.pointerdown",
                json!({"x":1100,"y":250,"screenPixels":true}),
            ),
            node("task.sleep", json!({"milliseconds":800,"exactTiming":true})),
            node(
                "input.pointermove",
                json!({"x":1150,"y":260,"screenPixels":true}),
            ),
            node("input.pointerup", json!({})),
            node(
                "input.swipe",
                json!({"x1":1000,"y1":200,"x2":1200,"y2":300,"durationMs":200,"screenPixels":true}),
            ),
            node("input.tap", json!({"x":10,"y":20})),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("calls={}; Task={sleep=function(ms) assert(ms==800) end}; Input={tapScreen=function(x,y) assert(x==1200 and y==300);table.insert(calls,'tap') end,pointerDownScreen=function(x,y) assert(x==1100 and y==250);table.insert(calls,'down') end,pointerMoveScreen=function(x,y) assert(x==1150 and y==260);table.insert(calls,'move') end,pointerUp=function() table.insert(calls,'up') end,swipeScreen=function(a,b,c,d,ms) assert(a==1000 and b==200 and c==1200 and d==300 and ms==200);table.insert(calls,'swipe') end,tap=function(x,y) assert(x==10 and y==20);table.insert(calls,'design') end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    lua.load("assert(table.concat(calls,',')=='tap,down,move,up,swipe,design')")
        .exec()
        .unwrap();
}

#[test]
fn display_alias_does_not_change_generated_execution() {
    let project = manifest(json!([]), json!([]));
    let ordinary = node(
        "task.log",
        json!({"level":"info","message":"runtime message"}),
    );
    let mut aliased = ordinary.clone();
    aliased["display"] = json!({"alias":"display only","showParameters":false});
    let plain = sources(&project, &[ordinary], &[]);
    let customized = sources(&project, &[aliased], &[]);
    // Source content changes its integrity identity, not the executable statements.
    assert_eq!(
        std::str::from_utf8(&plain.main_lua)
            .unwrap()
            .replace(&plain.record.generation_id, "IDENTITY"),
        std::str::from_utf8(&customized.main_lua)
            .unwrap()
            .replace(&customized.record.generation_id, "IDENTITY")
    );
    assert_eq!(
        std::str::from_utf8(&plain.source_map_json)
            .unwrap()
            .replace(&plain.record.generation_id, "IDENTITY"),
        std::str::from_utf8(&customized.source_map_json)
            .unwrap()
            .replace(&customized.record.generation_id, "IDENTITY")
    );
}

#[test]
fn commented_container_skips_children_and_restoration_preserves_child_comment() {
    let project = manifest(json!([]), json!([]));
    let mut repeat = node("control.repeat", json!({"times":1}));
    repeat["childBlocks"] = json!({"body":"body"});
    let child = |message: &str, disabled: bool| {
        let mut child = node("task.log", json!({"level":"info","message":message}));
        child["blockId"] = json!("body");
        child["parentId"] = json!("main-0");
        child["depth"] = json!(1);
        child["disabled"] = json!(disabled);
        child
    };
    for parent_disabled in [true, false] {
        repeat["disabled"] = json!(parent_disabled);
        let bundle = sources(
            &project,
            &[
                repeat.clone(),
                child("independent", true),
                child("active", false),
            ],
            &[],
        );
        let lua = runtime();
        lua.load(
            "System={elapsedRealtimeMillis=function() return 0 end}; Task={sleep=function() end}",
        )
        .exec()
        .unwrap();
        lua.load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        let logs = lua
            .globals()
            .get::<mlua::Table>("logs")
            .unwrap()
            .sequence_values::<String>()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(
            logs,
            if parent_disabled {
                vec![]
            } else {
                vec!["active".to_owned()]
            }
        );
    }
}

#[test]
fn compound_conditions_short_circuit_and_execute_one_branch() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.7".into();
    let compare = |v: &str, op: &str, value: Value| json!({"type":"compare","variable":v,"operator":op,"value":value});
    for (tree, expected) in [
        (
            json!({"type":"any","conditions":[compare("x","equals",json!(1)),compare("undefined","lessThan",json!(2))]}),
            "yes",
        ),
        (
            json!({"type":"all","conditions":[compare("x","equals",json!(0)),compare("undefined","lessThan",json!(2))]}),
            "alternate",
        ),
        (
            json!({"type":"not","condition":compare("x","equals",json!(1))}),
            "alternate",
        ),
    ] {
        let mut conditional = node(
            "control.if",
            json!({"variable":"x","operator":"equals","value":1,"condition":tree,"elseIf":[{"variable":"x","operator":"equals","value":1,"condition":{"type":"not","condition":compare("x","equals",json!(0))}}]}),
        );
        conditional["nodeVersion"] = json!(2);
        conditional["childBlocks"] = json!({"then":"yes","elseIf0":"alternate","else":"no"});
        let branch = |block: &str, msg: &str| {
            let mut v = node("task.log", json!({"level":"info","message":msg}));
            v["blockId"] = json!(block);
            v["parentId"] = json!("main-1");
            v["depth"] = json!(1);
            v
        };
        let bundle = sources(
            &project,
            &[
                node("variable.set", json!({"name":"x","value":1})),
                conditional,
                branch("yes", "yes"),
                branch("alternate", "alternate"),
                branch("no", "no"),
            ],
            &[],
        );
        let lua = runtime();
        let instrumented = std::str::from_utf8(&bundle.main_lua).unwrap().replace(
            "local function __compare(left, operator, right)",
            "comparisonCalls=0\nlocal function __compare(left, operator, right) comparisonCalls=comparisonCalls+1",
        );
        lua.load(&instrumented)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        let logs = lua
            .globals()
            .get::<mlua::Table>("logs")
            .unwrap()
            .sequence_values::<String>()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(logs, [expected]);
        assert_eq!(
            lua.globals().get::<i32>("comparisonCalls").unwrap(),
            if expected == "yes" { 1 } else { 2 }
        );
        assert!(std::str::from_utf8(&bundle.main_lua)
            .unwrap()
            .contains("local __condition ="));
    }
}

#[test]
fn dynamic_messages_evaluate_typed_values_once_and_keep_channels_separate() {
    let project = manifest(
        json!([
            {"name":"count","scope":"flow","flowId":"main","type":"integer"},
            {"name":"title","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    let bundle = sources(
        &project,
        &[
            node("variable.set", json!({"name":"count","value":7})),
            node("variable.set", json!({"name":"title","value":"进度："})),
            node(
                "task.message",
                json!({"channel":"popup","expression":"title .. text(count)"}),
            ),
            node(
                "task.message",
                json!({"channel":"output","expression":"upper(\"ready\") .. \"\\n中文\""}),
            ),
            node(
                "task.message",
                json!({"channel":"console","expression":"\"count=\" .. text(count + 1)"}),
            ),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("events={}; Prompt={toast=function(s) table.insert(events,'popup:'..s) end,show=function(s) table.insert(events,'output:'..s) end}; Log.info=function(s) table.insert(events,'console:'..s) end").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    let events = lua
        .globals()
        .get::<mlua::Table>("events")
        .unwrap()
        .sequence_values::<String>()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert_eq!(
        events,
        ["popup:进度：7", "output:READY\n中文", "console:count=8"]
    );
    assert!(!std::str::from_utf8(&bundle.main_lua)
        .unwrap()
        .contains("__vars[\"count\"] = 8"));
}

#[test]
fn dynamic_message_limits_are_checked_before_the_host_is_called() {
    let project = manifest(
        json!([{ "name":"title","scope":"global","type":"string" }]),
        json!([]),
    );
    let bundle = sources(
        &project,
        &[node(
            "task.message",
            json!({"channel":"popup","expression":"title"}),
        )],
        &[],
    );
    for (value, allowed) in [
        ("x".repeat(2048).into_bytes(), true),
        ("x".repeat(2049).into_bytes(), false),
        (("中".repeat(682) + "ab").into_bytes(), true),
        ("中".repeat(683).into_bytes(), false),
        (vec![0xff], false),
    ] {
        let lua = runtime();
        lua.globals()
            .set("value", lua.create_string(&value).unwrap())
            .unwrap();
        // Synthetic dynamic value injected after declarations, retaining the
        // generated type and result guards. No external script is executed.
        let source = std::str::from_utf8(&bundle.main_lua).unwrap().replace(
            "local __call_args = {}",
            "__globals.title=value; local __call_args = {}",
        );
        lua.load("sent=0; Prompt={toast=function(s) sent=sent+1 end}")
            .exec()
            .unwrap();
        let result = lua
            .load(source)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(());
        assert_eq!(result.is_ok(), allowed);
        assert_eq!(
            lua.globals().get::<u32>("sent").unwrap(),
            u32::from(allowed)
        );
    }
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
fn unicode_string_operations_execute_with_literal_search_and_limits() {
    let manifest = manifest(
        json!([
            {"name":"label","scope":"global","type":"string"},
            {"name":"count","scope":"global","type":"integer"}
        ]),
        json!([]),
    );
    let bundle = sources(
        &manifest,
        &[
            node(
                "variable.calculate",
                json!({"name":"label","expression":"trim(\" 中文A.%  \" )"}),
            ),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"len(label)"}),
            ),
            log("count"),
            node(
                "variable.calculate",
                json!({"name":"count","expression":"find(label,\".%\")"}),
            ),
            log("count"),
            node(
                "variable.calculate",
                json!({"name":"label","expression":"replace(label,\".%\",\"结束\")"}),
            ),
            log("label"),
            node(
                "variable.calculate",
                json!({"name":"label","expression":"sub(label,2,2)"}),
            ),
            log("label"),
            node(
                "variable.calculate",
                json!({"name":"label","expression":"lower(upper(label))"}),
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
    assert_eq!(logs, ["5", "4", "中文A结束", "文A", "文a"]);
}

#[test]
fn native_byte_operations_preserve_existing_character_semantics() {
    let manifest = manifest(
        json!([
            {"name":"label","scope":"global","type":"string"},
            {"name":"count","scope":"global","type":"integer"}
        ]),
        json!([]),
    );
    let cases = [
        ("count", "byteLen(\"中文A\")"),
        ("count", "len(\"中文A\")"),
        ("count", "byteFind(\"中文A.%\",\".%\",0)"),
        ("count", "find(\"中文A.%\",\".%\")"),
        ("count", "byteFind(\"abcabc\",\"abc\",1)"),
        ("count", "byteFind(\"abc\",\"d\",0)"),
        ("count", "byteFind(\"abc\",\"\",3)"),
        ("count", "byteFind(\"abc\",\"\",4)"),
        ("label", "byteSub(\"中文A\",3,3)"),
        ("label", "byteSub(\"中文A\",6,100)"),
        ("label", "byteSub(\"中文A\",7,0)"),
        ("label", "byteSub(\"中\",1,0)"),
        ("count", "byteLen(\"\\u0000中文\")"),
        ("count", "byteFind(\"a\\u0000b\",\"\\u0000\",0)"),
    ];
    let nodes = cases
        .iter()
        .flat_map(|(target, expression)| {
            [
                node(
                    "variable.calculate",
                    json!({"name":target,"expression":expression}),
                ),
                log(target),
            ]
        })
        .collect::<Vec<_>>();
    let bundle = sources(&manifest, &nodes, &[]);
    let lua = runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    let logs: Vec<String> = lua.globals().get("logs").unwrap();
    assert_eq!(
        logs,
        ["7", "3", "7", "4", "3", "-1", "3", "-1", "文", "A", "", "", "7", "1"]
    );
}

#[test]
fn native_integer_division_is_exact_and_truncates_toward_zero() {
    let manifest = manifest(
        json!([{"name":"count","scope":"global","type":"integer"}]),
        json!([]),
    );
    let pairs = [
        (-7_i64, 3_i64),
        (7, -3),
        (-7, -3),
        (7, 3),
        (0, -3),
        (i64::MAX, 3),
        (i64::MIN, 3),
        (i64::MIN, 1),
        (i64::MIN, -2),
        (1, i64::MIN),
        (-1, i64::MIN),
        (i64::MIN, i64::MAX),
        (i64::MIN, i64::MIN),
    ];
    let mut nodes = Vec::new();
    let mut expected = Vec::new();
    for (a, b) in pairs {
        for (function, result) in [("idiv", a / b), ("irem", a % b)] {
            nodes.push(node(
                "variable.calculate",
                json!({"name":"count","expression":format!("{function}({a},{b})")}),
            ));
            nodes.push(log("count"));
            expected.push(result.to_string());
        }
    }
    let bundle = sources(&manifest, &nodes, &[]);
    let lua = runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    let logs: Vec<String> = lua.globals().get("logs").unwrap();
    assert_eq!(logs, expected);
}

#[test]
fn native_calculation_runtime_rejects_bad_bounds_and_split_utf8() {
    let manifest = manifest(
        json!([
            {"name":"label","scope":"global","type":"string"},
            {"name":"count","scope":"global","type":"integer"}
        ]),
        json!([]),
    );
    for (target, expression, message) in [
        ("label", "byteSub(\"中\",1,1)", "切断UTF-8"),
        ("label", "byteSub(\"abc\",4,1)", "字节截取起点"),
        ("label", "byteSub(\"abc\",0,4097)", "字节截取起点"),
        ("count", "byteFind(\"abc\",\"a\",-1)", "字节查找起点"),
        ("count", "byteFind(\"abc\",\"a\",4097)", "字节查找起点"),
        ("count", "idiv(1,0)", "除以零"),
        ("count", "irem(1,0)", "除以零"),
        ("count", "idiv(-9223372036854775808,-1)", "溢出"),
        ("count", "irem(-9223372036854775808,-1)", "溢出"),
    ] {
        let bundle = sources(
            &manifest,
            &[node(
                "variable.calculate",
                json!({"name":target,"expression":expression}),
            )],
            &[],
        );
        let error = runtime()
            .load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap_err();
        assert!(error.to_string().contains(message), "{expression}: {error}");
    }
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
        include_str!(
            "../../../../android/apps/studio-android/src/test/resources/compiler-plugin-v2.lua"
        )
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
fn extended_ui_events_keep_decimal_and_json_selection_types() {
    for (kind, control, variable_type, initial, options, setup, expected) in [
        (
            "number",
            "DECIMAL",
            "number",
            json!(0.25),
            json!([]),
            "__ui_initial_globals={target=0.25}; payload='-0.5'",
            vec!["0.25", "-0.5"],
        ),
        (
            "multiChoice",
            "MULTISELECT",
            "string",
            json!(["a,b"]),
            json!(["a,b", "c"]),
            "__ui_initial_globals={target='initial'}; payload='[\"a,b\"]'",
            vec!["initial", "[\"a,b\"]"],
        ),
    ] {
        let mut manifest = manifest(
            json!([{"name":"target","scope":"global","flowId":null,"type":variable_type}]),
            json!([{"name":"value","type":variable_type,"required":true}]),
        );
        manifest.runtime_api = "1.7".into();
        manifest.capabilities.push("ui.control".into());
        manifest.runner_ui = Some(serde_json::from_value(json!({"description":null,"version":2,"pages":[{"id":"main","title":"main","width":720,"height":960,"background":"#FFFFFFFF"}],"fields":[{"id":"control","label":"control","kind":kind,"required":true,"initialValue":initial,"minimum":null,"maximum":null,"options":options,"ui":{"control":control,"pageId":"main","x":0,"y":0,"width":100,"height":40,"fontPx":20,"textColor":"#FF000000","background":"#FFFFFFFF","alignment":"center","visible":true,"enabled":true,"binding":"global:target","events":{"change":"flow:child"}}}]})).unwrap());
        flow_ir::validate_runner_ui(manifest.runner_ui.as_ref()).unwrap();
        let bundle = sources(&manifest, &[log("target")], &[log("value")]);
        let lua = runtime();
        lua.load(setup).exec().unwrap();
        lua.load("RunnerConfig={}; __ui_initial_locals={}; UI={getValue=function() return payload end,waitEvent=function() if visited then error('test-finish') end; visited=true; return 'control:change' end}; Task={spawn=function(f) f(); return 1 end}").exec().unwrap();
        let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
        assert!(entry
            .call::<()>(())
            .unwrap_err()
            .to_string()
            .contains("test-finish"));
        assert_eq!(loop_logs(&lua), expected);
    }
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
fn interface_property_results_are_assigned_without_changing_legacy_writes() {
    let mut manifest = manifest(
        json!([{"name":"value","scope":"global","type":"string"}]),
        json!([]),
    );
    manifest.runtime_api = "1.7".into();
    manifest.capabilities.push("ui.control".into());
    manifest.runner_ui = Some(serde_json::from_value(json!({"fields":[{"id":"name","label":"Name","kind":"text","required":false,"initialValue":"","options":[]}]})).unwrap());
    let bundle = sources(
        &manifest,
        &[
            node(
                "ui.command",
                json!({"controlId":"name","operation":"alignment","value":"center"}),
            ),
            node(
                "ui.command",
                json!({"controlId":"name","operation":"get:alignment","value":"","resultVariable":"value"}),
            ),
            log("value"),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("UI={command=function(id,op,v) assert(id=='name'); if op=='alignment' then assert(v=='center'); alignment=v else assert(op=='get:alignment' and v==''); return alignment end end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), ["center"]);
    for (operation, value) in [
        ("fontBold", "true"),
        ("textUnderline", "false"),
        ("borderColor", "#80123456"),
        ("borderWidthPx", "32"),
        ("cornerPx", "512"),
    ] {
        let bundle = sources(
            &manifest,
            &[
                node(
                    "ui.command",
                    json!({"controlId":"name","operation":operation,"value":value}),
                ),
                node(
                    "ui.command",
                    json!({"controlId":"name","operation":format!("get:{operation}"),"value":"","resultVariable":"value"}),
                ),
                log("value"),
            ],
            &[],
        );
        let lua = runtime();
        lua.globals().set("operation", operation).unwrap();
        lua.load("UI={command=function(id,op,v) assert(id=='name'); if op==operation then saved=v else assert(op=='get:'..operation and v==''); return saved end end}").exec().unwrap();
        lua.load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(loop_logs(&lua), [value]);
    }
}

#[test]
fn ui_input_and_config_blocks_compile_with_explicit_private_storage() {
    let mut manifest = manifest(
        json!([{"name":"config","scope":"global","type":"string"}]),
        json!([]),
    );
    manifest.runtime_api = "1.7".into();
    manifest
        .capabilities
        .extend(["ui.control".into(), "storage.data".into()]);
    manifest.runner_ui=Some(serde_json::from_value(json!({"fields":[{"id":"name","label":"Name","kind":"text","required":false,"initialValue":"","options":[]}]})).unwrap());
    let bundle = sources(
        &manifest,
        &[
            node(
                "ui.command",
                json!({"controlId":"name","operation":"hint","value":"请输入"}),
            ),
            node(
                "ui.command",
                json!({"controlId":"window","operation":"get:config","value":"","resultVariable":"config"}),
            ),
            node(
                "files.write",
                json!({"path":"settings/ui.json","text":"","textVariable":"config"}),
            ),
            node(
                "files.read",
                json!({"path":"settings/ui.json","resultVariable":"config"}),
            ),
            node(
                "ui.command",
                json!({"controlId":"window","operation":"restoreConfig","value":"","valueVariable":"config"}),
            ),
        ],
        &[],
    );
    let lua = runtime();
    lua.load(r#"
        UI={command=function(id,op,value)
            if op=='hint' then assert(id=='name' and value=='请输入')
            elseif op=='get:config' then assert(id=='window' and value==''); return '{"values":{}}'
            else assert(id=='window' and op=='restoreConfig' and value=='{"values":{}}'); restored=true end
        end}
        Files={write=function(path,value) assert(path=='settings/ui.json'); stored=value end,
            read=function(path) assert(path=='settings/ui.json'); return stored end}
    "#).exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert!(lua.globals().get::<bool>("restored").unwrap());
}

#[test]
fn data_and_private_file_blocks_render_literal_and_typed_variable_operands() {
    let mut manifest = manifest(
        json!([
            {"name":"data","scope":"global","type":"string"},
            {"name":"count","scope":"global","type":"integer"}
        ]),
        json!([]),
    );
    manifest.runtime_api = "1.7".into();
    manifest.capabilities.push("storage.data".into());
    let bundle = sources(
        &manifest,
        &[
            node("variable.set", json!({"name":"data","value":"[]"})),
            node(
                "data.append",
                json!({"json":"[]","jsonVariable":"data","valueJson":"1","resultVariable":"data"}),
            ),
            node(
                "data.length",
                json!({"json":"[]","jsonVariable":"data","resultVariable":"count"}),
            ),
            node(
                "files.write",
                json!({"path":"state/value.json","text":"","textVariable":"data"}),
            ),
            node(
                "files.read",
                json!({"path":"state/value.json","resultVariable":"data"}),
            ),
            log("count"),
            log("data"),
        ],
        &[],
    );
    let lua = runtime();
    lua.load("Data={append=function(j,v) assert(j=='[]' and v=='1'); return '[1]' end,length=function(j) assert(j=='[1]'); return 1 end}; Files={write=function(p,t) assert(p=='state/value.json'); saved=t end,read=function(p) assert(p=='state/value.json'); return saved end}").exec().unwrap();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), ["1", "[1]"]);
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
fn cancellable_cycle_passes_old_iteration_cap_and_reads_nearest_metrics() {
    let project = manifest(
        json!([{"name":"n","scope":"flow","flowId":"main","type":"integer"}]),
        json!([]),
    );
    let main = [
        loop_node(
            "loop",
            None,
            "block-main",
            0,
            "control.cycle",
            json!({"yieldMs":1}),
            Some("body"),
        ),
        loop_node(
            "metric",
            Some("loop"),
            "body",
            1,
            "control.loopmetric",
            json!({"metric":"count","name":"n"}),
            None,
        ),
        loop_node(
            "check",
            Some("loop"),
            "body",
            1,
            "control.loopcheck",
            json!({"metric":"count","limit":10005}),
            None,
        ),
        log("n"),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = loop_runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(loop_logs(&lua), ["10005"]);
    assert_eq!(lua.globals().get::<u64>("clock").unwrap(), 10005);
    assert!(!std::str::from_utf8(&bundle.main_lua)
        .unwrap()
        .contains("iteration limit exceeded"));
}

#[test]
fn cycle_continue_keeps_cancellable_yield_and_cancel_does_not_run_more_body() {
    let project = manifest(json!([]), json!([]));
    let main = [
        loop_node(
            "loop",
            None,
            "block-main",
            0,
            "control.cycle",
            json!({"yieldMs":1}),
            Some("body"),
        ),
        loop_node(
            "log",
            Some("loop"),
            "body",
            1,
            "task.log",
            json!({"level":"info","message":"body"}),
            None,
        ),
        loop_node(
            "continue",
            Some("loop"),
            "body",
            1,
            "control.continue",
            json!({}),
            None,
        ),
        // Creates locals after the continue. The generated jump must leave their
        // scope rather than jump into it, and this body must remain unreachable.
        loop_node(
            "unreachable_repeat",
            Some("loop"),
            "body",
            1,
            "control.repeat",
            json!({"times":2}),
            Some("unreachable_body"),
        ),
        loop_node(
            "unreachable_log",
            Some("unreachable_repeat"),
            "unreachable_body",
            2,
            "task.log",
            json!({"level":"info","message":"must not run"}),
            None,
        ),
    ];
    let bundle = sources(&project, &main, &[]);
    let lua = loop_runtime();
    lua.load("Task.sleep=function(ms) coroutine.yield(ms); if cancelled then error('cancelled',0) end end").exec().unwrap();
    let thread = lua
        .create_thread(lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap())
        .unwrap();
    assert_eq!(thread.resume::<u32>(()).unwrap(), 1);
    assert!(loop_logs(&lua).is_empty());
    for _ in 0..3 {
        assert_eq!(thread.resume::<u32>(()).unwrap(), 1);
    }
    assert_eq!(loop_logs(&lua).len(), 3);
    lua.globals().set("cancelled", true).unwrap();
    assert!(thread
        .resume::<()>(())
        .unwrap_err()
        .to_string()
        .contains("cancelled"));
    assert_eq!(loop_logs(&lua).len(), 3);
}

#[test]
fn timed_cycle_rechecks_deadline_after_yield_and_uses_numeric_variable_seconds() {
    let project = manifest(
        json!([{"name":"duration","scope":"flow","flowId":"main","type":"number"}]),
        json!([]),
    );
    for args in [
        json!({"yieldMs":2,"durationMs":6}),
        json!({"yieldMs":2,"durationVariable":"duration","durationUnit":"seconds"}),
    ] {
        let main = [
            node("variable.set", json!({"name":"duration","value":0.006})),
            loop_node(
                "loop",
                None,
                "block-main",
                0,
                "control.cycle",
                args,
                Some("body"),
            ),
            loop_node(
                "log",
                Some("loop"),
                "body",
                1,
                "task.log",
                json!({"level":"info","message":"body"}),
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
        assert_eq!(loop_logs(&lua), ["body", "body"]);
        assert_eq!(lua.globals().get::<u64>("clock").unwrap(), 6);
    }
}

#[test]
fn plugin_status_selects_exactly_one_branch_without_overwriting_return_parameters() {
    let project = manifest(
        json!([
            {"name":"answer","scope":"flow","flowId":"main","type":"integer"},
            {"name":"text","scope":"flow","flowId":"main","type":"string"},
            {"name":"__status","scope":"flow","flowId":"child","type":"string"}
        ]),
        json!([]),
    );
    let mut call = node(
        "flow.call.branch",
        json!({"targetFlowId":"child","arguments":{}}),
    );
    call["nodeId"] = json!("call");
    call["childBlocks"] = json!({"success":"ok","failure":"bad"});
    let main = [
        call,
        loop_node(
            "ok",
            Some("call"),
            "ok",
            1,
            "task.log",
            json!({"level":"info","message":"success"}),
            None,
        ),
        loop_node(
            "bad",
            Some("call"),
            "bad",
            1,
            "task.log",
            json!({"level":"info","message":"failure"}),
            None,
        ),
        node(
            "flow.return.get",
            json!({"index":1,"targetVariable":"answer"}),
        ),
        log("answer"),
        node(
            "flow.return.get",
            json!({"index":2,"targetVariable":"text"}),
        ),
        log("text"),
    ];
    for success in [Some(true), Some(false), None] {
        let mut child = vec![
            node("flow.return.set", json!({"index":1,"value":42})),
            node("variable.set", json!({"name":"__status","value":"payload"})),
            node(
                "flow.return.set",
                json!({"index":2,"valueVariable":"__status"}),
            ),
        ];
        if let Some(value) = success {
            child.push(node("flow.status.return", json!({"success":value})));
            child.push(node(
                "task.log",
                json!({"level":"info","message":"unreachable"}),
            ));
        }
        let bundle = sources(&project, &main, &child);
        let lua = runtime();
        lua.load(&bundle.main_lua)
            .eval::<mlua::Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(
            loop_logs(&lua),
            [
                if success == Some(false) {
                    "failure"
                } else {
                    "success"
                },
                "42",
                "payload"
            ]
        );
    }
    let child = [node("task.sleep", json!({"milliseconds":1}))];
    let bundle = sources(&project, &main, &child);
    let lua = runtime();
    lua.load("Task={sleep=function() error('host failure',0) end}")
        .exec()
        .unwrap();
    assert!(lua
        .load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap_err()
        .to_string()
        .contains("host failure"));
    assert!(loop_logs(&lua).is_empty());
}

fn execute_loop_bundle(bundle: &crate::GenerationBundle) -> mlua::Lua {
    let lua = loop_runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    lua
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
    let lua = execute_loop_bundle(&bundle);
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
    let lua = execute_loop_bundle(&bundle);
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
        (0.001_666_666_666_666_666_8, Some("minutes")),
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
    for duration in [0.0, -1.0, 86_400_001.0, 0.001, 1.5] {
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
            text.push_str(&value.to_string());
            text.push('\n');
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
fn compiled_checkpoints_keep_the_current_interface_bound_local_context() {
    let mut project = manifest(
        json!([{"name":"username","scope":"flow","flowId":"main","type":"string"}]),
        json!([]),
    );
    project.runtime_api = "1.7".into();
    project.capabilities.push("ui.control".into());
    project.runner_ui = Some(serde_json::from_value(json!({"description":null,"version":2,
        "pages":[{"id":"main","title":"Main","width":720,"height":960,"background":"#FFFFFFFF"}],
        "fields":[{"id":"name","label":"Name","kind":"text","required":false,"initialValue":"initial","minimum":null,"maximum":null,"options":[],
            "ui":{"control":"INPUT","pageId":"main","x":20,"y":20,"width":320,"height":72,"fontPx":28,"textColor":"#FF202938","background":"#FFFFFFFF","alignment":"center","visible":true,"enabled":true,"binding":"flow:main:username","events":{}}}]})).unwrap());
    let bundle = sources(
        &project,
        &[node("task.noop", json!({})), log("username")],
        &[],
    );
    let lua = runtime();
    lua.load("__ui_debug_skip_events=true; __ui_initial_locals={main={username='start'}}; __autoscript_debug_capture=true; __autoscript_debug_step=true").exec().unwrap();
    let entry: mlua::Function = lua.load(&bundle.main_lua).eval().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    lua.load("__ui_initial_locals.main.username='changed'")
        .exec()
        .unwrap();
    assert_eq!(
        thread.resume::<String>(()).unwrap(),
        "__AUTOSCRIPT_DEBUG_CHECKPOINT_V1"
    );
    let snapshot: mlua::Table = lua.globals().get("__autoscript_debug_snapshot").unwrap();
    let locals: mlua::Table = snapshot.raw_get("locals").unwrap();
    let overrides: mlua::Table = snapshot.raw_get("localOverrides").unwrap();
    assert_eq!(locals.raw_get::<String>("username").unwrap(), "start");
    assert_eq!(overrides.raw_get::<String>("username").unwrap(), "changed");
    lua.globals().set("__autoscript_debug_step", false).unwrap();
    thread.resume::<()>(()).unwrap();
    assert_eq!(loop_logs(&lua), ["changed"]);
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
