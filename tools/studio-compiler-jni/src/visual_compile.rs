//! Studio-owned project compilation and bounded draft validation.
use flow_compiler::{commit_generation, compile_project, CompileError, FlowSource};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde::Serialize;
use std::fs;
use std::path::Path;

const MAX_MANIFEST_BYTES: u64 = 1024 * 1024;
const MAX_FLOW_BYTES: u64 = 64 * 1024 * 1024;
const MAX_PROJECT_FLOWS: usize = 256;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BridgeReply {
    status: &'static str,
    generation_id: Option<String>,
    code: Option<String>,
    diagnostic: Option<String>,
    flow_id: Option<String>,
    node_id: Option<String>,
    line: Option<usize>,
}

struct OwnedFlow {
    flow_id: String,
    exact_bytes: Vec<u8>,
    report: flow_ir::LoadReport,
}

pub(crate) fn compile_project_directory(directory: &Path) -> String {
    compile(directory, None, true).unwrap_or_else(|reply| encode_reply(&reply))
}

pub(crate) fn validate_project_draft(directory: &Path, flow_id: &str, draft: &[u8]) -> String {
    if draft.len() > usize::try_from(MAX_FLOW_BYTES).expect("Flow limit fits usize") {
        return encode_reply(&failure("RESOURCE_LIMIT", "Flow草稿超过64 MiB"));
    }
    compile(directory, Some((flow_id, draft)), false).unwrap_or_else(|reply| encode_reply(&reply))
}

fn compile(
    directory: &Path,
    draft: Option<(&str, &[u8])>,
    commit: bool,
) -> Result<String, Box<BridgeReply>> {
    let root = directory
        .canonicalize()
        .map_err(|error| failure("PROJECT_DIRECTORY", format!("项目目录不可用：{error}")))?;
    if !root.is_dir() {
        return Err(failure("PROJECT_DIRECTORY", "项目路径不是目录"));
    }
    let manifest_path = root.join("project.json");
    let manifest_bytes = read_bounded(&manifest_path, MAX_MANIFEST_BYTES, "项目清单")?;
    let manifest = parse_project_manifest(&manifest_bytes)
        .map_err(|error| failure("PROJECT_MANIFEST", error.to_string()))?;
    if manifest.flows.len() > MAX_PROJECT_FLOWS {
        return Err(failure("TOO_MANY_FLOWS", "Flow数量超过256"));
    }
    if draft.is_some_and(|(flow_id, _)| !manifest.flows.iter().any(|flow| flow.flow_id == flow_id))
    {
        return Err(failure("UNKNOWN_FLOW", "草稿Flow不在项目清单中"));
    }

    let mut owned = Vec::with_capacity(manifest.flows.len());
    for declaration in &manifest.flows {
        let flow_path = root.join(&declaration.path);
        let canonical = flow_path.canonicalize().map_err(|error| {
            failure_for_flow(
                "FLOW_FILE",
                format!("Flow文件不可用：{error}"),
                &declaration.flow_id,
            )
        })?;
        if !canonical.starts_with(&root) || !canonical.is_file() {
            return Err(failure_for_flow(
                "FLOW_PATH_ESCAPE",
                "Flow路径越出项目目录",
                &declaration.flow_id,
            ));
        }
        let exact_bytes = draft
            .filter(|(flow_id, _)| *flow_id == declaration.flow_id)
            .map_or_else(
                || read_bounded(&canonical, MAX_FLOW_BYTES, "Flow文件"),
                |(_, bytes)| Ok(bytes.to_vec()),
            )?;
        let report = load_jsonl(
            &exact_bytes,
            LoadOptions {
                flow_id: &declaration.flow_id,
                root_block_id: &declaration.root_block_id,
                flow_schema_version: manifest.flow_schema_version,
                supported_nodes: flow_compiler::SUPPORTED_NODE_VERSIONS,
            },
        );
        owned.push(OwnedFlow {
            flow_id: declaration.flow_id.clone(),
            exact_bytes,
            report,
        });
    }
    let sources = owned
        .iter()
        .map(|flow| FlowSource {
            flow_id: &flow.flow_id,
            exact_bytes: &flow.exact_bytes,
            report: &flow.report,
        })
        .collect::<Vec<_>>();
    let bundle = compile_project(&manifest, &sources).map_err(|errors| {
        errors.first().map_or_else(
            || failure("COMPILE_FAILED", "可视化项目编译失败"),
            compile_failure,
        )
    })?;
    if commit {
        commit_generation(&root.join("generated"), &bundle)
            .map_err(|error| failure("COMMIT_FAILED", format!("生成物提交失败：{error:?}")))?;
    }
    Ok(encode_reply(&BridgeReply {
        status: "ok",
        generation_id: Some(bundle.record.generation_id),
        code: None,
        diagnostic: None,
        flow_id: None,
        node_id: None,
        line: None,
    }))
}

fn read_bounded(path: &Path, maximum: u64, label: &str) -> Result<Vec<u8>, Box<BridgeReply>> {
    let length = fs::metadata(path)
        .map_err(|error| failure("READ_FAILED", format!("{label}不可读：{error}")))?
        .len();
    if length > maximum {
        return Err(failure("RESOURCE_LIMIT", format!("{label}超过大小上限")));
    }
    fs::read(path).map_err(|error| failure("READ_FAILED", format!("{label}读取失败：{error}")))
}

fn compile_failure(error: &CompileError) -> Box<BridgeReply> {
    if let CompileError::FlowNotCompilable {
        flow_id,
        diagnostics,
    } = error
    {
        if let Some(diagnostic) = diagnostics.first() {
            return Box::new(BridgeReply {
                status: "error",
                generation_id: None,
                code: Some(format!("{:?}", diagnostic.code)),
                diagnostic: Some(diagnostic.message.clone()),
                flow_id: Some(flow_id.clone()),
                node_id: diagnostic.node_id.clone(),
                line: diagnostic.line,
            });
        }
    }
    let node_id = compile_error_node_id(error).map(ToOwned::to_owned);
    Box::new(BridgeReply {
        status: "error",
        generation_id: None,
        code: Some(compile_error_code(error).to_owned()),
        diagnostic: Some(format!("{error:?}")),
        flow_id: None,
        node_id,
        line: None,
    })
}

fn compile_error_node_id(error: &CompileError) -> Option<&str> {
    match error {
        CompileError::InvalidNodeArguments { node_id, .. }
        | CompileError::UnsupportedNodeKind { node_id, .. }
        | CompileError::MissingCapability { node_id, .. }
        | CompileError::UnknownTargetFlow { node_id, .. }
        | CompileError::MissingArgument { node_id, .. }
        | CompileError::UnknownArgument { node_id, .. }
        | CompileError::ArgumentTypeMismatch { node_id, .. } => Some(node_id),
        _ => None,
    }
}

fn compile_error_code(error: &CompileError) -> &'static str {
    match error {
        CompileError::UnsupportedSourceMode => "UNSUPPORTED_SOURCE_MODE",
        CompileError::MissingFlow(_) => "MISSING_FLOW",
        CompileError::UnexpectedFlow(_) => "UNEXPECTED_FLOW",
        CompileError::DuplicateFlowSource(_) => "DUPLICATE_FLOW_SOURCE",
        CompileError::SourceBytesMismatch(_) => "SOURCE_BYTES_MISMATCH",
        CompileError::FlowNotCompilable { .. } => "FLOW_NOT_COMPILABLE",
        CompileError::ProjectStructure(_) => "PROJECT_STRUCTURE",
        CompileError::InvalidNodeArguments { .. } => "INVALID_NODE_ARGUMENTS",
        CompileError::UnsupportedNodeKind { .. } => "UNSUPPORTED_NODE_KIND",
        CompileError::MissingCapability { .. } => "MISSING_CAPABILITY",
        CompileError::UnknownTargetFlow { .. } => "UNKNOWN_TARGET_FLOW",
        CompileError::MissingArgument { .. } => "MISSING_ARGUMENT",
        CompileError::UnknownArgument { .. } => "UNKNOWN_ARGUMENT",
        CompileError::ArgumentTypeMismatch { .. } => "ARGUMENT_TYPE_MISMATCH",
        CompileError::RecursiveFlowCall => "RECURSIVE_FLOW_CALL",
        CompileError::Serialization(_) => "SERIALIZATION",
        CompileError::LuaSyntax(_) => "LUA_SYNTAX",
    }
}

fn failure(code: &'static str, diagnostic: impl Into<String>) -> Box<BridgeReply> {
    Box::new(BridgeReply {
        status: "error",
        generation_id: None,
        code: Some(code.to_owned()),
        diagnostic: Some(diagnostic.into()),
        flow_id: None,
        node_id: None,
        line: None,
    })
}

fn failure_for_flow(
    code: &'static str,
    diagnostic: impl Into<String>,
    flow_id: &str,
) -> Box<BridgeReply> {
    let mut reply = failure(code, diagnostic);
    reply.flow_id = Some(flow_id.to_owned());
    reply
}

fn encode_reply(reply: &BridgeReply) -> String {
    serde_json::to_string(reply).unwrap_or_else(|_| {
        r#"{"status":"error","generationId":null,"code":"SERIALIZATION","diagnostic":"编译结果序列化失败","flowId":null,"nodeId":null,"line":null}"#.to_owned()
    })
}

pub(crate) fn internal_error_reply(message: &str) -> String {
    encode_reply(&failure("INTERNAL", message))
}

#[cfg(test)]
mod tests {
    use super::{compile_project_directory, validate_project_draft, MAX_FLOW_BYTES};
    use automation_core::{FrameFormat, FrameMetadata, FramePool, FramePoolConfig, Rotation};
    use engine_core::{EngineSession, EngineSessionConfig, EngineState};
    use glyph_ocr::{encode_dictionary, GlyphSource};
    use lua_runtime::LuaScalar;
    use runtime_executor::{ExternalHostEvent, ExternalHostQueue};
    use runtime_scheduler::{HostCompletion, HostResult, SchedulerEvent, TaskState};
    use serde_json::Value;
    use std::fs;
    use std::path::PathBuf;
    use std::sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    };
    use std::time::Duration;

    static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(1);
    const TEST_OP_INPUT_TAP: u32 = 4_000;
    const TEST_OP_SCREEN_CAPTURE: u32 = 5_000;

    fn project_directory() -> PathBuf {
        std::env::temp_dir().join(format!(
            "autoscript-visual-bridge-{}-{}",
            std::process::id(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
        ))
    }

    fn create_project(kind: &str) -> PathBuf {
        let root = project_directory();
        fs::create_dir_all(root.join("visual/flows")).expect("project directories");
        fs::write(
            root.join("project.json"),
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-1","name":"Visual","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"block-main","params":[],"returns":null}],"resources":[],"capabilities":["core.task"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("manifest");
        fs::write(
            root.join("visual/flows/main.jsonl"),
            format!(
                "{{\"flowSchemaVersion\":1,\"nodeId\":\"node-1\",\"blockId\":\"block-main\",\"parentId\":null,\"orderKey\":\"a0\",\"kind\":\"{kind}\",\"nodeVersion\":1,\"depth\":0,\"args\":{{}}}}\n"
            ),
        )
        .expect("Flow");
        root
    }

    fn create_visual_pipeline_project() -> PathBuf {
        let root = project_directory();
        fs::create_dir_all(root.join("visual/flows")).expect("project directories");
        fs::write(
            root.join("project.json"),
            br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-vision-e2e","name":"Vision E2E","sourceMode":"visual","entryFlowId":"main","flows":[{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],"resources":[{"kind":"image","path":"assets/images/target.png"},{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}],"capabilities":["screen.capture","vision.pixel","vision.pixel.legacy","vision.template","ocr.glyph","input.basic"],"design":{"width":8,"height":3,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#,
        )
        .expect("manifest");
        fs::write(
            root.join("visual/flows/main.jsonl"),
            visual_pipeline_e2e_source(),
        )
        .expect("Flow");
        root
    }

    fn visual_pipeline_e2e_source() -> String {
        concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"capture","blockId":"root","parentId":null,"orderKey":"a0","kind":"screen.capture","nodeVersion":1,"depth":0,"args":{"resultVariable":"frame"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"legacy-search","blockId":"root","parentId":null,"orderKey":"aa0","kind":"legacy.duodianzhaose","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","region":{"left":0,"top":0,"width":8,"height":3},"pattern":[{"dx":0,"dy":0,"rgb":660510,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0},{"dx":-1,"dy":0,"rgb":16777215,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0}],"direction":1,"minimumMatchPercent":100,"resultVariable":"legacyResult","countVariable":"legacyCount","foundVariable":"legacyFound","xVariable":"legacyX","yVariable":"legacyY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"legacy-compare","blockId":"root","parentId":null,"orderKey":"ab0","kind":"legacy.duodianbise","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","pattern":[{"x":7,"y":1,"rgb":660510,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0},{"x":6,"y":1,"rgb":16777215,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0}],"minimumMatchPercent":100,"resultVariable":"legacyMatch"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"legacy-count-colors","blockId":"root","parentId":null,"orderKey":"ac0","kind":"legacy.getrectcolornum","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","region":{"left":0,"top":0,"width":8,"height":3},"colors":[{"rgb":660510,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0},{"rgb":16777215,"toleranceRed":0,"toleranceGreen":0,"toleranceBlue":0}],"resultVariable":"legacyColorNum"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"legacy-rgb","blockId":"root","parentId":null,"orderKey":"ad0","kind":"legacy.getrgbcolor","nodeVersion":1,"depth":0,"args":{"red":10,"green":20,"blue":30,"resultVariable":"legacyRgb"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"get-color","blockId":"root","parentId":null,"orderKey":"b0","kind":"vision.getcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","point":{"x":7,"y":1},"resultVariable":"pixel"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-color","blockId":"root","parentId":null,"orderKey":"c0","kind":"vision.findcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":660510,"tolerance":0,"region":{"left":0,"top":0,"right":8,"bottom":3},"foundVariable":"colorFound","xVariable":"colorX","yVariable":"colorY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"compare-color","blockId":"root","parentId":null,"orderKey":"d0","kind":"vision.comparecolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","point":{"x":7,"y":1},"rgb":660510,"tolerance":0,"resultVariable":"colorMatched"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-multi","blockId":"root","parentId":null,"orderKey":"e0","kind":"vision.findmulticolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","anchorRgb":660510,"anchorTolerance":0,"samples":[{"x":-1,"y":0,"rgb":16777215,"tolerance":0}],"region":{"left":0,"top":0,"right":8,"bottom":3},"foundVariable":"multiFound","xVariable":"multiX","yVariable":"multiY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"count-color","blockId":"root","parentId":null,"orderKey":"f0","kind":"vision.countcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":16777215,"tolerance":0,"region":{"left":0,"top":0,"right":7,"bottom":3},"limit":256,"resultVariable":"whiteCount"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-all","blockId":"root","parentId":null,"orderKey":"g0","kind":"vision.findallcolor","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","rgb":16777215,"tolerance":0,"region":{"left":0,"top":0,"right":7,"bottom":3},"limit":9,"resultVariable":"whitePoints"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"find-image","blockId":"root","parentId":null,"orderKey":"h0","kind":"vision.findimage","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","imagePath":"assets/images/target.png","tolerance":0,"similarityPermille":1000,"region":{"left":0,"top":0,"right":8,"bottom":3},"foundVariable":"imageFound","xVariable":"imageX","yVariable":"imageY"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"ocr","blockId":"root","parentId":null,"orderKey":"i0","kind":"ocr.glyph","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame","dictionaryPath":"dictionaries/main.asglyph","foregroundRgb":16777215,"tolerance":0,"similarityPermille":1000,"region":{"left":0,"top":0,"right":7,"bottom":3},"spaceGapColumns":3,"textVariable":"ocrText","coverageVariable":"ocrCoverage","scoreVariable":"ocrScore"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"release","blockId":"root","parentId":null,"orderKey":"j0","kind":"screen.release","nodeVersion":1,"depth":0,"args":{"frameVariable":"frame"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-pixel","blockId":"root","parentId":null,"orderKey":"k0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"pixel-then","else":"pixel-else"},"depth":0,"args":{"variable":"pixel","operator":"equals","value":660510}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-color-found","blockId":"pixel-then","parentId":"if-pixel","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"color-found-then","else":"color-found-else"},"depth":1,"args":{"variable":"colorFound","operator":"equals","value":true}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-color-x","blockId":"color-found-then","parentId":"if-color-found","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"color-x-then","else":"color-x-else"},"depth":2,"args":{"variable":"colorX","operator":"equals","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-color-y","blockId":"color-x-then","parentId":"if-color-x","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"color-y-then","else":"color-y-else"},"depth":3,"args":{"variable":"colorY","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-color-matched","blockId":"color-y-then","parentId":"if-color-y","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"color-matched-then","else":"color-matched-else"},"depth":4,"args":{"variable":"colorMatched","operator":"equals","value":true}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-multi-found","blockId":"color-matched-then","parentId":"if-color-matched","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"multi-found-then","else":"multi-found-else"},"depth":5,"args":{"variable":"multiFound","operator":"equals","value":true}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-multi-x","blockId":"multi-found-then","parentId":"if-multi-found","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"multi-x-then","else":"multi-x-else"},"depth":6,"args":{"variable":"multiX","operator":"equals","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-multi-y","blockId":"multi-x-then","parentId":"if-multi-x","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"multi-y-then","else":"multi-y-else"},"depth":7,"args":{"variable":"multiY","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-white-count","blockId":"multi-y-then","parentId":"if-multi-y","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"white-count-then","else":"white-count-else"},"depth":8,"args":{"variable":"whiteCount","operator":"equals","value":9}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-image-found","blockId":"white-count-then","parentId":"if-white-count","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"image-found-then","else":"image-found-else"},"depth":9,"args":{"variable":"imageFound","operator":"equals","value":true}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-image-x","blockId":"image-found-then","parentId":"if-image-found","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"image-x-then","else":"image-x-else"},"depth":10,"args":{"variable":"imageX","operator":"equals","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-image-y","blockId":"image-x-then","parentId":"if-image-x","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"image-y-then","else":"image-y-else"},"depth":11,"args":{"variable":"imageY","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-ocr-text","blockId":"image-y-then","parentId":"if-image-y","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"ocr-text-then","else":"ocr-text-else"},"depth":12,"args":{"variable":"ocrText","operator":"equals","value":"A I"}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-ocr-coverage","blockId":"ocr-text-then","parentId":"if-ocr-text","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"ocr-coverage-then","else":"ocr-coverage-else"},"depth":13,"args":{"variable":"ocrCoverage","operator":"equals","value":1000}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-ocr-score","blockId":"ocr-coverage-then","parentId":"if-ocr-coverage","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"ocr-score-then","else":"ocr-score-else"},"depth":14,"args":{"variable":"ocrScore","operator":"equals","value":1000}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-count","blockId":"ocr-score-then","parentId":"if-ocr-score","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-count-then","else":"legacy-count-else"},"depth":15,"args":{"variable":"legacyCount","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-found","blockId":"legacy-count-then","parentId":"if-legacy-count","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-found-then","else":"legacy-found-else"},"depth":16,"args":{"variable":"legacyFound","operator":"equals","value":true}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-x","blockId":"legacy-found-then","parentId":"if-legacy-found","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-x-then","else":"legacy-x-else"},"depth":17,"args":{"variable":"legacyX","operator":"equals","value":7}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-y","blockId":"legacy-x-then","parentId":"if-legacy-x","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-y-then","else":"legacy-y-else"},"depth":18,"args":{"variable":"legacyY","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-match","blockId":"legacy-y-then","parentId":"if-legacy-y","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-match-then","else":"legacy-match-else"},"depth":19,"args":{"variable":"legacyMatch","operator":"equals","value":1}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-color-count","blockId":"legacy-match-then","parentId":"if-legacy-match","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-color-count-then","else":"legacy-color-count-else"},"depth":20,"args":{"variable":"legacyColorNum","operator":"equals","value":10}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"if-legacy-rgb","blockId":"legacy-color-count-then","parentId":"if-legacy-color-count","orderKey":"a0","kind":"control.if","nodeVersion":1,"childBlocks":{"then":"legacy-rgb-then","else":"legacy-rgb-else"},"depth":21,"args":{"variable":"legacyRgb","operator":"equals","value":660510}}"#,
            "\n",
            r#"{"flowSchemaVersion":1,"nodeId":"success-marker","blockId":"legacy-rgb-then","parentId":"if-legacy-rgb","orderKey":"a0","kind":"input.tap","nodeVersion":1,"depth":22,"args":{"x":1,"y":2}}"#,
            "\n",
        )
        .to_owned()
    }

    #[test]
    fn valid_project_is_compiled_and_committed() {
        let root = create_project("task.noop");

        let reply: Value = serde_json::from_str(&compile_project_directory(&root)).expect("reply");

        assert_eq!(reply["status"], "ok");
        assert!(reply["generationId"].as_str().is_some());
        assert!(root.join("generated/main.lua").is_file());
        assert!(root.join("generated/source-map.json").is_file());
        assert!(root.join("generated/generation.json").is_file());
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    #[allow(clippy::too_many_lines)] // One test intentionally shows the full compile/runtime seam.
    fn compiled_visual_project_executes_the_complete_runtime_pipeline() {
        let root = create_visual_pipeline_project();
        let reply: Value =
            serde_json::from_str(&compile_project_directory(&root)).expect("compile reply");
        assert_eq!(reply["status"], "ok");
        let generated = fs::read(root.join("generated/main.lua")).expect("generated Lua");

        let host_queue = ExternalHostQueue::new(4, 4).expect("host queue");
        let frames = Arc::new(Mutex::new(FramePool::new(FramePoolConfig::default())));
        let mut engine = EngineSession::new_external(
            8,
            3,
            EngineSessionConfig::default(),
            host_queue.clone(),
            Arc::clone(&frames),
        )
        .expect("engine");
        engine
            .register_template(
                "assets/images/target.png",
                FrameMetadata {
                    width: 1,
                    height: 1,
                    row_stride: 4,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 0,
                    snapshot_id: 1,
                },
                Arc::from([10, 20, 30, 255]),
            )
            .expect("template");
        let dictionary = encode_dictionary(&[
            GlyphSource {
                label: "A".to_owned(),
                width: 3,
                height: 3,
                packed_bits: vec![0b0101_0111, 0b1000_0000],
            },
            GlyphSource {
                label: "I".to_owned(),
                width: 1,
                height: 3,
                packed_bits: vec![0b1110_0000],
            },
        ])
        .expect("dictionary encoding");
        engine
            .register_dictionary("dictionaries/main.asglyph", &dictionary)
            .expect("dictionary");

        let mut pixels = vec![0_u8; 8 * 3 * 4];
        for (x, y) in [
            (1, 0),
            (0, 1),
            (2, 1),
            (0, 2),
            (1, 2),
            (2, 2),
            (6, 0),
            (6, 1),
            (6, 2),
        ] {
            let offset = (y * 8 + x) * 4;
            pixels[offset..offset + 4].copy_from_slice(&[255, 255, 255, 255]);
        }
        let target_offset = (8 + 7) * 4;
        pixels[target_offset..target_offset + 4].copy_from_slice(&[10, 20, 30, 255]);
        let capture = engine
            .publish_capture(
                FrameMetadata {
                    width: 8,
                    height: 3,
                    row_stride: 32,
                    pixel_stride: 4,
                    format: FrameFormat::Rgba8888,
                    rotation: Rotation::Degrees0,
                    timestamp_nanos: 1,
                    snapshot_id: 1,
                },
                &pixels,
            )
            .expect("capture");

        let capabilities = [
            "input.basic".to_owned(),
            "ocr.glyph".to_owned(),
            "screen.capture".to_owned(),
            "vision.pixel".to_owned(),
            "vision.pixel.legacy".to_owned(),
            "vision.template".to_owned(),
        ];
        let task = engine
            .start(&generated, "visual-e2e", &capabilities)
            .expect("start");
        engine.pump(0).expect("dispatch capture");
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = host_queue
            .wait_next_timeout(Duration::from_secs(1))
            .expect("capture request timeout")
        else {
            panic!("expected capture request")
        };
        assert_eq!(request.opcode, TEST_OP_SCREEN_CAPTURE);
        assert!(request.args.is_empty());
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(capture.0.to_le_bytes().to_vec()),
            })
            .expect("capture completion");

        engine.pump(1).expect("run visual operations");
        let ExternalHostEvent::Dispatch {
            request,
            completion,
        } = host_queue
            .wait_next_timeout(Duration::from_secs(1))
            .expect("success marker timeout")
        else {
            panic!("expected success marker")
        };
        assert_eq!(request.opcode, TEST_OP_INPUT_TAP);
        assert_eq!(request.args, [LuaScalar::Integer(1), LuaScalar::Integer(2)]);
        completion
            .submit_host_completion(HostCompletion {
                request_id: request.request_id,
                task: request.task,
                result: HostResult::Success(Vec::new()),
            })
            .expect("marker completion");

        let final_report = engine.pump(2).expect("finish");
        assert!(final_report.events.iter().any(|event| matches!(
            event,
            SchedulerEvent::TaskFinished {
                task: finished,
                state: TaskState::Completed,
            } if *finished == task
        )));
        assert_eq!(engine.state(), EngineState::Stopped);
        assert_eq!(engine.active_resource_count(), 0);
        let frames = frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        assert_eq!(frames.frame_count(), 1);
        assert_eq!(frames.template_count(), 1);
        assert_eq!(frames.series_count(), 0);

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn compiler_error_preserves_node_location() {
        let root = create_project("unknown.node");

        let reply: Value = serde_json::from_str(&compile_project_directory(&root)).expect("reply");

        assert_eq!(reply["status"], "error");
        assert_eq!(reply["flowId"], "main");
        assert_eq!(reply["nodeId"], "node-1");
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn valid_draft_is_checked_without_changing_source_or_generation() {
        let root = create_project("task.noop");
        let committed: Value =
            serde_json::from_str(&compile_project_directory(&root)).expect("compiled reply");
        let generation_path = root.join("generated/generation.json");
        let generation_before = fs::read(&generation_path).expect("generation");
        let flow_path = root.join("visual/flows/main.jsonl");
        let source_before = fs::read(&flow_path).expect("source");
        let draft = source_before.clone();

        let reply: Value = serde_json::from_str(&validate_project_draft(&root, "main", &draft))
            .expect("draft reply");

        assert_eq!(reply["status"], "ok");
        assert_eq!(reply["generationId"], committed["generationId"]);
        assert_eq!(fs::read(&flow_path).expect("source after"), source_before);
        assert_eq!(
            fs::read(&generation_path).expect("generation after"),
            generation_before
        );
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn invalid_draft_reports_location_and_preserves_formal_flow() {
        let root = create_project("task.noop");
        let flow_path = root.join("visual/flows/main.jsonl");
        let source_before = fs::read(&flow_path).expect("source");
        let draft = br#"{"flowSchemaVersion":1,"nodeId":"draft-node","blockId":"block-main","parentId":null,"orderKey":"a0","kind":"unknown.node","nodeVersion":1,"depth":0,"args":{}}
"#;

        let reply: Value =
            serde_json::from_str(&validate_project_draft(&root, "main", draft)).expect("reply");

        assert_eq!(reply["status"], "error");
        assert_eq!(reply["flowId"], "main");
        assert_eq!(reply["nodeId"], "draft-node");
        assert_eq!(reply["line"], 1);
        assert_eq!(fs::read(&flow_path).expect("source after"), source_before);
        assert!(!root.join("generated/generation.json").exists());
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn oversized_draft_is_rejected_before_project_access() {
        let draft = vec![0; usize::try_from(MAX_FLOW_BYTES).expect("limit") + 1];
        let reply: Value = serde_json::from_str(&validate_project_draft(
            PathBuf::from("missing-project").as_path(),
            "main",
            &draft,
        ))
        .expect("reply");

        assert_eq!(reply["status"], "error");
        assert_eq!(reply["code"], "RESOURCE_LIMIT");
    }
}
