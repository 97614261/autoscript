use super::{
    compare_fixed_pattern, count_any_color_total, find_all_pattern_matches, parse_color_list,
    parse_fixed_pattern, parse_relative_pattern, ImageView, LegacyParseError, PixelPoint,
    PixelRect, SearchOptions, SearchOrder, VisionError,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LegacySearchDirection {
    BottomLeftToTopRight = 0,
    TopLeftToBottomRight = 1,
    BottomRightToTopLeft = 2,
    TopRightToBottomLeft = 3,
    CenterOut = 4,
}

impl TryFrom<i32> for LegacySearchDirection {
    type Error = LegacyCompatError;

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::BottomLeftToTopRight),
            1 => Ok(Self::TopLeftToBottomRight),
            2 => Ok(Self::BottomRightToTopLeft),
            3 => Ok(Self::TopRightToBottomLeft),
            4 => Ok(Self::CenterOut),
            _ => Err(LegacyCompatError::InvalidDirection(value)),
        }
    }
}

impl From<LegacySearchDirection> for SearchOrder {
    fn from(value: LegacySearchDirection) -> Self {
        match value {
            LegacySearchDirection::BottomLeftToTopRight => Self::BottomLeftToTopRight,
            LegacySearchDirection::TopLeftToBottomRight => Self::TopLeftToBottomRight,
            LegacySearchDirection::BottomRightToTopLeft => Self::BottomRightToTopLeft,
            LegacySearchDirection::TopRightToBottomLeft => Self::TopRightToBottomLeft,
            LegacySearchDirection::CenterOut => Self::CenterOut,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LegacySearchLimits {
    pub step_x: u32,
    pub step_y: u32,
    pub max_pixel_comparisons: u64,
    pub max_results: usize,
}

impl Default for LegacySearchLimits {
    fn default() -> Self {
        Self {
            step_x: 1,
            step_y: 1,
            max_pixel_comparisons: 100_000_000,
            max_results: 256,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LegacyCompatError {
    Parse(LegacyParseError),
    Vision(VisionError),
    InvalidDirection(i32),
    InvalidRectangle,
    InvalidRgbChannel,
}

impl From<LegacyParseError> for LegacyCompatError {
    fn from(value: LegacyParseError) -> Self {
        Self::Parse(value)
    }
}

impl From<VisionError> for LegacyCompatError {
    fn from(value: VisionError) -> Self {
        Self::Vision(value)
    }
}

/// Compatibility implementation of `DuoDianZhaoSe`. Its seven legacy arguments retain their
/// original meaning; the explicit image and safety limits replace hidden global screen state.
///
/// # Errors
///
/// Rejects malformed legacy parameters, invalid geometry/direction, or safety budget exhaustion.
#[allow(clippy::too_many_arguments)]
pub fn duo_dian_zhao_se(
    image: ImageView<'_>,
    left: u32,
    top: u32,
    width: u32,
    height: u32,
    parameters: &str,
    direction: i32,
    minimum_match_percent: u8,
    limits: LegacySearchLimits,
) -> Result<Vec<PixelPoint>, LegacyCompatError> {
    let samples = parse_relative_pattern(parameters)?;
    let options = search_options(left, top, width, height, direction, limits)?;
    Ok(find_all_pattern_matches(
        image,
        &samples,
        minimum_match_percent,
        options,
        limits.max_results,
    )?)
}

/// Compatibility implementation of `DuoDianBiSe` with ceiling percentage semantics.
///
/// # Errors
///
/// Rejects malformed fixed-point parameters, invalid percentages/coordinates, or budget exhaustion.
pub fn duo_dian_bi_se(
    image: ImageView<'_>,
    parameters: &str,
    minimum_match_percent: u8,
    max_pixel_comparisons: u64,
) -> Result<bool, LegacyCompatError> {
    let samples = parse_fixed_pattern(parameters)?;
    Ok(compare_fixed_pattern(
        image,
        &samples,
        minimum_match_percent,
        max_pixel_comparisons,
    )?)
}

/// Compatibility implementation of `GetRectColorNum`.
///
/// # Errors
///
/// Rejects malformed color lists/geometry or budget exhaustion.
#[allow(clippy::too_many_arguments)]
pub fn get_rect_color_num(
    image: ImageView<'_>,
    left: u32,
    top: u32,
    width: u32,
    height: u32,
    parameters: &str,
    limits: LegacySearchLimits,
) -> Result<usize, LegacyCompatError> {
    let colors = parse_color_list(parameters)?;
    let options = search_options(left, top, width, height, 1, limits)?;
    Ok(count_any_color_total(image, &colors, options)?)
}

/// Compatibility implementation of `GetRGBColor`, returning `0xRRGGBB`.
///
/// # Errors
///
/// Rejects channels outside 0 through 255.
pub fn get_rgb_color(red: i32, green: i32, blue: i32) -> Result<u32, LegacyCompatError> {
    let red = u8::try_from(red).map_err(|_| LegacyCompatError::InvalidRgbChannel)?;
    let green = u8::try_from(green).map_err(|_| LegacyCompatError::InvalidRgbChannel)?;
    let blue = u8::try_from(blue).map_err(|_| LegacyCompatError::InvalidRgbChannel)?;
    Ok((u32::from(red) << 16) | (u32::from(green) << 8) | u32::from(blue))
}

fn search_options(
    left: u32,
    top: u32,
    width: u32,
    height: u32,
    direction: i32,
    limits: LegacySearchLimits,
) -> Result<SearchOptions, LegacyCompatError> {
    if width == 0 || height == 0 {
        return Err(LegacyCompatError::InvalidRectangle);
    }
    let right = left
        .checked_add(width)
        .ok_or(LegacyCompatError::InvalidRectangle)?;
    let bottom = top
        .checked_add(height)
        .ok_or(LegacyCompatError::InvalidRectangle)?;
    Ok(SearchOptions {
        roi: PixelRect {
            left,
            top,
            right,
            bottom,
        },
        step_x: limits.step_x,
        step_y: limits.step_y,
        order: LegacySearchDirection::try_from(direction)?.into(),
        max_pixel_comparisons: limits.max_pixel_comparisons,
    })
}

#[cfg(test)]
mod tests {
    use super::{
        duo_dian_bi_se, duo_dian_zhao_se, get_rect_color_num, get_rgb_color, LegacyCompatError,
        LegacySearchLimits,
    };
    use crate::{ImageView, PixelFormat, PixelPoint};

    fn image(pixels: &[u8], width: u32, height: u32) -> ImageView<'_> {
        ImageView {
            width,
            height,
            row_stride: usize::try_from(width).expect("width") * 4,
            format: PixelFormat::Rgba8888,
            pixels,
        }
    }

    #[test]
    fn four_legacy_functions_preserve_documented_semantics() {
        let pixels = [255, 0, 0, 255, 0, 0, 0, 255, 255, 0, 0, 255, 0, 255, 0, 255];
        let view = image(&pixels, 2, 2);
        let found = duo_dian_zhao_se(
            view,
            0,
            0,
            2,
            2,
            "(255,0,0)-(0,0,0)#(1,1)|(0,255,0)-(0,0,0)",
            1,
            100,
            LegacySearchLimits::default(),
        )
        .expect("multi color search");
        assert_eq!(found, vec![PixelPoint { x: 0, y: 0 }]);
        assert!(
            duo_dian_bi_se(view, "(0,0)|(255,0,0)-(0,0,0)#(1,0)|(9,9,9)-(0,0,0)", 50, 2,)
                .expect("fixed compare")
        );
        assert_eq!(
            get_rect_color_num(
                view,
                0,
                0,
                2,
                2,
                "(255,0,0)-(0,0,0)#(0,255,0)-(0,0,0)",
                LegacySearchLimits::default(),
            )
            .expect("region count"),
            3
        );
        assert_eq!(get_rgb_color(0x12, 0x34, 0x56), Ok(0x12_34_56));
    }

    #[test]
    fn five_legacy_directions_and_width_height_roi_are_exact() {
        let pixels = [255, 0, 0, 255].repeat(9);
        let view = image(&pixels, 3, 3);
        let expected = [
            PixelPoint { x: 0, y: 2 },
            PixelPoint { x: 0, y: 0 },
            PixelPoint { x: 2, y: 2 },
            PixelPoint { x: 2, y: 0 },
            PixelPoint { x: 1, y: 1 },
        ];
        for (direction, first) in expected.into_iter().enumerate() {
            let points = duo_dian_zhao_se(
                view,
                0,
                0,
                3,
                3,
                "(255,0,0)-(0,0,0)",
                i32::try_from(direction).expect("small direction"),
                100,
                LegacySearchLimits::default(),
            )
            .expect("direction search");
            assert_eq!(points.first(), Some(&first));
        }

        assert_eq!(
            duo_dian_zhao_se(
                view,
                1,
                1,
                1,
                1,
                "(255,0,0)-(0,0,0)",
                1,
                100,
                LegacySearchLimits::default(),
            ),
            Ok(vec![PixelPoint { x: 1, y: 1 }])
        );
        assert_eq!(
            duo_dian_zhao_se(
                view,
                0,
                0,
                0,
                1,
                "(255,0,0)-(0,0,0)",
                1,
                100,
                LegacySearchLimits::default(),
            ),
            Err(LegacyCompatError::InvalidRectangle)
        );
        assert!(matches!(
            duo_dian_zhao_se(
                view,
                0,
                0,
                3,
                3,
                "(255,0,0)-(0,0,0)",
                5,
                100,
                LegacySearchLimits::default(),
            ),
            Err(LegacyCompatError::InvalidDirection(5))
        ));
    }

    #[test]
    fn legacy_percentage_uses_ceiling_for_ten_fixed_points() {
        let pixels = [
            255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0,
            0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 0, 0, 0, 255, 0, 0, 0, 255,
        ];
        let view = image(&pixels, 10, 1);
        let parameters = (0..10)
            .map(|x| format!("({x},0)|(255,0,0)-(0,0,0)"))
            .collect::<Vec<_>>()
            .join("#");
        assert_eq!(
            duo_dian_bi_se(view, &parameters, 71, 10),
            Ok(true),
            "71 percent requires eight of ten"
        );
        assert_eq!(duo_dian_bi_se(view, &parameters, 80, 10), Ok(true));
        assert_eq!(duo_dian_bi_se(view, &parameters, 81, 10), Ok(false));
    }
}
