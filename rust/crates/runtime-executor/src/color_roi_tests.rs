use super::*;
use automation_core::{FrameFormat, FrameMetadata, Rotation};
use runtime_scheduler::{ManualClock, MonoTime};

fn runtime(
    width: u32,
    height: u32,
) -> (RuntimeExecutor<ManualClock, VirtualHost>, CapturedFrameId) {
    let runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(width, height),
    )
    .unwrap();
    let capture = runtime
        .frames
        .lock()
        .unwrap()
        .publish_capture(
            FrameMetadata {
                width,
                height,
                row_stride: width * 4,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 1,
                snapshot_id: 1,
            },
            &[255; 4].repeat((width * height) as usize),
        )
        .unwrap();
    (runtime, capture)
}

fn run(runtime: &mut RuntimeExecutor<ManualClock, VirtualHost>, source: &str) -> TaskToken {
    let task = runtime
        .start_entry_chunk(source.as_bytes(), "color-roi")
        .unwrap();
    for _ in 0..64 {
        runtime.drive().unwrap();
        if matches!(
            runtime.task_state(task),
            Some(TaskState::Completed | TaskState::Failed)
        ) {
            break;
        }
    }
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(task)
    );
    task
}

#[test]
fn polygon_native_gray_and_feature_requests_keep_owned_leases_and_vertices() {
    let (mut runtime, capture) = runtime(4, 4);
    let task = runtime
        .start_entry_chunk(
            format!(
                "return function() polygonFrame=Screen.cache({}); Task.sleep(1000) end",
                capture.0
            )
            .as_bytes(),
            "polygon-native",
        )
        .unwrap();
    runtime.drive().unwrap();
    let frame = runtime.lua().global_scalar("polygonFrame").unwrap();
    let mut args = vec![
        frame.clone(),
        frame,
        LuaScalar::Integer(900),
        LuaScalar::Integer(0),
        LuaScalar::Integer(0),
        LuaScalar::Integer(4),
        LuaScalar::Integer(4),
        LuaScalar::Bytes(b"@polygon:[[0,0],[4,0],[0,4]]".to_vec()),
    ];
    for opcode in [5108, 5109] {
        let prepared = runtime.prepare_native_vision(task, opcode, &args).unwrap();
        assert_eq!(prepared.polygon, Some(vec![[0, 0], [4, 0], [0, 4]]));
        assert_eq!(prepared.frame.metadata.width, 4);
    }
    args[7] = LuaScalar::Bytes(b"@polygon:[[0,0],[5,0],[0,4]]".to_vec());
    assert!(runtime.prepare_native_vision(task, 5108, &args).is_err());
    args.pop();
    assert!(runtime
        .prepare_native_vision(task, 5108, &args)
        .unwrap()
        .polygon
        .is_none());
}

#[test]
fn outside_color_roi_returns_empty_results_and_continues_portrait() {
    let (mut runtime, capture) = runtime(720, 1280);
    let source = format!(
        r"return function()
        coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','flow-1','node-383',false,false,7,'技能','颜色计数')
        local frame = Screen.cache({})
        for i=1,3 do
            assert(Screen.countColor(frame,0xffffff,0,977,452,1003,468,0)==0)
            assert(Screen.countColor(frame,0xffffff,0,977,452,1003,468,0,'000000:0')==0)
            assert(Screen.findColor(frame,0xffffff,0,977,452,1003,468)==nil)
            assert(Screen.findColor(frame,0xffffff,0,977,452,1003,468,'000000:0')==nil)
            assert(Screen.findMultiColor(frame,0xffffff,0,{{}},977,452,1003,468)==nil)
            assert(#Screen.findAllColor(frame,0xffffff,0,977,452,1003,468,2048)==0)
            assert(#Screen.findAllColor(frame,0xffffff,0,977,452,1003,468,2048,'000000:0')==0)
        end
        Log.info('continued-after-empty-result')
        Screen.release(frame)
    end",
        capture.0
    );
    run(&mut runtime, &source);
    assert_eq!(runtime.resources().resource_count(), 0);
    assert!(runtime.vision_roi_warnings.is_empty());
    let logs = runtime.drain_script_logs();
    assert_eq!(logs.len(), 5, "{logs:?}");
    assert!(logs[..4]
        .iter()
        .all(|message| message.contains("COLOR_ROI_OUT_OF_BOUNDS")
            && message.contains("(977,452)-(1003,468)")
            && message.contains("720x1280")
            && message.contains("插件 技能 (flow-1) · 第7行 · 颜色计数 · 积木 node-383")));
    assert!(logs[4].contains("continued-after-empty-result"));
}

#[test]
fn partial_color_roi_is_not_clamped_and_in_bounds_search_still_matches() {
    let (mut runtime, capture) = runtime(3, 2);
    run(
        &mut runtime,
        &format!(
            r"return function()
        local frame=Screen.cache({})
        assert(Screen.countColor(frame,0xffffff,0,2,0,4,2,0)==0)
        assert(Screen.findColor(frame,0xffffff,0,2,0,4,2)==nil)
        assert(Screen.findMultiColor(frame,0xffffff,0,{{}},2,0,4,2)==nil)
        assert(#Screen.findAllColor(frame,0xffffff,0,2,0,4,2,20)==0)
        assert(Screen.countColor(frame,0xffffff,0,0,1,3,3,0)==0)
        assert(Screen.countColor(frame,0xffffff,0,0,0,3,2,0)==6)
        assert(Screen.findColor(frame,0xffffff,0,0,0,3,2).x==0)
        assert(Screen.findMultiColor(frame,0xffffff,0,{{}},0,0,3,2).y==0)
        assert(#Screen.findAllColor(frame,0xffffff,0,0,0,3,2,20)==6)
        Screen.release(frame)
    end",
            capture.0
        ),
    );
    assert_eq!(runtime.drain_script_logs().len(), 5);
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn outside_color_roi_does_not_hide_bad_arguments_or_expired_frames() {
    let (mut runtime, capture) = runtime(3, 2);
    run(
        &mut runtime,
        &format!(
            r"return function()
        local frame=Screen.cache({})
        local function fails(f) local ok,e=pcall(f); assert(not ok and type(e)=='string' and #e>0,e) end
        fails(function() Screen.countColor(frame,0xffffff,0,-1,0,4,2,0) end)
        fails(function() Screen.countColor(frame,0xffffff,0,2,0,1,2,0) end)
        fails(function() Screen.countColor(frame,0xffffff,0,1,0,1,2,0) end)
        fails(function() Screen.countColor(frame,-1,0,0,0,4,2,0) end)
        fails(function() Screen.countColor(frame,0xffffff,256,0,0,4,2,0) end)
        fails(function() Screen.countColor(frame,0xffffff,0,0,0,4,2,-1) end)
        fails(function() Screen.countColor(frame,0xffffff,0,0,0,4,2,0,'invalid') end)
        fails(function() Screen.findAllColor(frame,0xffffff,0,0,0,4,2,2049) end)
        fails(function() Screen.findMultiColor(frame,0xffffff,0,{{{{x=0,y=0,rgb=-1,tolerance=0}}}},0,0,4,2) end)
        Screen.release(frame)
        fails(function() Screen.countColor(frame,0xffffff,0,0,0,4,2,0) end)
    end",
            capture.0
        ),
    );
    assert!(runtime.drain_script_logs().is_empty());
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn outside_color_roi_warnings_are_bounded_and_cleared_on_stop() {
    let (mut runtime, capture) = runtime(3, 2);
    let source = format!(
        r"return function()
        local frame=Screen.cache({})
        for i=1,140 do assert(Screen.countColor(frame,0xffffff,0,0,0,3+i,2,0)==0) end
        Task.sleep(60000)
    end",
        capture.0
    );
    let task = runtime
        .start_entry_chunk(source.as_bytes(), "bounded-color-roi")
        .unwrap();
    for _ in 0..16 {
        runtime.drive().unwrap();
    }
    assert_eq!(runtime.task_state(task), Some(TaskState::Running));
    assert_eq!(runtime.vision_roi_warnings.len(), MAX_VISION_ROI_WARNINGS);
    assert_eq!(runtime.drain_script_logs().len(), MAX_VISION_ROI_WARNINGS);
    runtime.handle().request_cancel(task).unwrap();
    runtime.drive().unwrap();
    assert_eq!(runtime.task_state(task), Some(TaskState::Cancelled));
    assert!(runtime.vision_roi_warnings.is_empty());
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn outside_color_roi_honors_log_switch_without_changing_result() {
    let (mut runtime, capture) = runtime(3, 2);
    runtime.output_switches.logs = false;
    run(
        &mut runtime,
        &format!(
            r"return function()
        local frame=Screen.cache({})
        assert(Screen.countColor(frame,0xffffff,0,0,0,4,2,0)==0)
        Screen.release(frame)
    end",
            capture.0
        ),
    );
    assert!(runtime.drain_script_logs().is_empty());
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn outside_color_roi_cannot_borrow_another_tasks_frame() {
    let (mut runtime, capture) = runtime(3, 2);
    let owner = runtime
        .start_entry_chunk(
            format!(
                r"return function()
        foreign_frame=Screen.cache({}); Task.sleep(60000)
    end",
                capture.0
            )
            .as_bytes(),
            "frame-owner",
        )
        .unwrap();
    runtime.drive().unwrap();
    run(
        &mut runtime,
        r"return function()
        local ok,e=pcall(Screen.countColor,foreign_frame,0xffffff,0,0,0,4,2,0)
        assert(not ok and e:find('FRAME_OPERATION_FAILED',1,true),e)
        ok,e=pcall(Screen.countColor,foreign_frame,0xffffff,0,0,0,3,2,0,'','[[0,0],[3,0],[0,2]]')
        assert(not ok and e:find('FRAME_OPERATION_FAILED',1,true),e)
    end",
    );
    assert!(runtime.drain_script_logs().is_empty());
    assert_eq!(runtime.resources().resource_count(), 1);
    runtime.handle().request_cancel(owner).unwrap();
    runtime.drive().unwrap();
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn polygon_lua_color_calls_filter_before_limits_and_preserve_rectangle_calls() {
    let (mut runtime, capture) = runtime(4, 4);
    run(
        &mut runtime,
        &format!(
            r#"return function()
        local frame=Screen.cache({})
        local polygon='[[0,4],[4,0],[4,4]]'
        assert(Screen.polygonRegionVersion==1)
        assert(Screen.countColor(frame,0xffffff,0,0,0,4,4,0,'',polygon)==10)
        assert(Screen.countColor(frame,0xffffff,0,0,0,4,4,2,'000000:0',polygon)==2)
        local p=Screen.findColor(frame,0xffffff,0,0,0,4,4,nil,polygon)
        assert(p.x==3 and p.y==0)
        local all=Screen.findAllColor(frame,0xffffff,0,0,0,4,4,2,'',polygon)
        assert(#all==2 and all[1].x==3 and all[2].x==2 and all[2].y==1)
        p=Screen.findMultiColor(frame,0xffffff,0,{{{{x=-1,y=0,rgb=0xffffff,tolerance=0}}}},0,0,4,4,polygon)
        assert(p.x==3 and p.y==1)
        assert(Screen.countColor(frame,0xffffff,0,0,0,4,4,0)==16)
        assert(Screen.findColor(frame,0xffffff,0,0,0,4,4).x==0)
        Screen.release(frame)
        local ok=pcall(Screen.countColor,frame,0xffffff,0,0,0,4,4,0,'',polygon)
        assert(not ok)
    end"#,
            capture.0
        ),
    );
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn polygon_lua_template_and_multi_results_skip_outside_origins() {
    let (mut runtime, capture) = runtime(4, 4);
    runtime
        .frames
        .lock()
        .unwrap()
        .register_template(
            "assets/images/white.png",
            FrameMetadata {
                width: 2,
                height: 2,
                row_stride: 8,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 0,
                snapshot_id: 1,
            },
            Arc::from([255; 16]),
        )
        .unwrap();
    run(
        &mut runtime,
        &format!(
            r#"return function()
        local frame=Screen.cache({}); local template=Screen.loadImage('assets/images/white.png')
        local polygon='[[0,4],[4,0],[4,4]]'
        local p=Screen.findImage(frame,template,0,1000,0,0,4,4,0,polygon)
        assert(p.x==2 and p.y==1)
        p=Screen.findImages(frame,'["assets/images/white.png"]',0,1000,0,0,4,4,0,polygon)
        assert(p.x==2 and p.y==1 and p.width==2 and p.height==2)
        local all=Screen.findAllImages(frame,'["assets/images/white.png"]',0,1000,0,0,4,4,0,2,polygon)
        assert(Screen.matchValue(all,0,'count')=='2')
        assert(Screen.matchValue(all,0,'truncated')=='1')
        assert(Screen.matchValue(all,1,'x')=='2' and Screen.matchValue(all,1,'y')=='1')
        assert(Screen.findImage(frame,template,0,1000,0,0,4,4).x==0)
        Screen.release(template); Screen.release(frame)
    end"#,
            capture.0
        ),
    );
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn polygon_lua_rejects_invalid_regions_without_losing_ownership_or_orientation_warning() {
    let (mut runtime, capture) = runtime(4, 4);
    run(
        &mut runtime,
        &format!(
            r#"return function()
        local frame=Screen.cache({})
        for _,polygon in ipairs({{'[[0,0],[4,4],[0,4],[4,0]]','[[0,0],[4294967296,0],[0,4]]','[]','broken'}}) do
            assert(not pcall(Screen.countColor,frame,0xffffff,0,0,0,4,4,0,'',polygon))
        end
        assert(Screen.countColor(frame,0xffffff,0,0,0,5,4,0,'','[[0,0],[5,0],[0,4]]')==0)
        assert(Screen.countColor(frame,0xffffff,0,0,0,4,4,0)==16)
        Screen.release(frame)
    end"#,
            capture.0
        ),
    );
    assert_eq!(runtime.resources().resource_count(), 0);
    assert_eq!(runtime.drain_script_logs().len(), 1);
}
