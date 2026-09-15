//! Deterministic project-to-Runner release preparation and verification.

use flow_compiler::{compile_project, FlowSource};
use flow_ir::{
    load_jsonl, parse_project_manifest, LoadOptions, OrientationPolicy, ProjectManifest,
    ProjectResourceKind, ProjectSourceMode, RunnerUi, RunnerUiFieldKind, ScaleMode,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::fmt;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const FORMAT_VERSION: u32 = 3;
const MANIFEST_PATH: &str = "release.json";
const LUA_BUNDLE_PATH: &str = "payload/main.lua";
const SOURCE_MAP_BUNDLE_PATH: &str = "payload/source-map.json";
const MAX_MANIFEST_BYTES: u64 = 1024 * 1024;
const MAX_LUA_BYTES: u64 = 16 * 1024 * 1024;
const MAX_FLOW_BYTES: u64 = 64 * 1024 * 1024;
const MAX_SOURCE_MAP_BYTES: u64 = 8 * 1024 * 1024;
const MAX_IMAGE_BYTES: u64 = 32 * 1024 * 1024;
const MAX_DICTIONARY_BYTES: u64 = 8 * 1024 * 1024;
const MAX_RELEASE_BYTES: u64 = 512 * 1024 * 1024;
const MAX_RESOURCES: usize = 256;
const MAX_CAPABILITIES: usize = 64;
const MAX_SOURCE_MAP_ENTRIES: usize = 100_000;
const SUPPORTED_RUNTIME_API: &str = "1.5";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PrepareOptions {
    pub application_id: String,
    pub version_code: u32,
    pub version_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseManifest {
    pub format_version: u32,
    pub release_id: String,
    pub project_id: String,
    pub display_name: String,
    pub application_id: String,
    pub version_code: u32,
    pub version_name: String,
    pub runtime_api: String,
    pub source_mode: String,
    pub capabilities: Vec<String>,
    pub design: ReleaseDesign,
    pub runner_ui: Option<RunnerUi>,
    pub lua: ReleaseArtifact,
    pub source_map: Option<ReleaseArtifact>,
    pub resources: Vec<ReleaseResource>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseDesign {
    pub width: u32,
    pub height: u32,
    pub scale_mode: String,
    pub orientation_policy: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseArtifact {
    pub path: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReleaseResource {
    pub kind: String,
    pub logical_path: String,
    pub bundle_path: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReleaseError {
    Io(String),
    InvalidOption(String),
    InvalidProject(String),
    InvalidFlow(String),
    InvalidRelease(String),
    ResourceLimit(String),
    OutputExists(PathBuf),
}

impl fmt::Display for ReleaseError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ReleaseError {}

struct OwnedFlow {
    flow_id: String,
    exact_bytes: Vec<u8>,
    report: flow_ir::LoadReport,
}

struct CompiledPayload {
    lua: Vec<u8>,
    source_map: Option<Vec<u8>>,
}

/// Produces an immutable, digest-bound release directory without overwriting an existing output.
///
/// # Errors
///
/// Fails for invalid options, projects, Flow graphs, Lua, resources, paths, limits, or I/O.
pub fn prepare_project(
    project_directory: &Path,
    output_directory: &Path,
    options: &PrepareOptions,
) -> Result<ReleaseManifest, ReleaseError> {
    validate_options(options)?;
    if output_directory.exists() {
        return Err(ReleaseError::OutputExists(output_directory.to_path_buf()));
    }
    let project_root = canonical_directory(project_directory, "project directory")?;
    let manifest_bytes = read_bounded(
        &contained_file(&project_root, "project.json")?,
        MAX_MANIFEST_BYTES,
        "project.json",
    )?;
    let project = parse_project_manifest(&manifest_bytes)
        .map_err(|error| ReleaseError::InvalidProject(error.to_string()))?;
    if !runtime_api_compatible(&project.runtime_api) {
        return Err(ReleaseError::InvalidProject(format!(
            "runtime API {} is not compatible with {SUPPORTED_RUNTIME_API}",
            project.runtime_api
        )));
    }
    let compiled = compile_or_load_lua(&project_root, &project)?;
    let lua_artifact = ReleaseArtifact {
        path: LUA_BUNDLE_PATH.to_owned(),
        sha256: sha256(&compiled.lua),
        size: u64::try_from(compiled.lua.len()).map_err(|_| resource_limit("Lua size overflow"))?,
    };
    let source_map_artifact = compiled.source_map.as_ref().map(|bytes| ReleaseArtifact {
        path: SOURCE_MAP_BUNDLE_PATH.to_owned(),
        sha256: sha256(bytes),
        size: u64::try_from(bytes.len()).unwrap_or(u64::MAX),
    });

    let base_size = lua_artifact
        .size
        .checked_add(
            source_map_artifact
                .as_ref()
                .map_or(0, |artifact| artifact.size),
        )
        .ok_or_else(|| resource_limit("release size overflow"))?;
    let resource_payloads = collect_resource_payloads(&project_root, &project, base_size)?;

    let design = ReleaseDesign {
        width: project.design.width,
        height: project.design.height,
        scale_mode: scale_mode_name(project.design.scale_mode).to_owned(),
        orientation_policy: orientation_name(project.design.orientation_policy).to_owned(),
    };
    let mut capabilities = project.capabilities.clone();
    capabilities.sort();
    let mut release = ReleaseManifest {
        format_version: FORMAT_VERSION,
        release_id: String::new(),
        project_id: project.project_id,
        display_name: project.name,
        application_id: options.application_id.clone(),
        version_code: options.version_code,
        version_name: options.version_name.clone(),
        runtime_api: project.runtime_api,
        source_mode: source_mode_name(project.source_mode).to_owned(),
        capabilities,
        design,
        runner_ui: project.runner_ui,
        lua: lua_artifact,
        source_map: source_map_artifact,
        resources: resource_payloads
            .iter()
            .map(|(resource, _)| resource.clone())
            .collect(),
    };
    release.release_id = calculate_release_id(&release);

    persist_release(
        output_directory,
        &release,
        &compiled.lua,
        compiled.source_map.as_deref(),
        &resource_payloads,
    )?;
    Ok(release)
}

fn collect_resource_payloads(
    project_root: &Path,
    project: &ProjectManifest,
    lua_size: u64,
) -> Result<Vec<(ReleaseResource, Vec<u8>)>, ReleaseError> {
    if project.resources.len() > MAX_RESOURCES {
        return Err(resource_limit("more than 256 project resources"));
    }
    let mut payloads = Vec::with_capacity(project.resources.len());
    let mut total_bytes = lua_size;
    for declaration in &project.resources {
        let maximum = resource_limit_for(declaration.kind);
        let bytes = read_bounded(
            &contained_file(project_root, &declaration.path)?,
            maximum,
            &declaration.path,
        )?;
        validate_resource_bytes(declaration.kind, &bytes, &declaration.path)?;
        let size =
            u64::try_from(bytes.len()).map_err(|_| resource_limit("resource size overflow"))?;
        total_bytes = total_bytes
            .checked_add(size)
            .ok_or_else(|| resource_limit("release size overflow"))?;
        if total_bytes > MAX_RELEASE_BYTES {
            return Err(resource_limit("release payload exceeds 512 MiB"));
        }
        payloads.push((
            ReleaseResource {
                kind: resource_kind_name(declaration.kind).to_owned(),
                logical_path: declaration.path.clone(),
                bundle_path: format!("payload/{}", declaration.path),
                sha256: sha256(&bytes),
                size,
            },
            bytes,
        ));
    }
    payloads.sort_by(|left, right| left.0.logical_path.cmp(&right.0.logical_path));
    Ok(payloads)
}

fn persist_release(
    output_directory: &Path,
    release: &ReleaseManifest,
    lua: &[u8],
    source_map: Option<&[u8]>,
    resource_payloads: &[(ReleaseResource, Vec<u8>)],
) -> Result<(), ReleaseError> {
    let parent = output_directory.parent().ok_or_else(|| {
        ReleaseError::InvalidOption("output directory must have a parent".to_owned())
    })?;
    fs::create_dir_all(parent).map_err(|error| io_error(&error))?;
    let staging = parent.join(format!(
        ".release-{}-{}-{}.tmp",
        std::process::id(),
        timestamp_nonce(),
        output_directory
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("bundle")
    ));
    if staging.exists() {
        return Err(ReleaseError::OutputExists(staging));
    }
    fs::create_dir(&staging).map_err(|error| io_error(&error))?;
    let result = (|| {
        write_synced(&staging.join(LUA_BUNDLE_PATH), lua)?;
        if let Some(source_map) = source_map {
            write_synced(&staging.join(SOURCE_MAP_BUNDLE_PATH), source_map)?;
        }
        for (resource, bytes) in resource_payloads {
            write_synced(&staging.join(&resource.bundle_path), bytes)?;
        }
        let mut manifest = serde_json::to_vec_pretty(release)
            .map_err(|error| ReleaseError::InvalidRelease(error.to_string()))?;
        manifest.push(b'\n');
        write_synced(&staging.join(MANIFEST_PATH), &manifest)?;
        verify_bundle(&staging)?;
        fs::rename(&staging, output_directory).map_err(|error| io_error(&error))
    })();
    if result.is_err() {
        let _ = fs::remove_dir_all(&staging);
    }
    result
}

/// Verifies exact bundle closure, metadata binding, payload digests, Lua syntax and resource format.
///
/// # Errors
///
/// Fails closed for an unknown field, extra/missing file, path escape, digest mismatch or limit.
pub fn verify_bundle(directory: &Path) -> Result<ReleaseManifest, ReleaseError> {
    let root = canonical_directory(directory, "release directory")?;
    let manifest_bytes = read_bounded(
        &contained_file(&root, MANIFEST_PATH)?,
        MAX_MANIFEST_BYTES,
        MANIFEST_PATH,
    )?;
    let release: ReleaseManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|error| ReleaseError::InvalidRelease(error.to_string()))?;
    validate_release_manifest(&release)?;
    if calculate_release_id(&release) != release.release_id {
        return Err(invalid_release(
            "releaseId does not bind the current metadata",
        ));
    }

    let mut expected = BTreeSet::from([MANIFEST_PATH.to_owned(), release.lua.path.clone()]);
    if let Some(source_map) = &release.source_map {
        expected.insert(source_map.path.clone());
    }
    expected.extend(
        release
            .resources
            .iter()
            .map(|resource| resource.bundle_path.clone()),
    );
    let actual = collect_files(&root)?;
    if actual != expected {
        let extra = actual.difference(&expected).next();
        let missing = expected.difference(&actual).next();
        return Err(invalid_release(match (extra, missing) {
            (Some(path), _) => format!("unexpected release file: {path}"),
            (_, Some(path)) => format!("missing release file: {path}"),
            _ => "release file closure mismatch".to_owned(),
        }));
    }

    let lua = verify_artifact(&root, &release.lua, MAX_LUA_BYTES)?;
    lua_runtime::validate_text_chunk(&lua, "embedded/main.lua")
        .map_err(|error| invalid_release(format!("invalid Lua: {error}")))?;
    let mut total_bytes = release.lua.size;
    if let Some(source_map) = &release.source_map {
        let bytes = verify_artifact(&root, source_map, MAX_SOURCE_MAP_BYTES)?;
        let parsed: flow_compiler::SourceMap = serde_json::from_slice(&bytes)
            .map_err(|error| invalid_release(format!("invalid source map: {error}")))?;
        let expected_lua_header = format!(
            "-- @generated by flow-compiler; do not edit.\n-- generationId: {}\n",
            parsed.generation_id
        );
        if parsed.schema_version != 1
            || !valid_generation_id(&parsed.generation_id)
            || parsed.entries.len() > MAX_SOURCE_MAP_ENTRIES
            || !lua.starts_with(expected_lua_header.as_bytes())
            || parsed.entries.iter().any(|entry| {
                entry.flow_id.is_empty()
                    || entry.flow_id.chars().count() > 128
                    || entry.flow_id.chars().any(char::is_control)
                    || entry.node_id.is_empty()
                    || entry.node_id.chars().count() > 128
                    || entry.node_id.chars().any(char::is_control)
                    || entry.lua_start_line == 0
                    || entry.lua_start_line > entry.lua_end_line
            })
        {
            return Err(invalid_release(
                "source map is invalid or does not match the generated Lua",
            ));
        }
        total_bytes = total_bytes
            .checked_add(source_map.size)
            .ok_or_else(|| resource_limit("release size overflow"))?;
    }
    for resource in &release.resources {
        let kind = parse_resource_kind(&resource.kind)?;
        let artifact = ReleaseArtifact {
            path: resource.bundle_path.clone(),
            sha256: resource.sha256.clone(),
            size: resource.size,
        };
        let bytes = verify_artifact(&root, &artifact, resource_limit_for(kind))?;
        validate_resource_bytes(kind, &bytes, &resource.logical_path)?;
        total_bytes = total_bytes
            .checked_add(resource.size)
            .ok_or_else(|| resource_limit("release size overflow"))?;
        if total_bytes > MAX_RELEASE_BYTES {
            return Err(resource_limit("release payload exceeds 512 MiB"));
        }
    }
    Ok(release)
}

fn compile_or_load_lua(
    root: &Path,
    project: &ProjectManifest,
) -> Result<CompiledPayload, ReleaseError> {
    match project.source_mode {
        ProjectSourceMode::Lua => {
            let entry = project
                .entry_point
                .as_deref()
                .ok_or_else(|| ReleaseError::InvalidProject("Lua entry is missing".to_owned()))?;
            let bytes = read_bounded(&contained_file(root, entry)?, MAX_LUA_BYTES, entry)?;
            lua_runtime::validate_text_chunk(&bytes, "main.lua")
                .map_err(|error| ReleaseError::InvalidProject(format!("invalid Lua: {error}")))?;
            Ok(CompiledPayload {
                lua: bytes,
                source_map: None,
            })
        }
        ProjectSourceMode::Visual => {
            let mut owned = Vec::with_capacity(project.flows.len());
            for declaration in &project.flows {
                let exact_bytes = read_bounded(
                    &contained_file(root, &declaration.path)?,
                    MAX_FLOW_BYTES,
                    &declaration.path,
                )?;
                let report = load_jsonl(
                    &exact_bytes,
                    LoadOptions {
                        flow_id: &declaration.flow_id,
                        root_block_id: &declaration.root_block_id,
                        flow_schema_version: project.flow_schema_version,
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
            compile_project(project, &sources)
                .map(|bundle| CompiledPayload {
                    lua: bundle.main_lua,
                    source_map: Some(bundle.source_map_json),
                })
                .map_err(|errors| ReleaseError::InvalidFlow(format!("{errors:?}")))
        }
    }
}

fn validate_options(options: &PrepareOptions) -> Result<(), ReleaseError> {
    if !valid_application_id(&options.application_id) {
        return Err(ReleaseError::InvalidOption(
            "invalid Android applicationId".to_owned(),
        ));
    }
    if options.version_code == 0 || options.version_code > 2_100_000_000 {
        return Err(ReleaseError::InvalidOption(
            "versionCode is out of range".to_owned(),
        ));
    }
    if options.version_name.is_empty()
        || options.version_name.len() > 64
        || options.version_name.chars().any(char::is_control)
    {
        return Err(ReleaseError::InvalidOption(
            "versionName is invalid".to_owned(),
        ));
    }
    Ok(())
}

fn validate_release_manifest(release: &ReleaseManifest) -> Result<(), ReleaseError> {
    validate_options(&PrepareOptions {
        application_id: release.application_id.clone(),
        version_code: release.version_code,
        version_name: release.version_name.clone(),
    })?;
    if release.format_version != FORMAT_VERSION
        || !valid_identity(&release.project_id, 64)
        || !valid_display_name(&release.display_name)
        || !valid_runtime_api(&release.runtime_api)
        || !runtime_api_compatible(&release.runtime_api)
        || !matches!(release.source_mode.as_str(), "lua" | "visual")
        || release.capabilities.len() > MAX_CAPABILITIES
        || release
            .capabilities
            .iter()
            .any(|value| !valid_capability(value))
        || !release
            .capabilities
            .windows(2)
            .all(|pair| pair[0] < pair[1])
        || !(1..=32_768).contains(&release.design.width)
        || !(1..=32_768).contains(&release.design.height)
        || !matches!(
            release.design.scale_mode.as_str(),
            "letterbox" | "crop" | "stretch"
        )
        || !matches!(
            release.design.orientation_policy.as_str(),
            "follow" | "portrait" | "landscape"
        )
        || release.lua.path != LUA_BUNDLE_PATH
        || !valid_sha256(&release.release_id)
        || !valid_sha256(&release.lua.sha256)
        || release.lua.size == 0
        || release.lua.size > MAX_LUA_BYTES
        || release.resources.len() > MAX_RESOURCES
    {
        return Err(invalid_release("invalid release metadata"));
    }
    flow_ir::validate_runner_ui(release.runner_ui.as_ref())
        .map_err(|error| invalid_release(error.to_string()))?;
    match (release.source_mode.as_str(), release.source_map.as_ref()) {
        ("visual", Some(artifact))
            if artifact.path == SOURCE_MAP_BUNDLE_PATH
                && valid_sha256(&artifact.sha256)
                && (1..=MAX_SOURCE_MAP_BYTES).contains(&artifact.size) => {}
        ("lua", None) => {}
        _ => {
            return Err(invalid_release(
                "source map does not match release source mode",
            ))
        }
    }
    let mut last_path: Option<&str> = None;
    for resource in &release.resources {
        let kind = parse_resource_kind(&resource.kind)?;
        if !valid_resource_path(kind, &resource.logical_path)
            || resource.bundle_path != format!("payload/{}", resource.logical_path)
            || !valid_sha256(&resource.sha256)
            || resource.size == 0
            || resource.size > resource_limit_for(kind)
            || last_path.is_some_and(|previous| previous >= resource.logical_path.as_str())
        {
            return Err(invalid_release("invalid or unsorted release resource"));
        }
        last_path = Some(&resource.logical_path);
    }
    Ok(())
}

fn verify_artifact(
    root: &Path,
    artifact: &ReleaseArtifact,
    maximum: u64,
) -> Result<Vec<u8>, ReleaseError> {
    let bytes = read_bounded(
        &contained_file(root, &artifact.path)?,
        maximum,
        &artifact.path,
    )?;
    if u64::try_from(bytes.len()).ok() != Some(artifact.size) || sha256(&bytes) != artifact.sha256 {
        return Err(invalid_release(format!(
            "artifact digest mismatch: {}",
            artifact.path
        )));
    }
    Ok(bytes)
}

fn calculate_release_id(release: &ReleaseManifest) -> String {
    let mut digest = Sha256::new();
    for value in [
        release.format_version.to_string(),
        release.project_id.clone(),
        release.display_name.clone(),
        release.application_id.clone(),
        release.version_code.to_string(),
        release.version_name.clone(),
        release.runtime_api.clone(),
        release.source_mode.clone(),
        release.capabilities.len().to_string(),
        release.design.width.to_string(),
        release.design.height.to_string(),
        release.design.scale_mode.clone(),
        release.design.orientation_policy.clone(),
        release.lua.path.clone(),
        release.lua.sha256.clone(),
        release.lua.size.to_string(),
        release.source_map.is_some().to_string(),
        release.resources.len().to_string(),
        release.runner_ui.is_some().to_string(),
    ] {
        digest_field(&mut digest, &value);
    }
    for capability in &release.capabilities {
        digest_field(&mut digest, capability);
    }
    if let Some(source_map) = &release.source_map {
        digest_field(&mut digest, &source_map.path);
        digest_field(&mut digest, &source_map.sha256);
        digest_field(&mut digest, &source_map.size.to_string());
    }
    if let Some(runner_ui) = &release.runner_ui {
        digest_field(&mut digest, &runner_ui.description.is_some().to_string());
        if let Some(description) = &runner_ui.description {
            digest_field(&mut digest, description);
        }
        digest_field(&mut digest, &runner_ui.fields.len().to_string());
        for field in &runner_ui.fields {
            digest_field(&mut digest, &field.id);
            digest_field(&mut digest, &field.label);
            digest_field(&mut digest, runner_ui_kind_name(field.kind));
            digest_field(&mut digest, &field.required.to_string());
            digest_field(&mut digest, &runner_ui_initial_value(field));
            digest_optional_integer(&mut digest, field.minimum);
            digest_optional_integer(&mut digest, field.maximum);
            digest_field(&mut digest, &field.options.len().to_string());
            for option in &field.options {
                digest_field(&mut digest, option);
            }
        }
    }
    for resource in &release.resources {
        for value in [
            &resource.kind,
            &resource.logical_path,
            &resource.bundle_path,
            &resource.sha256,
            &resource.size.to_string(),
        ] {
            digest_field(&mut digest, value);
        }
    }
    format!("{:x}", digest.finalize())
}

fn digest_optional_integer(digest: &mut Sha256, value: Option<i64>) {
    digest_field(digest, &value.is_some().to_string());
    if let Some(value) = value {
        digest_field(digest, &value.to_string());
    }
}

fn runner_ui_kind_name(kind: RunnerUiFieldKind) -> &'static str {
    match kind {
        RunnerUiFieldKind::Text => "text",
        RunnerUiFieldKind::Integer => "integer",
        RunnerUiFieldKind::Boolean => "boolean",
        RunnerUiFieldKind::Choice => "choice",
    }
}

fn runner_ui_initial_value(field: &flow_ir::RunnerUiField) -> String {
    match field.kind {
        RunnerUiFieldKind::Text | RunnerUiFieldKind::Choice => {
            field.initial_value.as_str().unwrap_or_default().to_owned()
        }
        RunnerUiFieldKind::Integer => field.initial_value.as_i64().unwrap_or_default().to_string(),
        RunnerUiFieldKind::Boolean => field
            .initial_value
            .as_bool()
            .unwrap_or_default()
            .to_string(),
    }
}

fn digest_field(digest: &mut Sha256, value: &str) {
    let bytes = value.as_bytes();
    digest.update(u64::try_from(bytes.len()).unwrap_or(u64::MAX).to_be_bytes());
    digest.update(bytes);
}

fn collect_files(root: &Path) -> Result<BTreeSet<String>, ReleaseError> {
    let mut result = BTreeSet::new();
    let mut pending = vec![root.to_path_buf()];
    while let Some(directory) = pending.pop() {
        for entry in fs::read_dir(&directory).map_err(|error| io_error(&error))? {
            let entry = entry.map_err(|error| io_error(&error))?;
            let metadata = fs::symlink_metadata(entry.path()).map_err(|error| io_error(&error))?;
            if metadata.file_type().is_symlink() {
                return Err(invalid_release("release bundle contains a symbolic link"));
            }
            if metadata.is_dir() {
                pending.push(entry.path());
            } else if metadata.is_file() {
                let relative = entry
                    .path()
                    .strip_prefix(root)
                    .map_err(|error| invalid_release(error.to_string()))?
                    .to_string_lossy()
                    .replace('\\', "/");
                result.insert(relative);
            } else {
                return Err(invalid_release("release bundle contains a special file"));
            }
        }
    }
    Ok(result)
}

fn canonical_directory(path: &Path, label: &str) -> Result<PathBuf, ReleaseError> {
    let canonical = path.canonicalize().map_err(|error| io_error(&error))?;
    if !canonical.is_dir() {
        return Err(ReleaseError::InvalidOption(format!(
            "{label} is not a directory"
        )));
    }
    Ok(canonical)
}

fn contained_file(root: &Path, relative: &str) -> Result<PathBuf, ReleaseError> {
    if !valid_relative_path(relative) {
        return Err(invalid_release(format!(
            "invalid relative path: {relative}"
        )));
    }
    let file = root
        .join(relative)
        .canonicalize()
        .map_err(|error| io_error(&error))?;
    if !file.starts_with(root) || !file.is_file() {
        return Err(invalid_release(format!(
            "path escapes release root: {relative}"
        )));
    }
    if fs::symlink_metadata(root.join(relative))
        .map_err(|error| io_error(&error))?
        .file_type()
        .is_symlink()
    {
        return Err(invalid_release(format!(
            "symbolic links are not allowed: {relative}"
        )));
    }
    Ok(file)
}

fn read_bounded(path: &Path, maximum: u64, label: &str) -> Result<Vec<u8>, ReleaseError> {
    let length = fs::metadata(path).map_err(|error| io_error(&error))?.len();
    if length == 0 || length > maximum {
        return Err(resource_limit(format!("{label} has an invalid size")));
    }
    let bytes = fs::read(path).map_err(|error| io_error(&error))?;
    if bytes.is_empty() || u64::try_from(bytes.len()).unwrap_or(u64::MAX) > maximum {
        return Err(resource_limit(format!("{label} exceeds its size limit")));
    }
    Ok(bytes)
}

fn write_synced(path: &Path, bytes: &[u8]) -> Result<(), ReleaseError> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| io_error(&error))?;
    }
    let mut file = File::create(path).map_err(|error| io_error(&error))?;
    file.write_all(bytes).map_err(|error| io_error(&error))?;
    file.flush().map_err(|error| io_error(&error))?;
    file.sync_all().map_err(|error| io_error(&error))
}

fn validate_resource_bytes(
    kind: ProjectResourceKind,
    bytes: &[u8],
    path: &str,
) -> Result<(), ReleaseError> {
    match kind {
        ProjectResourceKind::Image if !valid_image(bytes) => Err(ReleaseError::InvalidProject(
            format!("invalid image resource: {path}"),
        )),
        ProjectResourceKind::Image => Ok(()),
        ProjectResourceKind::GlyphDictionary => glyph_ocr::decode_dictionary(bytes)
            .map(|_| ())
            .map_err(|error| {
                ReleaseError::InvalidProject(format!("invalid dictionary {path}: {error:?}"))
            }),
    }
}

fn valid_image(bytes: &[u8]) -> bool {
    bytes.starts_with(b"\x89PNG\r\n\x1a\n")
        || bytes.starts_with(&[0xff, 0xd8, 0xff])
        || (bytes.len() >= 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP")
        || bytes.starts_with(b"GIF87a")
        || bytes.starts_with(b"GIF89a")
        || bytes.starts_with(b"BM")
}

fn valid_application_id(value: &str) -> bool {
    let segments = value.split('.').collect::<Vec<_>>();
    segments.len() >= 2
        && segments.iter().all(|segment| {
            let mut characters = segment.chars();
            characters
                .next()
                .is_some_and(|first| first.is_ascii_lowercase())
                && characters.all(|character| {
                    character.is_ascii_lowercase() || character.is_ascii_digit() || character == '_'
                })
        })
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
    matches!((parts.next(), parts.next()), (Some(first), Some(second)) if
        valid_part(first) && valid_part(second) && parts.all(valid_part))
}

fn valid_identity(value: &str, maximum: usize) -> bool {
    !value.is_empty()
        && value.len() <= maximum
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-')
        })
        && value
            .chars()
            .next()
            .is_some_and(|character| character.is_ascii_alphanumeric())
}

fn valid_display_name(value: &str) -> bool {
    !value.trim().is_empty() && value.chars().count() <= 128 && !value.chars().any(char::is_control)
}

fn valid_runtime_api(value: &str) -> bool {
    let mut parts = value.split('.');
    matches!((parts.next(), parts.next(), parts.next()), (Some(a), Some(b), None) if
        !a.is_empty() && !b.is_empty() && a.chars().all(|c| c.is_ascii_digit()) &&
        b.chars().all(|c| c.is_ascii_digit()))
}

fn runtime_api_compatible(value: &str) -> bool {
    let parse = |text: &str| {
        let (major, minor) = text.split_once('.')?;
        Some((major.parse::<u32>().ok()?, minor.parse::<u32>().ok()?))
    };
    matches!((parse(value), parse(SUPPORTED_RUNTIME_API)),
        (Some((required_major, required_minor)), Some((major, minor))) if
            required_major == major && required_minor <= minor)
}

fn valid_relative_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= 512
        && !path.contains('\\')
        && !path.contains('\0')
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

fn valid_resource_path(kind: ProjectResourceKind, path: &str) -> bool {
    valid_relative_path(path)
        && match kind {
            ProjectResourceKind::Image => path.starts_with("assets/images/"),
            ProjectResourceKind::GlyphDictionary => {
                path.starts_with("dictionaries/") && path.ends_with(".asglyph")
            }
        }
}

fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_generation_id(value: &str) -> bool {
    value.len() == 36
        && value.starts_with("gen-")
        && value[4..]
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn parse_resource_kind(value: &str) -> Result<ProjectResourceKind, ReleaseError> {
    match value {
        "image" => Ok(ProjectResourceKind::Image),
        "glyphDictionary" => Ok(ProjectResourceKind::GlyphDictionary),
        _ => Err(invalid_release("unknown release resource kind")),
    }
}

const fn resource_limit_for(kind: ProjectResourceKind) -> u64 {
    match kind {
        ProjectResourceKind::Image => MAX_IMAGE_BYTES,
        ProjectResourceKind::GlyphDictionary => MAX_DICTIONARY_BYTES,
    }
}

const fn resource_kind_name(kind: ProjectResourceKind) -> &'static str {
    match kind {
        ProjectResourceKind::Image => "image",
        ProjectResourceKind::GlyphDictionary => "glyphDictionary",
    }
}

const fn source_mode_name(mode: ProjectSourceMode) -> &'static str {
    match mode {
        ProjectSourceMode::Lua => "lua",
        ProjectSourceMode::Visual => "visual",
    }
}

const fn scale_mode_name(mode: ScaleMode) -> &'static str {
    match mode {
        ScaleMode::Letterbox => "letterbox",
        ScaleMode::Crop => "crop",
        ScaleMode::Stretch => "stretch",
    }
}

const fn orientation_name(policy: OrientationPolicy) -> &'static str {
    match policy {
        OrientationPolicy::Follow => "follow",
        OrientationPolicy::Portrait => "portrait",
        OrientationPolicy::Landscape => "landscape",
    }
}

fn sha256(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn timestamp_nonce() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_nanos())
}

fn io_error(error: &std::io::Error) -> ReleaseError {
    ReleaseError::Io(error.to_string())
}

fn invalid_release(message: impl Into<String>) -> ReleaseError {
    ReleaseError::InvalidRelease(message.into())
}

fn resource_limit(message: impl Into<String>) -> ReleaseError {
    ReleaseError::ResourceLimit(message.into())
}

#[cfg(test)]
mod tests {
    use super::{prepare_project, verify_bundle, PrepareOptions, ReleaseError};
    use std::fs;
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temporary_root(label: &str) -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("autoscript-release-{label}-{nonce}"));
        fs::create_dir(&root).expect("temporary root");
        root
    }

    fn write_lua_project(root: &std::path::Path) {
        fs::write(
            root.join("project.json"),
            r#"{
  "formatVersion": 2,
  "flowSchemaVersion": 1,
  "runtimeApi": "1.5",
  "projectId": "release-test",
  "name": "Release Test",
  "sourceMode": "lua",
  "entryPoint": "main.lua",
  "flows": [],
  "resources": [],
  "capabilities": ["vision.pixel", "core.task"],
  "design": {"width": 720, "height": 1280, "scaleMode": "letterbox", "orientationPolicy": "follow"},
  "runnerUi": {"description":"Run options","fields":[{"id":"delayMs","label":"Delay","kind":"integer","required":true,"initialValue":10,"minimum":0,"maximum":1000,"options":[]}]}
}"#,
        )
        .expect("manifest");
        fs::write(
            root.join("main.lua"),
            "return function() Task.sleep(1) end\n",
        )
        .expect("Lua");
    }

    fn write_visual_project(root: &std::path::Path) {
        fs::create_dir_all(root.join("visual/flows")).expect("Flow directory");
        fs::write(
            root.join("project.json"),
            r#"{
  "formatVersion": 2,
  "flowSchemaVersion": 1,
  "runtimeApi": "1.5",
  "projectId": "visual-release-test",
  "name": "Visual Release",
  "sourceMode": "visual",
  "entryFlowId": "main",
  "flows": [{"flowId":"main","path":"visual/flows/main.jsonl","rootBlockId":"root","params":[],"returns":null}],
  "resources": [],
  "capabilities": ["core.task"],
  "design": {"width": 720, "height": 1280, "scaleMode": "letterbox", "orientationPolicy": "follow"}
}"#,
        )
        .expect("manifest");
        fs::write(
            root.join("visual/flows/main.jsonl"),
            concat!(
                r#"{"flowSchemaVersion":1,"nodeId":"node","blockId":"root","parentId":null,"orderKey":"a0","kind":"task.noop","nodeVersion":1,"depth":0,"args":{}}"#,
                "\n",
            ),
        )
        .expect("Flow");
    }

    fn options() -> PrepareOptions {
        PrepareOptions {
            application_id: "com.autoscript.release_test".to_owned(),
            version_code: 7,
            version_name: "1.2.3".to_owned(),
        }
    }

    #[test]
    fn prepares_deterministic_verified_release_without_editor_files() {
        let root = temporary_root("prepare");
        let project = root.join("project");
        fs::create_dir(&project).expect("project");
        write_lua_project(&project);
        fs::create_dir(project.join("generated")).expect("generated");
        fs::write(project.join("generated/stale"), "not released").expect("stale");
        let first = root.join("first");
        let second = root.join("second");

        let first_manifest = prepare_project(&project, &first, &options()).expect("first release");
        let second_manifest =
            prepare_project(&project, &second, &options()).expect("second release");

        assert_eq!(first_manifest.release_id, second_manifest.release_id);
        assert_eq!(
            first_manifest.capabilities,
            vec!["core.task".to_owned(), "vision.pixel".to_owned()]
        );
        assert_eq!(first_manifest.format_version, 3);
        assert_eq!(
            first_manifest.runner_ui.as_ref().map(|ui| ui.fields.len()),
            Some(1)
        );
        assert_eq!(verify_bundle(&first).expect("verify"), first_manifest);
        assert!(!first.join("generated").exists());
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn verification_rejects_payload_tampering_and_extra_files() {
        let root = temporary_root("tamper");
        let project = root.join("project");
        fs::create_dir(&project).expect("project");
        write_lua_project(&project);
        let output = root.join("release");
        prepare_project(&project, &output, &options()).expect("release");

        fs::write(output.join("payload/main.lua"), "return function() end\n").expect("tamper");
        assert!(matches!(
            verify_bundle(&output),
            Err(ReleaseError::InvalidRelease(_))
        ));
        fs::write(output.join("extra.txt"), "extra").expect("extra");
        assert!(matches!(
            verify_bundle(&output),
            Err(ReleaseError::InvalidRelease(_))
        ));
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn verification_rejects_capability_tampering() {
        let root = temporary_root("capability-tamper");
        let project = root.join("project");
        fs::create_dir(&project).expect("project");
        write_lua_project(&project);
        let output = root.join("release");
        prepare_project(&project, &output, &options()).expect("release");
        let manifest_path = output.join("release.json");
        let mut manifest: serde_json::Value =
            serde_json::from_slice(&fs::read(&manifest_path).expect("manifest")).expect("JSON");
        manifest["capabilities"] = serde_json::json!(["core.task"]);
        fs::write(
            &manifest_path,
            serde_json::to_vec_pretty(&manifest).expect("serialized manifest"),
        )
        .expect("tamper capabilities");

        assert!(matches!(
            verify_bundle(&output),
            Err(ReleaseError::InvalidRelease(_))
        ));
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn visual_release_is_recompiled_from_authoritative_flow() {
        let root = temporary_root("visual");
        let project = root.join("project");
        fs::create_dir(&project).expect("project");
        write_visual_project(&project);
        let output = root.join("release");

        let release = prepare_project(&project, &output, &options()).expect("visual release");

        assert_eq!(release.source_mode, "visual");
        assert!(release.source_map.is_some());
        assert!(output.join("payload/source-map.json").is_file());
        let lua = fs::read_to_string(output.join("payload/main.lua")).expect("generated Lua");
        assert!(lua.contains("generationId"));
        assert_eq!(verify_bundle(&output).expect("verify"), release);
        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn refuses_overwrite_and_invalid_application_identity() {
        let root = temporary_root("options");
        let project = root.join("project");
        fs::create_dir(&project).expect("project");
        write_lua_project(&project);
        let output = root.join("release");
        prepare_project(&project, &output, &options()).expect("release");
        assert!(matches!(
            prepare_project(&project, &output, &options()),
            Err(ReleaseError::OutputExists(_))
        ));
        let invalid = PrepareOptions {
            application_id: "Bad Id".to_owned(),
            ..options()
        };
        assert!(matches!(
            prepare_project(&project, &root.join("invalid"), &invalid),
            Err(ReleaseError::InvalidOption(_))
        ));
        fs::remove_dir_all(root).expect("cleanup");
    }
}
