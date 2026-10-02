//! Platform-neutral contracts for capture, input arbitration and OCR.

mod backend;
mod capture_series;
mod frame_pool;
mod input_arbiter;

pub use backend::{
    AutomationBackend, BackendError, CaptureBackend, CaptureRequest, OcrEngine, OcrRequest,
    OcrResult, OcrSpan,
};
pub use capture_series::{
    CaptureSeriesCollector, CaptureSeriesConfig, CaptureSeriesError, CaptureSeriesResult,
    CaptureSeriesSample, CaptureSeriesStopReason, CaptureSeriesUpdate,
};
pub use frame_pool::{
    CaptureSeriesHandle, CapturedFrameId, FrameError, FrameFormat, FrameHandle, FrameMetadata,
    FramePool, FramePoolConfig, FrameView, FrameVisionError, NamedTemplateMatch, Rotation,
    SeriesPublish,
};
pub use input_arbiter::{
    InputArbiter, InputArbiterConfig, InputCommand, InputDecision, InputError, InputTransaction,
    StopPlan, TransactionId,
};
pub use pixel_vision::{
    duo_dian_bi_se, duo_dian_zhao_se, format_color_list, format_fixed_pattern,
    format_relative_pattern, get_rect_color_num, get_rgb_color, parse_color_list,
    parse_fixed_pattern, parse_relative_pattern, Color, ColorSpec, ColorTolerance,
    FixedPatternSample, ImageView, LegacyCompatError, LegacyFormatError, LegacyParseError,
    LegacySearchDirection, LegacySearchLimits, PatternSample, PixelFormat, PixelPoint, PixelRect,
    PreparedTemplate, SearchOptions, SearchOrder, TemplateMatch, TemplateOptions, VisionError,
};
