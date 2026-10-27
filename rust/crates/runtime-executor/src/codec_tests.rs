use crate::codec_runtime::execute;
use lua_runtime::LuaScalar;
fn b(text: &str) -> LuaScalar {
    LuaScalar::Bytes(text.as_bytes().to_vec())
}
#[test]
fn codecs_roundtrip_unicode_and_exact_variants() {
    for source in [
        "",
        "f",
        "fo",
        "foo",
        "foob",
        "fooba",
        "foobar",
        "智构😀 +/%",
        "\0\n\r",
    ] {
        for (encode, decode) in [(14231, 14232), (14233, 14234)] {
            let encoded = execute(encode, &[b(source)]).unwrap();
            assert_eq!(execute(decode, &[encoded]).unwrap(), b(source));
        }
        for mode in ["component", "form"] {
            let encoded = execute(14229, &[b(source), b(mode)]).unwrap();
            assert_eq!(execute(14230, &[encoded, b(mode)]).unwrap(), b(source));
        }
    }
    assert_eq!(
        execute(14229, &[b("A +/~"), b("component")]).unwrap(),
        b("A%20%2B%2F~")
    );
    assert_eq!(
        execute(14229, &[b("A +/~"), b("form")]).unwrap(),
        b("A+%2B%2F~")
    );
    assert_eq!(execute(14230, &[b("+%2B"), b("form")]).unwrap(), b(" +"));
    assert_eq!(
        execute(14230, &[b("+%2B"), b("component")]).unwrap(),
        b("++")
    );
    assert_eq!(execute(14233, &[b("foobar")]).unwrap(), b("Zm9vYmFy"));
    assert_eq!(execute(14231, &[b("智构")]).unwrap(), b("E699BAE69E84"));
}
#[test]
fn malformed_encodings_utf8_and_expansion_are_rejected() {
    for source in [
        "Zg", "Zg=", "Zg===", "Zh==", "Zm9=", "=m9v", "Zg==Zg==", "Zg==\n", "-w==", "/w==",
    ] {
        assert!(execute(14234, &[b(source)]).is_err(), "{source}");
    }
    for source in ["0", "GG", "0x20", "FF", "C080"] {
        assert!(execute(14232, &[b(source)]).is_err());
    }
    for source in ["%", "%2", "%zz", "%FF", "%C0%80"] {
        assert!(execute(14230, &[b(source), b("component")]).is_err());
    }
    assert!(execute(14229, &[b(&" ".repeat(21_846)), b("component")]).is_err());
    assert!(execute(14231, &[b(&"a".repeat(32_769))]).is_err());
    assert!(execute(14233, &[b(&"a".repeat(49_153))]).is_err());
    let LuaScalar::Bytes(encoded) = execute(14233, &[b(&"a".repeat(49_152))]).unwrap() else {
        panic!("bytes expected")
    };
    assert_eq!(encoded.len(), 65_536);
    assert!(execute(14229, &[b("a"), b("unknown")]).is_err());
    assert!(execute(14231, &[LuaScalar::Bytes(vec![0xff])]).is_err());
}
#[test]
fn nth_occurrence_is_unicode_nonoverlapping_and_bounded() {
    assert_eq!(
        execute(14235, &[b("😀a甲a"), b("a"), LuaScalar::Integer(2)]).unwrap(),
        LuaScalar::Integer(4)
    );
    assert_eq!(
        execute(14235, &[b("aaa"), b("aa"), LuaScalar::Integer(2)]).unwrap(),
        LuaScalar::Integer(0)
    );
    assert_eq!(
        execute(
            14236,
            &[b("[甲][乙]"), b("["), b("]"), LuaScalar::Integer(2)]
        )
        .unwrap(),
        b("乙")
    );
    assert_eq!(
        execute(14236, &[b("[甲"), b("["), b("]"), LuaScalar::Integer(1)]).unwrap(),
        b("")
    );
    for n in [0, 1025] {
        assert!(execute(14235, &[b("aa"), b("a"), LuaScalar::Integer(n)]).is_err());
    }
    assert!(execute(14235, &[b("aa"), b(""), LuaScalar::Integer(1)]).is_err());
}
#[test]
fn path_tools_never_escape_private_relative_namespace() {
    assert_eq!(
        execute(14402, &[b("a//./子/b.png")]).unwrap(),
        b("a/子/b.png")
    );
    assert_eq!(execute(14400, &[b("a/b.png")]).unwrap(), b("b.png"));
    assert_eq!(execute(14401, &[b("a/b.png")]).unwrap(), b("a"));
    assert_eq!(execute(14401, &[b("b.png")]).unwrap(), b(""));
    assert_eq!(
        execute(14403, &[b("a/./"), b("子/b.png")]).unwrap(),
        b("a/子/b.png")
    );
    for source in [
        "/absolute",
        "../escape",
        "a/../b",
        "C:/bad",
        "a\\b",
        ".hidden",
        "a/尾.",
        "a/尾 ",
        "a\0b",
    ] {
        assert!(execute(14402, &[b(source)]).is_err(), "{source}");
    }
    assert!(execute(14403, &[b("safe"), b("/bad")]).is_err());
    assert!(execute(14402, &[b(&vec!["a"; 17].join("/"))]).is_err());
}
