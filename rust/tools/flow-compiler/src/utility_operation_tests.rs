use crate::{compile_project, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde_json::{json, Value};

fn project() -> Value {
    json!({"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.8","projectId":"utility-test","name":"Utility",
        "sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
        "capabilities":["core.math","core.task","device.display"],"variables":[
            {"name":"i","type":"integer","scope":"global"},{"name":"n","type":"number","scope":"global"},
            {"name":"s","type":"string","scope":"global"},{"name":"w","type":"integer","scope":"global"},{"name":"h","type":"integer","scope":"global"}],
        "design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}})
}

#[test]
fn number_at_uses_declared_typed_operands_and_round_trips() {
    let args = json!({"text":"100(+100) 180","textVariable":"s","index":2,"resultVariable":"s"});
    let output = compile(&project(), &[("text.numberat", args.clone())]).unwrap();
    let lua = String::from_utf8(output.main_lua).unwrap();
    assert!(lua.contains("Text.numberAt(__vars[\"s\"],2)"));
    for (key, value) in [
        ("index", json!(0)),
        ("index", json!(65)),
        ("resultVariable", json!("i")),
        ("textVariable", json!("i")),
        ("textVariable", json!("missing")),
    ] {
        let mut bad = args.clone();
        bad[key] = value;
        assert!(
            compile(&project(), &[("text.numberat", bad)]).is_err(),
            "{key}"
        );
    }
    let mut old = project();
    old["runtimeApi"] = json!("1.7");
    assert!(compile(&old, &[("text.numberat", args)]).is_err());
}

#[test]
fn glyph_palette_is_explicit_validated_and_old_engines_fail_before_loading() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "ocr.glyph"]);
    manifest["resources"] = json!([{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}]);
    for (name, kind) in [
        ("frame", "image"),
        ("score", "integer"),
        ("coverage", "integer"),
    ] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":kind,"scope":"global"}));
    }
    let mut args = json!({"frameVariable":"frame","dictionaryPath":"dictionaries/main.asglyph","foregroundRgb":16_711_680,
        "tolerance":0,"similarityPermille":1000,"region":{"left":0,"top":0,"right":3,"bottom":3},"spaceGapColumns":0,
        "textVariable":"s","coverageVariable":"coverage","scoreVariable":"score","additionalColors":"00FF00:8"});
    let output = compile(&manifest, &[("ocr.glyph", args.clone())]).unwrap();
    let lua = String::from_utf8(output.main_lua).unwrap();
    assert!(lua.contains("Ocr.glyphPaletteVersion ~= 1") && lua.contains("00FF00:8"));
    for bad in ["FF0000:8", "00FF00:256", "00FF00:8;00ff00:9", "00FF00:"] {
        args["additionalColors"] = json!(bad);
        assert!(
            compile(&manifest, &[("ocr.glyph", args.clone())]).is_err(),
            "{bad}"
        );
    }
    args.as_object_mut().unwrap().remove("additionalColors");
    let old =
        String::from_utf8(compile(&manifest, &[("ocr.glyph", args)]).unwrap().main_lua).unwrap();
    assert!(!old.contains("glyphPaletteVersion"));
}

#[test]
fn codec_additions_cannot_claim_the_older_api_minor() {
    let mut manifest = project();
    let args = json!({"text":"public","resultVariable":"s"});
    assert!(compile(&manifest, &[("text.tohex", args.clone())]).is_ok());
    manifest["runtimeApi"] = json!("1.7");
    assert!(compile(&manifest, &[("text.tohex", args)]).is_err());
}

#[test]
fn loaded_frame_information_and_release_do_not_require_screen_capture() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "vision.template"]);
    manifest["resources"] = json!([{"kind":"image","path":"assets/images/public.png"}]);
    manifest["variables"]
        .as_array_mut()
        .unwrap()
        .push(json!({"name":"frame","type":"image","scope":"global"}));
    let result = compile(
        &manifest,
        &[
            (
                "screen.loadimage",
                json!({"imagePath":"assets/images/public.png","resultVariable":"frame"}),
            ),
            (
                "screen.frameinfo",
                json!({"frameVariable":"frame","resultVariable":"s"}),
            ),
            ("screen.release", json!({"frameVariable":"frame"})),
        ],
    )
    .unwrap();
    let lua = String::from_utf8(result.main_lua).unwrap();
    assert!(lua.contains("Screen.frameInfo") && lua.contains("Screen.release"));
    assert!(!lua.contains("Screen.capture()"));
    assert!(compile(
        &manifest,
        &[("screen.capture", json!({"resultVariable":"frame"}))]
    )
    .is_err());
}

#[test]
fn frame_queries_and_scaled_templates_are_typed_and_emit_the_new_fixed_apis() {
    let mut manifest = project();
    manifest["capabilities"] = json!([
        "core.task",
        "screen.capture",
        "vision.pixel",
        "vision.template"
    ]);
    manifest["resources"] = json!([{"kind":"image","path":"assets/images/public.png"}]);
    for name in ["frame", "previous"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let compare = json!({"frameVariable":"frame","previousFrameVariable":"previous","left":0,"top":0,"right":2,"bottom":2,"tolerance":0,"resultVariable":"s"});
    let map = json!({"frameVariable":"frame","x":0,"y":0,"xVariable":"i","yVariable":"","resultVariable":"s"});
    let scaled = json!({"frameVariable":"frame","imagePath":"assets/images/public.png","left":0,"top":0,"right":2,"bottom":2,"tolerance":0,"similarityPermille":1000,"direction":0,"minScalePermille":750,"maxScalePermille":1250,"stepPermille":125,"resultVariable":"s"});
    let output=compile(&manifest,&[("screen.compareframes",compare.clone()),("screen.mappoint",map.clone()),("vision.findscaled",scaled.clone()),
        ("vision.readresult",json!({"sourceVariable":"s","shape":"SCALED_IMAGE","index":0,"field":"count","resultVariable":"i"}))]).unwrap();
    let lua = String::from_utf8(output.main_lua).unwrap();
    assert!(
        lua.contains("Screen.compareFrames")
            && lua.contains("Screen.mapPoint")
            && lua.contains("pcall(Screen.findScaled")
    );
    assert!(!lua.contains("Input.tap") && !lua.contains("Screen.capture()"));
    let mut bad = scaled;
    bad["stepPermille"] = json!(1);
    assert!(compile(&manifest, &[("vision.findscaled", bad)]).is_err());
    let mut bad = map;
    bad["xVariable"] = json!("s");
    assert!(compile(&manifest, &[("screen.mappoint", bad)]).is_err());
    let mut bad = compare;
    bad["previousFrameVariable"] = json!("frame");
    assert!(compile(&manifest, &[("screen.compareframes", bad)]).is_err());
}

#[test]
fn recognition_reader_and_actions_are_typed_bounded_and_emit_existing_apis() {
    let mut manifest = project();
    manifest["capabilities"]
        .as_array_mut()
        .unwrap()
        .push(json!("input.basic"));
    let reader = json!({"sourceVariable":"s","shape":"GLYPH_ITEM","index":1,"field":"recognized","resultVariable":"i"});
    let action = json!({"sourceVariable":"s","shape":"GLYPH_ITEM","selection":"all","anchor":"center","action":"tap","limit":2,"durationMs":100,"offsetX":0,"offsetY":0,"screenConfirmed":true});
    let output = compile(
        &manifest,
        &[
            ("vision.readresult", reader.clone()),
            ("vision.resultaction", action.clone()),
        ],
    )
    .unwrap()
    .main_lua;
    let lua = String::from_utf8(output).unwrap();
    assert!(
        lua.contains("Screen.recognitionValue")
            && lua.contains("Input.tapScreen")
            && lua.contains("System.getScreenSize")
    );
    assert!(lua.contains("recognized") && lua.contains("RESULT_ACTION_OUT_OF_BOUNDS"));
    assert!(!lua.contains("=int("));
    let mut wrong = reader;
    wrong["resultVariable"] = json!("s");
    assert!(compile(&manifest, &[("vision.readresult", wrong)]).is_err());
    let mut unconfirmed = action.clone();
    unconfirmed["screenConfirmed"] = json!(false);
    assert!(compile(&manifest, &[("vision.resultaction", unconfirmed)]).is_err());
    let mut hold = action;
    hold["action"] = json!("hold");
    assert!(compile(&manifest, &[("vision.resultaction", hold)]).is_err());
}
fn compile(
    manifest: &Value,
    nodes: &[(&str, Value)],
) -> Result<crate::GenerationBundle, Vec<crate::CompileError>> {
    let manifest = parse_project_manifest(&serde_json::to_vec(manifest).unwrap()).unwrap();
    let source = nodes
        .iter()
        .enumerate()
        .map(|(i, (kind, args))| {
            json!({"flowSchemaVersion":1,"nodeId":format!("n{i}"),"blockId":"root","parentId":null,
        "depth":0,"nodeVersion":if *kind == "vision.findimage" {2} else {1},"orderKey":format!("a{i:03}"),"kind":kind,"args":args})
            .to_string()
                + "\n"
        })
        .collect::<String>();
    let report = load_jsonl(
        source.as_bytes(),
        LoadOptions {
            flow_id: "main",
            root_block_id: "root",
            flow_schema_version: 1,
            supported_nodes: SUPPORTED_NODE_VERSIONS,
        },
    );
    compile_project(
        &manifest,
        &[FlowSource {
            flow_id: "main",
            exact_bytes: source.as_bytes(),
            report: &report,
        }],
    )
}
#[test]
fn utility_blocks_freeze_and_execute_with_real_variable_outputs() {
    use crate::utility_operations::{ScalarKind, SPECS};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    let mut nodes = Vec::new();
    for (kind, spec) in SPECS
        .iter()
        .chain(crate::pure_utility_specs::SPECS.iter())
        .chain(crate::codec_utility_specs::SPECS.iter())
    {
        let mut args = json!({});
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../schema/block-catalog/blocks")
            .join(format!("{kind}.json"));
        let contract: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        for field in spec.fields {
            let default = contract["properties"]
                .as_array()
                .unwrap()
                .iter()
                .find(|p| p["path"].as_str() == Some(field.name))
                .unwrap()["defaultValue"]
                .as_str()
                .unwrap();
            args[field.name] = if field.kind == ScalarKind::String {
                json!(default)
            } else {
                serde_json::from_str(default).unwrap()
            };
        }
        if spec.size {
            args["widthVariable"] = json!("w");
            args["heightVariable"] = json!("h");
        } else {
            args["resultVariable"] = json!(match spec.result {
                ScalarKind::Integer => "i",
                ScalarKind::Number => "n",
                ScalarKind::String => "s",
            });
        }
        nodes.push((*kind, args));
    }
    nodes.push((
        "task.message",
        json!({"expression":"text(w) .. \":\" .. text(h)","channel":"console"}),
    ));
    let bundle = compile(&project(), &nodes).unwrap();
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(720, 1280),
    )
    .unwrap();
    runtime
        .set_allowed_capabilities(&[
            "core.math".into(),
            "core.task".into(),
            "device.display".into(),
        ])
        .unwrap();
    let owner = runtime
        .start_entry_chunk(&bundle.main_lua, "utility-compiled")
        .unwrap();
    for _ in 0..32 {
        runtime.drive().unwrap();
        if runtime.task_state(owner) == Some(TaskState::Completed) {
            break;
        }
    }
    assert_eq!(
        runtime.task_state(owner),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(owner)
    );
    assert!(runtime
        .drain_script_logs()
        .iter()
        .any(|s| s.contains("720:1280")));
}
#[test]
fn typed_sources_capabilities_old_api_and_unknown_fields_are_rejected() {
    let args = json!({"min":0,"max":10,"minVariable":"i","resultVariable":"i"});
    assert!(compile(&project(), &[("math.randint", args.clone())]).is_ok());
    for (key, value) in [
        ("minVariable", json!("n")),
        ("minVariable", json!("missing")),
        ("resultVariable", json!("s")),
        ("resultVariable", json!("missing")),
        ("min", json!(1.5)),
        ("injectedCode", json!("os.execute('bad')")),
    ] {
        let mut invalid = args.clone();
        invalid[key] = value;
        assert!(compile(&project(), &[("math.randint", invalid)]).is_err());
    }
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task"]);
    assert!(compile(&manifest, &[("math.randint", args.clone())]).is_err());
    manifest = project();
    manifest["runtimeApi"] = json!("1.6");
    assert!(compile(&manifest, &[("math.randint", args)]).is_err());
    assert!(compile(
        &project(),
        &[(
            "system.screensize",
            json!({"widthVariable":"w","heightVariable":"w"})
        )]
    )
    .is_err());
}

#[test]
fn pure_utilities_validate_operation_choices_sources_outputs_and_capabilities() {
    let args = json!({"operation":"sqrt","value":9,"valueVariable":"i","resultVariable":"n"});
    let output = compile(&project(), &[("math.unary", args.clone())]).unwrap();
    assert!(String::from_utf8(output.main_lua)
        .unwrap()
        .contains("Math.unary(\"sqrt\",__vars[\"i\"])"));
    for (key, value) in [
        ("operation", json!("os.execute")),
        ("value", json!(1e13)),
        ("valueVariable", json!("s")),
        ("resultVariable", json!("i")),
        ("resultVariable", json!("missing")),
    ] {
        let mut bad = args.clone();
        bad[key] = value;
        assert!(compile(&project(), &[("math.unary", bad)]).is_err());
    }
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task"]);
    assert!(compile(&manifest, &[("math.unary", args)]).is_err());
    let text =
        json!({"text":"甲12","pattern":"[0-9]+","replacement":"\"$1\n","resultVariable":"s"});
    assert!(compile(&project(), &[("text.regexreplace", text.clone())]).is_ok());
    let mut bad = text;
    bad["pattern"] = json!("a".repeat(129));
    assert!(compile(&project(), &[("text.regexreplace", bad)]).is_err());
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.math"]);
    assert!(compile(
        &manifest,
        &[(
            "data.encode",
            json!({"value":"x","mode":"text","resultVariable":"s"})
        )]
    )
    .is_err());
}
#[test]
fn all_extended_file_blocks_freeze_dispatch_and_receive_exact_typed_results() {
    use crate::utility_operations::ScalarKind;
    use runtime_executor::{
        ExecutorConfig, ExternalHost, ExternalHostEvent, ExternalHostQueue, RuntimeExecutor,
    };
    use runtime_scheduler::{
        HostCompletion, HostResult, ManualClock, MonoTime, SchedulerConfig, TaskState,
    };
    use std::time::Duration;
    let mut nodes = Vec::new();
    for (kind, spec) in crate::file_utility_specs::SPECS {
        let mut args = default_file_arguments(kind, spec);
        args["resultVariable"] = json!(if spec.result == ScalarKind::Integer {
            "i"
        } else {
            "s"
        });
        nodes.push((*kind, args));
    }
    let mut manifest = project();
    assert!(compile(&manifest, &nodes).is_err());
    manifest["capabilities"] = json!(["core.task", "storage.data"]);
    manifest["runtimeApi"] = json!("1.8");
    let bundle = compile(&manifest, &nodes).unwrap();
    let queue = ExternalHostQueue::new(4, 4).unwrap();
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        ExternalHost::new(queue.clone()),
    )
    .unwrap();
    runtime
        .set_allowed_capabilities(&["core.task".into(), "storage.data".into()])
        .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "compiled-private-files")
        .unwrap();
    for (kind, spec) in crate::file_utility_specs::SPECS {
        let mut event = None;
        for _ in 0..8 {
            runtime.drive().unwrap();
            event = queue.wait_next_timeout(Duration::from_millis(1));
            if event.is_some() {
                break;
            }
        }
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = event.unwrap_or_else(|| {
            panic!(
                "{}: {:?} {:?}",
                spec.api,
                runtime.task_state(task),
                runtime.task_failure(task)
            )
        })
        else {
            panic!("file call did not dispatch")
        };
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../schema/api-schema/functions")
            .join(format!("{}.json", kind.replace('.', "-")));
        let contract: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
        assert_eq!(
            u64::from(request.opcode),
            contract["opcode"].as_u64().unwrap()
        );
        assert_eq!(request.timeout, Duration::from_secs(3));
        let payload = if spec.result == ScalarKind::String {
            b"\"value\"".to_vec()
        } else {
            b"1".to_vec()
        };
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(payload),
            })
            .unwrap();
    }
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(task)
    );
    let mut invalid = nodes[0].1.clone();
    invalid["path"] = json!("../outside");
    assert!(compile(&manifest, &[(nodes[0].0, invalid)]).is_err());
}
fn default_file_arguments(kind: &str, spec: &crate::utility_operations::Spec) -> Value {
    use crate::utility_operations::ScalarKind;
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../schema/block-catalog/blocks")
        .join(format!("{kind}.json"));
    let contract: Value = serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap();
    let mut args = json!({});
    for field in spec.fields {
        let text = contract["properties"]
            .as_array()
            .unwrap()
            .iter()
            .find(|p| p["path"].as_str() == Some(field.name))
            .unwrap()["defaultValue"]
            .as_str()
            .unwrap();
        args[field.name] = if field.kind == ScalarKind::String {
            json!(text)
        } else {
            serde_json::from_str(text).unwrap()
        };
    }
    args
}

#[test]
fn compiled_series_crop_borrow_and_release_use_real_frame_resources() {
    use automation_core::{FrameFormat, FrameMetadata, FramePool, FramePoolConfig, Rotation};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::sync::{Arc, Mutex};
    let mut manifest = project();
    manifest["capabilities"] = json!([
        "core.task",
        "screen.capture",
        "screen.capture.series",
        "vision.pixel"
    ]);
    for name in ["frame", "cropped"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let bundle=compile(&manifest,&[
        ("screen.series",json!({"maxFrames":1,"durationMs":1000,"targetFps":10,"allowPartial":false,"resultVariable":"s"})),
        ("screen.seriesframe",json!({"sourceVariable":"s","index":1,"resultVariable":"frame"})),
        ("screen.crop",json!({"frameVariable":"frame","left":1,"top":0,"right":2,"bottom":1,"resultVariable":"cropped"})),
        ("vision.getcolor",json!({"frameVariable":"cropped","point":{"x":0,"y":0},"resultVariable":"i"})),
        ("screen.release",json!({"frameVariable":"cropped"})),
        ("screen.seriesrelease",json!({"sourceVariable":"s"})),
        ("task.message",json!({"expression":"text(i)","channel":"console"})),
    ]).unwrap();
    let frames = Arc::new(Mutex::new(FramePool::new(FramePoolConfig::default())));
    let capture = frames
        .lock()
        .unwrap()
        .publish_capture(
            FrameMetadata {
                width: 2,
                height: 1,
                row_stride: 8,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 123,
                snapshot_id: 1,
            },
            &[0, 0, 0, 255, 10, 20, 30, 255],
        )
        .unwrap();
    let mut runtime = RuntimeExecutor::new_with_frames(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        CaptureHost(capture.0),
        frames,
    )
    .unwrap();
    runtime
        .set_allowed_capabilities(&[
            "core.task".into(),
            "screen.capture".into(),
            "screen.capture.series".into(),
            "vision.pixel".into(),
        ])
        .unwrap();
    let owner = runtime
        .start_entry_chunk(&bundle.main_lua, "compiled-frame-resources")
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(owner),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(owner)
    );
    assert_eq!(runtime.resources().resource_count(), 0);
    assert!(runtime
        .drain_script_logs()
        .iter()
        .any(|s| s.contains("660510")));
}

struct CaptureHost(u64);

#[test]
fn color_boolean_outputs_follow_numeric_or_boolean_parameter_type_and_release_frames() {
    for target_type in ["integer", "number", "boolean"] {
        for target_matches in [true, false] {
            color_output_type_case(target_type, target_matches);
        }
    }
}

fn color_output_type_case(target_type: &str, target_matches: bool) {
    use automation_core::{FrameFormat, FrameMetadata, FramePool, FramePoolConfig, Rotation};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::sync::{Arc, Mutex};
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "screen.capture", "vision.pixel"]);
    manifest["variables"]
        .as_array_mut()
        .unwrap()
        .push(json!({"name":"frame","type":"image","scope":"global"}));
    if target_type == "boolean" {
        // An explicitly typed plugin argument shadows the integer global.
        manifest["flows"][0]["params"] = json!([{"name":"i","type":"boolean","required":false}]);
    } else {
        manifest["variables"][0]["type"] = json!(target_type);
    }
    let rgb = if target_matches {
        0x000a_141e
    } else {
        0x00ff_ffff
    };
    let roi = json!({"left":0,"top":0,"right":2,"bottom":1});
    let mut nodes = vec![("screen.capture", json!({"resultVariable":"frame"}))];
    for (kind, args) in [
        (
            "vision.findcolor",
            json!({"frameVariable":"frame","rgb":rgb,"tolerance":0,"region":roi,"foundVariable":"i","xVariable":"w","yVariable":"h"}),
        ),
        (
            "vision.findmulticolor",
            json!({"frameVariable":"frame","anchorRgb":rgb,"anchorTolerance":0,"samples":[{"x":1,"y":0,"rgb":0,"tolerance":0}],"region":roi,"foundVariable":"i","xVariable":"w","yVariable":"h"}),
        ),
        (
            "vision.comparecolor",
            json!({"frameVariable":"frame","point":{"x":0,"y":0},"rgb":rgb,"tolerance":0,"resultVariable":"i"}),
        ),
    ] {
        nodes.push((kind, args));
        nodes.push((
            "task.log",
            json!({"level":"info","message":"QA","valueVariable":"i"}),
        ));
    }
    nodes.push(("screen.release", json!({"frameVariable":"frame"})));
    let bundle = compile(&manifest, &nodes).unwrap();
    let frames = Arc::new(Mutex::new(FramePool::new(FramePoolConfig::default())));
    let capture = frames
        .lock()
        .unwrap()
        .publish_capture(
            FrameMetadata {
                width: 2,
                height: 1,
                row_stride: 8,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 1,
                snapshot_id: 1,
            },
            &[10, 20, 30, 255, 0, 0, 0, 255],
        )
        .unwrap();
    let mut runtime = RuntimeExecutor::new_with_frames(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        CaptureHost(capture.0),
        frames,
    )
    .unwrap();
    runtime
        .set_allowed_capabilities(&[
            "core.task".into(),
            "screen.capture".into(),
            "vision.pixel".into(),
        ])
        .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "color-output-types")
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{target_type}: {:?}",
        runtime.task_failure(task)
    );
    assert_eq!(runtime.resources().resource_count(), 0);
    assert_color_output_logs(&runtime.drain_script_logs(), target_type, target_matches);
}

fn assert_color_output_logs(logs: &[String], target_type: &str, target_matches: bool) {
    let expected = if target_type == "boolean" {
        if target_matches {
            "true"
        } else {
            "false"
        }
    } else if target_matches {
        "1"
    } else {
        "0"
    };
    assert_eq!(logs.len(), 3);
    assert!(
        logs.iter()
            .all(|line| line.strip_prefix("脚本/INFO: ") == Some(expected)),
        "{logs:?}"
    );
}

#[test]
fn reading_an_existing_series_does_not_require_capture_permission() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task"]);
    manifest["variables"]
        .as_array_mut()
        .unwrap()
        .push(json!({"name":"frame","type":"image","scope":"global"}));
    assert!(compile(
        &manifest,
        &[(
            "screen.seriesframe",
            json!({"sourceVariable":"s","index":1,"resultVariable":"frame"})
        )]
    )
    .is_ok());
}

impl runtime_executor::HostBackend for CaptureHost {
    fn cancel(
        &mut self,
        _: runtime_scheduler::RequestId,
        _: runtime_scheduler::TaskToken,
        _: script_api::CancelMode,
    ) {
    }
    fn dispatch(
        &mut self,
        request: &runtime_executor::HostRequest,
        completion: &runtime_scheduler::SchedulerHandle,
    ) -> Result<(), String> {
        if request.opcode != 5000 {
            return Err("unexpected test host request".into());
        }
        completion
            .submit_host_completion(runtime_scheduler::HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: runtime_scheduler::HostResult::Success(self.0.to_le_bytes().to_vec()),
            })
            .map_err(|e| format!("{e:?}"))
    }
}
#[test]
fn visual_frame_blocks_reject_wrong_types_bounds_caps_and_unknown_fields() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "screen.capture", "screen.capture.series"]);
    for name in ["frame", "cropped"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let args = json!({"frameVariable":"frame","left":0,"top":0,"right":2,"bottom":1,"resultVariable":"cropped"});
    assert!(compile(&manifest, &[("screen.crop", args.clone())]).is_ok());
    for (key, value) in [
        ("frameVariable", json!("i")),
        ("resultVariable", json!("s")),
        ("resultVariable", json!("frame")),
        ("right", json!(0)),
        ("left", json!(-1)),
        ("shell", json!("secret")),
    ] {
        let mut invalid = args.clone();
        invalid[key] = value;
        assert!(compile(&manifest, &[("screen.crop", invalid)]).is_err());
    }
    let series = json!({"maxFrames":1,"durationMs":1,"targetFps":1,"allowPartial":false,"resultVariable":"s"});
    for (key, value) in [
        ("maxFrames", json!(121)),
        ("durationMs", json!(60001)),
        ("targetFps", json!(0)),
        ("allowPartial", json!(1)),
        ("resultVariable", json!("frame")),
    ] {
        let mut invalid = series.clone();
        invalid[key] = value;
        assert!(compile(&manifest, &[("screen.series", invalid)]).is_err());
    }
    manifest["capabilities"] = json!(["screen.capture", "core.task"]);
    assert!(compile(&manifest, &[("screen.series", series)]).is_err());
}

#[test]
fn compiled_image_load_processing_and_release_execute_existing_rust_frame_contracts() {
    use automation_core::{FrameFormat, FrameMetadata, Rotation};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::sync::Arc;
    let mut manifest = project();
    manifest["capabilities"] = json!([
        "core.task",
        "vision.template",
        "screen.capture",
        "vision.pixel"
    ]);
    manifest["resources"] = json!([{"kind":"image","path":"assets/images/source.png"}]);
    for name in ["scratch", "result"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let bundle = compile(&manifest, &[
        ("screen.loadimage", json!({"imagePath":"assets/images/source.png","resultVariable":"scratch"})),
        ("screen.transform", json!({"frameVariable":"scratch","resultVariable":"result","mode":"gray","width":0,"height":0})),
        ("screen.release", json!({"frameVariable":"scratch"})),
        ("screen.transform", json!({"frameVariable":"result","resultVariable":"scratch","mode":"rotate90","width":0,"height":0})),
        ("screen.release", json!({"frameVariable":"result"})),
        ("screen.crop", json!({"frameVariable":"scratch","resultVariable":"result","left":0,"top":0,"right":1,"bottom":1})),
        ("screen.release", json!({"frameVariable":"scratch"})),
        ("vision.getcolor", json!({"frameVariable":"result","point":{"x":0,"y":0},"resultVariable":"i"})),
        ("screen.release", json!({"frameVariable":"result"})),
        ("task.message", json!({"expression":"text(i)","channel":"console"})),
    ]).unwrap();
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(2, 2),
    )
    .unwrap();
    runtime
        .frame_pool()
        .lock()
        .unwrap()
        .register_template(
            "assets/images/source.png",
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
            Arc::from([
                10, 20, 30, 255, 50, 60, 70, 255, 100, 100, 100, 255, 200, 0, 0, 255,
            ]),
        )
        .unwrap();
    runtime
        .set_allowed_capabilities(&[
            "core.task".into(),
            "vision.template".into(),
            "screen.capture".into(),
            "vision.pixel".into(),
        ])
        .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "synthetic-processing-pipeline")
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(task)
    );
    assert_eq!(runtime.resources().resource_count(), 0);
    assert!(runtime
        .drain_script_logs()
        .iter()
        .any(|line| line.contains("6579300")));
}

#[test]
fn threshold_transform_accepts_bounded_value_and_keeps_legacy_options() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["screen.capture"]);
    for name in ["source", "result"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let with_threshold = compile(
        &manifest,
        &[(
            "screen.transform",
            json!({"frameVariable":"source","resultVariable":"result","mode":"threshold","width":0,"height":0,"threshold":100}),
        )],
    )
    .unwrap();
    let threshold_lua = std::str::from_utf8(&with_threshold.main_lua).unwrap();
    assert!(threshold_lua.contains("threshold"), "{threshold_lua}");
    assert!(threshold_lua.contains("100"), "{threshold_lua}");
    assert!(compile(
        &manifest,
        &[(
            "screen.transform",
            json!({"frameVariable":"source","resultVariable":"result","mode":"gray","width":0,"height":0}),
        )],
    )
    .is_ok());
    assert!(compile(
        &manifest,
        &[(
            "screen.transform",
            json!({"frameVariable":"source","resultVariable":"result","mode":"threshold","width":0,"height":0,"threshold":256}),
        )],
    )
    .is_err());
}

#[test]
fn filter_transform_validates_rules_and_embeds_an_immutable_runtime_spec() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["screen.capture"]);
    for name in ["source", "result"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    let filter = json!({"version":1,"space":"absolute","output":"binary","invert":false,"rules":[{"rgb":16_777_215,"redTolerance":16,"greenTolerance":16,"blueTolerance":16,"exclude":false,"space":"absolute"}]}).to_string();
    let args = json!({"frameVariable":"source","resultVariable":"result","mode":"filter","width":0,"height":0,"filter":filter});
    let compiled = compile(&manifest, &[("screen.transform", args.clone())]).unwrap();
    let lua = std::str::from_utf8(&compiled.main_lua).unwrap();
    assert!(lua.contains("Screen.transform"));
    assert!(lua.contains("redTolerance"));
    let mut bad = args.clone();
    bad["mode"] = json!("gray");
    assert!(compile(&manifest, &[("screen.transform", bad)]).is_err());
    let mut bad = args.clone();
    bad.as_object_mut().unwrap().remove("filter");
    assert!(compile(&manifest, &[("screen.transform", bad)]).is_err());
    let mut bad = args;
    bad["filter"] = json!("{}");
    assert!(compile(&manifest, &[("screen.transform", bad)]).is_err());
}

#[test]
fn compiled_filter_then_template_match_runs_real_rust_and_releases_all_frames() {
    use automation_core::{FrameFormat, FrameMetadata, Rotation};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::sync::Arc;
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "vision.template", "screen.capture"]);
    manifest["resources"] = json!([{"kind":"image","path":"assets/images/source.png"},{"kind":"image","path":"assets/images/template.png"}]);
    for name in ["source", "result"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"image","scope":"global"}));
    }
    for name in ["found", "foundX", "foundY"] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":"integer","scope":"global"}));
    }
    let filter=json!({"version":1,"space":"absolute","output":"binary","invert":false,"rules":[{"rgb":16_711_680,"redTolerance":0,"greenTolerance":0,"blueTolerance":0,"exclude":false,"space":"absolute"}]}).to_string();
    let bundle=compile(&manifest,&[
        ("screen.loadimage",json!({"imagePath":"assets/images/source.png","resultVariable":"source"})),
        ("screen.transform",json!({"frameVariable":"source","resultVariable":"result","mode":"filter","width":0,"height":0,"filter":filter})),
        ("screen.release",json!({"frameVariable":"source"})),
        ("vision.findimage",json!({"imagePath":"assets/images/template.png","frameVariable":"result","tolerance":0,"similarityPermille":1000,"region":{"left":0,"top":0,"right":3,"bottom":1},"foundVariable":"found","xVariable":"foundX","yVariable":"foundY"})),
        ("screen.release",json!({"frameVariable":"result"})),
        ("task.message",json!({"expression":"text(foundX)","channel":"console"})),
    ]).unwrap();
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(3, 1),
    )
    .unwrap();
    for (path, width, pixels) in [
        (
            "assets/images/source.png",
            3,
            vec![0, 0, 0, 255, 255, 0, 0, 255, 0, 255, 0, 255],
        ),
        ("assets/images/template.png", 1, vec![255, 255, 255, 255]),
    ] {
        runtime
            .frame_pool()
            .lock()
            .unwrap()
            .register_template(
                path,
                FrameMetadata {
                    width,
                    height: 1,
                    row_stride: width * 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 0,
                    snapshot_id: 1,
                },
                Arc::from(pixels),
            )
            .unwrap();
    }
    runtime
        .set_allowed_capabilities(&[
            "core.task".into(),
            "vision.template".into(),
            "screen.capture".into(),
        ])
        .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "public-color-filter-pipeline")
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(task)
    );
    assert_eq!(runtime.drain_script_logs(), ["脚本/INFO: 1"]);
    assert_eq!(runtime.resources().resource_count(), 0);
}

#[test]
fn image_load_requires_declared_resource_typed_output_and_exact_fields_not_capture() {
    let mut manifest = project();
    manifest["capabilities"] = json!(["vision.template"]);
    manifest["resources"] = json!([{"kind":"image","path":"assets/images/source.png"}]);
    manifest["variables"]
        .as_array_mut()
        .unwrap()
        .push(json!({"name":"frame","type":"image","scope":"global"}));
    let args = json!({"imagePath":"assets/images/source.png","resultVariable":"frame"});
    assert!(compile(&manifest, &[("screen.loadimage", args.clone())]).is_ok());
    for (key, value) in [
        ("imagePath", json!("assets/images/missing.png")),
        ("imagePath", json!("assets/images/../source.png")),
        ("imagePath", json!("data/source.png")),
        ("resultVariable", json!("i")),
        ("resultVariable", json!("undeclared")),
        ("shell", json!("not-allowed")),
    ] {
        let mut invalid = args.clone();
        invalid[key] = value;
        assert!(compile(&manifest, &[("screen.loadimage", invalid)]).is_err());
    }
    manifest["flows"][0]["params"] = json!([{"name":"frame","type":"integer","required":false}]);
    assert!(compile(&manifest, &[("screen.loadimage", args.clone())]).is_err());
    manifest["flows"][0]["params"] = json!([]);
    manifest["capabilities"] = json!([]);
    assert!(compile(&manifest, &[("screen.loadimage", args)]).is_err());
}

#[test]
fn processed_pixel_matching_tail_reads_final_frame_without_capture_or_touch_and_releases() {
    use automation_core::{FrameFormat, FrameMetadata, Rotation};
    use runtime_executor::{ExecutorConfig, RuntimeExecutor, VirtualHost};
    use runtime_scheduler::{ManualClock, MonoTime, SchedulerConfig, TaskState};
    use std::sync::Arc;
    let mut manifest = project();
    manifest["capabilities"] = json!(["core.task", "vision.template", "screen.capture"]);
    manifest["resources"] = json!([
        {"kind":"image","path":"assets/images/source.png"},
        {"kind":"image","path":"assets/images/template.png"}
    ]);
    for (name, kind) in [
        ("scratch", "image"),
        ("result", "image"),
        ("found", "integer"),
        ("score", "integer"),
    ] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":kind,"scope":"global"}));
    }
    let bundle = compile(&manifest, &[
        ("screen.loadimage", json!({"imagePath":"assets/images/source.png","resultVariable":"scratch"})),
        ("screen.transform", json!({"frameVariable":"scratch","resultVariable":"result","mode":"gray","width":0,"height":0})),
        ("screen.release", json!({"frameVariable":"scratch"})),
        ("screen.transform", json!({"frameVariable":"result","resultVariable":"scratch","mode":"rotate90","width":0,"height":0})),
        ("screen.release", json!({"frameVariable":"result"})),
        ("screen.crop", json!({"frameVariable":"scratch","resultVariable":"result","left":0,"top":0,"right":1,"bottom":1})),
        ("screen.release", json!({"frameVariable":"scratch"})),
        ("vision.findimage", json!({"frameVariable":"result","imagePath":"assets/images/template.png","tolerance":0,"similarityPermille":1000,
            "region":{"left":0,"top":0,"right":1,"bottom":1},"direction":0,"autoCapture":false,"successAction":"none",
            "foundVariable":"found","xVariable":"w","yVariable":"h","scoreVariable":"score"})),
        ("screen.release", json!({"frameVariable":"result"})),
        ("task.message", json!({"expression":"text(found) .. \":\" .. text(w) .. \":\" .. text(h) .. \":\" .. text(score)","channel":"console"})),
    ]).unwrap();
    let lua = std::str::from_utf8(&bundle.main_lua).unwrap();
    assert!(!lua.contains("Screen.capture()"));
    assert!(!lua.contains("Input.tapScreen"));
    let mut runtime = RuntimeExecutor::new(
        ManualClock::new(MonoTime::ZERO),
        SchedulerConfig::default(),
        lua_runtime::LuaRuntimeConfig::default(),
        ExecutorConfig::default(),
        VirtualHost::new(2, 2),
    )
    .unwrap();
    for (path, width, height, bytes) in [
        (
            "assets/images/source.png",
            2,
            2,
            vec![
                10, 20, 30, 255, 50, 60, 70, 255, 100, 100, 100, 255, 200, 0, 0, 255,
            ],
        ),
        ("assets/images/template.png", 1, 1, vec![100, 100, 100, 255]),
    ] {
        runtime
            .frame_pool()
            .lock()
            .unwrap()
            .register_template(
                path,
                FrameMetadata {
                    width,
                    height,
                    row_stride: width * 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 0,
                    snapshot_id: 1,
                },
                Arc::from(bytes),
            )
            .unwrap();
    }
    runtime
        .set_allowed_capabilities(&[
            "core.task".into(),
            "vision.template".into(),
            "screen.capture".into(),
        ])
        .unwrap();
    let task = runtime
        .start_entry_chunk(&bundle.main_lua, "synthetic-processing-matching-tail")
        .unwrap();
    runtime.drive().unwrap();
    assert_eq!(
        runtime.task_state(task),
        Some(TaskState::Completed),
        "{:?}",
        runtime.task_failure(task)
    );
    assert_eq!(runtime.resources().resource_count(), 0);
    assert!(runtime
        .drain_script_logs()
        .iter()
        .any(|line| line.contains("1:0:0:1000")));
}

#[test]
fn processed_gray_and_ocr_tails_compile_borrowed_frames_with_explicit_release() {
    let mut manifest = project();
    manifest["capabilities"] = json!([
        "vision.template",
        "screen.capture",
        "vision.opencv",
        "ocr.onnx",
        "ocr.glyph"
    ]);
    manifest["resources"] = json!([
        {"kind":"image","path":"assets/images/source.png"},
        {"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}
    ]);
    for (name, kind) in [
        ("frame", "image"),
        ("found", "integer"),
        ("score", "integer"),
        ("coverage", "integer"),
    ] {
        manifest["variables"]
            .as_array_mut()
            .unwrap()
            .push(json!({"name":name,"type":kind,"scope":"global"}));
    }
    for (kind, args, expected) in [
        (
            "vision.findgray",
            json!({"frameVariable":"frame","imagePath":"assets/images/source.png","similarityPermille":900,
            "region":{"left":0,"top":0,"right":2,"bottom":2},"autoCapture":false,
            "foundVariable":"found","xVariable":"w","yVariable":"h","scoreVariable":"score"}),
            "Screen.findGray",
        ),
        (
            "ocr.alphanumeric",
            json!({"frameVariable":"frame","region":{"left":0,"top":0,"right":2,"bottom":2},
            "minimumConfidencePermille":500,"autoCapture":false,"textVariable":"s","scoreVariable":"score"}),
            "Ocr.alphanumeric",
        ),
        (
            "ocr.glyph",
            json!({"frameVariable":"frame","region":{"left":0,"top":0,"right":2,"bottom":2},
            "dictionaryPath":"dictionaries/main.asglyph","foregroundRgb":1_193_046,"tolerance":12,"similarityPermille":950,"spaceGapColumns":2,
            "textVariable":"s","scoreVariable":"score","coverageVariable":"coverage"}),
            "Ocr.glyph",
        ),
    ] {
        let bundle = compile(
            &manifest,
            &[
                (
                    "screen.loadimage",
                    json!({"imagePath":"assets/images/source.png","resultVariable":"frame"}),
                ),
                (kind, args),
                ("screen.release", json!({"frameVariable":"frame"})),
            ],
        )
        .unwrap();
        let lua = std::str::from_utf8(&bundle.main_lua).unwrap();
        assert!(lua.contains(expected));
        assert!(!lua.contains("Screen.capture()"));
        assert!(!lua.contains("Input.tapScreen"));
        if kind == "ocr.glyph" {
            assert!(lua.contains("Ocr.releaseDictionary(__dictionary)"));
        }
    }
}
