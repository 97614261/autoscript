mod analyze;
mod digest;
mod model;
mod render;
mod store;

use analyze::prepare;
use digest::{content_digest, flow_digest, generation_id, verify_record};
use flow_ir::{ProjectManifest, ProjectSourceMode, SupportedNodeVersion};
use render::{pretty_json, render_lua, render_source_map};

pub use model::{
    CommitError, CompileError, FlowSource, GenerationBundle, GenerationRecord, SourceMap,
    SourceMapEntry, VerificationError,
};
pub use store::{commit_generation, mark_generation_stale};

pub const GENERATOR_VERSION: &str = env!("CARGO_PKG_VERSION");
pub const SUPPORTED_NODE_VERSIONS: &[SupportedNodeVersion<'static>] = &[
    SupportedNodeVersion {
        kind: "task.noop",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "flow.call",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "task.sleep",
        version: 1,
    },
    SupportedNodeVersion {
        kind: "input.tap",
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
        kind: "variable.set",
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
        kind: "ocr.glyph",
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
    let flow_digest = flow_digest(manifest, sources)
        .map_err(|error| vec![CompileError::Serialization(format!("{error:?}"))])?;
    let generation_id = generation_id(&flow_digest, GENERATOR_VERSION, &manifest.runtime_api);
    let (main_lua, entries) =
        render_lua(&prepared, entry_flow_id, &generation_id).map_err(|error| vec![error])?;
    lua_runtime::validate_text_chunk(&main_lua, "generated/main.lua")
        .map_err(|error| vec![CompileError::LuaSyntax(error.to_string())])?;
    let source_map_json =
        render_source_map(&generation_id, entries).map_err(|error| vec![error])?;
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
            .contains(r#"__flows["child"]({["count"]=3})"#));
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
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-automation","name":"Automation","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null}],"resources":[],"capabilities":["core.task","input.basic"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
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
    }

    #[test]
    fn studio_catalog_exactly_matches_compiler_node_support() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
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
        assert_eq!(catalog, supported);
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
            r#"{"flowSchemaVersion":1,"nodeId":"if","blockId":"root","parentId":null,"orderKey":"b0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"if-then","else":"if-else"},"depth":0,"args":{"variable":"score","operator":"greaterOrEqual","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"repeat","blockId":"if-then","parentId":"if","orderKey":"a0","kind":"control.repeat","nodeVersion":1,"childBlocks":{"body":"repeat-body"},"depth":1,"args":{"times":2,"indexVariable":"index"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"copy","blockId":"repeat-body","parentId":"repeat","orderKey":"a0","kind":"variable.copy","nodeVersion":1,"depth":2,"args":{"name":"result","sourceName":"score"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"fallback","blockId":"if-else","parentId":"if","orderKey":"a0","kind":"variable.set","nodeVersion":1,"depth":1,"args":{"name":"result","value":null}}"#,
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
        let conditional = lua.find("if __compare").expect("if statement");
        let repeat = lua.find("for __index = 1, 2 do").expect("repeat loop");
        let copy = lua
            .find("__vars[\"result\"] = __vars[\"score\"]")
            .expect("copy variable");
        let otherwise = lua.find("__vars[\"result\"] = nil").expect("else branch");
        let while_loop = lua.find("while __compare").expect("while loop");
        assert!(set < conditional && conditional < repeat && repeat < copy);
        assert!(copy < otherwise && otherwise < while_loop);
        let source_map: SourceMap =
            serde_json::from_slice(&bundle.source_map_json).expect("source map");
        assert_eq!(
            source_map
                .entries
                .iter()
                .map(|entry| entry.node_id.as_str())
                .collect::<Vec<_>>(),
            ["set", "if", "repeat", "copy", "fallback", "while", "stop"]
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
