use crate::model::{
    Diagnostic, DiagnosticCode, FlowDocument, FlowNode, LineEnding, LoadOptions, LoadReport,
    NodeCompatibility, NodeEnvelope, RawLine, Severity, SUPPORTED_FLOW_SCHEMA_VERSION,
};
use crate::validation::validate_structure;
use std::fmt;

const MAX_FLOW_BYTES: usize = 64 * 1024 * 1024;
const MAX_LINE_BYTES: usize = 1024 * 1024;
const MAX_NODES: usize = 100_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EncodeError {
    NoParsedDocument,
    InvalidStructure,
    UnrepairedDepth,
    Serialization(String),
}

impl fmt::Display for EncodeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoParsedDocument => formatter.write_str("the JSONL source was not parsed"),
            Self::InvalidStructure => {
                formatter.write_str("the Flow has errors that cannot be safely rewritten")
            }
            Self::UnrepairedDepth => {
                formatter.write_str("depth conflicts require explicit repair before saving")
            }
            Self::Serialization(error) => formatter.write_str(error),
        }
    }
}

/// Parses every JSONL line before building the structure. Original bytes always remain available.
#[must_use]
pub fn load_jsonl(source: &[u8], options: LoadOptions<'_>) -> LoadReport {
    let original_source = source.to_vec();
    let raw_lines = split_lines(source);
    let mut diagnostics = Vec::new();
    if !preflight(source, &raw_lines, options, &mut diagnostics) {
        return LoadReport::new(None, raw_lines, diagnostics, original_source);
    }
    let nodes = parse_nodes(&raw_lines, options, &mut diagnostics);
    if has_unparsed_line(&diagnostics) {
        return LoadReport::new(None, raw_lines, diagnostics, original_source);
    }

    let structure = validate_structure(&nodes, options.root_block_id, &raw_lines, &mut diagnostics);
    let document = FlowDocument {
        flow_id: options.flow_id.to_owned(),
        root_block_id: options.root_block_id.to_owned(),
        flow_schema_version: options.flow_schema_version,
        nodes,
        canonical_order: structure.canonical_order,
        expected_depths: structure.expected_depths,
    };
    LoadReport::new(Some(document), raw_lines, diagnostics, original_source)
}

fn preflight(
    source: &[u8],
    raw_lines: &[RawLine],
    options: LoadOptions<'_>,
    diagnostics: &mut Vec<Diagnostic>,
) -> bool {
    if source.len() > MAX_FLOW_BYTES {
        diagnostics.push(Diagnostic::error(
            DiagnosticCode::ResourceLimitExceeded,
            None,
            None,
            format!("Flow exceeds the {MAX_FLOW_BYTES}-byte limit"),
        ));
        return false;
    }
    if std::str::from_utf8(source).is_err() {
        diagnostics.push(Diagnostic::error(
            DiagnosticCode::InvalidUtf8,
            None,
            None,
            "Flow must be UTF-8",
        ));
        return false;
    }
    if options.flow_schema_version != SUPPORTED_FLOW_SCHEMA_VERSION {
        diagnostics.push(Diagnostic::error(
            DiagnosticCode::UnsupportedFlowSchema,
            None,
            None,
            format!(
                "flowSchemaVersion {} is unsupported",
                options.flow_schema_version
            ),
        ));
        return false;
    }
    if raw_lines.len() > MAX_NODES {
        diagnostics.push(Diagnostic::error(
            DiagnosticCode::ResourceLimitExceeded,
            None,
            None,
            format!("Flow exceeds the {MAX_NODES}-node limit"),
        ));
        return false;
    }
    true
}

fn parse_nodes(
    raw_lines: &[RawLine],
    options: LoadOptions<'_>,
    diagnostics: &mut Vec<Diagnostic>,
) -> Vec<FlowNode> {
    let mut nodes = Vec::with_capacity(raw_lines.len());
    for (line_index, line) in raw_lines.iter().enumerate() {
        let Some(envelope) = parse_envelope(line, diagnostics) else {
            continue;
        };
        if envelope
            .flow_schema_version
            .is_some_and(|version| version != options.flow_schema_version)
        {
            diagnostics.push(Diagnostic::error(
                DiagnosticCode::FlowSchemaVersionMismatch,
                Some(line.number),
                Some(envelope.node_id.clone()),
                "line flowSchemaVersion differs from project.json",
            ));
        }
        let compatibility = classify_node(&envelope, options.supported_nodes);
        diagnose_compatibility(&envelope, compatibility, line.number, diagnostics);
        nodes.push(FlowNode {
            envelope,
            source_line_index: line_index,
            compatibility,
        });
    }

    nodes
}

fn parse_envelope(line: &RawLine, diagnostics: &mut Vec<Diagnostic>) -> Option<NodeEnvelope> {
    let issue = if line.content.len() > MAX_LINE_BYTES {
        Some((
            DiagnosticCode::ResourceLimitExceeded,
            format!("line exceeds the {MAX_LINE_BYTES}-byte limit"),
        ))
    } else if line.content.is_empty() {
        Some((
            DiagnosticCode::BlankLine,
            "blank lines are not valid Flow nodes".to_string(),
        ))
    } else {
        None
    };
    if let Some((code, message)) = issue {
        diagnostics.push(Diagnostic::error(code, Some(line.number), None, message));
        return None;
    }
    match serde_json::from_slice(&line.content) {
        Ok(envelope) => Some(envelope),
        Err(error) => {
            diagnostics.push(Diagnostic::error(
                DiagnosticCode::InvalidJson,
                Some(line.number),
                None,
                format!("invalid Flow envelope: {error}"),
            ));
            None
        }
    }
}

fn diagnose_compatibility(
    envelope: &NodeEnvelope,
    compatibility: NodeCompatibility,
    line_number: usize,
    diagnostics: &mut Vec<Diagnostic>,
) {
    let issue = match compatibility {
        NodeCompatibility::Supported => return,
        NodeCompatibility::UnknownKind => (
            DiagnosticCode::UnknownNodeKind,
            format!("unknown node kind {}", envelope.kind),
        ),
        NodeCompatibility::UnsupportedVersion => (
            DiagnosticCode::UnsupportedNodeVersion,
            format!(
                "unsupported {} nodeVersion {}",
                envelope.kind, envelope.node_version
            ),
        ),
    };
    diagnostics.push(Diagnostic::error(
        issue.0,
        Some(line_number),
        Some(envelope.node_id.clone()),
        issue.1,
    ));
}

fn has_unparsed_line(diagnostics: &[Diagnostic]) -> bool {
    diagnostics.iter().any(|diagnostic| {
        matches!(
            diagnostic.code,
            DiagnosticCode::BlankLine
                | DiagnosticCode::InvalidJson
                | DiagnosticCode::ResourceLimitExceeded
        )
    })
}

fn classify_node(
    node: &NodeEnvelope,
    supported: &[crate::model::SupportedNodeVersion<'_>],
) -> NodeCompatibility {
    if supported
        .iter()
        .any(|known| known.kind == node.kind && known.version == node.node_version)
    {
        NodeCompatibility::Supported
    } else if supported.iter().any(|known| known.kind == node.kind) {
        NodeCompatibility::UnsupportedVersion
    } else {
        NodeCompatibility::UnknownKind
    }
}

/// Produces stable LF-delimited JSON in structural/orderKey order.
///
/// `repair_depth` is an explicit save decision. It rewrites the derived cache from authoritative
/// structure; without it, a depth conflict cannot be overwritten.
///
/// # Errors
///
/// Returns an error for unparsed input, unsafe structural/compatibility errors, unrepaired depth,
/// or serialization failure.
pub fn encode_canonical(report: &LoadReport, repair_depth: bool) -> Result<Vec<u8>, EncodeError> {
    let document = report
        .document
        .as_ref()
        .ok_or(EncodeError::NoParsedDocument)?;
    for diagnostic in &report.diagnostics {
        if diagnostic.severity != Severity::Error {
            continue;
        }
        if diagnostic.code == DiagnosticCode::DepthMismatch {
            if !repair_depth {
                return Err(EncodeError::UnrepairedDepth);
            }
        } else {
            return Err(EncodeError::InvalidStructure);
        }
    }
    let order = document
        .canonical_order
        .as_ref()
        .ok_or(EncodeError::InvalidStructure)?;
    let depths = document
        .expected_depths
        .as_ref()
        .ok_or(EncodeError::InvalidStructure)?;
    let mut output = Vec::new();
    for &node_index in order {
        let mut envelope = document.nodes[node_index].envelope.clone();
        if repair_depth {
            envelope.depth = Some(depths[node_index]);
        }
        let encoded = serde_json::to_vec(&envelope)
            .map_err(|error| EncodeError::Serialization(error.to_string()))?;
        output.extend_from_slice(&encoded);
        output.push(b'\n');
    }
    Ok(output)
}

fn split_lines(source: &[u8]) -> Vec<RawLine> {
    let mut lines = Vec::new();
    let mut start = 0;
    for (index, &byte) in source.iter().enumerate() {
        if byte != b'\n' {
            continue;
        }
        let (end, ending) = if index > start && source[index - 1] == b'\r' {
            (index - 1, LineEnding::CrLf)
        } else {
            (index, LineEnding::Lf)
        };
        lines.push(RawLine {
            number: lines.len() + 1,
            content: source[start..end].to_vec(),
            ending,
        });
        start = index + 1;
    }
    if start < source.len() {
        lines.push(RawLine {
            number: lines.len() + 1,
            content: source[start..].to_vec(),
            ending: LineEnding::None,
        });
    }
    lines
}

#[cfg(test)]
mod tests {
    use super::{encode_canonical, load_jsonl, EncodeError};
    use crate::model::{
        DiagnosticCode, LoadOptions, SupportedNodeVersion, SUPPORTED_FLOW_SCHEMA_VERSION,
    };

    const SUPPORTED: &[SupportedNodeVersion<'_>] = &[SupportedNodeVersion {
        kind: "task.noop",
        version: 1,
    }];

    fn options() -> LoadOptions<'static> {
        LoadOptions {
            flow_id: "main",
            root_block_id: "block-root",
            flow_schema_version: SUPPORTED_FLOW_SCHEMA_VERSION,
            supported_nodes: SUPPORTED,
        }
    }

    #[test]
    fn invalid_line_preserves_the_exact_source() {
        let source = b"{not json}\r\n";
        let report = load_jsonl(source, options());
        assert!(report.document.is_none());
        assert_eq!(report.original_source(), source);
        assert_eq!(report.diagnostics[0].code, DiagnosticCode::InvalidJson);
    }

    #[test]
    fn unsupported_flow_version_does_not_guess_the_envelope() {
        let mut options = options();
        options.flow_schema_version = 2;
        let report = load_jsonl(b"", options);
        assert!(report.document.is_none());
        assert_eq!(
            report.diagnostics[0].code,
            DiagnosticCode::UnsupportedFlowSchema
        );
    }

    #[test]
    fn depth_repair_requires_an_explicit_decision() {
        let source = concat!(
            r#"{"flowSchemaVersion":1,"nodeId":"node-a","blockId":"block-root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":4,"args":{}}"#,
            "\n"
        );
        let report = load_jsonl(source.as_bytes(), options());
        assert_eq!(report.diagnostics[0].code, DiagnosticCode::DepthMismatch);
        assert_eq!(
            encode_canonical(&report, false),
            Err(EncodeError::UnrepairedDepth)
        );
        let repaired = encode_canonical(&report, true).expect("explicit depth repair");
        assert!(std::str::from_utf8(&repaired)
            .expect("UTF-8 JSON")
            .contains("\"depth\":0"));
    }
}
