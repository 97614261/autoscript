//! Bounded, freshness-checked screen capture cache for the Root vision hot path.
//!
//! The cache only reuses a frame when its geometry and screen generation still
//! match and its monotonic age is within the small hot-path window.  Callers
//! must clear it whenever input or the Root session can change the screen.

use std::collections::VecDeque;

use automation_core::{FrameMetadata, Rotation, SharedPixels};

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
// Only the newest entry can be queried; older immutable frames live in FramePool.
pub(crate) const CACHE_CAPACITY: usize = 1;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) const MAX_FRAME_AGE_NANOS: u64 = 80_000_000;
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) const MAX_CACHE_BYTES: usize = 32 * 1024 * 1024;

#[derive(Debug, Clone)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) struct CachedCapture {
    pub(crate) metadata: FrameMetadata,
    pub(crate) pixels: SharedPixels,
    pub(crate) age_nanos: u64,
}

#[derive(Debug, Default)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) struct CaptureCache {
    entries: VecDeque<Entry>,
    total_bytes: usize,
}

#[derive(Debug, Clone)]
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
struct Entry {
    metadata: FrameMetadata,
    pixels: SharedPixels,
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
impl CaptureCache {
    pub(crate) fn clear(&mut self) {
        self.entries.clear();
        self.total_bytes = 0;
    }

    pub(crate) fn insert(
        &mut self,
        metadata: FrameMetadata,
        pixels: impl Into<SharedPixels>,
    ) -> Option<CachedCapture> {
        let pixels = pixels.into();
        let bytes = pixels.allocation_bytes();
        if bytes > MAX_CACHE_BYTES {
            self.clear();
            return None;
        }
        while self.total_bytes.saturating_add(bytes) > MAX_CACHE_BYTES
            || self.entries.len() >= CACHE_CAPACITY
        {
            let Some(oldest) = self.entries.pop_front() else {
                break;
            };
            self.total_bytes = self
                .total_bytes
                .saturating_sub(oldest.pixels.allocation_bytes());
        }
        let entry = Entry { metadata, pixels };
        self.total_bytes = self.total_bytes.saturating_add(bytes);
        self.entries.push_back(entry.clone());
        Some(CachedCapture {
            metadata,
            pixels: entry.pixels,
            age_nanos: 0,
        })
    }

    pub(crate) fn fresh(
        &mut self,
        now_nanos: u64,
        width: u32,
        height: u32,
        snapshot_id: u64,
    ) -> Option<CachedCapture> {
        let entry = self.entries.back()?.clone();
        if entry.metadata.width != width
            || entry.metadata.height != height
            || entry.metadata.snapshot_id != snapshot_id
            || entry.metadata.pixel_stride != 4
            || entry.metadata.row_stride != width.saturating_mul(4)
            || entry.metadata.rotation != Rotation::Degrees0
        {
            return None;
        }
        let Some(age_nanos) = now_nanos.checked_sub(entry.metadata.timestamp_nanos) else {
            // A monotonic clock rollback is not a valid freshness proof.
            self.clear();
            return None;
        };
        if age_nanos > MAX_FRAME_AGE_NANOS {
            self.clear_expired(now_nanos);
            return None;
        }
        Some(CachedCapture {
            metadata: entry.metadata,
            pixels: entry.pixels,
            age_nanos,
        })
    }

    fn clear_expired(&mut self, now_nanos: u64) {
        let before = self.entries.len();
        self.entries.retain(|entry| {
            now_nanos
                .checked_sub(entry.metadata.timestamp_nanos)
                .is_some_and(|age| age <= MAX_FRAME_AGE_NANOS)
        });
        if self.entries.len() != before {
            self.total_bytes = self
                .entries
                .iter()
                .map(|entry| entry.pixels.allocation_bytes())
                .sum();
        }
    }
}

#[cfg(test)]
mod tests {
    use automation_core::{FrameFormat, Rotation};
    use std::sync::Arc;

    use super::*;

    fn metadata(timestamp_nanos: u64, snapshot_id: u64) -> FrameMetadata {
        FrameMetadata {
            width: 2,
            height: 2,
            row_stride: 8,
            pixel_stride: 4,
            format: FrameFormat::Bgra8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos,
            snapshot_id,
        }
    }

    #[test]
    fn reuses_only_matching_recent_generation() {
        let mut cache = CaptureCache::default();
        cache.insert(metadata(100, 7), Arc::from([1; 16]));
        assert!(cache.fresh(100 + MAX_FRAME_AGE_NANOS, 2, 2, 7).is_some());
        assert!(cache
            .fresh(100 + MAX_FRAME_AGE_NANOS + 1, 2, 2, 7)
            .is_none());
        assert!(cache.fresh(101, 2, 2, 8).is_none());
        assert!(cache.fresh(101, 3, 2, 7).is_none());
    }

    #[test]
    fn rejects_clock_rollback_and_clear_invalidates() {
        let mut cache = CaptureCache::default();
        cache.insert(metadata(100, 1), Arc::from([2; 16]));
        assert!(cache.fresh(99, 2, 2, 1).is_none());
        assert!(cache.fresh(100, 2, 2, 1).is_none());
    }

    #[test]
    fn keeps_only_a_small_bounded_window() {
        let mut cache = CaptureCache::default();
        for timestamp in 1..=CACHE_CAPACITY as u64 + 2 {
            cache.insert(
                metadata(timestamp, 1),
                Arc::from([u8::try_from(timestamp).unwrap(); 16]),
            );
        }
        assert_eq!(cache.entries.len(), CACHE_CAPACITY);
        assert!(cache.total_bytes <= MAX_CACHE_BYTES);
    }

    #[test]
    fn rejects_incompatible_stride_or_rotation_even_when_geometry_matches() {
        let mut cache = CaptureCache::default();
        let mut invalid = metadata(1, 1);
        invalid.row_stride = 12;
        cache.insert(invalid, Arc::from([0; 16]));
        assert!(cache.fresh(1, 2, 2, 1).is_none());

        let mut invalid = metadata(2, 1);
        invalid.rotation = Rotation::Degrees90;
        cache.insert(invalid, Arc::from([0; 16]));
        assert!(cache.fresh(2, 2, 2, 1).is_none());
    }

    #[test]
    fn rejects_a_frame_larger_than_the_cache_budget() {
        let mut cache = CaptureCache::default();
        let oversized = FrameMetadata {
            width: u32::try_from(MAX_CACHE_BYTES / 4 + 1).unwrap(),
            height: 1,
            row_stride: u32::try_from(MAX_CACHE_BYTES / 4 + 1)
                .unwrap()
                .saturating_mul(4),
            ..metadata(1, 1)
        };
        assert!(cache
            .insert(oversized, Arc::from(vec![0; MAX_CACHE_BYTES + 4]))
            .is_none());
        assert!(cache.fresh(1, oversized.width, 1, 1).is_none());
    }

    #[test]
    fn shares_received_allocation_through_cache_pool_and_lease() {
        use automation_core::{FramePool, FramePoolConfig};
        use runtime_scheduler::{TaskGeneration, TaskId, TaskResourceRegistry, TaskToken};

        let mut cache = CaptureCache::default();
        let mut pool = FramePool::new(FramePoolConfig::default());
        let mut resources = TaskResourceRegistry::default();
        let task = TaskToken {
            id: TaskId(1),
            generation: TaskGeneration(1),
        };
        let mut pixels = Vec::with_capacity(64);
        pixels.extend_from_slice(&[37; 16]);
        let address = pixels.as_ptr();
        let pixels = SharedPixels::from(pixels);
        cache.insert(metadata(100, 7), pixels.clone()).unwrap();
        assert_eq!(cache.total_bytes, 64);
        let capture = pool
            .publish_capture_shared(metadata(100, 7), pixels)
            .unwrap();
        assert_eq!(pool.total_bytes(), 64);
        let handle = pool.cache(task, capture, &mut resources).unwrap();
        let view = pool.view(task, handle, &resources).unwrap();
        assert_eq!(view.pixels.as_ptr(), address);
        assert_eq!(cache.fresh(101, 2, 2, 7).unwrap().pixels.as_ptr(), address);
        cache.clear();
        pool.release_resource(handle.resource_id);
        pool.reclaim_expired(u64::MAX);
        assert_eq!(pool.total_bytes(), 0);
        assert_eq!(view.pixels.as_ptr(), address);
        assert_eq!(view.pixels.as_ref(), &[37; 16]);
    }

    #[test]
    fn repeated_replacement_charges_capacity_and_never_mutates_old_owners() {
        let mut cache = CaptureCache::default();
        let first = cache.insert(metadata(1, 1), vec![37; 16]).unwrap();
        let first_address = first.pixels.as_ptr();
        for timestamp in 2..=5000 {
            let mut pixels = Vec::with_capacity(64);
            pixels.extend_from_slice(&[99; 16]);
            cache.insert(metadata(timestamp, 1), pixels).unwrap();
            assert_eq!(cache.total_bytes, 64);
            assert_eq!(cache.entries.len(), 1);
        }
        cache.clear();
        assert_eq!(first.pixels.as_ptr(), first_address);
        assert_eq!(first.pixels.as_ref(), &[37; 16]);

        let mut oversized = Vec::with_capacity(MAX_CACHE_BYTES + 1);
        oversized.extend_from_slice(&[1; 16]);
        assert!(cache.insert(metadata(5001, 1), oversized).is_none());
        assert_eq!(cache.total_bytes, 0);
        assert!(cache.entries.is_empty());
    }

    #[test]
    fn replacement_expiry_and_clear_release_retired_allocations_under_pressure() {
        let mut cache = CaptureCache::default();
        for timestamp in 1..=10_000 {
            let pixels: Arc<[u8]> = Arc::from([37; 16]);
            let retired = Arc::downgrade(&pixels);
            let borrowed = cache.insert(metadata(timestamp, 1), pixels).unwrap();
            match timestamp % 3 {
                0 => cache.clear(),
                1 => {
                    assert!(cache
                        .fresh(timestamp + MAX_FRAME_AGE_NANOS + 1, 2, 2, 1)
                        .is_none());
                }
                _ => {
                    cache.insert(metadata(timestamp + 1, 1), vec![99; 16]);
                    assert_eq!(cache.entries.len(), 1);
                    assert_eq!(cache.total_bytes, 16);
                    cache.clear();
                }
            }
            assert_eq!(cache.total_bytes, 0);
            assert!(cache.entries.is_empty());
            assert_eq!(borrowed.pixels.as_ref(), &[37; 16]);
            drop(borrowed);
            assert!(retired.upgrade().is_none());
        }
    }
}
