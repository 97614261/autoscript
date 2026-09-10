use crate::model::{CompileError, FlowSource};
use flow_ir::{
    validate_project_documents, FlowDocument, ProjectFlow, ProjectManifest, ProjectResourceKind,
    ValueType,
};
use serde::Deserialize;
use serde_json::Value;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FlowCallArgs {
    pub target_flow_id: String,
    pub arguments: BTreeMap<String, Value>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SleepArgs {
    pub milliseconds: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct TapArgs {
    pub x: i32,
    pub y: i32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SwipeArgs {
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
    pub duration_ms: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct KeyEventArgs {
    pub key_code: u32,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) enum ComparisonOperator {
    Equals,
    NotEquals,
    LessThan,
    LessOrEqual,
    GreaterThan,
    GreaterOrEqual,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ComparisonArgs {
    pub variable: String,
    pub operator: ComparisonOperator,
    pub value: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RepeatArgs {
    pub times: u32,
    #[serde(default)]
    pub index_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct WhileArgs {
    pub variable: String,
    pub operator: ComparisonOperator,
    pub value: Value,
    pub max_iterations: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct VariableSetArgs {
    pub name: String,
    pub value: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct VariableCopyArgs {
    pub name: String,
    pub source_name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PointArgs {
    pub x: i32,
    pub y: i32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct RectArgs {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ScreenCaptureArgs {
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ScreenReleaseArgs {
    pub frame_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GetColorArgs {
    pub frame_variable: String,
    pub point: PointArgs,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindColorArgs {
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    pub region: RectArgs,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CompareColorArgs {
    pub frame_variable: String,
    pub point: PointArgs,
    pub rgb: u32,
    pub tolerance: u8,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct MultiColorSampleArgs {
    pub x: i32,
    pub y: i32,
    pub rgb: u32,
    pub tolerance: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindMultiColorArgs {
    pub frame_variable: String,
    pub anchor_rgb: u32,
    pub anchor_tolerance: u8,
    pub samples: Vec<MultiColorSampleArgs>,
    pub region: RectArgs,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CountColorArgs {
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    pub region: RectArgs,
    pub limit: u16,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindAllColorArgs {
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    pub region: RectArgs,
    pub limit: u16,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindImageArgs {
    pub frame_variable: String,
    pub image_path: String,
    pub tolerance: u8,
    pub similarity_permille: u16,
    pub region: RectArgs,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GlyphOcrArgs {
    pub frame_variable: String,
    pub dictionary_path: String,
    pub foreground_rgb: u32,
    pub tolerance: u8,
    pub similarity_permille: u16,
    pub region: RectArgs,
    pub space_gap_columns: u16,
    pub text_variable: String,
    pub coverage_variable: String,
    pub score_variable: String,
}

#[derive(Debug, Clone)]
pub(crate) enum BuiltinNodeArgs {
    Sleep(SleepArgs),
    Tap(TapArgs),
    Swipe(SwipeArgs),
    KeyEvent(KeyEventArgs),
    If(ComparisonArgs),
    Repeat(RepeatArgs),
    While(WhileArgs),
    VariableSet(VariableSetArgs),
    VariableCopy(VariableCopyArgs),
    ScreenCapture(ScreenCaptureArgs),
    ScreenRelease(ScreenReleaseArgs),
    GetColor(GetColorArgs),
    FindColor(FindColorArgs),
    CompareColor(CompareColorArgs),
    FindMultiColor(FindMultiColorArgs),
    CountColor(CountColorArgs),
    FindAllColor(FindAllColorArgs),
    FindImage(FindImageArgs),
    GlyphOcr(GlyphOcrArgs),
}

pub(crate) struct PreparedFlow<'a> {
    pub declaration: &'a ProjectFlow,
    pub document: &'a FlowDocument,
}

pub(crate) struct PreparedProject<'a> {
    pub flows: Vec<PreparedFlow<'a>>,
    pub calls: HashMap<String, FlowCallArgs>,
    pub builtins: HashMap<String, BuiltinNodeArgs>,
}

pub(crate) fn prepare<'a>(
    manifest: &'a ProjectManifest,
    sources: &'a [FlowSource<'a>],
) -> Result<PreparedProject<'a>, Vec<CompileError>> {
    let mut errors = validate_sources(manifest, sources);
    let reports = sources
        .iter()
        .map(|source| source.report.clone())
        .collect::<Vec<_>>();
    errors.extend(
        validate_project_documents(manifest, &reports)
            .into_iter()
            .map(CompileError::ProjectStructure),
    );
    if !errors.is_empty() {
        return Err(errors);
    }

    let mut flows = Vec::with_capacity(manifest.flows.len());
    for declaration in &manifest.flows {
        let source = sources
            .iter()
            .find(|source| source.flow_id == declaration.flow_id)
            .expect("validated Flow source exists");
        let document = source
            .report
            .document
            .as_ref()
            .expect("validated Flow document exists");
        flows.push(PreparedFlow {
            declaration,
            document,
        });
    }
    flows.sort_by(|left, right| {
        left.declaration
            .flow_id
            .as_bytes()
            .cmp(right.declaration.flow_id.as_bytes())
    });
    let (calls, builtins) = validate_nodes(manifest, &flows, &mut errors);
    if errors.is_empty() {
        Ok(PreparedProject {
            flows,
            calls,
            builtins,
        })
    } else {
        Err(errors)
    }
}

fn validate_sources(manifest: &ProjectManifest, sources: &[FlowSource<'_>]) -> Vec<CompileError> {
    let declared = manifest
        .flows
        .iter()
        .map(|flow| flow.flow_id.as_str())
        .collect::<HashSet<_>>();
    let mut seen = HashSet::new();
    let mut errors = Vec::new();
    for source in sources {
        if !declared.contains(source.flow_id) {
            errors.push(CompileError::UnexpectedFlow(source.flow_id.to_owned()));
        }
        if !seen.insert(source.flow_id) {
            errors.push(CompileError::DuplicateFlowSource(source.flow_id.to_owned()));
        }
        if source.exact_bytes != source.report.original_source() {
            errors.push(CompileError::SourceBytesMismatch(source.flow_id.to_owned()));
        }
        if !source.report.is_compilable() {
            errors.push(CompileError::FlowNotCompilable {
                flow_id: source.flow_id.to_owned(),
                diagnostics: source.report.diagnostics.clone(),
            });
        }
    }
    for flow_id in declared {
        if !seen.contains(flow_id) {
            errors.push(CompileError::MissingFlow(flow_id.to_owned()));
        }
    }
    errors
}

fn validate_nodes(
    manifest: &ProjectManifest,
    flows: &[PreparedFlow<'_>],
    errors: &mut Vec<CompileError>,
) -> (
    HashMap<String, FlowCallArgs>,
    HashMap<String, BuiltinNodeArgs>,
) {
    let declarations = manifest
        .flows
        .iter()
        .map(|flow| (flow.flow_id.as_str(), flow))
        .collect::<HashMap<_, _>>();
    let mut calls = HashMap::new();
    let mut builtins = HashMap::new();
    let capabilities = manifest
        .capabilities
        .iter()
        .map(String::as_str)
        .collect::<HashSet<_>>();
    let mut edges: HashMap<&str, Vec<&str>> = declarations
        .keys()
        .map(|flow_id| (*flow_id, Vec::new()))
        .collect();
    for flow in flows {
        let Some(nodes) = flow.document.canonical_nodes() else {
            continue;
        };
        for node in nodes {
            match node.envelope.kind.as_str() {
                "task.noop" => {
                    if !node.envelope.child_blocks.is_empty()
                        || node
                            .envelope
                            .args
                            .as_object()
                            .is_none_or(|args| !args.is_empty())
                    {
                        errors.push(CompileError::InvalidNodeArguments {
                            node_id: node.envelope.node_id.clone(),
                            reason: "task.noop must have empty args and no child blocks".into(),
                        });
                    }
                }
                "flow.call" => {
                    if !node.envelope.child_blocks.is_empty() {
                        errors.push(CompileError::InvalidNodeArguments {
                            node_id: node.envelope.node_id.clone(),
                            reason: "flow.call is a leaf and cannot own child blocks".into(),
                        });
                    }
                    let Ok(arguments) =
                        serde_json::from_value::<FlowCallArgs>(node.envelope.args.clone())
                    else {
                        errors.push(CompileError::InvalidNodeArguments {
                            node_id: node.envelope.node_id.clone(),
                            reason: "flow.call requires targetFlowId and arguments".into(),
                        });
                        continue;
                    };
                    validate_call_arguments(
                        &node.envelope.node_id,
                        &arguments,
                        &declarations,
                        errors,
                    );
                    if let Some((&target_flow_id, _)) =
                        declarations.get_key_value(arguments.target_flow_id.as_str())
                    {
                        edges
                            .get_mut(flow.declaration.flow_id.as_str())
                            .expect("declared caller")
                            .push(target_flow_id);
                    }
                    calls.insert(node.envelope.node_id.clone(), arguments);
                }
                kind if is_builtin_kind(kind) => {
                    validate_builtin_node(
                        kind,
                        node,
                        manifest,
                        &capabilities,
                        &mut builtins,
                        errors,
                    );
                }
                kind => errors.push(CompileError::UnsupportedNodeKind {
                    node_id: node.envelope.node_id.clone(),
                    kind: kind.to_owned(),
                }),
            }
        }
    }
    if has_cycle(&edges) {
        errors.push(CompileError::RecursiveFlowCall);
    }
    (calls, builtins)
}

fn is_builtin_kind(kind: &str) -> bool {
    matches!(
        kind,
        "task.sleep"
            | "input.tap"
            | "input.swipe"
            | "input.keyevent"
            | "control.if"
            | "control.repeat"
            | "control.while"
            | "variable.set"
            | "variable.copy"
            | "screen.capture"
            | "screen.release"
            | "vision.getcolor"
            | "vision.findcolor"
            | "vision.comparecolor"
            | "vision.findmulticolor"
            | "vision.countcolor"
            | "vision.findallcolor"
            | "vision.findimage"
            | "ocr.glyph"
    )
}

fn validate_builtin_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    capabilities: &HashSet<&str>,
    builtins: &mut HashMap<String, BuiltinNodeArgs>,
    errors: &mut Vec<CompileError>,
) {
    validate_builtin_requirements(kind, node, capabilities, errors);
    let parsed = match kind {
        "task.sleep" => serde_json::from_value::<SleepArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| value.milliseconds >= 0)
            .map(BuiltinNodeArgs::Sleep)
            .ok_or("task.sleep requires non-negative integer milliseconds"),
        "input.tap" => serde_json::from_value::<TapArgs>(node.envelope.args.clone())
            .map(BuiltinNodeArgs::Tap)
            .map_err(|_| "input.tap requires 32-bit integer x and y"),
        "input.swipe" => serde_json::from_value::<SwipeArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| (1..=5000).contains(&value.duration_ms))
            .map(BuiltinNodeArgs::Swipe)
            .ok_or("input.swipe requires 32-bit coordinates and durationMs from 1 to 5000"),
        "input.keyevent" => serde_json::from_value::<KeyEventArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| value.key_code <= 65_535)
            .map(BuiltinNodeArgs::KeyEvent)
            .ok_or("input.keyevent requires keyCode from 0 to 65535"),
        "control.if" => {
            validate_child_blocks(node, &["then", "else"], errors);
            serde_json::from_value::<ComparisonArgs>(node.envelope.args.clone())
                .ok()
                .filter(valid_comparison)
                .map(BuiltinNodeArgs::If)
                .ok_or("control.if requires a valid variable comparison")
        }
        "control.repeat" => {
            validate_child_blocks(node, &["body"], errors);
            serde_json::from_value::<RepeatArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.times <= 1_000_000
                        && value.index_variable.as_deref().is_none_or(valid_variable_name)
                })
                .map(BuiltinNodeArgs::Repeat)
                .ok_or("control.repeat requires times from 0 to 1000000 and a valid optional indexVariable")
        }
        "control.while" => {
            validate_child_blocks(node, &["body"], errors);
            serde_json::from_value::<WhileArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    (1..=1_000_000).contains(&value.max_iterations)
                        && valid_comparison_parts(&value.variable, value.operator, &value.value)
                })
                .map(BuiltinNodeArgs::While)
                .ok_or(
                    "control.while requires a valid comparison and maxIterations from 1 to 1000000",
                )
        }
        "variable.set" => serde_json::from_value::<VariableSetArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| valid_variable_name(&value.name) && is_scalar(&value.value))
            .map(BuiltinNodeArgs::VariableSet)
            .ok_or("variable.set requires a valid name and scalar value"),
        "variable.copy" => serde_json::from_value::<VariableCopyArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                valid_variable_name(&value.name) && valid_variable_name(&value.source_name)
            })
            .map(BuiltinNodeArgs::VariableCopy)
            .ok_or("variable.copy requires valid name and sourceName"),
        "screen.capture"
        | "screen.release"
        | "vision.getcolor"
        | "vision.findcolor"
        | "vision.comparecolor"
        | "vision.findmulticolor"
        | "vision.countcolor"
        | "vision.findallcolor"
        | "vision.findimage"
        | "ocr.glyph" => parse_visual_node(kind, node, manifest),
        _ => unreachable!("caller filters builtin kinds"),
    };
    match parsed {
        Ok(arguments) => {
            builtins.insert(node.envelope.node_id.clone(), arguments);
        }
        Err(reason) => invalid_arguments(node, reason, errors),
    }
}

fn validate_builtin_requirements(
    kind: &str,
    node: &flow_ir::FlowNode,
    capabilities: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    match kind {
        "task.sleep" => require_capability(node, "core.task", capabilities, errors),
        "input.tap" | "input.swipe" | "input.keyevent" => {
            require_capability(node, "input.basic", capabilities, errors);
        }
        "screen.capture" | "screen.release" => {
            require_capability(node, "screen.capture", capabilities, errors);
        }
        "vision.getcolor"
        | "vision.findcolor"
        | "vision.comparecolor"
        | "vision.findmulticolor"
        | "vision.countcolor"
        | "vision.findallcolor" => {
            require_capability(node, "vision.pixel", capabilities, errors);
        }
        "vision.findimage" => {
            require_capability(node, "vision.template", capabilities, errors);
            require_capability(node, "screen.capture", capabilities, errors);
        }
        "ocr.glyph" => require_capability(node, "ocr.glyph", capabilities, errors),
        _ => {}
    }
    if matches!(
        kind,
        "task.sleep"
            | "input.tap"
            | "input.swipe"
            | "input.keyevent"
            | "variable.set"
            | "variable.copy"
            | "screen.capture"
            | "screen.release"
            | "vision.getcolor"
            | "vision.findcolor"
            | "vision.comparecolor"
            | "vision.findmulticolor"
            | "vision.countcolor"
            | "vision.findallcolor"
            | "vision.findimage"
            | "ocr.glyph"
    ) {
        validate_leaf(node, errors);
    }
}

fn parse_visual_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "screen.capture" => serde_json::from_value::<ScreenCaptureArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| valid_variable_name(&value.result_variable))
            .map(BuiltinNodeArgs::ScreenCapture)
            .ok_or("screen.capture requires a valid resultVariable"),
        "screen.release" => serde_json::from_value::<ScreenReleaseArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| valid_variable_name(&value.frame_variable))
            .map(BuiltinNodeArgs::ScreenRelease)
            .ok_or("screen.release requires a valid frameVariable"),
        "vision.getcolor" => serde_json::from_value::<GetColorArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                valid_variable_name(&value.frame_variable)
                    && valid_variable_name(&value.result_variable)
            })
            .map(BuiltinNodeArgs::GetColor)
            .ok_or("vision.getcolor requires valid frame and result variables"),
        "vision.findcolor" => serde_json::from_value::<FindColorArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.rgb <= 0xFF_FFFF
                    && valid_rect(&value.region)
                    && valid_variable_names([
                        &value.frame_variable,
                        &value.found_variable,
                        &value.x_variable,
                        &value.y_variable,
                    ])
            })
            .map(BuiltinNodeArgs::FindColor)
            .ok_or("vision.findcolor has invalid color, region, or variable names"),
        "vision.comparecolor"
        | "vision.findmulticolor"
        | "vision.countcolor"
        | "vision.findallcolor" => parse_extended_pixel_node(kind, node),
        "vision.findimage" => serde_json::from_value::<FindImageArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.similarity_permille <= 1000
                    && valid_rect(&value.region)
                    && has_resource(manifest, ProjectResourceKind::Image, &value.image_path)
                    && valid_variable_names([
                        &value.frame_variable,
                        &value.found_variable,
                        &value.x_variable,
                        &value.y_variable,
                    ])
            })
            .map(BuiltinNodeArgs::FindImage)
            .ok_or("vision.findimage has invalid resource, region, similarity, or variable names"),
        "ocr.glyph" => serde_json::from_value::<GlyphOcrArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.foreground_rgb <= 0xFF_FFFF
                    && value.similarity_permille <= 1000
                    && valid_rect(&value.region)
                    && has_resource(
                        manifest,
                        ProjectResourceKind::GlyphDictionary,
                        &value.dictionary_path,
                    )
                    && valid_variable_names([
                        &value.frame_variable,
                        &value.text_variable,
                        &value.coverage_variable,
                        &value.score_variable,
                    ])
            })
            .map(BuiltinNodeArgs::GlyphOcr)
            .ok_or("ocr.glyph has invalid resource, region, color, similarity, or variable names"),
        _ => unreachable!("caller filters visual node kinds"),
    }
}

fn parse_extended_pixel_node(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "vision.comparecolor" => {
            serde_json::from_value::<CompareColorArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.rgb <= 0xFF_FFFF
                        && valid_variable_names([&value.frame_variable, &value.result_variable])
                })
                .map(BuiltinNodeArgs::CompareColor)
                .ok_or("vision.comparecolor has invalid color or variable names")
        }
        "vision.findmulticolor" => {
            serde_json::from_value::<FindMultiColorArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.anchor_rgb <= 0xFF_FFFF
                        && (1..=64).contains(&value.samples.len())
                        && value.samples.iter().all(|sample| sample.rgb <= 0xFF_FFFF)
                        && valid_rect(&value.region)
                        && valid_variable_names([
                            &value.frame_variable,
                            &value.found_variable,
                            &value.x_variable,
                            &value.y_variable,
                        ])
                })
                .map(BuiltinNodeArgs::FindMultiColor)
                .ok_or(
                    "vision.findmulticolor has invalid samples, region, color, or variable names",
                )
        }
        "vision.countcolor" => serde_json::from_value::<CountColorArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.rgb <= 0xFF_FFFF
                    && (1..=256).contains(&value.limit)
                    && valid_rect(&value.region)
                    && valid_variable_names([&value.frame_variable, &value.result_variable])
            })
            .map(BuiltinNodeArgs::CountColor)
            .ok_or("vision.countcolor has invalid limit, region, color, or variable names"),
        "vision.findallcolor" => {
            serde_json::from_value::<FindAllColorArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.rgb <= 0xFF_FFFF
                        && (1..=256).contains(&value.limit)
                        && valid_rect(&value.region)
                        && valid_variable_names([&value.frame_variable, &value.result_variable])
                })
                .map(BuiltinNodeArgs::FindAllColor)
                .ok_or("vision.findallcolor has invalid limit, region, color, or variable names")
        }
        _ => unreachable!("caller filters extended pixel node kinds"),
    }
}

fn valid_rect(value: &RectArgs) -> bool {
    value.right > value.left && value.bottom > value.top
}

fn valid_variable_names<const N: usize>(values: [&str; N]) -> bool {
    values.into_iter().all(valid_variable_name)
}

fn has_resource(manifest: &ProjectManifest, kind: ProjectResourceKind, path: &str) -> bool {
    manifest
        .resources
        .iter()
        .any(|resource| resource.kind == kind && resource.path == path)
}

fn validate_child_blocks(
    node: &flow_ir::FlowNode,
    required: &[&str],
    errors: &mut Vec<CompileError>,
) {
    let actual = node
        .envelope
        .child_blocks
        .keys()
        .map(String::as_str)
        .collect::<HashSet<_>>();
    let expected = required.iter().copied().collect::<HashSet<_>>();
    if actual != expected {
        invalid_arguments(
            node,
            format!(
                "{} requires child blocks {}",
                node.envelope.kind,
                required.join(", ")
            ),
            errors,
        );
    }
}

fn valid_comparison(value: &ComparisonArgs) -> bool {
    valid_comparison_parts(&value.variable, value.operator, &value.value)
}

fn valid_comparison_parts(variable: &str, operator: ComparisonOperator, value: &Value) -> bool {
    valid_variable_name(variable)
        && is_scalar(value)
        && (matches!(
            operator,
            ComparisonOperator::Equals | ComparisonOperator::NotEquals
        ) || value.is_number()
            || value.is_string())
}

fn is_scalar(value: &Value) -> bool {
    !value.is_array() && !value.is_object()
}

fn valid_variable_name(value: &str) -> bool {
    let mut bytes = value.bytes();
    let Some(first) = bytes.next() else {
        return false;
    };
    value.len() <= 64
        && (first.is_ascii_alphabetic() || first == b'_')
        && bytes.all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn validate_leaf(node: &flow_ir::FlowNode, errors: &mut Vec<CompileError>) {
    if !node.envelope.child_blocks.is_empty() {
        invalid_arguments(node, "leaf node cannot own child blocks", errors);
    }
}

fn require_capability(
    node: &flow_ir::FlowNode,
    capability: &str,
    available: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    if !available.contains(capability) {
        errors.push(CompileError::MissingCapability {
            node_id: node.envelope.node_id.clone(),
            capability: capability.to_owned(),
        });
    }
}

fn invalid_arguments(
    node: &flow_ir::FlowNode,
    reason: impl Into<String>,
    errors: &mut Vec<CompileError>,
) {
    errors.push(CompileError::InvalidNodeArguments {
        node_id: node.envelope.node_id.clone(),
        reason: reason.into(),
    });
}

fn validate_call_arguments(
    node_id: &str,
    call: &FlowCallArgs,
    declarations: &HashMap<&str, &ProjectFlow>,
    errors: &mut Vec<CompileError>,
) {
    let Some(target) = declarations.get(call.target_flow_id.as_str()) else {
        errors.push(CompileError::UnknownTargetFlow {
            node_id: node_id.to_owned(),
            target_flow_id: call.target_flow_id.clone(),
        });
        return;
    };
    let parameters = target
        .params
        .iter()
        .map(|parameter| (parameter.name.as_str(), parameter))
        .collect::<HashMap<_, _>>();
    for parameter in &target.params {
        if parameter.required && !call.arguments.contains_key(&parameter.name) {
            errors.push(CompileError::MissingArgument {
                node_id: node_id.to_owned(),
                argument: parameter.name.clone(),
            });
        }
    }
    for (name, value) in &call.arguments {
        let Some(parameter) = parameters.get(name.as_str()) else {
            errors.push(CompileError::UnknownArgument {
                node_id: node_id.to_owned(),
                argument: name.clone(),
            });
            continue;
        };
        if !matches_type(value, parameter.value_type) {
            errors.push(CompileError::ArgumentTypeMismatch {
                node_id: node_id.to_owned(),
                argument: name.clone(),
            });
        }
    }
}

fn matches_type(value: &Value, expected: ValueType) -> bool {
    match expected {
        ValueType::Boolean => value.is_boolean(),
        ValueType::Integer => value.as_i64().is_some() || value.as_u64().is_some(),
        ValueType::Number => value.is_number(),
        ValueType::String => value.is_string(),
    }
}

fn has_cycle(edges: &HashMap<&str, Vec<&str>>) -> bool {
    let mut indegrees = edges
        .keys()
        .map(|key| (*key, 0_usize))
        .collect::<HashMap<_, _>>();
    for targets in edges.values() {
        for target in targets {
            if let Some(indegree) = indegrees.get_mut(target) {
                *indegree += 1;
            }
        }
    }
    let mut queue = indegrees
        .iter()
        .filter_map(|(&flow, &indegree)| (indegree == 0).then_some(flow))
        .collect::<VecDeque<_>>();
    let mut visited = 0;
    while let Some(flow) = queue.pop_front() {
        visited += 1;
        for target in &edges[flow] {
            let indegree = indegrees.get_mut(target).expect("known call target");
            *indegree -= 1;
            if *indegree == 0 {
                queue.push_back(target);
            }
        }
    }
    visited != edges.len()
}
