use flow_ir::{Diagnostic, ProjectDocumentError};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Copy)]
pub struct FlowSource<'a> {
    pub flow_id: &'a str,
    pub exact_bytes: &'a [u8],
    pub report: &'a flow_ir::LoadReport,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompileError {
    UnsupportedSourceMode,
    MissingFlow(String),
    UnexpectedFlow(String),
    DuplicateFlowSource(String),
    SourceBytesMismatch(String),
    FlowNotCompilable {
        flow_id: String,
        diagnostics: Vec<Diagnostic>,
    },
    ProjectStructure(ProjectDocumentError),
    InvalidNodeArguments {
        node_id: String,
        reason: String,
    },
    UnsupportedNodeKind {
        node_id: String,
        kind: String,
    },
    MissingCapability {
        node_id: String,
        capability: String,
    },
    UnknownTargetFlow {
        node_id: String,
        target_flow_id: String,
    },
    MissingArgument {
        node_id: String,
        argument: String,
    },
    UnknownArgument {
        node_id: String,
        argument: String,
    },
    ArgumentTypeMismatch {
        node_id: String,
        argument: String,
    },
    RecursiveFlowCall,
    Serialization(String),
    LuaSyntax(String),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SourceMap {
    pub schema_version: u32,
    pub generation_id: String,
    pub entries: Vec<SourceMapEntry>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SourceMapEntry {
    pub flow_id: String,
    pub node_id: String,
    pub lua_start_line: u32,
    pub lua_end_line: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GenerationRecord {
    pub generation_id: String,
    pub flow_digest: String,
    pub lua_digest: String,
    pub source_map_digest: String,
    pub generator_version: String,
    pub runtime_api: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GenerationBundle {
    pub main_lua: Vec<u8>,
    pub source_map_json: Vec<u8>,
    pub generation_json: Vec<u8>,
    pub record: GenerationRecord,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VerificationError {
    InvalidGenerationRecord(String),
    GenerationRecordMismatch,
    LuaDigestMismatch,
    SourceMapDigestMismatch,
    FlowDigestMismatch,
    RuntimeApiMismatch,
    GeneratorVersionMismatch,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommitError {
    Busy,
    InvalidBundle(String),
    Io {
        operation: &'static str,
        path: PathBuf,
        message: String,
    },
}
