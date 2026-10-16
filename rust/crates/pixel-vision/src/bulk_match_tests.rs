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
