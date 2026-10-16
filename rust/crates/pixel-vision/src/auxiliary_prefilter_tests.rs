use super::*;

fn view(bytes: &[u8], width: u32, height: u32, format: PixelFormat) -> ImageView<'_> {
    ImageView {
        pixels: bytes,
        width,
        height,
        row_stride: width as usize * 4,
        format,
    }
}

fn options() -> TemplateOptions {
    TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 7,
                top: 9,
                right: 173,
                bottom: 136,
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

fn texture(length: usize, wrong: bool) -> Vec<u8> {
    (0..length)
        .flat_map(|n| {
            if n % 20 < 12 {
                [255; 4]
            } else if n % 20 < 17 {
                if wrong {
                    [200, 0, 0, 255]
                } else {
                    [0, 0, 0, 255]
                }
            } else {
                [0, 0, 200, 255]
            }
        })
        .collect()
}

fn place(screen: &mut [u8], width: usize, patch: &[u8], w: usize, h: usize, x: usize, y: usize) {
    for row in 0..h {
        screen[((y + row) * width + x) * 4..((y + row) * width + x + w) * 4]
            .copy_from_slice(&patch[row * w * 4..(row + 1) * w * 4]);
    }
}

#[test]
#[allow(clippy::too_many_lines)]
fn auxiliary_and_regions_preserve_exhaustive_scores_and_all_search_orders() {
    for ignore in [false, true] {
        let mut patch = texture(32 * 24, false);
        if ignore {
            for pixel in patch.chunks_exact_mut(4).step_by(17) {
                pixel[3] = 0;
            }
        }
        let prepared =
            PreparedTemplate::prepare(view(&patch, 32, 24, PixelFormat::Rgba8888)).unwrap();
        let mut pixels = texture(180 * 140, true);
        place(&mut pixels, 180, &patch, 32, 24, 43, 52);
        for format in [PixelFormat::Rgba8888, PixelFormat::Bgra8888] {
            let mut bytes = pixels.clone();
            if format == PixelFormat::Bgra8888 {
                for pixel in bytes.chunks_exact_mut(4) {
                    pixel.swap(0, 2);
                }
            }
            let screen = view(&bytes, 180, 140, format);
            for threshold in [500, 850, 900, 950, 1000] {
                let opts = TemplateOptions {
                    ignore_transparent_template_pixels: ignore,
                    minimum_match_permille: threshold,
                    ..options()
                };
                let filter = MajorityIntegral::build(
                    screen,
                    &prepared,
                    opts,
                    &mut ComparisonBudget::new(1_000_000_000),
                )
                .unwrap();
                if threshold == 900 {
                    assert!(filter.as_ref().unwrap().auxiliary.is_some());
                }
                for order in [
                    SearchOrder::TopLeftToBottomRight,
                    SearchOrder::BottomLeftToTopRight,
                    SearchOrder::BottomRightToTopLeft,
                    SearchOrder::TopRightToBottomLeft,
                    SearchOrder::CenterOut,
                ] {
                    let search = SearchOptions {
                        roi: PixelRect {
                            right: opts.search.roi.right - 31,
                            bottom: opts.search.roi.bottom - 23,
                            ..opts.search.roi
                        },
                        order,
                        step_x: 1,
                        step_y: 1,
                        ..opts.search
                    };
                    let mut expected = Vec::new();
                    for origin in SearchPoints::new(search) {
                        let score = compare_template_reference(
                            screen,
                            &prepared,
                            origin,
                            opts,
                            &mut ComparisonBudget::new(1_000_000_000),
                        )
                        .unwrap();
                        if score >= threshold {
                            assert!(!filter.as_ref().is_some_and(|f| f.rejects(origin)));
                            expected.push(TemplateMatch {
                                origin,
                                matched_permille: score,
                            });
                            if expected.len() == 65 {
                                break;
                            }
                        }
                    }
                    let opts = TemplateOptions {
                        search: SearchOptions {
                            order,
                            step_x: 1,
                            step_y: 1,
                            ..opts.search
                        },
                        ..opts
                    };
                    assert_eq!(
                        find_prepared_templates_controlled(
                            screen,
                            &prepared,
                            opts,
                            65,
                            &mut 1_000_000_000,
                            &mut || false
                        )
                        .unwrap(),
                        expected
                    );
                    assert_eq!(
                        find_prepared_template(screen, &prepared, opts).unwrap(),
                        expected.first().copied()
                    );
                }
            }
        }
    }
}

#[test]
fn auxiliary_is_optional_bounded_and_disabled_for_tolerance() {
    let pixels = texture(180 * 140, true);
    let patch = texture(32 * 24, false);
    let prepared = PreparedTemplate::prepare(view(&patch, 32, 24, PixelFormat::Rgba8888)).unwrap();
    let screen = view(&pixels, 180, 140, PixelFormat::Rgba8888);
    let opts = options();
    let exact = MajorityIntegral::build(
        screen,
        &prepared,
        opts,
        &mut ComparisonBudget::new(1_000_000_000),
    )
    .unwrap()
    .unwrap();
    let total = exact.counts.len() + exact.auxiliary.as_ref().unwrap().counts.len();
    assert!(total * std::mem::size_of::<u32>() <= 8 * 1024 * 1024);
    assert!(exact.regions.len() <= 4);
    let tolerant = TemplateOptions {
        tolerance: ColorTolerance {
            red: 1,
            green: 3,
            blue: 7,
            alpha: 255,
        },
        ..opts
    };
    let filter = MajorityIntegral::build(
        screen,
        &prepared,
        tolerant,
        &mut ComparisonBudget::new(1_000_000_000),
    )
    .unwrap()
    .unwrap();
    assert!(filter.auxiliary.is_none());
    assert!(filter.regions.is_empty());
    let low_budget = MajorityIntegral::build(
        screen,
        &prepared,
        opts,
        &mut ComparisonBudget::new(180 * 140),
    )
    .unwrap()
    .unwrap();
    assert!(low_budget.auxiliary.is_none());
    let mut calls = 0;
    let mut cancel = || {
        calls += 1;
        calls == 2
    };
    let mut budget = ComparisonBudget {
        remaining: 1_000_000_000,
        next_check: 1_000_000_000,
        checkpoint: Some(&mut cancel),
    };
    assert!(matches!(
        MajorityIntegral::build(screen, &prepared, opts, &mut budget),
        Err(VisionError::Cancelled)
    ));
    assert!(budget.remaining < 1_000_000_000);
    assert!(matches!(
        MajorityIntegral::build(screen, &prepared, opts, &mut ComparisonBudget::new(100)),
        Err(VisionError::ComparisonBudgetExceeded)
    ));
}

#[test]
fn first_candidate_short_circuits_setup_and_memory_limit_disables_only_auxiliary() {
    let patch = texture(32 * 24, false);
    let prepared = PreparedTemplate::prepare(view(&patch, 32, 24, PixelFormat::Rgba8888)).unwrap();
    let mut pixels = texture(180 * 140, true);
    place(&mut pixels, 180, &patch, 32, 24, 7, 9);
    let mut metrics = TemplateSearchMetrics::default();
    let result = find_prepared_template_observed(
        view(&pixels, 180, 140, PixelFormat::Rgba8888),
        &prepared,
        options(),
        &mut 1_000_000_000,
        &mut || false,
        Some(&mut metrics),
    )
    .unwrap()
    .unwrap();
    assert_eq!(result.origin, PixelPoint { x: 7, y: 9 });
    assert_eq!(metrics.prefilters_built, 0);
    assert_eq!(metrics.candidates_visited, 1);
    let large = [255; 4].repeat(1500 * 1000);
    let opts = TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: 1500,
                bottom: 1000,
            },
            ..options().search
        },
        ..options()
    };
    let filter = MajorityIntegral::build(
        view(&large, 1500, 1000, PixelFormat::Rgba8888),
        &prepared,
        opts,
        &mut ComparisonBudget::new(1_000_000_000),
    )
    .unwrap()
    .unwrap();
    assert!(filter.auxiliary.is_none());
    assert!(filter.counts.len() * std::mem::size_of::<u32>() <= 8 * 1024 * 1024);
}

#[test]
fn spatial_conditions_reject_rearranged_colors_that_global_histograms_accept() {
    let mut patch = [255; 4].repeat(32 * 24);
    for pixel in patch.chunks_exact_mut(4).take(200) {
        pixel.copy_from_slice(&[0, 0, 0, 255]);
    }
    let prepared = PreparedTemplate::prepare(view(&patch, 32, 24, PixelFormat::Rgba8888)).unwrap();
    let mut reversed = patch.clone();
    for y in 0..24 {
        reversed[y * 32 * 4..(y + 1) * 32 * 4]
            .copy_from_slice(&patch[(23 - y) * 32 * 4..(24 - y) * 32 * 4]);
    }
    let mut pixels = [255; 4].repeat(180 * 140);
    place(&mut pixels, 180, &reversed, 32, 24, 7, 9);
    let mut filter = MajorityIntegral::build(
        view(&pixels, 180, 140, PixelFormat::Rgba8888),
        &prepared,
        options(),
        &mut ComparisonBudget::new(1_000_000_000),
    )
    .unwrap()
    .unwrap();
    let origin = PixelPoint { x: 7, y: 9 };
    assert!(filter.rejects(origin));
    filter.regions.clear();
    assert!(!filter.rejects(origin));
}

#[test]
#[ignore = "synthetic alternating primary-vs-additional prefilter benchmark; no private frames"]
fn benchmark_additional_prefilter_same_inputs() {
    let template = texture(200 * 122, false);
    let prepared =
        PreparedTemplate::prepare(view(&template, 200, 122, PixelFormat::Rgba8888)).unwrap();
    let opts = TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 149,
                top: 407,
                right: 546,
                bottom: 879,
            },
            ..options().search
        },
        ..options()
    };
    for scenario in ["missing-wrong-color", "found-late", "found-first"] {
        let mut pixels = texture(720 * 1280, true);
        if scenario != "missing-wrong-color" {
            let (left, top) = if scenario == "found-first" {
                (149, 407)
            } else {
                (240, 600)
            };
            place(&mut pixels, 720, &template, 200, 122, left, top);
        }
        let screen = view(&pixels, 720, 1280, PixelFormat::Rgba8888);
        let mut legacy = prepared.clone();
        legacy.additional_all = AdditionalTemplateFilter::default();
        legacy.additional_opaque = AdditionalTemplateFilter::default();
        let mut times = [Vec::new(), Vec::new()];
        let mut work = [TemplateSearchMetrics::default(); 2];
        for iteration in 0..100 {
            let mut results = [None, None];
            for variant in [iteration % 2, 1 - iteration % 2] {
                let start = std::time::Instant::now();
                let mut metrics = TemplateSearchMetrics::default();
                results[variant] = find_prepared_template_observed(
                    screen,
                    if variant == 0 { &legacy } else { &prepared },
                    opts,
                    &mut 1_000_000_000,
                    &mut || false,
                    Some(&mut metrics),
                )
                .unwrap();
                times[variant].push(start.elapsed().as_secs_f64() * 1000.0);
                work[variant] = metrics;
            }
            assert_eq!(results[0], results[1]);
        }
        for (variant, mut values) in times.into_iter().enumerate() {
            values.sort_by(f64::total_cmp);
            println!("{scenario}-{}: P50={:.3} P95={:.3} P99={:.3}ms candidates={} rejected={} comparisons={}", if variant == 0 { "primary" } else { "additional" }, values[49], values[94], values[98], work[variant].candidates_visited, work[variant].prefilter_rejected, work[variant].comparison_pixels);
        }
    }
}
