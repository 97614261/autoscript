//! UI properties are data, never Android member names.

pub(crate) const MAX_UI_PROPERTY_BYTES: usize = 32_768;

pub(crate) fn host_operation(operation: &str, value: &str) -> Result<bool, &'static str> {
    if let Some(property) = operation.strip_prefix("get:") {
        if !matches!(
            property,
            "value"
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
        ) || !value.is_empty()
        {
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
