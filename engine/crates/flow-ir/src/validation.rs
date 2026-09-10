use crate::model::{Diagnostic, DiagnosticCode, FlowNode, RawLine};
use std::collections::{HashMap, HashSet};

pub(crate) struct StructureOutcome {
    pub canonical_order: Option<Vec<usize>>,
    pub expected_depths: Option<Vec<u32>>,
}

pub(crate) fn validate_structure(
    nodes: &[FlowNode],
    root_block_id: &str,
    raw_lines: &[RawLine],
    diagnostics: &mut Vec<Diagnostic>,
) -> StructureOutcome {
    let initial_diagnostics = diagnostics.len();
    let (node_ids, block_owners) = build_indexes(nodes, root_block_id, raw_lines, diagnostics);
    validate_membership(
        nodes,
        root_block_id,
        raw_lines,
        &node_ids,
        &block_owners,
        diagnostics,
    );
    if diagnostics.len() != initial_diagnostics {
        return invalid_outcome();
    }

    let Some(depths) =
        calculate_depths(nodes, root_block_id, raw_lines, &block_owners, diagnostics)
    else {
        return invalid_outcome();
    };
    let members = sorted_members(nodes);
    let canonical_order = canonical_projection(nodes, root_block_id, &members);
    if canonical_order.len() != nodes.len() {
        diagnostics.push(Diagnostic::error(
            DiagnosticCode::StructuralCycle,
            None,
            None,
            "not every node is reachable from rootBlockId",
        ));
        return invalid_outcome();
    }
    diagnose_projection(nodes, raw_lines, &depths, &canonical_order, diagnostics);
    StructureOutcome {
        canonical_order: Some(canonical_order),
        expected_depths: Some(depths),
    }
}

fn invalid_outcome() -> StructureOutcome {
    StructureOutcome {
        canonical_order: None,
        expected_depths: None,
    }
}

fn build_indexes<'a>(
    nodes: &'a [FlowNode],
    root_block_id: &str,
    raw_lines: &[RawLine],
    diagnostics: &mut Vec<Diagnostic>,
) -> (HashMap<&'a str, usize>, HashMap<&'a str, usize>) {
    let mut node_ids = HashMap::with_capacity(nodes.len());
    for (index, node) in nodes.iter().enumerate() {
        if let Some(previous) = node_ids.insert(node.envelope.node_id.as_str(), index) {
            diagnostics.push(node_error(
                nodes,
                raw_lines,
                index,
                DiagnosticCode::DuplicateNodeId,
                format!(
                    "nodeId duplicates line {}",
                    line_number(nodes, raw_lines, previous)
                ),
            ));
        }
        if !valid_order_key(&node.envelope.order_key) {
            diagnostics.push(node_error(
                nodes,
                raw_lines,
                index,
                DiagnosticCode::InvalidOrderKey,
                "orderKey must contain 1-64 ASCII characters from 0-9A-Za-z",
            ));
        }
    }
    let mut block_owners = HashMap::new();
    for (owner_index, node) in nodes.iter().enumerate() {
        for block_id in node.envelope.child_blocks.values() {
            if block_id == root_block_id {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    owner_index,
                    DiagnosticCode::BlockOwnershipConflict,
                    "a child block cannot reuse rootBlockId",
                ));
            } else if let Some(previous_owner) = block_owners.insert(block_id.as_str(), owner_index)
            {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    owner_index,
                    DiagnosticCode::BlockOwnershipConflict,
                    format!(
                        "blockId {block_id} is already owned by {}",
                        nodes[previous_owner].envelope.node_id
                    ),
                ));
            }
        }
    }
    (node_ids, block_owners)
}

fn validate_membership(
    nodes: &[FlowNode],
    root_block_id: &str,
    raw_lines: &[RawLine],
    node_ids: &HashMap<&str, usize>,
    block_owners: &HashMap<&str, usize>,
    diagnostics: &mut Vec<Diagnostic>,
) {
    let mut order_keys = HashSet::with_capacity(nodes.len());
    for (index, node) in nodes.iter().enumerate() {
        let envelope = &node.envelope;
        if !order_keys.insert((envelope.block_id.as_str(), envelope.order_key.as_str())) {
            diagnostics.push(node_error(
                nodes,
                raw_lines,
                index,
                DiagnosticCode::DuplicateOrderKey,
                "orderKey must be unique inside its block",
            ));
        }
        if let Some(parent_id) = envelope.parent_id.as_deref() {
            if !node_ids.contains_key(parent_id) {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    index,
                    DiagnosticCode::DanglingParent,
                    format!("parentId {parent_id} does not exist"),
                ));
            }
        }
        if envelope.block_id == root_block_id {
            if envelope.parent_id.is_some() {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    index,
                    DiagnosticCode::RootNodeHasParent,
                    "nodes in rootBlockId must have parentId=null",
                ));
            }
        } else if let Some(&owner_index) = block_owners.get(envelope.block_id.as_str()) {
            let expected_parent = &nodes[owner_index].envelope.node_id;
            if envelope.parent_id.as_ref() != Some(expected_parent) {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    index,
                    DiagnosticCode::ParentBlockMismatch,
                    format!(
                        "block {} is owned by {}; parentId must match",
                        envelope.block_id, expected_parent
                    ),
                ));
            }
        } else {
            diagnostics.push(node_error(
                nodes,
                raw_lines,
                index,
                DiagnosticCode::MissingBlockOwner,
                format!("blockId {} has no owner", envelope.block_id),
            ));
        }
    }
}

fn calculate_depths(
    nodes: &[FlowNode],
    root_block_id: &str,
    raw_lines: &[RawLine],
    block_owners: &HashMap<&str, usize>,
    diagnostics: &mut Vec<Diagnostic>,
) -> Option<Vec<u32>> {
    let mut depths = Vec::with_capacity(nodes.len());
    for (index, node) in nodes.iter().enumerate() {
        let mut block = node.envelope.block_id.as_str();
        let mut seen = HashSet::new();
        let mut depth = 0_u32;
        while block != root_block_id {
            if !seen.insert(block) {
                diagnostics.push(node_error(
                    nodes,
                    raw_lines,
                    index,
                    DiagnosticCode::StructuralCycle,
                    "block ownership contains a cycle",
                ));
                return None;
            }
            let owner_index = block_owners[block];
            block = nodes[owner_index].envelope.block_id.as_str();
            depth = depth.saturating_add(1);
        }
        depths.push(depth);
    }
    Some(depths)
}

fn sorted_members(nodes: &[FlowNode]) -> HashMap<&str, Vec<usize>> {
    let mut members: HashMap<&str, Vec<usize>> = HashMap::new();
    for (index, node) in nodes.iter().enumerate() {
        members
            .entry(node.envelope.block_id.as_str())
            .or_default()
            .push(index);
    }
    for block_members in members.values_mut() {
        block_members.sort_by(|&left, &right| {
            nodes[left]
                .envelope
                .order_key
                .as_bytes()
                .cmp(nodes[right].envelope.order_key.as_bytes())
        });
    }
    members
}

fn diagnose_projection(
    nodes: &[FlowNode],
    raw_lines: &[RawLine],
    depths: &[u32],
    canonical_order: &[usize],
    diagnostics: &mut Vec<Diagnostic>,
) {
    for (index, node) in nodes.iter().enumerate() {
        if node
            .envelope
            .depth
            .is_some_and(|stored| stored != depths[index])
        {
            diagnostics.push(node_error(
                nodes,
                raw_lines,
                index,
                DiagnosticCode::DepthMismatch,
                format!(
                    "stored depth does not match structural depth {}",
                    depths[index]
                ),
            ));
        }
    }
    if canonical_order.iter().copied().ne(0..nodes.len()) {
        diagnostics.push(Diagnostic::warning(
            DiagnosticCode::NonCanonicalLineOrder,
            "physical JSONL order differs from the structural orderKey projection",
        ));
    }
}

enum WorkItem<'a> {
    Block(&'a str),
    Node(usize),
}

fn canonical_projection<'a>(
    nodes: &'a [FlowNode],
    root_block_id: &'a str,
    members: &HashMap<&'a str, Vec<usize>>,
) -> Vec<usize> {
    let mut output = Vec::with_capacity(nodes.len());
    let mut work = vec![WorkItem::Block(root_block_id)];
    while let Some(item) = work.pop() {
        match item {
            WorkItem::Block(block_id) => {
                if let Some(block_members) = members.get(block_id) {
                    for &node_index in block_members.iter().rev() {
                        work.push(WorkItem::Node(node_index));
                    }
                }
            }
            WorkItem::Node(node_index) => {
                output.push(node_index);
                for block_id in nodes[node_index].envelope.child_blocks.values().rev() {
                    work.push(WorkItem::Block(block_id));
                }
            }
        }
    }
    output
}

fn valid_order_key(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value.bytes().all(|byte| {
            byte.is_ascii_digit() || byte.is_ascii_uppercase() || byte.is_ascii_lowercase()
        })
}

fn node_error(
    nodes: &[FlowNode],
    raw_lines: &[RawLine],
    index: usize,
    code: DiagnosticCode,
    message: impl Into<String>,
) -> Diagnostic {
    Diagnostic::error(
        code,
        Some(line_number(nodes, raw_lines, index)),
        Some(nodes[index].envelope.node_id.clone()),
        message,
    )
}

fn line_number(nodes: &[FlowNode], raw_lines: &[RawLine], index: usize) -> usize {
    raw_lines[nodes[index].source_line_index].number
}

#[cfg(test)]
mod tests {
    use crate::{load_jsonl, DiagnosticCode, LoadOptions, SupportedNodeVersion};

    const SUPPORTED: &[SupportedNodeVersion<'_>] = &[
        SupportedNodeVersion {
            kind: "loop.counted",
            version: 1,
        },
        SupportedNodeVersion {
            kind: "task.noop",
            version: 1,
        },
    ];

    fn load(source: &str) -> crate::LoadReport {
        load_jsonl(
            source.as_bytes(),
            LoadOptions {
                flow_id: "main",
                root_block_id: "block-root",
                flow_schema_version: 1,
                supported_nodes: SUPPORTED,
            },
        )
    }

    #[test]
    fn parent_and_block_owner_must_agree() {
        let source = concat!(
            r#"{"nodeId":"loop","blockId":"block-root","parentId":null,"orderKey":"a0","kind":"loop.counted","nodeVersion":1,"childBlocks":{"body":"block-body"},"args":{}}"#,
            "\n",
            r#"{"nodeId":"child","blockId":"block-body","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"args":{}}"#,
            "\n"
        );
        let report = load(source);
        assert!(report
            .diagnostics
            .iter()
            .any(|item| item.code == DiagnosticCode::ParentBlockMismatch));
    }

    #[test]
    fn semantic_order_ignores_physical_line_order() {
        let source = concat!(
            r#"{"nodeId":"second","blockId":"block-root","parentId":null,"orderKey":"b0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"#,
            "\n",
            r#"{"nodeId":"first","blockId":"block-root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"#,
            "\n"
        );
        let report = load(source);
        assert!(!report.has_errors());
        assert!(report
            .diagnostics
            .iter()
            .any(|item| item.code == DiagnosticCode::NonCanonicalLineOrder));
        let encoded = crate::encode_canonical(&report, false).expect("canonical encoding");
        let encoded = std::str::from_utf8(&encoded).expect("UTF-8");
        assert!(encoded.find("first").expect("first") < encoded.find("second").expect("second"));
    }

    #[test]
    fn duplicate_order_key_is_not_repairable() {
        let source = concat!(
            r#"{"nodeId":"first","blockId":"block-root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"args":{}}"#,
            "\n",
            r#"{"nodeId":"second","blockId":"block-root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"args":{}}"#,
            "\n"
        );
        let report = load(source);
        assert!(report
            .diagnostics
            .iter()
            .any(|item| item.code == DiagnosticCode::DuplicateOrderKey));
        assert!(crate::encode_canonical(&report, false).is_err());
    }
}
