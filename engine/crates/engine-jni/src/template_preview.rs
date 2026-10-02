//! Bounded pure-pixel preview; no Lua VM or script-owned resource is touched.
use pixel_vision::{
    ColorTolerance, ImageView, PixelFormat, PixelRect, SearchOptions, TemplateOptions,
};

pub(crate) fn match_template(
    screen: ImageView<'_>,
    template: ImageView<'_>,
    tolerance: i32,
    similarity: i32,
) -> [i32; 4] {
    let (Ok(tolerance), Ok(similarity)) = (u8::try_from(tolerance), u16::try_from(similarity))
    else {
        return [2, -1, -1, 0];
    };
    if similarity > 1000
        || screen.width > 4096
        || screen.height > 4096
        || u64::from(screen.width) * u64::from(screen.height) > 4_194_304
        || template.width > screen.width
        || template.height > screen.height
    {
        return [2, -1, -1, 0];
    }
    let options = TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: screen.width,
                bottom: screen.height,
            },
            step_x: 1,
            step_y: 1,
            order: pixel_vision::SearchOrder::default(),
            max_pixel_comparisons: 10_000_000,
        },
        tolerance: ColorTolerance {
            red: tolerance,
            green: tolerance,
            blue: tolerance,
            alpha: 255,
        },
        minimum_match_permille: similarity,
        ignore_transparent_template_pixels: true,
    };
    match pixel_vision::find_template(screen, template, options) {
        Ok(Some(found)) => [
            0,
            i32::try_from(found.origin.x).unwrap_or(-1),
            i32::try_from(found.origin.y).unwrap_or(-1),
            i32::from(found.matched_permille),
        ],
        Ok(None) => [1, -1, -1, 0],
        Err(pixel_vision::VisionError::ComparisonBudgetExceeded) => [5, -1, -1, 0],
        Err(_) => [2, -1, -1, 0],
    }
}

pub(crate) fn image(width: u32, height: u32, pixels: &[u8]) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: width as usize * 4,
        format: PixelFormat::Rgba8888,
        pixels,
    }
}

#[cfg(test)]
mod tests {
    use super::{image, match_template};
    #[test]
    fn preview_uses_real_matcher_and_rejects_malformed_images() {
        let screen = [255, 0, 0, 255, 0, 255, 0, 255];
        let template = [0, 255, 0, 255];
        assert_eq!(
            match_template(image(2, 1, &screen), image(1, 1, &template), 0, 1000),
            [0, 1, 0, 1000]
        );
        assert_eq!(
            match_template(image(2, 1, &screen[..1]), image(1, 1, &template), 0, 1000)[0],
            2
        );
        assert_eq!(
            match_template(image(2, 1, &screen), image(1, 1, &template), 256, 1000)[0],
            2
        );
    }
}
