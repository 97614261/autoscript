use super::pure_utility_runtime::execute;
use lua_runtime::{LuaRuntimeConfig, LuaScalar};
fn b(s: &str) -> LuaScalar {
    LuaScalar::Bytes(s.as_bytes().to_vec())
}
fn i(n: i64) -> LuaScalar {
    LuaScalar::Integer(n)
}
fn n(v: f64) -> LuaScalar {
    LuaScalar::Number(v)
}

#[test]
fn number_at_preserves_signs_decimals_and_separate_values() {
    for (source, expected) in [
        ("100(+100) 180", vec!["100", "+100", "180"]),
        (
            "-12.50(+.5) [00180] / +3%",
            vec!["-12.50", "+.5", "00180", "+3"],
        ),
        ("A12 B34:56", vec!["12", "34", "56"]),
        ("100(+100)180", vec!["100", "+100", "180"]),
        ("abc()+-", vec![]),
    ] {
        for (index, value) in expected.iter().enumerate() {
            assert_eq!(
                execute(14237, &[b(source), i((index + 1) as i64)]).unwrap(),
                b(value)
            );
        }
        assert_eq!(
            execute(14237, &[b(source), i((expected.len() + 1) as i64)]).unwrap(),
            b("")
        );
    }
}

#[test]
fn number_at_rejects_wrong_types_and_whole_over_budget_sources() {
    for args in [
        vec![b("1"), i(0)],
        vec![b("1"), i(65)],
        vec![b("1"), n(1.0)],
        vec![i(123), i(1)],
        vec![b(&"1 ".repeat(65)), i(1)],
        vec![b(&"x".repeat(4097)), i(1)],
    ] {
        assert!(execute(14237, &args).is_err(), "{args:?}");
    }
    assert_eq!(
        execute(14237, &[b(&"1 ".repeat(64)), i(64)]).unwrap(),
        b("1")
    );
    assert_eq!(
        execute(14237, &[b(&"x".repeat(4096)), i(1)]).unwrap(),
        b("")
    );
    assert!(execute(14237, &[LuaScalar::Bytes(vec![255]), i(1)]).is_err());
}

#[test]
fn fixed_math_operations_and_domains() {
    for (op, value, expected) in [
        ("abs", -2.0, 2.0),
        ("ceil", -2.5, -2.0),
        ("floor", -2.5, -3.0),
        ("sqrt", 9.0, 3.0),
        ("ln", 1.0, 0.0),
        ("log10", 100.0, 2.0),
        ("exp", 0.0, 1.0),
        ("sin", 0.0, 0.0),
        ("cos", 0.0, 1.0),
        ("tan", 0.0, 0.0),
        ("asin", 0.0, 0.0),
        ("acos", 1.0, 0.0),
        ("atan", 0.0, 0.0),
        ("deg", std::f64::consts::PI, 180.0),
        ("rad", 180.0, std::f64::consts::PI),
    ] {
        assert_eq!(
            execute(1022, &[b(op), n(value)]).unwrap(),
            n(expected),
            "{op}"
        );
    }
    for (op, a, c, expected) in [
        ("min", 2, 3, 2.0),
        ("max", 2, 3, 3.0),
        ("pow", 2, 3, 8.0),
        ("atan2", 1, 0, std::f64::consts::FRAC_PI_2),
        ("log", 8, 2, 3.0),
    ] {
        assert_eq!(execute(1023, &[b(op), i(a), i(c)]).unwrap(), n(expected));
    }
    for (op, value) in [
        ("sqrt", -1.0),
        ("ln", 0.0),
        ("log10", -1.0),
        ("acos", 2.0),
        ("asin", -2.0),
        ("exp", 100.0),
        ("abs", f64::INFINITY),
        ("abs", f64::NAN),
        ("abs", 1e12 + 1.0),
        ("unknown", 1.0),
    ] {
        assert!(execute(1022, &[b(op), n(value)]).is_err(), "{op}");
    }
    for (op, a, c) in [
        ("atan2", 0.0, 0.0),
        ("log", 8.0, 1.0),
        ("log", -1.0, 2.0),
        ("pow", -1.0, 0.5),
        ("pow", 1e12, 2.0),
        ("pow", 0.0, -1.0),
    ] {
        assert!(execute(1023, &[b(op), n(a), n(c)]).is_err());
    }
    assert!(execute(1022, &[b("abs"), b("3")]).is_err());
    assert!(execute(1023, &[b("min"), i(1)]).is_err());
    assert!(execute(999, &[]).is_err());
}
#[test]
fn decimals_and_zero_padding_preserve_sign_and_exact_digit_contract() {
    for (v, d, expected) in [
        (1.25, 1, 1.3),
        (-1.25, 1, -1.3),
        (3.5, 0, 4.0),
        (-3.5, 0, -4.0),
        (-0.01, 0, 0.0),
        (1e12, 9, 1e12),
    ] {
        assert_eq!(execute(1024, &[n(v), i(d)]).unwrap(), n(expected));
    }
    for (v, d, w, expected) in [
        (12.5, 2, 4, "0012.50"),
        (-12.5, 2, 4, "-0012.50"),
        (-0.001, 2, 1, "0.00"),
        (9.999, 2, 1, "10.00"),
        (1.25, 1, 1, "1.3"),
        (-1.25, 1, 1, "-1.3"),
        (1e12, 0, 1, "1000000000000"),
    ] {
        assert_eq!(execute(14225, &[n(v), i(d), i(w)]).unwrap(), b(expected));
    }
    assert_eq!(
        execute(14225, &[i(1), i(9), i(32)]).unwrap(),
        b(&format!("{}1.000000000", "0".repeat(31)))
    );
    for args in [
        vec![i(1), i(10), i(1)],
        vec![i(1), i(-1), i(1)],
        vec![i(1), n(2.0), i(1)],
        vec![i(1), i(0), i(0)],
        vec![i(1), i(0), i(33)],
    ] {
        assert!(execute(14225, &args).is_err());
    }
}
#[test]
fn regex_replacement_is_literal_and_checks_each_expansion() {
    assert_eq!(
        execute(14226, &[b("甲12乙34"), b("[0-9]+"), b("$1")]).unwrap(),
        b("甲$1乙$1")
    );
    assert_eq!(
        execute(14226, &[b("aa"), b("^|$"), b("_")]).unwrap(),
        b("_aa_")
    );
    assert_eq!(execute(14226, &[b("a"), b("z"), b("x")]).unwrap(), b("a"));
    assert!(execute(14226, &[b("a"), b("(?=a)"), b("x")]).is_err());
    assert!(execute(14226, &[b("a"), b(""), b("x")]).is_err());
    assert!(execute(14226, &[b(&"a".repeat(1025)), b("a"), b("")]).is_err());
    assert!(execute(14226, &[b(&"a".repeat(17)), b("a"), b(&"x".repeat(4096))]).is_err());
    assert_eq!(
        execute(14226, &[b(&"a".repeat(16)), b("a"), b(&"x".repeat(4096))]).unwrap(),
        b(&"x".repeat(65_536))
    );
    assert!(execute(14226, &[b(&"x".repeat(4097)), b("x"), b("a")]).is_err());
    assert!(execute(14226, &[b("x"), b(&"x".repeat(129)), b("a")]).is_err());
}
#[test]
fn unicode_filter_reverse_and_invalid_utf8() {
    for (class, expected, removed) in [
        ("Digits", "12", "智构 A!１２\t😀"),
        ("Letters", "A", "智构 12!１２\t😀"),
        ("Alnum", "A12", "智构 !１２\t😀"),
        ("Han", "智构", " A12!１２\t😀"),
        ("Whitespace", " \t", "智构A12!１２😀"),
    ] {
        let source = "智构 A12!１２\t😀";
        assert_eq!(
            execute(14227, &[b(source), b(&format!("keep{class}"))]).unwrap(),
            b(expected)
        );
        assert_eq!(
            execute(14227, &[b(source), b(&format!("remove{class}"))]).unwrap(),
            b(removed)
        );
    }
    assert_eq!(
        execute(14227, &[b("𠀀中A"), b("keepHan")]).unwrap(),
        b("𠀀中")
    );
    assert_eq!(execute(14228, &[b("中文😀a")]).unwrap(), b("a😀文中"));
    assert_eq!(execute(14228, &[b("a\u{301}")]).unwrap(), b("\u{301}a"));
    assert!(execute(14227, &[b("x"), b("unknown")]).is_err());
    assert!(execute(14228, &[LuaScalar::Bytes(vec![0xff])]).is_err());
    assert!(execute(14228, &[b(&"x".repeat(65_537))]).is_err());
}
#[test]
fn json_encoding_handles_dynamic_strings_and_budgets() {
    for source in ["", "智构😀", "\"\\\n\t\r\0\u{8}\u{c}", "x"] {
        let LuaScalar::Bytes(encoded) = execute(14011, &[b(source), b("text")]).unwrap() else {
            panic!()
        };
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&encoded).unwrap(),
            serde_json::json!(source)
        );
    }
    for (source, expected) in [
        ("  [ 1, true, null ] ", "[1,true,null]"),
        ("3.5", "3.5"),
        ("null", "null"),
        ("true", "true"),
    ] {
        assert_eq!(
            execute(14011, &[b(source), b("json")]).unwrap(),
            b(expected)
        );
    }
    assert!(execute(14011, &[b("os.execute('bad')"), b("json")]).is_err());
    assert!(execute(14011, &[b("x"), b("unknown")]).is_err());
    assert!(execute(14011, &[b(&"\0".repeat(11_000)), b("text")]).is_err());
    assert!(execute(14011, &[b(&"x".repeat(65_535)), b("text")]).is_err());
    assert!(execute(
        14011,
        &[
            b(&format!("{}0{}", "[".repeat(17), "]".repeat(17))),
            b("json")
        ]
    )
    .is_err());
    assert!(execute(14011, &[b("{\"\":1}"), b("json")]).is_err());
}
#[test]
fn sorting_is_new_typed_data_and_preserves_integer_representation() {
    for (source, mode, expected) in [
        ("[3,1,2,1]", "numberAsc", "[1,1,2,3]"),
        (
            "[-2,1.5,1000000000000]",
            "numberDesc",
            "[1000000000000,1.5,-2]",
        ),
        ("[\"b\",\"a\",\"中\"]", "textAsc", "[\"a\",\"b\",\"中\"]"),
        ("[\"b\",\"a\",\"中\"]", "textDesc", "[\"中\",\"b\",\"a\"]"),
        ("[]", "numberAsc", "[]"),
    ] {
        assert_eq!(execute(14012, &[b(source), b(mode)]).unwrap(), b(expected));
    }
    for source in ["[1,\"2\"]", "[null]", "[true]", "[[1]]", "{}", "[1e13]"] {
        assert!(execute(14012, &[b(source), b("numberAsc")]).is_err());
    }
    assert!(execute(14012, &[b("[1]"), b("textAsc")]).is_err());
    assert!(execute(14012, &[b("[]"), b("unknown")]).is_err());
    assert!(execute(
        14012,
        &[
            b(&format!("[{}]", vec!["1"; 1025].join(","))),
            b("numberAsc")
        ]
    )
    .is_err());
}
#[test]
fn real_lua_wrappers_executor_capabilities_and_no_host_requests() {
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    let cases: &[(&[&str], &str, TaskState)] = &[
        (
            &["core.math", "core.task"],
            r#"return function()
            assert(Math.unary('sqrt',9)==3); assert(Math.binary('pow',2,3)==8)
            assert(Math.roundTo(-1.25,1)==-1.3); assert(Text.formatNumber(-12.5,2,4)=='-0012.50')
            assert(Text.regexReplace('a12b','[0-9]+','$1')=='a$1b')
            assert(Text.filter('A12中','keepDigits')=='12'); assert(Text.reverse('A中')=='中A')
            assert(Text.numberAt('100(+100) 180',1)=='100')
            assert(Text.numberAt('100(+100) 180',2)=='+100')
            assert(Text.numberAt('100(+100) 180',3)=='180')
            assert(Text.numberAt('100(+100) 180',4)=='')
            local encoded=Data.encode('"中','text'); assert(Data.text(encoded)=='"中')
            assert(Data.encode('[ 3, 1 ]','json')=='[3,1]'); assert(Data.sort('[3,1,2]','numberAsc')=='[1,2,3]')
            assert(not pcall(Math.unary,'sqrt',-1)); assert(not pcall(Data.sort,'[1,true]','numberAsc'))
            assert(Math.round(-3.5)==-4)
        end"#,
            TaskState::Completed,
        ),
        (
            &["core.task"],
            "return function() Math.unary('sqrt',9) end",
            TaskState::Failed,
        ),
        (
            &["core.math"],
            "return function() Text.reverse('abc') end",
            TaskState::Failed,
        ),
    ];
    for (capabilities, source, expected) in cases {
        let mut rt = crate::RuntimeExecutor::new(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            crate::ExecutorConfig::default(),
            crate::VirtualHost::new(720, 1280),
        )
        .unwrap();
        rt.set_allowed_capabilities(&capabilities.iter().map(|v| (*v).into()).collect::<Vec<_>>())
            .unwrap();
        let owner = rt
            .start_entry_chunk(source.as_bytes(), "pure-utility-qa")
            .unwrap();
        for _ in 0..32 {
            rt.drive().unwrap();
        }
        assert_eq!(
            rt.task_state(owner),
            Some(*expected),
            "{:?}",
            rt.task_failure(owner)
        );
        assert!(rt.backend().requests().is_empty());
    }
}
