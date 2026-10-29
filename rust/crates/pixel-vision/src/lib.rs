//! Deterministic, bounded scalar baseline for pixel color and template search.

pub mod color_filter;
pub mod color_palette;
pub mod image_processing;
mod legacy;
mod legacy_compat;
pub mod scaled_search;
mod search_metrics;
mod template_prefilter;
pub use search_metrics::TemplateSearchMetrics;
use template_prefilter::{
    integral_count, AdditionalTemplateFilter, IntegralCondition, RegionCondition,
};
#[cfg(test)]
mod auxiliary_prefilter_tests;
#[cfg(test)]
mod bulk_match_tests;
#[cfg(test)]
mod expanded_matrix_tests;
#[cfg(test)]
mod performance_matrix_tests;
#[cfg(test)]
mod search_optimization_tests;

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
    bgra_pixels: Vec<[u8; 4]>,
    opaque_count: u64,
    all_anchors: [Option<PreparedPixel>; 4],
    opaque_anchors: [Option<PreparedPixel>; 4],
    majority_all: Option<(Color, u64)>,
    majority_opaque: Option<(Color, u64)>,
    additional_all: AdditionalTemplateFilter,
    additional_opaque: AdditionalTemplateFilter,
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
        let majority_all = majority_color(&pixels, false);
        let majority_opaque = majority_color(&pixels, true);
        let additional_all = AdditionalTemplateFilter::prepare(
            &pixels,
            template.width,
            template.height,
            false,
            majority_all,
        );
        let additional_opaque = AdditionalTemplateFilter::prepare(
            &pixels,
            template.width,
            template.height,
            true,
            majority_opaque,
        );
        Ok(Self {
            width: template.width,
            height: template.height,
            content_hash: hasher.finalize().into(),
            bgra_pixels: pixels
                .into_iter()
                .map(|color| [color.blue, color.green, color.red, color.alpha])
                .collect(),
            opaque_count,
            all_anchors,
            opaque_anchors,
            majority_all,
            majority_opaque,
            additional_all,
            additional_opaque,
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
        self.bgra_pixels
            .len()
            .saturating_mul(std::mem::size_of::<[u8; 4]>())
            .saturating_add(std::mem::size_of::<Self>())
    }

    fn relevant_count(&self, ignore_transparent: bool) -> u64 {
        if ignore_transparent {
            self.opaque_count
        } else {
            u64::try_from(self.bgra_pixels.len()).unwrap_or(u64::MAX)
        }
    }

    fn anchors(&self, ignore_transparent: bool) -> [Option<PreparedPixel>; 4] {
        if ignore_transparent {
            self.opaque_anchors
        } else {
            self.all_anchors
        }
    }
}

// Two-pass majority detection uses constant memory, unlike a full exact-color histogram.
fn majority_color(pixels: &[Color], ignore_transparent: bool) -> Option<(Color, u64)> {
    let mut candidate = None;
    let mut votes = 0_u64;
    let mut relevant = 0_u64;
    for color in pixels
        .iter()
        .copied()
        .filter(|color| !ignore_transparent || color.alpha != 0)
    {
        relevant += 1;
        if votes == 0 {
            candidate = Some(color);
            votes = 1;
        } else if candidate == Some(color) {
            votes += 1;
        } else {
            votes -= 1;
        }
    }
    let color = candidate?;
    let count = pixels.iter().filter(|pixel| **pixel == color).count() as u64;
    (count > relevant / 2).then_some((color, count))
}

/// Exact necessary condition, not an approximate matcher: at least `F - allowed_mismatches`
/// screen pixels must match a template color which occurs F times, regardless of alignment.
struct MajorityIntegral {
    roi: PixelRect,
    stride: usize,
    counts: Vec<u32>,
    required: u32,
    maximum: u64,
    width: u32,
    height: u32,
    auxiliary: Option<IntegralCondition>,
    regions: Vec<RegionCondition>,
}

impl MajorityIntegral {
    fn build(
        image: ImageView<'_>,
        template: &PreparedTemplate,
        options: TemplateOptions,
        budget: &mut ComparisonBudget<'_>,
    ) -> Result<Option<Self>, VisionError> {
        let relevant = template.relevant_count(options.ignore_transparent_template_pixels);
        let required = relevant
            .saturating_mul(u64::from(options.minimum_match_permille))
            .div_ceil(1000);
        let allowed = relevant - required;
        let majority = if options.ignore_transparent_template_pixels {
            template.majority_opaque
        } else {
            template.majority_all
        };
        let Some((color, count)) = majority else {
            return Ok(None);
        };
        let roi = options.search.roi;
        let stride = usize::try_from(roi.width())
            .ok()
            .and_then(|width| width.checked_add(1))
            .ok_or(VisionError::InvalidRoi)?;
        let length = stride
            .checked_mul(roi.height() as usize + 1)
            .ok_or(VisionError::InvalidRoi)?;
        let candidates = u64::from(roi.width() - template.width + 1)
            .div_ceil(u64::from(options.search.step_x))
            * u64::from(roi.height() - template.height + 1)
                .div_ceil(u64::from(options.search.step_y));
        // Tiny/strict searches already reject efficiently with four anchors. Avoid setup overhead.
        if allowed < 4
            || relevant < 64
            || count <= allowed
            || candidates < 8192
            || length > 8 * 1024 * 1024 / std::mem::size_of::<u32>()
        {
            return Ok(None);
        }
        let mut counts = vec![0_u32; length];
        let additional = if options.ignore_transparent_template_pixels {
            &template.additional_opaque
        } else {
            &template.additional_all
        };
        let exact = options.tolerance == ColorTolerance::EXACT;
        let auxiliary_spec = additional.auxiliary.filter(|(_, frequency)| {
            exact
                && frequency.saturating_sub(allowed) >= relevant.div_ceil(20).max(8)
                && length <= 8 * 1024 * 1024 / (2 * std::mem::size_of::<u32>())
                && u64::from(roi.width()) * u64::from(roi.height()) <= budget.remaining / 4
        });
        let mut auxiliary = auxiliary_spec.map(|(_, frequency)| IntegralCondition {
            counts: vec![0; length],
            required: frequency - allowed,
            maximum: u64::from(template.width) * u64::from(template.height)
                - (relevant - frequency).saturating_sub(allowed),
        });
        for y in 0..roi.height() as usize {
            let offset = (roi.top as usize + y) * image.row_stride + roi.left as usize * 4;
            let row = &image.pixels[offset..offset + roi.width() as usize * 4];
            let mut sum = 0_u32;
            let mut auxiliary_sum = 0_u32;
            for (x, bytes) in row.chunks_exact(4).enumerate() {
                budget.consume(1)?;
                let actual = decode_pixel(bytes, image.format);
                sum += u32::from(color_matches(actual, color, options.tolerance));
                counts[(y + 1) * stride + x + 1] = counts[y * stride + x + 1] + sum;
                if let (Some(condition), Some((bucket, _))) = (&mut auxiliary, auxiliary_spec) {
                    budget.consume(1)?;
                    auxiliary_sum += u32::from(anchor_bucket(actual) == bucket);
                    condition.counts[(y + 1) * stride + x + 1] =
                        condition.counts[y * stride + x + 1] + auxiliary_sum;
                }
            }
        }
        Ok(Some(Self {
            roi,
            stride,
            counts,
            required: u32::try_from(count - allowed).map_err(|_| VisionError::InvalidImage)?,
            // Only exact colors are disjoint. Count the full window when transparent template pixels are skipped.
            maximum: if options.tolerance == ColorTolerance::EXACT {
                u64::from(template.width) * u64::from(template.height)
                    - (relevant - count).saturating_sub(allowed)
            } else {
                u64::MAX
            },
            width: template.width,
            height: template.height,
            auxiliary,
            regions: if exact {
                additional.conditions(allowed)
            } else {
                Vec::new()
            },
        }))
    }

    fn rejects(&self, origin: PixelPoint) -> bool {
        let left = (origin.x - self.roi.left) as usize;
        let top = (origin.y - self.roi.top) as usize;
        let right = left + self.width as usize;
        let bottom = top + self.height as usize;
        let count = integral_count(&self.counts, self.stride, left, top, right, bottom);
        if count < u64::from(self.required) || count > self.maximum {
            return true;
        }
        if self.auxiliary.as_ref().is_some_and(|condition| {
            let count = integral_count(&condition.counts, self.stride, left, top, right, bottom);
            count < condition.required || count > condition.maximum
        }) {
            return true;
        }
        self.regions.iter().any(|condition| {
            let rect = condition.rect;
            let count = integral_count(
                &self.counts,
                self.stride,
                left + rect.left as usize,
                top + rect.top as usize,
                left + rect.right as usize,
                top + rect.bottom as usize,
            );
            count < condition.required || count > condition.maximum
        })
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
    InvalidScaleRange,
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
    Cancelled,
}

pub const MAX_COLOR_RESULTS: usize = 4_096;
pub const MAX_COLOR_COUNT: usize = 64_000_000;

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

/// Counts matching pixels in deterministic row-major order. Zero counts the entire ROI;
/// a positive limit stops early without retaining a coordinate list.
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
    if limit > MAX_COLOR_COUNT {
        return Err(VisionError::InvalidResultLimit);
    }
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
/// Zero counts the entire ROI; positive limits stop early. Rejects malformed inputs,
/// invalid limits, or an exhausted comparison budget.
pub fn count_any_color(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
    limit: usize,
) -> Result<usize, VisionError> {
    if limit > MAX_COLOR_COUNT {
        return Err(VisionError::InvalidResultLimit);
    }
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
    let mut remaining = options.search.max_pixel_comparisons;
    find_prepared_template_controlled(image, template, options, &mut remaining, &mut || false)
}

/// Searches using a shared comparison budget and cooperative cancellation checkpoints.
/// The budget is consumed even when matching fails or is cancelled.
///
/// # Errors
///
/// Returns geometry/threshold errors, cancellation, or comparison-budget exhaustion.
pub fn find_prepared_template_controlled(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    remaining: &mut u64,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Option<TemplateMatch>, VisionError> {
    find_prepared_template_observed(image, template, options, remaining, cancelled, None)
}

/// The controlled search with optional bounded, content-free stage observations.
/// # Errors
/// Returns the same errors and consumes the same shared budget as the unobserved search.
pub fn find_prepared_template_observed(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    remaining: &mut u64,
    cancelled: &mut dyn FnMut() -> bool,
    mut metrics: Option<&mut TemplateSearchMetrics>,
) -> Result<Option<TemplateMatch>, VisionError> {
    let initial = (*remaining).min(options.search.max_pixel_comparisons);
    let mut budget = ComparisonBudget {
        remaining: initial,
        checkpoint: Some(cancelled),
        next_check: initial,
    };
    let started = metrics.as_ref().map(|_| std::time::Instant::now());
    let previous_prefilter_us = metrics.as_ref().map_or(0, |m| m.prefilter_build_us);
    let result = search_prepared_template(
        image,
        template,
        options,
        &mut budget,
        metrics.as_deref_mut(),
    );
    if let (Some(m), Some(started)) = (metrics, started) {
        m.search_us += search_metrics::elapsed_us(started)
            .saturating_sub(m.prefilter_build_us - previous_prefilter_us);
    }
    *remaining = remaining.saturating_sub(initial - budget.remaining);
    result
}

/// Collects matching origins in search order with a shared budget and cancellation.
/// The extra slot (maximum 65) lets callers detect truncation of a 64-item result.
/// # Errors
/// Rejects invalid limits/geometry/options, cancellation and exhausted budgets.
pub fn find_prepared_templates_controlled(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    limit: usize,
    remaining: &mut u64,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Vec<TemplateMatch>, VisionError> {
    find_prepared_templates_observed(image, template, options, limit, remaining, cancelled, None)
}

/// Multi-result controlled search with optional per-call observations.
/// # Errors
/// Returns the same errors and consumes the same shared budget as the unobserved search.
pub fn find_prepared_templates_observed(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    limit: usize,
    remaining: &mut u64,
    cancelled: &mut dyn FnMut() -> bool,
    mut metrics: Option<&mut TemplateSearchMetrics>,
) -> Result<Vec<TemplateMatch>, VisionError> {
    let initial = (*remaining).min(options.search.max_pixel_comparisons);
    let mut budget = ComparisonBudget {
        remaining: initial,
        checkpoint: Some(cancelled),
        next_check: initial,
    };
    let started = metrics.as_ref().map(|_| std::time::Instant::now());
    let previous_prefilter_us = metrics.as_ref().map_or(0, |m| m.prefilter_build_us);
    let result = (|| {
        if !(1..=65).contains(&limit) {
            return Err(VisionError::InvalidResultLimit);
        }
        validate_search(image, options.search)?;
        if template.width == 0 || template.height == 0 {
            return Err(VisionError::EmptyTemplate);
        }
        if options.minimum_match_permille > 1000 {
            return Err(VisionError::InvalidSimilarity);
        }
        if template.width > options.search.roi.width()
            || template.height > options.search.roi.height()
        {
            return Err(VisionError::TemplateOutsideRoi);
        }
        let candidates = SearchOptions {
            roi: PixelRect {
                right: options.search.roi.right - template.width + 1,
                bottom: options.search.roi.bottom - template.height + 1,
                ..options.search.roi
            },
            ..options.search
        };
        let comparison = TemplateComparison::new(image, template, options);
        let mut matches = Vec::new();
        let mut prefilter = None;
        for (index, origin) in SearchPoints::new(candidates).enumerate() {
            // Probe the first full candidate before paying setup; never compare it twice.
            if index == 1 {
                prefilter = observed_prefilter(
                    image,
                    template,
                    options,
                    &mut budget,
                    metrics.as_deref_mut(),
                )?;
            }
            if let Some(m) = metrics.as_deref_mut() {
                m.candidates_visited += 1;
            }
            if prefilter
                .as_ref()
                .is_some_and(|filter| filter.rejects(origin))
            {
                if let Some(m) = metrics.as_deref_mut() {
                    m.prefilter_rejected += 1;
                }
                budget.consume(1)?;
                continue;
            }
            let score = observed_compare(
                image,
                &comparison,
                origin,
                options,
                &mut budget,
                metrics.as_deref_mut(),
            )?;
            if score >= options.minimum_match_permille {
                matches.push(TemplateMatch {
                    origin,
                    matched_permille: score,
                });
                if matches.len() == limit {
                    break;
                }
            }
        }
        Ok(matches)
    })();
    if let (Some(m), Some(started)) = (metrics, started) {
        m.search_us += search_metrics::elapsed_us(started)
            .saturating_sub(m.prefilter_build_us - previous_prefilter_us);
    }
    *remaining = remaining.saturating_sub(initial - budget.remaining);
    result
}

fn search_prepared_template(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    budget: &mut ComparisonBudget<'_>,
    mut metrics: Option<&mut TemplateSearchMetrics>,
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
    let comparison = TemplateComparison::new(image, template, options);
    let mut prefilter = None;
    for (index, origin) in SearchPoints::new(candidate_options).enumerate() {
        if index == 1 {
            prefilter =
                observed_prefilter(image, template, options, budget, metrics.as_deref_mut())?;
        }
        if let Some(m) = metrics.as_deref_mut() {
            m.candidates_visited += 1;
        }
        if prefilter
            .as_ref()
            .is_some_and(|filter| filter.rejects(origin))
        {
            if let Some(m) = metrics.as_deref_mut() {
                m.prefilter_rejected += 1;
            }
            budget.consume(1)?;
            continue;
        }
        let score = observed_compare(
            image,
            &comparison,
            origin,
            options,
            budget,
            metrics.as_deref_mut(),
        )?;
        if score >= options.minimum_match_permille {
            return Ok(Some(TemplateMatch {
                origin,
                matched_permille: score,
            }));
        }
    }
    Ok(None)
}

fn observed_prefilter(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    budget: &mut ComparisonBudget<'_>,
    metrics: Option<&mut TemplateSearchMetrics>,
) -> Result<Option<MajorityIntegral>, VisionError> {
    let started = metrics.as_ref().map(|_| std::time::Instant::now());
    let before = budget.remaining;
    let result = MajorityIntegral::build(image, template, options, budget);
    if let (Some(m), Some(started)) = (metrics, started) {
        m.prefilter_build_us += search_metrics::elapsed_us(started);
        m.prefilter_work += before - budget.remaining;
        m.prefilters_built += result
            .as_ref()
            .ok()
            .and_then(Option::as_ref)
            .map_or(0, |filter| 1 + u64::from(filter.auxiliary.is_some()));
    }
    result
}

fn observed_compare(
    image: ImageView<'_>,
    comparison: &TemplateComparison<'_>,
    origin: PixelPoint,
    options: TemplateOptions,
    budget: &mut ComparisonBudget<'_>,
    metrics: Option<&mut TemplateSearchMetrics>,
) -> Result<u16, VisionError> {
    let before = budget.remaining;
    let result = compare_template_prepared(image, comparison, origin, options, budget);
    if let Some(m) = metrics {
        m.comparison_pixels += before - budget.remaining;
    }
    result
}

struct TemplateComparison<'a> {
    template: &'a PreparedTemplate,
    relevant: u64,
    allowed: u64,
    anchors: [Option<(usize, Color)>; 4],
    holes: [(u32, u32); 4],
}

impl<'a> TemplateComparison<'a> {
    fn new(image: ImageView<'_>, template: &'a PreparedTemplate, options: TemplateOptions) -> Self {
        let relevant = template.relevant_count(options.ignore_transparent_template_pixels);
        let anchors = template.anchors(options.ignore_transparent_template_pixels);
        let mut holes = anchors.map(|a| a.map_or((u32::MAX, u32::MAX), |p| (p.y, p.x)));
        holes.sort_unstable();
        Self {
            template,
            relevant,
            allowed: relevant
                - relevant
                    .saturating_mul(u64::from(options.minimum_match_permille))
                    .div_ceil(1000),
            anchors: anchors
                .map(|a| a.map(|p| (p.y as usize * image.row_stride + p.x as usize * 4, p.color))),
            holes,
        }
    }
}

#[cfg(test)]
fn compare_template(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    origin: PixelPoint,
    options: TemplateOptions,
    budget: &mut ComparisonBudget,
) -> Result<u16, VisionError> {
    compare_template_prepared(
        image,
        &TemplateComparison::new(image, template, options),
        origin,
        options,
        budget,
    )
}

fn compare_template_prepared(
    image: ImageView<'_>,
    comparison: &TemplateComparison<'_>,
    origin: PixelPoint,
    options: TemplateOptions,
    budget: &mut ComparisonBudget,
) -> Result<u16, VisionError> {
    let template = comparison.template;
    let ignore = options.ignore_transparent_template_pixels
        && template.opaque_count != template.bgra_pixels.len() as u64;
    let exact = options.tolerance == ColorTolerance::EXACT;
    match (image.format, exact, ignore) {
        (PixelFormat::Rgba8888, true, false) => {
            compare_template_spans::<false, true, false>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Rgba8888, true, true) => {
            compare_template_spans::<false, true, true>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Bgra8888, true, false) => {
            compare_template_spans::<true, true, false>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Bgra8888, true, true) => {
            compare_template_spans::<true, true, true>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Rgba8888, false, false) => compare_template_spans::<false, false, false>(
            image, comparison, origin, options, budget,
        ),
        (PixelFormat::Rgba8888, false, true) => {
            compare_template_spans::<false, false, true>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Bgra8888, false, false) => {
            compare_template_spans::<true, false, false>(image, comparison, origin, options, budget)
        }
        (PixelFormat::Bgra8888, false, true) => {
            compare_template_spans::<true, false, true>(image, comparison, origin, options, budget)
        }
    }
}

fn compare_template_spans<const BGRA: bool, const EXACT: bool, const IGNORE: bool>(
    image: ImageView<'_>,
    comparison: &TemplateComparison<'_>,
    origin: PixelPoint,
    options: TemplateOptions,
    budget: &mut ComparisonBudget,
) -> Result<u16, VisionError> {
    let template = comparison.template;
    let relevant = comparison.relevant;
    if relevant == 0 {
        return Err(VisionError::EmptyTemplate);
    }
    let allowed = comparison.allowed;
    let mut mismatches = 0;
    let origin_start = origin.y as usize * image.row_stride + origin.x as usize * 4;
    for (offset, expected) in comparison.anchors.iter().flatten().copied() {
        budget.consume(1)?;
        let actual = &image.pixels[origin_start + offset..origin_start + offset + 4];
        if !color_matches(
            decode_pixel(actual, image.format),
            expected,
            options.tolerance,
        ) {
            mismatches += 1;
        }
        if mismatches > allowed {
            return Ok(0);
        }
    }
    let holes = &comparison.holes;
    let mut hole = 0;
    let width = template.width as usize;
    let mut actual_start = origin_start;
    for (y, expected_row) in template.bgra_pixels.chunks_exact(width).enumerate() {
        let actual_row = &image.pixels[actual_start..actual_start + width * 4];
        let mut left = 0;
        while hole < holes.len() && holes[hole].0 as usize == y {
            let right = holes[hole].1 as usize;
            if !compare_span::<BGRA, EXACT, IGNORE>(
                &expected_row[left..right],
                &actual_row[left * 4..right * 4],
                options.tolerance,
                allowed,
                &mut mismatches,
                budget,
            )? {
                return Ok(0);
            }
            left = right + 1;
            hole += 1;
        }
        if !compare_span::<BGRA, EXACT, IGNORE>(
            &expected_row[left..],
            &actual_row[left * 4..],
            options.tolerance,
            allowed,
            &mut mismatches,
            budget,
        )? {
            return Ok(0);
        }
        actual_start += image.row_stride;
    }
    u16::try_from((relevant - mismatches).saturating_mul(1_000) / relevant)
        .map_err(|_| VisionError::InvalidSimilarity)
}

#[inline]
fn compare_span<const BGRA: bool, const EXACT: bool, const IGNORE: bool>(
    expected: &[[u8; 4]],
    actual: &[u8],
    tolerance: ColorTolerance,
    allowed: u64,
    mismatches: &mut u64,
    budget: &mut ComparisonBudget,
) -> Result<bool, VisionError> {
    compare_span_mode::<BGRA, EXACT, IGNORE, false>(
        expected, actual, tolerance, allowed, mismatches, budget,
    )
}

#[inline]
fn compare_span_mode<
    const BGRA: bool,
    const EXACT: bool,
    const IGNORE: bool,
    const WORD_MATCH: bool,
>(
    expected: &[[u8; 4]],
    actual: &[u8],
    tolerance: ColorTolerance,
    allowed: u64,
    mismatches: &mut u64,
    budget: &mut ComparisonBudget,
) -> Result<bool, VisionError> {
    const BLOCK: usize = 32;
    if !(EXACT || cfg!(test) && WORD_MATCH)
        || IGNORE
        || expected.len() < BLOCK
        || allowed - *mismatches < BLOCK as u64
    {
        return compare_span_scalar::<BGRA, EXACT, IGNORE>(
            expected, actual, tolerance, allowed, mismatches, budget,
        );
    }
    let mut offset = 0;
    while offset + BLOCK <= expected.len() && allowed - *mismatches >= BLOCK as u64 {
        let colors = &expected[offset..offset + BLOCK];
        let bytes = &actual[offset * 4..(offset + BLOCK) * 4];
        // No pixel in this block can fail or cross a budget/cancellation boundary.
        if allowed - *mismatches >= BLOCK as u64
            && budget.remaining.saturating_sub(budget.next_check) >= BLOCK as u64
        {
            budget.consume(BLOCK as u64)?;
            let mut converted = [[0; 4]; BLOCK];
            let packed = if BGRA {
                colors
            } else {
                for (dst, color) in converted.iter_mut().zip(colors) {
                    *dst = [color[2], color[1], color[0], color[3]];
                }
                &converted[..]
            };
            // The alternative is test-only: measurements did not justify a production change.
            #[cfg(test)]
            let count = if !EXACT {
                tolerant_block_mismatches::<BGRA>(colors, bytes, tolerance)
            } else if WORD_MATCH {
                packed
                    .iter()
                    .zip(bytes.chunks_exact(4))
                    .map(|(color, pixel)| {
                        u64::from(
                            u32::from_ne_bytes(*color)
                                != u32::from_ne_bytes(pixel.try_into().expect("four-byte chunk")),
                        )
                    })
                    .sum::<u64>()
            } else {
                packed
                    .iter()
                    .zip(bytes.chunks_exact(4))
                    .map(|(color, pixel)| u64::from(pixel != *color))
                    .sum::<u64>()
            };
            #[cfg(not(test))]
            let count = packed
                .iter()
                .zip(bytes.chunks_exact(4))
                .map(|(color, pixel)| u64::from(pixel != *color))
                .sum::<u64>();
            *mismatches += count;
        } else if !compare_span_scalar::<BGRA, EXACT, IGNORE>(
            colors, bytes, tolerance, allowed, mismatches, budget,
        )? {
            return Ok(false);
        }
        offset += BLOCK;
    }
    compare_span_scalar::<BGRA, EXACT, IGNORE>(
        &expected[offset..],
        &actual[offset * 4..],
        tolerance,
        allowed,
        mismatches,
        budget,
    )
}

#[inline]
#[cfg(test)]
#[allow(clippy::needless_bitwise_bool)] // All channels must be evaluated to allow bounded block vectorization.
fn tolerant_block_mismatches<const BGRA: bool>(
    expected: &[[u8; 4]],
    actual: &[u8],
    tolerance: ColorTolerance,
) -> u64 {
    expected
        .iter()
        .zip(actual.chunks_exact(4))
        .map(|(color, pixel)| {
            // No data-dependent early exit: the caller proves this whole block is safe.
            let blue = pixel[if BGRA { 0 } else { 2 }].abs_diff(color[0]);
            let green = pixel[1].abs_diff(color[1]);
            let red = pixel[if BGRA { 2 } else { 0 }].abs_diff(color[2]);
            let alpha = pixel[3].abs_diff(color[3]);
            u64::from(
                (blue > tolerance.blue)
                    | (green > tolerance.green)
                    | (red > tolerance.red)
                    | (alpha > tolerance.alpha),
            )
        })
        .sum()
}

#[inline]
fn compare_span_scalar<const BGRA: bool, const EXACT: bool, const IGNORE: bool>(
    expected: &[[u8; 4]],
    actual: &[u8],
    tolerance: ColorTolerance,
    allowed: u64,
    mismatches: &mut u64,
    budget: &mut ComparisonBudget,
) -> Result<bool, VisionError> {
    for (color, bytes) in expected.iter().zip(actual.chunks_exact(4)) {
        if IGNORE && color[3] == 0 {
            continue;
        }
        budget.consume(1)?;
        let equal = if EXACT {
            let packed = if BGRA {
                *color
            } else {
                [color[2], color[1], color[0], color[3]]
            };
            bytes == packed
        } else {
            let actual = if BGRA {
                Color {
                    red: bytes[2],
                    green: bytes[1],
                    blue: bytes[0],
                    alpha: bytes[3],
                }
            } else {
                Color {
                    red: bytes[0],
                    green: bytes[1],
                    blue: bytes[2],
                    alpha: bytes[3],
                }
            };
            color_matches(
                actual,
                decode_pixel(color, PixelFormat::Bgra8888),
                tolerance,
            )
        };
        if !equal {
            *mismatches += 1;
        }
        if *mismatches > allowed {
            return Ok(false);
        }
    }
    Ok(true)
}

#[cfg(test)]
fn compare_template_reference(
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
    let allowed_mismatches = relevant - required_matches;
    let mut mismatches = 0_u64;

    for pixel in anchors.iter().flatten().copied() {
        budget.consume(1)?;
        if !color_matches(
            read_pixel(image, origin.x + pixel.x, origin.y + pixel.y),
            pixel.color,
            options.tolerance,
        ) {
            mismatches += 1;
        }
        if mismatches > allowed_mismatches {
            return Ok(0);
        }
    }

    // Geometry was validated before candidate traversal. Slice once per row rather than
    // recomputing checked template/screen offsets for every compared pixel.
    let width = usize::try_from(template.width).map_err(|_| VisionError::InvalidImage)?;
    let origin_x = usize::try_from(origin.x).map_err(|_| VisionError::InvalidImage)?;
    let origin_y = usize::try_from(origin.y).map_err(|_| VisionError::InvalidImage)?;
    let anchor_coordinates = anchors.map(|anchor| {
        anchor.map(|pixel| {
            (
                usize::try_from(pixel.x).expect("validated template geometry"),
                usize::try_from(pixel.y).expect("validated template geometry"),
            )
        })
    });
    for (template_y, template_row) in template.bgra_pixels.chunks_exact(width).enumerate() {
        let start = (origin_y + template_y) * image.row_stride + origin_x * 4;
        let screen_row = &image.pixels[start..start + width * 4];
        for (template_x, (&expected, actual)) in template_row
            .iter()
            .zip(screen_row.chunks_exact(4))
            .enumerate()
        {
            let expected = decode_pixel(&expected, PixelFormat::Bgra8888);
            if options.ignore_transparent_template_pixels && expected.alpha == 0 {
                continue;
            }
            if anchor_coordinates.contains(&Some((template_x, template_y))) {
                continue;
            }
            budget.consume(1)?;
            if !color_matches(
                decode_pixel(actual, image.format),
                expected,
                options.tolerance,
            ) {
                mismatches += 1;
            }
            if mismatches > allowed_mismatches {
                return Ok(0);
            }
        }
    }
    u16::try_from((relevant - mismatches).saturating_mul(1_000) / relevant)
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
    decode_pixel(bytes, image.format)
}

fn decode_pixel(bytes: &[u8], format: PixelFormat) -> Color {
    match format {
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
    corner_column: u64,
    corner_row: u64,
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
            corner_column: 0,
            corner_row: 0,
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
        let row_index = self.corner_row;
        let column_index = self.corner_column;
        self.corner_column += 1;
        if self.corner_column == self.columns {
            self.corner_column = 0;
            self.corner_row += 1;
        }
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

struct ComparisonBudget<'a> {
    remaining: u64,
    checkpoint: Option<&'a mut dyn FnMut() -> bool>,
    next_check: u64,
}

impl ComparisonBudget<'_> {
    const fn new(maximum: u64) -> Self {
        Self {
            remaining: maximum,
            checkpoint: None,
            next_check: maximum,
        }
    }

    fn consume(&mut self, count: u64) -> Result<(), VisionError> {
        if self.remaining <= self.next_check {
            if self.checkpoint.as_mut().is_some_and(|check| check()) {
                return Err(VisionError::Cancelled);
            }
            self.next_check = self.remaining.saturating_sub(1024);
        }
        self.remaining = self
            .remaining
            .checked_sub(count)
            .ok_or(VisionError::ComparisonBudgetExceeded)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn public_multi_fixture_preserves_offset_order_limits_budget_and_cancel() {
        let mut pixels = vec![255; 10 * 6 * 4];
        let patch = [
            12, 12, 12, 255, 38, 38, 38, 255, 57, 57, 57, 255, 94, 94, 94, 255,
        ];
        for (left, top) in [(3, 2), (7, 3)] {
            for y in 0..2 {
                let offset = ((top + y) * 10 + left) * 4;
                pixels[offset..offset + 8].copy_from_slice(&patch[y * 8..y * 8 + 8]);
            }
        }
        let template = super::PreparedTemplate::prepare(image(&patch, 2, 2)).unwrap();
        let config = super::TemplateOptions {
            search: super::SearchOptions {
                roi: super::PixelRect {
                    left: 2,
                    top: 1,
                    right: 10,
                    bottom: 6,
                },
                ..options(10, 6)
            },
            tolerance: super::ColorTolerance::EXACT,
            minimum_match_permille: 1000,
            ignore_transparent_template_pixels: true,
        };
        for limit in [1, 2, 65] {
            let mut budget = 1000;
            let found = super::find_prepared_templates_controlled(
                image(&pixels, 10, 6),
                &template,
                config,
                limit,
                &mut budget,
                &mut || false,
            )
            .unwrap();
            assert_eq!(found.len(), limit.min(2));
            assert_eq!(found[0].origin, super::PixelPoint { x: 3, y: 2 });
            if found.len() > 1 {
                assert_eq!(found[1].origin, super::PixelPoint { x: 7, y: 3 });
            }
            assert!(budget < 1000);
        }
        for limit in [0, 66] {
            assert_eq!(
                super::find_prepared_templates_controlled(
                    image(&pixels, 10, 6),
                    &template,
                    config,
                    limit,
                    &mut 1000,
                    &mut || false
                ),
                Err(super::VisionError::InvalidResultLimit)
            );
        }
        assert_eq!(
            super::find_prepared_templates_controlled(
                image(&pixels, 10, 6),
                &template,
                config,
                2,
                &mut 1000,
                &mut || true
            ),
            Err(super::VisionError::Cancelled)
        );
        assert_eq!(
            super::find_prepared_templates_controlled(
                image(&pixels, 10, 6),
                &template,
                config,
                2,
                &mut 0,
                &mut || false
            ),
            Err(super::VisionError::ComparisonBudgetExceeded)
        );
    }

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

    #[test]
    fn full_color_count_is_not_limited_by_the_coordinate_list_budget() {
        let pixels = [255, 0, 0, 255].repeat(10_000);
        let view = image(&pixels, 100, 100);
        let target = Color {
            red: 255,
            green: 0,
            blue: 0,
            alpha: 255,
        };
        let mut search = options(100, 100);
        search.max_pixel_comparisons = 10_000;
        assert_eq!(
            count_color(view, target, ColorTolerance::EXACT, search, 0),
            Ok(10_000)
        );
        assert_eq!(
            count_color(view, target, ColorTolerance::EXACT, search, 5000),
            Ok(5000)
        );
        assert_eq!(
            find_all_colors(view, target, ColorTolerance::EXACT, search, 2048)
                .unwrap()
                .len(),
            2048
        );
        search.max_pixel_comparisons = 9_999;
        assert_eq!(
            count_color(view, target, ColorTolerance::EXACT, search, 0),
            Err(VisionError::ComparisonBudgetExceeded)
        );
        assert_eq!(
            count_color(
                view,
                target,
                ColorTolerance::EXACT,
                search,
                super::MAX_COLOR_COUNT + 1
            ),
            Err(VisionError::InvalidResultLimit)
        );
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
            Ok(3)
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
    fn corner_orders_keep_the_legacy_row_major_sequence() {
        let base = SearchOptions {
            roi: PixelRect {
                left: 4,
                top: 6,
                right: 13,
                bottom: 11,
            },
            step_x: 2,
            step_y: 2,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000,
        };
        let expected_top_left = [
            (4, 6),
            (6, 6),
            (8, 6),
            (10, 6),
            (12, 6),
            (4, 8),
            (6, 8),
            (8, 8),
            (10, 8),
            (12, 8),
            (4, 10),
            (6, 10),
            (8, 10),
            (10, 10),
            (12, 10),
        ];
        for (order, expected) in [
            (
                SearchOrder::TopLeftToBottomRight,
                expected_top_left.to_vec(),
            ),
            (
                SearchOrder::BottomLeftToTopRight,
                expected_top_left
                    .chunks_exact(5)
                    .rev()
                    .flat_map(|row| row.iter().copied())
                    .collect(),
            ),
            (
                SearchOrder::TopRightToBottomLeft,
                expected_top_left
                    .chunks_exact(5)
                    .flat_map(|row| row.iter().rev().copied())
                    .collect(),
            ),
            (
                SearchOrder::BottomRightToTopLeft,
                expected_top_left
                    .chunks_exact(5)
                    .rev()
                    .flat_map(|row| row.iter().rev().copied())
                    .collect(),
            ),
        ] {
            let mut options = base;
            options.order = order;
            let actual: Vec<_> = super::SearchPoints::new(options)
                .map(|point| (point.x, point.y))
                .collect();
            assert_eq!(actual, expected);
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

    fn assert_row_matches(
        screen: ImageView<'_>,
        template: ImageView<'_>,
        prepared: &PreparedTemplate,
        opts: TemplateOptions,
    ) {
        let candidates = SearchOptions {
            roi: PixelRect {
                right: opts.search.roi.right - template.width + 1,
                bottom: opts.search.roi.bottom - template.height + 1,
                ..opts.search.roi
            },
            ..opts.search
        };
        let mut expected = Vec::new();
        for origin in super::SearchPoints::new(candidates) {
            let mut relevant = 0_u64;
            let mut matched = 0_u64;
            for y in 0..template.height {
                for x in 0..template.width {
                    let pixel = super::read_pixel(template, x, y);
                    if opts.ignore_transparent_template_pixels && pixel.alpha == 0 {
                        continue;
                    }
                    relevant += 1;
                    if super::color_matches(
                        super::read_pixel(screen, origin.x + x, origin.y + y),
                        pixel,
                        opts.tolerance,
                    ) {
                        matched += 1;
                    }
                }
            }
            let score = u16::try_from(matched * 1000 / relevant).unwrap();
            if score >= opts.minimum_match_permille {
                expected.push(super::TemplateMatch {
                    origin,
                    matched_permille: score,
                });
            }
        }
        let mut remaining = 100_000_000;
        let all = super::find_prepared_templates_controlled(
            screen,
            prepared,
            opts,
            64,
            &mut remaining,
            &mut || false,
        )
        .unwrap();
        assert_eq!(all, expected, "{:?}, {opts:?}", screen.format);
        let mut remaining = 100_000_000;
        let first = super::find_prepared_template_controlled(
            screen,
            prepared,
            opts,
            &mut remaining,
            &mut || false,
        )
        .unwrap();
        assert_eq!(first, expected.first().copied());
    }

    #[test]
    fn row_matcher_preserves_scores_directions_transparency_and_padded_formats() {
        for seed in 0_u8..12 {
            let mut rgba = vec![0_u8; 28 * 5];
            for y in 0..5_usize {
                for x in 0..6_usize {
                    let value = u8::try_from((x * 7 + y * 11) % 32).unwrap() + seed;
                    let offset = y * 28 + x * 4;
                    rgba[offset..offset + 4].copy_from_slice(&[
                        value,
                        value / 2,
                        40 - seed,
                        if (x + y) % 5 == 0 { 0 } else { 255 },
                    ]);
                }
            }
            let screen_rgba = ImageView {
                width: 6,
                height: 5,
                row_stride: 28,
                format: PixelFormat::Rgba8888,
                pixels: &rgba,
            };
            let mut patch = Vec::new();
            for y in 1..3 {
                for x in 1..4 {
                    let color = super::read_pixel(screen_rgba, x, y);
                    patch.extend_from_slice(&[
                        color.red + seed % 3,
                        color.green,
                        color.blue,
                        color.alpha,
                    ]);
                }
            }
            let template_view = image(&patch, 3, 2);
            let prepared = PreparedTemplate::prepare(template_view).unwrap();
            let mut bgra = rgba.clone();
            for row in bgra.chunks_exact_mut(28) {
                for pixel in row[..24].chunks_exact_mut(4) {
                    pixel.swap(0, 2);
                }
            }
            for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
                let screen = ImageView {
                    format,
                    pixels: if format == PixelFormat::Rgba8888 {
                        &rgba
                    } else {
                        &bgra
                    },
                    ..screen_rgba
                };
                for ignore_transparent in [false, true] {
                    for threshold in [0, 500, 900, 1000] {
                        for tolerance in [0, 2, 255] {
                            for direction in [
                                SearchOrder::TopLeftToBottomRight,
                                SearchOrder::BottomLeftToTopRight,
                                SearchOrder::BottomRightToTopLeft,
                                SearchOrder::TopRightToBottomLeft,
                                SearchOrder::CenterOut,
                            ] {
                                let search = SearchOptions {
                                    roi: PixelRect {
                                        left: 1,
                                        top: 0,
                                        right: 6,
                                        bottom: 5,
                                    },
                                    order: direction,
                                    ..options(6, 5)
                                };
                                let opts = TemplateOptions {
                                    search,
                                    tolerance: ColorTolerance {
                                        red: tolerance,
                                        green: tolerance,
                                        blue: tolerance,
                                        alpha: tolerance,
                                    },
                                    minimum_match_permille: threshold,
                                    ignore_transparent_template_pixels: ignore_transparent,
                                };
                                assert_row_matches(screen, template_view, &prepared, opts);
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn integral_prefilter_matches_exhaustive_scores_in_all_directions_and_formats() {
        let mut template = vec![0; 16 * 16 * 4];
        for (index, pixel) in template.chunks_exact_mut(4).enumerate() {
            pixel.copy_from_slice(if index < 192 {
                &[100, 120, 140, 255]
            } else if index < 248 {
                &[220, 50, 30, 255]
            } else {
                &[10, 20, 30, 0]
            });
        }
        let prepared = PreparedTemplate::prepare(image(&template, 16, 16)).unwrap();
        let stride = 128 * 4 + 12;
        let mut rgba = vec![0; stride * 128];
        for row in rgba.chunks_exact_mut(stride) {
            for pixel in row[..128 * 4].chunks_exact_mut(4) {
                pixel.copy_from_slice(&[1, 2, 3, 255]);
            }
        }
        for (x, y) in [(20, 30), (90, 95)] {
            for row in 0..16 {
                let offset = (y + row) * stride + x * 4;
                rgba[offset..offset + 64].copy_from_slice(&template[row * 64..row * 64 + 64]);
            }
        }
        let mut bgra = rgba.clone();
        for row in bgra.chunks_exact_mut(stride) {
            for pixel in row[..128 * 4].chunks_exact_mut(4) {
                pixel.swap(0, 2);
            }
        }
        for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
            let screen = ImageView {
                width: 128,
                height: 128,
                row_stride: stride,
                format,
                pixels: if format == PixelFormat::Rgba8888 {
                    &rgba
                } else {
                    &bgra
                },
            };
            for ignore_transparent in [false, true] {
                for tolerance in [0, 5] {
                    for threshold in [900, 950] {
                        for order in [
                            SearchOrder::TopLeftToBottomRight,
                            SearchOrder::BottomLeftToTopRight,
                            SearchOrder::BottomRightToTopLeft,
                            SearchOrder::TopRightToBottomLeft,
                            SearchOrder::CenterOut,
                        ] {
                            for step in [1, 2] {
                                let opts = TemplateOptions {
                                    search: SearchOptions {
                                        roi: PixelRect {
                                            left: 3,
                                            top: 5,
                                            right: 125,
                                            bottom: 126,
                                        },
                                        order,
                                        step_x: step,
                                        step_y: step,
                                        max_pixel_comparisons: 100_000_000,
                                    },
                                    tolerance: ColorTolerance {
                                        red: tolerance,
                                        green: tolerance,
                                        blue: tolerance,
                                        alpha: tolerance,
                                    },
                                    minimum_match_permille: threshold,
                                    ignore_transparent_template_pixels: ignore_transparent,
                                };
                                let mut budget = super::ComparisonBudget::new(100_000_000);
                                let filter = super::MajorityIntegral::build(
                                    screen,
                                    &prepared,
                                    opts,
                                    &mut budget,
                                )
                                .unwrap();
                                assert_eq!(filter.is_some(), step == 1);
                                assert_row_matches(
                                    screen,
                                    image(&template, 16, 16),
                                    &prepared,
                                    opts,
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn integral_setup_is_cancellable_and_respects_the_shared_budget() {
        let frame: Vec<u8> = [0, 0, 0, 255].repeat(128 * 128);
        let template: Vec<u8> = [100, 120, 140, 255].repeat(16 * 16);
        let prepared = PreparedTemplate::prepare(image(&template, 16, 16)).unwrap();
        let opts = TemplateOptions {
            search: options(128, 128),
            tolerance: ColorTolerance::EXACT,
            minimum_match_permille: 900,
            ignore_transparent_template_pixels: true,
        };
        let mut remaining = 100_000_000;
        assert_eq!(
            super::find_prepared_template_controlled(
                image(&frame, 128, 128),
                &prepared,
                opts,
                &mut remaining,
                &mut || true
            ),
            Err(super::VisionError::Cancelled)
        );
        let mut remaining = 10;
        assert_eq!(
            super::find_prepared_template_controlled(
                image(&frame, 128, 128),
                &prepared,
                opts,
                &mut remaining,
                &mut || false
            ),
            Err(super::VisionError::ComparisonBudgetExceeded)
        );
        assert_eq!(remaining, 0);
    }

    #[test]
    #[ignore = "explicit synthetic performance audit; not a device benchmark"]
    fn benchmark_texture_search_hot_loop() {
        use std::time::Instant;
        let frame: Vec<u8> = [240, 240, 240, 255].repeat(720 * 1280);
        let template: Vec<u8> = (0..16 * 16)
            .flat_map(|index| {
                if (index / 16 + index % 16) % 2 == 0 {
                    [240, 240, 240, 255]
                } else {
                    [10, 10, 10, 255]
                }
            })
            .collect();
        let prepared = PreparedTemplate::prepare(image(&template, 16, 16)).unwrap();
        assert!(prepared.majority_opaque.is_none());
        let opts = TemplateOptions {
            search: SearchOptions {
                max_pixel_comparisons: 1_000_000_000,
                ..options(720, 1280)
            },
            tolerance: ColorTolerance::EXACT,
            minimum_match_permille: 900,
            ignore_transparent_template_pixels: true,
        };
        let mut times = Vec::new();
        for _ in 0..100 {
            let start = Instant::now();
            assert_eq!(
                super::find_prepared_template(image(&frame, 720, 1280), &prepared, opts).unwrap(),
                None
            );
            times.push(start.elapsed().as_secs_f64() * 1000.0);
        }
        times.sort_by(f64::total_cmp);
        println!("texture: host={}, frame/ROI=720x1280, template=16x16 checkerboard, tolerance=0, similarity=900, order=top-left/step1, status=not_found, N=100, P50={:.3}ms P95={:.3}ms P99={:.3}ms", std::env::consts::ARCH, times[49], times[94], times[98]);
    }

    #[test]
    #[ignore = "explicit synthetic performance audit; not a device benchmark"]
    fn benchmark_majority_prefilter_same_semantics() {
        use std::time::Instant;
        let frame: Vec<u8> = [1, 2, 3, 255].repeat(720 * 1280);
        let template: Vec<u8> = [100, 120, 140, 255].repeat(32 * 24);
        let prepared = PreparedTemplate::prepare(image(&template, 32, 24)).unwrap();
        let mut baseline = prepared.clone();
        baseline.majority_all = None;
        baseline.majority_opaque = None;
        let opts = TemplateOptions {
            search: SearchOptions {
                max_pixel_comparisons: 1_000_000_000,
                ..options(720, 1280)
            },
            tolerance: ColorTolerance::EXACT,
            minimum_match_permille: 900,
            ignore_transparent_template_pixels: true,
        };
        for (name, template) in [("previous", &baseline), ("integral", &prepared)] {
            let mut times = Vec::new();
            for _ in 0..100 {
                let start = Instant::now();
                assert_eq!(
                    super::find_prepared_template(image(&frame, 720, 1280), template, opts)
                        .unwrap(),
                    None
                );
                times.push(start.elapsed().as_secs_f64() * 1000.0);
            }
            times.sort_by(f64::total_cmp);
            println!("{name}: host={}, frame/ROI=720x1280, template=32x24, tolerance=0, similarity=900, status=not_found, N=100, P50={:.3}ms P95={:.3}ms P99={:.3}ms", std::env::consts::ARCH, times[49], times[94], times[98]);
        }
    }
}
