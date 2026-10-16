use crate::{
    compile_project, verify_generation, FlowSource, GenerationBundle, SUPPORTED_NODE_VERSIONS,
};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use mlua::{Function, Lua, Table};

fn compile_example() -> GenerationBundle {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../examples/native-workflow-project");
    let project =
        parse_project_manifest(&std::fs::read(root.join("project.json")).unwrap()).unwrap();
    let bytes = project
        .flows
        .iter()
        .map(|f| std::fs::read(root.join(&f.path)).unwrap())
        .collect::<Vec<_>>();
    let reports = project
        .flows
        .iter()
        .zip(&bytes)
        .map(|(f, bytes)| {
            load_jsonl(
                bytes,
                LoadOptions {
                    flow_id: &f.flow_id,
                    root_block_id: &f.root_block_id,
                    flow_schema_version: project.flow_schema_version,
                    supported_nodes: SUPPORTED_NODE_VERSIONS,
                },
            )
        })
        .collect::<Vec<_>>();
    let sources = project
        .flows
        .iter()
        .zip(bytes.iter().zip(&reports))
        .map(|(f, (bytes, report))| FlowSource {
            flow_id: &f.flow_id,
            exact_bytes: bytes,
            report,
        })
        .collect::<Vec<_>>();
    let bundle = compile_project(&project, &sources).unwrap();
    verify_generation(
        &bundle.generation_json,
        &project,
        &sources,
        &bundle.main_lua,
        &bundle.source_map_json,
    )
    .unwrap();
    bundle
}

// Real compiler and Lua 5.4; deterministic host double, never Root/user screen input.
fn host(matches: i32, repeats: i32, skip_events: bool) -> Lua {
    let lua = Lua::new();
    lua.globals().set("matched", matches).unwrap();
    lua.globals().set("repeats", repeats).unwrap();
    lua.globals()
        .set("__ui_debug_skip_events", skip_events)
        .unwrap();
    lua.load(r"
        released=0; captures=0; outputs={}; logs={}; tips={}; status=''; now=0
        __ui_initial_globals={repeatCount=repeats}
        __autoscript_debug_capture=true
        Task={sleep=function(ms) now=now+ms end, nowMs=function()return now end, spawn=function(fn)fn()end}
        System={elapsedRealtimeMillis=function()return now end}
        Screen={capture=function()captures=captures+1;return 1 end, cache=function(f)return f end,
            release=function()released=released+1 end,
            findAllImages=function() return '{}' end, matchValue=function()return tostring(matched)end}
        Prompt={toast=function(s,style)table.insert(tips,s); assert(style:find('1200'))end,
            show=function(s)table.insert(outputs,s)end}
        Log={info=function(s)table.insert(logs,s)end}
        UI={command=function(id,op,value)assert(id=='status' and op=='text');status=value;return ''end,
            waitEvent=function()return coroutine.yield('waiting')end, getValue=function()return '2'end}
        Input=setmetatable({},{__index=function()error('Example must not send device input')end})
    ").exec().unwrap();
    lua
}

#[test]
fn public_workflow_freezes_and_executes_success_failure_and_bounds() {
    let bundle = compile_example();
    for (matches, repeats, expected) in [(4, 3, 3), (0, 3, 0), (4, 6, 0), (4, 0, 0)] {
        let lua = host(matches, repeats, true);
        lua.load(&bundle.main_lua)
            .eval::<Function>()
            .unwrap()
            .call::<()>(())
            .unwrap();
        assert_eq!(lua.globals().get::<i32>("released").unwrap(), 1);
        assert_eq!(lua.globals().get::<i32>("captures").unwrap(), 1);
        assert_eq!(
            lua.globals().get::<String>("status").unwrap(),
            format!("已执行 {expected} 次")
        );
        assert_eq!(
            lua.globals()
                .get::<Table>("outputs")
                .unwrap()
                .len()
                .unwrap(),
            i64::from(expected)
        );
        assert_eq!(
            lua.globals().get::<Table>("logs").unwrap().len().unwrap(),
            i64::from(expected)
        );
        assert!(lua.globals().get::<Table>("tips").unwrap().len().unwrap() >= 2);
    }
}

#[test]
fn runner_event_mode_updates_the_same_global_binding_after_entry_finishes() {
    let lua = host(4, 3, false);
    let entry = lua
        .load(compile_example().main_lua)
        .eval::<Function>()
        .unwrap();
    let thread = lua.create_thread(entry).unwrap();
    assert_eq!(thread.resume::<String>(()).unwrap(), "waiting");
    assert_eq!(
        lua.globals().get::<String>("status").unwrap(),
        "已执行 3 次"
    );
    assert_eq!(
        thread.resume::<String>("repeatCount:change").unwrap(),
        "waiting"
    );
    let snapshot = lua
        .globals()
        .get::<Table>("__autoscript_debug_snapshot")
        .unwrap();
    assert_eq!(
        snapshot
            .get::<Table>("globals")
            .unwrap()
            .get::<i32>("repeatCount")
            .unwrap(),
        2
    );
}
