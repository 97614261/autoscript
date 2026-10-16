//! Fixed, cancellable operations over immutable, task-owned frames.
use automation_core::{FrameFormat, FrameView};
use serde::Deserialize;
use serde_json::{json, Value};

const MAX_PIXELS: u64 = 4 * 1024 * 1024;
const MAX_RESULTS: usize = 256;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Transform {
    pub mode: String,
    pub width: u32,
    pub height: u32,
    #[serde(default = "default_threshold")]
    pub threshold: u8,
}

const fn default_threshold() -> u8 {
    128
}

impl Transform {
    pub fn dimensions(&self, frame: &FrameView) -> Result<(u32, u32), String> {
        let (w, h) = (frame.metadata.width, frame.metadata.height);
        let size = match self.mode.as_str() {
            "rotate90" | "rotate270" => (h, w),
            "rotate180" | "flipX" | "flipY" | "gray" | "threshold" => (w, h),
            "resize" => (self.width, self.height),
            _ => return Err("unknown transform mode".into()),
        };
        if self.mode != "resize" && (self.width != 0 || self.height != 0) {
            return Err("non-resize transforms require zero dimensions".into());
        }
        if size.0 == 0 || size.1 == 0 || u64::from(size.0) * u64::from(size.1) > MAX_PIXELS {
            return Err("transform exceeds pixel budget".into());
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
    let (sw, sh) = (frame.metadata.width, frame.metadata.height);
    let mut output = Vec::with_capacity(w as usize * h as usize * 4);
    for y in 0..h {
        if cancelled() {
            return Err("VISION_CANCELLED".into());
        }
        for x in 0..w {
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
