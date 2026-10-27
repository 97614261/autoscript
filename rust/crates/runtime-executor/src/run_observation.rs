//! Structural telemetry: no script text, template paths, or pixels.
use super::{
    Clock, ExecutorError, HostBackend, Instant, LuaInput, LuaScalar, NativeVisionRequest,
    RuntimeExecutor, TaskState, TaskToken, MAX_TEMPLATE_PIXEL_COMPARISONS, OP_SCREEN_CAPTURE,
    OP_SCREEN_CAPTURE_REGION, OP_SCREEN_CAPTURE_SERIES_FRAME, OP_SCREEN_FIND_ALL_IMAGES,
    OP_SCREEN_FIND_IMAGE, OP_SCREEN_FIND_IMAGES,
};
use serde_json::{json, Value};
use std::hash::{Hash, Hasher};

fn fingerprint_text(hash: [u8; 32]) -> String {
    use std::fmt::Write;
    let mut result = String::with_capacity(64);
    for byte in hash {
        let _ = write!(result, "{byte:02x}");
    }
    result
}

#[derive(Debug, Clone)]
pub(super) struct RunContext {
    flow: String,
    node: String,
    metrics: bool,
    trace: bool,
}
#[derive(Debug, Clone)]
pub(super) struct Observation {
    started: Instant,
    pub(super) fields: Value,
}

impl Observation {
    /// Attach only a structural host code.  Host messages can contain private Lua text or paths,
    /// so the persisted event never includes the free-form diagnostic string.
    pub(super) fn record_failure_code(&mut self, code: &str) {
        let code = code.split(':').next().unwrap_or_default();
        if !code.is_empty()
            && code.len() <= 64
            && code
                .bytes()
                .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || byte == b'_')
        {
            self.fields["failureCode"] = json!(code);
        } else {
            self.fields["failureCode"] = json!("HOST_FAILURE");
        }
    }

    pub(super) fn record_search_metrics(
        &mut self,
        mut metrics: automation_core::TemplateSearchMetrics,
    ) {
        let elapsed = u64::try_from(self.started.elapsed().as_micros()).unwrap_or(u64::MAX);
        // Residual includes parsing, ownership checks, pool lock wait and result packing, not disk IO alone.
        metrics.template_setup_us =
            elapsed.saturating_sub(metrics.prefilter_build_us + metrics.search_us);
        self.fields["matchStagesUs"] = json!({"setup":metrics.template_setup_us,
            "prefilterBuild":metrics.prefilter_build_us,"searchCompare":metrics.search_us});
        self.fields["matchWork"] = json!({"templatesVisited":metrics.templates_visited,
            "preparedCacheHits":metrics.prepared_cache_hits,"templatesPrepared":metrics.templates_prepared,
            "prefiltersBuilt":metrics.prefilters_built,"candidatesVisited":metrics.candidates_visited,
            "prefilterRejected":metrics.prefilter_rejected,"comparisonPixels":metrics.comparison_pixels,
            "prefilterWork":metrics.prefilter_work});
    }
}

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    fn template_observation(
        &self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
        native: Option<&NativeVisionRequest>,
    ) -> (Vec<[u32; 2]>, Option<Vec<String>>) {
        if let Some(native) = native {
            return native
                .template
                .as_ref()
                .map_or((Vec::new(), None), |template| {
                    (
                        vec![[template.metadata.width, template.metadata.height]],
                        template
                            .template_fingerprint()
                            .map(|hash| vec![fingerprint_text(hash)]),
                    )
                });
        }
        if opcode == OP_SCREEN_FIND_IMAGE {
            let template = match args.get(1) {
                Some(LuaScalar::Integer(handle)) => self.legacy_frame_view(task, *handle).ok(),
                _ => None,
            };
            return template.map_or((Vec::new(), None), |template| {
                (
                    vec![[template.metadata.width, template.metadata.height]],
                    template
                        .template_fingerprint()
                        .map(|hash| vec![fingerprint_text(hash)]),
                )
            });
        }
        let paths = match args.get(1) {
            Some(LuaScalar::Bytes(paths)) if paths.len() <= 16_384 => {
                serde_json::from_slice::<Vec<String>>(paths).unwrap_or_default()
            }
            _ => Vec::new(),
        };
        let pool = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let sizes = paths
            .iter()
            .take(64)
            .map(|name| {
                pool.template_dimensions(name)
                    .map_or([0, 0], |(w, h)| [w, h])
            })
            .collect();
        let fingerprints = if paths.is_empty() || paths.len() > 64 {
            None
        } else {
            paths
                .iter()
                .map(|path| pool.template_fingerprint(path).map(fingerprint_text))
                .collect()
        };
        (sizes, fingerprints)
    }

    pub(super) fn apply_run_context(
        &mut self,
        task: TaskToken,
        values: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let [_, LuaScalar::Bytes(flow), LuaScalar::Bytes(node), LuaScalar::Boolean(trace), LuaScalar::Boolean(metrics)] =
            values
        else {
            return Err(ExecutorError::InvalidYield("invalid run context".into()));
        };
        let identifier = |bytes: &[u8]| -> Result<String, ExecutorError> {
            let text = std::str::from_utf8(bytes)
                .map_err(|_| ExecutorError::InvalidYield("invalid run identity".into()))?;
            if !bytes.is_empty()
                && bytes.len() <= 128
                && bytes
                    .iter()
                    .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.'))
            {
                return Ok(text.to_owned());
            }
            // Existing Flow identities permit Unicode and punctuation. Observability must not
            // reject a previously valid script or leak arbitrary text/path-like identities.
            let mut hash = std::collections::hash_map::DefaultHasher::new();
            bytes.hash(&mut hash);
            Ok(format!("anonymous:{:016x}", hash.finish()))
        };
        let context = RunContext {
            flow: identifier(flow)?,
            node: identifier(node)?,
            metrics: *metrics,
            trace: *trace,
        };
        if flow.len() <= 128 && node.len() <= 128 {
            if let (Ok(flow), Ok(node)) = (std::str::from_utf8(flow), std::str::from_utf8(node)) {
                let position = (flow.to_owned(), node.to_owned());
                if self.execution_trail.back() != Some(&position) {
                    if self.execution_trail.len() == 32 {
                        self.execution_trail.pop_front();
                    }
                    self.execution_trail.push_back(position.clone());
                }
                self.execution_position = Some(position);
            }
        }
        if *trace {
            self.record_run_event(&json!({"version":1,"kind":"trace","flowId":context.flow,"nodeId":context.node,"phase":"enter"}));
        }
        self.run_contexts.insert(task, context);
        self.scheduler.yield_budget(task)?;
        Ok(())
    }
    /// Latest-only UI position, independent of persisted trace and metrics switches.
    #[must_use]
    pub fn execution_position(&self) -> Option<(String, String)> {
        self.execution_position.clone()
    }

    /// Bounded entered rows; no source or values and no script scheduling changes.
    pub fn drain_execution_trail(&mut self) -> Vec<(String, String)> {
        self.execution_trail.drain(..).collect()
    }

    fn record_run_event(&mut self, event: &Value) {
        if self.run_events.len() >= 256 {
            self.run_events_dropped = self.run_events_dropped.saturating_add(1);
        } else {
            self.run_events.push_back(event.to_string());
        }
    }

    pub(super) fn record_task_terminal(&mut self, task: TaskToken, state: TaskState) {
        if let Some(context) = self.run_contexts.get(&task) {
            if context.trace || matches!(state, TaskState::Failed | TaskState::Cancelled) {
                let status = match state {
                    TaskState::Completed => "completed",
                    TaskState::Failed => "failed",
                    TaskState::Cancelled => "cancelled",
                    TaskState::Running => "running",
                };
                self.record_run_event(&json!({"version":1,"kind":"task","flowId":context.flow,"nodeId":context.node,"status":status}));
            }
        }
    }
    /// Drains independent telemetry, including an explicit loss counter.
    #[must_use]
    pub fn drain_run_events(&mut self) -> Vec<String> {
        let mut events: Vec<_> = self.run_events.drain(..).collect();
        if self.run_events_dropped != 0 || self.script_logs_dropped != 0 {
            events.push(
                json!({"version":1,"kind":"dropped","count":self.run_events_dropped.saturating_add(self.script_logs_dropped),"eventCount":self.run_events_dropped,"scriptLogCount":self.script_logs_dropped}).to_string(),
            );
            self.run_events_dropped = 0;
            self.script_logs_dropped = 0;
        }
        events
    }
    pub(super) fn start_observation(
        &self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
        native: Option<&NativeVisionRequest>,
    ) -> Option<Observation> {
        if self
            .run_contexts
            .get(&task)
            .is_some_and(|context| !context.metrics)
        {
            return None;
        }
        let algorithm = match opcode {
            OP_SCREEN_CAPTURE | OP_SCREEN_CAPTURE_SERIES_FRAME => "capture-host-roundtrip",
            OP_SCREEN_CAPTURE_REGION => "capture-region-host-roundtrip",
            OP_SCREEN_FIND_IMAGE => "rust-rgba-single",
            OP_SCREEN_FIND_IMAGES => "rust-rgba-first",
            OP_SCREEN_FIND_ALL_IMAGES => "rust-rgba-all",
            5108 => "opencv-gray-host-roundtrip",
            5109 => "opencv-orb-ransac-host-roundtrip",
            6101 => "onnx-ocr-host-roundtrip",
            6104 => "onnx-en-v5-host-roundtrip",
            6105 => "onnx-latin-text-host-roundtrip",
            _ => return None,
        };
        let mut fields = json!({"version":1,"kind":"vision","opcode":opcode,"algorithm":algorithm});
        if opcode == OP_SCREEN_CAPTURE_REGION {
            fields["captureRegion"] = json!(args
                .iter()
                .filter_map(|value| match value {
                    LuaScalar::Integer(value) => Some(*value),
                    _ => None,
                })
                .collect::<Vec<_>>());
        }
        if matches!(
            opcode,
            OP_SCREEN_FIND_IMAGE | OP_SCREEN_FIND_IMAGES | OP_SCREEN_FIND_ALL_IMAGES
        ) {
            fields["comparisonBudget"] = json!(MAX_TEMPLATE_PIXEL_COMPARISONS);
            fields["timeoutMs"] = json!(5_000);
        }
        if let Some(context) = self.run_contexts.get(&task) {
            fields["flowId"] = json!(context.flow);
            fields["nodeId"] = json!(context.node);
        }
        if let Some(native) = native {
            fields["frame"] = json!([native.frame.metadata.width, native.frame.metadata.height]);
            fields["roi"] = json!([
                native.region.left,
                native.region.top,
                native.region.right,
                native.region.bottom
            ]);
            fields["similarity"] = json!(native.minimum_score);
        } else if matches!(
            opcode,
            OP_SCREEN_FIND_IMAGE | OP_SCREEN_FIND_IMAGES | OP_SCREEN_FIND_ALL_IMAGES
        ) {
            let integer = |index: usize| match args.get(index) {
                Some(LuaScalar::Integer(n)) => Some(*n),
                _ => None,
            };
            if let Some(handle) = integer(0) {
                if let Ok(view) = self.legacy_frame_view(task, handle) {
                    fields["frame"] = json!([view.metadata.width, view.metadata.height]);
                }
            }
            fields["tolerance"] = json!(integer(2));
            fields["similarity"] = json!(integer(3));
            fields["roi"] = json!([integer(4), integer(5), integer(6), integer(7)]);
            fields["direction"] = json!(integer(8).unwrap_or(0));
            fields["limit"] = json!(if opcode == OP_SCREEN_FIND_ALL_IMAGES {
                integer(9)
            } else {
                None
            });
        }
        if native.is_some()
            || matches!(
                opcode,
                OP_SCREEN_FIND_IMAGE | OP_SCREEN_FIND_IMAGES | OP_SCREEN_FIND_ALL_IMAGES
            )
        {
            let (sizes, fingerprints) = self.template_observation(task, opcode, args, native);
            fields["templateCount"] = json!(sizes.len());
            fields["templateSizes"] = json!(sizes);
            if let Some(fingerprints) = fingerprints {
                fields["templateFingerprintVersion"] = json!("sha256-rgba-v1");
                fields["templateFingerprints"] = json!(fingerprints);
            }
        }
        Some(Observation {
            started: Instant::now(),
            fields,
        })
    }
    pub(super) fn finish_observation(&mut self, observation: Option<Observation>, status: &str) {
        if let Some(mut observation) = observation {
            observation.fields["elapsedUs"] =
                json!(u64::try_from(observation.started.elapsed().as_micros()).unwrap_or(u64::MAX));
            observation.fields["status"] = json!(status);
            self.record_run_event(&observation.fields);
        }
    }
}
pub(super) fn result_status(result: &Result<LuaInput, String>) -> &'static str {
    match result {
        Err(message)
            if message.contains("BUDGET_EXCEEDED") || message.contains("COMPARISON_BUDGET") =>
        {
            "budget_exceeded"
        }
        Err(message) if message.contains("TIMEOUT") => "timeout",
        Err(message) if message.contains("Cancelled") || message.contains("CANCELLED") => {
            "cancelled"
        }
        Err(message)
            if message.contains("SCREEN_CAPTURE_FAILED")
                || message.contains("CAPTURE_BACKEND_FAILED")
                || message.contains("CAPTURE_BACKEND_UNAVAILABLE")
                || message.contains("CAPTURE_CONNECTION_CLOSED") =>
        {
            "capture_failed"
        }
        Err(message) if message.contains("FRAME_OPERATION_FAILED") => "frame_failed",
        Err(_) => "error",
        Ok(LuaInput::Values(values)) if matches!(values.get(1), Some(LuaScalar::Nil)) => {
            "not_found"
        }
        Ok(LuaInput::Values(values)) if matches!(values.get(1), Some(LuaScalar::Bytes(bytes)) if bytes.is_empty()) => {
            "not_found"
        }
        Ok(LuaInput::Values(values)) if matches!(values.get(1), Some(LuaScalar::Bytes(bytes)) if serde_json::from_slice::<Value>(bytes).ok().is_some_and(|v| v.get("count").and_then(Value::as_u64) == Some(0))) => {
            "not_found"
        }
        Ok(_) => "found",
    }
}

#[cfg(test)]
mod tests {
    use super::result_status;
    use crate::{
        ExecutorConfig, HostBackend, HostRequest, RuntimeExecutor, VirtualHost, VirtualHostMode,
    };
    use lua_runtime::{LuaInput, LuaRuntimeConfig, LuaScalar};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use serde_json::Value;
    use std::time::Duration;

    fn runtime<H: HostBackend>(clock: &ManualClock, host: H) -> RuntimeExecutor<ManualClock, H> {
        RuntimeExecutor::new(
            clock.clone(),
            SchedulerConfig::default(),
            LuaRuntimeConfig::default(),
            ExecutorConfig::default(),
            host,
        )
        .unwrap()
    }

    #[derive(Debug)]
    struct CaptureHost;
    impl HostBackend for CaptureHost {
        fn dispatch(
            &mut self,
            request: &HostRequest,
            completion: &runtime_scheduler::SchedulerHandle,
        ) -> Result<(), String> {
            completion
                .submit_host_completion(runtime_scheduler::HostCompletion {
                    request_id: request.request_id,
                    task: request.task,
                    result: runtime_scheduler::HostResult::Success(1_i64.to_le_bytes().to_vec()),
                })
                .map_err(|error| format!("{error:?}"))
        }
        fn cancel(
            &mut self,
            _: runtime_scheduler::RequestId,
            _: runtime_scheduler::TaskToken,
            _: script_api::CancelMode,
        ) {
        }
    }

    #[test]
    fn actual_single_named_and_multi_searches_emit_work_only_when_enabled() {
        for enabled in [true, false] {
            let clock = ManualClock::new(MonoTime::ZERO);
            let mut runtime = runtime(&clock, CaptureHost);
            let metadata = automation_core::FrameMetadata {
                width: 1,
                height: 1,
                row_stride: 4,
                pixel_stride: 4,
                format: automation_core::FrameFormat::Rgba8888,
                rotation: automation_core::Rotation::Degrees0,
                timestamp_nanos: 1,
                snapshot_id: 1,
            };
            {
                let mut pool = runtime.frames.lock().unwrap();
                pool.publish_capture(metadata, &[1, 2, 3, 255]).unwrap();
                pool.register_template(
                    "assets/images/private-template.png",
                    metadata,
                    std::sync::Arc::from([1, 2, 3, 255]),
                )
                .unwrap();
            }
            let script = format!(
                r#"return function()
                coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','main','node-1',false,{enabled})
                local f=Screen.cache(Screen.capture())
                local t=Screen.loadImage('assets/images/private-template.png')
                assert(Screen.findImage(f,t,0,1000,0,0,1,1).x==0)
                assert(Screen.findImages(f,'["assets/images/private-template.png"]',0,1000,0,0,1,1,0).x==0)
                local r=Screen.findAllImages(f,'["assets/images/private-template.png"]',0,1000,0,0,1,1,0,1)
                assert(Screen.matchValue(r,0,'count')=='1')
            end"#
            );
            let task = runtime
                .start_entry_chunk(script.as_bytes(), "search-work")
                .unwrap();
            runtime.drive().unwrap();
            assert_eq!(
                runtime.task_state(task),
                Some(TaskState::Completed),
                "{:?}",
                runtime.task_failure(task)
            );
            let events = runtime.drain_run_events();
            assert!(!events.join("\n").contains("private-template"));
            if !enabled {
                assert!(events.is_empty());
                continue;
            }
            let matches: Vec<Value> = events
                .iter()
                .map(|s| serde_json::from_str::<Value>(s).unwrap())
                .filter(|v| {
                    v["algorithm"]
                        .as_str()
                        .is_some_and(|s| s.starts_with("rust-rgba"))
                })
                .collect();
            assert_eq!(matches.len(), 3);
            let expected_fingerprint = super::fingerprint_text(
                runtime
                    .frames
                    .lock()
                    .unwrap()
                    .template_fingerprint("assets/images/private-template.png")
                    .unwrap(),
            );
            for event in matches {
                assert_eq!(event["templateFingerprintVersion"], "sha256-rgba-v1");
                assert_eq!(
                    event["templateFingerprints"],
                    serde_json::json!([expected_fingerprint])
                );
                assert_eq!(event["status"], "found");
                assert_eq!(event["matchWork"]["comparisonPixels"], 1);
                assert_eq!(event["matchWork"]["candidatesVisited"], 1);
                assert_eq!(event["matchWork"]["preparedCacheHits"], 1);
                assert_eq!(event["matchWork"]["templatesPrepared"], 0);
                assert_eq!(event["matchWork"]["prefiltersBuilt"], 0);
                assert!(event["matchStagesUs"]["searchCompare"].is_u64());
            }
        }
    }

    #[test]
    fn capture_phase_numbers_reach_events_without_fabricating_missing_phases() {
        for stages in [None, Some([1, 2, 3, 4, 5, 6])] {
            let clock = ManualClock::new(MonoTime::ZERO);
            let mut runtime = runtime(&clock, CaptureHost);
            {
                let mut pool = runtime.frames.lock().unwrap();
                let capture = pool
                    .publish_capture(
                        automation_core::FrameMetadata {
                            width: 1,
                            height: 1,
                            row_stride: 4,
                            pixel_stride: 4,
                            format: automation_core::FrameFormat::Rgba8888,
                            rotation: automation_core::Rotation::Degrees0,
                            timestamp_nanos: 1,
                            snapshot_id: 1,
                        },
                        &[1, 2, 3, 255],
                    )
                    .unwrap();
                assert_eq!(capture.0, 1);
                pool.set_capture_timings(
                    capture,
                    automation_core::FrameCaptureTimings {
                        backend: 2,
                        acquisition_us: 30,
                        transfer_us: 20,
                        publish_us: 10,
                        streamed: true,
                        pipelined: false,
                        stages_us: stages,
                        transfer_stages_us: stages.map(|_| [7, 8, 9, 10, 11]),
                        transfer_packets: stages.map(|_| 1),
                        cache_hit: false,
                        cache_age_us: None,
                    },
                );
            }
            let task = runtime
                .start_entry_chunk(b"return function() Screen.capture() end", "capture-phases")
                .unwrap();
            runtime.drive().unwrap();
            assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
            let event: Value = serde_json::from_str(&runtime.drain_run_events()[0]).unwrap();
            assert_eq!(event["captureStagesUs"]["acquisition"], 30);
            if stages.is_some() {
                assert_eq!(event["captureTransferUs"]["verify"], 10);
                assert_eq!(event["captureTransferUs"]["packets"], 1);
            } else {
                assert!(event.get("captureTransferUs").is_none());
            }
            for (index, name) in [
                "systemCapture",
                "softwareReadback",
                "pixelRead",
                "pixelEncode",
                "bridgeWrite",
                "bridgeReceive",
            ]
            .iter()
            .enumerate()
            {
                if let Some(stages) = stages {
                    assert_eq!(event["captureStagesUs"][name], stages[index]);
                } else {
                    assert!(event["captureStagesUs"].get(name).is_none());
                }
            }
        }
    }

    #[test]
    fn explicit_region_is_dispatched_and_observed_separately_and_rejects_invalid_lua_bounds() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut executor = runtime(&clock, CaptureHost);
        let task=executor.start_entry_chunk(b"return function() local c=Screen.captureRegion(149,407,546,879); assert(c==1); local ok=pcall(Screen.captureRegion,4,0,4,2);assert(not ok) end","region").unwrap();
        executor.drive().unwrap();
        assert_eq!(executor.task_state(task), Some(TaskState::Completed));
        let events = executor.drain_run_events();
        assert_eq!(events.len(), 1);
        let event: Value = serde_json::from_str(&events[0]).unwrap();
        assert_eq!(event["opcode"], 5014);
        assert_eq!(event["algorithm"], "capture-region-host-roundtrip");
        assert_eq!(
            event["captureRegion"],
            serde_json::json!([149, 407, 546, 879])
        );
    }

    #[test]
    fn trace_and_metrics_are_independent_and_never_contain_log_text() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, CaptureHost);
        let task = runtime.start_entry_chunk(br"return function() coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','main','node-1',true,true); Log.info('private-output'); Screen.capture(); coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','main','node-2',false,false); Screen.capture() end", "observability").unwrap();
        runtime.drive().unwrap();
        assert_eq!(
            runtime.task_state(task),
            Some(TaskState::Completed),
            "{:?}",
            runtime.task_failure(task)
        );
        let events = runtime.drain_run_events();
        assert_eq!(events.len(), 2);
        assert!(!events.join("\n").contains("private-output"));
        let metric: Value = serde_json::from_str(&events[1]).unwrap();
        assert_eq!(metric["nodeId"], "node-1");
        assert_eq!(metric["status"], "completed");
        assert_eq!(runtime.drain_script_logs().len(), 1);
    }

    #[test]
    fn bounded_trace_reports_loss_and_masks_noncanonical_identity_text() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHost::new(720, 1280));
        let task = runtime.start_entry_chunk(br"return function() for i=1,1000 do coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','main','node-1',true,false) end end", "trace-budget").unwrap();
        for _ in 0..100 {
            runtime.drive().unwrap();
            if runtime.task_state(task) == Some(TaskState::Completed) {
                break;
            }
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        let events = runtime.drain_run_events();
        assert_eq!(events.len(), 257);
        let dropped: Value = serde_json::from_str(events.last().unwrap()).unwrap();
        assert_eq!(dropped["kind"], "dropped");
        assert_eq!(dropped["count"], 745);
        assert!(runtime.drain_run_events().is_empty());
        let task = runtime.start_entry_chunk(br"return function() coroutine.yield('__AUTOSCRIPT_RUN_CONTEXT_V1','secret/path','node',true,false) end", "invalid-trace").unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        let masked = runtime.drain_run_events().join("\n");
        assert!(masked.contains("anonymous:"));
        assert!(!masked.contains("secret/path"));
    }

    #[test]
    fn host_timeout_and_cancel_are_observed_without_changing_control() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut host = VirtualHost::new(720, 1280);
        host.mode = VirtualHostMode::NeverComplete;
        let mut runtime = runtime(&clock, host);
        let task = runtime
            .start_entry_chunk(b"return function() Screen.capture() end", "capture-timeout")
            .unwrap();
        runtime.drive().unwrap();
        clock.advance(Duration::from_secs(30));
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Failed));
        let events = runtime.drain_run_events();
        assert!(events
            .iter()
            .any(|line| line.contains("\"status\":\"timeout\"")));
        let task = runtime
            .start_entry_chunk(b"return function() Screen.capture() end", "capture-cancel")
            .unwrap();
        runtime.drive().unwrap();
        runtime.handle().request_cancel(task).unwrap();
        runtime.drive().unwrap();
        assert_eq!(runtime.task_state(task), Some(TaskState::Cancelled));
        assert!(runtime
            .drain_run_events()
            .iter()
            .any(|line| line.contains("\"status\":\"cancelled\"")));
    }

    #[test]
    fn script_log_ring_loss_is_visible_in_the_independent_channel() {
        let clock = ManualClock::new(MonoTime::ZERO);
        let mut runtime = runtime(&clock, VirtualHost::new(720, 1280));
        let task = runtime
            .start_entry_chunk(
                b"return function() for i=1,300 do Log.info(i) end end",
                "log-loss",
            )
            .unwrap();
        for _ in 0..20 {
            runtime.drive().unwrap();
            if runtime.task_state(task) == Some(TaskState::Completed) {
                break;
            }
        }
        assert_eq!(runtime.task_state(task), Some(TaskState::Completed));
        assert_eq!(runtime.drain_script_logs().len(), 200);
        let events = runtime.drain_run_events();
        let loss: Value = serde_json::from_str(&events[0]).unwrap();
        assert_eq!(loss["scriptLogCount"], 100);
        assert_eq!(loss["eventCount"], 0);
        assert!(runtime.drain_run_events().is_empty());
    }

    #[test]
    fn not_found_budget_and_timeout_have_distinct_statuses() {
        assert_eq!(
            result_status(&Err("VISION_COMPARISON_BUDGET_EXCEEDED".into())),
            "budget_exceeded"
        );
        assert_eq!(result_status(&Err("VISION_TIMEOUT".into())), "timeout");
        assert_eq!(result_status(&Err("Vision(Cancelled)".into())), "cancelled");
        assert_eq!(
            result_status(&Err("SCREEN_CAPTURE_FAILED: CAPTURE_BACKEND_FAILED".into())),
            "capture_failed"
        );
        assert_eq!(
            result_status(&Err("FRAME_OPERATION_FAILED".into())),
            "frame_failed"
        );
        assert_eq!(
            result_status(&Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Bytes(br#"{"count":0,"matches":[]}"#.to_vec())
            ]))),
            "not_found"
        );
    }
}
