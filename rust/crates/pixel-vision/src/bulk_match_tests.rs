use super::*;

fn synthetic_color(n: u32) -> [u8; 4] {
    [
        u8::try_from(n % 256).unwrap(),
        u8::try_from(n * 7 % 256).unwrap(),
        u8::try_from(n * 13 % 256).unwrap(),
        255,
    ]
}

#[test]
fn per_search_comparison_keeps_origin_stride_budget_and_checkpoints() {
    let patch: Vec<u8> = (0..85)
        .flat_map(|n| {
            let mut color = synthetic_color(n);
            if n % 7 == 0 {
                color[3] = 0;
            }
            color
        })
        .collect();
    let template = PreparedTemplate::prepare(ImageView {
        width: 17,
        height: 5,
        row_stride: 68,
        format: PixelFormat::Rgba8888,
        pixels: &patch,
    })
    .unwrap();
    for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
        let stride = 40 * 4 + 12;
        let mut pixels = vec![37; stride * 13];
        for y in 0..5 {
            for x in 0..17 {
                let start = (3 + y) * stride + (9 + x) * 4;
                let mut color: [u8; 4] = patch[(y * 17 + x) * 4..(y * 17 + x + 1) * 4]
                    .try_into()
                    .unwrap();
                if format == PixelFormat::Bgra8888 {
                    color.swap(0, 2);
                }
                pixels[start..start + 4].copy_from_slice(&color);
            }
        }
        let image = ImageView {
            width: 40,
            height: 13,
            row_stride: stride,
            format,
            pixels: &pixels,
        };
        for similarity in [0, 900, 999, 1000] {
            for ignore in [false, true] {
                for tolerance in [
                    ColorTolerance::EXACT,
                    ColorTolerance {
                        red: 3,
                        green: 4,
                        blue: 5,
                        alpha: 1,
                    },
                ] {
                    let options = TemplateOptions {
                        search: SearchOptions {
                            roi: PixelRect {
                                left: 0,
                                top: 0,
                                right: 40,
                                bottom: 13,
                            },
                            step_x: 1,
                            step_y: 1,
                            order: SearchOrder::TopLeftToBottomRight,
                            max_pixel_comparisons: 10000,
                        },
                        tolerance,
                        minimum_match_permille: similarity,
                        ignore_transparent_template_pixels: ignore,
                    };
                    let comparison = TemplateComparison::new(image, &template, options);
                    for origin in [
                        PixelPoint { x: 0, y: 0 },
                        PixelPoint { x: 9, y: 3 },
                        PixelPoint { x: 23, y: 8 },
                    ] {
                        for maximum in [0, 1, 4, 32, 10000] {
                            for stop_at in [1, 2, usize::MAX] {
                                assert_per_search_equivalence(
                                    image,
                                    &comparison,
                                    origin,
                                    options,
                                    maximum,
                                    stop_at,
                                );
                            }
                        }
                    }
                }
            }
        }
    }
}

fn assert_per_search_equivalence(
    image: ImageView<'_>,
    comparison: &TemplateComparison<'_>,
    origin: PixelPoint,
    options: TemplateOptions,
    maximum: u64,
    stop_at: usize,
) {
    let mut results = Vec::new();
    for reference in [false, true] {
        let mut calls = 0;
        let result = {
            let mut cancelled = || {
                calls += 1;
                calls == stop_at
            };
            let mut budget = ComparisonBudget {
                remaining: maximum,
                next_check: maximum,
                checkpoint: Some(&mut cancelled),
            };
            let result = if reference {
                compare_template_reference(image, comparison.template, origin, options, &mut budget)
            } else {
                compare_template_prepared(image, comparison, origin, options, &mut budget)
            };
            (result, budget.remaining, budget.next_check)
        };
        results.push((result, calls));
    }
    assert_eq!(results[0], results[1]);
}

#[test]
#[ignore = "explicit public synthetic CPU search matrix, not complete Android pipeline"]
fn benchmark_followup_search_matrix() {
    for similarity in [1000, 900] {
        let template_pixels: Vec<u8> = (0..200 * 122)
            .flat_map(|n| {
                if similarity == 900 && n % 4 != 0 {
                    [32, 48, 64, 255]
                } else {
                    synthetic_color(n)
                }
            })
            .collect();
        let template = PreparedTemplate::prepare(ImageView {
            width: 200,
            height: 122,
            row_stride: 800,
            format: PixelFormat::Bgra8888,
            pixels: &template_pixels,
        })
        .unwrap();
        for found in [true, false] {
            let mut pixels = [13, 17, 19, 255].repeat(720 * 1280);
            if found {
                for (y, row) in template_pixels.chunks_exact(800).enumerate() {
                    let start = ((948 + y) * 720 + 480) * 4;
                    pixels[start..start + 800].copy_from_slice(row);
                }
            }
            let image = ImageView {
                width: 720,
                height: 1280,
                row_stride: 2880,
                format: PixelFormat::Bgra8888,
                pixels: &pixels,
            };
            let options = TemplateOptions {
                search: SearchOptions {
                    roi: PixelRect {
                        left: 0,
                        top: 0,
                        right: 720,
                        bottom: 1280,
                    },
                    step_x: 1,
                    step_y: 1,
                    order: SearchOrder::TopLeftToBottomRight,
                    max_pixel_comparisons: 1_000_000_000,
                },
                tolerance: ColorTolerance::EXACT,
                minimum_match_permille: similarity,
                ignore_transparent_template_pixels: false,
            };
            let mut samples = Vec::new();
            for iteration in 0..110 {
                let started = std::time::Instant::now();
                let mut remaining = 1_000_000_000;
                let result = find_prepared_template_controlled(
                    image,
                    &template,
                    options,
                    &mut remaining,
                    &mut || false,
                )
                .unwrap();
                assert_eq!(result.is_some(), found);
                if let Some(result) = result {
                    assert_eq!(result.origin, PixelPoint { x: 480, y: 948 });
                }
                std::hint::black_box((result, remaining));
                if iteration >= 10 {
                    samples.push(started.elapsed().as_secs_f64() * 1000.0);
                }
            }
            samples.sort_by(f64::total_cmp);
            println!("CPU_SEARCH similarity={similarity} found={found} frame=720x1280 template=200x122 N=100 P50={:.3} P95={:.3} P99={:.3}ms; BGRA, tolerance=0, direction=0; no capture, input, transport or logs", samples[49], samples[94], samples[98]);
        }
    }
}

#[test]
fn deferred_span_setup_preserves_anchor_rejection_score_budget_and_cancellation() {
    let expected: Vec<u8> = (0..35)
        .flat_map(|n| {
            let mut color = synthetic_color(n);
            if n % 5 == 0 {
                color[3] = 0;
            }
            color
        })
        .collect();
    let template = PreparedTemplate::prepare(ImageView {
        width: 7,
        height: 5,
        row_stride: 28,
        format: PixelFormat::Rgba8888,
        pixels: &expected,
    })
    .unwrap();
    let pixels = [13, 17, 19, 255].repeat(16 * 16);
    for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
        let image = ImageView {
            width: 16,
            height: 16,
            row_stride: 64,
            format,
            pixels: &pixels,
        };
        for similarity in [0, 900, 999, 1000] {
            for tolerance in [
                ColorTolerance::EXACT,
                ColorTolerance {
                    red: 2,
                    green: 3,
                    blue: 4,
                    alpha: 0,
                },
            ] {
                for ignore in [false, true] {
                    let options = TemplateOptions {
                        search: SearchOptions {
                            roi: PixelRect {
                                left: 0,
                                top: 0,
                                right: 16,
                                bottom: 16,
                            },
                            step_x: 1,
                            step_y: 1,
                            order: SearchOrder::TopLeftToBottomRight,
                            max_pixel_comparisons: 1000,
                        },
                        tolerance,
                        minimum_match_permille: similarity,
                        ignore_transparent_template_pixels: ignore,
                    };
                    for maximum in [0, 1, 4, 16, 1000] {
                        for stop_at in [1, 2, usize::MAX] {
                            let mut results = Vec::new();
                            for reference in [false, true] {
                                let mut calls = 0;
                                let result = {
                                    let mut checkpoint = || {
                                        calls += 1;
                                        calls == stop_at
                                    };
                                    let mut budget = ComparisonBudget {
                                        remaining: maximum,
                                        next_check: maximum,
                                        checkpoint: Some(&mut checkpoint),
                                    };
                                    let score = if reference {
                                        compare_template_reference(
                                            image,
                                            &template,
                                            PixelPoint { x: 0, y: 0 },
                                            options,
                                            &mut budget,
                                        )
                                    } else {
                                        compare_template(
                                            image,
                                            &template,
                                            PixelPoint { x: 0, y: 0 },
                                            options,
                                            &mut budget,
                                        )
                                    };
                                    (score, budget.remaining, budget.next_check)
                                };
                                results.push((result, calls));
                            }
                            assert_eq!(results[0], results[1]);
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn word_comparison_keeps_every_budget_and_checkpoint() {
    for bgra in [false, true] {
        for stride in [0, 5, 17] {
            let expected: Vec<_> = (0..2053).map(synthetic_color).collect();
            let actual: Vec<_> = expected
                .iter()
                .enumerate()
                .flat_map(|(n, color)| {
                    let mut c = *color;
                    if stride != 0 && n % stride == 0 {
                        c[1] ^= 1;
                    }
                    if !bgra {
                        c.swap(0, 2);
                    }
                    c
                })
                .collect();
            for maximum in [0_u64, 1, 31, 32, 33, 1023, 1024, 1025, 9999] {
                for gap in [0, 1, 31, 32, 33, 1024] {
                    for allowed in [0, 31, 32, 33, 2053] {
                        for stop_at in [1, 2, usize::MAX] {
                            let mut outputs = Vec::new();
                            for bulk in [false, true] {
                                let mut calls = 0;
                                let mut checkpoint = || {
                                    calls += 1;
                                    calls == stop_at
                                };
                                let mut budget = ComparisonBudget {
                                    remaining: maximum,
                                    next_check: maximum.saturating_sub(gap),
                                    checkpoint: Some(&mut checkpoint),
                                };
                                let mut mismatches = 0;
                                let result = match (bgra, bulk) {
                                    (false, false) => {
                                        compare_span_mode::<false, true, false, false>(
                                            &expected,
                                            &actual,
                                            ColorTolerance::EXACT,
                                            allowed,
                                            &mut mismatches,
                                            &mut budget,
                                        )
                                    }
                                    (false, true) => compare_span_mode::<false, true, false, true>(
                                        &expected,
                                        &actual,
                                        ColorTolerance::EXACT,
                                        allowed,
                                        &mut mismatches,
                                        &mut budget,
                                    ),
                                    (true, false) => compare_span_mode::<true, true, false, false>(
                                        &expected,
                                        &actual,
                                        ColorTolerance::EXACT,
                                        allowed,
                                        &mut mismatches,
                                        &mut budget,
                                    ),
                                    (true, true) => compare_span_mode::<true, true, false, true>(
                                        &expected,
                                        &actual,
                                        ColorTolerance::EXACT,
                                        allowed,
                                        &mut mismatches,
                                        &mut budget,
                                    ),
                                };
                                outputs.push((
                                    result,
                                    budget.remaining,
                                    budget.next_check,
                                    mismatches,
                                    calls,
                                ));
                            }
                            assert_eq!(outputs[0], outputs[1]);
                        }
                    }
                }
            }
        }
    }
}

#[test]
#[ignore = "CPU-only alternating word comparison trial; not device or full-search latency"]
fn benchmark_word_comparison_blocks() {
    for bgra in [false, true] {
        for stride in [0, 5, 17] {
            let expected: Vec<_> = (0..200 * 122).map(synthetic_color).collect();
            let actual: Vec<_> = expected
                .iter()
                .enumerate()
                .flat_map(|(n, color)| {
                    let mut c = *color;
                    if stride != 0 && n % stride == 0 {
                        c[1] ^= 1;
                    }
                    if !bgra {
                        c.swap(0, 2);
                    }
                    c
                })
                .collect();
            let mut times = [Vec::new(), Vec::new()];
            for iteration in 0..110 {
                for variant in [iteration % 2, 1 - iteration % 2] {
                    let start = std::time::Instant::now();
                    let mut budget = ComparisonBudget {
                        remaining: 1_000_000_000,
                        next_check: 1_000_000_000,
                        checkpoint: None,
                    };
                    for _ in 0..100 {
                        let mut mismatches = 0;
                        for (row, bytes) in expected.chunks_exact(200).zip(actual.chunks_exact(800))
                        {
                            let result = match (bgra, variant) {
                                (false, 0) => compare_span_mode::<false, true, false, false>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (false, _) => compare_span_mode::<false, true, false, true>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (true, 0) => compare_span_mode::<true, true, false, false>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                                (true, _) => compare_span_mode::<true, true, false, true>(
                                    row,
                                    bytes,
                                    ColorTolerance::EXACT,
                                    2440,
                                    &mut mismatches,
                                    &mut budget,
                                ),
                            }
                            .unwrap();
                            std::hint::black_box((result, mismatches));
                            if !result {
                                break;
                            }
                        }
                    }
                    std::hint::black_box(budget.remaining);
                    if iteration >= 10 {
                        times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
                    }
                }
            }
            for (name, mut samples) in ["existing", "word32"].into_iter().zip(times) {
                samples.sort_by(f64::total_cmp);
                println!("bgra={bgra} mismatchStride={stride} {name}: P50={:.4} P95={:.4} P99={:.4}ms; 100 candidates, template=200x122, similarity=900, tolerance=0", samples[49], samples[94], samples[98]);
            }
        }
    }
}
