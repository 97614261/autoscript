//! Explicit bounded scale enumeration over the unchanged pixel matcher.
use crate::{
    ImageView, PixelFormat, PreparedTemplate, TemplateMatch, TemplateOptions, VisionError,
};
use serde::Deserialize;

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ScaleRange {
    pub min_permille: u32,
    pub max_permille: u32,
    pub step_permille: u32,
}

impl ScaleRange {
    /// Enumerate nearest to 1000 first, then smaller scale on a tie. No hidden scale fitting.
    /// # Errors
    /// Requires an exact inclusive range of at most 16 scales between 250 and 4000 permille.
    pub fn values(self) -> Result<Vec<u32>, VisionError> {
        if !(250..=4000).contains(&self.min_permille)
            || !(self.min_permille..=4000).contains(&self.max_permille)
            || !(1..=1000).contains(&self.step_permille)
        {
            return Err(VisionError::InvalidScaleRange);
        }
        let span = self.max_permille - self.min_permille;
        if !span.is_multiple_of(self.step_permille) || span / self.step_permille >= 16 {
            return Err(VisionError::InvalidScaleRange);
        }
        let mut values = (0..=span / self.step_permille)
            .map(|i| self.min_permille + i * self.step_permille)
            .collect::<Vec<_>>();
        values.sort_by_key(|scale| (scale.abs_diff(1000), *scale));
        Ok(values)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScaledMatch {
    pub matched: TemplateMatch,
    pub scale_permille: u32,
    pub width: u32,
    pub height: u32,
}

/// Returns the first accepted match in documented scale/traversal order, not a global best score.
/// # Errors
/// Rejects malformed geometry/options, >256K pixels per scaled template, >4M resampled pixels,
/// exhausted aggregate comparisons and cooperative cancellation.
pub fn find_scaled(
    image: ImageView<'_>,
    template: ImageView<'_>,
    range: ScaleRange,
    options: TemplateOptions,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Option<ScaledMatch>, VisionError> {
    crate::validate_image(template)?;
    crate::validate_search(image, options.search)?;
    if template.width > 4096
        || template.height > 4096
        || u64::from(template.width) * u64::from(template.height) > 4_194_304
        || image.width > 4096
        || image.height > 4096
        || u64::from(image.width) * u64::from(image.height) > 4_194_304
        || options.minimum_match_permille > 1000
    {
        return Err(VisionError::InvalidImage);
    }
    let mut sizes = Vec::new();
    let mut total = 0_u64;
    for scale in range.values()? {
        let w = template.width * scale / 1000;
        let h = template.height * scale / 1000;
        if w == 0 || h == 0 || w > 4096 || h > 4096 || u64::from(w) * u64::from(h) > 262_144 {
            return Err(VisionError::InvalidScaleRange);
        }
        if sizes
            .iter()
            .any(|&(_, width, height)| width == w && height == h)
        {
            continue;
        }
        total += u64::from(w) * u64::from(h);
        if total > 4_194_304 {
            return Err(VisionError::ComparisonBudgetExceeded);
        }
        sizes.push((scale, w, h));
    }
    let mut remaining = options.search.max_pixel_comparisons.min(100_000_000);
    for (scale, w, h) in sizes {
        if cancelled() {
            return Err(VisionError::Cancelled);
        }
        if w > options.search.roi.width() || h > options.search.roi.height() {
            continue;
        }
        let mut pixels = Vec::with_capacity(w as usize * h as usize * 4);
        for y in 0..h {
            for x in 0..w {
                if x.trailing_zeros() >= 8 && cancelled() {
                    return Err(VisionError::Cancelled);
                }
                let pixel =
                    crate::read_pixel(template, x * template.width / w, y * template.height / h);
                pixels.extend_from_slice(&[pixel.red, pixel.green, pixel.blue, pixel.alpha]);
            }
        }
        let prepared = PreparedTemplate::prepare(ImageView {
            width: w,
            height: h,
            row_stride: w as usize * 4,
            format: PixelFormat::Rgba8888,
            pixels: &pixels,
        })?;
        if cancelled() {
            return Err(VisionError::Cancelled);
        }
        if let Some(matched) = crate::find_prepared_template_controlled(
            image,
            &prepared,
            options,
            &mut remaining,
            cancelled,
        )? {
            if cancelled() {
                return Err(VisionError::Cancelled);
            }
            return Ok(Some(ScaledMatch {
                matched,
                scale_permille: scale,
                width: w,
                height: h,
            }));
        }
    }
    if cancelled() {
        return Err(VisionError::Cancelled);
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{ColorTolerance, PixelRect, SearchOptions, SearchOrder};
    fn image(w: u32, h: u32, pixels: &[u8]) -> ImageView<'_> {
        ImageView {
            width: w,
            height: h,
            row_stride: w as usize * 4,
            format: PixelFormat::Rgba8888,
            pixels,
        }
    }
    #[test]
    fn explicit_scaled_sampling_preserves_transparency_direction_and_budget() {
        let template = [
            255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 99, 99, 99, 0,
        ];
        let mut pixels = vec![0; 4 * 4 * 4];
        for y in 0..4 {
            for x in 0..4 {
                pixels[(y * 4 + x) * 4..(y * 4 + x + 1) * 4].copy_from_slice(
                    &template[(y / 2 * 2 + x / 2) * 4..(y / 2 * 2 + x / 2 + 1) * 4],
                );
            }
        }
        let range = ScaleRange {
            min_permille: 2000,
            max_permille: 2000,
            step_permille: 100,
        };
        let options = TemplateOptions {
            search: SearchOptions {
                roi: PixelRect {
                    left: 0,
                    top: 0,
                    right: 4,
                    bottom: 4,
                },
                step_x: 1,
                step_y: 1,
                order: SearchOrder::TopLeftToBottomRight,
                max_pixel_comparisons: 1000,
            },
            tolerance: ColorTolerance::EXACT,
            minimum_match_permille: 1000,
            ignore_transparent_template_pixels: true,
        };
        let found = find_scaled(
            image(4, 4, &pixels),
            image(2, 2, &template),
            range,
            options,
            &mut || false,
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            (
                found.scale_permille,
                found.width,
                found.height,
                found.matched.matched_permille
            ),
            (2000, 4, 4, 1000)
        );
        assert_eq!(
            find_scaled(
                image(4, 4, &pixels),
                image(2, 2, &template),
                range,
                options,
                &mut || true
            ),
            Err(VisionError::Cancelled)
        );
        assert!(ScaleRange {
            min_permille: 250,
            max_permille: 4000,
            step_permille: 1
        }
        .values()
        .is_err());
        assert_eq!(
            ScaleRange {
                min_permille: 750,
                max_permille: 1250,
                step_permille: 250
            }
            .values()
            .unwrap(),
            vec![1000, 750, 1250]
        );
        let mut tiny = options;
        tiny.search.max_pixel_comparisons = 0;
        assert!(find_scaled(
            image(4, 4, &pixels),
            image(2, 2, &template),
            range,
            tiny,
            &mut || false
        )
        .is_err());
    }
}
