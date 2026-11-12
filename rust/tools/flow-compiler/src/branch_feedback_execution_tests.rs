fn observed_branch_slots(bundle: &GenerationBundle, lua: &mlua::Lua) -> Vec<(String, String)> {
    lua.globals()
        .set("__autoscript_run_observation", true)
        .unwrap();
    let entry = lua.load(&bundle.main_lua).eval::<mlua::Function>().unwrap();
    let thread = lua.create_thread(entry).unwrap();
    let mut slots = Vec::new();
    for _ in 0..100 {
        let values = thread.resume::<mlua::MultiValue>(()).unwrap();
        if values.front().is_some_and(|v| matches!(v, mlua::Value::String(s) if s.to_str().unwrap() == "__AUTOSCRIPT_BRANCH_RESULT_V1")) {
            let text = |index| match &values[index] { mlua::Value::String(s) => s.to_str().unwrap().to_owned(), _ => panic!("expected identity") };
            slots.push((text(2), text(3)));
        }
        if thread.status() == mlua::prelude::LuaThreadStatus::Finished {
            return slots;
        }
    }
    panic!("branch fixture did not finish");
}

#[test]
fn branch_feedback_reports_actual_empty_plugin_return_branch() {
    for success in [true, false] {
        let mut call = node(
            "flow.call.branch",
            json!({"targetFlowId":"child","arguments":{}}),
        );
        call["childBlocks"] = json!({"success":"ok", "failure":"bad"});
        let bundle = sources(
            &manifest(json!([]), json!([])),
            &[call],
            &[node("flow.status.return", json!({"success":success}))],
        );
        assert_eq!(
            observed_branch_slots(&bundle, &runtime()),
            [(
                "main-0".into(),
                if success {
                    "success".into()
                } else {
                    "failure".into()
                }
            )]
        );
    }
}

#[test]
fn branch_feedback_reports_one_actual_condition_including_empty_else_if() {
    for (value, expected) in [(1, "then"), (2, "elseIf0"), (3, "else")] {
        let mut condition = node(
            "control.if",
            json!({"variable":"x","operator":"equals","value":1,
            "elseIf":[{"variable":"x","operator":"equals","value":2}]}),
        );
        condition["childBlocks"] = json!({"then":"yes","elseIf0":"alternate","else":"no"});
        let bundle = sources(
            &manifest(json!([]), json!([])),
            &[
                node("variable.set", json!({"name":"x","value":value})),
                condition,
            ],
            &[],
        );
        assert_eq!(
            observed_branch_slots(&bundle, &runtime()),
            [("main-1".into(), expected.into())]
        );
    }
}

#[test]
fn branch_feedback_reports_recognition_miss_and_hit_without_running_both_branches() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["vision.template", "screen.capture"].map(str::to_owned));
    project.resources =
        serde_json::from_value(json!([{"kind":"image","path":"assets/images/a.png"}])).unwrap();
    let operation = json!({"kind":"vision.findimage","nodeVersion":3,"args":{
        "frameVariable":"unused","imagePath":"assets/images/a.png","autoCapture":true,
        "region":{"left":0,"top":0,"right":100,"bottom":100},"tolerance":0,"similarityPermille":900,
        "foundVariable":"found","xVariable":"x","yVariable":"y"},
        "childBlocks":{"success":"ok","failure":"bad"}});
    let bundle = sources(&project, &[operation], &[]);
    for found in [true, false] {
        let lua = runtime();
        lua.globals().set("found", found).unwrap();
        lua.load("Screen={capture=function() return 1 end,cache=function(f) return f end,release=function() end,findImages=function() if found then return {x=3,y=4,width=2,height=2} end end}").exec().unwrap();
        assert_eq!(
            observed_branch_slots(&bundle, &lua),
            [(
                "main-0".into(),
                if found {
                    "success".into()
                } else {
                    "failure".into()
                }
            )]
        );
    }
}
