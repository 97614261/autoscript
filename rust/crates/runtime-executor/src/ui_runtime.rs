//! UI properties are data, never Android member names.

pub(crate) const MAX_UI_PROPERTY_BYTES: usize = 32_768;
pub(crate) fn list_write(operation: &str) -> bool {
    matches!(
        operation,
        "itemAdd"
            | "itemInsert"
            | "itemSet"
            | "itemRemove"
            | "itemsClear"
            | "selectedIndex"
            | "selectedIndices"
    )
}

pub(crate) fn host_operation(operation: &str, value: &str) -> Result<bool, &'static str> {
    if let Some(result) = extension_operation(operation, value) {
        return result;
    }
    if operation == "get:config" {
        return if value.is_empty() {
            Ok(true)
        } else {
            Err("config read requires empty value")
        };
    }
    if operation == "restoreConfig" {
        return if value.len() <= 8192 && !value.contains('\0') {
            Ok(true)
        } else {
            Err("UI config exceeds byte budget")
        };
    }
    if matches!(operation, "hint" | "minimum" | "maximum" | "step" | "range") {
        return input_property(operation, value);
    }
    if list_write(operation) {
        if value.len() > 8192 || value.contains('\0') {
            return Err("UI list payload exceeds budget");
        }
        return Ok(true);
    }
    if let Some(property) = operation.strip_prefix("get:") {
        if !valid_read_property(property) || !value.is_empty() {
            return Err("invalid UI property read");
        }
        return Ok(true);
    }
    let range = match operation {
        "x" => Some(0..=4096),
        "y" => Some(0..=8192),
        "width" => Some(20..=4096),
        "height" => Some(20..=8192),
        "fontPx" => Some(8..=160),
        "borderWidthPx" => Some(0..=32),
        "cornerPx" => Some(0..=512),
        _ => None,
    };
    if let Some(range) = range {
        if !value
            .parse::<i32>()
            .is_ok_and(|number| range.contains(&number))
        {
            return Err("invalid UI design pixel property");
        }
        return Ok(true);
    }
    if matches!(operation, "textColor" | "background" | "borderColor") {
        if value.len() != 9
            || !value.starts_with('#')
            || !value.as_bytes()[1..].iter().all(u8::is_ascii_hexdigit)
        {
            return Err("invalid UI color");
        }
        return Ok(true);
    }
    if operation == "alignment" {
        if !matches!(value, "left" | "center" | "right") {
            return Err("invalid UI alignment");
        }
        return Ok(true);
    }
    if matches!(operation, "fontBold" | "textUnderline") {
        return if matches!(value, "true" | "false") {
            Ok(true)
        } else {
            Err("invalid UI text style")
        };
    }
    Ok(false)
}
fn extension_operation(operation: &str, value: &str) -> Option<Result<bool, &'static str>> {
    let valid = match operation {
        "get:backgroundImage"
        | "get:groupCount"
        | "get:windowPosition"
        | "get:bubblePosition"
        | "groupReset" => value.is_empty(),
        "groupVisible" | "groupEnabled" => matches!(value, "true" | "false"),
        "backgroundImage" => {
            value.is_empty()
                || (value.len() <= 256
                    && value.starts_with("assets/images/")
                    && !value.contains('\\')
                    && !value.chars().any(char::is_control)
                    && value
                        .split('/')
                        .all(|part| !matches!(part, "" | "." | "..")))
        }
        "windowPosition" | "bubblePosition" => valid_position(value),
        _ => return None,
    };
    Some(if valid {
        Ok(true)
    } else {
        Err("invalid UI extension value")
    })
}
fn valid_position(value: &str) -> bool {
    if value.len() > 128 {
        return false;
    }
    let Ok(json) = serde_json::from_str::<serde_json::Value>(value) else {
        return false;
    };
    json.as_object().is_some_and(|object| {
        object.len() == 2
            && object.contains_key("x")
            && object.contains_key("y")
            && object
                .values()
                .all(|number| number.as_u64().is_some_and(|n| n <= 32768))
    })
}
fn input_property(operation: &str, value: &str) -> Result<bool, &'static str> {
    if operation == "hint" {
        if value.chars().count() > 256 || value.contains('\0') {
            return Err("invalid input hint");
        }
    } else if operation == "range" {
        if value.len() > 256 {
            return Err("range exceeds budget");
        }
        let json: serde_json::Value =
            serde_json::from_str(value).map_err(|_| "invalid range JSON")?;
        let map = json.as_object().ok_or("range must be object")?;
        if !map.contains_key("minimum")
            || !map.contains_key("maximum")
            || map
                .keys()
                .any(|k| !matches!(k.as_str(), "minimum" | "maximum" | "step"))
        {
            return Err("invalid range fields");
        }
        for v in map.values() {
            if v.as_i64()
                .is_none_or(|n| !(-1_000_000_000..=1_000_000_000).contains(&n))
            {
                return Err("range requires bounded integer");
            }
        }
    } else {
        let number = value
            .parse::<i64>()
            .map_err(|_| "input property requires integer")?;
        if !(-1_000_000_000..=1_000_000_000).contains(&number)
            || (operation == "step" && !(1..=1_000_000).contains(&number))
        {
            return Err("input property outside range");
        }
    }
    Ok(true)
}

fn valid_read_property(property: &str) -> bool {
    matches!(
        property,
        "value"
            | "itemCount"
            | "selectedIndex"
            | "selectedIndices"
            | "text"
            | "visible"
            | "enabled"
            | "items"
            | "progress"
            | "x"
            | "y"
            | "width"
            | "height"
            | "fontPx"
            | "textColor"
            | "background"
            | "alignment"
            | "fontBold"
            | "textUnderline"
            | "borderColor"
            | "borderWidthPx"
            | "cornerPx"
            | "hint"
            | "minimum"
            | "maximum"
            | "step"
    )
}

#[cfg(test)]
mod tests {
    use super::host_operation;
    use crate::{
        ExecutorConfig, ExternalHost, ExternalHostEvent, ExternalHostQueue, RuntimeExecutor,
    };
    use lua_runtime::{LuaRuntimeConfig, LuaScalar};
    use runtime_scheduler::{
        HostCompletion, HostResult, ManualClock, MonoTime, SchedulerConfig, TaskState,
    };
    use std::time::Duration;

    #[test]
    fn extensions_are_fixed_bounded_and_do_not_accept_arbitrary_paths() {
        for (operation, value) in [
            ("windowPosition", r#"{"x":0,"y":32768}"#),
            ("backgroundImage", "assets/images/menu/header.png"),
            ("backgroundImage", ""),
            ("groupVisible", "false"),
            ("groupReset", ""),
            ("get:groupCount", ""),
        ] {
            assert_eq!(host_operation(operation, value), Ok(true));
        }
        for (operation, value) in [
            ("bubblePosition", r#"{"x":-1,"y":0}"#),
            ("windowPosition", r#"{"x":0,"y":0,"z":0}"#),
            ("groupEnabled", "1"),
            ("groupReset", "anything"),
            ("backgroundImage", "assets/images/../secret"),
            ("backgroundImage", "/data/secret.png"),
            ("get:windowPosition", "1"),
        ] {
            assert!(host_operation(operation, value).is_err());
        }
    }

    fn external(
        clock: &ManualClock,
        queue: &ExternalHostQueue,
    ) -> RuntimeExecutor<ManualClock, ExternalHost> {
        RuntimeExecutor::new(
            clock.clone(),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            ExternalHost::new(queue.clone()),
        )
        .unwrap()
    }
    #[test]
    fn input_operations_are_typed_and_config_restore_changes_reads_to_host() {
        for (op, value) in [
            ("hint", "请输入"),
            ("minimum", "-20"),
            ("maximum", "100"),
            ("step", "5"),
            ("range", r#"{"minimum":0,"maximum":100,"step":5}"#),
            ("get:config", ""),
            ("restoreConfig", "{}"),
        ] {
            assert_eq!(host_operation(op, value), Ok(true));
        }
        let oversized_hint = "中".repeat(257);
        for (op, value) in [
            ("hint", oversized_hint.as_str()),
            ("step", "0"),
            ("maximum", "1.5"),
            ("range", r#"{"minimum":"0","maximum":10}"#),
            ("get:config", "extra"),
        ] {
            assert!(host_operation(op, value).is_err());
        }
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = external(&clock, &queue);
        let task=runtime.start_entry_chunk(b"return function() UI.command('window','restoreConfig','{}'); current=UI.getValue('number') end","restored-current-values").unwrap();
        runtime.drive().unwrap();
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("restore not dispatched")
        };
        assert_eq!(request.args[1], LuaScalar::Bytes(b"restoreConfig".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(Vec::new()),
            })
            .unwrap();
        runtime.drive().unwrap();
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("read did not use restored host values")
        };
        assert_eq!(request.args[1], LuaScalar::Bytes(b"get:value".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"7".to_vec()),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("current").unwrap(),
            LuaScalar::Bytes(b"7".to_vec())
        );
    }
    #[test]
    fn list_writes_wait_for_host_and_following_get_reads_current_value_not_seed() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = external(&clock, &queue);
        runtime
            .seed_ui_values(std::collections::BTreeMap::from([(
                "list".into(),
                "old".into(),
            )]))
            .unwrap();
        let task=runtime.start_entry_chunk(b"return function() UI.command('list','selectedIndex','2'); fresh=UI.getValue('list') end","list-current-value").unwrap();
        runtime.drive().unwrap();
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("list write did not reach host")
        };
        assert_eq!(request.opcode, 1203);
        assert_eq!(request.args[1], LuaScalar::Bytes(b"selectedIndex".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(Vec::new()),
            })
            .unwrap();
        runtime.drive().unwrap();
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue
            .wait_next_timeout(Duration::from_millis(10))
            .unwrap_or_else(|| {
                panic!(
                    "{:?} {:?}",
                    runtime.task_state(task),
                    runtime.task_failure(task)
                )
            })
        else {
            panic!("UI.get did not read actual host state")
        };
        assert_eq!(request.opcode, 1203);
        assert_eq!(request.args[1], LuaScalar::Bytes(b"get:value".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"new".to_vec()),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("fresh").unwrap(),
            LuaScalar::Bytes(b"new".to_vec())
        );
    }

    #[test]
    fn accepts_only_bounded_design_data_and_fixed_getters() {
        for (operation, value) in [
            ("x", "4096"),
            ("y", "8192"),
            ("width", "20"),
            ("height", "8192"),
            ("fontPx", "160"),
            ("textColor", "#8020aB43"),
            ("background", "#00000000"),
            ("alignment", "left"),
            ("get:x", ""),
            ("get:items", ""),
            ("fontBold", "true"),
            ("textUnderline", "false"),
            ("borderWidthPx", "32"),
            ("cornerPx", "512"),
            ("borderColor", "#00000000"),
            ("get:fontBold", ""),
        ] {
            assert_eq!(host_operation(operation, value), Ok(true));
        }
        for (operation, value) in [
            ("x", "-1"),
            ("y", "8193"),
            ("width", "19"),
            ("height", "8193"),
            ("fontPx", "161"),
            ("textColor", "#FFFFFF"),
            ("background", "red"),
            ("alignment", "top"),
            ("get:context", ""),
            ("get:x", "1"),
            ("get:show", ""),
            ("fontBold", "1"),
            ("textUnderline", "TRUE"),
            ("borderWidthPx", "33"),
            ("cornerPx", "-1"),
            ("borderColor", "transparent"),
            ("get:cornerPx", "1"),
        ] {
            assert!(host_operation(operation, value).is_err(), "{operation}");
        }
        assert_eq!(host_operation("text", "old API"), Ok(false));
        assert_eq!(host_operation("show", ""), Ok(false));
    }

    #[test]
    fn property_writes_and_reads_wait_for_actual_host_completion() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = external(&clock, &queue);
        let owner = runtime.start_entry_chunk(b"return function() written=UI.command('label','x','77'); result=UI.command('label','get:textColor',''); done=true end", "ui-properties").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.lua().global_scalar("done").unwrap(), LuaScalar::Nil);
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("UI write was not dispatched")
        };
        assert_eq!(request.opcode, 1203);
        assert_eq!(request.timeout, Duration::from_secs(3));
        assert_eq!(request.args[1], LuaScalar::Bytes(b"x".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(vec![]),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(
            runtime.lua().global_scalar("written").unwrap(),
            LuaScalar::Nil
        );
        assert_eq!(
            runtime.lua().global_scalar("result").unwrap(),
            LuaScalar::Nil
        );
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("UI read was not dispatched")
        };
        assert_eq!(request.args[1], LuaScalar::Bytes(b"get:textColor".to_vec()));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"#8020aB43".to_vec()),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("result").unwrap(),
            LuaScalar::Bytes(b"#8020aB43".to_vec())
        );
        assert_eq!(
            runtime.lua().global_scalar("done").unwrap(),
            LuaScalar::Boolean(true)
        );
        assert!(runtime.drain_script_prompts().is_empty());
    }

    #[test]
    fn cancelled_property_read_discards_late_host_result() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = external(&clock, &queue);
        let owner = runtime
            .start_entry_chunk(
                b"return function() result=UI.command('label','get:x','') end",
                "ui-cancel-property",
            )
            .unwrap();
        runtime.drive().unwrap();
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = queue.wait_next_timeout(Duration::from_millis(10)).unwrap()
        else {
            panic!("UI read was not dispatched")
        };
        runtime.handle().request_cancel(owner).unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Cancelled));
        assert!(matches!(
            queue.wait_next_timeout(Duration::from_millis(10)),
            Some(ExternalHostEvent::Cancel { .. })
        ));
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(b"77".to_vec()),
            })
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(
            runtime.lua().global_scalar("result").unwrap(),
            LuaScalar::Nil
        );
    }

    #[test]
    fn property_read_has_bounded_timeout_and_requires_ui_capability() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let queue = ExternalHostQueue::new(4, 4).unwrap();
        let mut runtime = external(&clock, &queue);
        let owner = runtime
            .start_entry_chunk(
                b"return function() result=UI.command('label','get:x','') end",
                "ui-timeout-property",
            )
            .unwrap();
        runtime.drive().unwrap();
        let _ = queue.wait_next_timeout(Duration::from_millis(10)).unwrap();
        clock.advance(Duration::from_secs(4));
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(owner), Some(TaskState::Failed));
        assert!(matches!(
            queue.wait_next_timeout(Duration::from_millis(10)),
            Some(ExternalHostEvent::Cancel { .. })
        ));
        let mut denied = external(&clock, &ExternalHostQueue::new(4, 4).unwrap());
        denied
            .set_allowed_capabilities(&["core.task".to_owned()])
            .unwrap();
        let owner = denied
            .start_entry_chunk(
                b"return function() allowed=pcall(UI.command,'label','get:x','') end",
                "ui-property-denied",
            )
            .unwrap();
        denied.drive().unwrap();
        assert_eq!(denied.task_state(owner), Some(TaskState::Completed));
        assert_eq!(
            denied.lua().global_scalar("allowed").unwrap(),
            LuaScalar::Boolean(false)
        );
    }

    #[test]
    fn host_property_result_rejects_invalid_utf8_nuls_and_over_budget() {
        for payload in [vec![255], vec![0], vec![b'A'; 32769]] {
            assert!(crate::host_resume_input(1203, &HostResult::Success(payload)).is_err());
        }
    }
}
