//! Alternative RGB colors share one image traversal; overlapping matches are returned once.
use crate::{
    color_matches, get_color, read_pixel, validate_result_limit, validate_search, Color, ColorSpec,
    ColorTolerance, ComparisonBudget, ImageView, PixelPoint, SearchOptions, SearchPoints,
    VisionError,
};

pub const MAX_COLORS: usize = 16;
pub const MAX_ADDITIONAL_BYTES: usize = 164;

/// Builds a palette from a primary color and up to 15 `RRGGBB:tolerance` entries.
///
/// # Errors
/// Rejects malformed, oversized or duplicate colors before image work begins.
pub fn parse(
    primary: u32,
    tolerance: u8,
    additional: &str,
) -> Result<Vec<ColorSpec>, &'static str> {
    if primary > 0xff_ffff || additional.len() > MAX_ADDITIONAL_BYTES {
        return Err("invalid color palette or byte budget");
    }
    let mut colors = vec![spec(primary, tolerance)];
    if additional.is_empty() {
        return Ok(colors);
    }
    for entry in additional.split(';') {
        if colors.len() == MAX_COLORS {
            return Err("color palette exceeds 16 colors");
        }
        let (rgb, tolerance) = entry.split_once(':').ok_or("invalid color entry")?;
        if rgb.len() != 6
            || !rgb.bytes().all(|c| c.is_ascii_hexdigit())
            || tolerance.is_empty()
            || tolerance.len() > 3
            || !tolerance.bytes().all(|c| c.is_ascii_digit())
        {
            return Err("invalid color entry");
        }
        let rgb = u32::from_str_radix(rgb, 16).map_err(|_| "invalid RGB")?;
        let tolerance = tolerance
            .parse::<u8>()
            .map_err(|_| "invalid color tolerance")?;
        let next = spec(rgb, tolerance);
        if colors.iter().any(|old| old.color == next.color) {
            return Err("duplicate color in palette");
        }
        colors.push(next);
    }
    Ok(colors)
}

fn spec(rgb: u32, tolerance: u8) -> ColorSpec {
    ColorSpec {
        color: Color {
            red: (rgb >> 16) as u8,
            green: (rgb >> 8) as u8,
            blue: rgb as u8,
            alpha: 255,
        },
        tolerance: ColorTolerance {
            red: tolerance,
            green: tolerance,
            blue: tolerance,
            alpha: 0,
        },
    }
}

/// # Errors
/// Rejects empty/oversized palettes, malformed images and points outside the image.
pub fn compare_any_color(
    image: ImageView<'_>,
    point: PixelPoint,
    colors: &[ColorSpec],
) -> Result<bool, VisionError> {
    validate_palette(colors)?;
    let actual = get_color(image, point)?;
    Ok(colors
        .iter()
        .any(|spec| color_matches(actual, spec.color, spec.tolerance)))
}

/// # Errors
/// Rejects malformed searches or exhausted per-color comparison budgets.
pub fn find_any_color(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
) -> Result<Option<PixelPoint>, VisionError> {
    Ok(find_all_any_colors(image, colors, options, 1)?
        .into_iter()
        .next())
}

/// # Errors
/// Rejects malformed searches, invalid limits or exhausted per-color comparison budgets.
pub fn find_all_any_colors(
    image: ImageView<'_>,
    colors: &[ColorSpec],
    options: SearchOptions,
    limit: usize,
) -> Result<Vec<PixelPoint>, VisionError> {
    validate_palette(colors)?;
    validate_result_limit(limit)?;
    validate_search(image, options)?;
    let mut budget = ComparisonBudget::new(options.max_pixel_comparisons);
    let mut points = Vec::with_capacity(limit.min(64));
    for point in SearchPoints::new(options) {
        let actual = read_pixel(image, point.x, point.y);
        for spec in colors {
            budget.consume(1)?;
            if color_matches(actual, spec.color, spec.tolerance) {
                points.push(point);
                if points.len() == limit {
                    return Ok(points);
                }
                break;
            }
        }
    }
    Ok(points)
}

fn validate_palette(colors: &[ColorSpec]) -> Result<(), VisionError> {
    if colors.is_empty() || colors.len() > MAX_COLORS {
        return Err(VisionError::EmptyPattern);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{count_any_color, PixelFormat, PixelRect, SearchOrder};

    const PIXELS: [u8; 16] = [
        0, 0, 255, 255, 255, 0, 0, 255, 254, 0, 0, 255, 0, 255, 0, 255,
    ];
    fn image() -> ImageView<'static> {
        ImageView {
            width: 4,
            height: 1,
            row_stride: 16,
            format: PixelFormat::Rgba8888,
            pixels: &PIXELS,
        }
    }
    fn options() -> SearchOptions {
        SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: 4,
                bottom: 1,
            },
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 100,
        }
    }

    #[test]
    fn palette_rejects_bad_duplicate_and_excessive_colors() {
        assert_eq!(parse(0xff0000, 0, "0000ff:8").unwrap().len(), 2);
        for value in [
            "FF0000:1",
            "0000FF:0;0000ff:1",
            "0000FF:256",
            "0000FF:-1",
            "#0000FF:0",
            "0000FF:0;",
            "0000FF: 0",
            "0000FF:0:1",
        ] {
            assert!(parse(0xff0000, 0, value).is_err(), "{value}");
        }
        let max = (1..16)
            .map(|v| format!("{v:06X}:255"))
            .collect::<Vec<_>>()
            .join(";");
        assert_eq!(parse(0, 0, &max).unwrap().len(), 16);
        assert!(parse(0, 0, &format!("{max};FFFFFF:0")).is_err());
        assert!(parse(0x1000000, 0, "").is_err());
    }

    #[test]
    fn palette_matches_any_color_once_in_pixel_order_with_independent_tolerances() {
        let colors = parse(0xff0000, 1, "FE0000:0;0000FF:0").unwrap();
        let points = find_all_any_colors(image(), &colors, options(), 256).unwrap();
        assert_eq!(
            points,
            vec![
                PixelPoint { x: 0, y: 0 },
                PixelPoint { x: 1, y: 0 },
                PixelPoint { x: 2, y: 0 }
            ]
        );
        assert_eq!(
            count_any_color(image(), &colors, options(), 256).unwrap(),
            3
        );
        assert_eq!(count_any_color(image(), &colors, options(), 0).unwrap(), 3);
        assert_eq!(
            find_any_color(image(), &colors, options()).unwrap(),
            Some(points[0])
        );
        assert!(compare_any_color(image(), PixelPoint { x: 2, y: 0 }, &colors).unwrap());
        assert!(!compare_any_color(image(), PixelPoint { x: 3, y: 0 }, &colors).unwrap());
        let mut reversed = options();
        reversed.order = SearchOrder::TopRightToBottomLeft;
        assert_eq!(
            find_any_color(image(), &colors, reversed).unwrap(),
            Some(points[2])
        );
        assert_eq!(
            find_all_any_colors(image(), &colors, options(), 2)
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn palette_work_budget_charges_each_compared_color_and_preserves_roi() {
        let colors = parse(0xff0000, 0, "0000FF:0").unwrap();
        let mut limited = options();
        limited.max_pixel_comparisons = 1;
        assert_eq!(
            find_any_color(image(), &colors, limited),
            Err(VisionError::ComparisonBudgetExceeded)
        );
        assert_eq!(
            count_any_color(image(), &colors, limited, 256),
            Err(VisionError::ComparisonBudgetExceeded)
        );
        limited.max_pixel_comparisons = 2;
        assert_eq!(
            find_any_color(image(), &colors, limited).unwrap(),
            Some(PixelPoint { x: 0, y: 0 })
        );
        limited.roi.left = 3;
        limited.max_pixel_comparisons = 100;
        assert_eq!(find_any_color(image(), &colors, limited).unwrap(), None);
        assert_eq!(
            compare_any_color(image(), PixelPoint { x: 4, y: 0 }, &colors),
            Err(VisionError::PointOutsideImage)
        );
        assert!(find_all_any_colors(image(), &colors, options(), 0).is_err());
    }
}
