use pixel_vision::{
    find_prepared_template, ColorTolerance, ImageView, PixelFormat, PixelRect, PreparedTemplate,
    SearchOptions, SearchOrder, TemplateOptions,
};
use std::hint::black_box;
use std::time::{Duration, Instant};

const SCREEN_WIDTH: u32 = 1_920;
const SCREEN_HEIGHT: u32 = 1_080;
const TEMPLATE_WIDTH: u32 = 32;
const TEMPLATE_HEIGHT: u32 = 32;
const MATCH_X: u32 = 1_731;
const MATCH_Y: u32 = 947;

fn main() {
    let iterations = std::env::args()
        .nth(1)
        .and_then(|value| value.parse::<usize>().ok())
        .filter(|value| *value > 0)
        .unwrap_or(30);
    let mut seed = 0x0123_4567_89ab_cdef_u64;
    let mut screen = vec![0_u8; byte_len(SCREEN_WIDTH, SCREEN_HEIGHT)];
    fill_pixels(&mut screen, &mut seed);
    let mut template = vec![0_u8; byte_len(TEMPLATE_WIDTH, TEMPLATE_HEIGHT)];
    fill_pixels(&mut template, &mut seed);
    copy_template_into_screen(&template, &mut screen);

    let screen_view = view(&screen, SCREEN_WIDTH, SCREEN_HEIGHT);
    let prepared = PreparedTemplate::prepare(view(&template, TEMPLATE_WIDTH, TEMPLATE_HEIGHT))
        .expect("valid deterministic template");
    let options = TemplateOptions {
        search: SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: SCREEN_WIDTH,
                bottom: SCREEN_HEIGHT,
            },
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 100_000_000,
        },
        tolerance: ColorTolerance::EXACT,
        minimum_match_permille: 1_000,
        ignore_transparent_template_pixels: true,
    };

    for _ in 0..5 {
        let result = find_prepared_template(screen_view, &prepared, options)
            .expect("warm-up search")
            .expect("warm-up match");
        black_box(result);
    }
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let started = Instant::now();
        let result = find_prepared_template(screen_view, &prepared, options)
            .expect("benchmark search")
            .expect("benchmark match");
        samples.push(started.elapsed());
        assert_eq!((result.origin.x, result.origin.y), (MATCH_X, MATCH_Y));
        black_box(result);
    }
    samples.sort_unstable();
    println!(
        "pixel-vision scalar template benchmark: arch={}, screen={}x{}, template={}x{}, iterations={}, order=top-left, step=1, threads=1",
        std::env::consts::ARCH,
        SCREEN_WIDTH,
        SCREEN_HEIGHT,
        TEMPLATE_WIDTH,
        TEMPLATE_HEIGHT,
        iterations,
    );
    println!(
        "P50={:.3}ms P95={:.3}ms P99={:.3}ms",
        milliseconds(percentile(&samples, 50)),
        milliseconds(percentile(&samples, 95)),
        milliseconds(percentile(&samples, 99)),
    );
}

fn byte_len(width: u32, height: u32) -> usize {
    usize::try_from(u64::from(width) * u64::from(height) * 4).expect("benchmark dimensions")
}

fn fill_pixels(pixels: &mut [u8], seed: &mut u64) {
    for pixel in pixels.chunks_exact_mut(4) {
        *seed = seed.wrapping_mul(6_364_136_223_846_793_005).wrapping_add(1);
        let bytes = seed.to_le_bytes();
        pixel.copy_from_slice(&[bytes[0], bytes[2], bytes[4], 255]);
    }
}

fn copy_template_into_screen(template: &[u8], screen: &mut [u8]) {
    let screen_stride = usize::try_from(SCREEN_WIDTH).expect("width") * 4;
    let template_stride = usize::try_from(TEMPLATE_WIDTH).expect("width") * 4;
    let match_x = usize::try_from(MATCH_X).expect("x") * 4;
    for row in 0..usize::try_from(TEMPLATE_HEIGHT).expect("height") {
        let source = row * template_stride;
        let target = (usize::try_from(MATCH_Y).expect("y") + row) * screen_stride + match_x;
        screen[target..target + template_stride]
            .copy_from_slice(&template[source..source + template_stride]);
    }
}

fn view(pixels: &[u8], width: u32, height: u32) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: usize::try_from(width).expect("width") * 4,
        format: PixelFormat::Rgba8888,
        pixels,
    }
}

fn percentile(samples: &[Duration], percentile: usize) -> Duration {
    let index = samples
        .len()
        .saturating_mul(percentile)
        .div_ceil(100)
        .saturating_sub(1);
    samples[index.min(samples.len() - 1)]
}

fn milliseconds(duration: Duration) -> f64 {
    duration.as_secs_f64() * 1_000.0
}
