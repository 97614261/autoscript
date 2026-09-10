//! Deterministic, bounded scalar baseline for pixel color and template search.

mod legacy;
mod legacy_compat;

pub use legacy::{
    format_color_list, format_fixed_pattern, format_relative_pattern, parse_color_list,
    parse_fixed_pattern, parse_relative_pattern, LegacyFormatError, LegacyParseError,
    MAX_LEGACY_COLOR_SPECS, MAX_LEGACY_FIXED_SAMPLES, MAX_LEGACY_PATTERN_SAMPLES,
};
pub use legacy_compat::{
    duo_dian_bi_se, duo_dian_zhao_se, get_rect_color_num, get_rgb_color, LegacyCompatError,
    LegacySearchDirection, LegacySearchLimits,
};

use sha2::{Digest, Sha256};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelFormat {
    Rgba8888,
    Bgra8888,
}

#[derive(Debug, Clone, Copy)]
pub struct ImageView<'a> {
    pub width: u32,
    pub height: u32,
    pub row_stride: usize,
    pub format: PixelFormat,
    pub pixels: &'a [u8],
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PixelPoint {
    pub x: u32,
    pub y: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PixelRect {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

impl PixelRect {
    #[must_use]
    pub const fn width(self) -> u32 {
        self.right.saturating_sub(self.left)
    }

    #[must_use]
    pub const fn height(self) -> u32 {
        self.bottom.saturating_sub(self.top)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Color {
    pub red: u8,
    pub green: u8,
    pub blue: u8,
    pub alpha: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColorTolerance {
    pub red: u8,
    pub green: u8,
    pub blue: u8,
    pub alpha: u8,
}

impl ColorTolerance {
    pub const EXACT: Self = Self {
        red: 0,
        green: 0,
        blue: 0,
        alpha: 0,
    };
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SearchOptions {
    pub roi: PixelRect,
    pub step_x: u32,
    pub step_y: u32,
    pub order: SearchOrder,
    pub max_pixel_comparisons: u64,
}

/// Stable candidate traversal order. Values intentionally model the five orders exposed by the
/// legacy product while keeping coordinates half-open and step-aligned.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum SearchOrder {
    BottomLeftToTopRight,
    #[default]
    TopLeftToBottomRight,
    BottomRightToTopLeft,
    TopRightToBottomLeft,
    CenterOut,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PatternSample {
    pub offset_x: i32,
    pub offset_y: i32,
    pub color: Color,
    pub tolerance: ColorTolerance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FixedPatternSample {
    pub point: PixelPoint,
    pub color: Color,
    pub tolerance: ColorTolerance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColorSpec {
    pub color: Color,
    pub tolerance: ColorTolerance,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TemplateOptions {
    pub search: SearchOptions,
    pub tolerance: ColorTolerance,
    pub minimum_match_permille: u16,
    pub ignore_transparent_template_pixels: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TemplateMatch {
    pub origin: PixelPoint,
    pub matched_permille: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct PreparedPixel {
    x: u32,
    y: u32,
    color: Color,
}

/// Immutable canonical template data. Preparing once removes format conversion, transparent-pixel
/// filtering and anchor selection from repeated searches.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreparedTemplate {
    width: u32,
    height: u32,
    content_hash: [u8; 32],
    pixels: Vec<Color>,
    opaque_count: u64,
    all_anchors: [Option<PreparedPixel>; 4],
    opaque_anchors: [Option<PreparedPixel>; 4],
}

impl PreparedTemplate {
    /// Converts a validated RGBA/BGRA view to canonical colors and hashes visible content.
    ///
    /// # Errors
    ///
    /// Rejects malformed image geometry.
    pub fn prepare(template: ImageView<'_>) -> Result<Self, VisionError> {
        validate_image(template)?;
        let capacity = usize::try_from(u64::from(template.width) * u64::from(template.height))
            .map_err(|_| VisionError::InvalidImage)?;
        let mut pixels = Vec::with_capacity(capacity);
        let mut opaque_count = 0_u64;
        let mut hasher = Sha256::new();
        hasher.update(template.width.to_le_bytes());
        hasher.update(template.height.to_le_bytes());
        for y in 0..template.height {
            for x in 0..template.width {
                let color = read_pixel(template, x, y);
                hasher.update([color.red, color.green, color.blue, color.alpha]);
                pixels.push(color);
                if color.alpha != 0 {
                    opaque_count += 1;
                }
            }
        }
        let all_anchors = prepared_anchors(&pixels, template.width, false);
        let opaque_anchors = prepared_anchors(&pixels, template.width, true);
        Ok(Self {
            width: template.width,
            height: template.height,
            content_hash: hasher.finalize().into(),
            pixels,
            opaque_count,
            all_anchors,
            opaque_anchors,
        })
    }

    #[must_use]
    pub const fn content_hash(&self) -> [u8; 32] {
        self.content_hash
    }

    #[must_use]
    pub const fn width(&self) -> u32 {
        self.width
    }

    #[must_use]
    pub const fn height(&self) -> u32 {
        self.height
    }

    #[must_use]
    pub fn estimated_bytes(&self) -> usize {
        self.pixels
            .len()
            .saturating_mul(std::mem::size_of::<Color>())
    }

    fn relevant_count(&self, ignore_transparent: bool) -> u64 {
        if ignore_transparent {
            self.opaque_count
        } else {
            u64::try_from(self.pixels.len()).unwrap_or(u64::MAX)
        }
    }

    fn anchors(&self, ignore_transparent: bool) -> [Option<PreparedPixel>; 4] {
        if ignore_transparent {
            self.opaque_anchors
        } else {
            self.all_anchors
        }
    }

    fn color_at(&self, x: u32, y: u32) -> Option<Color> {
        let index = usize::try_from(u64::from(y) * u64::from(self.width) + u64::from(x)).ok()?;
        self.pixels.get(index).copied()
    }
}

fn prepared_anchors(
    pixels: &[Color],
    width: u32,
    ignore_transparent: bool,
) -> [Option<PreparedPixel>; 4] {
    // A fixed 4-bit RGB histogram plus one transparency bit keeps preprocessing memory bounded
    // while still favoring rare, contrasting pixels over flat template backgrounds.
    let mut frequencies = vec![0_u32; 8_192];
    let mut sums = [0_u64; 4];
    let mut relevant = 0_u64;
    for color in pixels
        .iter()
        .filter(|color| !ignore_transparent || color.alpha != 0)
    {
        let channels = [color.red, color.green, color.blue, color.alpha];
        let bucket = anchor_bucket(*color);
        frequencies[bucket] = frequencies[bucket].saturating_add(1);
        for (sum, channel) in sums.iter_mut().zip(channels) {
            *sum += u64::from(channel);
        }
        relevant += 1;
    }
    if relevant == 0 {
        return [None; 4];
    }
    let means = sums.map(|sum| u8::try_from(sum / relevant).unwrap_or(u8::MAX));
    let mut candidates = Vec::<AnchorCandidate>::with_capacity(5);
    for (index, color) in pixels.iter().copied().enumerate() {
        if ignore_transparent && color.alpha == 0 {
            continue;
        }
        let channels = [color.red, color.green, color.blue, color.alpha];
        let frequency = frequencies[anchor_bucket(color)];
        let contrast = channels
            .into_iter()
            .zip(means)
            .map(|(channel, mean)| u32::from(channel.abs_diff(mean)))
            .sum();
        let flat_index = u64::try_from(index).unwrap_or(u64::MAX);
        candidates.push(AnchorCandidate {
            frequency,
            contrast,
            index,
            pixel: PreparedPixel {
                x: u32::try_from(flat_index % u64::from(width)).unwrap_or(u32::MAX),
                y: u32::try_from(flat_index / u64::from(width)).unwrap_or(u32::MAX),
                color,
            },
        });
        candidates.sort_unstable_by(|left, right| {
            left.frequency
                .cmp(&right.frequency)
                .then_with(|| right.contrast.cmp(&left.contrast))
                .then_with(|| left.index.cmp(&right.index))
        });
        if candidates.len() > 4 {
            candidates.pop();
        }
    }
    let mut anchors = [None; 4];
    for (slot, candidate) in anchors.iter_mut().zip(candidates) {
        *slot = Some(candidate.pixel);
    }
    anchors
}

#[derive(Debug, Clone, Copy)]
struct AnchorCandidate {
    frequency: u32,
    contrast: u32,
    index: usize,
    pixel: PreparedPixel,
}

fn anchor_bucket(color: Color) -> usize {
    let transparency = usize::from(color.alpha != 0) << 12;
    transparency
        | (usize::from(color.red >> 4) << 8)
        | (usize::from(color.green >> 4) << 4)
        | usize::from(color.blue >> 4)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VisionError {
    InvalidImage,
    InvalidRoi,
    InvalidStep,
    EmptyPattern,
    InvalidMatchPercentage,
    PatternOffsetOverflow,
    EmptyTemplate,
    TemplateOutsideRoi,
    InvalidSimilarity,
    PointOutsideImage,
    InvalidResultLimit,
    ComparisonBudgetExceeded,
}

pub const MAX_COLOR_RESULTS: usize = 4_096;

/// Reads one pixel from a validated image.
///
/// # Errors
///
/// Rejects malformed images and coordinates outside the image.
pub fn get_color(image: ImageView<'_>, point: PixelPoint) -> Result<Color, VisionError> {
    validate_image(image)?;
    if point.x >= image.width || point.y >= image.height {
        return Err(VisionError::PointOutsideImage);
    }
    Ok(read_pixel(image, point.x, point.y))
}

/// Compares one image pixel with a target color.
///
/// # Errors
///
/// Rejects malformed images and coordinates outside the image.
pub fn compare_color(
    image: ImageView<'_>,
    point: PixelPoint,
    target: Color,
    tolerance: ColorTolerance,
) -> Result<bool, VisionError> {
    Ok(color_matches(get_color(image, point)?, target, tolerance))
}

/// Finds the first matching color in deterministic row-major order.
///
/// # Errors
///
/// Rejects malformed images/options and stops at the configured comparison budget.
pub fn find_color(
    image: ImageView<'_>,
    target: Color,
    tolerance: ColorTolerance,
    options: SearchOptions,
) -> Result<Option<PixelPoint>, VisionError> {
    validate_search(image, options)?;
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    for point in SearchPoints::new(options) {
        budget.consume(1)?;
        if color_matches(read_pixel(image, point.x, point.y), target, tolerance) {
            return Ok(Some(point));
        }
    }
    Ok(None)
}

/// Counts matching pixels in deterministic row-major order, stopping at `limit`.
///
/// # Errors
///
/// Rejects malformed options, an invalid limit, or an exhausted comparison budget.
pub fn count_color(
    image: ImageView<'_>,
    target: Color,
    tolerance: ColorTolerance,
    options: SearchOptions,
    limit: usize,
) -> Result<usize, VisionError> {
    validate_result_limit(limit)?;
    validate_search(image, options)?;
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut count = 0_usize;
    for point in SearchPoints::new(options) {
        budget.consume(1)?;
        if color_matches(read_pixel(image, point.x, point.y), target, tolerance) {
            count += 1;
            if count == limit {
                return Ok(count);
            }
        }
    }
    Ok(count)
}

/// Collects matching pixels in deterministic row-major order, stopping at `limit`.
///
/// # Errors
///
/// Rejects malformed options, an invalid limit, or an exhausted comparison budget.
pub fn find_all_colors(
    image: ImageView<'_>,
    target: Color,
    tolerance: ColorTolerance,
    options: SearchOptions,
    limit: usize,
) -> Result<Vec<PixelPoint>, VisionError> {
    validate_result_limit(limit)?;
    validate_search(image, options)?;
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut points = Vec::with_capacity(limit.min(64));
    for point in SearchPoints::new(options) {
        budget.consume(1)?;
        if color_matches(read_pixel(image, point.x, point.y), target, tolerance) {
            points.push(point);
            if points.len() == limit {
                return Ok(points);
            }
        }
    }
    Ok(points)
}

/// Finds an anchor whose signed-offset samples all match.
///
/// # Errors
///
/// Rejects empty patterns, out-of-range geometry and exhausted comparison budgets.
pub fn find_pattern(
    image: ImageView<'_>,
    samples: &[PatternSample],
    options: SearchOptions,
) -> Result<Option<PixelPoint>, VisionError> {
    find_pattern_percentage(image, samples, 100, options)
}

/// Finds the first anchor for which at least the requested percentage of signed samples match.
///
/// # Errors
///
/// Rejects empty patterns, percentages above 100, out-of-range geometry and exhausted budgets.
pub fn find_pattern_percentage(
    image: ImageView<'_>,
    samples: &[PatternSample],
    minimum_match_percent: u8,
    options: SearchOptions,
) -> Result<Option<PixelPoint>, VisionError> {
    validate_search(image, options)?;
    if samples.is_empty() {
        return Err(VisionError::EmptyPattern);
    }
    if minimum_match_percent > 100 {
        return Err(VisionError::InvalidMatchPercentage);
    }
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let required = required_matches(samples.len(), minimum_match_percent)?;
    for point in SearchPoints::new(options) {
        if pattern_matches_candidate(image, point, samples, required, options.roi, &mut budget)? {
            return Ok(Some(point));
        }
    }
    Ok(None)
}

/// Returns a bounded set of matching pattern anchors in the configured deterministic order.
///
/// # Errors
///
/// Rejects invalid limits/options/patterns or exhausted comparison budgets.
pub fn find_all_pattern_matches(
    image: ImageView<'_>,
    samples: &[PatternSample],
    minimum_match_percent: u8,
    options: SearchOptions,
    limit: usize,
) -> Result<Vec<PixelPoint>, VisionError> {
    validate_result_limit(limit)?;
    validate_search(image, options)?;
    if samples.is_empty() {
        return Err(VisionError::EmptyPattern);
    }
    if minimum_match_percent > 100 {
        return Err(VisionError::InvalidMatchPercentage);
    }
    let required = required_matches(samples.len(), minimum_match_percent)?;
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut matches = Vec::with_capacity(limit.min(64));
    for point in SearchPoints::new(options) {
        if pattern_matches_candidate(image, point, samples, required, options.roi, &mut budget)? {
            matches.push(point);
            if matches.len() == limit {
                return Ok(matches);
            }
        }
    }
    Ok(matches)
}

fn required_matches(sample_count: usize, percentage: u8) -> Result<u64, VisionError> {
    let count = u64::try_from(sample_count).map_err(|_| VisionError::EmptyPattern)?;
    Ok(count.saturating_mul(u64::from(percentage)).div_ceil(100))
}

fn pattern_matches_candidate(
    image: ImageView<'_>,
    point: PixelPoint,
    samples: &[PatternSample],
    required: u64,
    roi: PixelRect,
    budget: &mut ComparisonBudget,
) -> Result<bool, VisionError> {
    if required == 0 {
        return Ok(true);
    }
    let sample_count = u64::try_from(samples.len()).map_err(|_| VisionError::EmptyPattern)?;
    let mut matched = 0_u64;
    for (index, sample) in samples.iter().enumerate() {
        let sample_x = i64::from(point.x) + i64::from(sample.offset_x);
        let sample_y = i64::from(point.y) + i64::from(sample.offset_y);
        if sample_x >= i64::from(roi.left)
            && sample_y >= i64::from(roi.top)
            && sample_x < i64::from(roi.right)
            && sample_y < i64::from(roi.bottom)
        {
            let sample_x =
                u32::try_from(sample_x).map_err(|_| VisionError::PatternOffsetOverflow)?;
            let sample_y =
                u32::try_from(sample_y).map_err(|_| VisionError::PatternOffsetOverflow)?;
            budget.consume(1)?;
            if color_matches(
                read_pixel(image, sample_x, sample_y),
                sample.color,
                sample.tolerance,
            ) {
                matched += 1;
            }
        }
        let compared = u64::try_from(index + 1).map_err(|_| VisionError::EmptyPattern)?;
        if matched >= required {
            return Ok(true);
        }
        if matched + (sample_count - compared) < required {
            return Ok(false);
        }
    }
    Ok(matched >= required)
}

/// Compares fixed absolute points and succeeds when the requested percentage matches.
///
/// The threshold uses ceiling arithmetic: with ten samples any percentage from 71 through 80
/// requires eight matches, matching the legacy API's documented behavior.
///
/// # Errors
///
/// Rejects malformed images, empty samples, points outside the image, invalid percentages, or an
/// exhausted comparison budget.
pub fn compare_fixed_pattern(
    image: ImageView<'_>,
    samples: &[FixedPatternSample],
    minimum_match_percent: u8,
    max_pixel_comparisons: u64,
) -> Result<bool, VisionError> {
    validate_image(image)?;
    if samples.is_empty() {
        return Err(VisionError::EmptyPattern);
    }
    if minimum_match_percent > 100 {
        return Err(VisionError::InvalidMatchPercentage);
    }
    if samples
        .iter()
        .any(|sample| sample.point.x >= image.width || sample.point.y >= image.height)
    {
        return Err(VisionError::PointOutsideImage);
    }
    let sample_count = u64::try_from(samples.len()).map_err(|_| VisionError::EmptyPattern)?;
    let required = sample_count
        .saturating_mul(u64::from(minimum_match_percent))
        .div_ceil(100);
    let mut budget = ComparisonBudget::new(max_pixel_comparisons);
    let mut matched = 0_u64;
    for (index, sample) in samples.iter().enumerate() {
        budget.consume(1)?;
        if color_matches(
            read_pixel(image, sample.point.x, sample.point.y),
            sample.color,
            sample.tolerance,
        ) {
            matched += 1;
        }
        let compared = u64::try_from(index + 1).map_err(|_| VisionError::EmptyPattern)?;
        if matched >= required {
            return Ok(true);
        }
        if matched + (sample_count - compared) < required {
            return Ok(false);
        }
    }
    Ok(matched >= required)
}

/// Counts pixels matching any one of a bounded set of color/tolerance pairs.
///
/// # Errors
///
/// Rejects malformed inputs, invalid limits, or an exhausted comparison budget.
pub fn count_any_color(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
    limit: usize,
) -> Result<usize, VisionError> {
    validate_result_limit(limit)?;
    validate_search(image, options)?;
    if colors.is_empty() {
        return Err(VisionError::EmptyPattern);
    }
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut count = 0_usize;
    for point in SearchPoints::new(options) {
        let actual = read_pixel(image, point.x, point.y);
        let mut matched = false;
        for spec in colors {
            budget.consume(1)?;
            if color_matches(actual, spec.color, spec.tolerance) {
                matched = true;
                break;
            }
        }
        if matched {
            count += 1;
            if count == limit {
                return Ok(count);
            }
        }
    }
    Ok(count)
}

/// Counts every matching pixel without a result-list cap. Work remains bounded by the comparison
/// budget and the finite ROI.
///
/// # Errors
///
/// Rejects malformed inputs or an exhausted comparison budget.
pub fn count_any_color_total(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
) -> Result<usize, VisionError> {
    validate_search(image, options)?;
    if colors.is_empty() {
        return Err(VisionError::EmptyPattern);
    }
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut count = 0_usize;
    for point in SearchPoints::new(options) {
        let actual = read_pixel(image, point.x, point.y);
        for spec in colors {
            budget.consume(1)?;
            if color_matches(actual, spec.color, spec.tolerance) {
                count = count.saturating_add(1);
                break;
            }
        }
    }
    Ok(count)
}

/// Finds the first template position meeting a per-channel tolerance and similarity threshold.
///
/// # Errors
///
/// Rejects malformed images, invalid thresholds, impossible geometry and exhausted budgets.
pub fn find_template(
    image: ImageView<'_>,
    template: ImageView<'_>,
    options: TemplateOptions,
) -> Result<Option<TemplateMatch>, VisionError> {
    let prepared = PreparedTemplate::prepare(template)?;
    find_prepared_template(image, &prepared, options)
}

/// Searches with a previously prepared immutable template.
///
/// # Errors
///
/// Rejects malformed screen/options, invalid thresholds, impossible geometry and exhausted
/// comparison budgets.
pub fn find_prepared_template(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
) -> Result<Option<TemplateMatch>, VisionError> {
    validate_search(image, options.search)?;
    if template.width == 0 || template.height == 0 {
        return Err(VisionError::EmptyTemplate);
    }
    if options.minimum_match_permille > 1_000 {
        return Err(VisionError::InvalidSimilarity);
    }
    if template.width > options.search.roi.width() || template.height > options.search.roi.height()
    {
        return Err(VisionError::TemplateOutsideRoi);
    }
    let last_x = options.search.roi.right - template.width + 1;
    let last_y = options.search.roi.bottom - template.height + 1;
    let candidate_options = SearchOptions {
        roi: PixelRect {
            left: options.search.roi.left,
            top: options.search.roi.top,
            right: last_x,
            bottom: last_y,
        },
        ..options.search
    };
    let mut budget = ComparisonBudget::new(options.search.max_pixel_comparisons);
    for origin in SearchPoints::new(candidate_options) {
        let score = compare_template(image, template, origin, options, &mut budget)?;
        if score >= options.minimum_match_permille {
            return Ok(Some(TemplateMatch {
                origin,
                matched_permille: score,
            }));
        }
    }
    Ok(None)
}

fn compare_template(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    origin: PixelPoint,
    options: TemplateOptions,
    budget: &mut ComparisonBudget,
) -> Result<u16, VisionError> {
    let relevant = template.relevant_count(options.ignore_transparent_template_pixels);
    if relevant == 0 {
        return Err(VisionError::EmptyTemplate);
    }
    let anchors = template.anchors(options.ignore_transparent_template_pixels);
    let required_matches = relevant
        .saturating_mul(u64::from(options.minimum_match_permille))
        .div_ceil(1_000);
    let mut matched = 0_u64;
    let mut compared = 0_u64;

    for pixel in anchors.iter().flatten().copied() {
        budget.consume(1)?;
        compared += 1;
        if color_matches(
            read_pixel(image, origin.x + pixel.x, origin.y + pixel.y),
            pixel.color,
            options.tolerance,
        ) {
            matched += 1;
        }
        if matched + (relevant - compared) < required_matches {
            return Ok(0);
        }
    }

    for template_y in 0..template.height {
        for template_x in 0..template.width {
            let expected = template
                .color_at(template_x, template_y)
                .ok_or(VisionError::InvalidImage)?;
            if options.ignore_transparent_template_pixels && expected.alpha == 0 {
                continue;
            }
            if anchors
                .iter()
                .flatten()
                .any(|anchor| anchor.x == template_x && anchor.y == template_y)
            {
                continue;
            }
            budget.consume(1)?;
            compared += 1;
            if color_matches(
                read_pixel(image, origin.x + template_x, origin.y + template_y),
                expected,
                options.tolerance,
            ) {
                matched += 1;
            }
            if matched + (relevant - compared) < required_matches {
                return Ok(0);
            }
        }
    }
    u16::try_from(matched.saturating_mul(1_000) / relevant)
        .map_err(|_| VisionError::InvalidSimilarity)
}

fn validate_result_limit(limit: usize) -> Result<(), VisionError> {
    if limit == 0 || limit > MAX_COLOR_RESULTS {
        return Err(VisionError::InvalidResultLimit);
    }
    Ok(())
}

fn validate_search(image: ImageView<'_>, options: SearchOptions) -> Result<(), VisionError> {
    validate_image(image)?;
    if options.step_x == 0 || options.step_y == 0 {
        return Err(VisionError::InvalidStep);
    }
    if options.roi.left >= options.roi.right
        || options.roi.top >= options.roi.bottom
        || options.roi.right > image.width
        || options.roi.bottom > image.height
    {
        return Err(VisionError::InvalidRoi);
    }
    Ok(())
}

fn validate_image(image: ImageView<'_>) -> Result<(), VisionError> {
    let width = usize::try_from(image.width).map_err(|_| VisionError::InvalidImage)?;
    let height = usize::try_from(image.height).map_err(|_| VisionError::InvalidImage)?;
    let packed_stride = width.checked_mul(4).ok_or(VisionError::InvalidImage)?;
    let required = image
        .row_stride
        .checked_mul(height)
        .ok_or(VisionError::InvalidImage)?;
    if width == 0
        || height == 0
        || image.row_stride < packed_stride
        || image.pixels.len() < required
    {
        return Err(VisionError::InvalidImage);
    }
    Ok(())
}

fn read_pixel(image: ImageView<'_>, x: u32, y: u32) -> Color {
    let offset = usize::try_from(y).expect("validated image height") * image.row_stride
        + usize::try_from(x).expect("validated image width") * 4;
    let bytes = &image.pixels[offset..offset + 4];
    match image.format {
        PixelFormat::Rgba8888 => Color {
            red: bytes[0],
            green: bytes[1],
            blue: bytes[2],
            alpha: bytes[3],
        },
        PixelFormat::Bgra8888 => Color {
            red: bytes[2],
            green: bytes[1],
            blue: bytes[0],
            alpha: bytes[3],
        },
    }
}

fn color_matches(actual: Color, expected: Color, tolerance: ColorTolerance) -> bool {
    actual.red.abs_diff(expected.red) <= tolerance.red
        && actual.green.abs_diff(expected.green) <= tolerance.green
        && actual.blue.abs_diff(expected.blue) <= tolerance.blue
        && actual.alpha.abs_diff(expected.alpha) <= tolerance.alpha
}

#[derive(Debug, Clone)]
struct SearchPoints {
    options: SearchOptions,
    columns: u64,
    rows: u64,
    emitted: u64,
    center: CenterOutState,
}

#[derive(Debug, Clone, Copy)]
struct CenterOutState {
    radius: u64,
    side: u8,
    offset: u64,
}

impl SearchPoints {
    fn new(options: SearchOptions) -> Self {
        let columns = axis_len(options.roi.left, options.roi.right, options.step_x);
        let rows = axis_len(options.roi.top, options.roi.bottom, options.step_y);
        Self {
            options,
            columns,
            rows,
            emitted: 0,
            center: CenterOutState {
                radius: 0,
                side: 0,
                offset: 0,
            },
        }
    }

    fn point(&self, column: u64, row: u64) -> Option<PixelPoint> {
        if column >= self.columns || row >= self.rows {
            return None;
        }
        let x = u64::from(self.options.roi.left)
            .checked_add(column.checked_mul(u64::from(self.options.step_x))?)?;
        let y = u64::from(self.options.roi.top)
            .checked_add(row.checked_mul(u64::from(self.options.step_y))?)?;
        Some(PixelPoint {
            x: u32::try_from(x).ok()?,
            y: u32::try_from(y).ok()?,
        })
    }

    fn next_corner(&mut self) -> Option<PixelPoint> {
        let total = self.columns.checked_mul(self.rows)?;
        if self.emitted >= total {
            return None;
        }
        let row_index = self.emitted / self.columns;
        let column_index = self.emitted % self.columns;
        self.emitted += 1;
        let (column, row) = match self.options.order {
            SearchOrder::BottomLeftToTopRight => (column_index, self.rows - 1 - row_index),
            SearchOrder::TopLeftToBottomRight => (column_index, row_index),
            SearchOrder::BottomRightToTopLeft => {
                (self.columns - 1 - column_index, self.rows - 1 - row_index)
            }
            SearchOrder::TopRightToBottomLeft => (self.columns - 1 - column_index, row_index),
            SearchOrder::CenterOut => return None,
        };
        self.point(column, row)
    }

    fn next_center(&mut self) -> Option<PixelPoint> {
        let total = self.columns.checked_mul(self.rows)?;
        if self.emitted >= total {
            return None;
        }
        let center_x = i64::try_from((self.columns - 1) / 2).ok()?;
        let center_y = i64::try_from((self.rows - 1) / 2).ok()?;
        loop {
            let radius = i64::try_from(self.center.radius).ok()?;
            let side_length = self.center.radius.saturating_mul(2);
            let offset = i64::try_from(self.center.offset).ok()?;
            let (x, y) = if radius == 0 {
                self.center.radius = 1;
                (center_x, center_y)
            } else {
                let candidate = match self.center.side {
                    0 => (center_x - radius + offset, center_y - radius),
                    1 => (center_x + radius, center_y - radius + offset),
                    2 => (center_x + radius - offset, center_y + radius),
                    _ => (center_x - radius, center_y + radius - offset),
                };
                self.center.offset += 1;
                if self.center.offset >= side_length {
                    self.center.offset = 0;
                    self.center.side += 1;
                    if self.center.side == 4 {
                        self.center.side = 0;
                        self.center.radius += 1;
                    }
                }
                candidate
            };
            if x < 0 || y < 0 {
                continue;
            }
            let (Ok(column), Ok(row)) = (u64::try_from(x), u64::try_from(y)) else {
                continue;
            };
            if let Some(point) = self.point(column, row) {
                self.emitted += 1;
                return Some(point);
            }
        }
    }
}

impl Iterator for SearchPoints {
    type Item = PixelPoint;

    fn next(&mut self) -> Option<Self::Item> {
        if self.options.order == SearchOrder::CenterOut {
            self.next_center()
        } else {
            self.next_corner()
        }
    }
}

fn axis_len(start: u32, end: u32, step: u32) -> u64 {
    u64::from(end - start - 1) / u64::from(step) + 1
}

struct ComparisonBudget {
    remaining: u64,
}

impl ComparisonBudget {
    const fn new(maximum: u64) -> Self {
        Self { remaining: maximum }
    }

    fn consume(&mut self, count: u64) -> Result<(), VisionError> {
        self.remaining = self
            .remaining
            .checked_sub(count)
            .ok_or(VisionError::ComparisonBudgetExceeded)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::{
        compare_color, compare_fixed_pattern, count_color, find_all_colors, find_color,
        find_pattern, find_template, get_color, Color, ColorTolerance, FixedPatternSample,
        ImageView, PatternSample, PixelFormat, PixelPoint, PixelRect, PreparedTemplate,
        SearchOptions, SearchOrder, TemplateOptions, VisionError,
    };

    fn image(pixels: &[u8], width: u32, height: u32) -> ImageView<'_> {
        ImageView {
            width,
            height,
            row_stride: usize::try_from(width).expect("width") * 4,
            format: PixelFormat::Rgba8888,
            pixels,
        }
    }

    fn options(width: u32, height: u32) -> SearchOptions {
        SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: width,
                bottom: height,
            },
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000,
        }
    }

    fn fixture(source: &str) -> Vec<u8> {
        source
            .split_ascii_whitespace()
            .flat_map(|pixel| {
                let value = u32::from_str_radix(pixel, 16).expect("fixture RGBA");
                value.to_be_bytes()
            })
            .collect()
    }

    #[test]
    fn color_and_signed_multi_point_search_are_deterministic() {
        let pixels = [0, 0, 0, 255, 10, 20, 30, 255, 0, 0, 0, 255, 40, 50, 60, 255];
        let view = image(&pixels, 2, 2);
        assert_eq!(
            find_color(
                view,
                Color {
                    red: 12,
                    green: 18,
                    blue: 32,
                    alpha: 255,
                },
                ColorTolerance {
                    red: 2,
                    green: 2,
                    blue: 2,
                    alpha: 0,
                },
                options(2, 2),
            )
            .expect("search"),
            Some(PixelPoint { x: 1, y: 0 })
        );
        let samples = [
            PatternSample {
                offset_x: 0,
                offset_y: 0,
                color: Color {
                    red: 40,
                    green: 50,
                    blue: 60,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
            PatternSample {
                offset_x: 0,
                offset_y: -1,
                color: Color {
                    red: 10,
                    green: 20,
                    blue: 30,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
        ];
        assert_eq!(
            find_pattern(view, &samples, options(2, 2)).expect("pattern"),
            Some(PixelPoint { x: 1, y: 1 })
        );
    }

    #[test]
    fn transparent_template_pixels_are_ignored() {
        let screen = image(&[9, 9, 9, 255, 20, 30, 40, 255, 80, 90, 100, 255], 3, 1);
        let template_pixels = [20, 30, 40, 255, 255, 0, 0, 0];
        let template = image(&template_pixels, 2, 1);
        let result = find_template(
            screen,
            template,
            TemplateOptions {
                search: options(3, 1),
                tolerance: ColorTolerance::EXACT,
                minimum_match_permille: 1_000,
                ignore_transparent_template_pixels: true,
            },
        )
        .expect("template");
        assert_eq!(result.expect("match").origin, PixelPoint { x: 1, y: 0 });
    }

    #[test]
    fn comparison_budget_fails_closed() {
        let pixels = [0_u8; 16];
        let mut search = options(2, 2);
        search.max_pixel_comparisons = 1;
        assert_eq!(
            find_color(
                image(&pixels, 2, 2),
                Color {
                    red: 255,
                    green: 255,
                    blue: 255,
                    alpha: 255,
                },
                ColorTolerance::EXACT,
                search,
            ),
            Err(VisionError::ComparisonBudgetExceeded)
        );
    }

    #[test]
    fn get_compare_count_and_find_all_are_bounded_and_row_major() {
        let pixels = [
            10, 20, 30, 255, 0, 0, 0, 255, 10, 20, 30, 255, 10, 20, 30, 255,
        ];
        let view = image(&pixels, 2, 2);
        let target = Color {
            red: 10,
            green: 20,
            blue: 30,
            alpha: 255,
        };
        assert_eq!(get_color(view, PixelPoint { x: 0, y: 0 }), Ok(target));
        assert_eq!(
            compare_color(
                view,
                PixelPoint { x: 1, y: 0 },
                target,
                ColorTolerance::EXACT
            ),
            Ok(false)
        );
        assert_eq!(
            count_color(view, target, ColorTolerance::EXACT, options(2, 2), 2),
            Ok(2)
        );
        assert_eq!(
            find_all_colors(view, target, ColorTolerance::EXACT, options(2, 2), 3),
            Ok(vec![
                PixelPoint { x: 0, y: 0 },
                PixelPoint { x: 0, y: 1 },
                PixelPoint { x: 1, y: 1 }
            ])
        );
        assert_eq!(
            get_color(view, PixelPoint { x: 2, y: 0 }),
            Err(VisionError::PointOutsideImage)
        );
        assert_eq!(
            count_color(view, target, ColorTolerance::EXACT, options(2, 2), 0),
            Err(VisionError::InvalidResultLimit)
        );
    }

    #[test]
    fn pattern_skips_candidates_whose_signed_samples_leave_roi() {
        let pixels = [10, 20, 30, 255, 40, 50, 60, 255];
        let samples = [
            PatternSample {
                offset_x: 0,
                offset_y: 0,
                color: Color {
                    red: 40,
                    green: 50,
                    blue: 60,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
            PatternSample {
                offset_x: -1,
                offset_y: 0,
                color: Color {
                    red: 10,
                    green: 20,
                    blue: 30,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
        ];
        assert_eq!(
            find_pattern(image(&pixels, 2, 1), &samples, options(2, 1)),
            Ok(Some(PixelPoint { x: 1, y: 0 }))
        );
    }

    #[test]
    fn template_anchor_order_preserves_similarity_result() {
        let screen = image(
            &[10, 0, 0, 255, 20, 0, 0, 255, 30, 0, 0, 255, 40, 0, 0, 255],
            4,
            1,
        );
        let template = image(
            &[10, 0, 0, 255, 99, 0, 0, 255, 30, 0, 0, 255, 40, 0, 0, 255],
            4,
            1,
        );
        let matched = find_template(
            screen,
            template,
            TemplateOptions {
                search: options(4, 1),
                tolerance: ColorTolerance::EXACT,
                minimum_match_permille: 750,
                ignore_transparent_template_pixels: true,
            },
        )
        .expect("search")
        .expect("75 percent match");
        assert_eq!(matched.matched_permille, 750);
    }

    #[test]
    fn search_orders_and_steps_have_stable_first_results() {
        let pixels = [10, 20, 30, 255].repeat(9);
        let view = image(&pixels, 3, 3);
        let target = Color {
            red: 10,
            green: 20,
            blue: 30,
            alpha: 255,
        };
        let cases = [
            (SearchOrder::BottomLeftToTopRight, PixelPoint { x: 0, y: 2 }),
            (SearchOrder::TopLeftToBottomRight, PixelPoint { x: 0, y: 0 }),
            (SearchOrder::BottomRightToTopLeft, PixelPoint { x: 2, y: 2 }),
            (SearchOrder::TopRightToBottomLeft, PixelPoint { x: 2, y: 0 }),
            (SearchOrder::CenterOut, PixelPoint { x: 1, y: 1 }),
        ];
        for (order, expected) in cases {
            let mut search = options(3, 3);
            search.order = order;
            assert_eq!(
                find_color(view, target, ColorTolerance::EXACT, search),
                Ok(Some(expected))
            );
        }

        let mut center = options(3, 3);
        center.order = SearchOrder::CenterOut;
        assert_eq!(
            find_all_colors(view, target, ColorTolerance::EXACT, center, 9),
            Ok(vec![
                PixelPoint { x: 1, y: 1 },
                PixelPoint { x: 0, y: 0 },
                PixelPoint { x: 1, y: 0 },
                PixelPoint { x: 2, y: 0 },
                PixelPoint { x: 2, y: 1 },
                PixelPoint { x: 2, y: 2 },
                PixelPoint { x: 1, y: 2 },
                PixelPoint { x: 0, y: 2 },
                PixelPoint { x: 0, y: 1 },
            ])
        );

        let mut stepped = options(3, 3);
        stepped.step_x = 2;
        stepped.step_y = 2;
        assert_eq!(
            find_all_colors(view, target, ColorTolerance::EXACT, stepped, 4),
            Ok(vec![
                PixelPoint { x: 0, y: 0 },
                PixelPoint { x: 2, y: 0 },
                PixelPoint { x: 0, y: 2 },
                PixelPoint { x: 2, y: 2 },
            ])
        );
    }

    #[test]
    fn every_search_order_visits_each_step_aligned_point_once() {
        let orders = [
            SearchOrder::BottomLeftToTopRight,
            SearchOrder::TopLeftToBottomRight,
            SearchOrder::BottomRightToTopLeft,
            SearchOrder::TopRightToBottomLeft,
            SearchOrder::CenterOut,
        ];
        for width in 1..=6 {
            for height in 1..=6 {
                for step_x in 1..=3 {
                    for step_y in 1..=3 {
                        for order in orders {
                            let options = SearchOptions {
                                roi: PixelRect {
                                    left: 7,
                                    top: 11,
                                    right: 7 + width,
                                    bottom: 11 + height,
                                },
                                step_x,
                                step_y,
                                order,
                                max_pixel_comparisons: 1_000,
                            };
                            let points: Vec<_> = super::SearchPoints::new(options).collect();
                            let expected = usize::try_from(
                                super::axis_len(7, 7 + width, step_x)
                                    * super::axis_len(11, 11 + height, step_y),
                            )
                            .expect("small grid");
                            assert_eq!(points.len(), expected);
                            let mut unique = points.clone();
                            unique.sort_unstable_by_key(|point| (point.y, point.x));
                            unique.dedup();
                            assert_eq!(unique.len(), expected);
                            assert!(points.iter().all(|point| {
                                point.x >= 7
                                    && point.x < 7 + width
                                    && point.y >= 11
                                    && point.y < 11 + height
                                    && (point.x - 7) % step_x == 0
                                    && (point.y - 11) % step_y == 0
                            }));
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn fixed_fixture_regresses_color_template_and_percentage_results() {
        let screen_pixels = fixture(include_str!("../tests/fixtures/screen.rgba.txt"));
        let template_pixels = fixture(include_str!("../tests/fixtures/template.rgba.txt"));
        let screen = image(&screen_pixels, 4, 3);
        let template = image(&template_pixels, 2, 2);
        let matched = find_template(
            screen,
            template,
            TemplateOptions {
                search: options(4, 3),
                tolerance: ColorTolerance::EXACT,
                minimum_match_permille: 1_000,
                ignore_transparent_template_pixels: true,
            },
        )
        .expect("fixture search")
        .expect("fixture match");
        assert_eq!(matched.origin, PixelPoint { x: 1, y: 1 });

        let fixed = [
            FixedPatternSample {
                point: PixelPoint { x: 1, y: 1 },
                color: Color {
                    red: 0xaa,
                    green: 0xbb,
                    blue: 0xcc,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
            FixedPatternSample {
                point: PixelPoint { x: 0, y: 0 },
                color: Color {
                    red: 255,
                    green: 255,
                    blue: 255,
                    alpha: 255,
                },
                tolerance: ColorTolerance::EXACT,
            },
        ];
        assert_eq!(compare_fixed_pattern(screen, &fixed, 50, 2), Ok(true));
        assert_eq!(compare_fixed_pattern(screen, &fixed, 51, 2), Ok(false));
    }

    #[test]
    fn prepared_hash_is_canonical_across_rgba_and_bgra_storage() {
        let rgba = [1, 2, 3, 255, 4, 5, 6, 0];
        let bgra = [3, 2, 1, 255, 6, 5, 4, 0];
        let rgba_template = PreparedTemplate::prepare(image(&rgba, 2, 1)).expect("RGBA");
        let bgra_template = PreparedTemplate::prepare(ImageView {
            width: 2,
            height: 1,
            row_stride: 8,
            format: PixelFormat::Bgra8888,
            pixels: &bgra,
        })
        .expect("BGRA");
        assert_eq!(rgba_template.content_hash(), bgra_template.content_hash());
    }

    #[test]
    fn template_preparation_prefers_rare_contrasting_anchors() {
        let pixels = [0, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 0, 0, 255];
        let prepared = PreparedTemplate::prepare(image(&pixels, 4, 1)).expect("template");
        assert_eq!(
            prepared.opaque_anchors[0].expect("anchor").color,
            Color {
                red: 255,
                green: 0,
                blue: 0,
                alpha: 255,
            }
        );
    }
}
