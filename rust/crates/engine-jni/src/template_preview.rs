//! Bounded pure-pixel preview; no Lua VM or script-owned resource is touched.
use pixel_vision::{
    ColorTolerance, ImageView, PixelFormat, PixelRect, SearchOptions, TemplateOptions,
};

#[cfg(test)]
pub(crate) fn match_template(
    screen: ImageView<'_>,
    template: ImageView<'_>,
    tolerance: i32,
    similarity: i32,
) -> [i32; 4] {
    match_template_controlled(screen, template, tolerance, similarity, &mut || false)
}

#[cfg(test)]
pub(crate) fn match_template_controlled(
    screen: ImageView<'_>,
    template: ImageView<'_>,
    tolerance: i32,
    similarity: i32,
    cancelled: &mut dyn FnMut() -> bool,
) -> [i32; 4] {
    match_template_configured(screen, template, tolerance, similarity, 0, cancelled)
}

pub(crate) fn match_template_configured(
    screen: ImageView<'_>,
    template: ImageView<'_>,
    tolerance: i32,
    similarity: i32,
    direction: i32,
    cancelled: &mut dyn FnMut() -> bool,
) -> [i32; 4] {
    if cancelled() {
        return [6, -1, -1, 0];
    }
    let order = match direction {
        0 => pixel_vision::SearchOrder::TopLeftToBottomRight,
        1 => pixel_vision::SearchOrder::TopRightToBottomLeft,
        2 => pixel_vision::SearchOrder::BottomLeftToTopRight,
        3 => pixel_vision::SearchOrder::BottomRightToTopLeft,
        4 => pixel_vision::SearchOrder::CenterOut,
        _ => return [2, -1, -1, 0],
    };
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
            order,
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
    let Ok(template) = pixel_vision::PreparedTemplate::prepare(template) else {
        return [2, -1, -1, 0];
    };
    let mut remaining = options.search.max_pixel_comparisons;
    match pixel_vision::find_prepared_template_controlled(
        screen,
        &template,
        options,
        &mut remaining,
        cancelled,
    ) {
        Ok(Some(found)) => [
            0,
            i32::try_from(found.origin.x).unwrap_or(-1),
            i32::try_from(found.origin.y).unwrap_or(-1),
            i32::from(found.matched_permille),
        ],
        Ok(None) => [1, -1, -1, 0],
        Err(pixel_vision::VisionError::ComparisonBudgetExceeded) => [5, -1, -1, 0],
        Err(pixel_vision::VisionError::Cancelled) => [6, -1, -1, 0],
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
    fn configured_preview_uses_all_five_modern_orders_and_rejects_unknown_direction() {
        let pixels = [10, 20, 30, 255].repeat(9);
        let expected = [(0, 0), (2, 0), (0, 2), (2, 2), (1, 1)];
        for (direction, (x, y)) in expected.into_iter().enumerate() {
            assert_eq!(
                super::match_template_configured(
                    image(3, 3, &pixels),
                    image(1, 1, &pixels[..4]),
                    0,
                    1000,
                    i32::try_from(direction).unwrap(),
                    &mut || false,
                ),
                [0, x, y, 1000]
            );
        }
        for direction in [-1, 5] {
            assert_eq!(
                super::match_template_configured(
                    image(3, 3, &pixels),
                    image(1, 1, &pixels[..4]),
                    0,
                    1000,
                    direction,
                    &mut || false,
                )[0],
                2
            );
        }
        assert_eq!(
            super::match_template_configured(
                image(3, 3, &pixels),
                image(1, 1, &pixels[..4]),
                0,
                1000,
                4,
                &mut || true,
            )[0],
            6
        );
    }
    #[test]
    fn pixel_preview_cooperates_with_cancel_before_and_during_matching() {
        let pixels = [255, 0, 0, 255];
        assert_eq!(
            super::match_template_controlled(
                image(1, 1, &pixels),
                image(1, 1, &pixels),
                0,
                1000,
                &mut || true
            )[0],
            6
        );
        let mut checks = 0;
        assert_eq!(
            super::match_template_controlled(
                image(1, 1, &pixels),
                image(1, 1, &pixels),
                0,
                1000,
                &mut || {
                    checks += 1;
                    checks > 1
                }
            )[0],
            6
        );
    }
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
