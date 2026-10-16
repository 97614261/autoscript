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
