use crate::analyze::PreparedProject;
use crate::model::{SourceMapEntry, VisionComponent, VisionRequirements};
use std::collections::{BTreeSet, HashSet};

/// Only emitted operations count; authorization declarations are not SDK demand.
pub(crate) fn collect(
    project: &PreparedProject<'_>,
    entries: &[SourceMapEntry],
) -> VisionRequirements {
    let emitted: HashSet<_> = entries
        .iter()
        .map(|entry| (entry.flow_id.as_str(), entry.node_id.as_str()))
        .collect();
    let mut components = BTreeSet::new();
    for flow in &project.flows {
        for node in &flow.document.nodes {
            if !emitted.contains(&(
                flow.declaration.flow_id.as_str(),
                node.envelope.node_id.as_str(),
            )) {
                continue;
            }
            match node.envelope.kind.as_str() {
                "vision.findgray" => {
                    components.insert(VisionComponent::Opencv);
                }
                "ocr.alphanumeric" => {
                    components.insert(VisionComponent::Onnx);
                }
                _ => {}
            }
        }
    }
    VisionRequirements {
        version: 1,
        components: components.into_iter().collect(),
    }
}
