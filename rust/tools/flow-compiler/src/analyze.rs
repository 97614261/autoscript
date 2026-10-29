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
    #[serde(default)]
    pub period_ms: Option<u32>,
    #[serde(default)]
    pub result_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct JobCancelArgs {
    pub id_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FlowChannelSetArgs {
    pub index: u8,
    #[serde(default)]
    pub value: Option<Value>,
    #[serde(default)]
    pub value_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FlowChannelGetArgs {
    pub index: u8,
    pub target_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SleepArgs {
    pub milliseconds: i64,
    #[serde(default)]
    pub milliseconds_variable: Option<String>,
    #[serde(default)]
    pub exact_timing: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LogArgs {
    pub level: LogLevel,
    pub message: String,
    #[serde(default)]
    pub value_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct PromptArgs {
    pub message: String,
    #[serde(default)]
    pub value_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct MessageArgs {
    pub expression: String,
    pub channel: MessageChannel,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum MessageChannel {
    Popup,
    Output,
    Console,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum LogLevel {
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct TapArgs {
    pub x: i32,
    pub y: i32,
    #[serde(default, rename = "screenPixels")]
    pub screen_pixels: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PointerArgs {
    pub x: i32,
    pub y: i32,
    #[serde(default, rename = "screenPixels")]
    pub screen_pixels: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct SwipeArgs {
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
    pub duration_ms: u32,
    #[serde(default)]
    pub screen_pixels: bool,
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
    #[serde(default)]
    pub value_variable: Option<String>,
    #[serde(default)]
    pub else_if: Vec<ElseIfArgs>,
    #[serde(default)]
    pub condition: Option<Value>,
}

/// A branch belonging to a `control.if`.  It intentionally repeats the compact
/// comparison contract instead of accepting Lua expressions.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ElseIfArgs {
    pub variable: String,
    pub operator: ComparisonOperator,
    pub value: Value,
    #[serde(default)]
    pub value_variable: Option<String>,
    #[serde(default)]
    pub condition: Option<Value>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RepeatArgs {
    pub times: u32,
    #[serde(default)]
    pub times_variable: Option<String>,
    #[serde(default)]
    pub index_variable: Option<String>,
    #[serde(default)]
    pub elapsed_variable: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct WhileArgs {
    pub variable: String,
    pub operator: ComparisonOperator,
    pub value: Value,
    pub max_iterations: u32,
    #[serde(default)]
    pub always: bool,
    #[serde(default)]
    pub duration_ms: Option<u64>,
    #[serde(default)]
    pub duration_variable: Option<String>,
    #[serde(default)]
    pub duration_unit: LoopTimeUnit,
    #[serde(default)]
    pub iteration_variable: Option<String>,
    #[serde(default)]
    pub elapsed_variable: Option<String>,
    #[serde(skip)]
    pub yield_ms: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CycleArgs {
    yield_ms: u32,
    #[serde(default)]
    duration_ms: Option<u64>,
    #[serde(default)]
    duration_variable: Option<String>,
    #[serde(default)]
    duration_unit: LoopTimeUnit,
    #[serde(default)]
    iteration_variable: Option<String>,
    #[serde(default)]
    elapsed_variable: Option<String>,
}

impl From<CycleArgs> for WhileArgs {
    fn from(value: CycleArgs) -> Self {
        Self {
            variable: "unused".into(),
            operator: ComparisonOperator::Equals,
            value: Value::Bool(true),
            max_iterations: 0,
            always: true,
            duration_ms: value.duration_ms,
            duration_variable: value.duration_variable,
            duration_unit: value.duration_unit,
            iteration_variable: value.iteration_variable,
            elapsed_variable: value.elapsed_variable,
            yield_ms: value.yield_ms,
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum LoopTimeUnit {
    #[default]
    Milliseconds,
    Seconds,
    Minutes,
}
impl LoopTimeUnit {
    pub(crate) fn factor(self) -> u32 {
        match self {
            Self::Milliseconds => 1,
            Self::Seconds => 1000,
            Self::Minutes => 60000,
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(crate) enum LoopMetric {
    Count,
    Elapsed,
}
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LoopMetricArgs {
    pub metric: LoopMetric,
    pub name: String,
    #[serde(default)]
    pub unit: LoopTimeUnit,
}
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LoopCheckArgs {
    pub metric: LoopMetric,
    pub limit: f64,
    #[serde(default)]
    pub limit_variable: Option<String>,
    #[serde(default)]
    pub unit: LoopTimeUnit,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct VariableSetArgs {
    pub name: String,
    pub value: Value,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct VariableCalculateArgs {
    pub name: String,
    pub expression: String,
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
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub point: PointArgs,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindColorArgs {
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    #[serde(default)]
    pub additional_colors: String,
    pub region: RectArgs,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct CompareColorArgs {
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub point: PointArgs,
    pub rgb: u32,
    pub tolerance: u8,
    #[serde(default)]
    pub additional_colors: String,
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
    #[serde(default)]
    pub auto_capture: bool,
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
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    #[serde(default)]
    pub additional_colors: String,
    pub region: RectArgs,
    pub limit: u32,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindAllColorArgs {
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub rgb: u32,
    pub tolerance: u8,
    #[serde(default)]
    pub additional_colors: String,
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
    #[serde(default)]
    pub direction: u8,
    pub left_variable: Option<String>,
    pub top_variable: Option<String>,
    pub right_variable: Option<String>,
    pub bottom_variable: Option<String>,
    pub image_directory: Option<String>,
    pub template_variable: Option<String>,
    pub score_variable: Option<String>,
    #[serde(default)]
    pub image_paths: Vec<String>,
    pub image_variable: Option<String>,
    #[serde(default = "default_recognition_frequency")]
    pub frequency: u8,
    #[serde(default)]
    pub auto_capture: bool,
    #[serde(default)]
    pub success_action: RecognitionAction,
    #[serde(default)]
    pub offset_x: i32,
    #[serde(default)]
    pub offset_y: i32,
    #[serde(default = "default_action_duration")]
    pub action_duration_ms: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct FindAllImagesArgs {
    pub frame_variable: String,
    pub auto_capture: bool,
    pub image_paths: Vec<String>,
    pub tolerance: u8,
    pub similarity_permille: u16,
    pub region: RectArgs,
    pub direction: u8,
    pub limit: u8,
    pub result_variable: String,
    #[serde(default)]
    pub success_action: RecognitionAction,
    #[serde(default)]
    pub all_matches: bool,
    #[serde(default)]
    pub offset_x: i32,
    #[serde(default)]
    pub offset_y: i32,
    #[serde(default = "default_action_duration")]
    pub action_duration_ms: u32,
    #[serde(default)]
    pub before_wait_ms: u32,
    #[serde(default)]
    pub after_wait_ms: u32,
}
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct MatchResultArgs {
    pub source_variable: String,
    pub index: u8,
    pub field: String,
    pub target_variable: String,
}

fn default_recognition_frequency() -> u8 {
    1
}
fn default_action_duration() -> u32 {
    100
}

#[derive(Debug, Clone, Copy, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub(crate) enum RecognitionAction {
    #[default]
    None,
    Tap,
    Hold,
    TapWait,
    PressRelease,
}

impl FindImageArgs {
    pub fn has_extended_options(&self) -> bool {
        self.direction != 0
            || self
                .region_variables()
                .iter()
                .any(std::option::Option::is_some)
            || self.image_directory.is_some()
            || !self.image_paths.is_empty()
            || self.template_variable.is_some()
            || self.score_variable.is_some()
            || self.image_variable.is_some()
            || self.frequency != 1
            || self.auto_capture
            || self.success_action != RecognitionAction::None
    }

    pub fn region_variables(&self) -> [Option<&str>; 4] {
        [
            self.left_variable.as_deref(),
            self.top_variable.as_deref(),
            self.right_variable.as_deref(),
            self.bottom_variable.as_deref(),
        ]
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GlyphOcrArgs {
    pub frame_variable: String,
    pub dictionary_path: String,
    pub foreground_rgb: u32,
    #[serde(default)]
    pub additional_colors: String,
    pub tolerance: u8,
    pub similarity_permille: u16,
    pub region: RectArgs,
    pub space_gap_columns: u16,
    pub text_variable: String,
    pub coverage_variable: String,
    pub score_variable: String,
}

impl GlyphOcrArgs {
    fn valid_colors(&self) -> bool {
        let Ok(colors) = glyph_ocr::parse_additional_colors(&self.additional_colors) else {
            return false;
        };
        if glyph_ocr::validate_additional_colors(self.foreground_rgb, &colors).is_err() {
            return false;
        }
        let Ok(width) = u64::try_from(i64::from(self.region.right) - i64::from(self.region.left))
        else {
            return false;
        };
        let Ok(height) = u64::try_from(i64::from(self.region.bottom) - i64::from(self.region.top))
        else {
            return false;
        };
        width
            .checked_mul(height)
            .and_then(|v| v.checked_mul(colors.len() as u64 + 1))
            .is_some_and(|work| work <= glyph_ocr::MAX_COLOR_COMPARISONS)
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GrayArgs {
    pub frame_variable: String,
    pub image_path: String,
    pub similarity_permille: u16,
    pub region: RectArgs,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
    pub score_variable: String,
    #[serde(default = "default_auto_capture")]
    pub auto_capture: bool,
}
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AlphanumericArgs {
    pub frame_variable: String,
    pub region: RectArgs,
    pub minimum_confidence_permille: u16,
    pub text_variable: String,
    pub score_variable: String,
    #[serde(default)]
    pub include_symbols: bool,
    #[serde(default = "default_auto_capture")]
    pub auto_capture: bool,
}
fn default_auto_capture() -> bool {
    true
}

#[derive(Debug, Clone)]
pub(crate) enum BuiltinNodeArgs {
    Utility(crate::utility_operations::UtilityOperation),
    Dialog(crate::ui_dialog::DialogArgs, String),
    Ui(UiArgs, String),
    Data(crate::data_operations::DataOperation),
    Gray(GrayArgs),
    Alphanumeric(AlphanumericArgs),
    Sleep(SleepArgs),
    JobCancel(JobCancelArgs, bool),
    Log(LogArgs),
    Prompt(PromptArgs),
    RunPrompt(PromptArgs),
    Message(MessageArgs),
    Tap(TapArgs),
    PointerDown(PointerArgs),
    PointerMove(PointerArgs),
    PointerUp,
    Swipe(SwipeArgs),
    KeyEvent(KeyEventArgs),
    If(ComparisonArgs),
    Repeat(RepeatArgs),
    While(WhileArgs),
    LoopMetric(LoopMetricArgs),
    LoopCheck(LoopCheckArgs),
    VariableSet(VariableSetArgs),
    VariableCalculate(VariableCalculateArgs),
    VariableCopy(VariableCopyArgs),
    ScreenCapture(ScreenCaptureArgs),
    ScreenResource(crate::screen_resources::Operation),
    ScriptControl(crate::script_controls::Operation),
    VisionExtension(crate::vision_extensions::Operation),
    RuntimeTool(crate::runtime_tools::Operation),
    ScreenRelease(ScreenReleaseArgs),
    GetColor(GetColorArgs),
    FindColor(FindColorArgs),
    CompareColor(CompareColorArgs),
    FindMultiColor(FindMultiColorArgs),
    CountColor(CountColorArgs),
    FindAllColor(FindAllColorArgs),
    FindImage(FindImageArgs),
    FindAllImages(FindAllImagesArgs),
    MatchResult(MatchResultArgs),
    GlyphOcr(GlyphOcrArgs),
    LegacyDuoDianZhaoSe(LegacyDuoDianZhaoSeArgs),
    LegacyDuoDianBiSe(LegacyDuoDianBiSeArgs),
    LegacyGetRectColorNum(LegacyGetRectColorNumArgs),
    LegacyGetRgbColor(LegacyGetRgbColorArgs),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct UiArgs {
    pub control_id: String,
    #[serde(default)]
    pub result_variable: Option<String>,
    #[serde(default)]
    pub value: String,
    #[serde(default)]
    pub value_variable: Option<String>,
    #[serde(default)]
    pub operation: String,
}

/// Legacy region keeps the original origin-plus-extent form instead of a half-open rectangle.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyRegionArgs {
    pub left: u32,
    pub top: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyRelativeSampleArgs {
    pub dx: i32,
    pub dy: i32,
    pub rgb: u32,
    pub tolerance_red: u8,
    pub tolerance_green: u8,
    pub tolerance_blue: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyFixedSampleArgs {
    pub x: u32,
    pub y: u32,
    pub rgb: u32,
    pub tolerance_red: u8,
    pub tolerance_green: u8,
    pub tolerance_blue: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyColorSpecArgs {
    pub rgb: u32,
    pub tolerance_red: u8,
    pub tolerance_green: u8,
    pub tolerance_blue: u8,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyDuoDianZhaoSeArgs {
    pub frame_variable: String,
    pub region: LegacyRegionArgs,
    pub pattern: Vec<LegacyRelativeSampleArgs>,
    pub direction: u8,
    pub minimum_match_percent: u8,
    pub result_variable: String,
    pub count_variable: String,
    pub found_variable: String,
    pub x_variable: String,
    pub y_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyDuoDianBiSeArgs {
    pub frame_variable: String,
    pub pattern: Vec<LegacyFixedSampleArgs>,
    pub minimum_match_percent: u8,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyGetRectColorNumArgs {
    #[serde(default)]
    pub auto_capture: bool,
    pub frame_variable: String,
    pub region: LegacyRegionArgs,
    pub colors: Vec<LegacyColorSpecArgs>,
    pub result_variable: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct LegacyGetRgbColorArgs {
    pub red: u8,
    pub green: u8,
    pub blue: u8,
    pub result_variable: String,
}

pub(crate) struct PreparedFlow<'a> {
    pub declaration: &'a ProjectFlow,
    pub document: &'a FlowDocument,
}

pub(crate) struct PreparedProject<'a> {
    pub manifest: &'a ProjectManifest,
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
            manifest,
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
        let node_by_id = flow
            .document
            .nodes
            .iter()
            .map(|node| (node.envelope.node_id.as_str(), node))
            .collect::<HashMap<_, _>>();
        let disabled = flow.document.disabled_node_ids();
        let labels = flow
            .document
            .nodes
            .iter()
            .filter(|node| !disabled.contains(node.envelope.node_id.as_str()))
            .filter(|node| node.envelope.kind == "control.label")
            .filter_map(|node| node.envelope.args.get("name").and_then(Value::as_str))
            .collect::<Vec<_>>();
        for node in nodes {
            if let Some(reason) = crate::randomization::invalid_node(node) {
                invalid_arguments(node, reason, errors);
            }
            if disabled.contains(node.envelope.node_id.as_str()) {
                continue;
            }
            if matches!(
                node.envelope.kind.as_str(),
                "control.loopmetric" | "control.loopcheck"
            ) {
                let in_loop = node_is_inside_loop(node, &node_by_id);
                if !in_loop {
                    invalid_arguments(node, "循环指标/检查只能位于当前插件的循环体", errors);
                }
            }
            match node.envelope.kind.as_str() {
                "flow.argument.set" | "flow.return.set" | "flow.argument.get"
                | "flow.return.get" => validate_channel_node(flow, node, errors),
                "control.break" | "control.continue" | "control.label" | "control.goto"
                | "flow.return" => validate_jump_node(node, &node_by_id, &labels, errors),
                "task.noop" | "task.comment" | "flow.status.return" => {
                    validate_plain_node(node, errors);
                }
                "flow.call" | "flow.call.branch" | "task.spawn" | "timer.every" => {
                    validate_call_node(
                        manifest,
                        flow,
                        node,
                        &declarations,
                        &mut calls,
                        &mut edges,
                        errors,
                    );
                }
                kind if is_builtin_kind(kind) => {
                    validate_builtin_node(
                        kind,
                        node,
                        manifest,
                        flow.declaration.flow_id.as_str(),
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

fn validate_channel_node(
    flow: &PreparedFlow<'_>,
    node: &flow_ir::FlowNode,
    errors: &mut Vec<CompileError>,
) {
    match node.envelope.kind.as_str() {
        "flow.argument.set" | "flow.return.set" => {
            let kind = node.envelope.kind.as_str();
            let valid = serde_json::from_value::<FlowChannelSetArgs>(node.envelope.args.clone())
                .ok()
                .is_some_and(|args| {
                    node.envelope.child_blocks.is_empty()
                        && (1..=99).contains(&args.index)
                        && (kind != "flow.return.set"
                            || args.index != 1
                            || args.value.as_ref().is_none_or(|value| {
                                flow.declaration.returns.as_ref().is_none_or(|declaration| {
                                    matches_type(value, declaration.value_type)
                                })
                            }))
                        && (args.value.as_ref().is_some_and(is_scalar)
                            ^ args
                                .value_variable
                                .as_deref()
                                .is_some_and(valid_variable_name))
                });
            if !valid {
                errors.push(CompileError::InvalidNodeArguments {
                    node_id: node.envelope.node_id.clone(),
                    reason: format!(
                        "{kind} requires a valid index and exactly one scalar or variable source"
                    ),
                });
            }
        }
        "flow.argument.get" | "flow.return.get" => {
            let valid = serde_json::from_value::<FlowChannelGetArgs>(node.envelope.args.clone())
                .ok()
                .is_some_and(|args| {
                    node.envelope.child_blocks.is_empty()
                        && valid_variable_name(&args.target_variable)
                        && (1..=99).contains(&args.index)
                });
            if !valid {
                errors.push(CompileError::InvalidNodeArguments {
                    node_id: node.envelope.node_id.clone(),
                    reason: "parameter get requires an index and target variable".into(),
                });
            }
        }
        _ => unreachable!("node validation dispatch"),
    }
}

fn validate_jump_node(
    node: &flow_ir::FlowNode,
    node_by_id: &HashMap<&str, &flow_ir::FlowNode>,
    labels: &[&str],
    errors: &mut Vec<CompileError>,
) {
    let kind = node.envelope.kind.as_str();
    let empty_args = node
        .envelope
        .args
        .as_object()
        .is_some_and(serde_json::Map::is_empty);
    let name = node.envelope.args.as_object().and_then(|args| {
        (args.len() == 1)
            .then(|| args.get("name"))
            .flatten()
            .and_then(Value::as_str)
    });
    let valid_name = name.is_some_and(valid_variable_name);
    let inside_loop = {
        let mut parent_id = node.envelope.parent_id.as_deref();
        let mut found = false;
        while let Some(id) = parent_id {
            let Some(parent) = node_by_id.get(id) else {
                break;
            };
            if matches!(
                parent.envelope.kind.as_str(),
                "control.repeat" | "control.while" | "control.cycle"
            ) {
                found = true;
                break;
            }
            parent_id = parent.envelope.parent_id.as_deref();
        }
        found
    };
    let valid = node.envelope.child_blocks.is_empty()
        && match kind {
            "control.break" | "control.continue" => empty_args && inside_loop,
            "flow.return" => empty_args,
            "control.label" => {
                valid_name
                    && node.envelope.parent_id.is_none()
                    && labels
                        .iter()
                        .copied()
                        .filter(|candidate| *candidate == name.unwrap_or(""))
                        .count()
                        == 1
            }
            "control.goto" => {
                valid_name
                    && node.envelope.parent_id.is_none()
                    && labels.contains(&name.unwrap_or(""))
            }
            _ => false,
        };
    if !valid {
        errors.push(CompileError::InvalidNodeArguments {
            node_id: node.envelope.node_id.clone(),
            reason: format!("{kind} requires valid arguments and structural position"),
        });
    }
}

fn validate_plain_node(node: &flow_ir::FlowNode, errors: &mut Vec<CompileError>) {
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
        "task.comment" => {
            let valid = node.envelope.child_blocks.is_empty()
                && node.envelope.args.as_object().is_some_and(|args| {
                    args.len() == 1
                        && args
                            .get("message")
                            .and_then(Value::as_str)
                            .is_some_and(|message| !message.is_empty() && message.len() <= 2_048)
                });
            if !valid {
                errors.push(CompileError::InvalidNodeArguments {
                    node_id: node.envelope.node_id.clone(),
                    reason: "task.comment requires one nonempty message and no child blocks".into(),
                });
            }
        }
        "flow.status.return" => {
            let valid = node.envelope.args.as_object().is_some_and(|args| {
                args.len() == 1 && args.get("success").is_some_and(Value::is_boolean)
            });
            if !valid || !node.envelope.child_blocks.is_empty() {
                invalid_arguments(
                    node,
                    "插件状态返回需要唯一布尔success且不能包含子区",
                    errors,
                );
            }
        }
        _ => unreachable!("node validation dispatch"),
    }
}

fn validate_call_node<'a>(
    manifest: &'a ProjectManifest,
    flow: &PreparedFlow<'a>,
    node: &flow_ir::FlowNode,
    declarations: &HashMap<&'a str, &'a ProjectFlow>,
    calls: &mut HashMap<String, FlowCallArgs>,
    edges: &mut HashMap<&'a str, Vec<&'a str>>,
    errors: &mut Vec<CompileError>,
) {
    let capabilities = manifest
        .capabilities
        .iter()
        .map(String::as_str)
        .collect::<HashSet<_>>();
    if node.envelope.kind == "flow.call.branch" {
        validate_child_blocks(node, &["success", "failure"], errors);
    } else if !node.envelope.child_blocks.is_empty() {
        errors.push(CompileError::InvalidNodeArguments {
            node_id: node.envelope.node_id.clone(),
            reason: "flow.call is a leaf and cannot own child blocks".into(),
        });
    }
    let Ok(arguments) = serde_json::from_value::<FlowCallArgs>(node.envelope.args.clone()) else {
        errors.push(CompileError::InvalidNodeArguments {
            node_id: node.envelope.node_id.clone(),
            reason: "flow.call requires targetFlowId and arguments".into(),
        });
        return;
    };
    validate_call_arguments(
        &node.envelope.node_id,
        &arguments,
        declarations,
        flow.document,
        errors,
    );
    let kind = node.envelope.kind.as_str();
    if !matches!(kind, "flow.call" | "flow.call.branch") {
        require_capability(node, "core.task", &capabilities, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7)
            || !arguments
                .result_variable
                .as_deref()
                .is_some_and(valid_variable_name)
            || (kind == "timer.every"
                && !arguments
                    .period_ms
                    .is_some_and(|period| (10..=60_000).contains(&period)))
            || (kind == "task.spawn" && arguments.period_ms.is_some())
        {
            errors.push(CompileError::InvalidNodeArguments { node_id:node.envelope.node_id.clone(), reason:"async plugin jobs require runtimeApi 1.7, a result variable and a valid timer period".into() });
        }
        if let Err(reason) = require_declared_variable_type(
            manifest,
            &flow.declaration.flow_id,
            arguments.result_variable.as_deref(),
            &[flow_ir::ProjectVariableType::Integer],
            "job ID must be an integer variable",
        ) {
            errors.push(CompileError::InvalidNodeArguments {
                node_id: node.envelope.node_id.clone(),
                reason: reason.into(),
            });
        }
    } else if arguments.period_ms.is_some() || arguments.result_variable.is_some() {
        errors.push(CompileError::InvalidNodeArguments {
            node_id: node.envelope.node_id.clone(),
            reason: "flow.call does not accept job options".into(),
        });
    }
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

fn node_is_inside_loop(
    node: &flow_ir::FlowNode,
    node_by_id: &HashMap<&str, &flow_ir::FlowNode>,
) -> bool {
    let mut parent = node.envelope.parent_id.as_deref();
    while let Some(id) = parent {
        let Some(owner) = node_by_id.get(id) else {
            break;
        };
        if matches!(
            owner.envelope.kind.as_str(),
            "control.repeat" | "control.while" | "control.cycle"
        ) {
            return true;
        }
        parent = owner.envelope.parent_id.as_deref();
    }
    false
}

fn is_builtin_kind(kind: &str) -> bool {
    crate::utility_operations::spec(kind).is_some()
        || crate::screen_resources::KINDS.contains(&kind)
        || crate::script_controls::KINDS.contains(&kind)
        || crate::runtime_tools::KINDS.contains(&kind)
        || crate::vision_extensions::KINDS.contains(&kind)
        || matches!(
            kind,
            "ui.get"
                | "vision.findallimages"
                | "vision.result"
                | "data.length"
                | "data.get"
                | "data.set"
                | "data.remove"
                | "data.append"
                | "data.keys"
                | "data.text"
                | "files.read"
                | "files.write"
                | "files.list"
                | "files.remove"
                | "ui.set"
                | "ui.command"
                | "ui.confirm"
                | "ui.dialog"
                | "ui.dialogclose"
                | "task.sleep"
                | "task.cancel"
                | "timer.cancel"
                | "task.log"
                | "task.prompt"
                | "task.message"
                | "task.runprompt"
                | "input.tap"
                | "input.pointerdown"
                | "input.pointermove"
                | "input.pointerup"
                | "input.swipe"
                | "input.keyevent"
                | "control.if"
                | "control.repeat"
                | "control.while"
                | "control.cycle"
                | "control.loopmetric"
                | "control.loopcheck"
                | "variable.set"
                | "variable.calculate"
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
                | "vision.findgray"
                | "ocr.alphanumeric"
                | "ocr.glyph"
                | "legacy.duodianzhaose"
                | "legacy.duodianbise"
                | "legacy.getrectcolornum"
                | "legacy.getrgbcolor"
        )
}

fn validate_builtin_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    flow_id: &str,
    capabilities: &HashSet<&str>,
    builtins: &mut HashMap<String, BuiltinNodeArgs>,
    errors: &mut Vec<CompileError>,
) {
    validate_builtin_requirements(kind, node, manifest, capabilities, errors);
    let parsed = match kind {
        kind if crate::runtime_tools::KINDS.contains(&kind) => {
            crate::runtime_tools::parse(kind, &node.envelope.args).map(BuiltinNodeArgs::RuntimeTool)
        }
        kind if crate::vision_extensions::KINDS.contains(&kind) => {
            crate::vision_extensions::parse(kind, &node.envelope.args)
                .map(BuiltinNodeArgs::VisionExtension)
        }
        kind if crate::script_controls::KINDS.contains(&kind) => {
            crate::script_controls::parse(kind, &node.envelope.args)
                .map(BuiltinNodeArgs::ScriptControl)
        }
        kind if crate::screen_resources::KINDS.contains(&kind) => {
            crate::screen_resources::parse(kind, &node.envelope.args)
                .map(BuiltinNodeArgs::ScreenResource)
        }
        kind if crate::utility_operations::spec(kind).is_some() => {
            crate::utility_operations::parse(kind, &node.envelope.args)
                .map(BuiltinNodeArgs::Utility)
        }
        "ui.confirm" | "ui.dialog" | "ui.dialogclose" => {
            crate::ui_dialog::parse(kind, node, manifest)
                .map(|v| BuiltinNodeArgs::Dialog(v, kind.into()))
        }
        "data.length" | "data.get" | "data.set" | "data.remove" | "data.append" | "data.keys"
        | "data.text" | "files.read" | "files.write" | "files.list" | "files.remove" | "ui.get"
        | "ui.set" | "ui.command" | "task.cancel" | "timer.cancel" => {
            parse_data_ui_arguments(kind, node)
        }
        "task.sleep" | "task.log" | "task.message" | "task.prompt" | "task.runprompt" => {
            parse_task_arguments(kind, node, manifest)
        }
        "input.tap" | "input.pointerdown" | "input.pointermove" | "input.pointerup"
        | "input.swipe" | "input.keyevent" => {
            if node
                .envelope
                .args
                .get("screenPixels")
                .and_then(Value::as_bool)
                == Some(true)
                && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
            {
                Err("屏幕像素输入需要runtimeApi 1.8")
            } else {
                parse_input_arguments(kind, node)
            }
        }
        "control.if" | "control.repeat" | "control.while" | "control.cycle"
        | "control.loopmetric" | "control.loopcheck" => {
            parse_control_arguments(kind, node, manifest, errors)
        }
        "variable.set" | "variable.calculate" | "variable.copy" => {
            parse_variable_arguments(kind, node)
        }
        "vision.findallimages"
        | "vision.result"
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
        | "vision.findgray"
        | "ocr.alphanumeric"
        | "legacy.duodianzhaose"
        | "legacy.duodianbise"
        | "legacy.getrectcolornum"
        | "legacy.getrgbcolor" => parse_vision_arguments(kind, node, manifest),
        _ => unreachable!("caller filters builtin kinds"),
    };
    match parsed {
        Ok(arguments) => {
            if kind == "vision.findallimages" {
                validate_child_blocks(node, &["success", "failure"], errors);
                if let BuiltinNodeArgs::FindAllImages(v) = &arguments {
                    let capabilities = manifest.capabilities.iter().map(String::as_str).collect();
                    if v.success_action != RecognitionAction::None {
                        require_capability(node, "input.basic", &capabilities, errors);
                    }
                    if v.before_wait_ms > 0
                        || v.after_wait_ms > 0
                        || matches!(
                            v.success_action,
                            RecognitionAction::TapWait | RecognitionAction::PressRelease
                        )
                    {
                        require_capability(node, "core.task", &capabilities, errors);
                    }
                }
            }
            if let Err(reason) = validate_declared_variable_types(manifest, flow_id, &arguments) {
                invalid_arguments(node, reason, errors);
            } else {
                builtins.insert(node.envelope.node_id.clone(), arguments);
            }
        }
        Err(reason) => invalid_arguments(node, reason, errors),
    }
}

fn parse_data_ui_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "data.length" | "data.get" | "data.set" | "data.remove" | "data.append" | "data.keys"
        | "data.text" | "files.read" | "files.write" | "files.list" | "files.remove" => {
            crate::data_operations::parse(kind, &node.envelope.args).map(BuiltinNodeArgs::Data)
        }
        "ui.get" | "ui.set" | "ui.command" => {
            serde_json::from_value::<UiArgs>(node.envelope.args.clone())
                .ok()
                .filter(|v| {
                    valid_control_id(&v.control_id)
                        && v.value.len() <= 8192
                        && v.value_variable.as_deref().is_none_or(valid_variable_name)
                        && if kind == "ui.get"
                            || (kind == "ui.command" && v.operation.starts_with("get:"))
                        {
                            v.result_variable
                                .as_deref()
                                .is_some_and(valid_variable_name)
                                && (kind == "ui.get"
                                    || (valid_ui_operation(&v.operation)
                                        && v.value.is_empty()
                                        && v.value_variable.is_none()))
                        } else {
                            v.result_variable.is_none()
                                && (kind != "ui.command" || valid_ui_operation(&v.operation))
                        }
                })
                .map(|v| BuiltinNodeArgs::Ui(v, kind.to_owned()))
                .ok_or("invalid UI control or operation")
        }
        "task.cancel" | "timer.cancel" => {
            serde_json::from_value::<JobCancelArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| valid_variable_name(&value.id_variable))
                .map(|value| BuiltinNodeArgs::JobCancel(value, kind == "timer.cancel"))
                .ok_or("job cancellation requires a valid idVariable")
        }
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn parse_task_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "task.sleep" => serde_json::from_value::<SleepArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.milliseconds >= 0
                    && value
                        .milliseconds_variable
                        .as_deref()
                        .is_none_or(valid_variable_name)
            })
            .map(BuiltinNodeArgs::Sleep)
            .ok_or("task.sleep requires non-negative integer milliseconds"),
        "task.log" => serde_json::from_value::<LogArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                !value.message.is_empty()
                    && value.message.len() <= 2_048
                    && value
                        .value_variable
                        .as_deref()
                        .is_none_or(valid_variable_name)
            })
            .map(BuiltinNodeArgs::Log)
            .ok_or("task.log requires a valid level, message, and optional variable name"),
        "task.message" => serde_json::from_value::<MessageArgs>(node.envelope.args.clone())
            .map_err(|_| "dynamic output requires expression and a valid channel")
            .and_then(|value| {
                if !runtime_api_at_least(&manifest.runtime_api, 1, 6) {
                    return Err("dynamic output requires runtimeApi 1.6 or later");
                }
                crate::variable_expression::render(&value.expression)?;
                Ok(BuiltinNodeArgs::Message(value))
            }),
        "task.prompt" => serde_json::from_value::<PromptArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                !value.message.is_empty()
                    && value.message.len() <= 2_048
                    && value
                        .value_variable
                        .as_deref()
                        .is_none_or(valid_variable_name)
            })
            .map(BuiltinNodeArgs::Prompt)
            .ok_or("task.prompt requires a valid message and optional variable name"),
        "task.runprompt" => serde_json::from_value::<PromptArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                !value.message.is_empty()
                    && value.message.len() <= 2_048
                    && value
                        .value_variable
                        .as_deref()
                        .is_none_or(valid_variable_name)
            })
            .map(BuiltinNodeArgs::RunPrompt)
            .ok_or("task.runprompt requires a valid message and optional variable name"),
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn parse_input_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "input.tap" => serde_json::from_value::<TapArgs>(node.envelope.args.clone())
            .map(BuiltinNodeArgs::Tap)
            .map_err(|_| "input.tap requires 32-bit integer x and y"),
        "input.pointerdown" => serde_json::from_value::<PointerArgs>(node.envelope.args.clone())
            .map(BuiltinNodeArgs::PointerDown)
            .map_err(|_| "input.pointerdown requires 32-bit integer x and y"),
        "input.pointermove" => serde_json::from_value::<PointerArgs>(node.envelope.args.clone())
            .map(BuiltinNodeArgs::PointerMove)
            .map_err(|_| "input.pointermove requires 32-bit integer x and y"),
        "input.pointerup" => {
            serde_json::from_value::<serde_json::Map<String, Value>>(node.envelope.args.clone())
                .ok()
                .filter(serde_json::Map::is_empty)
                .map(|_| BuiltinNodeArgs::PointerUp)
                .ok_or("input.pointerup requires empty args")
        }
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
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn parse_control_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    errors: &mut Vec<CompileError>,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "control.if" => serde_json::from_value::<ComparisonArgs>(node.envelope.args.clone())
            .ok()
            .filter(|v| {
                valid_comparison(v)
                    && ((v.condition.is_none() && v.else_if.iter().all(|b| b.condition.is_none()))
                        || (node.envelope.node_version >= 2
                            && runtime_api_at_least(&manifest.runtime_api, 1, 7)))
            })
            .map(|value| {
                validate_conditional_child_blocks(node, value.else_if.len(), errors);
                BuiltinNodeArgs::If(value)
            })
            .ok_or("control.if requires a valid variable comparison"),
        "control.repeat" => {
            validate_child_blocks(node, &["body"], errors);
            serde_json::from_value::<RepeatArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.times <= 1_000_000
                        && value.times_variable.as_deref().is_none_or(valid_variable_name)
                        && value.index_variable.as_deref().is_none_or(valid_variable_name)
                        && value.elapsed_variable.as_deref().is_none_or(valid_variable_name)
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
                        && !(value.duration_ms.is_some() && value.duration_variable.is_some())
                        && (value.duration_variable.is_some()
                            || value.duration_unit == LoopTimeUnit::Milliseconds)
                        && valid_comparison_parts(&value.variable, value.operator, &value.value)
                        && value
                            .duration_ms
                            .is_none_or(|duration| duration > 0 && duration <= 86_400_000)
                        && value
                            .duration_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                        && value
                            .iteration_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                        && value
                            .elapsed_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                })
                .map(BuiltinNodeArgs::While)
                .ok_or(
                    "control.while requires a valid comparison and maxIterations from 1 to 1000000",
                )
        }
        "control.cycle" => {
            validate_child_blocks(node, &["body"], errors);
            serde_json::from_value::<CycleArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    (1..=1000).contains(&value.yield_ms)
                        && !(value.duration_ms.is_some() && value.duration_variable.is_some())
                        && (value.duration_variable.is_some()
                            || value.duration_unit == LoopTimeUnit::Milliseconds)
                        && value
                            .duration_ms
                            .is_none_or(|duration| (1..=86_400_000).contains(&duration))
                        && value
                            .duration_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                        && value
                            .iteration_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                        && value
                            .elapsed_variable
                            .as_deref()
                            .is_none_or(valid_variable_name)
                })
                .map(|value| BuiltinNodeArgs::While(value.into()))
                .ok_or("可取消循环需要1..1000毫秒让步、有效时长及指标变量")
        }
        "control.loopmetric" | "control.loopcheck" => parse_loop_metric_arguments(kind, node),
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn parse_loop_metric_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "control.loopmetric" => {
            serde_json::from_value::<LoopMetricArgs>(node.envelope.args.clone())
                .ok()
                .filter(|v| {
                    valid_variable_name(&v.name)
                        && (v.metric == LoopMetric::Elapsed || v.unit == LoopTimeUnit::Milliseconds)
                })
                .map(BuiltinNodeArgs::LoopMetric)
                .ok_or("循环指标需要正确的变量、指标及单位")
        }
        "control.loopcheck" => serde_json::from_value::<LoopCheckArgs>(node.envelope.args.clone())
            .ok()
            .filter(|v| {
                v.limit.is_finite()
                    && v.limit_variable.as_deref().is_none_or(valid_variable_name)
                    && (v.metric == LoopMetric::Elapsed || v.unit == LoopTimeUnit::Milliseconds)
                    && if v.limit_variable.is_some() {
                        v.limit >= 0.0 && v.limit <= 86_400_000.0
                    } else {
                        match v.metric {
                            LoopMetric::Count => {
                                v.limit.fract() == 0.0 && (1.0..=1_000_000.0).contains(&v.limit)
                            }
                            LoopMetric::Elapsed => {
                                (1.0..=86_400_000.0)
                                    .contains(&(v.limit * f64::from(v.unit.factor())))
                                    && (v.limit * f64::from(v.unit.factor())).fract() == 0.0
                            }
                        }
                    }
            })
            .map(BuiltinNodeArgs::LoopCheck)
            .ok_or("循环检查阈值或单位无效"),
        _ => unreachable!("loop metric parser dispatch"),
    }
}

fn parse_variable_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "variable.set" => serde_json::from_value::<VariableSetArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| valid_variable_name(&value.name) && is_scalar(&value.value))
            .map(BuiltinNodeArgs::VariableSet)
            .ok_or("variable.set requires a valid name and scalar value"),
        "variable.calculate" => {
            serde_json::from_value::<VariableCalculateArgs>(node.envelope.args.clone())
                .map_err(|_| "计算需要name和expression参数")
                .and_then(|value| {
                    if !valid_variable_name(&value.name) {
                        return Err("计算目标变量名无效");
                    }
                    crate::variable_expression::render(&value.expression)?;
                    Ok(BuiltinNodeArgs::VariableCalculate(value))
                })
        }
        "variable.copy" => serde_json::from_value::<VariableCopyArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                valid_variable_name(&value.name) && valid_variable_name(&value.source_name)
            })
            .map(BuiltinNodeArgs::VariableCopy)
            .ok_or("variable.copy requires valid name and sourceName"),
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn parse_vision_arguments(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "vision.findallimages"
        | "vision.result"
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
        | "vision.findgray"
        | "ocr.alphanumeric" => parse_visual_node(kind, node, manifest),
        "legacy.duodianzhaose"
        | "legacy.duodianbise"
        | "legacy.getrectcolornum"
        | "legacy.getrgbcolor" => parse_legacy_node(kind, node),
        _ => unreachable!("builtin parser dispatch"),
    }
}

fn valid_ui_operation(operation: &str) -> bool {
    if matches!(
        operation,
        "backgroundImage"
            | "get:backgroundImage"
            | "groupVisible"
            | "groupEnabled"
            | "groupReset"
            | "get:groupCount"
            | "windowPosition"
            | "bubblePosition"
            | "get:windowPosition"
            | "get:bubblePosition"
    ) {
        return true;
    }
    if matches!(
        operation,
        "hint"
            | "minimum"
            | "maximum"
            | "step"
            | "range"
            | "get:hint"
            | "get:minimum"
            | "get:maximum"
            | "get:step"
            | "get:config"
            | "get:pages"
            | "get:controls"
            | "restoreConfig"
    ) {
        return true;
    }
    if matches!(
        operation,
        "itemAdd"
            | "itemInsert"
            | "itemSet"
            | "itemRemove"
            | "itemsClear"
            | "selectedIndex"
            | "selectedIndices"
            | "get:itemCount"
            | "get:selectedIndex"
            | "get:selectedIndices"
    ) {
        return true;
    }
    let property = operation.strip_prefix("get:").unwrap_or(operation);
    if matches!(
        property,
        "fontBold" | "textUnderline" | "borderColor" | "borderWidthPx" | "cornerPx"
    ) {
        return true;
    }
    if let Some(property) = operation.strip_prefix("get:") {
        matches!(
            property,
            "x" | "y"
                | "width"
                | "height"
                | "fontPx"
                | "textColor"
                | "background"
                | "alignment"
                | "value"
                | "text"
                | "visible"
                | "enabled"
                | "items"
                | "progress"
        )
    } else {
        matches!(
            operation,
            "text"
                | "visible"
                | "enabled"
                | "items"
                | "progress"
                | "page"
                | "show"
                | "hide"
                | "minimize"
                | "x"
                | "y"
                | "width"
                | "height"
                | "fontPx"
                | "textColor"
                | "background"
                | "alignment"
        )
    }
}

pub(crate) fn declared_variable_type(
    manifest: &ProjectManifest,
    flow_id: &str,
    name: &str,
) -> Option<flow_ir::ProjectVariableType> {
    // A Flow-local declaration intentionally shadows a global declaration of
    // the same name, matching the editor's local/global chooser.
    manifest
        .variables
        .iter()
        .find(|variable| {
            variable.name == name
                && matches!(variable.scope, flow_ir::ProjectVariableScope::Flow)
                && variable.flow_id.as_deref() == Some(flow_id)
        })
        .or_else(|| {
            manifest.variables.iter().find(|variable| {
                variable.name == name
                    && matches!(variable.scope, flow_ir::ProjectVariableScope::Global)
            })
        })
        .map(|variable| variable.value_type)
}

fn scalar_matches(value: &Value, expected: flow_ir::ProjectVariableType) -> bool {
    match expected {
        flow_ir::ProjectVariableType::Integer => value.as_i64().is_some(),
        flow_ir::ProjectVariableType::Number => value.as_f64().is_some(),
        flow_ir::ProjectVariableType::String => value.as_str().is_some(),
        flow_ir::ProjectVariableType::Image => false,
    }
}

fn validate_declared_variable_types(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::ScreenResource(value) => value.validate(manifest, flow_id),
        BuiltinNodeArgs::VisionExtension(value) => value.validate(manifest, flow_id),
        BuiltinNodeArgs::RuntimeTool(value) => value.validate(manifest, flow_id),
        BuiltinNodeArgs::Utility(value) => {
            value.validate_types(|name| loop_declared_type(manifest, flow_id, name))
        }
        BuiltinNodeArgs::Dialog(value, _) => require_loop_variable_type(
            manifest,
            flow_id,
            value.result_variable.as_deref(),
            &[flow_ir::ProjectVariableType::String],
            "对话框结果变量必须声明为字符型",
        ),
        BuiltinNodeArgs::FindAllImages(..)
        | BuiltinNodeArgs::MatchResult(..)
        | BuiltinNodeArgs::Ui(..)
        | BuiltinNodeArgs::Data(..) => {
            validate_result_and_ui_variables(manifest, flow_id, arguments)
        }
        BuiltinNodeArgs::Gray(..)
        | BuiltinNodeArgs::Alphanumeric(..)
        | BuiltinNodeArgs::JobCancel(..) => {
            validate_native_recognition_variables(manifest, flow_id, arguments)
        }
        BuiltinNodeArgs::FindImage(..) => {
            validate_find_image_variables(manifest, flow_id, arguments)
        }
        BuiltinNodeArgs::VariableSet(..)
        | BuiltinNodeArgs::VariableCalculate(..)
        | BuiltinNodeArgs::Message(..)
        | BuiltinNodeArgs::VariableCopy(..) => {
            validate_assignment_variables(manifest, flow_id, arguments)
        }
        BuiltinNodeArgs::If(..) | BuiltinNodeArgs::Repeat(..) | BuiltinNodeArgs::While(..) => {
            validate_condition_and_loop_variables(manifest, flow_id, arguments)
        }
        BuiltinNodeArgs::LoopMetric(..) | BuiltinNodeArgs::LoopCheck(..) => {
            validate_loop_output_variables(manifest, flow_id, arguments)
        }
        _ => Ok(()),
    }
}

fn validate_result_and_ui_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::FindAllImages(v) => {
            if !v.auto_capture {
                require_match_variable_type(
                    manifest,
                    flow_id,
                    Some(&v.frame_variable),
                    &[flow_ir::ProjectVariableType::Image],
                    "借用帧必须为图像变量",
                )?;
            }
            require_match_variable_type(
                manifest,
                flow_id,
                Some(&v.result_variable),
                &[flow_ir::ProjectVariableType::String],
                "多图结果集合必须写入已声明字符变量",
            )
        }
        BuiltinNodeArgs::MatchResult(v) => {
            require_match_variable_type(
                manifest,
                flow_id,
                Some(&v.source_variable),
                &[flow_ir::ProjectVariableType::String],
                "结果集合必须是字符变量",
            )?;
            require_match_variable_type(
                manifest,
                flow_id,
                Some(&v.target_variable),
                &[if v.field == "path" {
                    flow_ir::ProjectVariableType::String
                } else {
                    flow_ir::ProjectVariableType::Integer
                }],
                "结果属性目标类型不匹配",
            )
        }
        BuiltinNodeArgs::Ui(value, kind)
            if kind == "ui.get" || value.operation.starts_with("get:") =>
        {
            require_loop_variable_type(
                manifest,
                flow_id,
                value.result_variable.as_deref(),
                &[flow_ir::ProjectVariableType::String],
                "控件值输出变量必须是字符串型",
            )
        }
        BuiltinNodeArgs::Data(value) => {
            for operand in &value.operands {
                if let Some(name) = operand.variable.as_deref() {
                    if loop_declared_type(manifest, flow_id, name)
                        != Some(flow_ir::ProjectVariableType::String)
                    {
                        return Err("数据和文件来源变量必须声明为字符型");
                    }
                }
            }
            require_loop_variable_type(
                manifest,
                flow_id,
                value.result_variable.as_deref(),
                if value.integer_result {
                    &[flow_ir::ProjectVariableType::Integer]
                } else {
                    &[flow_ir::ProjectVariableType::String]
                },
                "数据和文件结果变量类型不匹配",
            )
        }
        _ => Ok(()),
    }
}

fn validate_native_recognition_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::Gray(value) => {
            require_declared_variable_type(
                manifest,
                flow_id,
                Some(&value.frame_variable),
                &[flow_ir::ProjectVariableType::Image],
                "帧变量必须是图像型",
            )?;
            for name in [
                &value.found_variable,
                &value.x_variable,
                &value.y_variable,
                &value.score_variable,
            ] {
                require_declared_variable_type(
                    manifest,
                    flow_id,
                    Some(name),
                    &[flow_ir::ProjectVariableType::Integer],
                    "灰度结果变量必须是整型（找到为1/0）",
                )?;
            }
            Ok(())
        }
        BuiltinNodeArgs::Alphanumeric(value) => {
            require_declared_variable_type(
                manifest,
                flow_id,
                Some(&value.frame_variable),
                &[flow_ir::ProjectVariableType::Image],
                "帧变量必须是图像型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                Some(&value.text_variable),
                &[flow_ir::ProjectVariableType::String],
                "OCR文本变量必须是字符串型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                Some(&value.score_variable),
                &[flow_ir::ProjectVariableType::Integer],
                "OCR得分变量必须是整型",
            )
        }
        BuiltinNodeArgs::JobCancel(value, _) => require_declared_variable_type(
            manifest,
            flow_id,
            Some(&value.id_variable),
            &[flow_ir::ProjectVariableType::Integer],
            "job ID must be an integer variable",
        ),
        _ => Ok(()),
    }
}

fn validate_find_image_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::FindImage(value) => {
            if let Some(parameter) = manifest
                .flows
                .iter()
                .find(|flow| flow.flow_id == flow_id)
                .and_then(|flow| {
                    flow.params
                        .iter()
                        .find(|parameter| parameter.name == value.found_variable)
                })
            {
                if !matches!(
                    parameter.value_type,
                    ValueType::Boolean | ValueType::Integer | ValueType::Number
                ) {
                    return Err("找到结果参数必须是布尔或数值型");
                }
            } else {
                require_declared_variable_type(
                    manifest,
                    flow_id,
                    Some(&value.found_variable),
                    &[
                        flow_ir::ProjectVariableType::Integer,
                        flow_ir::ProjectVariableType::Number,
                    ],
                    "找到结果的声明必须是整型或数值型",
                )?;
            }
            for (name, kinds) in [
                (
                    Some(value.frame_variable.as_str()),
                    &[flow_ir::ProjectVariableType::Image][..],
                ),
                (
                    Some(value.x_variable.as_str()),
                    &[flow_ir::ProjectVariableType::Integer][..],
                ),
                (
                    Some(value.y_variable.as_str()),
                    &[flow_ir::ProjectVariableType::Integer][..],
                ),
                (
                    value.template_variable.as_deref(),
                    &[flow_ir::ProjectVariableType::String][..],
                ),
                (
                    value.score_variable.as_deref(),
                    &[flow_ir::ProjectVariableType::Integer][..],
                ),
            ] {
                require_declared_variable_type(
                    manifest,
                    flow_id,
                    name,
                    kinds,
                    "识别输出/帧变量的声明类型不匹配",
                )?;
            }
            for name in value.region_variables() {
                require_declared_variable_type(
                    manifest,
                    flow_id,
                    name,
                    &[flow_ir::ProjectVariableType::Integer],
                    "识别区域变量必须是整型",
                )?;
            }
            require_declared_variable_type(
                manifest,
                flow_id,
                value.image_variable.as_deref(),
                &[flow_ir::ProjectVariableType::Image],
                "识别图像输出必须是图像型",
            )?;
            Ok(())
        }
        _ => Ok(()),
    }
}

fn validate_assignment_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::VariableSet(value) => {
            declared_variable_type(manifest, flow_id, &value.name).map_or(Ok(()), |expected| {
                scalar_matches(&value.value, expected)
                    .then_some(())
                    .ok_or("变量赋值与声明类型不匹配")
            })
        }
        BuiltinNodeArgs::VariableCalculate(value) => {
            let lookup = |name: &str| {
                if let Some(param) = manifest
                    .flows
                    .iter()
                    .find(|flow| flow.flow_id == flow_id)
                    .and_then(|flow| flow.params.iter().find(|param| param.name == name))
                {
                    match param.value_type {
                        ValueType::Integer => Some(flow_ir::ProjectVariableType::Integer),
                        ValueType::Number => Some(flow_ir::ProjectVariableType::Number),
                        ValueType::String => Some(flow_ir::ProjectVariableType::String),
                        ValueType::Boolean => None,
                    }
                } else {
                    declared_variable_type(manifest, flow_id, name)
                }
            };
            crate::variable_expression::validate(&value.expression, lookup(&value.name), lookup)
        }
        BuiltinNodeArgs::Message(value) => {
            let lookup = |name: &str| {
                if let Some(param) = manifest
                    .flows
                    .iter()
                    .find(|flow| flow.flow_id == flow_id)
                    .and_then(|flow| flow.params.iter().find(|param| param.name == name))
                {
                    match param.value_type {
                        ValueType::Integer => Some(flow_ir::ProjectVariableType::Integer),
                        ValueType::Number => Some(flow_ir::ProjectVariableType::Number),
                        ValueType::String => Some(flow_ir::ProjectVariableType::String),
                        ValueType::Boolean => None,
                    }
                } else {
                    declared_variable_type(manifest, flow_id, name)
                }
            };
            crate::variable_expression::validate(
                &value.expression,
                Some(flow_ir::ProjectVariableType::String),
                lookup,
            )
        }
        BuiltinNodeArgs::VariableCopy(value) => match (
            declared_variable_type(manifest, flow_id, &value.name),
            declared_variable_type(manifest, flow_id, &value.source_name),
        ) {
            (Some(left), Some(right)) if left != right => Err("复制变量的两端类型不匹配"),
            _ => Ok(()),
        },
        _ => Ok(()),
    }
}

fn validate_condition_and_loop_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::If(value) => {
            let mut comparisons = Vec::new();
            if let Some(tree) = &value.condition {
                crate::compound_condition::variable_pairs(tree, &mut comparisons);
            } else {
                comparisons.push((value.variable.as_str(), value.value_variable.as_deref()));
            }
            for branch in &value.else_if {
                if let Some(tree) = &branch.condition {
                    crate::compound_condition::variable_pairs(tree, &mut comparisons);
                } else {
                    comparisons.push((branch.variable.as_str(), branch.value_variable.as_deref()));
                }
            }
            for (left_name, right_name) in comparisons {
                if let (Some(left), Some(right)) = (
                    declared_variable_type(manifest, flow_id, left_name),
                    right_name.and_then(|name| declared_variable_type(manifest, flow_id, name)),
                ) {
                    if left != right {
                        return Err("判断两端变量类型不匹配");
                    }
                }
            }
            Ok(())
        }
        BuiltinNodeArgs::Repeat(value) => {
            require_loop_variable_type(
                manifest,
                flow_id,
                value.times_variable.as_deref(),
                &[flow_ir::ProjectVariableType::Integer],
                "循环次数变量必须是整型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                value.index_variable.as_deref(),
                &[flow_ir::ProjectVariableType::Integer],
                "循环序号变量必须是整型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                value.elapsed_variable.as_deref(),
                &[
                    flow_ir::ProjectVariableType::Integer,
                    flow_ir::ProjectVariableType::Number,
                ],
                "循环时间变量必须是数值型",
            )
        }
        BuiltinNodeArgs::While(value) => {
            require_loop_variable_type(
                manifest,
                flow_id,
                value.duration_variable.as_deref(),
                &[
                    flow_ir::ProjectVariableType::Integer,
                    flow_ir::ProjectVariableType::Number,
                ],
                "循环时长变量必须是数值型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                value.iteration_variable.as_deref(),
                &[flow_ir::ProjectVariableType::Integer],
                "循环次数变量必须是整型",
            )?;
            require_declared_variable_type(
                manifest,
                flow_id,
                value.elapsed_variable.as_deref(),
                &[
                    flow_ir::ProjectVariableType::Integer,
                    flow_ir::ProjectVariableType::Number,
                ],
                "循环时间变量必须是数值型",
            )
        }
        _ => Ok(()),
    }
}

fn validate_loop_output_variables(
    manifest: &ProjectManifest,
    flow_id: &str,
    arguments: &BuiltinNodeArgs,
) -> Result<(), &'static str> {
    match arguments {
        BuiltinNodeArgs::LoopMetric(value) => {
            require_loop_variable_type(
                manifest,
                flow_id,
                Some(&value.name),
                if value.metric == LoopMetric::Count {
                    &[flow_ir::ProjectVariableType::Integer]
                } else if value.unit == LoopTimeUnit::Milliseconds {
                    &[
                        flow_ir::ProjectVariableType::Integer,
                        flow_ir::ProjectVariableType::Number,
                    ]
                } else {
                    &[flow_ir::ProjectVariableType::Number]
                },
                "循环指标输出变量类型不匹配",
            )?;
            if loop_declared_type(manifest, flow_id, &value.name).is_none() {
                return Err("循环指标输出变量必须声明");
            }
            Ok(())
        }
        BuiltinNodeArgs::LoopCheck(value) => {
            if let Some(name) = &value.limit_variable {
                if loop_declared_type(manifest, flow_id, name).is_none() {
                    return Err("循环检查阈值变量必须声明");
                }
            }
            require_loop_variable_type(
                manifest,
                flow_id,
                value.limit_variable.as_deref(),
                if value.metric == LoopMetric::Count {
                    &[flow_ir::ProjectVariableType::Integer]
                } else {
                    &[
                        flow_ir::ProjectVariableType::Integer,
                        flow_ir::ProjectVariableType::Number,
                    ]
                },
                "循环检查阈值变量类型不匹配",
            )
        }
        _ => Ok(()),
    }
}

fn require_match_variable_type(
    manifest: &ProjectManifest,
    flow_id: &str,
    name: Option<&str>,
    allowed: &[flow_ir::ProjectVariableType],
    message: &'static str,
) -> Result<(), &'static str> {
    let Some(name) = name else { return Ok(()) };
    loop_declared_type(manifest, flow_id, name)
        .filter(|ty| allowed.contains(ty))
        .map(|_| ())
        .ok_or(message)
}

fn require_declared_variable_type(
    manifest: &ProjectManifest,
    flow_id: &str,
    name: Option<&str>,
    allowed: &[flow_ir::ProjectVariableType],
    message: &'static str,
) -> Result<(), &'static str> {
    let Some(name) = name else { return Ok(()) };
    declared_variable_type(manifest, flow_id, name).map_or(Ok(()), |actual| {
        allowed.contains(&actual).then_some(()).ok_or(message)
    })
}

fn loop_declared_type(
    manifest: &ProjectManifest,
    flow_id: &str,
    name: &str,
) -> Option<flow_ir::ProjectVariableType> {
    if let Some(param) = manifest
        .flows
        .iter()
        .find(|flow| flow.flow_id == flow_id)?
        .params
        .iter()
        .find(|p| p.name == name)
    {
        return match param.value_type {
            ValueType::Integer => Some(flow_ir::ProjectVariableType::Integer),
            ValueType::Number => Some(flow_ir::ProjectVariableType::Number),
            ValueType::String => Some(flow_ir::ProjectVariableType::String),
            ValueType::Boolean => None,
        };
    }
    declared_variable_type(manifest, flow_id, name)
}
fn require_loop_variable_type(
    manifest: &ProjectManifest,
    flow_id: &str,
    name: Option<&str>,
    allowed: &[flow_ir::ProjectVariableType],
    message: &'static str,
) -> Result<(), &'static str> {
    let Some(name) = name else { return Ok(()) };
    let parameter = manifest
        .flows
        .iter()
        .find(|flow| flow.flow_id == flow_id)
        .and_then(|flow| flow.params.iter().find(|p| p.name == name));
    let actual = loop_declared_type(manifest, flow_id, name);
    if parameter.is_some() || actual.is_some() {
        actual
            .filter(|ty| allowed.contains(ty))
            .map(|_| ())
            .ok_or(message)
    } else {
        Ok(())
    }
}

#[allow(clippy::too_many_lines)]
fn validate_builtin_requirements(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    capabilities: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    if (matches!(
        kind,
        "screen.frameinfo" | "screen.captureregion" | "text.numberat"
    ) || (kind == "ocr.alphanumeric"
        && node
            .envelope
            .args
            .get("includeSymbols")
            .and_then(Value::as_bool)
            == Some(true))
        || crate::codec_utility_specs::SPECS
            .iter()
            .any(|(name, _)| *name == kind))
        && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
    {
        invalid_arguments(node, "该新增工具需要runtimeApi 1.8", errors);
    }
    if let Some(spec) = crate::utility_operations::spec(kind) {
        require_capability(node, spec.capability, capabilities, errors);
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "基础工具需要runtimeApi 1.7", errors);
        }
        if [
            "files.getsize",
            "files.getextension",
            "files.directorystats",
            "files.tobase64",
            "files.frombase64",
            "files.zip",
            "files.unzip",
            "files.modifiedtime",
            "system.info",
            "system.keepawake",
            "app.openurl",
            "network.request",
            "input.imestatus",
            "input.imetext",
            "input.touchdown",
            "input.touchmove",
            "input.touchup",
            "floating.image",
            "floating.waitevent",
            "floating.closeimage",
        ]
        .contains(&kind)
            && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
        {
            invalid_arguments(node, "该工具需要runtimeApi 1.8", errors);
        }
    }
    if crate::screen_resources::KINDS.contains(&kind) {
        if kind == "screen.loadimage" {
            require_capability(node, "vision.template", capabilities, errors);
        } else if !matches!(kind, "screen.seriesframe" | "screen.frameinfo") {
            require_capability(node, "screen.capture", capabilities, errors);
        }
        if !matches!(
            kind,
            "screen.crop" | "screen.loadimage" | "screen.captureregion"
        ) {
            require_capability(node, "core.task", capabilities, errors);
        }
        if kind == "screen.series" {
            require_capability(node, "screen.capture.series", capabilities, errors);
        }
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "帧资源积木需要runtimeApi 1.7", errors);
        }
    }
    if crate::vision_extensions::KINDS.contains(&kind) {
        if kind == "vision.findfeatures" {
            require_capability(node, "vision.template", capabilities, errors);
        }
        if matches!(kind, "vision.findfeatures" | "ocr.alphanumericv5")
            && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
        {
            invalid_arguments(node, "该视觉工具需要runtimeApi 1.8", errors);
        }
        if kind == "vision.resultaction" {
            require_capability(node, "core.task", capabilities, errors);
            require_capability(node, "device.display", capabilities, errors);
        }
        if let Ok(value) = crate::vision_extensions::parse(kind, &node.envelope.args) {
            require_capability(node, value.capability(), capabilities, errors);
        }
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "图像扩展需要runtimeApi 1.7", errors);
        }
    }
    if crate::script_controls::KINDS.contains(&kind) {
        require_capability(node, "core.task", capabilities, errors);
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "脚本控制需要runtimeApi 1.7", errors);
        }
    }
    if crate::runtime_tools::KINDS.contains(&kind) {
        require_capability(
            node,
            if kind.starts_with("audio.") {
                "audio.control"
            } else if kind.starts_with("clipboard.") {
                "device.clipboard"
            } else if kind.starts_with("app.") {
                "device.apps"
            } else if kind.starts_with("floating.") {
                "ui.floating"
            } else {
                "input.basic"
            },
            capabilities,
            errors,
        );
        if kind == "audio.play"
            && !node
                .envelope
                .args
                .get("path")
                .and_then(serde_json::Value::as_str)
                .is_some_and(|path| path.starts_with("assets/audio/"))
        {
            require_capability(node, "storage.data", capabilities, errors);
        }
        if kind == "input.playrecording" {
            require_capability(node, "core.task", capabilities, errors);
        }
        if kind == "input.pastetext" {
            require_capability(node, "device.clipboard", capabilities, errors);
        }
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "运行工具需要runtimeApi 1.7", errors);
        }
        if (kind.starts_with("app.")
            || kind.starts_with("clipboard.")
            || kind.starts_with("floating.")
            || kind == "input.pastetext")
            && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
        {
            invalid_arguments(node, "平台工具需要runtimeApi 1.8", errors);
        }
    }
    validate_builtin_version_requirements(kind, node, manifest, capabilities, errors);
    validate_builtin_capabilities(kind, node, manifest, capabilities, errors);
    validate_builtin_leaf(kind, node, errors);
}

fn validate_builtin_version_requirements(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    capabilities: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    if kind.starts_with("data.") || kind.starts_with("files.") {
        require_capability(
            node,
            if kind.starts_with("files.") {
                "storage.data"
            } else {
                "core.task"
            },
            capabilities,
            errors,
        );
        validate_leaf(node, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "data/file operations require runtimeApi 1.7", errors);
        }
    }
    if matches!(kind, "task.cancel" | "timer.cancel") {
        require_capability(node, "core.task", capabilities, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            errors.push(CompileError::InvalidNodeArguments {
                node_id: node.envelope.node_id.clone(),
                reason: "job cancellation requires runtimeApi 1.7".into(),
            });
        }
        if !node.envelope.child_blocks.is_empty() {
            errors.push(CompileError::InvalidNodeArguments {
                node_id: node.envelope.node_id.clone(),
                reason: "job cancellation cannot own child blocks".into(),
            });
        }
    }
    validate_ui_command_requirements(kind, node, manifest, capabilities, errors);
    if matches!(kind, "ui.confirm" | "ui.dialog" | "ui.dialogclose") {
        validate_leaf(node, errors);
        require_capability(node, "ui.control", capabilities, errors);
        if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            invalid_arguments(node, "对话框需要 runtimeApi 1.7", errors);
        }
    }
    if kind == "vision.findimage" {
        if let Ok(value) = serde_json::from_value::<FindImageArgs>(node.envelope.args.clone()) {
            if value.success_action != RecognitionAction::None {
                require_capability(node, "input.basic", capabilities, errors);
                require_capability(node, "core.task", capabilities, errors);
            }
            if value.has_extended_options()
                && (node.envelope.node_version < 2
                    || !runtime_api_at_least(&manifest.runtime_api, 1, 7))
            {
                errors.push(CompileError::InvalidNodeArguments {
                    node_id: node.envelope.node_id.clone(),
                    reason: "extended vision.findimage requires nodeVersion 2 and runtimeApi 1.7"
                        .to_owned(),
                });
            }
        }
    }
    if matches!(
        kind,
        "input.pointerdown" | "input.pointermove" | "input.pointerup"
    ) && !runtime_api_at_least(&manifest.runtime_api, 1, 6)
    {
        errors.push(CompileError::InvalidNodeArguments {
            node_id: node.envelope.node_id.clone(),
            reason: format!(
                "{kind} requires project runtimeApi 1.6 or newer (current {})",
                manifest.runtime_api
            ),
        });
    }
}

fn validate_builtin_capabilities(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    capabilities: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    match kind {
        "task.sleep" | "task.log" | "task.prompt" | "task.runprompt" | "task.message"
        | "control.cycle" => require_capability(node, "core.task", capabilities, errors),
        "input.tap" | "input.pointerdown" | "input.pointermove" | "input.pointerup"
        | "input.swipe" | "input.keyevent" => {
            require_capability(node, "input.basic", capabilities, errors);
        }
        "screen.capture" => {
            require_capability(node, "screen.capture", capabilities, errors);
        }
        "screen.release" => {
            // Old projects declared only screen.capture for frame release. Preserve them.
            if !capabilities.contains("screen.capture") {
                require_capability(node, "core.task", capabilities, errors);
            }
        }
        "vision.getcolor"
        | "vision.findcolor"
        | "vision.comparecolor"
        | "vision.findmulticolor"
        | "vision.countcolor"
        | "vision.findallcolor" => {
            require_capability(node, "vision.pixel", capabilities, errors);
            if node
                .envelope
                .args
                .get("autoCapture")
                .and_then(Value::as_bool)
                == Some(true)
            {
                require_capability(node, "screen.capture", capabilities, errors);
            }
        }
        "vision.findimage" | "vision.findallimages" => {
            require_capability(node, "vision.template", capabilities, errors);
            require_capability(node, "screen.capture", capabilities, errors);
        }
        "ocr.glyph" => require_capability(node, "ocr.glyph", capabilities, errors),
        "vision.result" => {
            require_capability(node, "vision.template", capabilities, errors);
            require_capability(node, "core.task", capabilities, errors);
        }
        "vision.findgray" | "ocr.alphanumeric" => {
            if kind == "vision.findgray" {
                require_capability(node, "vision.template", capabilities, errors);
            }
            require_capability(
                node,
                if kind == "vision.findgray" {
                    "vision.opencv"
                } else {
                    "ocr.onnx"
                },
                capabilities,
                errors,
            );
            require_capability(node, "screen.capture", capabilities, errors);
            if !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
                errors.push(CompileError::InvalidNodeArguments {
                    node_id: node.envelope.node_id.clone(),
                    reason: "native vision requires runtimeApi 1.7".into(),
                });
            }
        }
        "legacy.duodianzhaose"
        | "legacy.duodianbise"
        | "legacy.getrectcolornum"
        | "legacy.getrgbcolor" => {
            require_capability(node, "vision.pixel.legacy", capabilities, errors);
            if kind == "legacy.getrectcolornum"
                && node
                    .envelope
                    .args
                    .get("autoCapture")
                    .and_then(Value::as_bool)
                    == Some(true)
            {
                require_capability(node, "screen.capture", capabilities, errors);
                if !runtime_api_at_least(&manifest.runtime_api, 1, 8) {
                    invalid_arguments(node, "临时截图颜色计数需要runtimeApi 1.8", errors);
                }
            }
        }
        _ => {}
    }
}

fn validate_builtin_leaf(kind: &str, node: &flow_ir::FlowNode, errors: &mut Vec<CompileError>) {
    if ((kind == "vision.findimage" && node.envelope.node_version >= 3)
        || (kind == "vision.findgray" && node.envelope.node_version >= 2))
        && !node.envelope.child_blocks.is_empty()
    {
        validate_child_blocks(node, &["success", "failure"], errors);
        return;
    }
    if matches!(
        kind,
        "task.sleep"
            | "task.log"
            | "task.message"
            | "task.prompt"
            | "task.runprompt"
            | "input.tap"
            | "input.pointerdown"
            | "input.pointermove"
            | "input.pointerup"
            | "input.swipe"
            | "input.keyevent"
            | "variable.set"
            | "variable.calculate"
            | "variable.copy"
            | "control.loopmetric"
            | "control.loopcheck"
            | "screen.capture"
            | "screen.release"
            | "vision.getcolor"
            | "vision.findcolor"
            | "vision.comparecolor"
            | "vision.findmulticolor"
            | "vision.countcolor"
            | "vision.findallcolor"
            | "vision.findimage"
            | "vision.result"
            | "vision.findgray"
            | "ocr.alphanumeric"
            | "ocr.glyph"
            | "legacy.duodianzhaose"
            | "legacy.duodianbise"
            | "legacy.getrectcolornum"
            | "legacy.getrgbcolor"
    ) {
        validate_leaf(node, errors);
    }
}

pub(crate) fn runtime_api_at_least(value: &str, required_major: u32, required_minor: u32) -> bool {
    let Some((major, minor)) = value.split_once('.') else {
        return false;
    };
    let (Ok(major), Ok(minor)) = (major.parse::<u32>(), minor.parse::<u32>()) else {
        return false;
    };
    major > required_major || (major == required_major && minor >= required_minor)
}

fn parse_legacy_node(
    kind: &str,
    node: &flow_ir::FlowNode,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "legacy.duodianzhaose" => {
            serde_json::from_value::<LegacyDuoDianZhaoSeArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.direction <= 4
                        && value.minimum_match_percent <= 100
                        && valid_legacy_region(&value.region)
                        && (1..=pixel_vision::MAX_LEGACY_PATTERN_SAMPLES)
                            .contains(&value.pattern.len())
                        && value.pattern[0].dx == 0
                        && value.pattern[0].dy == 0
                        && value.pattern.iter().all(|sample| sample.rgb <= 0xFF_FFFF)
                        && valid_variable_names([
                            &value.frame_variable,
                            &value.result_variable,
                            &value.count_variable,
                            &value.found_variable,
                            &value.x_variable,
                            &value.y_variable,
                        ])
                })
                .map(BuiltinNodeArgs::LegacyDuoDianZhaoSe)
                .ok_or(
                    "legacy.duodianzhaose requires a zero-offset anchor, 1..=65 samples, direction 0..=4, percent 0..=100, a positive region, and valid variable names",
                )
        }
        "legacy.duodianbise" => {
            serde_json::from_value::<LegacyDuoDianBiSeArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    value.minimum_match_percent <= 100
                        && (1..=pixel_vision::MAX_LEGACY_FIXED_SAMPLES)
                            .contains(&value.pattern.len())
                        && value.pattern.iter().all(|sample| sample.rgb <= 0xFF_FFFF)
                        && valid_variable_names([&value.frame_variable, &value.result_variable])
                })
                .map(BuiltinNodeArgs::LegacyDuoDianBiSe)
                .ok_or(
                    "legacy.duodianbise requires 1..=256 fixed samples, percent 0..=100, and valid variable names",
                )
        }
        "legacy.getrectcolornum" => {
            serde_json::from_value::<LegacyGetRectColorNumArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| {
                    valid_legacy_region(&value.region)
                        && (1..=pixel_vision::MAX_LEGACY_COLOR_SPECS).contains(&value.colors.len())
                        && value.colors.iter().all(|color| color.rgb <= 0xFF_FFFF)
                        && valid_variable_names([&value.frame_variable, &value.result_variable])
                })
                .map(BuiltinNodeArgs::LegacyGetRectColorNum)
                .ok_or(
                    "legacy.getrectcolornum requires 1..=64 colors, a positive region, and valid variable names",
                )
        }
        "legacy.getrgbcolor" => {
            serde_json::from_value::<LegacyGetRgbColorArgs>(node.envelope.args.clone())
                .ok()
                .filter(|value| valid_variable_name(&value.result_variable))
                .map(BuiltinNodeArgs::LegacyGetRgbColor)
                .ok_or("legacy.getrgbcolor requires 0..=255 channels and a valid resultVariable")
        }
        _ => unreachable!("caller filters legacy node kinds"),
    }
}

fn valid_legacy_region(value: &LegacyRegionArgs) -> bool {
    value.width > 0
        && value.height > 0
        && value.left.checked_add(value.width).is_some()
        && value.top.checked_add(value.height).is_some()
}

fn parse_visual_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    if matches!(kind, "vision.countcolor" | "vision.findallcolor")
        && node
            .envelope
            .args
            .get("limit")
            .and_then(Value::as_u64)
            .is_some_and(|n| n == 0 || n > 256)
        && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
    {
        return Err("扩展颜色数量需要runtimeApi 1.8");
    }
    if matches!(
        kind,
        "vision.findcolor" | "vision.comparecolor" | "vision.countcolor" | "vision.findallcolor"
    ) {
        if let Some(extra) = node.envelope.args.get("additionalColors") {
            let extra = extra.as_str().ok_or("颜色组必须为文本")?;
            if !extra.is_empty() && !runtime_api_at_least(&manifest.runtime_api, 1, 8) {
                return Err("多色组需要runtimeApi 1.8");
            }
            let rgb = node
                .envelope
                .args
                .get("rgb")
                .and_then(Value::as_u64)
                .and_then(|v| u32::try_from(v).ok())
                .ok_or("invalid primary RGB")?;
            pixel_vision::color_palette::parse(rgb, 0, extra)?;
        }
    }
    match kind {
        "screen.capture" | "screen.release" => parse_screen_resource_node(kind, node),
        "vision.getcolor"
        | "vision.findcolor"
        | "vision.comparecolor"
        | "vision.findmulticolor"
        | "vision.countcolor"
        | "vision.findallcolor" => parse_color_node(kind, node),
        "vision.findallimages" | "vision.result" => parse_multi_match_node(kind, node, manifest),
        "vision.findimage" => parse_find_image_node(kind, node, manifest),
        "ocr.glyph" | "vision.findgray" | "ocr.alphanumeric" => {
            parse_ocr_and_gray_node(kind, node, manifest)
        }
        _ => unreachable!("caller filters visual node kinds"),
    }
}

fn parse_screen_resource_node(
    kind: &str,
    node: &flow_ir::FlowNode,
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
        _ => unreachable!("visual parser dispatch"),
    }
}

fn parse_color_node(kind: &str, node: &flow_ir::FlowNode) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
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
        _ => unreachable!("visual parser dispatch"),
    }
}

fn parse_multi_match_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "vision.findallimages" => {
            serde_json::from_value::<FindAllImagesArgs>(node.envelope.args.clone())
                .ok()
                .filter(|v| {
                    runtime_api_at_least(&manifest.runtime_api, 1, 7)
                        && valid_variable_name(&v.frame_variable)
                        && valid_variable_name(&v.result_variable)
                        && v.frame_variable != v.result_variable
                        && valid_rect(&v.region)
                        && v.direction <= 4
                        && (1..=64).contains(&v.limit)
                        && (1..=5000).contains(&v.action_duration_ms)
                        && v.before_wait_ms <= 60_000
                        && v.after_wait_ms <= 60_000
                        && !(v.all_matches && v.success_action == RecognitionAction::Hold)
                        && v.similarity_permille <= 1000
                        && (1..=64).contains(&v.image_paths.len())
                        && v.image_paths.iter().collect::<HashSet<_>>().len() == v.image_paths.len()
                        && v.image_paths.iter().all(|p| {
                            p.len() <= 256
                                && manifest
                                    .resources
                                    .iter()
                                    .any(|r| r.kind == ProjectResourceKind::Image && r.path == *p)
                        })
                        && serde_json::to_vec(&v.image_paths).is_ok_and(|b| b.len() <= 16_384)
                })
                .map(BuiltinNodeArgs::FindAllImages)
                .ok_or("多结果找图的资源、变量、区域、数量或API无效")
        }
        "vision.result" => serde_json::from_value::<MatchResultArgs>(node.envelope.args.clone())
            .ok()
            .filter(|v| {
                runtime_api_at_least(&manifest.runtime_api, 1, 7)
                    && valid_variable_name(&v.source_variable)
                    && valid_variable_name(&v.target_variable)
                    && v.source_variable != v.target_variable
                    && v.index <= 64
                    && if matches!(v.field.as_str(), "count" | "truncated") {
                        v.index == 0
                    } else {
                        v.index > 0
                            && matches!(
                                v.field.as_str(),
                                "x" | "y" | "width" | "height" | "scorePermille" | "path"
                            )
                    }
            })
            .map(BuiltinNodeArgs::MatchResult)
            .ok_or("结果属性、索引或变量无效"),
        _ => unreachable!("visual parser dispatch"),
    }
}

fn parse_find_image_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "vision.findimage" => serde_json::from_value::<FindImageArgs>(node.envelope.args.clone())
            .ok()
            .and_then(|mut value| {
                if let Some(directory) = &value.image_directory {
                    if !directory.starts_with("assets/images/")
                        || !directory.ends_with('/')
                        || directory.contains("..")
                        || directory.contains('\\')
                    {
                        return None;
                    }
                    if !value.image_paths.is_empty() {
                        return None;
                    }
                    value.image_paths = manifest
                        .resources
                        .iter()
                        .filter(|resource| {
                            resource.kind == ProjectResourceKind::Image
                                && resource.path.starts_with(directory)
                        })
                        .map(|resource| resource.path.clone())
                        .collect();
                    value.image_paths.sort();
                    if value.image_paths.is_empty() {
                        return None;
                    }
                }
                Some(value)
            })
            .filter(|value| {
                let mut names = vec![
                    value.frame_variable.as_str(),
                    value.found_variable.as_str(),
                    value.x_variable.as_str(),
                    value.y_variable.as_str(),
                ];
                names.extend(
                    [
                        value.template_variable.as_deref(),
                        value.score_variable.as_deref(),
                        value.image_variable.as_deref(),
                    ]
                    .into_iter()
                    .flatten(),
                );
                value.similarity_permille <= 1000
                    && names.iter().collect::<HashSet<_>>().len() == names.len()
                    && value
                        .region_variables()
                        .iter()
                        .flatten()
                        .all(|name| !names.contains(name))
                    && value.direction <= 4
                    && (1..=30).contains(&value.frequency)
                    && (1..=60_000).contains(&value.action_duration_ms)
                    && value
                        .image_variable
                        .as_deref()
                        .is_none_or(valid_variable_name)
                    && value.image_paths.len() <= 64
                    && serde_json::to_string(&value.image_paths)
                        .is_ok_and(|paths| paths.len() <= 16_384)
                    && value
                        .image_paths
                        .iter()
                        .all(|path| has_resource(manifest, ProjectResourceKind::Image, path))
                    && value.image_paths.iter().collect::<HashSet<_>>().len()
                        == value.image_paths.len()
                    && [
                        value.template_variable.as_deref(),
                        value.score_variable.as_deref(),
                    ]
                    .iter()
                    .flatten()
                    .all(|name| valid_variable_name(name))
                    && value
                        .region_variables()
                        .iter()
                        .flatten()
                        .all(|name| valid_variable_name(name))
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
        _ => unreachable!("visual parser dispatch"),
    }
}

fn parse_ocr_and_gray_node(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
) -> Result<BuiltinNodeArgs, &'static str> {
    match kind {
        "ocr.glyph" => serde_json::from_value::<GlyphOcrArgs>(node.envelope.args.clone())
            .ok()
            .filter(|value| {
                value.foreground_rgb <= 0xFF_FFFF
                    && value.valid_colors()
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
        "vision.findgray" => serde_json::from_value::<GrayArgs>(node.envelope.args.clone())
            .ok()
            .filter(|v| {
                v.similarity_permille <= 1000
                    && valid_rect(&v.region)
                    && has_resource(manifest, ProjectResourceKind::Image, &v.image_path)
                    && distinct_native_variables([
                        &v.frame_variable,
                        &v.found_variable,
                        &v.x_variable,
                        &v.y_variable,
                        &v.score_variable,
                    ])
            })
            .map(BuiltinNodeArgs::Gray)
            .ok_or("invalid gray matching arguments"),
        "ocr.alphanumeric" => {
            serde_json::from_value::<AlphanumericArgs>(node.envelope.args.clone())
                .ok()
                .filter(|v| {
                    v.minimum_confidence_permille <= 1000
                        && valid_rect(&v.region)
                        && distinct_native_variables([
                            &v.frame_variable,
                            &v.text_variable,
                            &v.score_variable,
                        ])
                })
                .map(BuiltinNodeArgs::Alphanumeric)
                .ok_or("invalid alphanumeric arguments")
        }
        _ => unreachable!("visual parser dispatch"),
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
                    && value.limit <= 64_000_000
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
                        && (1..=2048).contains(&value.limit)
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

fn distinct_native_variables<const N: usize>(names: [&str; N]) -> bool {
    valid_variable_names(names) && names.iter().collect::<HashSet<_>>().len() == N
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

fn validate_conditional_child_blocks(
    node: &flow_ir::FlowNode,
    else_if_count: usize,
    errors: &mut Vec<CompileError>,
) {
    let expected = std::iter::once("then".to_owned())
        .chain(std::iter::once("else".to_owned()))
        .chain((0..else_if_count).map(|index| format!("elseIf{index}")))
        .collect::<HashSet<_>>();
    let actual = node
        .envelope
        .child_blocks
        .keys()
        .cloned()
        .collect::<HashSet<_>>();
    if actual != expected {
        invalid_arguments(
            node,
            "control.if 的子分支必须包含 then、else 与每个 elseIf 分支",
            errors,
        );
    }
}

fn valid_comparison(value: &ComparisonArgs) -> bool {
    value.else_if.len() <= 32
        && valid_comparison_parts(&value.variable, value.operator, &value.value)
        && value
            .value_variable
            .as_deref()
            .is_none_or(valid_variable_name)
        && value.else_if.iter().all(|branch| {
            valid_comparison_parts(&branch.variable, branch.operator, &branch.value)
                && branch
                    .value_variable
                    .as_deref()
                    .is_none_or(valid_variable_name)
                && branch
                    .condition
                    .as_ref()
                    .is_none_or(crate::compound_condition::valid)
        })
        && value
            .condition
            .as_ref()
            .is_none_or(crate::compound_condition::valid)
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

pub(crate) fn valid_variable_name(value: &str) -> bool {
    flow_ir::valid_user_name(value)
}

fn valid_control_id(value: &str) -> bool {
    let mut bytes = value.bytes();
    value.len() <= 64
        && bytes
            .next()
            .is_some_and(|b| b.is_ascii_alphabetic() || b == b'_')
        && bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_')
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
    caller: &FlowDocument,
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
    for (index, parameter) in target.params.iter().enumerate() {
        // Indexed channels are path-dependent. The callee checks the actual merged payload;
        // a branch that did not set a required channel fails at runtime, never defaults it.
        let indexed = caller.canonical_nodes().into_iter().flatten().any(|node| {
            node.envelope.kind == "flow.argument.set"
                && node.envelope.args.get("index").and_then(Value::as_u64)
                    == u64::try_from(index + 1).ok()
        });
        if parameter.required && !call.arguments.contains_key(&parameter.name) && !indexed {
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

fn validate_ui_command_requirements(
    kind: &str,
    node: &flow_ir::FlowNode,
    manifest: &ProjectManifest,
    capabilities: &HashSet<&str>,
    errors: &mut Vec<CompileError>,
) {
    if kind == "ui.command"
        && node
            .envelope
            .args
            .get("operation")
            .and_then(Value::as_str)
            .is_some_and(|op| matches!(op, "get:pages" | "get:controls"))
        && !runtime_api_at_least(&manifest.runtime_api, 1, 8)
    {
        invalid_arguments(node, "界面枚举需要runtimeApi 1.8", errors);
    }
    if kind.starts_with("ui.") && !matches!(kind, "ui.confirm" | "ui.dialog" | "ui.dialogclose") {
        require_capability(node, "ui.control", capabilities, errors);
        let id = node.envelope.args.get("controlId").and_then(Value::as_str);
        let window_operation = node
            .envelope
            .args
            .get("operation")
            .and_then(Value::as_str)
            .is_some_and(|op| {
                matches!(
                    op,
                    "show"
                        | "hide"
                        | "minimize"
                        | "page"
                        | "get:config"
                        | "get:pages"
                        | "get:controls"
                        | "restoreConfig"
                        | "windowPosition"
                        | "bubblePosition"
                        | "get:windowPosition"
                        | "get:bubblePosition"
                )
            });
        let exists = manifest.runner_ui.as_ref().is_some_and(|ui| {
            if window_operation {
                id == Some("window")
            } else {
                ui.fields.iter().any(|f| {
                    if Some(f.id.as_str()) != id {
                        return false;
                    }
                    let op = node
                        .envelope
                        .args
                        .get("operation")
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    if matches!(
                        op,
                        "groupVisible" | "groupEnabled" | "groupReset" | "get:groupCount"
                    ) {
                        return f
                            .ui
                            .as_ref()
                            .and_then(|p| p.get("control"))
                            .and_then(Value::as_str)
                            == Some("CONTAINER");
                    }
                    if matches!(op, "backgroundImage" | "get:backgroundImage") {
                        return f.ui.is_some();
                    }
                    true
                })
            }
        });
        if !exists || !runtime_api_at_least(&manifest.runtime_api, 1, 7) {
            errors.push(CompileError::InvalidNodeArguments {
                node_id: node.envelope.node_id.clone(),
                reason: "界面积木需要已设计界面、有效控件及 runtimeApi 1.7".into(),
            });
        }
        if node.envelope.args.get("operation").and_then(Value::as_str) == Some("backgroundImage")
            && !node
                .envelope
                .args
                .get("valueVariable")
                .is_some_and(Value::is_string)
        {
            let path = node
                .envelope
                .args
                .get("value")
                .and_then(Value::as_str)
                .unwrap_or("");
            if !path.is_empty()
                && !manifest
                    .resources
                    .iter()
                    .any(|r| r.kind == ProjectResourceKind::Image && r.path == path)
            {
                invalid_arguments(node, "背景图必须为声明的项目图片", errors);
            }
        }
    }
}
