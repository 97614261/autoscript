use crate::project::{validate_runner_ui, RunnerUi};
use serde_json::json;

#[test]
fn optional_ui_styles_are_strict_bounded_and_backward_compatible() {
    let base = json!({"version":2,"description":null,"pages":[{"id":"main","title":"main","width":720,"height":960,"background":"#FFFFFFFF"}],"fields":[{"id":"label","label":"text","kind":"text","required":false,"initialValue":"","minimum":null,"maximum":null,"options":[],"ui":{"control":"LABEL","pageId":"main","x":0,"y":0,"width":40,"height":40,"fontPx":12,"textColor":"#FF000000","background":"#FFFFFFFF","alignment":"center","visible":true,"enabled":true,"events":{}}}]});
    assert!(validate_runner_ui(Some(&serde_json::from_value(base.clone()).unwrap())).is_ok());
    for (key, value, valid) in [
        ("fontBold", json!(true), true),
        ("textUnderline", json!(false), true),
        ("borderColor", json!("#80aBcD12"), true),
        ("borderWidthPx", json!(32), true),
        ("cornerPx", json!(512), true),
        ("fontBold", json!("true"), false),
        ("textUnderline", json!(1), false),
        ("borderColor", json!("red"), false),
        ("borderWidthPx", json!(33), false),
        ("cornerPx", json!(-1), false),
        ("cornerPx", json!(null), false),
    ] {
        let mut current = base.clone();
        current["fields"][0]["ui"][key] = value;
        assert_eq!(
            validate_runner_ui(Some(&serde_json::from_value(current).unwrap())).is_ok(),
            valid,
            "{key}"
        );
    }
}

#[test]
fn ui_v2_allows_167_and_256_controls_but_257_and_old_33_fail() {
    for (size, version, valid) in [
        (167, 2, true),
        (256, 2, true),
        (257, 2, false),
        (33, 1, false),
    ] {
        let fields = (0..size).map(|n| json!({"id":format!("c{n}"),"label":"field","kind":"text","required":false,"initialValue":"","minimum":null,"maximum":null,"options":[],
            "ui": if version == 2 {json!({"control":"INPUT","pageId":"main","x":0,"y":0,"width":40,"height":40,"fontPx":12,"textColor":"#FF000000","background":"#FFFFFFFF","alignment":"left","visible":true,"enabled":true,"binding":null,"events":{}})} else {serde_json::Value::Null}})).collect::<Vec<_>>();
        let mut value = json!({"description":null,"fields":fields});
        if version == 2 {
            value["version"] = json!(2);
            value["pages"] = json!([{"id":"main","title":"main","width":720,"height":960,"background":"#FFFFFFFF"}]);
        }
        let ui: RunnerUi = serde_json::from_value(value).unwrap();
        assert_eq!(
            validate_runner_ui(Some(&ui)).is_ok(),
            valid,
            "size={size}, version={version}"
        );
    }
}
