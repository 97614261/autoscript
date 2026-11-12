use super::*;
use crate::{Color, ColorTolerance, PixelFormat, PreparedTemplate, SearchOrder, TemplateOptions};

fn image(bytes: &[u8], width: u32, height: u32) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: width as usize * 4,
        format: PixelFormat::Rgba8888,
        pixels: bytes,
    }
}
fn options() -> SearchOptions {
    SearchOptions {
        roi: PixelRect {
            left: 0,
            top: 0,
            right: 4,
            bottom: 4,
        },
        step_x: 1,
        step_y: 1,
        order: SearchOrder::TopLeftToBottomRight,
        max_pixel_comparisons: 10_000,
    }
}
fn white() -> ColorSpec {
    ColorSpec {
        color: Color {
            red: 255,
            green: 255,
            blue: 255,
            alpha: 255,
        },
        tolerance: ColorTolerance::EXACT,
    }
}

#[test]
fn polygon_pixel_centres_match_mask_golden_in_both_windings() {
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
            let region = PolygonRegion::prepare(&points).unwrap();
            let bits: String = (0..4)
                .flat_map(|y| (0..4).map(move |x| (x, y)))
                .map(|(x, y)| if region.contains(x, y) { '1' } else { '0' })
                .collect();
            assert_eq!(bits, golden);
            assert!(!region.contains(0, u32::MAX));
        }
    }
}

#[test]
fn polygon_rejects_crossings_touching_retraced_edges_and_bad_coordinates() {
    for points in [
        vec![[0, 0], [4, 4], [0, 4], [4, 0]],
        vec![[0, 0], [4, 0], [2, 0], [0, 4]],
        vec![[0, 0], [4, 0], [0, 0], [0, 4]],
        vec![[0, 0], [1, 1], [2, 2]],
        vec![[0, 0], [4097, 0], [0, 4]],
        vec![[0, 0], [4, 0]],
        vec![[0, 0], [4, 0], [4, 4], [2, 0], [0, 4]],
    ] {
        assert!(PolygonRegion::prepare(&points).is_err(), "{points:?}");
    }
    assert!(PolygonRegion::prepare(&[[0, 0], [2, 0], [4, 0], [4, 4], [0, 4]]).is_ok());
}

#[test]
fn polygon_color_filter_runs_before_result_limit_and_does_not_double_count_palette() {
    let region = PolygonRegion::prepare(&[[2, 0], [4, 0], [4, 4], [0, 4], [0, 2], [2, 2]]).unwrap();
    let bytes = [255; 64];
    let (count, points) = search_colors(
        image(&bytes, 4, 4),
        &[white(), white()],
        options(),
        &region,
        ColorSelection::All(2),
        &mut || false,
    )
    .unwrap();
    assert_eq!(count, 2);
    assert_eq!(
        points,
        vec![PixelPoint { x: 2, y: 0 }, PixelPoint { x: 3, y: 0 }]
    );
    assert_eq!(
        search_colors(
            image(&bytes, 4, 4),
            &[white(), white()],
            options(),
            &region,
            ColorSelection::Count(0),
            &mut || false
        )
        .unwrap(),
        (12, vec![])
    );
    assert_eq!(
        search_colors(
            image(&bytes, 4, 4),
            &[white()],
            options(),
            &region,
            ColorSelection::Count(3),
            &mut || false
        )
        .unwrap()
        .0,
        3
    );
}

#[test]
fn polygon_multicolor_checks_every_offset_and_continues_after_excluded_samples() {
    let region = PolygonRegion::prepare(&[
        [0, 0],
        [1, 0],
        [1, 3],
        [3, 3],
        [3, 0],
        [4, 0],
        [4, 4],
        [0, 4],
    ])
    .unwrap();
    assert!(
        region.contains(0, 0)
            && region.contains(3, 0)
            && region.contains(0, 3)
            && region.contains(3, 3)
    );
    assert!(!region.contains_rect(PixelPoint { x: 0, y: 0 }, 4, 4));
    let sample = PatternSample {
        offset_x: 1,
        offset_y: 0,
        color: white().color,
        tolerance: ColorTolerance::EXACT,
    };
    assert_eq!(
        search_pattern(
            image(&[255; 64], 4, 4),
            &[sample],
            options(),
            &region,
            &mut || false
        )
        .unwrap(),
        Some(PixelPoint { x: 0, y: 3 })
    );
}

#[test]
fn polygon_template_search_preserves_all_five_orders_and_filters_before_limits() {
    let region = PolygonRegion::prepare(&[[0, 4], [4, 0], [4, 4]]).unwrap();
    let template = PreparedTemplate::prepare(image(&[255; 16], 2, 2)).unwrap();
    for order in [
        SearchOrder::TopLeftToBottomRight,
        SearchOrder::BottomLeftToTopRight,
        SearchOrder::BottomRightToTopLeft,
        SearchOrder::TopRightToBottomLeft,
        SearchOrder::CenterOut,
    ] {
        let search = SearchOptions { order, ..options() };
        let config = TemplateOptions {
            search,
            tolerance: ColorTolerance::EXACT,
            minimum_match_permille: 1000,
            ignore_transparent_template_pixels: true,
        };
        let expected = SearchPoints::new(SearchOptions {
            roi: PixelRect {
                right: 3,
                bottom: 3,
                ..search.roi
            },
            ..search
        })
        .filter(|p| region.contains_rect(*p, 2, 2))
        .collect::<Vec<_>>();
        assert!(!expected.is_empty());
        let mut budget = 10_000;
        let first = crate::find_prepared_template_in_region_observed(
            image(&[255; 64], 4, 4),
            &template,
            config,
            &mut budget,
            &mut || false,
            None,
            Some(&region),
        )
        .unwrap()
        .unwrap();
        assert_eq!(first.origin, expected[0]);
        let all = crate::find_prepared_templates_in_region_observed(
            image(&[255; 64], 4, 4),
            &template,
            config,
            65,
            &mut budget,
            &mut || false,
            None,
            Some(&region),
        )
        .unwrap();
        assert_eq!(
            all.into_iter().map(|m| m.origin).collect::<Vec<_>>(),
            expected
        );
    }
}

#[test]
fn polygon_template_origin_projection_matches_exhaustive_containment() {
    for points in [
        vec![[0, 4], [4, 0], [4, 4]],
        vec![[0, 0], [4, 0], [4, 1], [1, 1], [1, 4], [0, 4]],
        vec![
            [0, 0],
            [1, 0],
            [1, 3],
            [3, 3],
            [3, 0],
            [4, 0],
            [4, 4],
            [0, 4],
        ],
    ] {
        let region = PolygonRegion::prepare(&points).unwrap();
        for width in 1..=5 {
            for height in 1..=5 {
                let projected = region
                    .template_origins(width, height, &mut |_| Ok(()))
                    .unwrap();
                for x in 0..=4 {
                    for y in 0..=4 {
                        assert_eq!(
                            projected.contains(x, y),
                            region.contains_rect(PixelPoint { x, y }, width, height),
                            "{points:?} template {width}x{height} origin {x},{y}"
                        );
                    }
                }
            }
        }
    }
    let region = PolygonRegion::prepare(&[[0, 0], [128, 0], [128, 128], [0, 128]]).unwrap();
    let mut work = 0;
    let projected = region
        .template_origins(64, 64, &mut |n| {
            work += n;
            Ok(())
        })
        .unwrap();
    assert!(projected.contains(64, 64));
    assert!(!projected.contains(65, 64));
    assert!(
        work < 20_000,
        "geometry preparation repeated pixel work: {work}"
    );
}

#[test]
fn polygon_searches_keep_cancellation_roi_and_budget_guards() {
    let region = PolygonRegion::prepare(&[[0, 0], [4, 0], [4, 4], [0, 4]]).unwrap();
    let bytes = [255; 64];
    assert_eq!(
        search_colors(
            image(&bytes, 4, 4),
            &[white()],
            options(),
            &region,
            ColorSelection::First,
            &mut || true
        ),
        Err(VisionError::Cancelled)
    );
    assert_eq!(
        search_colors(
            image(&bytes, 4, 4),
            &[white()],
            SearchOptions {
                max_pixel_comparisons: 1,
                ..options()
            },
            &region,
            ColorSelection::Count(0),
            &mut || false
        ),
        Err(VisionError::ComparisonBudgetExceeded)
    );
    let invalid = SearchOptions {
        roi: PixelRect {
            right: 3,
            ..options().roi
        },
        ..options()
    };
    assert_eq!(
        search_colors(
            image(&bytes, 4, 4),
            &[white()],
            invalid,
            &region,
            ColorSelection::First,
            &mut || false
        ),
        Err(VisionError::InvalidRoi)
    );
    let template = PreparedTemplate::prepare(image(&[255; 16], 2, 2)).unwrap();
    let config = TemplateOptions {
        search: options(),
        tolerance: ColorTolerance::EXACT,
        minimum_match_permille: 1000,
        ignore_transparent_template_pixels: true,
    };
    let mut budget = 10_000;
    assert_eq!(
        crate::find_prepared_template_in_region_observed(
            image(&bytes, 4, 4),
            &template,
            config,
            &mut budget,
            &mut || true,
            None,
            Some(&region)
        ),
        Err(VisionError::Cancelled)
    );
    let mut budget = 1;
    assert_eq!(
        crate::find_prepared_template_in_region_observed(
            image(&bytes, 4, 4),
            &template,
            config,
            &mut budget,
            &mut || false,
            None,
            Some(&region)
        ),
        Err(VisionError::ComparisonBudgetExceeded)
    );
}
