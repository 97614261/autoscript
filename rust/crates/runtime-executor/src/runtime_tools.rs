use crate::{host_failure_input, ExecutorError, HostBackend, RuntimeExecutor};
use lua_runtime::{LuaInput, LuaScalar};
use runtime_scheduler::{Clock, TaskToken};
use serde::Deserialize;

pub(crate) fn handles(opcode: u32) -> bool {
    matches!(opcode, 2200..=2202 | 15000..=15003 | 16000..=16003 | 17000..=17003)
}

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    pub(super) fn apply_runtime_tool(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let validation = if opcode == 2201 {
            match args {
                [LuaScalar::Bytes(bytes)] => recording(bytes).map(|_| ()),
                _ => Err("recording requires JSON"),
            }
        } else {
            validate(opcode, args)
        };
        let additional = match opcode {
            2202 => Some("device.clipboard"),
            15000 if !matches!(args.first(), Some(LuaScalar::Bytes(path)) if path.starts_with(b"assets/audio/")) => {
                Some("storage.data")
            }
            2201 => Some("core.task"),
            _ => None,
        };
        let denied = additional.is_some_and(|cap| {
            self.allowed_capabilities
                .as_ref()
                .is_some_and(|caps| !caps.contains(cap))
        });
        if denied || validation.is_err() {
            self.resume_inputs.insert(
                task,
                host_failure_input(
                    "RUNTIME_TOOL_INVALID",
                    "Invalid arguments or missing capability",
                ),
            );
            self.scheduler.yield_budget(task)?;
            Ok(())
        } else {
            self.dispatch_host(task, opcode, args.to_vec())
        }
    }
}

pub(crate) fn validate(opcode: u32, args: &[LuaScalar]) -> Result<(), &'static str> {
    if (17000..=17003).contains(&opcode) {
        return crate::floating_runtime::validate(opcode, args);
    }
    match (opcode, args) {
        (2200, [LuaScalar::Integer(duration), LuaScalar::Integer(budget)])
            if (1000..=60000).contains(duration) && (128..=512).contains(budget) =>
        {
            Ok(())
        }
        (15000, [LuaScalar::Bytes(path)]) => {
            super::data_runtime::validate_file_request(14100, &[LuaScalar::Bytes(path.clone())])
        }
        (15001 | 15002 | 16000, []) => Ok(()),
        (16001 | 2202, [LuaScalar::Bytes(text)])
            if text.len() <= 4096 && std::str::from_utf8(text).is_ok() =>
        {
            Ok(())
        }
        (16002 | 16003, [LuaScalar::Bytes(name)]) => {
            let name = std::str::from_utf8(name).map_err(|_| "package invalid")?;
            if name.len() <= 255
                && name.split('.').count() >= 2
                && name.split('.').all(|p| {
                    p.as_bytes().first().is_some_and(u8::is_ascii_alphabetic)
                        && p.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
                })
            {
                Ok(())
            } else {
                Err("package invalid")
            }
        }
        (15003, [LuaScalar::Integer(volume)]) if (0..=100).contains(volume) => Ok(()),
        _ => Err("invalid runtime tool arguments"),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Recording {
    width: i32,
    height: i32,
    rotation: i32,
    #[serde(rename = "elapsedMs")]
    elapsed: i32,
    #[serde(rename = "skippedGestures")]
    skipped: usize,
    actions: Vec<[i32; 8]>,
}
pub(crate) fn recording(bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
    if bytes.len() > 32768 {
        return Err("recording exceeds budget");
    }
    let data: Recording = serde_json::from_slice(bytes).map_err(|_| "invalid recording JSON")?;
    if !(1..=8192).contains(&data.width)
        || !(1..=8192).contains(&data.height)
        || i64::from(data.width) * i64::from(data.height) > 33_554_432
        || !(0..=3).contains(&data.rotation)
        || !(0..=60000).contains(&data.elapsed)
        || data.actions.len() > 512
        || data.skipped > 512 - data.actions.len()
    {
        return Err("invalid recording bounds");
    }
    let mut previous = 0;
    let mut output = Vec::with_capacity(20 + data.actions.len() * 32);
    for value in [
        data.width,
        data.height,
        data.rotation,
        data.elapsed,
        i32::try_from(data.actions.len()).map_err(|_| "invalid action count")?,
    ] {
        output.extend(value.to_le_bytes());
    }
    for action in data.actions {
        let [kind, start, duration, x1, y1, x2, y2, key] = action;
        let end = start
            .checked_add(duration)
            .ok_or("recording duration overflow")?;
        if start < previous
            || !(0..=5000).contains(&duration)
            || end > data.elapsed
            || !(1..=4).contains(&kind)
        {
            return Err("invalid recording timing");
        }
        if kind == 4 {
            if ![3, 4, 24, 25, 66, 82].contains(&key) || duration != 0 || [x1, y1, x2, y2] != [0; 4]
            {
                return Err("invalid recording key");
            }
        } else if key != 0
            || duration < if kind == 1 { 1 } else { 50 }
            || !(0..data.width).contains(&x1)
            || !(0..data.width).contains(&x2)
            || !(0..data.height).contains(&y1)
            || !(0..data.height).contains(&y2)
            || (kind != 3 && (x1 != x2 || y1 != y2))
        {
            return Err("invalid recording coordinates");
        }
        previous = end;
        for value in action {
            output.extend(value.to_le_bytes());
        }
    }
    Ok(output)
}

pub(crate) fn response(opcode: u32, bytes: Vec<u8>) -> Result<LuaInput, &'static str> {
    let value = match opcode {
        17000..=17002 if bytes.is_empty() => return Ok(LuaInput::Boolean(true)),
        17003 if bytes.len() <= 256 => {
            let value: serde_json::Value =
                serde_json::from_slice(&bytes).map_err(|_| "floating bounds invalid")?;
            let object = value
                .as_object()
                .filter(|v| v.len() == 5)
                .ok_or("floating bounds invalid")?;
            if object
                .get("visible")
                .and_then(serde_json::Value::as_bool)
                .is_none()
                || !["x", "y", "width", "height"].iter().all(|k| {
                    object
                        .get(*k)
                        .and_then(serde_json::Value::as_u64)
                        .is_some_and(|v| v <= 8192)
                })
            {
                return Err("floating bounds invalid");
            }
            LuaScalar::Bytes(bytes)
        }
        16000 if bytes.len() <= 4096 && std::str::from_utf8(&bytes).is_ok() => {
            LuaScalar::Bytes(bytes)
        }
        16002 if bytes.len() <= 1024 => {
            let value: serde_json::Value =
                serde_json::from_slice(&bytes).map_err(|_| "app response invalid")?;
            let object = value.as_object().ok_or("app response invalid")?;
            let visible = object
                .get("visible")
                .and_then(serde_json::Value::as_bool)
                .ok_or("app response invalid")?;
            if (visible
                && (object.len() != 3
                    || object
                        .get("versionName")
                        .and_then(serde_json::Value::as_str)
                        .is_none_or(|v| v.len() > 256)
                    || object
                        .get("versionCode")
                        .and_then(serde_json::Value::as_i64)
                        .is_none()))
                || (!visible && object.len() != 1)
            {
                return Err("app response invalid");
            }
            LuaScalar::Bytes(bytes)
        }
        2201 => LuaScalar::Bytes(recording(&bytes)?),
        2200 => {
            recording(&bytes)?;
            LuaScalar::Bytes(bytes)
        }
        15002 => {
            let volume = std::str::from_utf8(&bytes)
                .map_err(|_| "invalid volume")?
                .parse::<i64>()
                .map_err(|_| "invalid volume")?;
            if !(0..=100).contains(&volume) {
                return Err("invalid volume");
            }
            LuaScalar::Integer(volume)
        }
        15000 | 15001 | 15003 | 16001 | 16003 | 2202 if bytes.is_empty() => {
            return Ok(LuaInput::Boolean(true))
        }
        _ => return Err("invalid runtime tool response"),
    };
    Ok(LuaInput::Values(vec![LuaScalar::Boolean(true), value]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ExecutorConfig, HostRequest, RuntimeExecutor, VirtualHost};
    use lua_runtime::LuaRuntimeConfig;
    use runtime_scheduler::{
        HostCompletion, HostResult, ManualClock, MonoTime, SchedulerConfig, SchedulerHandle,
        TaskState,
    };
    use std::time::Duration;
    fn sample() -> Vec<u8> {
        serde_json::json!({"width":720,"height":1280,"rotation":0,"elapsedMs":150,"skippedGestures":0,
            "actions":[[1,0,100,10,20,10,20,0],[4,150,0,0,0,0,0,4]]}).to_string().into_bytes()
    }
    #[test]
    fn whole_recording_is_validated_before_any_replay_and_bounds_are_strict() {
        assert_eq!(recording(&sample()).unwrap().len(), 84);
        for bad in [
            serde_json::json!([1, 0, 100, 720, 20, 720, 20, 0]),
            serde_json::json!([4, 0, 0, 0, 0, 0, 0, 26]),
            serde_json::json!([3, 0, 6000, 1, 1, 2, 2, 0]),
        ] {
            let mut json: serde_json::Value = serde_json::from_slice(&sample()).unwrap();
            json["actions"][1] = bad;
            assert!(recording(&serde_json::to_vec(&json).unwrap()).is_err());
        }
        assert!(validate(2200, &[LuaScalar::Integer(999), LuaScalar::Integer(128)]).is_err());
        assert!(validate(15003, &[LuaScalar::Integer(101)]).is_err());
        assert!(validate(15000, &[LuaScalar::Bytes(b"../secret.wav".to_vec())]).is_err());
        assert!(response(15002, b"101".to_vec()).is_err());
    }
    struct ToolsHost {
        inner: VirtualHost,
        json: Vec<u8>,
        requests: Vec<HostRequest>,
    }
    impl HostBackend for ToolsHost {
        fn cancel(
            &mut self,
            id: runtime_scheduler::RequestId,
            task: TaskToken,
            mode: crate::CancelMode,
        ) {
            self.inner.cancel(id, task, mode);
        }
        fn dispatch(
            &mut self,
            request: &HostRequest,
            completion: &SchedulerHandle,
        ) -> Result<(), String> {
            self.requests.push(request.clone());
            let result = match request.opcode {
                16000 => HostResult::Success("中文".as_bytes().to_vec()),
                16002 => HostResult::Success(br#"{"visible":false}"#.to_vec()),
                2200 | 2201 => HostResult::Success(self.json.clone()),
                15002 => HostResult::Success(b"37".to_vec()),
                15000 | 15001 | 15003 | 16001 | 16003 | 2202 | 4010 | 17000..=17002 => {
                    HostResult::Success(Vec::new())
                }
                17003 => HostResult::Success(
                    br#"{"x":10,"y":20,"width":320,"height":120,"visible":true}"#.to_vec(),
                ),
                _ => return self.inner.dispatch(request, completion),
            };
            completion
                .submit_host_completion(HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result,
                })
                .map_err(|e| format!("{e:?}"))
        }
    }
    #[test]
    fn real_lua_record_replay_and_audio_use_fixed_calls_with_screen_snapshot() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let host = ToolsHost {
            inner: VirtualHost::new(720, 1280),
            json: sample(),
            requests: Vec::new(),
        };
        let mut runtime = RuntimeExecutor::new(
            clock.clone(),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            host,
        )
        .unwrap();
        runtime
            .set_allowed_capabilities(
                &["input.basic", "core.task", "audio.control", "storage.data"].map(str::to_owned),
            )
            .unwrap();
        let task = runtime.start_entry_chunk(b"return function() r=Input.record(1000,128); Input.playRecording(r); volume=Audio.getVolume(); Audio.play('audio/a.wav'); Audio.setVolume(25); Audio.stop() end", "runtime-tools").unwrap();
        for _ in 0..8 {
            runtime.drive().unwrap();
            clock.advance(Duration::from_millis(100));
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("volume").unwrap(),
            LuaScalar::Integer(37)
        );
        let swipe = runtime
            .backend
            .requests
            .iter()
            .find(|r| r.opcode == 4010)
            .unwrap();
        assert_eq!(swipe.args.last(), Some(&LuaScalar::Integer(1)));
        assert_eq!(
            runtime
                .backend
                .requests
                .iter()
                .filter(|r| r.opcode == 4010)
                .count(),
            1
        );
        assert!(runtime.backend.requests.iter().any(|r| r.opcode == 4002));
    }

    #[test]
    fn audio_storage_capability_and_invalid_replay_prevent_side_effects() {
        for (script, capabilities) in [
            ("Audio.play('audio/a.wav')", vec!["audio.control"]),
            (
                "Input.playRecording('{}')",
                vec!["input.basic", "core.task"],
            ),
        ] {
            let mut runtime = RuntimeExecutor::new(
                ManualClock::new(MonoTime::ZERO),
                SchedulerConfig::default(),
                LuaRuntimeConfig::default(),
                ExecutorConfig::default(),
                ToolsHost {
                    inner: VirtualHost::new(720, 1280),
                    json: sample(),
                    requests: Vec::new(),
                },
            )
            .unwrap();
            runtime
                .set_allowed_capabilities(
                    &capabilities
                        .into_iter()
                        .map(str::to_owned)
                        .collect::<Vec<_>>(),
                )
                .unwrap();
            let task = runtime
                .start_entry_chunk(
                    format!("return function() {script} end").as_bytes(),
                    "tools-invalid",
                )
                .unwrap();
            for _ in 0..4 {
                runtime.drive().unwrap();
            }
            assert_ne!(runtime.task_state(task), Some(TaskState::Completed));
            assert!(runtime
                .backend
                .requests
                .iter()
                .all(|r| ![15000, 4010, 4002].contains(&r.opcode)));
        }
    }

    #[test]
    fn platform_calls_preserve_unicode_and_do_not_claim_missing_app_is_uninstalled() {
        let mut runtime = RuntimeExecutor::new(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            ToolsHost {
                inner: VirtualHost::new(720, 1280),
                json: sample(),
                requests: Vec::new(),
            },
        )
        .unwrap();
        runtime
            .set_allowed_capabilities(&["device.clipboard".into(), "device.apps".into()])
            .unwrap();
        let task=runtime.start_entry_chunk("return function() clip=Clipboard.getText(); Clipboard.setText(clip); info=Apps.info('com.android.settings'); Apps.open('com.android.settings') end".as_bytes(),"platform-tools").unwrap();
        for _ in 0..8 {
            runtime.drive().unwrap();
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(
            runtime.lua().global_scalar("clip").unwrap(),
            LuaScalar::Bytes("中文".as_bytes().to_vec())
        );
        assert_eq!(
            runtime.lua().global_scalar("info").unwrap(),
            LuaScalar::Bytes(br#"{"visible":false}"#.to_vec())
        );
        assert!(validate(16001, &[LuaScalar::Bytes(vec![0; 4097])]).is_err());
        assert!(validate(16003, &[LuaScalar::Bytes(b"com.foo;reboot".to_vec())]).is_err());
        assert!(response(16002, br#"{"visible":false,"installed":false}"#.to_vec()).is_err());
    }

    #[test]
    fn named_floating_calls_use_independent_capability_and_fixed_bounds_response() {
        let mut runtime = RuntimeExecutor::new(
            ManualClock::new(MonoTime::ZERO),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            ToolsHost {
                inner: VirtualHost::new(720, 1280),
                json: sample(),
                requests: Vec::new(),
            },
        )
        .unwrap();
        runtime
            .set_allowed_capabilities(&["ui.floating".into()])
            .unwrap();
        let task=runtime.start_entry_chunk(b"return function() Floating.show('panel',-1,-1,320,120,'text',20,'#FFFFFFFF','#C0202938','','[]'); bounds=Floating.getBounds('panel'); Floating.hide('panel'); Floating.close('panel') end","floating-tools").unwrap();
        for _ in 0..8 {
            runtime.drive().unwrap();
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert!(matches!(
            runtime.lua().global_scalar("bounds").unwrap(),
            LuaScalar::Bytes(_)
        ));
        assert_eq!(
            runtime
                .backend
                .requests
                .iter()
                .filter(|r| (17000..=17003).contains(&r.opcode))
                .count(),
            4
        );
    }
}
