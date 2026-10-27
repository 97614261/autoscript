//! Bounded integer masks and binary cleanup shared by frame transforms.
use serde::{Deserialize, Serialize};

pub const MAX_PIXELS: u64 = 4_194_304;
pub const MAX_WORK: u64 = 64_000_000;
pub const MAX_MASK_BYTES: usize = 15_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Stroke {
    pub kind: String,
    pub keep: bool,
    pub radius: u32,
    pub points: Vec<[u32; 2]>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Mask {
    pub version: u8,
    pub width: u32,
    pub height: u32,
    pub strokes: Vec<Stroke>,
}

impl Mask {
    /// # Errors
    /// Rejects malformed data, geometry or work exceeding fixed limits.
    pub fn parse(text: &str) -> Result<Self, &'static str> {
        if text.len() > MAX_MASK_BYTES {
            return Err("mask byte budget exceeded");
        }
        let mask: Self = serde_json::from_str(text).map_err(|_| "invalid mask JSON")?;
        mask.validate(mask.width, mask.height)?;
        Ok(mask)
    }

    /// # Errors
    /// A mask may only be used at its exact authored pixel dimensions.
    pub fn validate(&self, width: u32, height: u32) -> Result<(), &'static str> {
        dimensions(width, height)?;
        if self.version != 1
            || (width, height) != (self.width, self.height)
            || self.strokes.is_empty()
            || self.strokes.len() > 64
        {
            return Err("invalid mask version, geometry or strokes");
        }
        let mut points = 0;
        let mut work = 0_u64;
        for stroke in &self.strokes {
            points += stroke.points.len();
            if points > 1024 {
                return Err("mask point budget exceeded");
            }
            match stroke.kind.as_str() {
                "rect" => {
                    if stroke.radius != 0 || stroke.points.len() != 2 {
                        return Err("invalid mask rectangle");
                    }
                    let [a, b] = [stroke.points[0], stroke.points[1]];
                    if a[0] >= b[0] || a[1] >= b[1] || b[0] > width || b[1] > height {
                        return Err("mask rectangle outside image");
                    }
                    work += u64::from(b[0] - a[0]) * u64::from(b[1] - a[1]);
                }
                "brush" => {
                    if stroke.points.is_empty()
                        || !(1..=64).contains(&stroke.radius)
                        || stroke
                            .points
                            .iter()
                            .any(|p| p[0] >= width || p[1] >= height)
                    {
                        return Err("invalid mask brush");
                    }
                    let steps = 1 + stroke
                        .points
                        .windows(2)
                        .map(|p| {
                            u64::from(p[0][0].abs_diff(p[1][0]).max(p[0][1].abs_diff(p[1][1]))) + 1
                        })
                        .sum::<u64>();
                    work += steps * u64::from(stroke.radius * 2 + 1).pow(2);
                }
                "polygon" => {
                    let p = &stroke.points;
                    if stroke.radius != 0
                        || !(3..=128).contains(&p.len())
                        || p.iter().any(|v| v[0] > width || v[1] > height)
                        || p.iter().enumerate().any(|(i, v)| p[..i].contains(v))
                        || p.iter()
                            .zip(p.iter().cycle().skip(1))
                            .map(|(a, b)| {
                                i64::from(a[0]) * i64::from(b[1])
                                    - i64::from(b[0]) * i64::from(a[1])
                            })
                            .sum::<i64>()
                            == 0
                    {
                        return Err("invalid mask polygon");
                    }
                    let top = p.iter().map(|v| v[1]).min().unwrap_or(0);
                    let bottom = p.iter().map(|v| v[1]).max().unwrap_or(0);
                    work +=
                        u64::from(bottom - top) * (u64::from(width) + (p.len() * p.len()) as u64);
                }
                _ => return Err("unknown mask stroke"),
            }
            if work > MAX_WORK {
                return Err("mask raster work budget exceeded");
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Cleanup {
    pub version: u8,
    pub foreground: String,
    pub threshold: u8,
    pub radius: u32,
    pub min_area: u32,
    pub connectivity: u8,
}
impl Cleanup {
    /// # Errors
    /// Rejects unknown settings and excessive pixel-neighborhood work.
    pub fn validate(&self, mode: &str, width: u32, height: u32) -> Result<(), &'static str> {
        let pixels = dimensions(width, height)?;
        if self.version != 1
            || !matches!(self.foreground.as_str(), "light" | "dark")
            || !(1..=2).contains(&self.radius)
            || !(1..=4096).contains(&self.min_area)
            || !matches!(self.connectivity, 4 | 8)
        {
            return Err("invalid cleanup settings");
        }
        let passes = if matches!(mode, "open" | "close") {
            2
        } else {
            1
        };
        let work = if mode == "despeckle" {
            8
        } else {
            u64::from(self.radius * 2 + 1).pow(2) * passes
        };
        if !matches!(mode, "erode" | "dilate" | "open" | "close" | "despeckle")
            || pixels * work > MAX_WORK
        {
            return Err("cleanup work budget exceeded");
        }
        Ok(())
    }
    /// # Errors
    /// Rejects malformed or unsupported settings.
    pub fn parse(text: &str) -> Result<Self, &'static str> {
        if text.len() > 1024 {
            return Err("cleanup byte budget exceeded");
        }
        let value: Self = serde_json::from_str(text).map_err(|_| "invalid cleanup JSON")?;
        value.validate("despeckle", 1, 1)?;
        Ok(value)
    }
}

fn dimensions(width: u32, height: u32) -> Result<u64, &'static str> {
    let size = u64::from(width) * u64::from(height);
    if width == 0 || height == 0 || width > 4096 || height > 4096 || size > MAX_PIXELS {
        Err("image processing pixel budget exceeded")
    } else {
        Ok(size)
    }
}
fn checkpoint(cancelled: &mut dyn FnMut() -> bool) -> Result<(), &'static str> {
    if cancelled() {
        Err("VISION_CANCELLED")
    } else {
        Ok(())
    }
}

// Pixel-centre intersections use integer rationals, matching the Kotlin preview exactly.
fn polygon_spans(points: &[[u32; 2]], y: u32, width: u32) -> Vec<std::ops::Range<u32>> {
    let mut crossings = Vec::with_capacity(points.len());
    for (first, second) in points.iter().zip(points.iter().cycle().skip(1)) {
        let (a, b) = if first[1] < second[1] {
            (first, second)
        } else {
            (second, first)
        };
        let cy = 2 * i64::from(y) + 1;
        if cy >= 2 * i64::from(a[1]) && cy < 2 * i64::from(b[1]) {
            let dy = i64::from(b[1] - a[1]);
            let n = 2 * i64::from(a[0]) * dy
                + (cy - 2 * i64::from(a[1])) * (i64::from(b[0]) - i64::from(a[0]))
                - dy;
            crossings.push((n, 2 * dy));
        }
    }
    crossings.sort_unstable_by(|a, b| (a.0 * b.1).cmp(&(b.0 * a.1)));
    let ceil = |(n, d): (i64, i64)| -> u32 {
        u32::try_from((-(-n).div_euclid(d)).clamp(0, i64::from(width)))
            .expect("clamped image coordinate")
    };
    crossings
        .chunks_exact(2)
        .map(|pair| ceil(pair[0])..ceil(pair[1]))
        .collect()
}

/// # Errors
/// Rejects invalid input, excessive work, cancellation and fully masked images.
pub fn mask(
    rgba: &[u8],
    width: u32,
    height: u32,
    spec: &Mask,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Vec<u8>, &'static str> {
    spec.validate(width, height)?;
    let count = usize::try_from(dimensions(width, height)?).map_err(|_| "image too large")?;
    if rgba.len() != count * 4 {
        return Err("pixel length mismatch");
    }
    let mut kept = vec![1_u8; count];
    for stroke in &spec.strokes {
        checkpoint(cancelled)?;
        if stroke.kind == "rect" {
            for y in stroke.points[0][1]..stroke.points[1][1] {
                checkpoint(cancelled)?;
                for x in stroke.points[0][0]..stroke.points[1][0] {
                    kept[(y * width + x) as usize] = u8::from(stroke.keep);
                }
            }
        } else if stroke.kind == "polygon" {
            let top = stroke.points.iter().map(|p| p[1]).min().unwrap_or(0);
            let bottom = stroke.points.iter().map(|p| p[1]).max().unwrap_or(0);
            for y in top..bottom {
                checkpoint(cancelled)?;
                for span in polygon_spans(&stroke.points, y, width) {
                    for x in span {
                        kept[(y * width + x) as usize] = u8::from(stroke.keep);
                    }
                }
            }
        } else {
            let mut dab = |x: i64, y: i64| -> Result<(), &'static str> {
                checkpoint(cancelled)?;
                let radius = i64::from(stroke.radius);
                for py in (y - radius).max(0)..=(y + radius).min(i64::from(height) - 1) {
                    for px in (x - radius).max(0)..=(x + radius).min(i64::from(width) - 1) {
                        if (px - x).pow(2) + (py - y).pow(2) <= radius.pow(2) {
                            let index = usize::try_from(py * i64::from(width) + px)
                                .map_err(|_| "invalid mask pixel")?;
                            kept[index] = u8::from(stroke.keep);
                        }
                    }
                }
                Ok(())
            };
            let first = stroke.points[0];
            dab(i64::from(first[0]), i64::from(first[1]))?;
            for pair in stroke.points.windows(2) {
                let (mut x, mut y) = (i64::from(pair[0][0]), i64::from(pair[0][1]));
                let (tx, ty) = (i64::from(pair[1][0]), i64::from(pair[1][1]));
                let dx = (tx - x).abs();
                let dy = -(ty - y).abs();
                let mut error = dx + dy;
                let sx = if x < tx { 1 } else { -1 };
                let sy = if y < ty { 1 } else { -1 };
                loop {
                    dab(x, y)?;
                    if x == tx && y == ty {
                        break;
                    }
                    let twice = 2 * error;
                    if twice >= dy {
                        error += dy;
                        x += sx;
                    }
                    if twice <= dx {
                        error += dx;
                        y += sy;
                    }
                }
            }
        }
    }
    let mut output = rgba.to_vec();
    let mut visible = false;
    for (index, pixel) in output.chunks_exact_mut(4).enumerate() {
        if index.trailing_zeros() >= 8 {
            checkpoint(cancelled)?;
        }
        if kept[index] == 0 {
            pixel.fill(0);
        } else if pixel[3] != 0 {
            visible = true;
        }
    }
    if !visible {
        return Err("mask removes every visible pixel");
    }
    Ok(output)
}

fn morphology(
    bits: &[u8],
    width: usize,
    height: usize,
    radius: usize,
    dilate: bool,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Vec<u8>, &'static str> {
    let mut output = vec![0; bits.len()];
    for y in 0..height {
        checkpoint(cancelled)?;
        for x in 0..width {
            let mut hit = !dilate;
            for dy in 0..=radius * 2 {
                for dx in 0..=radius * 2 {
                    let px = x + dx;
                    let py = y + dy;
                    let inside =
                        px >= radius && py >= radius && px - radius < width && py - radius < height;
                    let value = inside && bits[(py - radius) * width + px - radius] != 0;
                    if dilate {
                        hit |= value;
                    } else {
                        hit &= value;
                    }
                }
            }
            output[y * width + x] = u8::from(hit);
        }
    }
    Ok(output)
}
fn remove_components(
    bits: &mut [u8],
    width: usize,
    height: usize,
    spec: &Cleanup,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<(), &'static str> {
    let mut queue: Vec<u32> = Vec::new();
    for start in 0..bits.len() {
        if start.trailing_zeros() >= 8 {
            checkpoint(cancelled)?;
        }
        if bits[start] != 1 {
            continue;
        }
        queue.clear();
        queue.push(u32::try_from(start).expect("bounded pixel index"));
        bits[start] = 2;
        let mut read = 0;
        while read < queue.len() {
            if read.trailing_zeros() >= 8 {
                checkpoint(cancelled)?;
            }
            let index = queue[read] as usize;
            read += 1;
            let x = index % width;
            let y = index / width;
            for dy in 0_usize..3 {
                for dx in 0_usize..3 {
                    if dx == 1 && dy == 1 || spec.connectivity == 4 && dx != 1 && dy != 1 {
                        continue;
                    }
                    let px = x + dx;
                    let py = y + dy;
                    if px == 0 || py == 0 || px > width || py > height {
                        continue;
                    }
                    let other = (py - 1) * width + px - 1;
                    if bits[other] == 1 {
                        bits[other] = 2;
                        queue.push(u32::try_from(other).expect("bounded pixel index"));
                    }
                }
            }
        }
        let keep = if queue.len() >= spec.min_area as usize {
            3
        } else {
            0
        };
        for &index in &queue {
            bits[index as usize] = keep;
        }
    }
    Ok(())
}

/// # Errors
/// Applies only explicit binary cleanup; no automatic OCR or threshold selection.
pub fn cleanup(
    rgba: &[u8],
    width: u32,
    height: u32,
    mode: &str,
    spec: &Cleanup,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Vec<u8>, &'static str> {
    spec.validate(mode, width, height)?;
    let count = (width * height) as usize;
    if rgba.len() != count * 4 {
        return Err("pixel length mismatch");
    }
    let light = spec.foreground == "light";
    let mut bits = Vec::with_capacity(count);
    for (index, pixel) in rgba.chunks_exact(4).enumerate() {
        if index.trailing_zeros() >= 8 {
            checkpoint(cancelled)?;
        }
        let gray =
            (u32::from(pixel[0]) * 77 + u32::from(pixel[1]) * 150 + u32::from(pixel[2]) * 29 + 128)
                >> 8;
        bits.push(u8::from(
            pixel[3] != 0 && ((gray >= u32::from(spec.threshold)) == light),
        ));
    }
    let (w, h, r) = (width as usize, height as usize, spec.radius as usize);
    match mode {
        "despeckle" => remove_components(&mut bits, w, h, spec, cancelled)?,
        "erode" | "dilate" => bits = morphology(&bits, w, h, r, mode == "dilate", cancelled)?,
        "open" | "close" => {
            bits = morphology(&bits, w, h, r, mode == "close", cancelled)?;
            bits = morphology(&bits, w, h, r, mode == "open", cancelled)?;
        }
        _ => return Err("unknown cleanup mode"),
    }
    let mut result = rgba.to_vec();
    for (index, pixel) in result.chunks_exact_mut(4).enumerate() {
        if index.trailing_zeros() >= 8 {
            checkpoint(cancelled)?;
        }
        if pixel[3] == 0 {
            pixel.fill(0);
        } else {
            let value = if (bits[index] != 0) == light { 255 } else { 0 };
            pixel[..3].fill(value);
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn polygon_golden_pixel_centres_winding_concavity_and_cancellation() {
        let source = [0x80_u8; 64];
        for (points, golden) in [
            (vec![[0, 0], [4, 0], [0, 4]], "1110110010000000"),
            (
                vec![[0, 0], [4, 0], [4, 1], [1, 1], [1, 4], [0, 4]],
                "1111100010001000",
            ),
        ] {
            for reverse in [false, true] {
                let mut points = points.clone();
                if reverse {
                    points.reverse();
                }
                let spec = Mask {
                    version: 1,
                    width: 4,
                    height: 4,
                    strokes: vec![
                        Stroke {
                            kind: "rect".into(),
                            keep: false,
                            radius: 0,
                            points: vec![[0, 0], [4, 4]],
                        },
                        Stroke {
                            kind: "polygon".into(),
                            keep: true,
                            radius: 0,
                            points,
                        },
                    ],
                };
                let result = mask(&source, 4, 4, &spec, &mut || false).unwrap();
                let bits: String = result
                    .chunks_exact(4)
                    .map(|p| if p[3] == 0 { '0' } else { '1' })
                    .collect();
                assert_eq!(bits, golden);
                assert!(result
                    .chunks_exact(4)
                    .all(|p| p == [0; 4] || p == [0x80; 4]));
                assert_eq!(
                    Mask::parse(&serde_json::to_string(&spec).unwrap()).unwrap(),
                    spec
                );
                let mut checks = 0;
                assert!(mask(&source, 4, 4, &spec, &mut || {
                    checks += 1;
                    checks == 3
                })
                .is_err());
            }
        }
    }

    #[test]
    fn polygon_rejects_degenerate_outside_and_excessive_raster_work() {
        let mut spec = Mask {
            version: 1,
            width: 4,
            height: 4,
            strokes: vec![Stroke {
                kind: "polygon".into(),
                keep: true,
                radius: 0,
                points: vec![[0, 0], [1, 1], [2, 2]],
            }],
        };
        assert!(spec.validate(4, 4).is_err());
        spec.strokes[0].points = vec![[0, 0], [0, 0], [2, 1]];
        assert!(spec.validate(4, 4).is_err());
        spec.strokes[0].points = vec![[0, 0], [5, 0], [0, 4]];
        assert!(spec.validate(4, 4).is_err());
        let mut points = vec![[0, 0], [4096, 0], [4096, 1024], [0, 1024]];
        points.extend((1..=124).map(|x| [x, 0]));
        spec.width = 4096;
        spec.height = 1024;
        spec.strokes = vec![
            Stroke {
                kind: "polygon".into(),
                keep: true,
                radius: 0,
                points
            };
            4
        ];
        assert!(spec.validate(4096, 1024).is_err());
    }

    #[test]
    fn mask_restores_source_alpha_and_is_bounded_and_cancellable() {
        let source = [255_u8; 36];
        let mut spec = Mask {
            version: 1,
            width: 3,
            height: 3,
            strokes: vec![Stroke {
                kind: "rect".into(),
                keep: false,
                radius: 0,
                points: vec![[0, 0], [2, 3]],
            }],
        };
        let result = mask(&source, 3, 3, &spec, &mut || false).unwrap();
        assert_eq!(&result[..8], &[0; 8]);
        assert_eq!(&result[8..12], &[255; 4]);
        spec.strokes.push(Stroke {
            kind: "brush".into(),
            keep: true,
            radius: 1,
            points: vec![[0, 1]],
        });
        assert_eq!(
            &mask(&source, 3, 3, &spec, &mut || false).unwrap()[12..16],
            &[255; 4]
        );
        assert!(mask(&source, 3, 3, &spec, &mut || true).is_err());
        assert!(spec.validate(4, 3).is_err());
        spec.strokes[0].points[1] = [3, 3];
        spec.strokes.pop();
        assert!(mask(&source, 3, 3, &spec, &mut || false).is_err());
    }
    #[test]
    fn cleanup_keeps_polarity_alpha_and_explicit_noise_thresholds() {
        let mut source = vec![0_u8; 100];
        for pixel in source.chunks_exact_mut(4) {
            pixel[3] = 255;
        }
        for index in [0, 6, 7, 11, 12] {
            source[index * 4..index * 4 + 3].fill(255);
        }
        let spec = Cleanup {
            version: 1,
            foreground: "light".into(),
            threshold: 128,
            radius: 1,
            min_area: 2,
            connectivity: 4,
        };
        let cleaned = cleanup(&source, 5, 5, "despeckle", &spec, &mut || false).unwrap();
        assert_eq!(&cleaned[..4], &[0, 0, 0, 255]);
        assert_eq!(&cleaned[24..28], &[255; 4]);
        assert!(cleanup(&source, 5, 5, "open", &spec, &mut || true).is_err());
        assert!(spec.validate("open", 4096, 1024).is_err());
        assert!(Mask::parse("{}").is_err());
        assert!(Cleanup::parse("{}").is_err());
    }
}
