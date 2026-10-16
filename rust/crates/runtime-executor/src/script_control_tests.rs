use crate::{ExecutorConfig, RuntimeExecutor, VirtualHost};
use lua_runtime::{LuaRuntimeConfig, LuaScalar};
use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, SchedulerPoll, TaskState};
fn runtime() -> RuntimeExecutor<ManualClock, VirtualHost> {
    RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(1, 1),
    )
    .unwrap()
}
#[test]
fn restart_stops_children_and_emits_one_priority_marker_without_continuing() {
    let mut runtime = runtime();
    let root = runtime.start_entry_chunk(
        b"return function() child=Task.spawn(function() Task.restart(); after_restart=true end); Task.sleep(1000); parent_after=true end",
        "restart-cleanup",
    ).unwrap();
    assert_eq!(runtime.drive().unwrap().next, SchedulerPoll::Stopped);
    assert_eq!(runtime.task_state(root), Some(TaskState::Cancelled));
    assert_eq!(runtime.resources().resource_count(), 0);
    assert_eq!(
        runtime.drain_script_prompts(),
        vec![serde_json::json!({"kind":"scriptRestart"}).to_string()]
    );
    assert_eq!(
        runtime.lua().global_scalar("after_restart").unwrap(),
        LuaScalar::Nil
    );
    assert_eq!(
        runtime.lua().global_scalar("parent_after").unwrap(),
        LuaScalar::Nil
    );
}

#[test]
fn child_can_stop_the_whole_run_and_owned_frames_are_released() {
    use automation_core::{FrameFormat, FrameMetadata, Rotation};
    let mut runtime = runtime();
    let capture = runtime
        .frame_pool()
        .lock()
        .unwrap()
        .publish_capture(
            FrameMetadata {
                width: 1,
                height: 1,
                row_stride: 4,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 1,
                snapshot_id: 1,
            },
            &[1, 2, 3, 255],
        )
        .unwrap();
    let code=format!("return function() held=Screen.cache({}); child=Task.spawn(function() Task.stop(); child_after=true end); Task.sleep(1000); parent_after=true end",capture.0);
    let root = runtime
        .start_entry_chunk(code.as_bytes(), "script-self-stop")
        .unwrap();
    let report = runtime.drive().unwrap();
    assert_eq!(report.next, SchedulerPoll::Stopped);
    assert_eq!(runtime.task_state(root), Some(TaskState::Cancelled));
    assert_eq!(runtime.resources().resource_count(), 0);
    assert_eq!(
        runtime.drain_script_prompts(),
        vec![serde_json::json!({"kind":"scriptStop"}).to_string()]
    );
    assert_eq!(
        runtime.lua().global_scalar("child_after").unwrap(),
        LuaScalar::Nil
    );
    assert_eq!(
        runtime.lua().global_scalar("parent_after").unwrap(),
        LuaScalar::Nil
    );
}
#[test]
fn disabled_logs_do_not_erase_prior_output_and_switches_do_not_replay_prompts() {
    let mut runtime = runtime();
    let root=runtime.start_entry_chunk(b"return function() Log.info('before'); Log.setEnabled(false); Log.info('hidden'); Log.setEnabled(true); Log.info('after'); Prompt.show('old'); Prompt.setEnabled(false); Prompt.show('hidden'); Prompt.toast('hidden'); Prompt.setEnabled(true); Prompt.show('new') end","script-switches").unwrap();
    runtime.drive().unwrap();
    assert_eq!(runtime.task_state(root), Some(TaskState::Completed));
    assert_eq!(
        runtime.drain_script_logs(),
        vec!["脚本/INFO: before", "脚本/INFO: after"]
    );
    let prompts = runtime
        .drain_script_prompts()
        .iter()
        .map(|p| serde_json::from_str::<serde_json::Value>(p).unwrap())
        .collect::<Vec<_>>();
    assert_eq!(prompts.len(), 2);
    assert_eq!(prompts[0]["kind"], "promptEnabled");
    assert_eq!(prompts[0]["enabled"], true);
    assert_eq!(prompts[1]["message"], "new");
}
#[test]
fn prompt_overflow_never_evicts_the_enable_marker() {
    let mut runtime = runtime();
    let root = runtime
        .start_entry_chunk(
            b"return function() Prompt.setEnabled(true); for i=1,300 do Prompt.show(i) end end",
            "prompt-control-preservation",
        )
        .unwrap();
    for _ in 0..20 {
        runtime.drive().unwrap();
        if runtime.task_state(root) == Some(TaskState::Completed) {
            break;
        }
    }
    assert_eq!(runtime.task_state(root), Some(TaskState::Completed));
    let events = runtime.drain_script_prompts();
    assert!(events.len() <= crate::MAX_SCRIPT_PROMPT_ENTRIES);
    assert_eq!(
        serde_json::from_str::<serde_json::Value>(&events[0]).unwrap()["kind"],
        "promptEnabled"
    );
}
#[test]
fn stop_requires_the_project_task_capability() {
    let mut runtime = runtime();
    runtime
        .set_allowed_capabilities(&["screen.capture".into()])
        .unwrap();
    let root = runtime
        .start_entry_chunk(
            b"return function() allowed=pcall(Task.stop); still_running=true end",
            "stop-denied",
        )
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(runtime.task_state(root), Some(TaskState::Completed));
    assert_eq!(
        runtime.lua().global_scalar("allowed").unwrap(),
        LuaScalar::Boolean(false)
    );
    assert_eq!(
        runtime.lua().global_scalar("still_running").unwrap(),
        LuaScalar::Boolean(true)
    );
}
