//! Fixed, cancellable operations over immutable, task-owned frames.
use automation_core::{FrameFormat, FrameView};
use serde::Deserialize;
use serde_json::{json, Value};

const MAX_PIXELS: u64 = 4 * 1024 * 1024;
const MAX_RESULTS: usize = 256;

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub(crate) struct ScaledSearch {
    pub region: Region,
    pub tolerance: u8,
    pub similarity_permille: u16,
    pub direction: u8,
    pub range: pixel_vision::scaled_search::ScaleRange,
}
pub(crate) fn scaled_search(
    current: &FrameView,
    template: &FrameView,
    spec: &ScaledSearch,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Value, String> {
    spec.region.validate(current)?;
    let order = match spec.direction {
        0 => pixel_vision::SearchOrder::TopLeftToBottomRight,
        1 => pixel_vision::SearchOrder::TopRightToBottomLeft,
        2 => pixel_vision::SearchOrder::BottomLeftToTopRight,
        3 => pixel_vision::SearchOrder::BottomRightToTopLeft,
        4 => pixel_vision::SearchOrder::CenterOut,
        _ => return Err("invalid scaled direction".into()),
    };
    let options = pixel_vision::TemplateOptions {
        search: pixel_vision::SearchOptions {
            roi: pixel_vision::PixelRect {
                left: spec.region.left,
                top: spec.region.top,
                right: spec.region.right,
                bottom: spec.region.bottom,
            },
            step_x: 1,
            step_y: 1,
            order,
            max_pixel_comparisons: 100_000_000,
        },
        tolerance: pixel_vision::ColorTolerance {
            red: spec.tolerance,
            green: spec.tolerance,
            blue: spec.tolerance,
            alpha: 255,
        },
        minimum_match_permille: spec.similarity_permille,
        ignore_transparent_template_pixels: true,
    };
    let found = pixel_vision::scaled_search::find_scaled(
        current.image_view().map_err(|_| "invalid source frame")?,
        template
            .image_view()
            .map_err(|_| "invalid template frame")?,
        spec.range,
        options,
        cancelled,
    )
    .map_err(|error| format!("SCALED_SEARCH_{error:?}"))?;
    Ok(json!(found.map(|v|json!({"x":v.matched.origin.x,"y":v.matched.origin.y,"width":v.width,"height":v.height,
        "scorePermille":v.matched.matched_permille,"scalePermille":v.scale_permille})).into_iter().collect::<Vec<_>>()))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct FrameComparison {
    pub region: Region,
    pub tolerance: u8,
}

pub(crate) fn compare_frames(
    current: &FrameView,
    previous: &FrameView,
    spec: &FrameComparison,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Value, String> {
    let start = std::time::Instant::now();
    if current.metadata.width != previous.metadata.width
        || current.metadata.height != previous.metadata.height
        || current.metadata.rotation != previous.metadata.rotation
    {
        return Err("COMPARE_GEOMETRY_MISMATCH".into());
    }
    let total = spec.region.validate(current)?;
    spec.region.validate(previous)?;
    let mut changed = 0_u64;
    for y in spec.region.top..spec.region.bottom {
        if cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        for x in spec.region.left..spec.region.right {
            if x.trailing_zeros() >= 8 && cancelled() {
                return Err("VISION_CANCELLED".into());
            }
            let a = rgba(current, x, y);
            let b = rgba(previous, x, y);
            if (a[3] == 0) != (b[3] == 0)
                || a[3] != 0 && b[3] != 0 && (0..3).any(|i| a[i].abs_diff(b[i]) > spec.tolerance)
            {
                changed += 1;
            }
        }
    }
    if cancelled() {
        return Err("VISION_CANCELLED".into());
    }
    let fresh = current
        .origin
        .as_ref()
        .zip(previous.origin.as_ref())
        .is_some_and(|(a, b)| a.is_fresher_than(b));
    Ok(
        json!({"changedPixels":changed,"totalPixels":total,"ratioPermille":changed*1000/total as u64,
        "fresh":u8::from(fresh),"elapsedUs":u64::try_from(start.elapsed().as_micros()).unwrap_or(u64::MAX)}),
    )
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Transform {
    pub mode: String,
    pub width: u32,
    pub height: u32,
    #[serde(default = "default_threshold")]
    pub threshold: u8,
    #[serde(default)]
    pub filter: Option<pixel_vision::color_filter::Filter>,
    #[serde(default)]
    pub mask: Option<pixel_vision::image_processing::Mask>,
    #[serde(default)]
    pub cleanup: Option<pixel_vision::image_processing::Cleanup>,
}

const fn default_threshold() -> u8 {
    128
}

impl Transform {
    pub fn dimensions(&self, frame: &FrameView) -> Result<(u32, u32), String> {
        let (w, h) = (frame.metadata.width, frame.metadata.height);
        let size = match self.mode.as_str() {
            "rotate90" | "rotate270" => (h, w),
            "rotate180" | "flipX" | "flipY" | "gray" | "threshold" | "filter" | "mask"
            | "erode" | "dilate" | "open" | "close" | "despeckle" => (w, h),
            "resize" => (self.width, self.height),
            _ => return Err("unknown transform mode".into()),
        };
        if self.mode != "resize" && (self.width != 0 || self.height != 0) {
            return Err("non-resize transforms require zero dimensions".into());
        }
        if size.0 == 0 || size.1 == 0 || u64::from(size.0) * u64::from(size.1) > MAX_PIXELS {
            return Err("transform exceeds pixel budget".into());
        }
        match (self.mode.as_str(), &self.filter) {
            ("filter", Some(filter)) => filter
                .validate(u64::from(w) * u64::from(h))
                .map_err(str::to_owned)?,
            ("filter", None) => return Err("filter rules required".into()),
            (_, Some(_)) => return Err("filter rules require filter mode".into()),
            (_, None) => {}
        }
        if self.mode == "mask" {
            self.mask
                .as_ref()
                .ok_or("mask rules required")?
                .validate(w, h)
                .map_err(str::to_owned)?;
        } else if self.mask.is_some() {
            return Err("mask rules require mask mode".into());
        }
        if matches!(
            self.mode.as_str(),
            "erode" | "dilate" | "open" | "close" | "despeckle"
        ) {
            self.cleanup
                .as_ref()
                .ok_or("cleanup settings required")?
                .validate(&self.mode, w, h)
                .map_err(str::to_owned)?;
        } else if self.cleanup.is_some() {
            return Err("cleanup rules require cleanup mode".into());
        }
        Ok(size)
    }
}

pub(crate) fn rgba(frame: &FrameView, x: u32, y: u32) -> [u8; 4] {
    let offset = y as usize * frame.metadata.row_stride as usize + x as usize * 4;
    let mut pixel: [u8; 4] = frame.pixels[offset..offset + 4]
        .try_into()
        .expect("validated frame");
    if frame.metadata.format == FrameFormat::Bgra8888 {
        pixel.swap(0, 2);
    }
    pixel
}

pub(crate) fn transform(
    frame: &FrameView,
    spec: &Transform,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<(u32, u32, Vec<u8>), String> {
    let (w, h) = spec.dimensions(frame)?;
    if spec.mask.is_some() || spec.cleanup.is_some() {
        let mut pixels = Vec::with_capacity(w as usize * h as usize * 4);
        for y in 0..h {
            for x in 0..w {
                if x.trailing_zeros() >= 8 && cancelled() {
                    return Err("VISION_CANCELLED".into());
                }
                pixels.extend_from_slice(&rgba(frame, x, y));
            }
        }
        let result = if let Some(mask) = &spec.mask {
            pixel_vision::image_processing::mask(&pixels, w, h, mask, cancelled)
        } else {
            pixel_vision::image_processing::cleanup(
                &pixels,
                w,
                h,
                &spec.mode,
                spec.cleanup.as_ref().expect("validated cleanup"),
                cancelled,
            )
        }
        .map_err(str::to_owned)?;
        return Ok((w, h, result));
    }
    let (sw, sh) = (frame.metadata.width, frame.metadata.height);
    let filter = spec
        .filter
        .as_ref()
        .map(pixel_vision::color_filter::Filter::prepare);
    let mut output = Vec::with_capacity(w as usize * h as usize * 4);
    for y in 0..h {
        if cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        for x in 0..w {
            if filter.is_some() && x & 255 == 255 && cancelled() {
                return Err("VISION_CANCELLED".into());
            }
            let (sx, sy) = match spec.mode.as_str() {
                "rotate90" => (y, sh - 1 - x),
                "rotate180" => (sw - 1 - x, sh - 1 - y),
                "rotate270" => (sw - 1 - y, x),
                "flipX" => (sw - 1 - x, y),
                "flipY" => (x, sh - 1 - y),
                "resize" => (
                    u32::try_from(u64::from(x) * u64::from(sw) / u64::from(w))
                        .expect("source coordinate"),
                    u32::try_from(u64::from(y) * u64::from(sh) / u64::from(h))
                        .expect("source coordinate"),
                ),
                _ => (x, y),
            };
            let mut pixel = rgba(frame, sx, sy);
            if let Some(filter) = &filter {
                pixel = filter.pixel(pixel);
            }
            if matches!(spec.mode.as_str(), "gray" | "threshold") {
                let gray = u8::try_from(
                    (u32::from(pixel[0]) * 77
                        + u32::from(pixel[1]) * 150
                        + u32::from(pixel[2]) * 29
                        + 128)
                        >> 8,
                )
                .expect("weighted color");
                let value = if spec.mode == "threshold" {
                    if gray >= spec.threshold {
                        255
                    } else {
                        0
                    }
                } else {
                    gray
                };
                pixel[..3].fill(value);
            }
            output.extend_from_slice(&pixel);
        }
    }
    if cancelled() {
        return Err("VISION_CANCELLED".into());
    }
    Ok((w, h, output))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Region {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}
impl Region {
    fn validate(&self, frame: &FrameView) -> Result<usize, String> {
        if self.left >= self.right
            || self.top >= self.bottom
            || self.right > frame.metadata.width
            || self.bottom > frame.metadata.height
        {
            return Err("invalid region".into());
        }
        let n = u64::from(self.right - self.left) * u64::from(self.bottom - self.top);
        if n > MAX_PIXELS {
            return Err("ROI exceeds pixel budget".into());
        }
        Ok(usize::try_from(n).expect("bounded pixels"))
    }
}
fn matches(frame: &FrameView, x: u32, y: u32, rgb: u32, tolerance: u8) -> bool {
    let p = rgba(frame, x, y);
    p[0].abs_diff(rgb.to_be_bytes()[1]) <= tolerance
        && p[1].abs_diff(rgb.to_be_bytes()[2]) <= tolerance
        && p[2].abs_diff(rgb.to_be_bytes()[3]) <= tolerance
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Components {
    pub region: Region,
    pub rgb: u32,
    pub tolerance: u8,
    pub connectivity: u8,
    pub min_area: u32,
    pub limit: usize,
}
pub(crate) fn components(
    frame: &FrameView,
    spec: &Components,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Value, String> {
    let pixel_count = spec.region.validate(frame)?;
    if spec.rgb > 0x00ff_ffff
        || !matches!(spec.connectivity, 4 | 8)
        || spec.min_area == 0
        || spec.limit == 0
        || spec.limit > MAX_RESULTS
    {
        return Err("invalid component options".into());
    }
    let region = &spec.region;
    let width = (region.right - region.left) as usize;
    let mut mask = vec![0_u8; pixel_count];
    for (index, item) in mask.iter_mut().enumerate() {
        if index % 1024 == 0 && cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        *item = u8::from(matches(
            frame,
            region.left + u32::try_from(index % width).expect("bounded pixel coordinate"),
            region.top + u32::try_from(index / width).expect("bounded pixel coordinate"),
            spec.rgb,
            spec.tolerance,
        ));
    }
    let mut result = Vec::new();
    let mut queue = Vec::new();
    for start in 0..pixel_count {
        if start % 1024 == 0 && cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        if mask[start] != 1 {
            continue;
        }
        queue.clear();
        queue.push(start);
        mask[start] = 2;
        let (mut left, mut top, mut right, mut bottom) = (width, pixel_count / width, 0, 0);
        let mut cursor = 0;
        while cursor < queue.len() {
            if cursor % 1024 == 0 && cancelled() {
                return Err("VISION_CANCELLED".into());
            }
            let index = queue[cursor];
            cursor += 1;
            let (x, y) = (index % width, index / width);
            left = left.min(x);
            top = top.min(y);
            right = right.max(x + 1);
            bottom = bottom.max(y + 1);
            for dy in -1_i64..=1 {
                for dx in -1_i64..=1 {
                    if (dx == 0 && dy == 0) || (spec.connectivity == 4 && dx != 0 && dy != 0) {
                        continue;
                    }
                    let (nx, ny) = (
                        i64::try_from(x).expect("bounded region") + dx,
                        i64::try_from(y).expect("bounded region") + dy,
                    );
                    if nx < 0
                        || ny < 0
                        || nx >= i64::try_from(width).expect("bounded region")
                        || ny >= i64::try_from(pixel_count / width).expect("bounded region")
                    {
                        continue;
                    }
                    let neighbor = usize::try_from(ny).expect("nonnegative neighbor") * width
                        + usize::try_from(nx).expect("nonnegative neighbor");
                    if mask[neighbor] == 1 {
                        mask[neighbor] = 2;
                        queue.push(neighbor);
                    }
                }
            }
        }
        if queue.len() >= spec.min_area as usize {
            result.push(json!({"area":queue.len(),"left":region.left+u32::try_from(left).expect("bounded pixel coordinate"),"top":region.top+u32::try_from(top).expect("bounded pixel coordinate"),"right":region.left+u32::try_from(right).expect("bounded pixel coordinate"),"bottom":region.top+u32::try_from(bottom).expect("bounded pixel coordinate")}));
            if result.len() == spec.limit {
                break;
            }
        }
    }
    Ok(json!(result))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Sample {
    pub dx: i32,
    pub dy: i32,
    pub rgb: u32,
    pub tolerance: u8,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct MultiColor {
    pub region: Region,
    pub samples: Vec<Sample>,
    pub limit: usize,
}
pub(crate) fn multi_color(
    frame: &FrameView,
    spec: &MultiColor,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Value, String> {
    let n = spec.region.validate(frame)?;
    if spec.samples.is_empty()
        || spec.samples.len() > 64
        || spec.limit == 0
        || spec.limit > MAX_RESULTS
        || spec.samples.iter().any(|s| {
            s.rgb > 0x00ff_ffff || s.dx.unsigned_abs() > 32768 || s.dy.unsigned_abs() > 32768
        })
        || n as u64 * spec.samples.len() as u64 > 100_000_000
    {
        return Err("invalid pattern or comparison budget".into());
    }
    let mut result = Vec::new();
    for y in spec.region.top..spec.region.bottom {
        if cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        for x in spec.region.left..spec.region.right {
            let found = spec.samples.iter().all(|s| {
                let (px, py) = (
                    i64::from(x) + i64::from(s.dx),
                    i64::from(y) + i64::from(s.dy),
                );
                px >= i64::from(spec.region.left)
                    && py >= i64::from(spec.region.top)
                    && px < i64::from(spec.region.right)
                    && py < i64::from(spec.region.bottom)
                    && matches(
                        frame,
                        u32::try_from(px).expect("bounded pixel coordinate"),
                        u32::try_from(py).expect("bounded pixel coordinate"),
                        s.rgb,
                        s.tolerance,
                    )
            });
            if found {
                result.push(json!({"x":x,"y":y}));
                if result.len() == spec.limit {
                    return Ok(json!(result));
                }
            }
        }
    }
    Ok(json!(result))
}
