use crate::analyze::ComparisonOperator;
use crate::model::CompileError;
use serde_json::Value;

pub(crate) fn valid(value: &Value) -> bool {
    let mut count = 0;
    serde_json::to_vec(value).is_ok_and(|v| v.len() <= 8192) && walk(value, 1, &mut count)
}
fn name(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|s| {
        let mut b = s.bytes();
        s.len() <= 64
            && b.next()
                .is_some_and(|b| b.is_ascii_alphabetic() || b == b'_')
            && b.all(|b| b.is_ascii_alphanumeric() || b == b'_')
    })
}
fn walk(v: &Value, depth: usize, count: &mut usize) -> bool {
    *count += 1;
    if depth > 8 || *count > 64 {
        return false;
    }
    let Some(o) = v.as_object() else {
        return false;
    };
    match o.get("type").and_then(Value::as_str) {
        Some("compare") => {
            o.keys().all(|k| {
                matches!(
                    k.as_str(),
                    "type" | "variable" | "operator" | "value" | "valueVariable"
                )
            }) && name(o.get("variable"))
                && o.get("operator").is_some_and(|op| {
                    serde_json::from_value::<ComparisonOperator>(op.clone()).is_ok()
                })
                && o.get("value")
                    .is_some_and(|v| !v.is_array() && !v.is_object())
                && (!o.contains_key("valueVariable") || name(o.get("valueVariable")))
                && (o.contains_key("valueVariable")
                    || matches!(
                        o.get("operator").and_then(Value::as_str),
                        Some("equals" | "notEquals")
                    )
                    || o.get("value")
                        .is_some_and(|v| v.is_number() || v.is_string()))
        }
        Some("all" | "any") => {
            o.len() == 2
                && o.get("conditions")
                    .and_then(Value::as_array)
                    .is_some_and(|a| {
                        !a.is_empty()
                            && a.len() <= 64
                            && a.iter().all(|v| walk(v, depth + 1, count))
                    })
        }
        Some("not") => {
            o.len() == 2
                && o.get("condition")
                    .is_some_and(|v| walk(v, depth + 1, count))
        }
        _ => false,
    }
}
pub(crate) fn variable_pairs<'a>(v: &'a Value, out: &mut Vec<(&'a str, Option<&'a str>)>) {
    match v["type"].as_str() {
        Some("compare") => out.push((
            v["variable"].as_str().expect("validated name"),
            v.get("valueVariable").and_then(Value::as_str),
        )),
        Some("all" | "any") => {
            for child in v["conditions"].as_array().expect("validated children") {
                variable_pairs(child, out);
            }
        }
        Some("not") => variable_pairs(&v["condition"], out),
        _ => unreachable!("validated condition"),
    }
}
pub(crate) fn render(v: &Value) -> Result<String, CompileError> {
    match v["type"].as_str() {
        Some("compare") => crate::render::comparison_expression(
            v["variable"].as_str().expect("validated name"),
            serde_json::from_value(v["operator"].clone()).expect("validated operator"),
            &v["value"],
            v.get("valueVariable").and_then(Value::as_str),
        ),
        Some("all" | "any") => {
            let parts = v["conditions"]
                .as_array()
                .expect("validated children")
                .iter()
                .map(render)
                .collect::<Result<Vec<_>, _>>()?;
            Ok(format!(
                "({})",
                parts.join(if v["type"] == "all" { " and " } else { " or " })
            ))
        }
        Some("not") => Ok(format!("(not {})", render(&v["condition"])?)),
        _ => unreachable!("validated condition"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    #[test]
    fn strict_shapes_depth_terms_and_escape_are_bounded() {
        let leaf = json!({"type":"compare","variable":"x","operator":"equals","value":1});
        assert!(valid(&leaf));
        assert!(valid(
            &json!({"type":"any","conditions":[leaf.clone(),{"type":"not","condition":leaf.clone()}]})
        ));
        for bad in [
            json!({"type":"all","conditions":[]}),
            json!({"type":"all","conditions":[leaf.clone()],"unknown":true}),
            json!({"type":"compare","variable":"x;os.exit()","operator":"equals","value":1}),
            json!({"type":"compare","variable":"x","operator":"lessThan","value":false}),
            json!({"type":"not","condition":leaf.clone(),"unknown":1}),
        ] {
            assert!(!valid(&bad));
        }
        assert!(!valid(
            &json!({"type":"all","conditions":vec![leaf.clone();64]})
        ));
        let mut deep = leaf;
        for _ in 0..7 {
            deep = json!({"type":"not","condition":deep});
        }
        assert!(valid(&deep));
        assert!(!valid(&json!({"type":"not","condition":deep})));
    }
}
