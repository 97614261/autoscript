use super::*;

fn image(bytes: &[u8], width: u32, height: u32) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: width as usize * 4,
        format: PixelFormat::Rgba8888,
        pixels: bytes,
    }
}

fn options(roi: PixelRect) -> TemplateOptions {
    TemplateOptions {
        search: SearchOptions {
            roi,
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000_000_000,
        },
        tolerance: ColorTolerance::EXACT,
        minimum_match_permille: 1000,
        ignore_transparent_template_pixels: true,
    }
}

fn template(seed: u32) -> Vec<u8> {
    (0..200 * 122)
        .flat_map(|n| {
            [
                u8::try_from(50 + (n * 7 + seed) % 190).unwrap(),
                u8::try_from(50 + (n * 11 + seed) % 190).unwrap(),
                u8::try_from(50 + (n * 13 + seed) % 190).unwrap(),
                255,
            ]
        })
        .collect()
}

fn frame(target: Option<PixelPoint>, patch: &[u8]) -> Vec<u8> {
    let mut bytes = [13, 17, 19, 255].repeat(720 * 1280);
    if let Some(point) = target {
        for row in 0..122_usize {
            let start = ((point.y as usize + row) * 720 + point.x as usize) * 4;
            bytes[start..start + 800].copy_from_slice(&patch[row * 800..(row + 1) * 800]);
        }
    }
    bytes
}

// Keep a byte-for-byte traversal reference for the candidate gate. This mirrors the production
// SearchPoints state machine, including center-out state; only corner row/column derivation uses
// the old division/remainder path.
#[derive(Debug, Clone)]
struct DivisionReferencePoints {
    options: SearchOptions,
    columns: u64,
    rows: u64,
    emitted: u64,
    center: CenterOutState,
}

impl DivisionReferencePoints {
    fn new(options: SearchOptions) -> Self {
        Self {
            columns: axis_len(options.roi.left, options.roi.right, options.step_x),
            rows: axis_len(options.roi.top, options.roi.bottom, options.step_y),
            options,
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

impl Iterator for DivisionReferencePoints {
    type Item = PixelPoint;

    fn next(&mut self) -> Option<Self::Item> {
        if self.options.order == SearchOrder::CenterOut {
            self.next_center()
        } else {
            self.next_corner()
        }
    }
}

#[test]
fn production_cursor_matches_division_at_edges_steps_and_single_axes() {
    for left in [0, 7, u32::MAX - 24] {
        for width in [1, 2, 7, 23] {
            for height in [1, 2, 9, 23] {
                for step in [1, 2, 5, u32::MAX] {
                    for order in [
                        SearchOrder::TopLeftToBottomRight,
                        SearchOrder::BottomLeftToTopRight,
                        SearchOrder::BottomRightToTopLeft,
                        SearchOrder::TopRightToBottomLeft,
                        SearchOrder::CenterOut,
                    ] {
                        let options = SearchOptions {
                            roi: PixelRect {
                                left,
                                top: left,
                                right: left + width,
                                bottom: left + height,
                            },
                            step_x: step,
                            step_y: step,
                            order,
                            max_pixel_comparisons: 10000,
                        };
                        assert_eq!(
                            SearchPoints::new(options).collect::<Vec<_>>(),
                            DivisionReferencePoints::new(options).collect::<Vec<_>>()
                        );
                    }
                }
            }
        }
    }
}

fn search_points_checksum(options: SearchOptions) -> u64 {
    DivisionReferencePoints::new(options).fold(0_u64, |checksum, point| {
        checksum.wrapping_add(u64::from(point.x) ^ u64::from(point.y).rotate_left(17))
    })
}

fn incremental_corner_checksum(options: SearchOptions) -> u64 {
    SearchPoints::new(options).fold(0_u64, |checksum, point| {
        checksum.wrapping_add(u64::from(point.x) ^ u64::from(point.y).rotate_left(17))
    })
}

#[test]
fn division_reference_matches_production_search_points() {
    for order in [
        SearchOrder::BottomLeftToTopRight,
        SearchOrder::TopLeftToBottomRight,
        SearchOrder::BottomRightToTopLeft,
        SearchOrder::TopRightToBottomLeft,
        SearchOrder::CenterOut,
    ] {
        let options = SearchOptions {
            roi: PixelRect {
                left: 11,
                top: 17,
                right: 37,
                bottom: 43,
            },
            step_x: 3,
            step_y: 2,
            order,
            max_pixel_comparisons: 1_000_000,
        };
        let production: Vec<_> = SearchPoints::new(options).collect();
        let reference: Vec<_> = DivisionReferencePoints::new(options).collect();
        assert_eq!(reference, production, "reference drifted for {order:?}");
    }
}

fn search_with_points<I>(
    image: ImageView<'_>,
    template: &PreparedTemplate,
    options: TemplateOptions,
    remaining: &mut u64,
    points: I,
) -> Result<Option<TemplateMatch>, VisionError>
where
    I: IntoIterator<Item = PixelPoint>,
{
    super::validate_search(image, options.search)?;
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
    let initial = *remaining;
    let mut budget = ComparisonBudget::new(initial);
    let comparison = super::TemplateComparison::new(image, template, options);
    let mut prefilter = None;
    for (index, origin) in points.into_iter().enumerate() {
        if index == 1 {
            prefilter = super::observed_prefilter(image, template, options, &mut budget, None)?;
        }
        if prefilter
            .as_ref()
            .is_some_and(|filter| filter.rejects(origin))
        {
            budget.consume(1)?;
            continue;
        }
        let score =
            super::observed_compare(image, &comparison, origin, options, &mut budget, None)?;
        if score >= options.minimum_match_permille {
            *remaining = remaining.saturating_sub(initial - budget.remaining);
            return Ok(Some(TemplateMatch {
                origin,
                matched_permille: score,
            }));
        }
    }
    *remaining = remaining.saturating_sub(initial - budget.remaining);
    Ok(None)
}

#[test]
fn cancellation_during_full_roi_search_retains_work_and_never_returns_a_match() {
    let patch = template(1);
    let prepared = PreparedTemplate::prepare(image(&patch, 200, 122)).unwrap();
    let bytes = frame(None, &patch);
    let mut calls = 0;
    let mut cancelled = || {
        calls += 1;
        calls >= 3
    };
    let mut budget = 1_000_000_000;
    let mut metrics = TemplateSearchMetrics::default();
    let result = find_prepared_template_observed(
        image(&bytes, 720, 1280),
        &prepared,
        options(PixelRect {
            left: 0,
            top: 0,
            right: 720,
            bottom: 1280,
        }),
        &mut budget,
        &mut cancelled,
        Some(&mut metrics),
    );
    assert_eq!(result, Err(VisionError::Cancelled));
    assert_eq!(calls, 3);
    assert!(budget < 1_000_000_000);
    assert!(metrics.candidates_visited > 0);
}

#[test]
#[ignore = "explicit fixed-input CPU matrix; no screenshots or target-device claims"]
fn benchmark_fixed_search_matrix() {
    let small = PixelRect {
        left: 149,
        top: 407,
        right: 546,
        bottom: 879,
    };
    let large = PixelRect {
        left: 0,
        top: 0,
        right: 720,
        bottom: 1280,
    };
    let patch = template(1);
    let templates: Vec<_> = [23, 47, 83, 1]
        .into_iter()
        .map(|seed| {
            let bytes = template(seed);
            PreparedTemplate::prepare(image(&bytes, 200, 122)).unwrap()
        })
        .collect();
    let cases = [
        ("early", small, Some(PixelPoint { x: 149, y: 407 }), 1_usize),
        ("late", small, Some(PixelPoint { x: 346, y: 757 }), 1),
        ("missing", small, None, 1),
        ("multi-four", small, Some(PixelPoint { x: 346, y: 757 }), 4),
        ("large-missing", large, None, 1),
    ];
    let frames: Vec<_> = cases
        .iter()
        .map(|(_, _, target, _)| frame(*target, &patch))
        .collect();
    let mut samples = vec![Vec::new(); cases.len()];
    let mut work = vec![(0, 0); cases.len()];
    for iteration in 0..110 {
        // Rotate scenario order so a later scenario does not always inherit a warmer CPU.
        for offset in 0..cases.len() {
            let index = (iteration + offset) % cases.len();
            let (_, roi, expected, count) = cases[index];
            let mut metrics = TemplateSearchMetrics::default();
            let mut budget = 1_000_000_000;
            let started = std::time::Instant::now();
            let mut found = None;
            for prepared in templates.iter().skip(4 - count) {
                metrics.templates_visited += 1;
                found = find_prepared_template_observed(
                    image(&frames[index], 720, 1280),
                    prepared,
                    options(roi),
                    &mut budget,
                    &mut || false,
                    Some(&mut metrics),
                )
                .unwrap();
                if found.is_some() {
                    break;
                }
            }
            let elapsed = started.elapsed().as_secs_f64() * 1000.0;
            assert_eq!(found.map(|value| value.origin), expected);
            work[index] = (metrics.candidates_visited, metrics.comparison_pixels);
            if iteration >= 10 {
                samples[index].push(elapsed);
            }
        }
    }
    for ((name, roi, _, count), (mut values, work)) in
        cases.into_iter().zip(samples.into_iter().zip(work))
    {
        values.sort_by(f64::total_cmp);
        println!("matrix {name}: frame=720x1280 ROI={},{},{},{} template=200x122 count={count} RGBA score=1000 tolerance=0 direction=0 n=100 P50={:.3} P95={:.3} P99={:.3}ms candidates={} comparisonPixels={}",
            roi.left, roi.top, roi.right, roi.bottom, values[49], values[94], values[98], work.0, work.1);
    }
}

#[test]
#[ignore = "same-process interleaved A/B traversal benchmark; no device or full-search claim"]
fn benchmark_incremental_cursor_against_division_reference() {
    let cases = [
        (
            "small-step1",
            SearchOptions {
                roi: PixelRect {
                    left: 149,
                    top: 407,
                    right: 546,
                    bottom: 879,
                },
                step_x: 1,
                step_y: 1,
                order: SearchOrder::TopLeftToBottomRight,
                max_pixel_comparisons: 1_000_000_000,
            },
        ),
        (
            "large-step1",
            SearchOptions {
                roi: PixelRect {
                    left: 0,
                    top: 0,
                    right: 720,
                    bottom: 1_280,
                },
                step_x: 1,
                step_y: 1,
                order: SearchOrder::TopLeftToBottomRight,
                max_pixel_comparisons: 1_000_000_000,
            },
        ),
        (
            "large-step3-reverse",
            SearchOptions {
                roi: PixelRect {
                    left: 11,
                    top: 17,
                    right: 719,
                    bottom: 1_273,
                },
                step_x: 3,
                step_y: 3,
                order: SearchOrder::BottomRightToTopLeft,
                max_pixel_comparisons: 1_000_000_000,
            },
        ),
    ];
    for (name, options) in cases {
        let expected = search_points_checksum(options);
        assert_eq!(incremental_corner_checksum(options), expected);
        let mut samples = [Vec::new(), Vec::new()];
        for iteration in 0..220 {
            let variant = iteration % 2;
            let started = std::time::Instant::now();
            let checksum = if variant == 0 {
                search_points_checksum(options)
            } else {
                incremental_corner_checksum(options)
            };
            assert_eq!(std::hint::black_box(checksum), expected);
            if iteration >= 20 {
                samples[variant].push(started.elapsed().as_secs_f64() * 1_000_000.0);
            }
        }
        for values in &mut samples {
            values.sort_by(f64::total_cmp);
        }
        println!(
            "cursor-ab {name}: legacy P50/P95/P99={:.2}/{:.2}/{:.2}us incremental P50/P95/P99={:.2}/{:.2}/{:.2}us n={}",
            samples[0][49],
            samples[0][94],
            samples[0][98],
            samples[1][49],
            samples[1][94],
            samples[1][98],
            samples[0].len()
        );
    }
}

#[test]
#[ignore = "same-process interleaved full-search A/B; no device or end-to-end claim"]
fn benchmark_incremental_cursor_full_search_ab() {
    let common_roi = PixelRect {
        left: 149,
        top: 407,
        right: 546,
        bottom: 879,
    };
    let cases = [
        ("early", common_roi, Some(PixelPoint { x: 149, y: 407 })),
        ("late", common_roi, Some(PixelPoint { x: 346, y: 757 })),
        ("missing", common_roi, None),
        (
            "large-missing",
            PixelRect {
                left: 0,
                top: 0,
                right: 720,
                bottom: 1_280,
            },
            None,
        ),
    ];
    let patch = template(1);
    let prepared = PreparedTemplate::prepare(image(&patch, 200, 122)).unwrap();
    let search = |roi| TemplateOptions {
        search: SearchOptions {
            roi,
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000_000_000,
        },
        tolerance: ColorTolerance::EXACT,
        minimum_match_permille: 1_000,
        ignore_transparent_template_pixels: true,
    };
    for (name, roi, expected) in cases {
        let frame_bytes = frame(expected, &patch);
        let mut samples = [Vec::new(), Vec::new()];
        for iteration in 0..120 {
            for variant in [iteration % 2, 1 - iteration % 2] {
                let started = std::time::Instant::now();
                let mut remaining = 1_000_000_000;
                let result = if variant == 0 {
                    search_with_points(
                        image(&frame_bytes, 720, 1_280),
                        &prepared,
                        search(roi),
                        &mut remaining,
                        DivisionReferencePoints::new(SearchOptions {
                            roi: PixelRect {
                                right: roi.right - prepared.width() + 1,
                                bottom: roi.bottom - prepared.height() + 1,
                                ..roi
                            },
                            ..search(roi).search
                        }),
                    )
                } else {
                    search_with_points(
                        image(&frame_bytes, 720, 1_280),
                        &prepared,
                        search(roi),
                        &mut remaining,
                        SearchPoints::new(SearchOptions {
                            roi: PixelRect {
                                right: roi.right - prepared.width() + 1,
                                bottom: roi.bottom - prepared.height() + 1,
                                ..roi
                            },
                            ..search(roi).search
                        }),
                    )
                };
                assert_eq!(result.unwrap().map(|match_| match_.origin), expected);
                if iteration >= 10 {
                    samples[variant].push(started.elapsed().as_secs_f64() * 1000.0);
                }
            }
        }
        for values in &mut samples {
            values.sort_by(f64::total_cmp);
        }
        println!(
            "cursor-full-ab {name}: division P50/P95/P99={:.3}/{:.3}/{:.3}ms candidate P50/P95/P99={:.3}/{:.3}/{:.3}ms n={}",
            samples[0][54],
            samples[0][103],
            samples[0][107],
            samples[1][54],
            samples[1][103],
            samples[1][107],
            samples[0].len()
        );
    }
}
