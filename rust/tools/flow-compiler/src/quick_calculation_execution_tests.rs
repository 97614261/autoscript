fn run_quick_calculation_bundle(bundle: &GenerationBundle) -> Result<Vec<String>, String> {
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(720, 1280),
    )
    .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "quick-calculation")
        .unwrap();
    for _ in 0..128 {
        runtime.drive().unwrap();
        if runtime.task_state(task) == Some(TaskState::Failed) {
            return Err(runtime.task_failure(task).unwrap().message.clone());
        }
        if runtime.task_state(task) == Some(TaskState::Completed) {
            return Ok(runtime.drain_script_logs());
        }
    }
    panic!("bounded calculation did not complete");
}

#[test]
fn quick_calculation_runs_conversions_math_and_literal_unicode_splits() {
    let project = manifest(
        json!([
            {"name":"i","scope":"global","type":"integer"},
            {"name":"n","scope":"global","type":"number"},
            {"name":"s","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    let cases = [
        ("i", "int(\" +190 \")", "190"),
        ("i", "intOr(\"abc\",-1)", "-1"),
        ("i", "intOr(\"-12.7\",99)", "-12"),
        ("n", "floatOr(\"bad\",2.5)", "2.5"),
        ("i", "abs(-190)", "190"),
        ("i", "floor(-1.2)", "-2"),
        ("i", "ceil(-1.2)", "-1"),
        ("i", "round(-1.5)", "-2"),
        ("i", "round(1.5)", "2"),
        (
            "i",
            "min(9007199254740993,9007199254740994)",
            "9007199254740993",
        ),
        ("n", "max(1,2.5)", "2.5"),
        ("s", "split(\"a,,b,\",\",\",0)", "[\"a\",\"\",\"b\",\"\"]"),
        (
            "s",
            "split(\" 100 , +100 , 180 \",\",\",3)",
            "[\"100\",\"+100\",\"180\"]",
        ),
        (
            "s",
            "split(\"甲.*乙.*丙\",\".*\",0)",
            "[\"甲\",\"乙\",\"丙\"]",
        ),
        (
            "s",
            "splitAny(\"100, 200；300\\n400\",\",； \\n\",3)",
            "[\"100\",\"200\",\"300\",\"400\"]",
        ),
        ("s", "split(\"中😀A\",\"\",0)", "[\"中\",\"😀\",\"A\"]"),
        ("s", "split(\"\",\",\",0)", "[\"\"]"),
        ("s", "split(\"\",\"\",0)", "[]"),
        (
            "s",
            "split(\"a\\n\\\"\\\\b\",\",\",0)",
            "[\"a\\u000a\\\"\\\\b\"]",
        ),
    ];
    let nodes = cases
        .iter()
        .flat_map(|(target, expression, _)| {
            [
                node(
                    "variable.calculate",
                    json!({"name":target,"expression":expression}),
                ),
                log(target),
            ]
        })
        .collect::<Vec<_>>();
    let bundle = sources(&project, &nodes, &[]);
    // Scalar helpers remain usable without a host or any Runtime API promotion.
    let lua = runtime();
    lua.load(&bundle.main_lua)
        .eval::<mlua::Function>()
        .unwrap()
        .call::<()>(())
        .unwrap();
    assert_eq!(
        loop_logs(&lua),
        cases
            .iter()
            .map(|(_, _, expected)| expected.to_string())
            .collect::<Vec<_>>()
    );
}

#[test]
fn quick_calculation_reads_real_bounded_json_apis_without_extra_nodes() {
    let mut project = manifest(
        json!([
            {"name":"列表","scope":"global","type":"string"}, {"name":"输出","scope":"global","type":"string"},
            {"name":"序号","scope":"global","type":"integer"}, {"name":"整数","scope":"global","type":"integer"},
            {"name":"小数","scope":"global","type":"number"}
        ]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    let cases = [
        (
            "列表",
            "split(\"100,+100,180\",\",\",0)",
            "[\"100\",\"+100\",\"180\"]",
        ),
        ("序号", "2", "2"),
        ("整数", "itemInt(列表,序号)", "100"),
        ("整数", "listLen(列表)", "3"),
        ("输出", "itemText(列表,1)", "100"),
        ("整数", "itemInt(列表,9,-1)", "-1"),
        (
            "输出",
            "itemText(\"{\\\"a.b\\\":\\\"中文\\\"}\",\"a.b\")",
            "中文",
        ),
        ("小数", "itemFloat(\"[\\\"+1.25\\\"]\",1)", "1.25"),
        ("整数", "itemInt(\"[\\\"abc\\\"]\",1,7)", "7"),
        (
            "输出",
            "itemText(\"{\\\"x\\\":null}\",\"x\",\"空值\")",
            "空值",
        ),
        (
            "输出",
            "itemText(\"[9007199254740993]\",1)",
            "9007199254740993",
        ),
        (
            "整数",
            "itemInt(\"[9007199254740993]\",1)",
            "9007199254740993",
        ),
        ("输出", "itemText(\"[true]\",1)", "true"),
        (
            "整数",
            "itemInt(itemJson(\"{\\\"rows\\\":[123]}\",\"rows\"),1)",
            "123",
        ),
        ("整数", "listLen(\"{\\\"x\\\":1,\\\"y\\\":2}\")", "2"),
    ];
    let nodes = cases
        .iter()
        .flat_map(|(target, expression, _)| {
            [
                node(
                    "variable.calculate",
                    json!({"name":target,"expression":expression}),
                ),
                log(target),
            ]
        })
        .collect::<Vec<_>>();
    let logs = run_quick_calculation_bundle(&sources(&project, &nodes, &[])).unwrap();
    assert_eq!(
        logs,
        cases
            .iter()
            .map(|(_, _, expected)| format!("脚本/INFO: {expected}"))
            .collect::<Vec<_>>()
    );
}

#[test]
fn quick_calculation_defaults_do_not_hide_corrupt_json_or_invalid_indexes() {
    let mut project = manifest(
        json!([
            {"name":"i","scope":"global","type":"integer"}, {"name":"s","scope":"global","type":"string"}
        ]),
        json!([]),
    );
    project.runtime_api = "1.8".into();
    for (target, expression, expected) in [
        ("i", "itemInt(\"not json\",1,99)", "合法"),
        ("i", "itemInt(\"{}\",1,99)", "对象字段"),
        ("i", "itemInt(\"[]\",0,99)", "从1"),
        ("i", "itemInt(\"[]\",1)", "超出范围"),
        ("s", "itemText(\"[[1]]\",1)", "JSON子项"),
        ("i", "int(\"abc\")", "十进制"),
        ("i", "abs(-9223372036854775808)", "64位"),
        ("i", "floor(1e100)", "64位"),
        ("s", "splitAny(\"x\",\"\",0)", "分隔字符"),
        ("s", "split(\"x\",\",\",4)", "选项"),
    ] {
        let error = run_quick_calculation_bundle(&sources(
            &project,
            &[node(
                "variable.calculate",
                json!({"name":target,"expression":expression}),
            )],
            &[],
        ))
        .unwrap_err();
        assert!(error.contains(expected), "{expression}: {error}");
        assert!(error.contains("第1行"), "{error}");
    }
}

#[test]
fn quick_calculation_split_enforces_item_and_encoded_text_budgets() {
    let project = manifest(
        json!([{"name":"s","scope":"global","type":"string"}]),
        json!([]),
    );
    for (source, expression, expected) in [
        (",".repeat(1024), "split(s,\",\",0)", "1024"),
        ("\u{0}".repeat(700), "split(s,\",\",0)", "4096"),
    ] {
        let bundle = sources(
            &project,
            &[
                node("variable.set", json!({"name":"s","value":source})),
                node(
                    "variable.calculate",
                    json!({"name":"s","expression":expression}),
                ),
            ],
            &[],
        );
        let error = run_quick_calculation_bundle(&bundle).unwrap_err();
        assert!(error.contains(expected), "{error}");
    }
}
