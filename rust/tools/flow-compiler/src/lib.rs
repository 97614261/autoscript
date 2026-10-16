mod analyze;
mod compound_condition;
#[cfg(test)]
mod data_completion_tests;
mod data_operations;
mod digest;
#[cfg(test)]
mod execution_tests;

#[cfg(test)]
mod cycle_contract_tests;
mod file_utility_specs;
mod floating_tools;
mod frozen_lua;
mod model;
#[cfg(test)]
mod native_vision_tests;
#[cfg(test)]
mod native_workflow_tests;
mod randomization;
mod render;
mod runtime_tools;
mod screen_resources;
mod script_controls;
mod store;
#[cfg(test)]
mod template_result_tests;
mod ui_dialog;
#[cfg(test)]
mod utility_operation_tests;
mod utility_operations;
mod variable_expression;
#[cfg(test)]
mod vision_completion_tests;
mod vision_extensions;
mod vision_requirements;

use analyze::prepare;
use digest::{content_digest, flow_digest, generation_id, verify_record};
use flow_ir::{ProjectManifest, ProjectSourceMode, SupportedNodeVersion};
use render::{pretty_json, render_lua, render_source_map};

pub use frozen_lua::{bundle_lua_project, popup_style_prefix};
pub use model::{
    CommitError, CompileError, FlowSource, GenerationBundle, GenerationRecord, SourceMap,
    SourceMapEntry, VerificationError, VisionComponent, VisionRequirements,
};
pub use store::{commit_generation, mark_generation_stale};

pub const GENERATOR_VERSION: &str = concat!(env!("CARGO_PKG_VERSION"), "-plugin-v14");
pub const SUPPORTED_NODE_VERSIONS: &[SupportedNodeVersion<'static>] = &[
    SupportedNodeVersion {
        kind: "input.pastetext",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "floating.show",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "floating.hide",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "floating.close",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "floating.bounds",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "clipboard.gettext",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "clipboard.settext",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "app.info",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "app.open",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.zip",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.unzip",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.getsize",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.getextension",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.directorystats",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.tobase64",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.frombase64",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.append",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.exists",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.isdir",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.mkdir",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.copy",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.move",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.rmdir",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.readline",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.insertline",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.deleteline",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.linecount",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.writeline",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.kvset",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.kvget",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.kvremove",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.kvkeys",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.kvadd",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.length",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.contains",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.startswith",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.endswith",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.count",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.split",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.join",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.left",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.right",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.sub",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.insert",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.remove",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.replace",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.replacefirst",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.repeat",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.between",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.before",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.after",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.trim",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.find",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.lower",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.upper",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.regexmatch",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.regexextract",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "text.regexall",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.valid",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.type",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.has",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.getjson",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "date.format",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "date.adddays",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "date.daysbetween",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.randint",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.randstr",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.round",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.clamp",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.inrange",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.angle",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.flipangle",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.joystickx",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.joysticky",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.red",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.green",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.blue",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "math.distance",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "system.elapsed",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "system.time",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "system.screensize",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.if",
        version: 2,
    },
    SupportedNodeVersion {
        kind: "vision.findallimages",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.result",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.continue",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.cycle",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.call.branch",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.status.return",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.length",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.get",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.set",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.remove",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.append",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.keys",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "data.text",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.read",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.write",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.list",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "files.remove",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.get",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.set",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.command",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.confirm",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.dialog",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ui.dialogclose",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.noop",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.comment",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.call",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.spawn",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.cancel",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "timer.every",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "timer.cancel",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.break",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.label",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.goto",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.return",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.argument.set",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.argument.get",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.return.set",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.return.get",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.sleep",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.log",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.prompt",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.message",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.runprompt",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.tap",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.pointerdown",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.pointermove",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.pointerup",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.swipe",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.keyevent",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.if",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.repeat",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.while",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.loopmetric",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "control.loopcheck",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "variable.set",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "variable.calculate",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "variable.copy",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.capture",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.crop",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.loadimage",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.transform",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.savepng",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findallmulticolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.components",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ocr.glyphdetails",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ocr.findtext",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.readresult",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.resultaction",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.series",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.seriesframe",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.seriesrelease",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.stop",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.restart",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.record",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.playrecording",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "audio.play",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "audio.stop",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "audio.getvolume",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "audio.setvolume",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.logenabled",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.promptenabled",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "screen.release",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.getcolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findcolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.comparecolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findmulticolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.countcolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findallcolor",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findimage",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findimage",
        version: 2,
    },
    SupportedNodeVersion {
        kind: "ocr.glyph",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "vision.findgray",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "ocr.alphanumeric",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "legacy.duodianzhaose",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "legacy.duodianbise",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "legacy.getrectcolornum",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "legacy.getrgbcolor",
        version: 1,
    },
];

/// Compiles validated project Flow sources to one deterministic Lua program and bound metadata.
///
/// # Errors
///
/// Returns all input/semantic errors found before rendering, or one deterministic rendering error.
pub fn compile_project(
    manifest: &ProjectManifest,
    sources: &[FlowSource<'_>],
) -> Result<GenerationBundle, Vec<CompileError>> {
    if manifest.source_mode != ProjectSourceMode::Visual {
        return Err(vec![CompileError::UnsupportedSourceMode]);
    }
    let entry_flow_id = manifest
        .entry_flow_id
        .as_deref()
        .ok_or_else(|| vec![CompileError::UnsupportedSourceMode])?;
    let prepared = prepare(manifest, sources)?;
    if randomization::enabled(&prepared)
        && !analyze::runtime_api_at_least(&manifest.runtime_api, 1, 8)
    {
        return Err(vec![CompileError::RuntimeApiRequirement {
            required: "1.8 (项目随机化)".to_owned(),
            current: manifest.runtime_api.clone(),
        }]);
    }
    let flow_digest = flow_digest(manifest, sources)
        .map_err(|error| vec![CompileError::Serialization(format!("{error:?}"))])?;
    let generation_id = generation_id(&flow_digest, GENERATOR_VERSION, &manifest.runtime_api);
    let (main_lua, entries) =
        render_lua(&prepared, entry_flow_id, &generation_id).map_err(|error| vec![error])?;
    lua_runtime::validate_text_chunk(&main_lua, "generated/main.lua")
        .map_err(|error| vec![CompileError::LuaSyntax(error.to_string())])?;
    let vision_requirements = vision_requirements::collect(&prepared, &entries);
    let source_map_json = render_source_map(&generation_id, entries, vision_requirements)
        .map_err(|error| vec![error])?;
    let record = GenerationRecord {
        generation_id,
        flow_digest,
        lua_digest: content_digest(&main_lua),
        source_map_digest: content_digest(&source_map_json),
        generator_version: GENERATOR_VERSION.to_owned(),
        runtime_api: manifest.runtime_api.clone(),
    };
    let generation_json = pretty_json(&record).map_err(|error| vec![error])?;
    Ok(GenerationBundle {
        main_lua,
        source_map_json,
        generation_json,
        record,
    })
}

/// Verifies exact Flow bytes and both generated files against the sole generation record.
///
/// # Errors
///
/// Returns a stable mismatch reason for stale/tampered source, Lua, source map, runtime API, or
/// generator version.
pub fn verify_generation(
    generation_json: &[u8],
    manifest: &ProjectManifest,
    sources: &[FlowSource<'_>],
    main_lua: &[u8],
    source_map_json: &[u8],
) -> Result<GenerationRecord, VerificationError> {
    let record: GenerationRecord = serde_json::from_slice(generation_json)
        .map_err(|error| VerificationError::InvalidGenerationRecord(error.to_string()))?;
    verify_record(
        &record,
        manifest,
        sources,
        main_lua,
        source_map_json,
        GENERATOR_VERSION,
    )?;
    let source_map: SourceMap = serde_json::from_slice(source_map_json)
        .map_err(|error| VerificationError::InvalidGenerationRecord(error.to_string()))?;
    let expected_header = format!("-- generationId: {}\n", record.generation_id);
    if source_map.generation_id != record.generation_id
        || !main_lua
            .windows(expected_header.len())
            .any(|window| window == expected_header.as_bytes())
    {
        return Err(VerificationError::GenerationRecordMismatch);
    }
    Ok(record)
}

#[cfg(test)]
mod tests {
    use super::{
        compile_project, verify_generation, CompileError, FlowSource, SourceMap,
        SUPPORTED_NODE_VERSIONS,
    };
    use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
    use serde_json::json;
    use std::path::Path;

    const MAIN: &str = concat!(
        r#"{"flowSchemaVersion":1,"nodeId":"node-call","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"flow.call","nodeVersion":1,"depth":0,"args":{"targetFlowId":"child","arguments":{"count":3}}}"#,
        "\n"
    );
    const CHILD: &str = concat!(
        r#"{"flowSchemaVersion":1,"nodeId":"node-noop","blockId":"block-child","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"#,
        "\n"
    );

    fn manifest() -> flow_ir::ProjectManifest {
        parse_project_manifest(
            br#"{"formatVersion":1,"flowSchemaVersion":1,"runtimeApi":"1.0","projectId":"project-1","name":"Compiler test","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null},{"flowId":"child","path":"visual/flows/child.jsonl","rootBlockId":"block-child","params":[{"name":"count","type":"integer","required":true}],"returns":null}],"capabilities":[],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid test manifest")
    }

    fn reports() -> (flow_ir::LoadReport, flow_ir::LoadReport) {
        (
            load_jsonl(
                MAIN.as_bytes(),
                LoadOptions {
                    flow_id: "main",
                    root_block_id: "block-main",
                    flow_schema_version: 1,
                    supported_nodes: SUPPORTED_NODE_VERSIONS,
                },
            ),
            load_jsonl(
                CHILD.as_bytes(),
                LoadOptions {
                    flow_id: "child",
                    root_block_id: "block-child",
                    flow_schema_version: 1,
                    supported_nodes: SUPPORTED_NODE_VERSIONS,
                },
            ),
        )
    }

    #[test]
    fn randomized_projects_require_api_18_and_old_defaults_remain_exact() {
        let mut manifest = manifest();
        let (main_report, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: MAIN.as_bytes(),
                report: &main_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let exact = compile_project(&manifest, &sources).unwrap();
        assert!(!String::from_utf8(exact.main_lua)
            .unwrap()
            .contains("__random_states"));
        manifest.randomization.touch.enabled = true;
        manifest.randomization.touch.x_px = 5;
        assert!(matches!(
            compile_project(&manifest, &sources).unwrap_err().as_slice(),
            [CompileError::RuntimeApiRequirement { .. }]
        ));
        manifest.runtime_api = "1.8".into();
        assert!(compile_project(&manifest, &sources).is_ok());
    }

    #[test]
    fn multi_flow_output_is_deterministic_and_verifiable() {
        let manifest = manifest();
        let (main_report, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: MAIN.as_bytes(),
                report: &main_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let first = compile_project(&manifest, &sources).expect("compiled project");
        let second = compile_project(&manifest, &sources).expect("same compiled project");
        assert_eq!(first, second);
        assert!(std::str::from_utf8(&first.main_lua)
            .expect("Lua UTF-8")
            .contains(r#"__flows["child"]((function() local __payload={["count"]=3}"#));
        let source_map: SourceMap =
            serde_json::from_slice(&first.source_map_json).expect("source map");
        assert_eq!(source_map.entries.len(), 2);
        assert_eq!(
            verify_generation(
                &first.generation_json,
                &manifest,
                &sources,
                &first.main_lua,
                &first.source_map_json,
            ),
            Ok(first.record)
        );
    }

    #[test]
    fn jump_labels_and_return_compile_but_missing_labels_fail() {
        let manifest = manifest();
        let main = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"jump","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"control.goto","nodeVersion":1,"depth":0,"args":{"name":"done"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"mark","blockId":"block-main","parentId":null,"orderKey":"a1","kind":"control.label","nodeVersion":1,"depth":0,"args":{"name":"done"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"exit","blockId":"block-main","parentId":null,"orderKey":"a2","kind":"flow.return","nodeVersion":1,"depth":0,"args":{}}"#,
            "\n"
        );
        let main_report = load_jsonl(
            main.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let (_, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: main.as_bytes(),
                report: &main_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let output = compile_project(&manifest, &sources).expect("valid root-level jump");
        let lua = String::from_utf8(output.main_lua).expect("Lua text");
        assert!(lua.contains("goto __jump_done"));
        assert!(lua.contains("::__jump_done::"));
        assert!(lua.contains("do return __checked_return() end"));
        let missing = main.replacen("\"name\":\"done\"", "\"name\":\"missing\"", 1);
        let missing_report = load_jsonl(
            missing.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bad = [
            FlowSource {
                flow_id: "main",
                exact_bytes: missing.as_bytes(),
                report: &missing_report,
            },
            sources[1],
        ];
        assert!(compile_project(&manifest, &bad).is_err());
        let outside_loop = main.replacen(
            "\"kind\":\"control.goto\",\"nodeVersion\":1,\"depth\":0,\"args\":{\"name\":\"done\"}",
            "\"kind\":\"control.break\",\"nodeVersion\":1,\"depth\":0,\"args\":{}",
            1,
        );
        let break_report = load_jsonl(
            outside_loop.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bad_break = [
            FlowSource {
                flow_id: "main",
                exact_bytes: outside_loop.as_bytes(),
                report: &break_report,
            },
            sources[1],
        ];
        assert!(compile_project(&manifest, &bad_break).is_err());
    }

    #[test]
    fn indexed_call_and_return_channels_compile_to_real_flow_state() {
        let mut manifest = manifest();
        manifest.capabilities.push("core.task".to_owned());
        let main = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"set","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"flow.argument.set","nodeVersion":1,"depth":0,"args":{"index":1,"value":9}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"call","blockId":"block-main","parentId":null,"orderKey":"a1","kind":"flow.call","nodeVersion":1,"depth":0,"args":{"targetFlowId":"child","arguments":{"count":3}}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"get","blockId":"block-main","parentId":null,"orderKey":"a2","kind":"flow.return.get","nodeVersion":1,"depth":0,"args":{"index":1,"targetVariable":"answer"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"get2","blockId":"block-main","parentId":null,"orderKey":"a25","kind":"flow.return.get","nodeVersion":1,"depth":0,"args":{"index":2,"targetVariable":"answer2"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"sleep","blockId":"block-main","parentId":null,"orderKey":"a3","kind":"task.sleep","nodeVersion":1,"depth":0,"args":{"milliseconds":0,"millisecondsVariable":"answer"}}"#,
            "\n"
        );
        let child = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"read","blockId":"block-child","parentId":null,"orderKey":"a0","kind":"flow.argument.get","nodeVersion":1,"depth":0,"args":{"index":1,"targetVariable":"received"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"ret","blockId":"block-child","parentId":null,"orderKey":"a1","kind":"flow.return.set","nodeVersion":1,"depth":0,"args":{"index":1,"valueVariable":"received"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"ret2","blockId":"block-child","parentId":null,"orderKey":"a2","kind":"flow.return.set","nodeVersion":1,"depth":0,"args":{"index":2,"value":true}}"#,
            "\n"
        );
        let main_report = load_jsonl(
            main.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let child_report = load_jsonl(
            child.as_bytes(),
            LoadOptions {
                flow_id: "child",
                root_block_id: "block-child",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: main.as_bytes(),
                report: &main_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: child.as_bytes(),
                report: &child_report,
            },
        ];
        let output = compile_project(&manifest, &sources).expect("call channels compile");
        let lua = String::from_utf8(output.main_lua).expect("Lua text");
        assert!(lua.contains("__call_args[1] = __channel_value(9)"));
        assert!(lua.contains("__payload[__i]=__v"));
        assert!(lua.contains("__vars[\"received\"] = __args[1]"));
        assert!(lua.contains("__return_values[1] = __channel_value(__vars[\"received\"])"));
        assert!(lua.contains("__vars[\"answer\"] = (__last_return_values or {})[1]"));
        assert!(lua.contains("__return_values[2] = __channel_value(true)"));
        assert!(lua.contains("__vars[\"answer2\"] = (__last_return_values or {})[2]"));
        assert!(lua.contains("  return nil\nend"));
        assert!(lua.contains("Task.sleep(__delay)"));
    }

    #[test]
    fn modified_flow_bytes_are_stale() {
        let manifest = manifest();
        let (main_report, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: MAIN.as_bytes(),
                report: &main_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let bundle = compile_project(&manifest, &sources).expect("compiled project");
        let modified = [
            FlowSource {
                flow_id: "main",
                exact_bytes: b"modified",
                report: &main_report,
            },
            sources[1],
        ];
        assert!(verify_generation(
            &bundle.generation_json,
            &manifest,
            &modified,
            &bundle.main_lua,
            &bundle.source_map_json,
        )
        .is_err());
    }

    #[test]
    fn argument_type_mismatch_blocks_compilation() {
        let manifest = manifest();
        let wrong = MAIN.replace("\"count\":3", "\"count\":\"three\"");
        let wrong_report = load_jsonl(
            wrong.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let (_, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: wrong.as_bytes(),
                report: &wrong_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let errors = compile_project(&manifest, &sources).expect_err("type mismatch must fail");
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::ArgumentTypeMismatch { argument, .. } if argument == "count"
        )));
    }

    #[test]
    fn recursive_flow_calls_are_rejected_by_default() {
        let manifest = manifest();
        let recursive = MAIN
            .replace("\"targetFlowId\":\"child\"", "\"targetFlowId\":\"main\"")
            .replace("\"arguments\":{\"count\":3}", "\"arguments\":{}");
        let recursive_report = load_jsonl(
            recursive.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let (_, child_report) = reports();
        let sources = [
            FlowSource {
                flow_id: "main",
                exact_bytes: recursive.as_bytes(),
                report: &recursive_report,
            },
            FlowSource {
                flow_id: "child",
                exact_bytes: CHILD.as_bytes(),
                report: &child_report,
            },
        ];
        let errors = compile_project(&manifest, &sources).expect_err("recursion must fail");
        assert!(errors.contains(&CompileError::RecursiveFlowCall));
    }

    #[test]
    fn automation_nodes_compile_to_runtime_api_calls() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.6","projectId":"project-automation","name":"Automation","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null}],"resources":[],"capabilities":["core.task","input.basic"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid automation manifest");
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"sleep","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"task.sleep","nodeVersion":1,"depth":0,"args":{"milliseconds":250}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"tap","blockId":"block-main","parentId":null,"orderKey":"b0","kind":"input.tap","nodeVersion":1,"depth":0,"args":{"x":12,"y":34}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"swipe","blockId":"block-main","parentId":null,"orderKey":"c0","kind":"input.swipe","nodeVersion":1,"depth":0,"args":{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":300}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"key","blockId":"block-main","parentId":null,"orderKey":"d0","kind":"input.keyevent","nodeVersion":1,"depth":0,"args":{"keyCode":4}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"pointer-down","blockId":"block-main","parentId":null,"orderKey":"e0","kind":"input.pointerdown","nodeVersion":1,"depth":0,"args":{"x":30,"y":40}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"hold","blockId":"block-main","parentId":null,"orderKey":"f1","kind":"task.sleep","nodeVersion":1,"depth":0,"args":{"milliseconds":450}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"pointer-move","blockId":"block-main","parentId":null,"orderKey":"f2","kind":"input.pointermove","nodeVersion":1,"depth":0,"args":{"x":50,"y":60}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"pointer-up","blockId":"block-main","parentId":null,"orderKey":"f3","kind":"input.pointerup","nodeVersion":1,"depth":0,"args":{}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"log","blockId":"block-main","parentId":null,"orderKey":"g0","kind":"task.log","nodeVersion":1,"depth":0,"args":{"level":"warn","message":"input complete"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"log-variable","blockId":"block-main","parentId":null,"orderKey":"h0","kind":"task.log","nodeVersion":1,"depth":0,"args":{"level":"info","message":"score","valueVariable":"score"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"prompt","blockId":"block-main","parentId":null,"orderKey":"i0","kind":"task.prompt","nodeVersion":1,"depth":0,"args":{"message":"正在执行","valueVariable":"status"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"run-prompt","blockId":"block-main","parentId":null,"orderKey":"i1","kind":"task.runprompt","nodeVersion":1,"depth":0,"args":{"message":"处理中","valueVariable":"status"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"comment","blockId":"block-main","parentId":null,"orderKey":"j0","kind":"task.comment","nodeVersion":1,"depth":0,"args":{"message":"下一步点击按钮"}}"#,
            "\n",
        );
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bundle = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect("automation nodes compile");
        let lua = std::str::from_utf8(&bundle.main_lua).expect("generated Lua is UTF-8");
        assert!(lua.contains("Task.sleep(250)"));
        assert!(lua.contains("Input.tap(12,34)"));
        assert!(lua.contains("Input.swipe(1,2,3,4,300)"));
        assert!(lua.contains("Input.keyEvent(4)"));
        assert!(lua.contains("Input.pointerDown(30,40)"));
        assert!(lua.contains("Input.pointerMove(50,60)"));
        assert!(lua.contains("Input.pointerUp()"));
        let down = lua.find("Input.pointerDown(30,40)").unwrap();
        let hold = lua.find("Task.sleep(450)").unwrap();
        let movement = lua.find("Input.pointerMove(50,60)").unwrap();
        let up = lua.find("Input.pointerUp()").unwrap();
        assert!(down < hold && hold < movement && movement < up);
        assert!(lua.contains("Log.warn(\"input complete\")"));
        assert!(lua.contains("Log.info(tostring(__vars[\"score\"]))"));
        assert!(lua.contains("Prompt.toast(tostring(__vars[\"status\"]))"));
        assert!(lua.contains("Prompt.show(tostring(__vars[\"status\"]))"));
        assert!(lua.contains("-- 下一步点击按钮"));
    }

    #[test]
    fn required_capability_and_argument_ranges_are_compiler_gates() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-gate","name":"Gate","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null}],"resources":[],"capabilities":[],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid gate manifest");
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"swipe","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"input.swipe","nodeVersion":1,"depth":0,"args":{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":0}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"pointer","blockId":"block-main","parentId":null,"orderKey":"b0","kind":"input.pointerdown","nodeVersion":1,"depth":0,"args":{"x":10,"y":20}}"#,
            "\n",
        );
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-main",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let errors = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect_err("missing capability and invalid duration must fail");
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::MissingCapability { node_id, capability }
                if node_id == "swipe" && capability == "input.basic"
        )));
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, .. } if node_id == "swipe"
        )));
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, reason }
                if node_id == "pointer" && reason.contains("runtimeApi 1.6")
        )));
    }

    #[test]
    fn studio_catalog_exactly_matches_compiler_node_support() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let blocks = api_codegen::load_block_catalog(&root.join("schema/block-catalog/blocks"))
            .expect("valid block catalog");
        let catalog = blocks
            .iter()
            .map(|block| (block.kind.as_str(), block.node_version))
            .collect::<Vec<_>>();
        let mut supported = SUPPORTED_NODE_VERSIONS
            .iter()
            .map(|node| (node.kind, node.version))
            .collect::<Vec<_>>();
        supported.sort_unstable();
        supported.retain(|entry| !supported_node_is_older(entry));
        assert_eq!(catalog, supported);
    }

    fn supported_node_is_older(entry: &(&str, u32)) -> bool {
        SUPPORTED_NODE_VERSIONS
            .iter()
            .any(|node| node.kind == entry.0 && node.version > entry.1)
    }

    #[test]
    fn nested_control_flow_and_variables_render_in_structural_order() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-control","name":"Control","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[],"capabilities":[],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid control manifest");
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"set","blockId":"root","parentId":null,"orderKey":"a0","kind":"variable.set","nodeVersion":1,"depth":0,"args":{"name":"score","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if","blockId":"root","parentId":null,"orderKey":"b0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"if-then","elseIf0":"if-else-if","else":"if-else"},"depth":0,"args":{"variable":"score","operator":"greaterOrEqual","value":7,"valueVariable":"targetScore","elseIf":[{"variable":"score","operator":"equals","value":3}]}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"repeat","blockId":"if-then","parentId":"if","orderKey":"a0","kind":"control.repeat","nodeVersion":1,"childBlocks":{"body":"repeat-body"},"depth":1,"args":{"times":2,"indexVariable":"index"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"copy","blockId":"repeat-body","parentId":"repeat","orderKey":"a0","kind":"variable.copy","nodeVersion":1,"depth":2,"args":{"name":"result","sourceName":"score"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"break","blockId":"repeat-body","parentId":"repeat","orderKey":"a1","kind":"control.break","nodeVersion":1,"depth":2,"args":{}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"fallback","blockId":"if-else","parentId":"if","orderKey":"a0","kind":"variable.set","nodeVersion":1,"depth":1,"args":{"name":"result","value":null}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"alternate","blockId":"if-else-if","parentId":"if","orderKey":"a0","kind":"variable.set","nodeVersion":1,"depth":1,"args":{"name":"result","value":"alternate"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"while","blockId":"root","parentId":null,"orderKey":"c0","kind":"control.while","nodeVersion":1,"childBlocks":{"body":"while-body"},"depth":0,"args":{"variable":"running","operator":"equals","value":true,"maxIterations":3}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"stop","blockId":"while-body","parentId":"while","orderKey":"a0","kind":"variable.set","nodeVersion":1,"depth":1,"args":{"name":"running","value":false}}"#,
            "\n",
        );
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bundle = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect("nested control Flow compiles");
        let lua = std::str::from_utf8(&bundle.main_lua).expect("generated Lua UTF-8");
        let set = lua.find("__vars[\"score\"] = 7").expect("set variable");
        let conditional = lua.find("if __condition then").expect("if statement");
        assert!(
            lua.contains(r#"__compare(__vars["score"],"greaterOrEqual", __vars["targetScore"])"#)
        );
        assert!(lua.contains("local __loop_times = 2"));
        assert!(lua.contains("do break end"));
        let repeat = lua
            .find("for __index = 1, __loop_times do")
            .expect("repeat loop");
        let copy = lua
            .find("__vars[\"result\"] = __vars[\"score\"]")
            .expect("copy variable");
        let otherwise = lua.find("__vars[\"result\"] = nil").expect("else branch");
        let else_if = lua
            .find("elseif __compare(__vars[\"score\"],\"equals\", 3) then")
            .expect("elseif branch");
        let alternate = lua
            .find("__vars[\"result\"] = \"alternate\"")
            .expect("elseif body");
        let while_loop = lua.find("while __compare").expect("while loop");
        assert!(set < conditional && conditional < repeat && repeat < copy);
        assert!(
            copy < else_if
                && else_if < alternate
                && alternate < otherwise
                && otherwise < while_loop
        );
        let source_map: SourceMap =
            serde_json::from_slice(&bundle.source_map_json).expect("source map");
        assert_eq!(
            source_map
                .entries
                .iter()
                .map(|entry| entry.node_id.as_str())
                .collect::<Vec<_>>(),
            [
                "set",
                "if",
                "repeat",
                "copy",
                "break",
                "alternate",
                "fallback",
                "while",
                "stop"
            ]
        );
    }

    #[test]
    fn visual_pipeline_compiles_to_real_runtime_calls_and_result_variables() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-vision","name":"Vision","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[{"kind":"image","path":"assets/images/button.png"},{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}],"capabilities":["screen.capture","vision.pixel","vision.template","ocr.glyph"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid visual manifest");
        let source = visual_pipeline_source();
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bundle = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect("visual pipeline compiles");
        let lua = std::str::from_utf8(&bundle.main_lua).expect("generated Lua UTF-8");
        assert!(lua.contains(r#"__vars["frame"] = Screen.cache(Screen.capture())"#));
        assert!(lua.contains(r#"Screen.getColor(__vars["frame"],10,20)"#));
        assert!(lua.contains(r#"Screen.findColor(__vars["frame"],16711935,8,0,0,720,1280)"#));
        assert!(lua.contains(
            r#"__vars["matched"] = Screen.compareColor(__vars["frame"],10,20,16711935,8)"#
        ));
        assert!(lua.contains(
            r#"Screen.findMultiColor(__vars["frame"],16711680,4,{{x=1,y=0,rgb=65280,tolerance=5},{x=-1,y=2,rgb=255,tolerance=6}},0,0,720,1280)"#
        ));
        assert!(lua.contains(
            r#"__vars["colorCount"] = Screen.countColor(__vars["frame"],16711935,8,0,0,720,1280,200)"#
        ));
        assert!(lua.contains(
            r#"__vars["colorPoints"] = Screen.findAllColor(__vars["frame"],16711935,8,0,0,720,1280,32)"#
        ));
        assert!(lua.contains(r#"Screen.loadImage("assets/images/button.png")"#));
        assert!(lua.contains(r#"Screen.findImage(__vars["frame"],__template,12,900,1,2,300,400)"#));
        assert!(lua.contains(r#"Ocr.loadDictionary("dictionaries/main.asglyph")"#));
        assert!(lua
            .contains(r#"Ocr.glyph(__vars["frame"],__dictionary,16777215,20,850,5,6,700,1200,3)"#));
        assert!(lua.contains(r#"__vars["ocrText"] = __result.text"#));
        assert!(lua.contains(r#"__vars["ocrCoverage"] = __result.coveragePermille"#));
        assert!(lua.contains(r#"__vars["ocrScore"] = __result.averageScorePermille"#));
        assert!(lua.contains(r#"Screen.release(__vars["frame"]); __vars["frame"] = nil"#));
        let source_map: SourceMap =
            serde_json::from_slice(&bundle.source_map_json).expect("source map");
        assert_eq!(source_map.entries.len(), 10);
        assert!(source_map
            .entries
            .iter()
            .all(|entry| entry.lua_start_line == entry.lua_end_line));
    }

    #[test]
    fn visual_resources_and_rectangles_are_hard_compiler_gates() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-vision-gate","name":"Vision gate","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}],"capabilities":["screen.capture","vision.pixel","vision.template","ocr.glyph"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid visual gate manifest");
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"image","blockId":"root","parentId":null,"orderKey":"a0","kind":"vision.findimage","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","imagePath":"assets/images/missing.png","tolerance":0,"similarityPermille":900,"region":{"left":10,"top":0,"right":10,"bottom":20},"foundVariable":"found","xVariable":"x","yVariable":"y"}}"#,
            "\n",
        );
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let errors = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect_err("unregistered image and empty rectangle must fail");
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, .. } if node_id == "image"
        )));
    }

    #[test]
    fn visual_capabilities_are_hard_compiler_gates() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-vision-capability","name":"Vision capability","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[],"capabilities":[],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid capability manifest");
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"capture","blockId":"root","parentId":null,"orderKey":"a0","kind":"screen.capture","nodeVersion":1,"depth":0,"args":{"resultVariable":"frame"}}"#,
            "\n",
        );
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let errors = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect_err("missing screen capability must fail");
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::MissingCapability { node_id, capability }
                if node_id == "capture" && capability == "screen.capture"
        )));
    }

    #[test]
    fn extended_pixel_nodes_enforce_sample_and_result_limits() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-extended-pixel-gate","name":"Pixel gate","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[],"capabilities":["vision.pixel"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid manifest");
        let samples = vec![json!({"x": 0, "y": 0, "rgb": 0, "tolerance": 0}); 65];
        let source = [
            json!({
                "flowSchemaVersion": 1, "nodeId": "too-many-samples", "blockId": "root",
                "parentId": null, "orderKey": "a0", "kind": "vision.findmulticolor",
                "nodeVersion": 1, "depth": 0,
                "args": {"frameVariable": "frame", "anchorRgb": 0, "anchorTolerance": 0,
                    "samples": samples, "region": {"left": 0, "top": 0, "right": 1, "bottom": 1},
                    "foundVariable": "found", "xVariable": "x", "yVariable": "y"}
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "zero-limit", "blockId": "root",
                "parentId": null, "orderKey": "b0", "kind": "vision.findallcolor",
                "nodeVersion": 1, "depth": 0,
                "args": {"frameVariable": "frame", "rgb": 0, "tolerance": 0,
                    "region": {"left": 0, "top": 0, "right": 1, "bottom": 1},
                    "limit": 0, "resultVariable": "points"}
            }),
        ]
        .into_iter()
        .map(|node| node.to_string())
        .collect::<Vec<_>>()
        .join("\n")
            + "\n";
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );

        let errors = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect_err("limits must fail");

        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, .. } if node_id == "too-many-samples"
        )));
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, .. } if node_id == "zero-limit"
        )));
    }

    #[test]
    fn legacy_nodes_render_structured_arguments_as_strict_legacy_text() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-legacy-blocks","name":"Legacy blocks","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[],"capabilities":["screen.capture","vision.pixel.legacy"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid manifest");
        let source = legacy_pipeline_source();
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let bundle = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect("legacy pipeline compiles");
        let lua = std::str::from_utf8(&bundle.main_lua).expect("generated Lua UTF-8");

        // The author edits structured JSON; the strict legacy grammar only appears here.
        assert!(lua.contains(
            r#"Legacy.duoDianZhaoSe(__vars["frame"],5,6,300,400,"(255,0,0)-(5,6,7)#(3,-2)|(0,255,0)-(1,2,3)",2,80)"#
        ));
        assert!(lua.contains(r#"__vars["legacyResult"] = __result"#));
        assert!(lua.contains(r#"__vars["legacyCount"] = __result.count"#));
        assert!(lua.contains(r#"__vars["legacyFound"] = __result.count > 0"#));
        assert!(lua.contains(
            r#"__vars["legacyMatch"] = Legacy.duoDianBiSe(__vars["frame"],"(10,20)|(0,0,255)-(4,5,6)#(30,40)|(255,255,255)-(0,0,0)",70)"#
        ));
        assert!(lua.contains(
            r#"__vars["legacyColorNum"] = Legacy.getRectColorNum(__vars["frame"],1,2,100,200,"(17,34,51)-(1,2,3)#(68,85,102)-(4,5,6)")"#
        ));
        assert!(lua.contains(r#"__vars["legacyRgb"] = Legacy.getRgbColor(17,34,51)"#));
    }

    #[test]
    fn legacy_multi_color_anchor_must_have_zero_offset() {
        let manifest = parse_project_manifest(
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-legacy-anchor","name":"Legacy anchor","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[],"capabilities":["vision.pixel.legacy"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("valid manifest");
        let source = json!({
            "flowSchemaVersion": 1, "nodeId": "moved-anchor", "blockId": "root",
            "parentId": null, "orderKey": "a0", "kind": "legacy.duodianzhaose",
            "nodeVersion": 1, "depth": 0,
            "args": {
                "frameVariable": "frame",
                "region": {"left": 0, "top": 0, "width": 10, "height": 10},
                "pattern": [{"dx": 1, "dy": 0, "rgb": 0, "toleranceRed": 0, "toleranceGreen": 0, "toleranceBlue": 0}],
                "direction": 1, "minimumMatchPercent": 100,
                "resultVariable": "result",
                "countVariable": "c", "foundVariable": "f", "xVariable": "x", "yVariable": "y"
            }
        })
        .to_string()
            + "\n";
        let report = load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED_NODE_VERSIONS,
            },
        );
        let errors = compile_project(
            &manifest,
            &[FlowSource {
                flow_id: "main",
                exact_bytes: source.as_bytes(),
                report: &report,
            }],
        )
        .expect_err("a non-zero anchor offset cannot be expressed in legacy text");
        assert!(errors.iter().any(|error| matches!(
            error,
            CompileError::InvalidNodeArguments { node_id, .. } if node_id == "moved-anchor"
        )));
    }

    fn legacy_pipeline_source() -> String {
        [
            json!({
                "flowSchemaVersion": 1, "nodeId": "capture", "blockId": "root",
                "parentId": null, "orderKey": "a0", "kind": "screen.capture",
                "nodeVersion": 1, "depth": 0, "args": {"resultVariable": "frame"}
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "legacy-multi", "blockId": "root",
                "parentId": null, "orderKey": "b0", "kind": "legacy.duodianzhaose",
                "nodeVersion": 1, "depth": 0,
                "args": {
                    "frameVariable": "frame",
                    "region": {"left": 5, "top": 6, "width": 300, "height": 400},
                    "pattern": [
                        {"dx": 0, "dy": 0, "rgb": 16_711_680, "toleranceRed": 5, "toleranceGreen": 6, "toleranceBlue": 7},
                        {"dx": 3, "dy": -2, "rgb": 65_280, "toleranceRed": 1, "toleranceGreen": 2, "toleranceBlue": 3}
                    ],
                    "direction": 2, "minimumMatchPercent": 80,
                    "resultVariable": "legacyResult",
                    "countVariable": "legacyCount", "foundVariable": "legacyFound",
                    "xVariable": "legacyX", "yVariable": "legacyY"
                }
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "legacy-compare", "blockId": "root",
                "parentId": null, "orderKey": "c0", "kind": "legacy.duodianbise",
                "nodeVersion": 1, "depth": 0,
                "args": {
                    "frameVariable": "frame",
                    "pattern": [
                        {"x": 10, "y": 20, "rgb": 255, "toleranceRed": 4, "toleranceGreen": 5, "toleranceBlue": 6},
                        {"x": 30, "y": 40, "rgb": 16_777_215, "toleranceRed": 0, "toleranceGreen": 0, "toleranceBlue": 0}
                    ],
                    "minimumMatchPercent": 70, "resultVariable": "legacyMatch"
                }
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "legacy-count", "blockId": "root",
                "parentId": null, "orderKey": "d0", "kind": "legacy.getrectcolornum",
                "nodeVersion": 1, "depth": 0,
                "args": {
                    "frameVariable": "frame",
                    "region": {"left": 1, "top": 2, "width": 100, "height": 200},
                    "colors": [
                        {"rgb": 1_122_867, "toleranceRed": 1, "toleranceGreen": 2, "toleranceBlue": 3},
                        {"rgb": 4_478_310, "toleranceRed": 4, "toleranceGreen": 5, "toleranceBlue": 6}
                    ],
                    "resultVariable": "legacyColorNum"
                }
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "legacy-rgb", "blockId": "root",
                "parentId": null, "orderKey": "e0", "kind": "legacy.getrgbcolor",
                "nodeVersion": 1, "depth": 0,
                "args": {"red": 17, "green": 34, "blue": 51, "resultVariable": "legacyRgb"}
            }),
            json!({
                "flowSchemaVersion": 1, "nodeId": "release", "blockId": "root",
                "parentId": null, "orderKey": "f0", "kind": "screen.release",
                "nodeVersion": 1, "depth": 0, "args": {"frameVariable": "frame"}
            }),
        ]
        .into_iter()
        .map(|node| node.to_string())
        .collect::<Vec<_>>()
        .join("\n")
            + "\n"
    }

    fn visual_pipeline_source() -> String {
        concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"capture","blockId":"root","parentId":null,"orderKey":"a0","kind":"screen.capture","nodeVersion":1,"depth":0,"args":{"resultVariable":"frame"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"color","blockId":"root","parentId":null,"orderKey":"b0","kind":"vision.getcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","point":{"x":10,"y":20},"resultVariable":"pixel"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-color","blockId":"root","parentId":null,"orderKey":"c0","kind":"vision.findcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":16711935,"tolerance":8,"region":{"left":0,"top":0,"right":720,"bottom":1280},"foundVariable":"colorFound","xVariable":"colorX","yVariable":"colorY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"compare-color","blockId":"root","parentId":null,"orderKey":"d0","kind":"vision.comparecolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","point":{"x":10,"y":20},"rgb":16711935,"tolerance":8,"resultVariable":"matched"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-multi","blockId":"root","parentId":null,"orderKey":"e0","kind":"vision.findmulticolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","anchorRgb":16711680,"anchorTolerance":4,"samples":[{"x":1,"y":0,"rgb":65280,"tolerance":5},{"x":-1,"y":2,"rgb":255,"tolerance":6}],"region":{"left":0,"top":0,"right":720,"bottom":1280},"foundVariable":"multiFound","xVariable":"multiX","yVariable":"multiY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"count-color","blockId":"root","parentId":null,"orderKey":"f0","kind":"vision.countcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":16711935,"tolerance":8,"region":{"left":0,"top":0,"right":720,"bottom":1280},"limit":200,"resultVariable":"colorCount"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-all","blockId":"root","parentId":null,"orderKey":"g0","kind":"vision.findallcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":16711935,"tolerance":8,"region":{"left":0,"top":0,"right":720,"bottom":1280},"limit":32,"resultVariable":"colorPoints"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-image","blockId":"root","parentId":null,"orderKey":"h0","kind":"vision.findimage","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","imagePath":"assets/images/button.png","tolerance":12,"similarityPermille":900,"region":{"left":1,"top":2,"right":300,"bottom":400},"foundVariable":"imageFound","xVariable":"imageX","yVariable":"imageY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"ocr","blockId":"root","parentId":null,"orderKey":"i0","kind":"ocr.glyph","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","dictionaryPath":"dictionaries/main.asglyph","foregroundRgb":16777215,"tolerance":20,"similarityPermille":850,"region":{"left":5,"top":6,"right":700,"bottom":1200},"spaceGapColumns":3,"textVariable":"ocrText","coverageVariable":"ocrCoverage","scoreVariable":"ocrScore"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"release","blockId":"root","parentId":null,"orderKey":"j0","kind":"screen.release","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame"}}"#,
            "\n",
        )
        .to_owned()
    }
}
