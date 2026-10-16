use std::sync::Arc;

use runtime_scheduler::{TaskGeneration, TaskId, TaskResourceRegistry, TaskToken};

use crate::{
    CaptureSeriesConfig, FrameError, FrameFormat, FrameMetadata, FramePool, FramePoolConfig,
    Rotation, SeriesPublish,
};

fn task(id: u64) -> TaskToken {
    TaskToken {
        id: TaskId(id),
        generation: TaskGeneration(1),
    }
}
fn metadata() -> FrameMetadata {
    FrameMetadata {
        width: 2,
        height: 2,
        row_stride: 8,
        pixel_stride: 4,
        format: FrameFormat::Bgra8888,
        rotation: Rotation::Degrees0,
        timestamp_nanos: 10,
        snapshot_id: 1,
    }
}

#[test]
fn vector_capture_retains_pointer_through_lease_and_release_without_frame_reuse() {
    let mut pool = FramePool::new(FramePoolConfig::default());
    let mut resources = TaskResourceRegistry::default();
    let pixels = vec![37; 16];
    let address = pixels.as_ptr();
    let capture = pool.publish_capture_vec(metadata(), pixels).unwrap();
    let handle = pool.cache(task(1), capture, &mut resources).unwrap();
    let view = pool.view(task(1), handle, &resources).unwrap();
    assert_eq!(view.pixels.as_ptr(), address);
    assert!(matches!(
        pool.view(task(2), handle, &resources),
        Err(FrameError::LeaseNotHeld(_))
    ));
    let next = pool.publish_capture_vec(metadata(), vec![99; 16]).unwrap();
    let next_handle = pool.cache(task(1), next, &mut resources).unwrap();
    assert_eq!(
        pool.view(task(1), next_handle, &resources)
            .unwrap()
            .pixels
            .as_ref(),
        &[99; 16]
    );
    assert_eq!(view.pixels.as_ref(), &[37; 16]);
    pool.release_resource(handle.resource_id);
    pool.reclaim_expired(u64::MAX);
    assert_eq!(view.pixels.as_ptr(), address); // In-flight immutable view retains its own owner.
    assert_eq!(view.pixels.as_ref(), &[37; 16]);
}

#[test]
fn vector_capacity_is_charged_and_reclaimed_not_just_initialized_length() {
    let mut pool = FramePool::new(FramePoolConfig {
        max_total_bytes: 64,
        ..FramePoolConfig::default()
    });
    let mut pixels = Vec::with_capacity(64);
    pixels.extend_from_slice(&[1; 16]);
    pool.publish_capture_vec(metadata(), pixels).unwrap();
    assert_eq!(pool.total_bytes(), 64);
    pool.reclaim_expired(u64::MAX);
    assert_eq!(pool.total_bytes(), 0);
    let mut excessive = Vec::with_capacity(65);
    excessive.extend_from_slice(&[1; 16]);
    assert_eq!(
        pool.publish_capture_vec(metadata(), excessive),
        Err(FrameError::FrameBudgetExceeded)
    );
    assert_eq!(pool.frame_count(), 0);
}

#[test]
fn malformed_owned_frame_is_not_published_or_charged() {
    let mut pool = FramePool::new(FramePoolConfig::default());
    assert!(matches!(
        pool.publish_capture_vec(metadata(), vec![1; 15]),
        Err(FrameError::ByteLengthMismatch { .. })
    ));
    let mut invalid = metadata();
    invalid.row_stride = 1;
    assert_eq!(
        pool.publish_capture_vec(invalid, vec![1; 16]),
        Err(FrameError::InvalidGeometry)
    );
    assert_eq!(pool.total_bytes(), 0);
}

#[test]
fn series_vector_retains_pointer_and_spare_capacity_cannot_consume_reserved_budget() {
    let mut pool = FramePool::new(FramePoolConfig::default());
    let mut resources = TaskResourceRegistry::default();
    let first = pool.publish_capture_vec(metadata(), vec![1; 16]).unwrap();
    let first = pool.cache(task(1), first, &mut resources).unwrap();
    let series = pool
        .begin_series(
            task(1),
            first,
            &mut resources,
            CaptureSeriesConfig {
                max_frames: 2,
                target_fps: 10,
                max_duration_nanos: 1_000_000_000,
                allow_partial: false,
            },
        )
        .unwrap();
    let mut next_metadata = metadata();
    next_metadata.timestamp_nanos = 100_000_010;
    let mut excessive = Vec::with_capacity(32);
    excessive.extend_from_slice(&[2; 16]);
    assert_eq!(
        pool.publish_series_capture_vec(series, next_metadata, excessive),
        Err(FrameError::FrameBudgetExceeded)
    );
    assert_eq!(pool.reserved_series_bytes(), 16);
    let pixels = vec![2; 16];
    let address = pixels.as_ptr();
    let SeriesPublish::Frame { capture, .. } = pool
        .publish_series_capture_vec(series, next_metadata, pixels)
        .unwrap()
    else {
        panic!("expected complete series frame")
    };
    let handle = pool
        .cache_series_capture(task(1), series, capture, &mut resources)
        .unwrap();
    assert_eq!(
        pool.view(task(1), handle, &resources)
            .unwrap()
            .pixels
            .as_ptr(),
        address
    );
    assert_eq!(pool.total_bytes(), 32);
    assert_eq!(pool.reserved_series_bytes(), 0);
}

#[test]
#[ignore = "explicit synthetic ownership publication benchmark, not Android screenshot performance"]
fn benchmark_received_frame_publication() {
    let bytes = 720 * 1280 * 4;
    let metadata = FrameMetadata {
        width: 720,
        height: 1280,
        row_stride: 720 * 4,
        ..metadata()
    };
    let mut old = Vec::new();
    let mut owned = Vec::new();
    for index in 0..110 {
        for legacy in if index % 2 == 0 {
            [true, false]
        } else {
            [false, true]
        } {
            let pixels = vec![std::hint::black_box(37); bytes];
            let mut pool = FramePool::new(FramePoolConfig::default());
            let started = std::time::Instant::now();
            let capture = if legacy {
                pool.publish_capture_owned(metadata, Arc::from(pixels))
            } else {
                pool.publish_capture_vec(metadata, pixels)
            }
            .unwrap();
            std::hint::black_box(capture);
            let elapsed = started.elapsed();
            if index >= 10 {
                if legacy {
                    old.push(elapsed);
                } else {
                    owned.push(elapsed);
                }
            }
        }
    }
    for (name, mut values) in [("legacy-vec-to-arc", old), ("move-owned-vec", owned)] {
        values.sort_unstable();
        println!(
            "{name}: samples={} P50={:.6}ms P95={:.6}ms P99={:.6}ms",
            values.len(),
            values[49].as_secs_f64() * 1_000.0,
            values[94].as_secs_f64() * 1_000.0,
            values[98].as_secs_f64() * 1_000.0
        );
    }
}
