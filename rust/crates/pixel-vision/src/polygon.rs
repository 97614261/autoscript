//! Pixel-centre, even-odd polygon regions shared by color and template searches.
use crate::{
    color_matches, read_pixel, validate_search, ColorSpec, ComparisonBudget, ImageView,
    PatternSample, PixelPoint, PixelRect, SearchOptions, SearchPoints, VisionError,
};

#[cfg(test)]
mod tests;

#[derive(Debug)]
pub struct PolygonRegion {
    pub bounds: PixelRect,
    rows: Vec<Vec<(u32, u32)>>,
}

impl PolygonRegion {
    /// Builds immutable scanline spans; rejects crossing/touching edges and degenerate shapes.
    pub fn prepare(points: &[[u32; 2]]) -> Result<Self, VisionError> {
        if !(3..=128).contains(&points.len()) || points.iter().flatten().any(|v| *v > 4096) {
            return Err(VisionError::InvalidRoi);
        }
        let n = points.len();
        let cross = |a: [u32; 2], b: [u32; 2], c: [u32; 2]| {
            (i64::from(b[0]) - i64::from(a[0])) * (i64::from(c[1]) - i64::from(a[1]))
                - (i64::from(b[1]) - i64::from(a[1])) * (i64::from(c[0]) - i64::from(a[0]))
        };
        let on = |a: [u32; 2], b: [u32; 2], p: [u32; 2]| {
            cross(a, b, p) == 0
                && p[0] >= a[0].min(b[0])
                && p[0] <= a[0].max(b[0])
                && p[1] >= a[1].min(b[1])
                && p[1] <= a[1].max(b[1])
        };
        let mut area = 0_i64;
        for i in 0..n {
            let (a, b) = (points[i], points[(i + 1) % n]);
            if points[..i].contains(&a) {
                return Err(VisionError::InvalidRoi);
            }
            // Adjacent edges may be straight, but must not double back over one another.
            let c = points[(i + 2) % n];
            if cross(a, b, c) == 0 && (on(a, b, c) || on(b, c, a)) {
                return Err(VisionError::InvalidRoi);
            }
            area += i64::from(a[0]) * i64::from(b[1]) - i64::from(b[0]) * i64::from(a[1]);
            for j in i + 1..n {
                if j == i + 1 || i == 0 && j == n - 1 {
                    continue;
                }
                let (c, d) = (points[j], points[(j + 1) % n]);
                let (ab_c, ab_d, cd_a, cd_b) = (
                    cross(a, b, c),
                    cross(a, b, d),
                    cross(c, d, a),
                    cross(c, d, b),
                );
                if on(a, b, c)
                    || on(a, b, d)
                    || on(c, d, a)
                    || on(c, d, b)
                    || ab_c * ab_d < 0 && cd_a * cd_b < 0
                {
                    return Err(VisionError::InvalidRoi);
                }
            }
        }
        if area == 0 {
            return Err(VisionError::InvalidRoi);
        }
        let bounds = PixelRect {
            left: points.iter().map(|p| p[0]).min().unwrap(),
            top: points.iter().map(|p| p[1]).min().unwrap(),
            right: points.iter().map(|p| p[0]).max().unwrap(),
            bottom: points.iter().map(|p| p[1]).max().unwrap(),
        };
        let mut rows = Vec::with_capacity(bounds.height() as usize);
        for y in bounds.top..bounds.bottom {
            let mut crossings = Vec::new();
            for i in 0..n {
                let (mut a, mut b) = (points[i], points[(i + 1) % n]);
                if a[1] > b[1] {
                    std::mem::swap(&mut a, &mut b);
                }
                let cy = 2 * i64::from(y) + 1;
                if cy < 2 * i64::from(a[1]) || cy >= 2 * i64::from(b[1]) {
                    continue;
                }
                let dy = i64::from(b[1] - a[1]);
                crossings.push((
                    2 * i64::from(a[0]) * dy
                        + (cy - 2 * i64::from(a[1])) * (i64::from(b[0]) - i64::from(a[0]))
                        - dy,
                    2 * dy,
                ));
            }
            crossings.sort_by(|a, b| (a.0 * b.1).cmp(&(b.0 * a.1)));
            let ceil = |(n, d): (i64, i64)| (-(-n).div_euclid(d)) as u32;
            rows.push(
                crossings
                    .chunks_exact(2)
                    .filter_map(|pair| {
                        let (l, r) = (ceil(pair[0]), ceil(pair[1]));
                        (l < r).then_some((l, r))
                    })
                    .collect::<Vec<_>>(),
            );
        }
        if rows.iter().all(Vec::is_empty) {
            return Err(VisionError::InvalidRoi);
        }
        Ok(Self { bounds, rows })
    }

    pub fn fits(&self, roi: PixelRect) -> bool {
        self.bounds.left >= roi.left
            && self.bounds.top >= roi.top
            && self.bounds.right <= roi.right
            && self.bounds.bottom <= roi.bottom
    }

    pub fn contains(&self, x: u32, y: u32) -> bool {
        self.rows
            .get(y.wrapping_sub(self.bounds.top) as usize)
            .is_some_and(|row| row.iter().any(|&(l, r)| x >= l && x < r))
    }

    /// Template bounds must fit completely, including concave cutouts between its corners.
    pub fn contains_rect(&self, origin: PixelPoint, width: u32, height: u32) -> bool {
        let Some(right) = origin.x.checked_add(width) else {
            return false;
        };
        let Some(bottom) = origin.y.checked_add(height) else {
            return false;
        };
        if origin.y < self.bounds.top || bottom > self.bounds.bottom {
            return false;
        }
        (origin.y..bottom).all(|y| {
            self.rows[(y - self.bounds.top) as usize]
                .iter()
                .any(|&(l, r)| origin.x >= l && right <= r)
        })
    }

    /// Intersect scanline spans once per template, rather than checking every template row
    /// at every candidate pixel. This geometry never contains captured image data.
    pub(crate) fn template_origins(
        &self,
        width: u32,
        height: u32,
        charge: &mut dyn FnMut(u64) -> Result<(), VisionError>,
    ) -> Result<Self, VisionError> {
        if width == 0 || height == 0 {
            return Err(VisionError::EmptyTemplate);
        }
        if width > self.bounds.width() || height > self.bounds.height() {
            return Ok(Self {
                bounds: self.bounds,
                rows: vec![],
            });
        }
        let shrink = |row: &[(u32, u32)]| {
            row.iter()
                .filter_map(|&(l, r)| (r - l >= width).then(|| (l, r - width + 1)))
                .collect::<Vec<_>>()
        };
        let shrunk = self
            .rows
            .iter()
            .map(|row| {
                charge(row.len() as u64 + 1)?;
                Ok(shrink(row))
            })
            .collect::<Result<Vec<_>, VisionError>>()?;
        let mut rows = Vec::with_capacity(self.rows.len() - height as usize + 1);
        for start in 0..=self.rows.len() - height as usize {
            charge(shrunk[start].len() as u64 + 1)?;
            let mut spans = shrunk[start].clone();
            let mut next = Vec::new();
            for row in &shrunk[start + 1..start + height as usize] {
                if spans.is_empty() {
                    break;
                }
                charge((spans.len() + row.len() + 1) as u64)?;
                let (mut a, mut b) = (0, 0);
                next.clear();
                while a < spans.len() && b < row.len() {
                    let l = spans[a].0.max(row[b].0);
                    let r = spans[a].1.min(row[b].1);
                    if l < r {
                        next.push((l, r));
                    }
                    if spans[a].1 < row[b].1 {
                        a += 1;
                    } else {
                        b += 1;
                    }
                }
                std::mem::swap(&mut spans, &mut next);
            }
            rows.push(spans);
        }
        Ok(Self {
            bounds: PixelRect {
                right: self.bounds.right - width + 1,
                bottom: self.bounds.bottom - height + 1,
                ..self.bounds
            },
            rows,
        })
    }
}

pub enum ColorSelection {
    First,
    All(usize),
    Count(usize),
}

pub fn search_colors(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
    region: &PolygonRegion,
    mode: ColorSelection,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<(usize, Vec<PixelPoint>), VisionError> {
    validate_search(image, options)?;
    if !region.fits(options.roi) {
        return Err(VisionError::InvalidRoi);
    }
    if colors.is_empty() || colors.len() > 16 {
        return Err(VisionError::EmptyPattern);
    }
    let (limit, retain) = match mode {
        ColorSelection::First => (1, true),
        ColorSelection::All(n) if (1..=2048).contains(&n) => (n, true),
        ColorSelection::Count(n) if n <= crate::MAX_COLOR_COUNT => (n, false),
        _ => return Err(VisionError::InvalidResultLimit),
    };
    let mut budget = ComparisonBudget {
        remaining: options.max_pixel_comparisons,
        checkpoint: Some(cancelled),
        next_check: options.max_pixel_comparisons,
    };
    let mut count = 0;
    let mut points = Vec::new();
    for p in SearchPoints::new(options) {
        budget.consume(1)?;
        if !region.contains(p.x, p.y) {
            continue;
        }
        let actual = read_pixel(image, p.x, p.y);
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
            if retain {
                points.push(p);
            }
            if count == limit {
                break;
            }
        }
    }
    Ok((count, points))
}

pub fn search_pattern(
    image: ImageView<'_>,
    samples: &[PatternSample],
    options: SearchOptions,
    region: &PolygonRegion,
    cancelled: &mut dyn FnMut() -> bool,
) -> Result<Option<PixelPoint>, VisionError> {
    validate_search(image, options)?;
    if !region.fits(options.roi) {
        return Err(VisionError::InvalidRoi);
    }
    if samples.is_empty() || samples.len() > 65 {
        return Err(VisionError::EmptyPattern);
    }
    let mut budget = ComparisonBudget {
        remaining: options.max_pixel_comparisons,
        checkpoint: Some(cancelled),
        next_check: options.max_pixel_comparisons,
    };
    for p in SearchPoints::new(options) {
        budget.consume(1)?;
        if !region.contains(p.x, p.y) {
            continue;
        }
        let mut matched = true;
        for sample in samples {
            budget.consume(1)?;
            let x = i64::from(p.x) + i64::from(sample.offset_x);
            let y = i64::from(p.y) + i64::from(sample.offset_y);
            if x < 0
                || y < 0
                || x >= i64::from(image.width)
                || y >= i64::from(image.height)
                || !region.contains(x as u32, y as u32)
                || !color_matches(
                    read_pixel(image, x as u32, y as u32),
                    sample.color,
                    sample.tolerance,
                )
            {
                matched = false;
                break;
            }
        }
        if matched {
            return Ok(Some(p));
        }
    }
    Ok(None)
}
