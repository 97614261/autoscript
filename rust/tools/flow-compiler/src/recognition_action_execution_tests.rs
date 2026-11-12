#[test]
fn recognition_inline_actions_compile_execute_and_leave_match_outputs_unchanged() {
    let mut project = manifest(json!([]), json!([]));
    project.runtime_api = "1.8".into();
    project
        .capabilities
        .extend(["vision.template", "screen.capture", "input.basic"].map(str::to_owned));
    project.resources =
        serde_json::from_value(json!([{"kind":"image","path":"assets/images/a.png"}])).unwrap();
    for (anchor, mode, expected) in [
        ("topLeft", "follow", (11, 18)),
        ("center", "disabled", (31, 28)),
        ("center", "offset", (31, 28)),
        ("center", "inside", (10, 20)),
    ] {
        let operation = json!({"kind":"vision.findimage","nodeVersion":3,"args":{
            "frameVariable":"unused","imagePath":"assets/images/a.png","autoCapture":true,
            "region":{"left":0,"top":0,"right":200,"bottom":100},"tolerance":0,"similarityPermille":900,
            "foundVariable":"found","xVariable":"x","yVariable":"y","successAction":"tap",
            "actionAnchor":anchor,"actionRandom":mode,"actionRandomX":0,"actionRandomY":0,"offsetX":1,"offsetY":-2}});
        let bundle = sources(&project, &[operation, log("x"), log("y")], &[]);
        for found in [false, true] {
            let lua = runtime();
            lua.globals().set("found", found).unwrap();
            lua.load(r#"
                released=0; clicks=0
                Screen={capture=function()return 1 end,cache=function(f)return f end,
                    release=function()released=released+1 end,
                    findImages=function()if found then return {x=10,y=20,width=40,height=20} end end}
                Input={tapScreen=function(x,y)assert(released==1);clicks=clicks+1;cx=x;cy=y end,
                    resolvePoint=function(x,y)return {x=x,y=y,width=200,height=100,snapshotId=7} end}
                math.random=function(a,b)return a end
            "#).exec().unwrap();
            lua.load(bundle.main_lua.clone())
                .eval::<mlua::Function>()
                .unwrap()
                .call::<()>(())
                .unwrap();
            assert_eq!(
                lua.globals().get::<i32>("clicks").unwrap(),
                i32::from(found)
            );
            if found {
                assert_eq!(lua.globals().get::<i32>("cx").unwrap(), expected.0);
                assert_eq!(lua.globals().get::<i32>("cy").unwrap(), expected.1);
                let logs = lua.globals().get::<mlua::Table>("logs").unwrap();
                assert!(logs.get::<String>(1).unwrap().ends_with("10"));
                assert!(logs.get::<String>(2).unwrap().ends_with("20"));
            }
        }
    }
}
