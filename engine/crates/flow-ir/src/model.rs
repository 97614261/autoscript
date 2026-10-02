use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

pub const SUPPORTED_FLOW_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NodeEnvelope {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub flow_schema_version: Option<u32>,
    pub node_id: String,
    pub block_id: String,
    pub parent_id: Option<String>,
    pub order_key: String,
    pub kind: String,
    pub node_version: u32,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub disabled: bool,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub child_blocks: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub depth: Option<u32>,
    pub args: Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SupportedNodeVersion<'a> {
    pub kind: &'a str,
    pub version: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct LoadOptions<'a> {
    pub flow_id: &'a str,
    pub root_block_id: &'a str,
    pub flow_schema_version: u32,
    pub supported_nodes: &'a [SupportedNodeVersion<'a>],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeCompatibility {
    Supported,
    UnknownKind,
    UnsupportedVersion,
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlowNode {
    pub envelope: NodeEnvelope,
    pub source_line_index: usize,
    pub compatibility: NodeCompatibility,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LineEnding {
    None,
    Lf,
    CrLf,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawLine {
    pub number: usize,
    pub content: Vec<u8>,
    pub ending: LineEnding,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Severity {
    Warning,
    Error,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiagnosticCode {
    ResourceLimitExceeded,
    InvalidUtf8,
    BlankLine,
    InvalidJson,
    UnsupportedFlowSchema,
    FlowSchemaVersionMismatch,
    UnknownNodeKind,
    UnsupportedNodeVersion,
    DuplicateNodeId,
    InvalidOrderKey,
    DuplicateOrderKey,
    BlockOwnershipConflict,
    MissingBlockOwner,
    RootNodeHasParent,
    ParentBlockMismatch,
    DanglingParent,
    StructuralCycle,
    DepthMismatch,
    NonCanonicalLineOrder,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    pub severity: Severity,
    pub code: DiagnosticCode,
    pub line: Option<usize>,
    pub node_id: Option<String>,
    pub message: String,
}

impl Diagnostic {
    pub(crate) fn error(
        code: DiagnosticCode,
        line: Option<usize>,
        node_id: Option<String>,
        message: impl Into<String>,
    ) -> Self {
        Self {
            severity: Severity::Error,
            code,
            line,
            node_id,
            message: message.into(),
        }
    }

    pub(crate) fn warning(code: DiagnosticCode, message: impl Into<String>) -> Self {
        Self {
            severity: Severity::Warning,
            code,
            line: None,
            node_id: None,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct FlowDocument {
    pub flow_id: String,
    pub root_block_id: String,
    pub flow_schema_version: u32,
    pub nodes: Vec<FlowNode>,
    pub(crate) canonical_order: Option<Vec<usize>>,
    pub(crate) expected_depths: Option<Vec<u32>>,
}

impl FlowDocument {
    /// Includes descendants of disabled containers; canonical order visits owners first.
    #[must_use]
    pub fn disabled_node_ids(&self) -> std::collections::HashSet<&str> {
        let mut disabled = std::collections::HashSet::new();
        if let Some(nodes) = self.canonical_nodes() {
            for node in nodes {
                if node.envelope.disabled
                    || node
                        .envelope
                        .parent_id
                        .as_deref()
                        .is_some_and(|id| disabled.contains(id))
                {
                    disabled.insert(node.envelope.node_id.as_str());
                }
            }
        }
        disabled
    }

    /// Iterates nodes in authoritative structural and `orderKey` order.
    #[must_use]
    pub fn canonical_nodes(&self) -> Option<impl Iterator<Item = &FlowNode>> {
        self.canonical_order
            .as_ref()
            .map(|order| order.iter().map(|&index| &self.nodes[index]))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct LoadReport {
    pub document: Option<FlowDocument>,
    pub raw_lines: Vec<RawLine>,
    pub diagnostics: Vec<Diagnostic>,
    original_source: Vec<u8>,
}

impl LoadReport {
    #[must_use]
    pub fn has_errors(&self) -> bool {
        self.diagnostics
            .iter()
            .any(|diagnostic| diagnostic.severity == Severity::Error)
    }

    #[must_use]
    pub fn is_compilable(&self) -> bool {
        self.document.is_some() && !self.has_errors()
    }

    /// Returns exact source bytes for read-only viewing/export, including original line endings.
    #[must_use]
    pub fn original_source(&self) -> &[u8] {
        &self.original_source
    }

    pub(crate) fn new(
        document: Option<FlowDocument>,
        raw_lines: Vec<RawLine>,
        diagnostics: Vec<Diagnostic>,
        original_source: Vec<u8>,
    ) -> Self {
        Self {
            document,
            raw_lines,
            diagnostics,
            original_source,
        }
    }
}
