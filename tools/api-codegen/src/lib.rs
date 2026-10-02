use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

pub const GENERATOR_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionMode {
    RustSync,
    HostSync,
    AsyncTask,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CancelMode {
    None,
    DiscardResult,
    Cooperative,
    AbortSafe,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ParameterSpec {
    pub name: String,
    #[serde(rename = "type")]
    pub type_name: String,
    pub required: bool,
    pub summary: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReturnSpec {
    #[serde(rename = "type")]
    pub type_name: String,
    pub nullable: bool,
    pub summary: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApiFunction {
    pub schema_version: u32,
    pub opcode: u32,
    pub name: String,
    pub since: String,
    pub capability: String,
    pub execution: ExecutionMode,
    pub cancel_mode: CancelMode,
    pub side_effect: String,
    pub timeout_ms: Option<u64>,
    pub resource_ownership: String,
    pub summary: String,
    pub params: Vec<ParameterSpec>,
    pub returns: ReturnSpec,
    pub legacy_aliases: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockCategory {
    Flow,
    Task,
    Control,
    Variable,
    Screen,
    Vision,
    Ocr,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockPropertyEditor {
    Boolean,
    Integer,
    IntegerEnum,
    Number,
    String,
    Scalar,
    Point,
    Rect,
    Color,
    MultiColorSamples,
    LegacyPattern,
    LegacyFixedPattern,
    LegacyColorGroup,
    LegacyRegion,
    FlowReference,
    FlowArguments,
    Enum,
    Resource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BlockResourceKind {
    Image,
    GlyphDictionary,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BlockPropertySpec {
    pub path: String,
    pub label: String,
    pub editor: BlockPropertyEditor,
    pub required: bool,
    pub default_value: Option<String>,
    pub resource_kind: Option<BlockResourceKind>,
    pub depends_on: Option<String>,
    #[serde(default)]
    pub choices: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BlockMigrationSpec {
    pub from_version: u32,
    pub to_version: u32,
    pub rename_arguments: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct BlockDefinition {
    pub schema_version: u32,
    pub kind: String,
    pub node_version: u32,
    pub node_schema_id: String,
    pub title: String,
    pub category: BlockCategory,
    pub summary: String,
    pub search_terms: Vec<String>,
    pub required_capabilities: Vec<String>,
    pub child_blocks: Vec<String>,
    pub properties: Vec<BlockPropertySpec>,
    pub migrations: Vec<BlockMigrationSpec>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct ContractSnapshot<'a> {
    schema_version: u32,
    generator_version: &'a str,
    functions: &'a [ApiFunction],
}

/// Loads, validates and deterministically sorts all function declarations.
///
/// # Errors
///
/// Returns an error for unreadable directories/files, invalid JSON, unknown fields, duplicate
/// names/opcodes, malformed identifiers, invalid parameters or inconsistent execution metadata.
pub fn load_catalog(functions_dir: &Path) -> Result<Vec<ApiFunction>, String> {
    let entries = fs::read_dir(functions_dir)
        .map_err(|error| format!("cannot read {}: {error}", functions_dir.display()))?;
    let mut paths = Vec::new();
    for entry in entries {
        let path = entry
            .map_err(|error| format!("cannot enumerate {}: {error}", functions_dir.display()))?
            .path();
        if path
            .extension()
            .is_some_and(|extension| extension == "json")
        {
            paths.push(path);
        }
    }
    paths.sort();

    let mut functions = Vec::with_capacity(paths.len());
    for path in paths {
        let bytes =
            fs::read(&path).map_err(|error| format!("cannot read {}: {error}", path.display()))?;
        let function: ApiFunction = serde_json::from_slice(&bytes)
            .map_err(|error| format!("invalid {}: {error}", path.display()))?;
        functions.push(function);
    }
    validate_catalog(&functions)?;
    functions.sort_by_key(|function| function.opcode);
    Ok(functions)
}

/// Loads and validates the Studio block catalog in deterministic kind order.
///
/// # Errors
///
/// Returns an error for malformed declarations, duplicate kinds, invalid property contracts or
/// non-contiguous migration chains.
pub fn load_block_catalog(blocks_dir: &Path) -> Result<Vec<BlockDefinition>, String> {
    let entries = fs::read_dir(blocks_dir)
        .map_err(|error| format!("cannot read {}: {error}", blocks_dir.display()))?;
    let mut paths = entries
        .map(|entry| entry.map(|value| value.path()))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|error| format!("cannot enumerate {}: {error}", blocks_dir.display()))?;
    paths.retain(|path| {
        path.extension()
            .is_some_and(|extension| extension == "json")
    });
    paths.sort();
    let mut blocks = paths
        .into_iter()
        .map(|path| {
            let bytes = fs::read(&path)
                .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
            serde_json::from_slice::<BlockDefinition>(&bytes)
                .map_err(|error| format!("invalid {}: {error}", path.display()))
        })
        .collect::<Result<Vec<_>, _>>()?;
    validate_block_catalog(&blocks)?;
    blocks.sort_by(|left, right| left.kind.as_bytes().cmp(right.kind.as_bytes()));
    Ok(blocks)
}

fn validate_block_catalog(blocks: &[BlockDefinition]) -> Result<(), String> {
    if blocks.is_empty() {
        return Err("block catalog must contain at least one definition".into());
    }
    let mut kinds = HashSet::with_capacity(blocks.len());
    for block in blocks {
        if block.schema_version != 1
            || block.node_version == 0
            || !valid_block_kind(&block.kind)
            || !kinds.insert(block.kind.as_str())
            || block.title.is_empty()
            || block.summary.is_empty()
            || !block
                .node_schema_id
                .starts_with("https://autoscript.local/schema/node/")
        {
            return Err(format!(
                "{} has invalid or duplicate block metadata",
                block.kind
            ));
        }
        if has_duplicates(&block.search_terms)
            || has_duplicates(&block.required_capabilities)
            || has_duplicates(&block.child_blocks)
            || block
                .required_capabilities
                .iter()
                .any(|capability| !valid_dotted_lower(capability))
        {
            return Err(format!("{} contains duplicate catalog values", block.kind));
        }
        let mut property_paths = HashSet::new();
        for property in &block.properties {
            let is_choice_editor = matches!(
                property.editor,
                BlockPropertyEditor::Enum | BlockPropertyEditor::IntegerEnum
            );
            if !valid_identifier(&property.path)
                || property.label.is_empty()
                || !property_paths.insert(property.path.as_str())
                || property
                    .depends_on
                    .as_deref()
                    .is_some_and(|dependency| !valid_identifier(dependency))
                || (is_choice_editor == property.choices.is_empty())
                || (property.editor == BlockPropertyEditor::IntegerEnum
                    && property
                        .choices
                        .iter()
                        .any(|choice| choice.parse::<i64>().is_err()))
                || ((property.editor == BlockPropertyEditor::Resource)
                    != property.resource_kind.is_some())
            {
                return Err(format!(
                    "{} has invalid property {}",
                    block.kind, property.path
                ));
            }
        }
        for property in &block.properties {
            if property
                .depends_on
                .as_deref()
                .is_some_and(|dependency| !property_paths.contains(dependency))
            {
                return Err(format!("{} property dependency is unknown", block.kind));
            }
        }
        let mut migrations = block.migrations.clone();
        migrations.sort_by_key(|migration| migration.from_version);
        for migration in &migrations {
            if migration.to_version != migration.from_version.saturating_add(1)
                || migration.to_version > block.node_version
                || migration.rename_arguments.iter().any(|(from, to)| {
                    !valid_identifier(from) || !valid_identifier(to) || from == to
                })
            {
                return Err(format!("{} has invalid migration", block.kind));
            }
        }
        if migrations
            .windows(2)
            .any(|pair| pair[0].to_version != pair[1].from_version)
        {
            return Err(format!("{} has a migration gap", block.kind));
        }
    }
    Ok(())
}

fn valid_block_kind(kind: &str) -> bool {
    valid_dotted_lower(kind)
}

fn valid_dotted_lower(value: &str) -> bool {
    let parts = value.split('.').collect::<Vec<_>>();
    parts.len() >= 2
        && parts.iter().all(|part| {
            part.chars()
                .next()
                .is_some_and(|character| character.is_ascii_lowercase())
                && part
                    .chars()
                    .all(|character| character.is_ascii_lowercase() || character.is_ascii_digit())
        })
}

fn has_duplicates(values: &[String]) -> bool {
    let mut seen = HashSet::with_capacity(values.len());
    values
        .iter()
        .any(|value| value.is_empty() || !seen.insert(value))
}

fn validate_catalog(functions: &[ApiFunction]) -> Result<(), String> {
    if functions.is_empty() {
        return Err("API catalog must contain at least one function".into());
    }
    let mut names = HashSet::with_capacity(functions.len());
    let mut opcodes = HashSet::with_capacity(functions.len());
    for function in functions {
        if function.schema_version != 1 {
            return Err(format!("{} uses unsupported schemaVersion", function.name));
        }
        if function.opcode == 0 || !opcodes.insert(function.opcode) {
            return Err(format!(
                "{} has zero or duplicate opcode {}",
                function.name, function.opcode
            ));
        }
        if !names.insert(function.name.as_str()) || !valid_api_name(&function.name) {
            return Err(format!(
                "{} has an invalid or duplicate API name",
                function.name
            ));
        }
        if function.capability.is_empty()
            || function.summary.is_empty()
            || function.since.is_empty()
        {
            return Err(format!(
                "{} has incomplete contract metadata",
                function.name
            ));
        }
        if function.execution == ExecutionMode::HostSync && function.timeout_ms.is_none() {
            return Err(format!("{} host_sync requires timeoutMs", function.name));
        }
        if function.execution == ExecutionMode::AsyncTask
            && function.cancel_mode == CancelMode::None
        {
            return Err(format!(
                "{} async_task must declare cancellation",
                function.name
            ));
        }
        let mut parameters = HashSet::with_capacity(function.params.len());
        for parameter in &function.params {
            if !valid_identifier(&parameter.name)
                || parameter.type_name.is_empty()
                || parameter.summary.is_empty()
                || !parameters.insert(parameter.name.as_str())
            {
                return Err(format!(
                    "{} has an invalid parameter {}",
                    function.name, parameter.name
                ));
            }
        }
    }
    Ok(())
}

fn valid_api_name(name: &str) -> bool {
    let Some((namespace, function)) = name.split_once('.') else {
        return false;
    };
    !function.contains('.')
        && namespace.chars().next().is_some_and(char::is_uppercase)
        && valid_identifier(namespace)
        && valid_identifier(function)
}

fn valid_identifier(value: &str) -> bool {
    let mut chars = value.chars();
    chars
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic() || first == '_')
        && chars.all(|character| character.is_ascii_alphanumeric() || character == '_')
}

/// Renders the reviewable JSON contract snapshot using stable field and opcode ordering.
///
/// # Panics
///
/// Serialization only covers validated, in-memory schema structs and is expected to be infallible.
#[must_use]
pub fn render_snapshot(functions: &[ApiFunction]) -> String {
    let snapshot = ContractSnapshot {
        schema_version: 1,
        generator_version: GENERATOR_VERSION,
        functions,
    };
    let mut output = serde_json::to_string_pretty(&snapshot).expect("serializing API snapshot");
    output.push('\n');
    output
}

/// Renders the generated Rust registration table included by `script-api` at build time.
#[must_use]
pub fn render_rust(functions: &[ApiFunction]) -> String {
    let mut output = String::from("// @generated by api-codegen; do not edit.\n\n");
    output.push_str("pub static API_CONTRACTS: &[ApiContract] = &[\n");
    for function in functions {
        let _ = writeln!(output, "    ApiContract {{");
        let _ = writeln!(output, "        opcode: {},", function.opcode);
        let _ = writeln!(output, "        name: {:?},", function.name);
        let _ = writeln!(output, "        since: {:?},", function.since);
        let _ = writeln!(output, "        capability: {:?},", function.capability);
        let _ = writeln!(
            output,
            "        execution: ExecutionMode::{:?},",
            function.execution
        );
        let _ = writeln!(
            output,
            "        cancel_mode: CancelMode::{:?},",
            function.cancel_mode
        );
        let _ = writeln!(output, "        side_effect: {:?},", function.side_effect);
        let timeout = function
            .timeout_ms
            .map_or_else(|| "None".into(), |value| format!("Some({value})"));
        let _ = writeln!(output, "        timeout_ms: {timeout},");
        let _ = writeln!(
            output,
            "        resource_ownership: {:?},",
            function.resource_ownership
        );
        let _ = writeln!(
            output,
            "        return_type: {:?},",
            function.returns.type_name
        );
        output.push_str("        params: &[\n");
        for parameter in &function.params {
            let _ = writeln!(
                output,
                "            ApiParameter {{ name: {:?}, type_name: {:?}, required: {} }},",
                parameter.name, parameter.type_name, parameter.required
            );
        }
        output.push_str("        ],\n    },\n");
    }
    output.push_str("];\n");
    output
}

/// Renders EmmyLua-compatible declarations for editor completion.
///
/// # Panics
///
/// Panics when passed a function name that has not been checked by [`load_catalog`].
#[must_use]
pub fn render_lua_stub(functions: &[ApiFunction]) -> String {
    let mut output = String::from("---@meta\n-- @generated by api-codegen; do not edit.\n\n");
    let mut declared_namespaces = HashSet::new();
    for function in functions {
        let (namespace, method) = function.name.split_once('.').expect("validated API name");
        if declared_namespaces.insert(namespace) {
            let _ = writeln!(output, "{namespace} = {namespace} or {{}}\n");
        }
        let _ = writeln!(output, "---{}", function.summary);
        for parameter in &function.params {
            let _ = writeln!(
                output,
                "---@param {} {} {}",
                parameter.name,
                lua_type(&parameter.type_name),
                parameter.summary
            );
        }
        if function.returns.type_name != "void" {
            let _ = writeln!(
                output,
                "---@return {} value {}",
                lua_type(&function.returns.type_name),
                function.returns.summary
            );
        }
        let params = function
            .params
            .iter()
            .map(|parameter| parameter.name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        let _ = writeln!(output, "function {namespace}.{method}({params}) end\n");
    }
    output
}

fn lua_type(type_name: &str) -> &str {
    match type_name {
        "integer" => "integer",
        "number" => "number",
        "boolean" => "boolean",
        "string" => "string",
        "void" => "nil",
        other => other,
    }
}

/// Renders a self-contained offline HTML API catalog.
#[must_use]
pub fn render_html(functions: &[ApiFunction]) -> String {
    let mut output = String::from("<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>AutoScript API</title><style>body{font:14px system-ui;margin:0;background:#f4f6f8;color:#1f2933}header{padding:14px 18px;background:#1976d2;color:#fff}main{max-width:980px;margin:auto;padding:12px}.api{background:#fff;border:1px solid #d6dbe1;border-radius:6px;margin:8px 0;padding:10px 12px}code{color:#0d47a1}table{border-collapse:collapse;width:100%}td,th{border-top:1px solid #e6e9ed;padding:5px;text-align:left}.meta{color:#52606d;font-size:12px}</style></head><body><header><strong>AutoScript 脚本能力目录</strong></header><main>\n");
    for function in functions {
        let _ = writeln!(output, "<section class=\"api\"><h3><code>{}</code></h3><p>{}</p><p class=\"meta\">opcode {} · since {} · {} · {:?}</p><table><tr><th>参数</th><th>类型</th><th>说明</th></tr>", html_escape(&function.name), html_escape(&function.summary), function.opcode, html_escape(&function.since), html_escape(&function.capability), function.execution);
        for parameter in &function.params {
            let _ = writeln!(
                output,
                "<tr><td><code>{}</code></td><td>{}</td><td>{}</td></tr>",
                html_escape(&parameter.name),
                html_escape(&parameter.type_name),
                html_escape(&parameter.summary)
            );
        }
        let _ = writeln!(
            output,
            "</table><p><b>返回：</b>{} — {}</p></section>",
            html_escape(&function.returns.type_name),
            html_escape(&function.returns.summary)
        );
    }
    output.push_str("</main></body></html>\n");
    output
}

/// Renders Kotlin wire contracts and typed request payloads for Android host dispatch.
#[must_use]
pub fn render_kotlin(functions: &[ApiFunction]) -> String {
    let mut output = String::from(
        "// @generated by api-codegen; do not edit.\n\
         package com.autoscript.runtime.api.generated\n\n\
         enum class ApiExecutionMode { RUST_SYNC, HOST_SYNC, ASYNC_TASK }\n\
         enum class ApiCancelMode { NONE, DISCARD_RESULT, COOPERATIVE, ABORT_SAFE }\n\n\
         data class ApiContract(\n\
             val opcode: Int,\n\
             val name: String,\n\
             val since: String,\n\
             val capability: String,\n\
             val execution: ApiExecutionMode,\n\
             val cancelMode: ApiCancelMode,\n\
             val sideEffect: String,\n\
             val timeoutMs: Long?,\n\
             val resourceOwnership: String,\n\
         )\n\n\
         sealed interface ApiRequest { val opcode: Int }\n\n",
    );
    output.push_str(
        "data class MultiColorSample(\n\
             val x: Long,\n\
             val y: Long,\n\
             val rgb: Long,\n\
             val tolerance: Long,\n\
         )\n\n",
    );

    for function in functions {
        let class_name = kotlin_request_name(&function.name);
        if function.params.is_empty() {
            let _ = writeln!(
                output,
                "data object {class_name} : ApiRequest {{ override val opcode: Int = {} }}\n",
                function.opcode
            );
        } else {
            let _ = writeln!(output, "data class {class_name}(");
            for parameter in &function.params {
                let nullable = if parameter.required { "" } else { "?" };
                let _ = writeln!(
                    output,
                    "    val {}: {}{},",
                    parameter.name,
                    kotlin_type(&parameter.type_name),
                    nullable
                );
            }
            let _ = writeln!(
                output,
                ") : ApiRequest {{ override val opcode: Int = {} }}\n",
                function.opcode
            );
        }
    }

    output.push_str("object GeneratedApiContracts {\n");
    for function in functions {
        let _ = writeln!(
            output,
            "    const val {}: Int = {}",
            kotlin_opcode_name(&function.name),
            function.opcode
        );
    }
    output.push_str("\n    val all: List<ApiContract> = listOf(\n");
    for function in functions {
        let timeout = function
            .timeout_ms
            .map_or_else(|| "null".into(), |value| format!("{value}L"));
        let _ = writeln!(
            output,
            "        ApiContract({}, {:?}, {:?}, {:?}, ApiExecutionMode.{}, ApiCancelMode.{}, {:?}, {}, {:?}),",
            function.opcode,
            function.name,
            function.since,
            function.capability,
            kotlin_execution(function.execution),
            kotlin_cancel(function.cancel_mode),
            function.side_effect,
            timeout,
            function.resource_ownership,
        );
    }
    output.push_str("    )\n}\n");
    output
}

/// Editor documentation stays in Studio, not in the Runner's API transport.
#[must_use]
pub fn render_function_docs_kotlin(functions: &[ApiFunction]) -> String {
    let mut output = String::from(
        "// @generated by api-codegen; do not edit.\n\
         package com.autoscript.studio.generated\n\n\
         data class FunctionParameterDoc(\n\
             val name: String, val type: String, val required: Boolean, val summary: String,\n\
         )\n\n\
         data class FunctionDoc(\n\
             val name: String, val since: String, val capability: String, val summary: String,\n\
             val parameters: List<FunctionParameterDoc>,\n\
             val returnType: String, val returnNullable: Boolean, val returnSummary: String,\n\
         )\n\n\
         object GeneratedFunctionDocumentation {\n\
             val all: List<FunctionDoc> = listOf(\n",
    );
    for function in functions {
        let _ = writeln!(
            output,
            "        FunctionDoc({}, {}, {}, {}, listOf(",
            kotlin_document_string(&function.name),
            kotlin_document_string(&function.since),
            kotlin_document_string(&function.capability),
            kotlin_document_string(&function.summary),
        );
        for parameter in &function.params {
            let _ = writeln!(
                output,
                "            FunctionParameterDoc({}, {}, {}, {}),",
                kotlin_document_string(&parameter.name),
                kotlin_document_string(&parameter.type_name),
                parameter.required,
                kotlin_document_string(&parameter.summary),
            );
        }
        let _ = writeln!(
            output,
            "        ), {}, {}, {}),",
            kotlin_document_string(&function.returns.type_name),
            function.returns.nullable,
            kotlin_document_string(&function.returns.summary),
        );
    }
    output.push_str("    )\n}\n");
    output
}

fn kotlin_document_string(value: &str) -> String {
    let mut output = String::from("\"");
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '$' => output.push_str("\\$"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            control if control.is_control() => {
                let _ = write!(output, "\\u{:04x}", u32::from(control));
            }
            other => output.push(other),
        }
    }
    output.push('"');
    output
}

/// Renders the Studio-only block catalog and property/migration contracts.
#[must_use]
pub fn render_block_kotlin(blocks: &[BlockDefinition]) -> String {
    let mut output = String::from(
        "// @generated by api-codegen; do not edit.\n\
         package com.autoscript.studio.generated\n\n\
         enum class BlockCategory { FLOW, TASK, CONTROL, VARIABLE, SCREEN, VISION, OCR }\n\
         enum class BlockPropertyEditor { BOOLEAN, INTEGER, INTEGER_ENUM, NUMBER, STRING, SCALAR, POINT, RECT, COLOR, MULTI_COLOR_SAMPLES, LEGACY_PATTERN, LEGACY_FIXED_PATTERN, LEGACY_COLOR_GROUP, LEGACY_REGION, FLOW_REFERENCE, FLOW_ARGUMENTS, ENUM, RESOURCE }\n\
         enum class BlockResourceKind { IMAGE, GLYPH_DICTIONARY }\n\n\
         data class BlockPropertyContract(\n\
             val path: String,\n\
             val label: String,\n\
             val editor: BlockPropertyEditor,\n\
             val required: Boolean,\n\
             val defaultValue: String?,\n\
             val resourceKind: BlockResourceKind?,\n\
             val dependsOn: String?,\n\
             val choices: List<String>,\n\
         )\n\n\
         data class BlockMigrationContract(\n\
             val fromVersion: Int,\n\
             val toVersion: Int,\n\
             val renameArguments: Map<String, String>,\n\
         )\n\n\
         data class BlockContract(\n\
             val kind: String,\n\
             val nodeVersion: Int,\n\
             val nodeSchemaId: String,\n\
             val title: String,\n\
             val category: BlockCategory,\n\
             val summary: String,\n\
             val searchTerms: List<String>,\n\
             val requiredCapabilities: Set<String>,\n\
             val childBlocks: List<String>,\n\
             val properties: List<BlockPropertyContract>,\n\
             val migrations: List<BlockMigrationContract>,\n\
         )\n\n\
         object GeneratedBlockCatalog {\n\
             val all: List<BlockContract> = listOf(\n",
    );
    for block in blocks {
        render_block_contract(&mut output, block);
    }
    output.push_str("    )\n}\n");
    output
}

fn render_block_contract(output: &mut String, block: &BlockDefinition) {
    let _ = writeln!(output, "        BlockContract(");
    let _ = writeln!(output, "            kind = {:?},", block.kind);
    let _ = writeln!(output, "            nodeVersion = {},", block.node_version);
    let _ = writeln!(
        output,
        "            nodeSchemaId = {:?},",
        block.node_schema_id
    );
    let _ = writeln!(output, "            title = {:?},", block.title);
    let _ = writeln!(
        output,
        "            category = BlockCategory.{},",
        block_category_name(block.category)
    );
    let _ = writeln!(output, "            summary = {:?},", block.summary);
    let _ = writeln!(
        output,
        "            searchTerms = {},",
        kotlin_string_collection("listOf", &block.search_terms)
    );
    let _ = writeln!(
        output,
        "            requiredCapabilities = {},",
        kotlin_string_collection("setOf", &block.required_capabilities)
    );
    let _ = writeln!(
        output,
        "            childBlocks = {},",
        kotlin_string_collection("listOf", &block.child_blocks)
    );
    output.push_str("            properties = listOf(\n");
    for property in &block.properties {
        let _ = writeln!(
            output,
            "                BlockPropertyContract({:?}, {:?}, BlockPropertyEditor.{}, {}, {}, {}, {}, {}),",
            property.path,
            property.label,
            block_property_editor_name(property.editor),
            property.required,
            property
                .default_value
                .as_ref()
                .map_or_else(|| "null".into(), |value| format!("{value:?}")),
            property.resource_kind.map_or_else(
                || "null".into(),
                |value| format!("BlockResourceKind.{}", block_resource_kind_name(value)),
            ),
            property
                .depends_on
                .as_ref()
                .map_or_else(|| "null".into(), |value| format!("{value:?}")),
            kotlin_string_collection("listOf", &property.choices),
        );
    }
    output.push_str("            ),\n            migrations = listOf(\n");
    for migration in &block.migrations {
        let mappings = render_renamed_arguments(&migration.rename_arguments);
        let _ = writeln!(
            output,
            "                BlockMigrationContract({}, {}, {}),",
            migration.from_version, migration.to_version, mappings,
        );
    }
    output.push_str("            ),\n        ),\n");
}

fn render_renamed_arguments(arguments: &BTreeMap<String, String>) -> String {
    if arguments.is_empty() {
        return "emptyMap()".to_owned();
    }
    let entries = arguments
        .iter()
        .map(|(from, to)| format!("{from:?} to {to:?}"))
        .collect::<Vec<_>>()
        .join(", ");
    format!("mapOf({entries})")
}

fn kotlin_string_collection(function: &str, values: &[String]) -> String {
    if values.is_empty() {
        return format!(
            "empty{}()",
            if function == "setOf" { "Set" } else { "List" }
        );
    }
    format!(
        "{function}({})",
        values
            .iter()
            .map(|value| format!("{value:?}"))
            .collect::<Vec<_>>()
            .join(", ")
    )
}

const fn block_category_name(category: BlockCategory) -> &'static str {
    match category {
        BlockCategory::Flow => "FLOW",
        BlockCategory::Task => "TASK",
        BlockCategory::Control => "CONTROL",
        BlockCategory::Variable => "VARIABLE",
        BlockCategory::Screen => "SCREEN",
        BlockCategory::Vision => "VISION",
        BlockCategory::Ocr => "OCR",
    }
}

const fn block_property_editor_name(editor: BlockPropertyEditor) -> &'static str {
    match editor {
        BlockPropertyEditor::Boolean => "BOOLEAN",
        BlockPropertyEditor::Integer => "INTEGER",
        BlockPropertyEditor::IntegerEnum => "INTEGER_ENUM",
        BlockPropertyEditor::Number => "NUMBER",
        BlockPropertyEditor::String => "STRING",
        BlockPropertyEditor::Scalar => "SCALAR",
        BlockPropertyEditor::Point => "POINT",
        BlockPropertyEditor::Rect => "RECT",
        BlockPropertyEditor::Color => "COLOR",
        BlockPropertyEditor::MultiColorSamples => "MULTI_COLOR_SAMPLES",
        BlockPropertyEditor::LegacyPattern => "LEGACY_PATTERN",
        BlockPropertyEditor::LegacyFixedPattern => "LEGACY_FIXED_PATTERN",
        BlockPropertyEditor::LegacyColorGroup => "LEGACY_COLOR_GROUP",
        BlockPropertyEditor::LegacyRegion => "LEGACY_REGION",
        BlockPropertyEditor::FlowReference => "FLOW_REFERENCE",
        BlockPropertyEditor::FlowArguments => "FLOW_ARGUMENTS",
        BlockPropertyEditor::Enum => "ENUM",
        BlockPropertyEditor::Resource => "RESOURCE",
    }
}

const fn block_resource_kind_name(kind: BlockResourceKind) -> &'static str {
    match kind {
        BlockResourceKind::Image => "IMAGE",
        BlockResourceKind::GlyphDictionary => "GLYPH_DICTIONARY",
    }
}

fn kotlin_request_name(api_name: &str) -> String {
    let mut output = String::new();
    for part in api_name.split('.') {
        let mut chars = part.chars();
        if let Some(first) = chars.next() {
            output.extend(first.to_uppercase());
            output.extend(chars);
        }
    }
    output.push_str("Request");
    output
}

fn kotlin_opcode_name(api_name: &str) -> String {
    let mut output = String::from("OP_");
    for (index, character) in api_name.chars().enumerate() {
        if character == '.' {
            output.push('_');
        } else if character.is_ascii_uppercase() && index > 0 {
            let previous = api_name.as_bytes()[index - 1] as char;
            if previous != '.' && previous != '_' {
                output.push('_');
            }
            output.push(character);
        } else {
            output.push(character.to_ascii_uppercase());
        }
    }
    output
}

fn kotlin_type(type_name: &str) -> String {
    if let Some(item_type) = type_name.strip_suffix("[]") {
        return format!("List<{}>", kotlin_type(item_type));
    }
    match type_name {
        // Lua callbacks stay inside the VM; the typed boundary carries a registry ID only.
        "integer" | "function" => "Long".to_owned(),
        "number" => "Double".to_owned(),
        "boolean" => "Boolean".to_owned(),
        "string" => "String".to_owned(),
        other => other.to_owned(),
    }
}

const fn kotlin_execution(mode: ExecutionMode) -> &'static str {
    match mode {
        ExecutionMode::RustSync => "RUST_SYNC",
        ExecutionMode::HostSync => "HOST_SYNC",
        ExecutionMode::AsyncTask => "ASYNC_TASK",
    }
}

const fn kotlin_cancel(mode: CancelMode) -> &'static str {
    match mode {
        CancelMode::None => "NONE",
        CancelMode::DiscardResult => "DISCARD_RESULT",
        CancelMode::Cooperative => "COOPERATIVE",
        CancelMode::AbortSafe => "ABORT_SAFE",
    }
}

fn html_escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// Returns every reviewable generated output and its repository-relative path.
#[must_use]
pub fn reviewable_outputs(root: &Path, functions: &[ApiFunction]) -> Vec<(PathBuf, String)> {
    vec![
        (
            root.join("schema/api-schema/snapshot/contract.json"),
            render_snapshot(functions),
        ),
        (
            root.join("schema/api-schema/stubs/autoscript.lua"),
            render_lua_stub(functions),
        ),
        (
            root.join("docs-site/generated/api.html"),
            render_html(functions),
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::{
        render_block_kotlin, render_function_docs_kotlin, render_html, render_kotlin,
        validate_block_catalog, validate_catalog, ApiFunction, BlockCategory, BlockDefinition,
        BlockMigrationSpec, BlockPropertyEditor, BlockPropertySpec, CancelMode, ExecutionMode,
        ParameterSpec, ReturnSpec,
    };
    use std::collections::BTreeMap;

    fn function(name: &str, opcode: u32) -> ApiFunction {
        ApiFunction {
            schema_version: 1,
            opcode,
            name: name.into(),
            since: "1.0".into(),
            capability: "core.test".into(),
            execution: ExecutionMode::RustSync,
            cancel_mode: CancelMode::None,
            side_effect: "none".into(),
            timeout_ms: None,
            resource_ownership: "none".into(),
            summary: "test <contract>".into(),
            params: vec![ParameterSpec {
                name: "value".into(),
                type_name: "string".into(),
                required: true,
                summary: "value".into(),
            }],
            returns: ReturnSpec {
                type_name: "void".into(),
                nullable: false,
                summary: "none".into(),
            },
            legacy_aliases: Vec::new(),
        }
    }

    #[test]
    fn duplicate_opcodes_are_rejected() {
        let functions = [function("Test.first", 7), function("Test.second", 7)];
        assert!(validate_catalog(&functions)
            .expect_err("duplicate opcode must fail")
            .contains("duplicate opcode"));
    }

    #[test]
    fn host_calls_require_a_timeout() {
        let mut host = function("Test.host", 8);
        host.execution = ExecutionMode::HostSync;
        assert!(validate_catalog(&[host])
            .expect_err("host call without timeout must fail")
            .contains("requires timeoutMs"));
    }

    #[test]
    fn html_output_escapes_schema_text() {
        let html = render_html(&[function("Test.escape", 9)]);
        assert!(html.contains("test &lt;contract&gt;"));
        assert!(!html.contains("test <contract>"));
    }

    #[test]
    fn kotlin_output_contains_typed_request_and_stable_opcode() {
        let kotlin = render_kotlin(&[function("Math.distance", 1000)]);
        assert!(kotlin.contains("data class MathDistanceRequest("));
        assert!(kotlin.contains("val value: String"));
        assert!(kotlin.contains("const val OP_MATH_DISTANCE: Int = 1000"));
        assert!(kotlin.contains("ApiExecutionMode.RUST_SYNC"));
        assert!(!kotlin.contains("ApiExecutionMode::"));
    }

    #[test]
    fn kotlin_callback_argument_is_an_id_not_a_lua_function() {
        let mut api = function("Task.spawn", 3001);
        api.params[0].type_name = "function".into();
        let output = render_kotlin(&[api]);
        assert!(output.contains("val value: Long"));
        assert!(!output.contains(": function"));
    }

    #[test]
    fn studio_function_docs_preserve_schema_details_and_escape_interpolation() {
        let mut api = function("Screen.sample", 5009);
        api.summary = "说明 \"$frame\"\n第二行\u{000c}\0".into();
        api.params[0].required = false;
        api.returns.nullable = true;
        let output = render_function_docs_kotlin(&[api]);
        assert!(output.contains("package com.autoscript.studio.generated"));
        assert!(output.contains("FunctionParameterDoc(\"value\", \"string\", false, \"value\")"));
        assert!(output.contains("\\\"\\$frame\\\"\\n第二行"));
        assert!(output.contains("\\u000c\\u0000"));
        assert!(output.contains("), \"void\", true, \"none\")"));
        assert!(!output.contains("runtime.api"));
    }

    fn block() -> BlockDefinition {
        BlockDefinition {
            schema_version: 1,
            kind: "task.sample".into(),
            node_version: 2,
            node_schema_id: "https://autoscript.local/schema/node/task.sample/2/schema.json".into(),
            title: "示例".into(),
            category: BlockCategory::Task,
            summary: "测试积木".into(),
            search_terms: vec!["sample".into()],
            required_capabilities: vec!["core.task".into()],
            child_blocks: vec!["body".into()],
            properties: vec![BlockPropertySpec {
                path: "value".into(),
                label: "值".into(),
                editor: BlockPropertyEditor::Integer,
                required: true,
                default_value: Some("7".into()),
                resource_kind: None,
                depends_on: None,
                choices: Vec::new(),
            }],
            migrations: vec![BlockMigrationSpec {
                from_version: 1,
                to_version: 2,
                rename_arguments: BTreeMap::from([("old".into(), "value".into())]),
            }],
        }
    }

    #[test]
    fn block_catalog_generates_studio_contract_and_migration() {
        let block = block();
        validate_block_catalog(std::slice::from_ref(&block)).expect("valid catalog");
        let kotlin = render_block_kotlin(&[block]);
        assert!(kotlin.contains("kind = \"task.sample\""));
        assert!(kotlin.contains("BlockPropertyEditor.INTEGER"));
        assert!(kotlin.contains("mapOf(\"old\" to \"value\")"));
    }

    #[test]
    fn integer_enum_requires_numeric_choices_and_generates_typed_editor() {
        let mut block = block();
        block.properties[0].editor = BlockPropertyEditor::IntegerEnum;
        block.properties[0].choices = vec!["0".into(), "1".into()];
        validate_block_catalog(std::slice::from_ref(&block)).expect("valid integer enum");
        assert!(render_block_kotlin(std::slice::from_ref(&block))
            .contains("BlockPropertyEditor.INTEGER_ENUM"));

        block.properties[0].choices = vec!["not-an-integer".into()];
        assert!(validate_block_catalog(&[block])
            .expect_err("non-numeric integer enum choice must fail")
            .contains("invalid property"));
    }

    #[test]
    fn block_catalog_rejects_unknown_property_dependency() {
        let mut block = block();
        block.properties[0].depends_on = Some("missing".into());
        assert!(validate_block_catalog(&[block])
            .expect_err("unknown dependency")
            .contains("dependency"));
    }
}
