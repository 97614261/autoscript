use crate::LoadReport;
use serde::Deserialize;
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::fmt;

pub const SUPPORTED_PROJECT_FORMAT_VERSION: u32 = 2;
pub const LEGACY_PROJECT_FORMAT_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectManifest {
    pub format_version: u32,
    pub flow_schema_version: u32,
    pub runtime_api: String,
    pub project_id: String,
    pub name: String,
    pub source_mode: ProjectSourceMode,
    #[serde(default)]
    pub entry_point: Option<String>,
    #[serde(default)]
    pub entry_flow_id: Option<String>,
    #[serde(default)]
    pub flows: Vec<ProjectFlow>,
    #[serde(default)]
    pub resources: Vec<ProjectResource>,
    pub capabilities: Vec<String>,
    pub design: DesignSpec,
    #[serde(default)]
    pub owner_id: Option<String>,
    #[serde(default)]
    pub cloud_id: Option<String>,
    #[serde(default)]
    pub sync_state: Option<String>,
    #[serde(default)]
    pub signature: Option<Map<String, Value>>,
    #[serde(default)]
    pub license_policy: Option<Map<String, Value>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectSourceMode {
    Lua,
    Visual,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectFlow {
    pub flow_id: String,
    pub path: String,
    pub root_block_id: String,
    pub params: Vec<ProjectParameter>,
    pub returns: Option<ProjectReturn>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectResource {
    pub kind: ProjectResourceKind,
    pub path: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ProjectResourceKind {
    Image,
    GlyphDictionary,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectParameter {
    pub name: String,
    #[serde(rename = "type")]
    pub value_type: ValueType,
    pub required: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectReturn {
    #[serde(rename = "type")]
    pub value_type: ValueType,
    pub nullable: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ValueType {
    Boolean,
    Integer,
    Number,
    String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DesignSpec {
    pub width: u32,
    pub height: u32,
    pub scale_mode: ScaleMode,
    pub orientation_policy: OrientationPolicy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ScaleMode {
    Letterbox,
    Crop,
    Stretch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OrientationPolicy {
    Follow,
    Portrait,
    Landscape,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProjectManifestError {
    InvalidJson(String),
    UnsupportedFormatVersion(u32),
    UnsupportedFlowSchemaVersion(u32),
    InvalidRuntimeApi,
    InvalidIdentity(&'static str),
    MissingEntryPoint,
    InvalidEntryPoint(String),
    UnexpectedEntryPoint,
    MissingEntryFlowDeclaration,
    UnexpectedEntryFlow,
    UnexpectedFlows,
    InvalidDesignSize,
    NoFlows,
    DuplicateFlowId(String),
    DuplicateFlowPath(String),
    DuplicateRootBlockId(String),
    InvalidFlowPath(String),
    MissingEntryFlow(String),
    InvalidCapability(String),
    DuplicateCapability(String),
    InvalidParameter(String),
    DuplicateParameter(String),
    TooManyResources,
    InvalidResourcePath(String),
    DuplicateResourcePath(String),
}

impl fmt::Display for ProjectManifestError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

/// Parses a strict `project.json` and enforces semantic invariants not expressible in its schema.
///
/// # Errors
///
/// Returns a stable error for invalid JSON, unsupported versions, duplicate identities, an unsafe
/// Flow path, a missing entry Flow, invalid capabilities, or an out-of-range design size.
pub fn parse_project_manifest(source: &[u8]) -> Result<ProjectManifest, ProjectManifestError> {
    let mut value: Value = serde_json::from_slice(source)
        .map_err(|error| ProjectManifestError::InvalidJson(error.to_string()))?;
    let version = value
        .get("formatVersion")
        .and_then(Value::as_u64)
        .and_then(|version| u32::try_from(version).ok())
        .ok_or_else(|| ProjectManifestError::InvalidJson("formatVersion is missing".to_owned()))?;
    if version == LEGACY_PROJECT_FORMAT_VERSION {
        let object = value.as_object_mut().ok_or_else(|| {
            ProjectManifestError::InvalidJson("project manifest must be an object".to_owned())
        })?;
        object.insert(
            "formatVersion".to_owned(),
            Value::from(SUPPORTED_PROJECT_FORMAT_VERSION),
        );
        object.insert("sourceMode".to_owned(), Value::from("visual"));
    } else if version != SUPPORTED_PROJECT_FORMAT_VERSION {
        return Err(ProjectManifestError::UnsupportedFormatVersion(version));
    }
    let manifest: ProjectManifest = serde_json::from_value(value)
        .map_err(|error| ProjectManifestError::InvalidJson(error.to_string()))?;
    validate_manifest(&manifest)?;
    Ok(manifest)
}

fn validate_manifest(manifest: &ProjectManifest) -> Result<(), ProjectManifestError> {
    if manifest.format_version != SUPPORTED_PROJECT_FORMAT_VERSION {
        return Err(ProjectManifestError::UnsupportedFormatVersion(
            manifest.format_version,
        ));
    }
    if manifest.flow_schema_version != crate::SUPPORTED_FLOW_SCHEMA_VERSION {
        return Err(ProjectManifestError::UnsupportedFlowSchemaVersion(
            manifest.flow_schema_version,
        ));
    }
    if !valid_runtime_api(&manifest.runtime_api) {
        return Err(ProjectManifestError::InvalidRuntimeApi);
    }
    validate_identity(&manifest.project_id, "projectId")?;
    validate_identity(&manifest.name, "name")?;
    if !(1..=32_768).contains(&manifest.design.width)
        || !(1..=32_768).contains(&manifest.design.height)
    {
        return Err(ProjectManifestError::InvalidDesignSize);
    }
    validate_source_mode(manifest)?;
    validate_resources(&manifest.resources)?;
    validate_capabilities(&manifest.capabilities)
}

fn validate_source_mode(manifest: &ProjectManifest) -> Result<(), ProjectManifestError> {
    match manifest.source_mode {
        ProjectSourceMode::Lua => {
            if manifest.entry_flow_id.is_some() {
                return Err(ProjectManifestError::UnexpectedEntryFlow);
            }
            if !manifest.flows.is_empty() {
                return Err(ProjectManifestError::UnexpectedFlows);
            }
            let entry_point = manifest
                .entry_point
                .as_deref()
                .ok_or(ProjectManifestError::MissingEntryPoint)?;
            if !valid_lua_entry_point(entry_point) {
                return Err(ProjectManifestError::InvalidEntryPoint(
                    entry_point.to_owned(),
                ));
            }
        }
        ProjectSourceMode::Visual => {
            if manifest.entry_point.is_some() {
                return Err(ProjectManifestError::UnexpectedEntryPoint);
            }
            let entry_flow_id = manifest
                .entry_flow_id
                .as_deref()
                .ok_or(ProjectManifestError::MissingEntryFlowDeclaration)?;
            validate_identity(entry_flow_id, "entryFlowId")?;
            validate_flows(manifest, entry_flow_id)?;
        }
    }
    Ok(())
}

fn validate_resources(resources: &[ProjectResource]) -> Result<(), ProjectManifestError> {
    if resources.len() > 256 {
        return Err(ProjectManifestError::TooManyResources);
    }
    let mut paths = HashSet::with_capacity(resources.len());
    for resource in resources {
        if !valid_resource_path(resource.kind, &resource.path) {
            return Err(ProjectManifestError::InvalidResourcePath(
                resource.path.clone(),
            ));
        }
        if !paths.insert(resource.path.as_str()) {
            return Err(ProjectManifestError::DuplicateResourcePath(
                resource.path.clone(),
            ));
        }
    }
    Ok(())
}

fn validate_flows(
    manifest: &ProjectManifest,
    entry_flow_id: &str,
) -> Result<(), ProjectManifestError> {
    if manifest.flows.is_empty() {
        return Err(ProjectManifestError::NoFlows);
    }
    let mut flow_ids = HashSet::with_capacity(manifest.flows.len());
    let mut paths = HashSet::with_capacity(manifest.flows.len());
    let mut roots = HashSet::with_capacity(manifest.flows.len());
    for flow in &manifest.flows {
        validate_identity(&flow.flow_id, "flowId")?;
        validate_identity(&flow.root_block_id, "rootBlockId")?;
        if !flow_ids.insert(flow.flow_id.as_str()) {
            return Err(ProjectManifestError::DuplicateFlowId(flow.flow_id.clone()));
        }
        if !valid_flow_path(&flow.path) {
            return Err(ProjectManifestError::InvalidFlowPath(flow.path.clone()));
        }
        if !paths.insert(flow.path.as_str()) {
            return Err(ProjectManifestError::DuplicateFlowPath(flow.path.clone()));
        }
        if !roots.insert(flow.root_block_id.as_str()) {
            return Err(ProjectManifestError::DuplicateRootBlockId(
                flow.root_block_id.clone(),
            ));
        }
        let mut parameters = HashSet::with_capacity(flow.params.len());
        for parameter in &flow.params {
            if !valid_identifier(&parameter.name) {
                return Err(ProjectManifestError::InvalidParameter(
                    parameter.name.clone(),
                ));
            }
            if !parameters.insert(parameter.name.as_str()) {
                return Err(ProjectManifestError::DuplicateParameter(
                    parameter.name.clone(),
                ));
            }
        }
    }
    if !flow_ids.contains(entry_flow_id) {
        return Err(ProjectManifestError::MissingEntryFlow(
            entry_flow_id.to_owned(),
        ));
    }
    Ok(())
}

fn valid_lua_entry_point(value: &str) -> bool {
    value == "main.lua"
}

fn valid_identifier(value: &str) -> bool {
    let mut bytes = value.bytes();
    bytes
        .next()
        .is_some_and(|byte| byte.is_ascii_alphabetic() || byte == b'_')
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn validate_capabilities(capabilities: &[String]) -> Result<(), ProjectManifestError> {
    let mut unique = HashSet::with_capacity(capabilities.len());
    for capability in capabilities {
        if !valid_capability(capability) {
            return Err(ProjectManifestError::InvalidCapability(capability.clone()));
        }
        if !unique.insert(capability.as_str()) {
            return Err(ProjectManifestError::DuplicateCapability(
                capability.clone(),
            ));
        }
    }
    Ok(())
}

fn validate_identity(value: &str, field: &'static str) -> Result<(), ProjectManifestError> {
    if value.is_empty() || value.len() > 128 {
        Err(ProjectManifestError::InvalidIdentity(field))
    } else {
        Ok(())
    }
}

fn valid_runtime_api(value: &str) -> bool {
    let Some((major, minor)) = value.split_once('.') else {
        return false;
    };
    !major.is_empty()
        && !minor.is_empty()
        && !minor.contains('.')
        && major.bytes().all(|byte| byte.is_ascii_digit())
        && minor.bytes().all(|byte| byte.is_ascii_digit())
}

fn valid_flow_path(value: &str) -> bool {
    let Some(filename) = value.strip_prefix("visual/flows/") else {
        return false;
    };
    let Some(stem) = filename.strip_suffix(".jsonl") else {
        return false;
    };
    !stem.is_empty()
        && !filename.contains(['/', '\\'])
        && filename
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

fn valid_resource_path(kind: ProjectResourceKind, value: &str) -> bool {
    if value.is_empty()
        || value.len() > 256
        || value.contains(['\\', '\0'])
        || value
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
    {
        return false;
    }
    match kind {
        ProjectResourceKind::Image => value.starts_with("assets/images/"),
        ProjectResourceKind::GlyphDictionary => {
            value.starts_with("dictionaries/") && value.ends_with(".asglyph")
        }
    }
}

fn valid_capability(value: &str) -> bool {
    let mut parts = value.split('.');
    let valid_part = |part: &str| {
        part.bytes()
            .next()
            .is_some_and(|byte| byte.is_ascii_lowercase())
            && part
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit())
    };
    let Some(first) = parts.next() else {
        return false;
    };
    let Some(second) = parts.next() else {
        return false;
    };
    valid_part(first) && valid_part(second) && parts.all(valid_part)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProjectDocumentError {
    MissingFlow(String),
    UnexpectedFlow(String),
    DuplicateLoadedFlow(String),
    RootBlockMismatch(String),
    DuplicateGlobalNodeId(String),
    DuplicateGlobalBlockId(String),
}

/// Checks loaded Flow documents against the manifest and project-wide ID scopes.
#[must_use]
pub fn validate_project_documents(
    manifest: &ProjectManifest,
    reports: &[LoadReport],
) -> Vec<ProjectDocumentError> {
    let declared = manifest
        .flows
        .iter()
        .map(|flow| (flow.flow_id.as_str(), flow))
        .collect::<HashMap<_, _>>();
    let mut loaded = HashSet::new();
    let mut node_ids = HashSet::new();
    let mut block_ids = HashSet::new();
    let mut errors = Vec::new();
    for report in reports {
        let Some(document) = report.document.as_ref() else {
            continue;
        };
        if !loaded.insert(document.flow_id.as_str()) {
            errors.push(ProjectDocumentError::DuplicateLoadedFlow(
                document.flow_id.clone(),
            ));
        }
        let Some(flow) = declared.get(document.flow_id.as_str()) else {
            errors.push(ProjectDocumentError::UnexpectedFlow(
                document.flow_id.clone(),
            ));
            continue;
        };
        if document.root_block_id != flow.root_block_id {
            errors.push(ProjectDocumentError::RootBlockMismatch(
                document.flow_id.clone(),
            ));
        }
        register_id(
            &mut block_ids,
            &document.root_block_id,
            ProjectDocumentError::DuplicateGlobalBlockId,
            &mut errors,
        );
        for node in &document.nodes {
            register_id(
                &mut node_ids,
                &node.envelope.node_id,
                ProjectDocumentError::DuplicateGlobalNodeId,
                &mut errors,
            );
            for block_id in node.envelope.child_blocks.values() {
                register_id(
                    &mut block_ids,
                    block_id,
                    ProjectDocumentError::DuplicateGlobalBlockId,
                    &mut errors,
                );
            }
        }
    }
    for flow_id in declared.keys() {
        if !loaded.contains(flow_id) {
            errors.push(ProjectDocumentError::MissingFlow((*flow_id).to_owned()));
        }
    }
    errors
}

fn register_id<'a>(
    ids: &mut HashSet<&'a str>,
    id: &'a str,
    duplicate: impl FnOnce(String) -> ProjectDocumentError,
    errors: &mut Vec<ProjectDocumentError>,
) {
    if !ids.insert(id) {
        errors.push(duplicate(id.to_owned()));
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_project_manifest, ProjectManifestError, ProjectSourceMode};

    fn manifest(flow_path: &str, entry: &str) -> String {
        format!(
            r#"{{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.0","projectId":"project-1","name":"test","sourceMode":"visual","entryFlowId":"{entry}","flows":[{{"flowId":"main","path":"{flow_path}","rootBlockId":"block-main","params":[],"returns":null}}],"capabilities":["core.task"],"design":{{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}}}"#
        )
    }

    #[test]
    fn valid_manifest_is_parsed() {
        let parsed = parse_project_manifest(manifest("visual/flows/main.jsonl", "main").as_bytes())
            .expect("valid manifest");
        assert_eq!(parsed.flows[0].flow_id, "main");
        assert_eq!(parsed.source_mode, ProjectSourceMode::Visual);
    }

    #[test]
    fn traversal_flow_path_is_rejected() {
        let error =
            parse_project_manifest(manifest("visual/flows/../outside.jsonl", "main").as_bytes())
                .expect_err("nested traversal must fail");
        assert!(matches!(error, ProjectManifestError::InvalidFlowPath(_)));
    }

    #[test]
    fn entry_flow_must_exist() {
        let error =
            parse_project_manifest(manifest("visual/flows/main.jsonl", "missing").as_bytes())
                .expect_err("missing entry must fail");
        assert_eq!(
            error,
            ProjectManifestError::MissingEntryFlow("missing".into())
        );
    }

    #[test]
    fn project_resources_are_typed_canonical_and_unique() {
        let source = manifest("visual/flows/main.jsonl", "main").replace(
            r#""capabilities""#,
            r#""resources":[{"kind":"image","path":"assets/images/button.png"},{"kind":"glyphDictionary","path":"dictionaries/main.asglyph"}],"capabilities""#,
        );
        let parsed = parse_project_manifest(source.as_bytes()).expect("resources");
        assert_eq!(parsed.resources.len(), 2);

        let duplicate = manifest("visual/flows/main.jsonl", "main").replace(
            r#""capabilities""#,
            r#""resources":[{"kind":"image","path":"assets/images/button.png"},{"kind":"image","path":"assets/images/button.png"}],"capabilities""#,
        );
        assert!(matches!(
            parse_project_manifest(duplicate.as_bytes()),
            Err(ProjectManifestError::DuplicateResourcePath(_))
        ));
    }

    #[test]
    fn resource_traversal_is_rejected() {
        let source = manifest("visual/flows/main.jsonl", "main").replace(
            r#""capabilities""#,
            r#""resources":[{"kind":"glyphDictionary","path":"dictionaries/../secret.asglyph"}],"capabilities""#,
        );
        assert!(matches!(
            parse_project_manifest(source.as_bytes()),
            Err(ProjectManifestError::InvalidResourcePath(_))
        ));
    }

    #[test]
    fn lua_project_has_an_exclusive_main_entry() {
        let source = br#"{"formatVersion":2,"flowSchemaVersion":1,"runtimeApi":"1.5","projectId":"project-lua","name":"Lua","sourceMode":"lua","entryPoint":"main.lua","flows":[],"capabilities":["core.task"],"design":{"width":720,"height":1280,"scaleMode":"letterbox","orientationPolicy":"follow"}}"#;
        let parsed = parse_project_manifest(source).expect("Lua project");
        assert_eq!(parsed.source_mode, ProjectSourceMode::Lua);
        assert_eq!(parsed.entry_point.as_deref(), Some("main.lua"));

        let mixed = String::from_utf8(source.to_vec())
            .expect("UTF-8")
            .replace(r#""flows":[]"#, r#""entryFlowId":"main","flows":[]"#);
        assert_eq!(
            parse_project_manifest(mixed.as_bytes()),
            Err(ProjectManifestError::UnexpectedEntryFlow)
        );
    }

    #[test]
    fn legacy_visual_manifest_is_migrated_in_memory() {
        let legacy = manifest("visual/flows/main.jsonl", "main")
            .replace(r#""formatVersion":2"#, r#""formatVersion":1"#)
            .replace(r#""sourceMode":"visual","#, "");
        let parsed = parse_project_manifest(legacy.as_bytes()).expect("legacy migration");
        assert_eq!(parsed.format_version, 2);
        assert_eq!(parsed.source_mode, ProjectSourceMode::Visual);
        assert_eq!(parsed.entry_flow_id.as_deref(), Some("main"));
    }
}
