use super::*;

struct Fixture {
    pixels: Vec<u8>,
    template: Vec<u8>,
    width: u32,
    height: u32,
    mode: u8,
    roi: PixelRect,
    target: PixelPoint,
}
fn image(bytes: &[u8], width: u32, height: u32) -> ImageView<'_> {
    ImageView {
        width,
        height,
        row_stride: width as usize * 4,
        format: PixelFormat::Rgba8888,
        pixels: bytes,
    }
}
fn fixture(width: u32, height: u32, mode: u8) -> Fixture {
    let original: Vec<_> = (0..80_u32 * 40)
        .flat_map(|i| {
            let n = i.wrapping_mul(104_729) ^ 0x7956_3481;
            let bytes = n.to_le_bytes();
            [bytes[0], bytes[1], bytes[2], 255]
        })
        .collect();
    let mut template = original.clone();
    for (index, pixel) in template.chunks_exact_mut(4).enumerate() {
        if mode == 1 && index % 20 == 0 {
            pixel[..3].copy_from_slice(&[255, 0, 255]);
        }
        if mode == 2 && index % 5 == 0 {
            pixel[3] = 0;
        }
    }
    let mut pixels = [13, 17, 19, 255].repeat(width as usize * height as usize);
    let target = PixelPoint {
        x: width / 2,
        y: height / 2,
    };
    for row in 0..40_usize {
        let offset = ((target.y as usize + row) * width as usize + target.x as usize) * 4;
        pixels[offset..offset + 320].copy_from_slice(&original[row * 320..(row + 1) * 320]);
    }
    let roi = PixelRect {
        left: target.x - 16,
        top: target.y - 16,
        right: target.x + 96,
        bottom: target.y + 56,
    };
    Fixture {
        pixels,
        template,
        width,
        height,
        mode,
        roi,
        target,
    }
}
fn options(fixture: &Fixture) -> TemplateOptions {
    TemplateOptions {
        search: SearchOptions {
            roi: fixture.roi,
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 1_000_000_000,
        },
        tolerance: if fixture.mode == 1 {
            ColorTolerance {
                red: 12,
                green: 12,
                blue: 12,
                alpha: 0,
            }
        } else {
            ColorTolerance::EXACT
        },
        minimum_match_permille: if fixture.mode == 1 { 900 } else { 1000 },
        ignore_transparent_template_pixels: true,
    }
}
fn search(fixture: &Fixture, prepared: &PreparedTemplate) -> TemplateMatch {
    find_prepared_template_observed(
        image(&fixture.pixels, fixture.width, fixture.height),
        prepared,
        options(fixture),
        &mut 1_000_000_000,
        &mut || false,
        None,
    )
    .unwrap()
    .unwrap()
}

#[test]
fn exact_relaxed_and_transparent_matrix_keeps_true_pixel_coordinates_at_three_resolutions() {
    for (width, height) in [(720, 1280), (1080, 1920), (1440, 2560)] {
        for mode in 0..3 {
            let fixture = fixture(width, height, mode);
            let prepared = PreparedTemplate::prepare(image(&fixture.template, 80, 40)).unwrap();
            assert_eq!(search(&fixture, &prepared).origin, fixture.target);
        }
    }
}

#[test]
#[ignore = "explicit expanded CPU baseline; not Android capture/transfer performance"]
fn benchmark_expanded_resolution_threshold_alpha_matrix() {
    for (width, height) in [(720, 1280), (1080, 1920), (1440, 2560)] {
        let cases: Vec<_> = (0..3).map(|mode| fixture(width, height, mode)).collect();
        let mut prepared = Vec::new();
        let mut prepare_us = Vec::new();
        for case in &cases {
            let started = std::time::Instant::now();
            prepared.push(PreparedTemplate::prepare(image(&case.template, 80, 40)).unwrap());
            prepare_us.push(started.elapsed().as_micros());
        }
        let mut samples = vec![Vec::new(); cases.len()];
        let mut first = vec![0; cases.len()];
        for iteration in 0..111 {
            for offset in 0..cases.len() {
                let index = (iteration + offset) % cases.len();
                let case = &cases[index];
                let started = std::time::Instant::now();
                let found = search(case, &prepared[index]);
                let elapsed = started.elapsed().as_micros();
                assert_eq!(found.origin, case.target);
                if iteration == 0 {
                    first[index] = elapsed;
                }
                if iteration >= 11 {
                    samples[index].push(elapsed);
                }
            }
        }
        for (index, values) in samples.iter_mut().enumerate() {
            values.sort_unstable();
            let mode = cases[index].mode;
            println!("expanded-cpu frame={width}x{height} template=80x40 mode={mode}(0=exact,1=900permille-tolerance12-5percent-mismatch,2=20percent-transparent) roi=112x72 warmup=10 n=100 prepareUs={} firstUs={} P50={} P95={} P99={}us; public synthetic CPU only, no process-cold or device claim",prepare_us[index],first[index],values[49],values[94],values[98]);
        }
    }
}
