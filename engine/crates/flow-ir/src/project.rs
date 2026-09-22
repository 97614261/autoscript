use crate::LoadReport;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::fmt;

pub const SUPPORTED_PROJECT_FORMAT_VERSION: u32 = 2;
pub const LEGACY_PROJECT_FORMAT_VERSION: u32 = 1;
const MAX_PROJECT_CAPABILITIES: usize = 64;

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
    pub lua_files: Vec<String>,
    #[serde(default)]
    pub lua_directories: Vec<String>,
    #[serde(default)]
    pub entry_flow_id: Option<String>,
    #[serde(default)]
    pub flows: Vec<ProjectFlow>,
    #[serde(default)]
    pub variables: Vec<ProjectVariable>,
    #[serde(default)]
    pub resources: Vec<ProjectResource>,
    pub capabilities: Vec<String>,
    pub design: DesignSpec,
    #[serde(default)]
    pub runner_ui: Option<RunnerUi>,
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
pub struct ProjectVariable {
    pub name: String,
    pub scope: ProjectVariableScope,
    #[serde(default)]
    pub flow_id: Option<String>,
    #[serde(rename = "type")]
    pub value_type: ProjectVariableType,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectVariableScope {
    Global,
    Flow,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectVariableType {
    Integer,
    Number,
    String,
    Image,
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunnerUi {
    #[serde(default)]
    pub description: Option<String>,
    pub fields: Vec<RunnerUiField>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct RunnerUiField {
    pub id: String,
    pub label: String,
    pub kind: RunnerUiFieldKind,
    pub required: bool,
    pub initial_value: Value,
    #[serde(default)]
    pub minimum: Option<i64>,
    #[serde(default)]
    pub maximum: Option<i64>,
    #[serde(default)]
    pub options: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RunnerUiFieldKind {
    Text,
    Integer,
    Boolean,
    Choice,
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
    TooManyCapabilities,
    InvalidCapability(String),
    DuplicateCapability(String),
    InvalidParameter(String),
    DuplicateParameter(String),
    InvalidVariable(String),
    DuplicateVariable(String),
    TooManyResources,
    InvalidResourcePath(String),
    DuplicateResourcePath(String),
    InvalidRunnerUi(String),
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
    validate_runner_ui_json_shape(&value)?;
    let manifest: ProjectManifest = serde_json::from_value(value)
        .map_err(|error| ProjectManifestError::InvalidJson(error.to_string()))?;
    validate_manifest(&manifest)?;
    Ok(manifest)
}

fn validate_runner_ui_json_shape(value: &Value) -> Result<(), ProjectManifestError> {
    const FIELD_KEYS: [&str; 8] = [
        "id",
        "label",
        "kind",
        "required",
        "initialValue",
        "minimum",
        "maximum",
        "options",
    ];

    let Some(runner_ui) = value.get("runnerUi") else {
        return Ok(());
    };
    if runner_ui.is_null() {
        return Ok(());
    }
    let object = runner_ui.as_object().ok_or_else(|| {
        ProjectManifestError::InvalidRunnerUi("runnerUi must be an object or null".to_owned())
    })?;
    if object.len() != 2 || !object.contains_key("description") || !object.contains_key("fields") {
        return Err(ProjectManifestError::InvalidRunnerUi(
            "runnerUi fields are incomplete or unknown".to_owned(),
        ));
    }
    let fields = object
        .get("fields")
        .and_then(Value::as_array)
        .ok_or_else(|| {
            ProjectManifestError::InvalidRunnerUi("runnerUi fields must be an array".to_owned())
        })?;
    if fields.iter().any(|field| {
        field.as_object().is_none_or(|field| {
            field.len() != FIELD_KEYS.len()
                || FIELD_KEYS.iter().any(|key| !field.contains_key(*key))
        })
    }) {
        return Err(ProjectManifestError::InvalidRunnerUi(
            "runnerUi field is incomplete or contains unknown keys".to_owned(),
        ));
    }
    Ok(())
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
    validate_variables(manifest)?;
    validate_resources(&manifest.resources)?;
    validate_capabilities(&manifest.capabilities)?;
    validate_runner_ui(manifest.runner_ui.as_ref())
}

fn validate_variables(manifest: &ProjectManifest) -> Result<(), ProjectManifestError> {
    if manifest.variables.len() > 256 {
        return Err(ProjectManifestError::InvalidVariable(
            "too many variables".to_owned(),
        ));
    }
    let flow_ids = manifest
        .flows
        .iter()
        .map(|flow| flow.flow_id.as_str())
        .collect::<HashSet<_>>();
    let mut declarations = HashSet::with_capacity(manifest.variables.len());
    for variable in &manifest.variables {
        if variable.name.len() > 64 || !valid_identifier(&variable.name) {
            return Err(ProjectManifestError::InvalidVariable(variable.name.clone()));
        }
        let key = match variable.scope {
            ProjectVariableScope::Global => {
                if variable.flow_id.is_some() {
                    return Err(ProjectManifestError::InvalidVariable(variable.name.clone()));
                }
                format!("global:{}", variable.name)
            }
            ProjectVariableScope::Flow => {
                let Some(flow_id) = variable.flow_id.as_deref() else {
                    return Err(ProjectManifestError::InvalidVariable(variable.name.clone()));
                };
                if !flow_ids.contains(flow_id) {
                    return Err(ProjectManifestError::InvalidVariable(variable.name.clone()));
                }
                format!("flow:{flow_id}:{}", variable.name)
            }
        };
        if !declarations.insert(key) {
            return Err(ProjectManifestError::DuplicateVariable(
                variable.name.clone(),
            ));
        }
    }
    Ok(())
}

/// Validates the optional dynamic form exposed by a packaged runner.
///
/// # Errors
///
/// Returns [`ProjectManifestError::InvalidRunnerUi`] when the field count, identifiers, labels,
/// value constraints, or initial values are invalid.
pub fn validate_runner_ui(runner_ui: Option<&RunnerUi>) -> Result<(), ProjectManifestError> {
    let Some(runner_ui) = runner_ui else {
        return Ok(());
    };
    if runner_ui.fields.is_empty() || runner_ui.fields.len() > 32 {
        return Err(ProjectManifestError::InvalidRunnerUi(
            "runnerUi must contain 1..32 fields".to_owned(),
        ));
    }
    if runner_ui
        .description
        .as_ref()
        .is_some_and(|value| value.chars().count() > 512 || value.chars().any(char::is_control))
    {
        return Err(ProjectManifestError::InvalidRunnerUi(
            "runnerUi description is invalid".to_owned(),
        ));
    }
    let mut ids = HashSet::with_capacity(runner_ui.fields.len());
    for field in &runner_ui.fields {
        if !valid_identifier(&field.id) || field.id.len() > 64 || !ids.insert(field.id.as_str()) {
            return Err(ProjectManifestError::InvalidRunnerUi(
                "runnerUi field id is invalid or duplicated".to_owned(),
            ));
        }
        if field.label.is_empty()
            || field.label.chars().count() > 64
            || field.label.chars().any(char::is_control)
        {
            return Err(ProjectManifestError::InvalidRunnerUi(
                "runnerUi field label is invalid".to_owned(),
            ));
        }
        let valid = match field.kind {
            RunnerUiFieldKind::Text => {
                field
                    .initial_value
                    .as_str()
                    .is_some_and(|value| value.chars().count() <= 256 && !value.contains('\0'))
                    && field.minimum.is_none()
                    && field.maximum.is_none()
                    && field.options.is_empty()
            }
            RunnerUiFieldKind::Integer => {
                let value = field.initial_value.as_i64();
                let minimum = field.minimum.unwrap_or(-1_000_000_000);
                let maximum = field.maximum.unwrap_or(1_000_000_000);
                value.is_some_and(|value| minimum <= value && value <= maximum)
                    && minimum <= maximum
                    && field.options.is_empty()
            }
            RunnerUiFieldKind::Boolean => {
                field.initial_value.is_boolean()
                    && field.minimum.is_none()
                    && field.maximum.is_none()
                    && field.options.is_empty()
            }
            RunnerUiFieldKind::Choice => {
                let unique = field.options.iter().collect::<HashSet<_>>();
                (1..=32).contains(&field.options.len())
                    && unique.len() == field.options.len()
                    && field.options.iter().all(|value| {
                        !value.is_empty()
                            && value.chars().count() <= 64
                            && !value.chars().any(char::is_control)
                    })
                    && field
                        .initial_value
                        .as_str()
                        .is_some_and(|value| field.options.iter().any(|option| option == value))
                    && field.minimum.is_none()
                    && field.maximum.is_none()
            }
        };
        if !valid {
            return Err(ProjectManifestError::InvalidRunnerUi(format!(
                "runnerUi field {} is invalid",
                field.id
            )));
        }
    }
    Ok(())
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
            let lua_files: Vec<&str> = if manifest.lua_files.is_empty() {
                vec![entry_point]
            } else {
                manifest.lua_files.iter().map(String::as_str).collect()
            };
            if lua_files.len() > 128
                || lua_files.iter().any(|path| !valid_lua_entry_point(path))
                || !lua_files.windows(2).all(|pair| pair[0] < pair[1])
                || !lua_files.iter().any(|path| *path == entry_point)
            {
                return Err(ProjectManifestError::InvalidEntryPoint(
                    entry_point.to_owned(),
                ));
            }
            let lua_directories: Vec<&str> = if manifest.lua_directories.is_empty() {
                vec!["lua"]
            } else {
                manifest
                    .lua_directories
                    .iter()
                    .map(String::as_str)
                    .collect()
            };
            if lua_directories.len() > 128
                || lua_directories
                    .iter()
                    .any(|path| !valid_lua_directory(path))
                || !lua_directories.windows(2).all(|pair| pair[0] < pair[1])
                || !lua_directories.contains(&"lua")
                || lua_files
                    .iter()
                    .flat_map(|path| lua_parent_directories(path))
                    .any(|parent| !lua_directories.contains(&parent))
            {
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
    if value == "main.lua" {
        return true;
    }
    let Some(relative) = value.strip_prefix("lua/") else {
        return false;
    };
    let segments: Vec<&str> = relative.split('/').collect();
    if !(1..=5).contains(&segments.len()) {
        return false;
    }
    let Some(file) = segments.last().and_then(|name| name.strip_suffix(".lua")) else {
        return false;
    };
    segments[..segments.len() - 1]
        .iter()
        .all(|segment| valid_lua_path_segment(segment))
        && valid_lua_path_segment(file)
}

fn valid_lua_path_segment(value: &str) -> bool {
    !value.is_empty()
        && value.chars().count() <= 64
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric()
                || character == '_'
                || character == '-'
                || ('\u{4e00}'..='\u{9fff}').contains(&character)
        })
}

fn valid_lua_directory(value: &str) -> bool {
    let Some(relative) = value.strip_prefix("lua") else {
        return false;
    };
    if relative.is_empty() {
        return true;
    }
    let Some(relative) = relative.strip_prefix('/') else {
        return false;
    };
    let segments: Vec<&str> = relative.split('/').collect();
    (1..=5).contains(&segments.len())
        && segments
            .iter()
            .all(|segment| valid_lua_path_segment(segment))
}

fn lua_parent_directories(path: &str) -> Vec<&str> {
    if path == "main.lua" {
        return Vec::new();
    }
    let parts: Vec<&str> = path.split('/').collect();
    (1..parts.len())
        .map(|index| match index {
            1 => "lua",
            _ => &path[..parts[..index].iter().map(|part| part.len()).sum::<usize>() + index - 1],
        })
        .collect()
}

fn valid_identifier(value: &str) -> bool {
    let mut bytes = value.bytes();
    bytes
        .next()
        .is_some_and(|byte| byte.is_ascii_alphabetic() || byte == b'_')
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn validate_capabilities(capabilities: &[String]) -> Result<(), ProjectManifestError> {
    if capabilities.len() > MAX_PROJECT_CAPABILITIES {
        return Err(ProjectManifestError::TooManyCapabilities);
    }
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

/// Flow 文件名允许的最大字符数（按 Unicode 标量计）。
const MAX_FLOW_NAME_CHARS: usize = 64;
const FLOW_PATH_PREFIX: &str = "visual/flows/";
const FLOW_PATH_SUFFIX: &str = ".jsonl";

/// Flow 路径策略，必须与 `ProjectStore.isValidFlowPath` 和 `project.schema.json` 保持一致。
///
/// 参考新版易编精灵：源文件全部放在同一个固定目录里，分组只是编辑器的虚拟视图而不是子目录，
/// 所以路径固定为单层 `visual/flows/<名称>.jsonl`。名称允许中文（参考产品的文件名本来就是中文），
/// 字符集限定为字母、数字、`_`、`-`、CJK 统一表意文字，以及不在首尾的 `.` 和空格；
/// 于是 `.`、`..`、隐藏名、路径分隔符和 Windows 保留字符都构造不出来。
fn valid_flow_path(value: &str) -> bool {
    value
        .strip_prefix(FLOW_PATH_PREFIX)
        .and_then(|rest| rest.strip_suffix(FLOW_PATH_SUFFIX))
        .is_some_and(valid_flow_name)
}

fn valid_flow_name(name: &str) -> bool {
    let (Some(first), Some(last)) = (name.chars().next(), name.chars().next_back()) else {
        return false;
    };
    name.chars().count() <= MAX_FLOW_NAME_CHARS
        && flow_name_edge_char(first)
        && flow_name_edge_char(last)
        && name
            .chars()
            .all(|value| flow_name_edge_char(value) || matches!(value, '.' | ' '))
}

fn flow_name_edge_char(value: char) -> bool {
    value.is_ascii_alphanumeric()
        || matches!(value, '_' | '-')
        || ('\u{4E00}'..='\u{9FFF}').contains(&value)
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
    if value.len() > 128 {
        return false;
    }
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
    fn typed_global_and_flow_variables_are_validated() {
        let source = manifest("visual/flows/main.jsonl", "main").replace(
            r#""capabilities""#,
            r#""variables":[{"name":"total","scope":"global","flowId":null,"type":"integer"},{"name":"frame","scope":"flow","flowId":"main","type":"image"}],"capabilities""#,
        );
        let parsed = parse_project_manifest(source.as_bytes()).expect("typed variables");
        assert_eq!(parsed.variables.len(), 2);

        let invalid = source.replace(
            r#""name":"frame","scope":"flow","flowId":"main""#,
            r#""name":"frame","scope":"flow","flowId":"missing""#,
        );
        assert_eq!(
            parse_project_manifest(invalid.as_bytes()).expect_err("missing Flow must fail"),
            ProjectManifestError::InvalidVariable("frame".to_owned()),
        );

        let duplicate = source.replace(
            r#"],"capabilities""#,
            r#",{"name":"total","scope":"global","flowId":null,"type":"number"}],"capabilities""#,
        );
        assert!(matches!(
            parse_project_manifest(duplicate.as_bytes()),
            Err(ProjectManifestError::DuplicateVariable(name)) if name == "total"
        ));
    }

    #[test]
    fn traversal_flow_path_is_rejected() {
        let error =
            parse_project_manifest(manifest("visual/flows/../outside.jsonl", "main").as_bytes())
                .expect_err("nested traversal must fail");
        assert!(matches!(error, ProjectManifestError::InvalidFlowPath(_)));
    }

    #[test]
    fn flow_path_allows_single_level_chinese_names() {
        for path in [
            "visual/flows/默认名称1.jsonl",
            "visual/flows/主流程_副本 v2.jsonl",
            "visual/flows/a.b.jsonl",
        ] {
            let parsed = parse_project_manifest(manifest(path, "main").as_bytes())
                .unwrap_or_else(|error| panic!("{path} must parse: {error:?}"));
            assert_eq!(parsed.flows[0].path, path);
        }
    }

    #[test]
    fn flow_path_rejects_nesting_edges_and_length() {
        let too_long = format!("visual/flows/{}.jsonl", "长".repeat(65));
        for path in [
            "visual/flows/分组/文件.jsonl",
            "visual/flows/.hidden.jsonl",
            "visual/flows/name .jsonl",
            "visual/flows/a:b.jsonl",
            "visual/flows/a\\b.jsonl",
            "visual/flows/.jsonl",
            "visual/flows/カタカナ.jsonl",
            too_long.as_str(),
        ] {
            assert!(
                matches!(
                    parse_project_manifest(manifest(path, "main").as_bytes()),
                    Err(ProjectManifestError::InvalidFlowPath(_))
                ),
                "{path} must be rejected"
            );
        }
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
    fn capability_count_is_bounded() {
        let capabilities = (0..65)
            .map(|index| format!(r#""test.c{index}""#))
            .collect::<Vec<_>>()
            .join(",");
        let source = manifest("visual/flows/main.jsonl", "main")
            .replace(r#"["core.task"]"#, &format!("[{capabilities}]"));

        assert_eq!(
            parse_project_manifest(source.as_bytes()),
            Err(ProjectManifestError::TooManyCapabilities)
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
