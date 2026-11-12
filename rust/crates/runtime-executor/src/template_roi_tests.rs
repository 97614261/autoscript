use super::*;
use automation_core::{FrameFormat, FrameMetadata, Rotation};
use runtime_scheduler::{ManualClock, MonoTime};

fn runtime(
    width: u32,
    height: u32,
    template_size: u32,
) -> (RuntimeExecutor<ManualClock, VirtualHost>, CapturedFrameId) {
    let runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(width, height),
    )
    .unwrap();
    let frames = runtime.frame_pool();
    let mut pool = frames.lock().unwrap();
    let metadata = FrameMetadata {
        width,
        height,
        row_stride: width * 4,
        pixel_stride: 4,
        format: FrameFormat::Rgba8888,
        rotation: Rotation::Degrees0,
        timestamp_nanos: 1,
        snapshot_id: 1,
    };
    let capture = pool
        .publish_capture(metadata, &[255; 4].repeat((width * height) as usize))
        .unwrap();
    pool.register_template(
        "assets/images/public.png",
        FrameMetadata {
            width: template_size,
            height: template_size,
            row_stride: template_size * 4,
            ..metadata
        },
        Arc::from([255; 4].repeat((template_size * template_size) as usize)),
    )
    .unwrap();
    drop(pool);
    (runtime, capture)
}

fn run(runtime: &mut RuntimeExecutor<ManualClock, VirtualHost>, source: &str) -> TaskToken {
    let task = runtime
        .start_entry_chunk(source.as_bytes(), "template-roi")
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
fn template_roi_19px_outside_portrait_returns_misses_and_continues_with_location() {
    let (mut runtime, capture) = runtime(720, 1280, 19);
    run(
        &mut runtime,
        &format!(
            r#"return function()
            coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','flow-6','node-315',false,false,2,'搬砖入口','区域找图')
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/public.png')
            for i=1,3 do
                assert(Screen.findImages(f,'["assets/images/public.png"]',0,1000,977,452,996,471,0)==nil)
                assert(Screen.findImage(f,t,0,1000,977,452,996,471)==nil)
                local all=Screen.findAllImages(f,'["assets/images/public.png"]',0,1000,977,452,996,471,0,64)
                assert(Screen.matchValue(all,0,'count')=='0' and Screen.matchValue(all,0,'truncated')=='0')
            end
            Log.info('continued-after-template-miss')
            Screen.release(f); Screen.release(t)
        end"#,
            capture.0
        ),
    );
    let logs = runtime.drain_script_logs();
    assert_eq!(logs.len(), 2, "{logs:?}");
    assert!(logs[0].contains("TEMPLATE_ROI_OUT_OF_BOUNDS"));
    assert!(logs[0].contains("(977,452)-(996,471)超出截图720x1280"));
    assert!(logs[0].contains("插件 搬砖入口 (flow-6) · 第2行 · 区域找图 · 积木 node-315"));
    assert!(logs[1].contains("continued-after-template-miss"));
    assert_eq!(runtime.resources().resource_count(), 0);
    assert!(runtime.vision_roi_warnings.is_empty());
}

#[test]
fn template_roi_partial_overflow_does_not_clamp_and_valid_boundary_keeps_matches() {
    let (mut runtime, capture) = runtime(3, 2, 1);
    run(
        &mut runtime,
        &format!(
            r#"return function()
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/public.png')
            for _,roi in ipairs({{{{2,0,4,2}},{{0,1,3,3}}}}) do
                assert(Screen.findImage(f,t,0,1000,table.unpack(roi))==nil)
                assert(Screen.findImages(f,'["assets/images/public.png"]',0,1000,roi[1],roi[2],roi[3],roi[4],0)==nil)
                local all=Screen.findAllImages(f,'["assets/images/public.png"]',0,1000,roi[1],roi[2],roi[3],roi[4],0,64)
                assert(Screen.matchValue(all,0,'count')=='0')
            end
            local p=Screen.findImage(f,t,0,1000,2,1,3,2)
            assert(p.x==2 and p.y==1)
            p=Screen.findImages(f,'["assets/images/public.png"]',0,1000,2,1,3,2,0)
            assert(p.x==2 and p.y==1 and p.width==1 and p.height==1)
            local all=Screen.findAllImages(f,'["assets/images/public.png"]',0,1000,2,1,3,2,0,64)
            assert(Screen.matchValue(all,0,'count')=='1' and Screen.matchValue(all,0,'truncated')=='0')
            Screen.release(f); Screen.release(t)
        end"#,
            capture.0
        ),
    );
    assert_eq!(runtime.drain_script_logs().len(), 2);
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn template_roi_too_small_is_a_miss_and_exact_size_still_matches() {
    let (mut runtime, capture) = runtime(20, 20, 19);
    run(
        &mut runtime,
        &format!(
            r#"return function()
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/public.png')
            assert(Screen.findImage(f,t,0,1000,0,0,18,19)==nil)
            assert(Screen.findImages(f,'["assets/images/public.png"]',0,1000,0,0,18,19,0)==nil)
            local all=Screen.findAllImages(f,'["assets/images/public.png"]',0,1000,0,0,18,19,0,64)
            assert(Screen.matchValue(all,0,'count')=='0')
            local p=Screen.findImages(f,'["assets/images/public.png"]',0,1000,1,1,20,20,0)
            assert(p.x==1 and p.y==1 and p.width==19 and p.height==19)
            assert(Screen.findImage(f,t,0,1000,1,1,20,20).x==1)
            Screen.release(f); Screen.release(t)
        end"#,
            capture.0
        ),
    );
    let logs = runtime.drain_script_logs();
    assert_eq!(logs.len(), 1, "{logs:?}");
    assert!(logs[0].contains("TEMPLATE_OUTSIDE_ROI"));
}

#[test]
fn template_roi_does_not_hide_invalid_parameters_templates_or_released_leases() {
    let (mut runtime, capture) = runtime(3, 2, 1);
    run(
        &mut runtime,
        &format!(
            r#"return function()
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/public.png')
            local paths='["assets/images/public.png"]'
            local function fails(fn) local ok,e=pcall(fn); assert(not ok and type(e)=='string',e) end
            fails(function() Screen.findImages(f,paths,0,1000,-1,0,4,2,0) end)
            fails(function() Screen.findImages(f,paths,0,1000,4,0,3,2,0) end)
            fails(function() Screen.findImages(f,paths,0,1000,0,0,0,2,0) end)
            fails(function() Screen.findImages(f,paths,256,1000,0,0,4,2,0) end)
            fails(function() Screen.findImages(f,paths,0,1001,0,0,4,2,0) end)
            fails(function() Screen.findImage(f,t,0,1001,0,0,4,2) end)
            fails(function() Screen.findAllImages(f,paths,0,1001,0,0,4,2,0,1) end)
            fails(function() Screen.findImages(f,paths,0,1000,0,0,4,2,9) end)
            fails(function() Screen.findImages(f,'[]',0,1000,0,0,4,2,0) end)
            fails(function() Screen.findImages(f,'["assets/images/missing.png"]',0,1000,0,0,4,2,0) end)
            fails(function() Screen.findImages(f,'["assets/images/public.png","assets/images/public.png"]',0,1000,0,0,4,2,0) end)
            fails(function() Screen.findAllImages(f,paths,0,1000,0,0,4,2,0,0) end)
            fails(function() Screen.findAllImages(f,paths,0,1000,0,0,4,2,0,65) end)
            Screen.release(t)
            fails(function() Screen.findImage(f,t,0,1000,0,0,4,2) end)
            Screen.release(f)
            fails(function() Screen.findImages(f,paths,0,1000,0,0,4,2,0) end)
        end"#,
            capture.0
        ),
    );
    assert!(runtime.drain_script_logs().is_empty());
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn template_roi_valid_outside_polygon_misses_but_conflicting_or_malformed_polygon_fails() {
    let (mut runtime, capture) = runtime(3, 2, 1);
    run(
        &mut runtime,
        &format!(
            r#"return function()
            local f=Screen.cache({}); local t=Screen.loadImage('assets/images/public.png')
            local paths='["assets/images/public.png"]'
            local polygon='[[0,0],[4,0],[4,2],[0,2]]'
            assert(Screen.findImages(f,paths,0,1000,0,0,4,2,0,polygon)==nil)
            assert(Screen.findImage(f,t,0,1000,0,0,4,2,0,polygon)==nil)
            local all=Screen.findAllImages(f,paths,0,1000,0,0,4,2,0,64,polygon)
            assert(Screen.matchValue(all,0,'count')=='0')
            local ok,e=pcall(Screen.findImages,f,paths,0,1000,0,0,3,2,0,polygon)
            assert(not ok and e:find('多边形选区超出找图范围',1,true),e)
            ok=pcall(Screen.findImages,f,paths,0,1000,0,0,4,2,0,'[[0,0],[4,4],[0,4],[4,0]]')
            assert(not ok)
            Screen.release(f); Screen.release(t)
        end"#,
            capture.0
        ),
    );
    assert_eq!(runtime.drain_script_logs().len(), 1);
}

#[test]
fn template_roi_log_switch_and_foreign_frame_ownership_are_preserved() {
    let (mut runtime, capture) = runtime(3, 2, 1);
    runtime.output_switches.logs = false;
    let owner = runtime
        .start_entry_chunk(
            format!(
                "return function() foreign_frame=Screen.cache({}); Task.sleep(60000) end",
                capture.0
            )
            .as_bytes(),
            "template-frame-owner",
        )
        .unwrap();
    runtime.drive().unwrap();
    run(
        &mut runtime,
        r#"return function()
            local ok,e=pcall(Screen.findImages,foreign_frame,'["assets/images/public.png"]',0,1000,0,0,4,2,0)
            assert(not ok and e:find('FRAME_OPERATION_FAILED',1,true),e)
            local own=Screen.loadImage('assets/images/public.png')
            assert(Screen.findImages(own,'["assets/images/public.png"]',0,1000,0,0,4,2,0)==nil)
            Screen.release(own)
        end"#,
    );
    assert!(runtime.drain_script_logs().is_empty());
    assert!(runtime.vision_roi_warnings.is_empty());
    runtime.handle().request_cancel(owner).unwrap();
    runtime.drive().unwrap();
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn template_roi_warning_budget_is_shared_and_cleared_on_stop() {
    let (mut runtime, capture) = runtime(3, 2, 1);
    let source = format!(
        r#"return function()
            local f=Screen.cache({})
            for i=1,140 do
                assert(Screen.findImages(f,'["assets/images/public.png"]',0,1000,0,0,3+i,2,0)==nil)
            end
            Task.sleep(60000)
        end"#,
        capture.0
    );
    let task = runtime
        .start_entry_chunk(source.as_bytes(), "template-roi-budget")
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
