use crate::{frame_png, vision_extensions::*};
use automation_core::{
    FrameFormat, FrameMetadata, FramePool, FramePoolConfig, FrameView, Rotation,
};
use runtime_scheduler::{TaskGeneration, TaskId, TaskResourceRegistry, TaskToken};

fn frame(width: u32, pixels: &[u8], format: FrameFormat) -> FrameView {
    let mut pool = FramePool::new(FramePoolConfig::default());
    let mut registry = TaskResourceRegistry::default();
    let owner = TaskToken {
        id: TaskId(1),
        generation: TaskGeneration(1),
    };
    let handle = pool
        .insert(
            owner,
            FrameMetadata {
                width,
                height: u32::try_from(pixels.len()).unwrap() / (width * 4),
                row_stride: width * 4,
                pixel_stride: 4,
                format,
                rotation: Rotation::Degrees0,
                timestamp_nanos: 1,
                snapshot_id: 1,
            },
            pixels,
            &mut registry,
        )
        .unwrap();
    pool.view(owner, handle, &registry).unwrap()
}
#[test]
fn transforms_preserve_alpha_and_source_and_reject_bad_dimensions_or_cancel() {
    let source = frame(2, &[10, 20, 30, 40, 50, 60, 70, 80], FrameFormat::Rgba8888);
    let transform_spec = |mode: &str, width, height| Transform {
        mode: mode.into(),
        width,
        height,
        threshold: 128,
    };
    assert_eq!(
        transform(&source, &transform_spec("rotate90", 0, 0), &mut || false).unwrap(),
        (1, 2, source.pixels.to_vec())
    );
    assert_eq!(
        transform(&source, &transform_spec("flipX", 0, 0), &mut || false)
            .unwrap()
            .2,
        vec![50, 60, 70, 80, 10, 20, 30, 40]
    );
    let output = transform(&source, &transform_spec("resize", 1, 1), &mut || false).unwrap();
    assert_eq!(output, (1, 1, vec![10, 20, 30, 40]));
    assert_eq!(source.pixels.as_ref(), &[10, 20, 30, 40, 50, 60, 70, 80]);
    assert!(transform(&source, &transform_spec("resize", 0, 1), &mut || false).is_err());
    assert!(transform(&source, &transform_spec("flipY", 0, 0), &mut || true).is_err());
    let bgra = frame(1, &[30, 20, 10, 40], FrameFormat::Bgra8888);
    assert_eq!(rgba(&bgra, 0, 0), [10, 20, 30, 40]);
}
#[test]
fn editor_preview_golden_vectors_match_existing_runtime_transforms() {
    let input = frame(
        2,
        &[
            0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 5, 0, 0, 0, 6, 0,
        ],
        FrameFormat::Rgba8888,
    );
    for (mode, width, height, expected) in [
        ("rotate90", 0, 0, vec![5, 3, 1, 6, 4, 2]),
        ("rotate270", 0, 0, vec![2, 4, 6, 1, 3, 5]),
        ("rotate180", 0, 0, vec![6, 5, 4, 3, 2, 1]),
        ("flipX", 0, 0, vec![2, 1, 4, 3, 6, 5]),
        ("flipY", 0, 0, vec![5, 6, 3, 4, 1, 2]),
        ("resize", 4, 1, vec![1, 1, 2, 2]),
    ] {
        let spec = Transform {
            mode: mode.into(),
            width,
            height,
            threshold: 128,
        };
        let (_, _, pixels) = transform(&input, &spec, &mut || false).unwrap();
        assert_eq!(
            pixels
                .chunks_exact(4)
                .map(|pixel| pixel[2])
                .collect::<Vec<_>>(),
            expected
        );
        assert!(pixels
            .chunks_exact(4)
            .all(|pixel| pixel[0] == 0 && pixel[1] == 0 && pixel[3] == 0));
    }
    let gray_input = frame(
        4,
        &[
            10, 20, 30, 40, 50, 60, 70, 128, 255, 0, 0, 255, 0, 255, 0, 18,
        ],
        FrameFormat::Rgba8888,
    );
    let (_, _, pixels) = transform(
        &gray_input,
        &Transform {
            mode: "gray".into(),
            width: 0,
            height: 0,
            threshold: 128,
        },
        &mut || false,
    )
    .unwrap();
    assert_eq!(
        pixels,
        vec![18, 18, 18, 40, 58, 58, 58, 128, 77, 77, 77, 255, 149, 149, 149, 18]
    );
    let (_, _, threshold_pixels) = transform(
        &gray_input,
        &Transform {
            mode: "threshold".into(),
            width: 0,
            height: 0,
            threshold: 100,
        },
        &mut || false,
    )
    .unwrap();
    assert_eq!(
        threshold_pixels,
        vec![0, 0, 0, 40, 0, 0, 0, 128, 0, 0, 0, 255, 255, 255, 255, 18]
    );
}

#[test]
fn components_distinguish_four_and_eight_connectivity_with_real_area() {
    let source = frame(
        2,
        &[
            255, 255, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255, 255,
        ],
        FrameFormat::Rgba8888,
    );
    let mut spec = Components {
        region: Region {
            left: 0,
            top: 0,
            right: 2,
            bottom: 2,
        },
        rgb: 0x00ff_ffff,
        tolerance: 0,
        connectivity: 4,
        min_area: 1,
        limit: 256,
    };
    let four = components(&source, &spec, &mut || false).unwrap();
    assert_eq!(four.as_array().unwrap().len(), 2);
    assert_eq!(four[0]["area"], 1);
    spec.connectivity = 8;
    let eight = components(&source, &spec, &mut || false).unwrap();
    assert_eq!(eight.as_array().unwrap().len(), 1);
    assert_eq!(eight[0]["area"], 2);
    assert_eq!(eight[0]["right"], 2);
    spec.min_area = 3;
    assert!(components(&source, &spec, &mut || false)
        .unwrap()
        .as_array()
        .unwrap()
        .is_empty());
    assert!(components(&source, &spec, &mut || true).is_err());
}
#[test]
fn batch_pattern_respects_offsets_roi_limit_and_cancellation() {
    let source = frame(
        3,
        &[255, 255, 255, 255, 0, 0, 0, 255, 255, 255, 255, 255],
        FrameFormat::Rgba8888,
    );
    let mut spec = MultiColor {
        region: Region {
            left: 0,
            top: 0,
            right: 3,
            bottom: 1,
        },
        samples: vec![Sample {
            dx: 0,
            dy: 0,
            rgb: 0x00ff_ffff,
            tolerance: 0,
        }],
        limit: 1,
    };
    assert_eq!(
        multi_color(&source, &spec, &mut || false)
            .unwrap()
            .as_array()
            .unwrap()
            .len(),
        1
    );
    spec.limit = 256;
    spec.samples.push(Sample {
        dx: 1,
        dy: 0,
        rgb: 0,
        tolerance: 0,
    });
    let result = multi_color(&source, &spec, &mut || false).unwrap();
    assert_eq!(result.as_array().unwrap().len(), 1);
    assert_eq!(result[0]["x"], 0);
    assert!(multi_color(&source, &spec, &mut || true).is_err());
}
#[test]
fn png_has_original_geometry_rgba_and_valid_stored_zlib_stream() {
    let source = frame(2, &[30, 20, 10, 40, 70, 60, 50, 80], FrameFormat::Bgra8888);
    let png = frame_png::encode(&source, &mut || false).unwrap();
    assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
    let mut offset = 8;
    let mut zlib = Vec::new();
    while offset < png.len() {
        let size = u32::from_be_bytes(png[offset..offset + 4].try_into().unwrap()) as usize;
        let kind = &png[offset + 4..offset + 8];
        let data = &png[offset + 8..offset + 8 + size];
        if kind == b"IHDR" {
            assert_eq!(&data[..8], &[0, 0, 0, 2, 0, 0, 0, 1]);
        }
        if kind == b"IDAT" {
            zlib.extend_from_slice(data);
        }
        offset += size + 12;
    }
    assert_eq!(&zlib[..2], &[0x78, 0x01]);
    assert_eq!(zlib[2], 1);
    let len = u16::from_le_bytes(zlib[3..5].try_into().unwrap());
    assert_eq!(!len, u16::from_le_bytes(zlib[5..7].try_into().unwrap()));
    assert_eq!(
        &zlib[7..7 + usize::from(len)],
        &[0, 10, 20, 30, 40, 50, 60, 70, 80]
    );
    assert!(frame_png::encode(&source, &mut || true).is_err());
}
