//! Bounded plain-language action records from actual runtime arguments and replies.
use super::{LuaInput, LuaScalar, NativeVisionRequest};
use serde_json::{json, Value};

pub(super) fn fields(
    opcode: u32,
    args: &[LuaScalar],
    native: Option<&NativeVisionRequest>,
) -> Option<Value> {
    let number = |index: usize| match args.get(index) {
        Some(LuaScalar::Integer(value)) => value.to_string(),
        _ => "?".into(),
    };
    let point = |index| format!("({}, {})", number(index), number(index + 1));
    let region = |index| format!("区域 {} → {}", point(index), point(index + 2));
    let (operation, details) = match opcode {
        4000 | 4006 => ("点击", point(0)),
        4001 | 4010 => (
            "滑动",
            format!("{} → {} · {} 毫秒", point(0), point(2), number(4)),
        ),
        4002 => ("按键", format!("键值 {}", number(0))),
        4003 | 4007 => ("按下", point(0)),
        4004 | 4009 => ("移动触点", point(0)),
        4005 => ("抬起触点", String::new()),
        5000 | 5004 => ("截图", "当前屏幕".into()),
        5014 => ("截图", region(0)),
        5100 => ("查找颜色", region(3)),
        5101 | 5107 | 5114 => ("查找图片", region(4)),
        5102 => ("读取颜色", point(1)),
        5103 => ("比较颜色", point(1)),
        5104 => ("多点找色", region(3)),
        5105 => ("颜色计数", region(3)),
        5106 => ("查找全部颜色", region(3)),
        5108 | 5109 => {
            let name = if opcode == 5108 {
                "灰度找图"
            } else {
                "特征匹配"
            };
            let details = native
                .map(|v| {
                    format!(
                        "区域 ({}, {}) → ({}, {})",
                        v.region.left, v.region.top, v.region.right, v.region.bottom
                    )
                })
                .unwrap_or_else(|| region(3));
            (name, details)
        }
        6100 | 6102 => ("字库识别", region(5)),
        6101 | 6104 | 6105 => ("识别文字", region(1)),
        5123 => {
            let details = args
                .get(2)
                .and_then(|v| match v {
                    LuaScalar::Bytes(v) if v.len() <= 16384 => {
                        serde_json::from_slice::<Value>(v).ok()
                    }
                    _ => None,
                })
                .and_then(|v| {
                    let r = v.get("region")?;
                    Some(format!(
                        "区域 ({}, {}) → ({}, {})",
                        r.get("left")?.as_i64()?,
                        r.get("top")?.as_i64()?,
                        r.get("right")?.as_i64()?,
                        r.get("bottom")?.as_i64()?
                    ))
                })
                .unwrap_or_else(|| "区域参数未解析".into());
            ("多尺度找图", details)
        }
        2200 => ("录制触控", format!("最多 {} 毫秒", number(0))),
        4013 | 4014 => (
            if opcode == 4013 {
                "按下手指"
            } else {
                "移动手指"
            },
            format!("手指 {} · {}", number(0), point(1)),
        ),
        4015 => ("抬起手指", format!("手指 {}", number(0))),
        _ => return generic_fields(opcode, args),
    };
    let mut value = json!({"version":1,"kind":"action","opcode":opcode,"operation":operation,"details":details});
    if matches!(opcode, 4000..=4004 | 4006..=4007 | 4009..=4010) {
        value["coordinateSpace"] = json!(if matches!(opcode, 4006 | 4007 | 4009 | 4010) {
            "屏幕坐标"
        } else {
            "设计坐标"
        });
    }
    if (5100..=5114).contains(&opcode)
        && args.last().is_some_and(
            |v| matches!(v, LuaScalar::Bytes(bytes) if bytes.starts_with(b"@polygon:")),
        )
    {
        value["polygon"] = json!(true);
    }
    if native.is_some_and(|v| v.polygon.is_some()) {
        value["polygon"] = json!(true);
    }
    Some(value)
}

pub(super) fn complete(event: &mut Value, status: &str, input: Option<&LuaInput>) {
    let opcode = event["opcode"].as_u64().unwrap_or(0);
    let success = matches!(
        status,
        "found" | "completed" | "not_found" | "requested" | "disabled"
    );
    event["status"] = json!(if matches!(status, "found" | "not_found") {
        "completed"
    } else {
        status
    });
    let Some(LuaInput::Values(values)) = input else {
        if success && event["generic"].as_bool() == Some(true) {
            match input {
                Some(LuaInput::Integer(n)) => event["result"] = json!(n.to_string()),
                Some(LuaInput::Number(n)) => event["result"] = json!(n.to_string()),
                Some(LuaInput::Bytes(bytes)) => event["result"] = json!(data_preview(bytes)),
                _ => {}
            }
        }
        return;
    };
    if matches!(values.first(), Some(LuaScalar::Boolean(false))) {
        event["status"] = json!("error");
        let code = values
            .iter()
            .skip(1)
            .filter_map(|value| match value {
                LuaScalar::Bytes(bytes)
                    if bytes.len() <= 64
                        && !bytes.is_empty()
                        && bytes.iter().all(|v| {
                            v.is_ascii_uppercase() || v.is_ascii_digit() || *v == b'_'
                        }) =>
                {
                    String::from_utf8(bytes.clone()).ok()
                }
                _ => None,
            })
            .next();
        if let Some(code) = code {
            event["failureCode"] = json!(code);
        }
        return;
    }
    if !success {
        return;
    }
    let numeric = |index: usize| match values.get(index) {
        Some(LuaScalar::Integer(value)) => Some(*value),
        _ => None,
    };
    let found = |event: &mut Value, exists| {
        event["status"] = json!(if exists { "found" } else { "not_found" });
    };
    match opcode {
        5100 | 5101 | 5104 | 5107 | 5108 | 5109 => {
            let point = numeric(1).zip(numeric(2));
            found(event, point.is_some());
            if let Some((x, y)) = point {
                event["result"] = json!(format!("({}, {})", x, y));
            }
        }
        5105 | 5106 => {
            if let Some(count) = numeric(1) {
                found(event, count > 0);
                event["result"] = json!(format!("{} 个", count));
            }
        }
        5103 => {
            if let Some(LuaScalar::Boolean(value)) = values.get(1) {
                found(event, *value);
            }
        }
        5114 | 5123 | 6100 | 6101 | 6102 | 6104 | 6105 => {
            if let Some(LuaScalar::Bytes(bytes)) = values.get(1) {
                if bytes.len() <= 65_536 {
                    let text = String::from_utf8_lossy(bytes);
                    if let Some(value) = matches!(opcode, 5114 | 5123 | 6102)
                        .then(|| serde_json::from_slice::<Value>(bytes).ok())
                        .flatten()
                    {
                        if opcode == 6102 {
                            if let Some(text) = value.get("text").and_then(Value::as_str) {
                                found(event, !text.is_empty());
                                event["result"] = json!(preview(text));
                                return;
                            }
                        }
                        let count = value.as_array().map(Vec::len).or_else(|| {
                            value
                                .get("count")
                                .and_then(Value::as_u64)
                                .and_then(|v| usize::try_from(v).ok())
                        });
                        if let Some(count) = count {
                            found(event, count > 0);
                            event["result"] = json!(format!("{} 项", count));
                            return;
                        }
                    }
                    found(event, !text.is_empty());
                    event["result"] = json!(preview(&text));
                }
            }
        }
        5102 => {
            if let Some(rgb) = numeric(1) {
                event["result"] = json!(format!("#{:06X}", rgb & 0xffffff));
            }
        }
        _ if event["generic"].as_bool() == Some(true) => {
            if let Some(value) = values.get(1) {
                let result = match value {
                    LuaScalar::Bytes(bytes) if opcode == 18003 => {
                        serde_json::from_slice::<Value>(bytes)
                            .ok()
                            .and_then(|v| v.get("status").and_then(Value::as_i64))
                            .map(|s| format!("HTTP {}", s))
                            .unwrap_or_else(|| "响应已收到".into())
                    }
                    LuaScalar::Bytes(bytes) if sensitive_result(opcode) => {
                        format!("收到 {} 字节", bytes.len())
                    }
                    _ if matches!(opcode, 5001..=5003 | 5200..=5202 | 6000..=6001 | 3001 | 3003) => {
                        String::new()
                    }
                    _ => scalar_preview(value),
                };
                event["result"] = json!(result);
            }
        }
        _ => {}
    }
}

pub(super) fn observed_inline(opcode: u32) -> bool {
    matches!(opcode, 5001..=5003 | 5005 | 5010..=5013 | 5100..=5107 | 5110..=5115 |
        5120..=5123 | 5200..=5202 | 6000..=6001 | 6100 | 6102..=6103)
}

fn generic_fields(opcode: u32, args: &[LuaScalar]) -> Option<Value> {
    // Explicit Log.write already has its own text record; never duplicate its message here.
    if opcode == 1100 {
        return None;
    }
    let (title, labels) = super::run_action_catalog::entry(opcode)?;
    let contract = crate::contract(opcode)?;
    let details = args
        .iter()
        .take(4)
        .enumerate()
        .map(|(index, arg)| {
            let name = contract.params.get(index).map_or("", |p| p.name);
            let value = if sensitive_argument(opcode, name) {
                match arg {
                    LuaScalar::Bytes(v) => format!("{} 字节", v.len()),
                    _ => "已提供".into(),
                }
            } else {
                scalar_preview(arg).chars().take(40).collect()
            };
            format!("{}={}", labels.get(index).copied().unwrap_or("参数"), value)
        })
        .collect::<Vec<_>>()
        .join(" · ");
    Some(
        json!({"version":1,"kind":"action","opcode":opcode,"operation":title,"details":details.chars().take(240).collect::<String>(),"generic":true}),
    )
}

fn sensitive_argument(opcode: u32, name: &str) -> bool {
    matches!(opcode, 18002 | 2202 | 16001 | 18005)
        || matches!(opcode, 5003 | 6000)
        || (opcode == 18003 && matches!(name, "url" | "headersJson" | "body"))
        || matches!(
            name,
            "callback" | "headersJson" | "body" | "password" | "token"
        )
        || ((14100..=14147).contains(&opcode)
            && matches!(name, "content" | "text" | "value" | "base64" | "data"))
}

fn sensitive_result(opcode: u64) -> bool {
    matches!(opcode, 16000 | 18000 | 18004 | 14100 | 14117 | 14123 | 14143..=14144 | 1201 | 1203..=1205)
}

fn scalar_preview(value: &LuaScalar) -> String {
    match value {
        LuaScalar::Nil => "空值".into(),
        LuaScalar::Boolean(v) => if *v { "是" } else { "否" }.into(),
        LuaScalar::Integer(v) => v.to_string(),
        LuaScalar::Number(v) => v.to_string(),
        LuaScalar::Bytes(v) => data_preview(v),
    }
}

fn data_preview(bytes: &[u8]) -> String {
    if bytes.len() <= 65_536 {
        if let Ok(value) = serde_json::from_slice::<Value>(bytes) {
            match value {
                Value::Array(v) => return format!("数组 {} 项", v.len()),
                Value::Object(v) => return format!("对象 {} 个字段", v.len()),
                _ => {}
            }
        }
    }
    match std::str::from_utf8(bytes) {
        Ok(text) => preview(text),
        Err(_) => format!("二进制 {} 字节", bytes.len()),
    }
}

fn preview(text: &str) -> String {
    let mut chars = text.chars();
    let mut value: String = chars
        .by_ref()
        .take(160)
        .map(|c| {
            if c.is_control() || matches!(c, '\u{2028}' | '\u{2029}') {
                ' '
            } else {
                c
            }
        })
        .collect();
    if chars.next().is_some() {
        value.push('…');
    }
    value
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ExecutorConfig, RuntimeExecutor, VirtualHost, VirtualHostMode};
    use lua_runtime::LuaRuntimeConfig;
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::time::Duration;

    fn runtime(
        clock: &ManualClock,
        mode: VirtualHostMode,
    ) -> RuntimeExecutor<ManualClock, VirtualHost> {
        let mut host = VirtualHost::new(720, 1280);
        host.mode = mode;
        RuntimeExecutor::new(
            clock.clone(),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            host,
        )
        .unwrap()
    }
    fn actions(runtime: &mut RuntimeExecutor<ManualClock, VirtualHost>) -> Vec<Value> {
        runtime
            .drain_run_events()
            .iter()
            .map(|v| serde_json::from_str::<Value>(v).unwrap())
            .filter(|v| v["kind"] == "action")
            .collect()
    }

    #[test]
    fn real_lua_taps_log_actual_coordinates_and_frozen_visual_location_with_trace_off() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::Immediate);
        let source = "return function() coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','flow-2','node-8',false,false,12,'放技能','点击'); Input.tap(123,456); Input.tap(123,456) end";
        let task = runtime
            .start_entry_chunk(source.as_bytes(), "action-tap")
            .unwrap();
        for _ in 0..4 {
            runtime.drive().unwrap();
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 2);
        for record in records {
            assert_eq!(record["details"], "(123, 456)");
            assert_eq!(record["status"], "completed");
            assert_eq!(record["coordinateSpace"], "设计坐标");
            assert_eq!(record["visualLocation"]["flowName"], "放技能");
            assert_eq!(record["visualLocation"]["line"], 12);
        }
    }

    #[test]
    fn pending_host_does_not_claim_a_completed_click_and_timeout_remains_failure() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::NeverComplete);
        runtime
            .start_entry_chunk(b"return function() Input.tap(12,34) end", "action-timeout")
            .unwrap();
        runtime.drive().unwrap();
        assert!(actions(&mut runtime).is_empty());
        clock.advance(Duration::from_secs(60));
        for _ in 0..4 {
            runtime.drive().unwrap();
        }
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 1);
        assert_eq!(records[0]["status"], "timeout");
    }

    #[test]
    fn rejected_dispatch_never_produces_a_successful_click() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::FailDispatch);
        runtime
            .start_entry_chunk(b"return function() Input.tap(12,34) end", "action-failure")
            .unwrap();
        for _ in 0..4 {
            runtime.drive().unwrap();
        }
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 1);
        assert_eq!(records[0]["status"], "error");
        assert_eq!(records[0]["failureCode"], "HOST_DISPATCH_FAILED");
    }

    #[test]
    fn image_and_color_misses_and_zero_count_use_their_typed_replies() {
        let args = vec![LuaScalar::Integer(1); 8];
        for opcode in [5100, 5101, 5108, 5109] {
            let mut record = fields(opcode, &args, None).unwrap();
            complete(
                &mut record,
                "not_found",
                Some(&LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Nil,
                ])),
            );
            assert_eq!(record["status"], "not_found");
            complete(
                &mut record,
                "found",
                Some(&LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Integer(9),
                    LuaScalar::Integer(10),
                ])),
            );
            assert_eq!(record["status"], "found");
            assert_eq!(record["result"], "(9, 10)");
        }
        let mut count = fields(5105, &args, None).unwrap();
        complete(
            &mut count,
            "found",
            Some(&LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(0),
            ])),
        );
        assert_eq!(count["status"], "not_found");
        assert_eq!(count["result"], "0 个");
    }

    #[test]
    fn multi_image_json_and_ocr_literal_brackets_are_not_confused() {
        let args = vec![LuaScalar::Integer(1); 10];
        let mut record = fields(5114, &args, None).unwrap();
        complete(
            &mut record,
            "found",
            Some(&LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(br#"{"count":2,"matches":[]}"#.to_vec()),
            ])),
        );
        assert_eq!(record["result"], "2 项");
        let mut ocr = fields(6101, &args, None).unwrap();
        complete(
            &mut ocr,
            "found",
            Some(&LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(b"[]".to_vec()),
            ])),
        );
        assert_eq!(ocr["status"], "found");
        assert_eq!(ocr["result"], "[]");
    }

    #[test]
    fn detailed_glyph_ocr_logs_text_without_dumping_its_json() {
        let args = vec![LuaScalar::Integer(1); 10];
        for text in ["100(+100) 180", ""] {
            let mut record = fields(6102, &args, None).unwrap();
            let bytes = json!({"text":text,"matches":[]}).to_string().into_bytes();
            complete(
                &mut record,
                "completed",
                Some(&LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Bytes(bytes),
                ])),
            );
            assert_eq!(record["result"], text);
            assert_eq!(
                record["status"],
                if text.is_empty() {
                    "not_found"
                } else {
                    "found"
                }
            );
        }
        assert_eq!(preview("a\nb\u{2028}c"), "a b c");
        assert!(preview(&"a".repeat(161)).ends_with('…'));
    }

    #[test]
    fn polygon_action_uses_the_actual_runtime_marker() {
        let mut args = vec![LuaScalar::Integer(1); 8];
        args.push(LuaScalar::Bytes(b"@polygon:[[0,0],[10,0],[0,10]]".to_vec()));
        assert_eq!(fields(5100, &args, None).unwrap()["polygon"], true);
    }

    #[test]
    fn every_registered_api_has_a_readable_action_or_explicit_output_route() {
        for api in script_api::API_CONTRACTS {
            let (label, _) = crate::run_action_catalog::entry(api.opcode).expect(api.name);
            assert!(
                !label.is_empty() && label.chars().count() <= 32,
                "{}",
                api.name
            );
            if api.opcode == 1100 {
                assert!(fields(api.opcode, &[], None).is_none());
            } else {
                assert!(fields(api.opcode, &[], None).is_some(), "{}", api.name);
            }
        }
    }

    #[test]
    fn utility_data_and_handwritten_api_calls_log_actual_returns_without_duplicate_output() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::Immediate);
        let task = runtime
            .start_entry_chunk(
                br#"return function()
            assert(Math.distance(0,0,3,4)==5)
            local parts=Text.split('100,200',',')
            assert(Data.length(parts)==2)
            Log.info('explicit-output')
        end"#,
                "all-actions",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(
            runtime.task_state(task),
            Some(TaskState::Completed),
            "{:?}",
            runtime.task_failure(task)
        );
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 3);
        assert_eq!(records[0]["result"], "5");
        assert_eq!(records[1]["result"], "数组 2 项");
        assert_eq!(records[2]["result"], "2");
        assert_eq!(runtime.drain_script_logs().len(), 1);
    }

    #[test]
    fn sleep_logs_waiting_then_actual_wakeup_or_cancellation() {
        for cancel in [false, true] {
            let clock = ManualClock::new(MonoTime::ZERO);
            let mut runtime = runtime(&clock, VirtualHostMode::Immediate);
            let task = runtime
                .start_entry_chunk(b"return function() Task.sleep(1000) end", "wait-log")
                .unwrap();
            runtime.drive().unwrap();
            let records = actions(&mut runtime);
            assert_eq!(records.len(), 1);
            assert_eq!(records[0]["status"], "waiting");
            if cancel {
                runtime.handle().request_cancel(task).unwrap();
            } else {
                clock.advance(Duration::from_millis(1000));
            }
            runtime.drive().unwrap();
            let records = actions(&mut runtime);
            assert_eq!(records.len(), 1);
            assert_eq!(
                records[0]["status"],
                if cancel { "cancelled" } else { "completed" }
            );
            assert!(runtime.pending_log_sleeps.is_empty());
        }
    }

    #[test]
    fn http_and_input_parameters_and_sensitive_returns_are_not_dumped() {
        let args = vec![
            LuaScalar::Bytes(b"POST".to_vec()),
            LuaScalar::Bytes(b"http://private/token".to_vec()),
            LuaScalar::Bytes(b"{Authorization:secret}".to_vec()),
            LuaScalar::Bytes(b"password=secret".to_vec()),
        ];
        let mut event = fields(18003, &args, None).unwrap();
        assert!(event.to_string().contains("POST"));
        assert!(!event.to_string().contains("private") && !event.to_string().contains("secret"));
        complete(
            &mut event,
            "completed",
            Some(&LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(br#"{"status":404,"bodyBase64":"secret"}"#.to_vec()),
            ])),
        );
        assert_eq!(event["result"], "HTTP 404");
        assert_eq!(event["status"], "completed");
        for opcode in [16000, 14100, 18004] {
            let mut event = fields(opcode, &[], None).unwrap();
            complete(
                &mut event,
                "completed",
                Some(&LuaInput::Values(vec![
                    LuaScalar::Boolean(true),
                    LuaScalar::Bytes(b"secret".to_vec()),
                ])),
            );
            assert!(!event.to_string().contains("secret"));
        }
    }

    #[test]
    fn handled_request_failure_and_disabled_prompt_are_not_recorded_as_completion() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::Immediate);
        let task = runtime
            .start_entry_chunk(
                br#"return function()
            local ok=pcall(Text.split,'one',1)
            assert(not ok)
            Prompt.setEnabled(false)
            Prompt.show('hidden')
        end"#,
                "handled-failure",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(
            runtime.task_state(task),
            Some(TaskState::Completed),
            "{:?}",
            runtime.task_failure(task)
        );
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 3);
        assert_eq!(records[0]["status"], "error");
        assert_eq!(records[2]["status"], "disabled");
    }

    #[test]
    fn asynchronous_file_read_records_only_real_host_outcome_and_frozen_row() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::NeverComplete);
        let task = runtime.start_entry_chunk(
            "return function() coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','flow-1','node-9',false,false,9,'测试','读取文件'); Files.read('sample.txt') end".as_bytes(), "file-log").unwrap();
        runtime.drive().unwrap();
        assert!(actions(&mut runtime).is_empty());
        let request = runtime.backend().requests()[0].clone();
        runtime
            .handle()
            .submit_host_completion(runtime_scheduler::HostCompletion {
                request_id: request.request_id,
                task,
                result: runtime_scheduler::HostResult::Failure {
                    code: "FILE_NOT_FOUND".into(),
                    message: "fixture-private-message".into(),
                },
            })
            .unwrap();
        runtime.drive().unwrap();
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 1);
        assert_eq!(records[0]["status"], "error");
        assert_eq!(records[0]["failureCode"], "FILE_NOT_FOUND");
        assert_eq!(records[0]["visualLocation"]["line"], 9);
        assert!(records[0]["details"]
            .as_str()
            .unwrap()
            .contains("sample.txt"));
        assert!(!records[0].to_string().contains("fixture-private-message"));
    }

    #[test]
    fn denied_sleep_and_frame_operations_log_failure_without_waiting_or_leaking_resources() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHostMode::Immediate);
        runtime.set_allowed_capabilities(&[]).unwrap();
        let task = runtime
            .start_entry_chunk(
                br"return function()
            assert(not pcall(Task.sleep,1000))
            assert(not pcall(Screen.cache,1))
        end",
                "denied-actions",
            )
            .unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        let records = actions(&mut runtime);
        assert_eq!(records.len(), 2);
        assert!(records
            .iter()
            .all(|v| v["status"] == "error" && v["failureCode"] == "CAPABILITY_DENIED"));
        assert!(runtime.pending_log_sleeps.is_empty());
        assert!(runtime.backend().requests().is_empty());
    }
}
