use super::*;

#[test]
fn batched_exact_spans_preserve_failure_pixel_budget_and_checkpoint_boundaries() {
    for length in [31, 32, 33, 65, 2053] {
        let colors: Vec<_> = (0..length)
            .map(|n| Color {
                red: u8::try_from(n % 251).unwrap(),
                green: u8::try_from(n % 239).unwrap(),
                blue: u8::try_from(n % 227).unwrap(),
                alpha: 255,
            })
            .collect();
        let actual: Vec<_> = colors
            .iter()
            .enumerate()
            .flat_map(|(n, c)| {
                [
                    if n % 5 == 0 {
                        c.red.wrapping_add(1)
                    } else {
                        c.red
                    },
                    c.green,
                    c.blue,
                    c.alpha,
                ]
            })
            .collect();
        let colors: Vec<_> = colors
            .iter()
            .map(|c| [c.blue, c.green, c.red, c.alpha])
            .collect();
        for allowed in [0, 1, 31, 32, 33, 4096] {
            for maximum in [0, 1, 31, 32, 33, 1023, 1024, 1025, 9999] {
                for gap in [0, 1, 31, 32, 33, 1024] {
                    for stop_at in [1, 2, 4, usize::MAX] {
                        let mut calls_old = 0;
                        let mut calls_new = 0;
                        let mut stop_old = || {
                            calls_old += 1;
                            calls_old == stop_at
                        };
                        let mut stop_new = || {
                            calls_new += 1;
                            calls_new == stop_at
                        };
                        let mut old = ComparisonBudget {
                            remaining: maximum,
                            next_check: maximum.saturating_sub(gap),
                            checkpoint: Some(&mut stop_old),
                        };
                        let mut new = ComparisonBudget {
                            remaining: maximum,
                            next_check: maximum.saturating_sub(gap),
                            checkpoint: Some(&mut stop_new),
                        };
                        let mut mismatches_old = 0;
                        let mut mismatches_new = 0;
                        assert_eq!(
                            compare_span::<false, true, false>(
                                &colors,
                                &actual,
                                ColorTolerance::EXACT,
                                allowed,
                                &mut mismatches_new,
                                &mut new
                            ),
                            compare_span_scalar::<false, true, false>(
                                &colors,
                                &actual,
                                ColorTolerance::EXACT,
                                allowed,
                                &mut mismatches_old,
                                &mut old
                            )
                        );
                        assert_eq!(
                            (new.remaining, new.next_check, mismatches_new),
                            (old.remaining, old.next_check, mismatches_old)
                        );
                        assert_eq!(calls_new, calls_old);
                    }
                }
            }
        }
    }
}

#[test]
#[ignore = "alternating scalar-span vs bounded-block CPU benchmark; no device claims"]
fn benchmark_exact_span_batches() {
    use std::time::Instant;
    for bgra in [false, true] {
        for mismatch_stride in [0, 17, 5] {
            let colors: Vec<_> = (0..200 * 122)
                .map(|n| Color {
                    red: u8::try_from(n % 251).unwrap(),
                    green: u8::try_from(n % 239).unwrap(),
                    blue: u8::try_from(n % 227).unwrap(),
                    alpha: 255,
                })
                .collect();
            let actual: Vec<_> = colors
                .iter()
                .enumerate()
                .flat_map(|(n, c)| {
                    let red = if mismatch_stride != 0 && n % mismatch_stride == 0 {
                        c.red.wrapping_add(1)
                    } else {
                        c.red
                    };
                    if bgra {
                        [c.blue, c.green, red, c.alpha]
                    } else {
                        [red, c.green, c.blue, c.alpha]
                    }
                })
                .collect();
            let packed: Vec<_> = colors
                .iter()
                .map(|c| [c.blue, c.green, c.red, c.alpha])
                .collect();
            let mut times = [Vec::new(), Vec::new()];
            let mut work = [0; 2];
            for iteration in 0..100 {
                for variant in [iteration % 2, 1 - iteration % 2] {
                    let start = Instant::now();
                    let mut budget = ComparisonBudget {
                        remaining: 1_000_000_000,
                        next_check: 1_000_000_000,
                        checkpoint: Some(&mut || false),
                    };
                    for _ in 0..100 {
                        let mut mismatches = 0;
                        for ((row, packed_row), bytes) in colors
                            .chunks_exact(200)
                            .zip(packed.chunks_exact(200))
                            .zip(actual.chunks_exact(200 * 4))
                        {
                            let result = match (variant, bgra) {
                                (0, false) => compare_original_span::<false>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (0, true) => compare_original_span::<true>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (_, false) => compare_span::<false, true, false>(
                                    packed_row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (_, true) => compare_span::<true, true, false>(
                                    packed_row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                            }
                            .unwrap();
                            if !result {
                                break;
                            }
                        }
                    }
                    work[variant] = 1_000_000_000 - budget.remaining;
                    times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
                }
            }
            assert_eq!(work[0], work[1]);
            for (name, mut values) in ["scalar-span", "bounded-block"].into_iter().zip(times) {
                values.sort_by(f64::total_cmp);
                println!("exact-{bgra}-mismatch-{mismatch_stride}-{name}-100-candidates: P50={:.3} P95={:.3} P99={:.3}ms pixels={}", values[49], values[94], values[98], work[0]);
            }
        }
    }
}

fn compare_original_span<const BGRA: bool>(
    expected: &[Color],
    actual: &[u8],
    _tolerance: ColorTolerance,
    allowed: u64,
    mismatches: &mut u64,
    budget: &mut ComparisonBudget,
) -> Result<bool, VisionError> {
    for (color, bytes) in expected.iter().zip(actual.chunks_exact(4)) {
        budget.consume(1)?;
        let packed = if BGRA {
            [color.blue, color.green, color.red, color.alpha]
        } else {
            [color.red, color.green, color.blue, color.alpha]
        };
        if bytes != packed {
            *mismatches += 1;
        }
        if *mismatches > allowed {
            return Ok(false);
        }
    }
    Ok(true)
}

fn view(
    bytes: &[u8],
    width: u32,
    height: u32,
    format: PixelFormat,
    padding: usize,
) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: width as usize * 4 + padding,
        format,
        pixels: bytes,
    }
}

fn options(width: u32, height: u32) -> TemplateOptions {
    TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: width,
                bottom: height,
            },
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000_000_000,
        },
        tolerance: ColorTolerance::EXACT,
        minimum_match_permille: 900,
        ignore_transparent_template_pixels: true,
    }
}

#[test]
fn specialized_spans_preserve_scores_budget_and_pixel_order() {
    for (w, h) in [(1, 1), (2, 2), (7, 5), (13, 7), (70, 3)] {
        let mut source = Vec::new();
        for n in 0..w * h {
            source.extend_from_slice(&[
                (n * 31 % 251) as u8,
                (n * 17 % 251) as u8,
                (n * 7 % 251) as u8,
                if n % 5 == 0 { 0 } else { 255 },
            ]);
        }
        let prepared =
            PreparedTemplate::prepare(view(&source, w, h, PixelFormat::Rgba8888, 0)).unwrap();
        for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
            let stride = (w + 4) as usize * 4 + 12;
            let mut screen = vec![37; stride * (h + 3) as usize];
            for y in 0..h as usize {
                for x in 0..w as usize {
                    let mut color: [u8; 4] = source
                        [(y * w as usize + x) * 4..(y * w as usize + x + 1) * 4]
                        .try_into()
                        .unwrap();
                    if (x + y) % 7 == 0 {
                        color[0] = color[0].wrapping_add(9);
                    }
                    if format == PixelFormat::Bgra8888 {
                        color.swap(0, 2);
                    }
                    let offset = (y + 1) * stride + (x + 2) * 4;
                    screen[offset..offset + 4].copy_from_slice(&color);
                }
            }
            let frame = view(&screen, w + 4, h + 3, format, 12);
            for ignore in [false, true] {
                for similarity in [0, 500, 900, 950, 1000] {
                    for tolerance in [
                        ColorTolerance::EXACT,
                        ColorTolerance {
                            red: 7,
                            green: 3,
                            blue: 11,
                            alpha: 255,
                        },
                    ] {
                        let opts = TemplateOptions {
                            tolerance,
                            minimum_match_permille: similarity,
                            ignore_transparent_template_pixels: ignore,
                            ..options(w + 4, h + 3)
                        };
                        for maximum in [0, 1, 2, 16, 100_000] {
                            for origin in [PixelPoint { x: 0, y: 0 }, PixelPoint { x: 2, y: 1 }] {
                                let mut old = ComparisonBudget::new(maximum);
                                let mut new = ComparisonBudget::new(maximum);
                                let reference = compare_template_reference(
                                    frame, &prepared, origin, opts, &mut old,
                                );
                                assert_eq!(
                                    compare_template(frame, &prepared, origin, opts, &mut new),
                                    reference
                                );
                                assert_eq!(new.remaining, old.remaining);
                            }
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn specialized_spans_preserve_cancellation_checkpoints() {
    let bytes = [255; 4].repeat(200 * 122);
    let frame = view(&bytes, 200, 122, PixelFormat::Rgba8888, 0);
    let prepared = PreparedTemplate::prepare(frame).unwrap();
    for stop_at in [1, 2, 8, 25] {
        let mut old_calls = 0;
        let mut new_calls = 0;
        let mut old_stop = || {
            old_calls += 1;
            old_calls == stop_at
        };
        let mut new_stop = || {
            new_calls += 1;
            new_calls == stop_at
        };
        let mut old = ComparisonBudget {
            remaining: 100_000,
            next_check: 100_000,
            checkpoint: Some(&mut old_stop),
        };
        let mut new = ComparisonBudget {
            remaining: 100_000,
            next_check: 100_000,
            checkpoint: Some(&mut new_stop),
        };
        let reference = compare_template_reference(
            frame,
            &prepared,
            PixelPoint { x: 0, y: 0 },
            options(200, 122),
            &mut old,
        );
        assert_eq!(
            compare_template(
                frame,
                &prepared,
                PixelPoint { x: 0, y: 0 },
                options(200, 122),
                &mut new
            ),
            reference
        );
        assert_eq!(new.remaining, old.remaining);
        assert_eq!(new_calls, old_calls);
    }
}

#[test]
#[allow(clippy::too_many_lines)] // Keep exhaustive format/alpha/tolerance variants together.
fn complementary_integral_rejects_blank_but_not_valid_or_transparent_windows() {
    let frame = [255; 4].repeat(180 * 100);
    let mut patch = [255; 4].repeat(40 * 20);
    for pixel in patch.chunks_exact_mut(4).take(240) {
        pixel.copy_from_slice(&[0, 0, 0, 255]);
    }
    let prepared =
        PreparedTemplate::prepare(view(&patch, 40, 20, PixelFormat::Rgba8888, 0)).unwrap();
    let opts = options(180, 100);
    let mut stats = TemplateSearchMetrics::default();
    let mut remaining = 1_000_000_000;
    assert_eq!(
        find_prepared_template_observed(
            view(&frame, 180, 100, PixelFormat::Rgba8888, 0),
            &prepared,
            opts,
            &mut remaining,
            &mut || false,
            Some(&mut stats)
        )
        .unwrap(),
        None
    );
    assert!(stats.comparison_pixels > 0);
    assert_eq!(stats.candidates_visited, 141 * 81);
    assert_eq!(stats.prefilter_rejected, stats.candidates_visited - 1);
    for ignore in [false, true] {
        for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
            let mut template = patch.clone();
            if ignore {
                for pixel in template.chunks_exact_mut(4).skip(500) {
                    pixel[3] = 0;
                }
            }
            let prepared =
                PreparedTemplate::prepare(view(&template, 40, 20, PixelFormat::Rgba8888, 0))
                    .unwrap();
            let mut screen = frame.clone();
            for y in 0..20 {
                screen[((y + 23) * 180 + 37) * 4..((y + 23) * 180 + 77) * 4]
                    .copy_from_slice(&template[y * 160..(y + 1) * 160]);
            }
            if format == PixelFormat::Bgra8888 {
                for pixel in screen.chunks_exact_mut(4) {
                    pixel.swap(0, 2);
                }
            }
            let opts = TemplateOptions {
                ignore_transparent_template_pixels: ignore,
                ..opts
            };
            let mut budget = ComparisonBudget::new(1_000_000_000);
            let filter = MajorityIntegral::build(
                view(&screen, 180, 100, format, 0),
                &prepared,
                opts,
                &mut budget,
            )
            .unwrap()
            .unwrap();
            for origin in SearchPoints::new(SearchOptions {
                roi: PixelRect {
                    right: 141,
                    bottom: 81,
                    ..opts.search.roi
                },
                ..opts.search
            }) {
                let score = compare_template_reference(
                    view(&screen, 180, 100, format, 0),
                    &prepared,
                    origin,
                    opts,
                    &mut ComparisonBudget::new(1_000_000_000),
                )
                .unwrap();
                if score >= 900 {
                    assert!(!filter.rejects(origin));
                }
            }
            let tolerant = TemplateOptions {
                tolerance: ColorTolerance {
                    red: 1,
                    green: 1,
                    blue: 1,
                    alpha: 0,
                },
                ..opts
            };
            assert_eq!(
                MajorityIntegral::build(
                    view(&screen, 180, 100, format, 0),
                    &prepared,
                    tolerant,
                    &mut budget
                )
                .unwrap()
                .unwrap()
                .maximum,
                u64::MAX
            );
        }
    }
}

#[test]
fn observations_do_not_change_results_or_budgets_and_retain_cancelled_work() {
    let pixels = [1, 2, 3, 255].repeat(30 * 20);
    let frame = view(&pixels, 30, 20, PixelFormat::Rgba8888, 0);
    let prepared =
        PreparedTemplate::prepare(view(&pixels[..4 * 4 * 3], 4, 3, PixelFormat::Rgba8888, 0))
            .unwrap();
    for limit in [1, 3, 65] {
        let mut old = 10000;
        let mut new = 10000;
        let mut metrics = TemplateSearchMetrics::default();
        let reference = find_prepared_templates_controlled(
            frame,
            &prepared,
            options(30, 20),
            limit,
            &mut old,
            &mut || false,
        );
        assert_eq!(
            find_prepared_templates_observed(
                frame,
                &prepared,
                options(30, 20),
                limit,
                &mut new,
                &mut || false,
                Some(&mut metrics)
            ),
            reference
        );
        assert_eq!(new, old);
        assert_eq!(metrics.candidates_visited, limit as u64);
        assert_eq!(metrics.comparison_pixels, limit as u64 * 12);
    }
    let mut remaining = 10000;
    let mut metrics = TemplateSearchMetrics::default();
    assert_eq!(
        find_prepared_template_observed(
            frame,
            &prepared,
            options(30, 20),
            &mut remaining,
            &mut || true,
            Some(&mut metrics)
        ),
        Err(VisionError::Cancelled)
    );
    assert_eq!(metrics.comparison_pixels, 0);
    let mut remaining = 5;
    let mut metrics = TemplateSearchMetrics::default();
    assert_eq!(
        find_prepared_template_observed(
            frame,
            &prepared,
            options(30, 20),
            &mut remaining,
            &mut || false,
            Some(&mut metrics)
        ),
        Err(VisionError::ComparisonBudgetExceeded)
    );
    assert_eq!(remaining, 0);
    assert_eq!(metrics.comparison_pixels, 5);
    assert_eq!(metrics.candidates_visited, 1);
}

#[test]
#[ignore = "explicit synthetic found/tolerant/observation CPU benchmark, no device claims"]
#[allow(clippy::too_many_lines)] // Keep alternating comparison and observation controls in one fixture.
fn benchmark_small_found_and_observation_overhead() {
    use std::time::Instant;
    let bytes: Vec<u8> = (0..720 * 1280)
        .flat_map(|n| {
            [
                u8::try_from(n % 251).unwrap(),
                u8::try_from(n % 239).unwrap(),
                u8::try_from(n % 227).unwrap(),
                255,
            ]
        })
        .collect();
    let image = view(&bytes, 720, 1280, PixelFormat::Rgba8888, 0);
    for (width, height, tolerance) in [
        (8, 8, ColorTolerance::EXACT),
        (200, 122, ColorTolerance::EXACT),
        (
            200,
            122,
            ColorTolerance {
                red: 7,
                green: 3,
                blue: 11,
                alpha: 0,
            },
        ),
    ] {
        let patch: Vec<u8> = (0..height as usize)
            .flat_map(|y| {
                bytes[y * 720 * 4..(y * 720 + width as usize) * 4]
                    .iter()
                    .copied()
            })
            .collect();
        let prepared =
            PreparedTemplate::prepare(view(&patch, width, height, PixelFormat::Rgba8888, 0))
                .unwrap();
        let opts = TemplateOptions {
            tolerance,
            ..options(720, 1280)
        };
        let mut times = [Vec::new(), Vec::new()];
        for iteration in 0..100 {
            for variant in [iteration % 2, 1 - iteration % 2] {
                let start = Instant::now();
                for _ in 0..100 {
                    let mut budget = ComparisonBudget::new(1_000_000_000);
                    let score = if variant == 0 {
                        compare_template_reference(
                            image,
                            &prepared,
                            PixelPoint { x: 0, y: 0 },
                            opts,
                            &mut budget,
                        )
                    } else {
                        compare_template(
                            image,
                            &prepared,
                            PixelPoint { x: 0, y: 0 },
                            opts,
                            &mut budget,
                        )
                    };
                    assert_eq!(score.unwrap(), 1000);
                }
                times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
            }
        }
        for (name, mut values) in ["scalar", "spans"].into_iter().zip(times) {
            values.sort_by(f64::total_cmp);
            println!("found-comparison-{width}x{height}-{tolerance:?}-{name}-100: P50={:.3} P95={:.3} P99={:.3}ms", values[49], values[94], values[98]);
        }
    }
    let patch = [17, 18, 19, 255].repeat(8 * 8);
    let prepared = PreparedTemplate::prepare(view(&patch, 8, 8, PixelFormat::Rgba8888, 0)).unwrap();
    let opts = options(720, 1280);
    let mut times = [Vec::new(), Vec::new()];
    for iteration in 0..100 {
        for variant in [iteration % 2, 1 - iteration % 2] {
            let start = Instant::now();
            let mut metrics = TemplateSearchMetrics::default();
            let result = find_prepared_template_observed(
                image,
                &prepared,
                opts,
                &mut 1_000_000_000,
                &mut || false,
                if variant == 1 {
                    Some(&mut metrics)
                } else {
                    None
                },
            );
            assert_eq!(result.unwrap(), None);
            times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
        }
    }
    for (name, mut values) in ["observation-off", "observation-on"].into_iter().zip(times) {
        values.sort_by(f64::total_cmp);
        println!(
            "missing-fullscreen-8x8-{name}: P50={:.3} P95={:.3} P99={:.3}ms",
            values[49], values[94], values[98]
        );
    }
}

#[test]
#[ignore = "explicit alternating scalar-vs-span CPU benchmark, no private templates or device captures"]
#[allow(clippy::too_many_lines)] // One benchmark keeps both alternating baselines reproducible.
fn benchmark_template_spans_and_background_filter() {
    use std::time::Instant;
    let texture: Vec<u8> = (0..200 * 122)
        .flat_map(|n| {
            if (n / 200 + n % 200) % 2 == 0 {
                [255; 4]
            } else {
                [0, 0, 0, 255]
            }
        })
        .collect();
    let frame = [255; 4].repeat(720 * 1280);
    let prepared =
        PreparedTemplate::prepare(view(&texture, 200, 122, PixelFormat::Rgba8888, 0)).unwrap();
    let image = view(&frame, 720, 1280, PixelFormat::Rgba8888, 0);
    let opts = TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 149,
                top: 407,
                right: 546,
                bottom: 879,
            },
            ..options(720, 1280).search
        },
        ..options(720, 1280)
    };
    let mut times = [Vec::new(), Vec::new()];
    for iteration in 0..100 {
        for variant in [iteration % 2, 1 - iteration % 2] {
            let start = Instant::now();
            let mut budget = ComparisonBudget::new(1_000_000_000);
            for x in 149..249 {
                let score = if variant == 0 {
                    compare_template_reference(
                        image,
                        &prepared,
                        PixelPoint { x, y: 407 },
                        opts,
                        &mut budget,
                    )
                } else {
                    compare_template(
                        image,
                        &prepared,
                        PixelPoint { x, y: 407 },
                        opts,
                        &mut budget,
                    )
                };
                assert_eq!(score.unwrap(), 0);
            }
            times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
        }
    }
    for (name, mut values) in ["scalar-100-candidates", "spans-100-candidates"]
        .into_iter()
        .zip(times)
    {
        values.sort_by(f64::total_cmp);
        println!(
            "{name}: P50={:.3} P95={:.3} P99={:.3}ms",
            values[49], values[94], values[98]
        );
    }
    let mut patch = [255; 4].repeat(200 * 122);
    for pixel in patch.chunks_exact_mut(4).take(7320) {
        pixel.copy_from_slice(&[0, 0, 0, 255]);
    }
    let template =
        PreparedTemplate::prepare(view(&patch, 200, 122, PixelFormat::Rgba8888, 0)).unwrap();
    let mut times = [Vec::new(), Vec::new()];
    for iteration in 0..100 {
        for variant in [iteration % 2, 1 - iteration % 2] {
            let start = Instant::now();
            let mut budget = ComparisonBudget::new(1_000_000_000);
            let mut filter = MajorityIntegral::build(image, &template, opts, &mut budget)
                .unwrap()
                .unwrap();
            if variant == 0 {
                filter.maximum = u64::MAX;
                filter.auxiliary = None;
                filter.regions.clear();
            }
            for origin in SearchPoints::new(SearchOptions {
                roi: PixelRect {
                    right: 347,
                    bottom: 758,
                    ..opts.search.roi
                },
                ..opts.search
            }) {
                if filter.rejects(origin) {
                    budget.consume(1).unwrap();
                } else {
                    assert_eq!(
                        compare_template(image, &template, origin, opts, &mut budget).unwrap(),
                        0
                    );
                }
            }
            times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
        }
    }
    for (name, mut values) in ["one-sided-integral", "complementary-integral"]
        .into_iter()
        .zip(times)
    {
        values.sort_by(f64::total_cmp);
        println!(
            "{name}: P50={:.3} P95={:.3} P99={:.3}ms",
            values[49], values[94], values[98]
        );
    }
}
