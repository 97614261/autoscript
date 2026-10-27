//! Studio-owned project compilation and bounded draft validation.
use flow_compiler::{commit_generation, compile_project, CompileError, FlowSource};
use flow_ir::{load_jsonl, parse_project_manifest, LoadOptions};
use serde::Serialize;
use std::fs;
use std::io::Read;
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
    compile(directory, &[], true, None).unwrap_or_else(|reply| encode_reply(&reply))
}

pub(crate) fn compile_node_test(directory: &Path, flow: &str, node: &str) -> String {
    compile(directory, &[], true, Some((flow, node))).unwrap_or_else(|reply| encode_reply(&reply))
}

pub(crate) fn validate_project_draft(directory: &Path, flow_id: &str, draft: &[u8]) -> String {
    if draft.len() > usize::try_from(MAX_FLOW_BYTES).expect("Flow limit fits usize") {
        return encode_reply(&failure("RESOURCE_LIMIT", "Flow草稿超过64 MiB"));
    }
    compile(directory, &[(flow_id, draft)], false, None)
        .unwrap_or_else(|reply| encode_reply(&reply))
}

pub(crate) fn validate_project_draft_set(directory: &Path, bytes: &[u8]) -> String {
    match crate::draft_set::decode(bytes) {
        Ok(drafts) => {
            compile(directory, &drafts, false, None).unwrap_or_else(|reply| encode_reply(&reply))
        }
        Err(code) => encode_reply(&failure(code, "组合草稿格式无效或超过资源限制")),
    }
}

#[allow(clippy::too_many_lines)]
fn compile(
    directory: &Path,
    drafts: &[(&str, &[u8])],
    commit: bool,
    node_test: Option<(&str, &str)>,
) -> Result<String, Box<BridgeReply>> {
    let root = directory
        .canonicalize()
        .map_err(|error| failure("PROJECT_DIRECTORY", format!("项目目录不可用：{error}")))?;
    if !root.is_dir() {
        return Err(failure("PROJECT_DIRECTORY", "项目路径不是目录"));
    }
    let manifest_path = root.join("project.json");
    let manifest_bytes = read_bounded(&manifest_path, MAX_MANIFEST_BYTES, "项目清单")?;
    let mut manifest = parse_project_manifest(&manifest_bytes)
        .map_err(|error| failure("PROJECT_MANIFEST", error.to_string()))?;
    if manifest.flows.len() > MAX_PROJECT_FLOWS {
        return Err(failure("TOO_MANY_FLOWS", "Flow数量超过256"));
    }
    if drafts
        .iter()
        .any(|(flow_id, _)| !manifest.flows.iter().any(|flow| flow.flow_id == *flow_id))
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
        let exact_bytes = drafts
            .iter()
            .find(|(flow_id, _)| *flow_id == declaration.flow_id)
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
    if !commit
        && !drafts.is_empty()
        && manifest
            .runtime_api
            .split_once('.')
            .is_some_and(|(major, minor)| major == "1" && minor.parse::<u32>().is_ok_and(|v| v < 8))
        && sources.iter().any(draft_uses_api18)
    {
        manifest.runtime_api = "1.8".into();
    }
    let mut bundle = compile_project(&manifest, &sources).map_err(|errors| {
        errors.first().map_or_else(
            || failure("COMPILE_FAILED", "可视化项目编译失败"),
            compile_failure,
        )
    })?;
    drop(sources);
    let provenance = if let Some((flow_id, node_id)) = node_test {
        use sha2::{Digest, Sha256};
        let hash = |bytes: &[u8]| format!("{:x}", Sha256::digest(bytes));
        let provenance = serde_json::json!({"flowId":flow_id,"nodeId":node_id,
            "manifestDigest":hash(&manifest_bytes),"flowDigests":owned.iter().map(|flow|
                (flow.flow_id.clone(), hash(&flow.exact_bytes))).collect::<std::collections::BTreeMap<_,_>>()});
        let flow = owned
            .iter_mut()
            .find(|flow| flow.flow_id == flow_id)
            .ok_or_else(|| failure("UNKNOWN_FLOW", "测试插件不存在"))?;
        let document = flow
            .report
            .document
            .as_ref()
            .ok_or_else(|| failure("INVALID_FLOW", "测试插件无效"))?;
        let selected = document
            .nodes
            .iter()
            .find(|node| node.envelope.node_id == node_id)
            .ok_or_else(|| failure("UNKNOWN_NODE", "测试积木不存在"))?;
        if document.disabled_node_ids().contains(node_id) {
            return Err(failure("DISABLED_NODE", "注释积木不能测试"));
        }
        let mut included = std::collections::HashSet::from([node_id]);
        loop {
            let before = included.len();
            for node in &document.nodes {
                if node
                    .envelope
                    .parent_id
                    .as_deref()
                    .is_some_and(|parent| included.contains(parent))
                {
                    included.insert(&node.envelope.node_id);
                }
            }
            if included.len() == before {
                break;
            }
        }
        let root_block = manifest
            .flows
            .iter()
            .find(|f| f.flow_id == flow_id)
            .expect("validated flow")
            .root_block_id
            .clone();
        let base_depth = selected.envelope.depth.unwrap_or(0);
        let mut isolated = Vec::new();
        for node in document
            .nodes
            .iter()
            .filter(|node| included.contains(node.envelope.node_id.as_str()))
        {
            let mut envelope = node.envelope.clone();
            if envelope.node_id == node_id {
                envelope.parent_id = None;
                envelope.block_id.clone_from(&root_block);
                envelope.flow_schema_version = Some(manifest.flow_schema_version);
            }
            envelope.depth = envelope.depth.map(|depth| depth.saturating_sub(base_depth));
            serde_json::to_writer(&mut isolated, &envelope)
                .map_err(|_| failure("SERIALIZATION", "测试结构编码失败"))?;
            isolated.push(b'\n');
        }
        flow.exact_bytes = isolated;
        flow.report = load_jsonl(
            &flow.exact_bytes,
            LoadOptions {
                flow_id,
                root_block_id: &root_block,
                flow_schema_version: manifest.flow_schema_version,
                supported_nodes: flow_compiler::SUPPORTED_NODE_VERSIONS,
            },
        );
        manifest.entry_flow_id = Some(flow_id.to_owned());
        let sources = owned
            .iter()
            .map(|flow| FlowSource {
                flow_id: &flow.flow_id,
                exact_bytes: &flow.exact_bytes,
                report: &flow.report,
            })
            .collect::<Vec<_>>();
        bundle = compile_project(&manifest, &sources).map_err(|errors| {
            errors.first().map_or_else(
                || {
                    failure(
                        "TEST_CONTEXT",
                        "积木依赖外层上下文，请选择包含初始化和控制结构的父级",
                    )
                },
                compile_failure,
            )
        })?;
        Some(provenance)
    } else {
        None
    };
    if commit {
        let output = root.join(if node_test.is_some() {
            "generated-node-test"
        } else {
            "generated"
        });
        commit_generation(&output, &bundle)
            .map_err(|error| failure("COMMIT_FAILED", format!("生成物提交失败：{error:?}")))?;
        if let Some(provenance) = provenance {
            let bytes = serde_json::to_vec(&provenance)
                .map_err(|_| failure("SERIALIZATION", "测试上下文编码失败"))?;
            fs::write(output.join("test-source.json"), bytes)
                .map_err(|_| failure("COMMIT_FAILED", "测试上下文保存失败"))?;
        }
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
    let mut bytes = Vec::new();
    fs::File::open(path)
        .and_then(|file| file.take(maximum + 1).read_to_end(&mut bytes))
        .map_err(|error| failure("READ_FAILED", format!("{label}读取失败：{error}")))?;
    if u64::try_from(bytes.len()).map_err(|_| failure("RESOURCE_LIMIT", "文件大小无效"))? > maximum
    {
        return Err(failure("RESOURCE_LIMIT", format!("{label}超过大小上限")));
    }
    Ok(bytes)
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
        CompileError::RuntimeApiRequirement { .. } => "RUNTIME_API_REQUIREMENT",
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

fn draft_uses_api18(source: &FlowSource<'_>) -> bool {
    source.report.document.as_ref().is_some_and(|document| {
        document.nodes.iter().any(|node| {
            node.envelope.randomization.is_some()
                || matches!(node.envelope.kind.as_str(), "screen.frameinfo" | "screen.captureregion" | "text.numberat")
                || node.envelope.kind == "ocr.alphanumeric"
                    && node.envelope.args.get("includeSymbols").and_then(serde_json::Value::as_bool) == Some(true)
                || [
                    "text.urlencode",
                    "text.urldecode",
                    "text.tohex",
                    "text.fromhex",
                    "text.tobase64",
                    "text.frombase64",
                    "text.findnth",
                    "text.betweennth",
                    "path.name",
                    "path.parent",
                    "path.normalize",
                    "path.join",
                ]
                .contains(&node.envelope.kind.as_str())
                || node.envelope.kind == "ui.command"
                    && node
                        .envelope
                        .args
                        .get("operation")
                        .and_then(serde_json::Value::as_str)
                        .is_some_and(|op| matches!(op, "get:pages" | "get:controls"))
                || [
                    "files.getsize",
                    "files.getextension",
                    "files.directorystats",
                    "files.tobase64",
                    "files.frombase64",
                    "files.zip",
                    "files.unzip",
                    "files.modifiedtime",
                    "system.info",
                    "system.keepawake",
                    "app.openurl",
                    "network.request",
                    "input.imestatus",
                    "input.imetext",
                    "input.touchdown",
                    "input.touchmove",
                    "input.touchup",
                    "floating.image",
                    "floating.waitevent",
                    "floating.closeimage",
                    "vision.findfeatures",
                    "ocr.alphanumericv5",
                    "clipboard.gettext",
                    "input.pastetext",
                    "clipboard.settext",
                    "app.info",
                    "app.open",
                    "floating.show",
                    "floating.hide",
                    "floating.close",
                    "floating.bounds",
                ]
                .contains(&node.envelope.kind.as_str())
        })
    })
}

pub(crate) fn internal_error_reply(message: &str) -> String {
    encode_reply(&failure("INTERNAL", message))
}

#[cfg(test)]
mod tests {
    use super::{
        compile_node_test, compile_project_directory, validate_project_draft, MAX_FLOW_BYTES,
    };
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

    fn encode_draft_set(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut bytes = b"ZGDS".to_vec();
        bytes.extend(1_u32.to_be_bytes());
        bytes.extend(u32::try_from(entries.len()).unwrap().to_be_bytes());
        for (id, source) in entries {
            bytes.extend(u32::try_from(id.len()).unwrap().to_be_bytes());
            bytes.extend(id.as_bytes());
            bytes.extend(u32::try_from(source.len()).unwrap().to_be_bytes());
            bytes.extend(*source);
        }
        bytes
    }

    #[test]
    fn selected_container_keeps_children_and_external_loop_dependency_is_rejected() {
        let root = create_project("task.noop");
        let source = concat!(
            "{\"flowSchemaVersion\":1,\"nodeId\":\"loop\",\"blockId\":\"block-main\",\"parentId\":null,\"orderKey\":\"a0\",\"kind\":\"control.repeat\",\"nodeVersion\":1,\"depth\":0,\"childBlocks\":{\"body\":\"body\"},\"args\":{\"times\":2}}\n",
            "{\"nodeId\":\"child\",\"blockId\":\"body\",\"parentId\":\"loop\",\"orderKey\":\"a0\",\"kind\":\"task.log\",\"nodeVersion\":1,\"depth\":1,\"args\":{\"level\":\"info\",\"message\":\"child\"}}\n",
            "{\"nodeId\":\"break\",\"blockId\":\"body\",\"parentId\":\"loop\",\"orderKey\":\"b0\",\"kind\":\"control.break\",\"nodeVersion\":1,\"depth\":1,\"args\":{}}\n",
            "{\"nodeId\":\"after\",\"blockId\":\"block-main\",\"parentId\":null,\"orderKey\":\"b0\",\"kind\":\"task.log\",\"nodeVersion\":1,\"depth\":0,\"args\":{\"level\":\"info\",\"message\":\"after\"}}\n",
        );
        let path = root.join("visual/flows/main.jsonl");
        fs::write(&path, source).unwrap();
        let reply: Value = serde_json::from_str(&compile_node_test(&root, "main", "loop")).unwrap();
        assert_eq!(reply["status"], "ok", "{reply}");
        let lua = fs::read(root.join("generated-node-test/main.lua")).unwrap();
        let mut engine = EngineSession::new(720, 1280, EngineSessionConfig::default()).unwrap();
        engine
            .start(&lua, "loop-test", &["core.task".into()])
            .unwrap();
        engine.pump(1).unwrap();
        assert_eq!(engine.drain_script_logs(), ["脚本/INFO: child"]);
        let isolated: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "child")).unwrap();
        assert_eq!(isolated["status"], "ok", "{isolated}");
        let dependency: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "break")).unwrap();
        assert_eq!(dependency["status"], "error", "{dependency}");
        fs::write(
            &path,
            source.replace(
                "\"nodeId\":\"loop\"",
                "\"nodeId\":\"loop\",\"disabled\":true",
            ),
        )
        .unwrap();
        let disabled: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "child")).unwrap();
        assert_eq!(disabled["code"], "DISABLED_NODE");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn selected_test_executes_no_siblings_and_preserves_formal_generation() {
        use std::fmt::Write;
        let root = create_project("task.noop");
        let mut source = String::new();
        for (i, name) in ["before", "selected", "after"].iter().enumerate() {
            writeln!(source, "{{\"flowSchemaVersion\":1,\"nodeId\":\"{name}\",\"blockId\":\"block-main\",\"parentId\":null,\"orderKey\":\"{}0\",\"kind\":\"task.log\",\"nodeVersion\":1,\"depth\":0,\"args\":{{\"level\":\"info\",\"message\":\"{name}\"}}}}", char::from(b'a' + u8::try_from(i).unwrap())).unwrap();
        }
        let path = root.join("visual/flows/main.jsonl");
        fs::write(&path, &source).unwrap();
        let normal: Value = serde_json::from_str(&compile_project_directory(&root)).unwrap();
        assert_eq!(normal["status"], "ok", "{normal}");
        let formal = fs::read(root.join("generated/main.lua")).unwrap();
        let reply: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "selected")).unwrap();
        assert_eq!(reply["status"], "ok", "{reply}");
        let lua = fs::read_to_string(root.join("generated-node-test/main.lua")).unwrap();
        let mut engine = EngineSession::new(720, 1280, EngineSessionConfig::default()).unwrap();
        engine
            .start(lua.as_bytes(), "selected-test", &["core.task".into()])
            .unwrap();
        engine.pump(1).unwrap();
        assert_eq!(engine.state(), EngineState::Stopped);
        assert_eq!(engine.drain_script_logs(), ["脚本/INFO: selected"]);
        assert_eq!(fs::read(root.join("generated/main.lua")).unwrap(), formal);
        assert_eq!(fs::read_to_string(path).unwrap(), source);
        let provenance: Value = serde_json::from_slice(
            &fs::read(root.join("generated-node-test/test-source.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(provenance["nodeId"], "selected");
        let missing: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "missing")).unwrap();
        assert_eq!(missing["code"], "UNKNOWN_NODE");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn selected_prompt_preview_emits_timed_output_without_changing_formal_semantics() {
        let root = create_project("task.runprompt");
        let path = root.join("visual/flows/main.jsonl");
        let mut node: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        node["args"] = serde_json::json!({"message":"selected prompt"});
        fs::write(&path, serde_json::to_vec(&node).unwrap()).unwrap();
        let manifest_path = root.join("project.json");
        let mut manifest: Value =
            serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
        manifest["debugSettings"] = serde_json::json!({"popupStyle":{"widthPx":100,"heightPx":30,"durationMs":900,"fontPx":15}});
        fs::write(manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        let reply: Value =
            serde_json::from_str(&compile_node_test(&root, "main", "node-1")).unwrap();
        assert_eq!(reply["status"], "ok", "{reply}");
        let compiled = fs::read_to_string(root.join("generated-node-test/main.lua")).unwrap();
        // Mirrors Studio's frozen-plan wrapper; the compiler supplies the project call style.
        let preview = format!("Prompt.show = function(message, styleJson) return Prompt.toast(message, styleJson or '{{}}') end\n{compiled}");
        for (source, kind) in [(&compiled, "window"), (&preview, "toast")] {
            let mut engine = EngineSession::new(720, 1280, EngineSessionConfig::default()).unwrap();
            engine
                .start(source.as_bytes(), "selected-prompt", &["core.task".into()])
                .unwrap();
            engine.pump(1).unwrap();
            assert_eq!(engine.state(), EngineState::Stopped);
            let prompts = engine.drain_script_prompts();
            assert_eq!(prompts.len(), 1);
            let prompt: Value = serde_json::from_str(&prompts[0]).unwrap();
            assert_eq!(prompt["kind"], kind);
            assert_eq!(prompt["message"], "selected prompt");
            assert_eq!(prompt["style"]["widthPx"], 100);
            assert_eq!(prompt["style"]["heightPx"], 30);
            assert_eq!(prompt["style"]["durationMs"], 900);
        }
        assert_eq!(
            fs::read_to_string(root.join("generated-node-test/main.lua")).unwrap(),
            compiled
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn combined_drafts_reject_a_cycle_that_each_individual_draft_allows() {
        let root = create_project("task.noop");
        let manifest_path = root.join("project.json");
        let mut manifest: Value =
            serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
        manifest["flows"].as_array_mut().unwrap().push(serde_json::json!({
            "flowId":"other","path":"visual/flows/other.jsonl","rootBlockId":"block-other","params":[],"returns":null
        }));
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        let main_path = root.join("visual/flows/main.jsonl");
        let other_path = root.join("visual/flows/other.jsonl");
        let main_before = fs::read(&main_path).unwrap();
        let other_before = String::from_utf8(main_before.clone())
            .unwrap()
            .replace("node-1", "node-other")
            .replace("block-main", "block-other");
        fs::write(&other_path, &other_before).unwrap();
        let mut main: Value = serde_json::from_slice(&main_before).unwrap();
        main["kind"] = "flow.call".into();
        main["args"] = serde_json::json!({"targetFlowId":"other","arguments":{}});
        let mut other: Value = serde_json::from_str(&other_before).unwrap();
        other["kind"] = "flow.call".into();
        other["args"] = serde_json::json!({"targetFlowId":"main","arguments":{}});
        let main = serde_json::to_vec(&main).unwrap();
        let other = serde_json::to_vec(&other).unwrap();
        for (id, source) in [("main", &main), ("other", &other)] {
            let reply: Value = serde_json::from_str(&super::validate_project_draft_set(
                &root,
                &encode_draft_set(&[(id, source)]),
            ))
            .unwrap();
            assert_eq!(reply["status"], "ok");
        }
        let reply: Value = serde_json::from_str(&super::validate_project_draft_set(
            &root,
            &encode_draft_set(&[("main", &main), ("other", &other)]),
        ))
        .unwrap();
        assert_eq!(reply["code"], "RECURSIVE_FLOW_CALL");
        assert_eq!(fs::read(main_path).unwrap(), main_before);
        assert_eq!(fs::read(other_path).unwrap(), other_before.as_bytes());
        assert!(!root.join("generated").exists());
        fs::remove_dir_all(root).unwrap();
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
    fn new_api_draft_upgrade_is_in_memory_and_does_not_commit_old_project() {
        let root = create_project("task.noop");
        let manifest_path = root.join("project.json");
        let mut manifest: Value =
            serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
        manifest["capabilities"] = serde_json::json!(["core.task", "ui.floating"]);
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        let before = fs::read(&manifest_path).unwrap();
        let source_path = root.join("visual/flows/main.jsonl");
        let source_before = fs::read(&source_path).unwrap();
        let mut node: Value = serde_json::from_slice(&source_before).unwrap();
        node["kind"] = serde_json::json!("floating.hide");
        node["args"] = serde_json::json!({"name":"panel"});
        let draft = serde_json::to_vec(&node).unwrap();
        let reply: Value =
            serde_json::from_str(&validate_project_draft(&root, "main", &draft)).unwrap();
        assert_eq!(reply["status"], "ok", "{reply}");
        assert_eq!(fs::read(&manifest_path).unwrap(), before);
        assert_eq!(fs::read(&source_path).unwrap(), source_before);
        assert!(!root.join("generated").exists());
        manifest["capabilities"] = serde_json::json!(["core.task"]);
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        let reply: Value =
            serde_json::from_str(&validate_project_draft(&root, "main", &draft)).unwrap();
        assert_eq!(reply["status"], "error");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn text_and_symbol_drafts_upgrade_old_api_without_writing_project() {
        let root = create_project("task.noop");
        let manifest_path = root.join("project.json");
        let mut manifest: Value = serde_json::from_slice(&fs::read(&manifest_path).unwrap()).unwrap();
        manifest["runtimeApi"] = serde_json::json!("1.7");
        manifest["capabilities"] = serde_json::json!(["core.task", "screen.capture", "ocr.onnx"]);
        manifest["variables"] = serde_json::json!([
            {"name":"text","type":"string","scope":"global"},
            {"name":"score","type":"integer","scope":"global"},
            {"name":"frame","type":"image","scope":"global"}
        ]);
        fs::write(&manifest_path, serde_json::to_vec(&manifest).unwrap()).unwrap();
        let before = fs::read(&manifest_path).unwrap();
        let source_path = root.join("visual/flows/main.jsonl");
        let original = fs::read(&source_path).unwrap();
        for (kind, args) in [
            ("text.numberat", serde_json::json!({"text":"item -12.5 / +7", "index":2, "resultVariable":"text"})),
            ("ocr.alphanumeric", serde_json::json!({"frameVariable":"frame", "autoCapture":true,
                "textVariable":"text", "scoreVariable":"score", "minimumConfidencePermille":500,
                "region":{"left":0,"top":0,"right":100,"bottom":20}, "includeSymbols":true})),
            ("screen.captureregion", serde_json::json!({"resultVariable":"frame", "left":0,"top":0,"right":100,"bottom":20})),
        ] {
            let mut node: Value = serde_json::from_slice(&original).unwrap();
            node["kind"] = serde_json::json!(kind);
            node["args"] = args;
            let draft = serde_json::to_vec(&node).unwrap();
            for reply in [
                validate_project_draft(&root, "main", &draft),
                super::validate_project_draft_set(&root, &encode_draft_set(&[("main", &draft)])),
            ] {
                let reply: Value = serde_json::from_str(&reply).unwrap();
                assert_eq!(reply["status"], "ok", "{kind}: {reply}");
            }
            assert_eq!(fs::read(&manifest_path).unwrap(), before);
            assert_eq!(fs::read(&source_path).unwrap(), original);
            assert!(!root.join("generated").exists());
        }
        fs::remove_dir_all(root).unwrap();
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
