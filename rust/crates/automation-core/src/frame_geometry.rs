//! Bounded inverse pixel sampling lineage. File images never acquire a screen origin.
use crate::{FrameMetadata, PixelPoint, PixelRect};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameOrigin {
    pub screen_width: u32,
    pub screen_height: u32,
    pub timestamp_nanos: u64,
    pub snapshot_id: u64,
    capture_rotation: Option<u32>,
    width: u32,
    height: u32,
    steps: Vec<MappingStep>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct MappingStep {
    mode: String,
    source: (u32, u32),
    output: (u32, u32),
    offset: (u32, u32),
}

impl FrameOrigin {
    #[must_use]
    pub fn capture(metadata: FrameMetadata) -> Self {
        Self {
            screen_width: metadata.width,
            screen_height: metadata.height,
            timestamp_nanos: metadata.timestamp_nanos,
            snapshot_id: metadata.snapshot_id,
            capture_rotation: None,
            width: metadata.width,
            height: metadata.height,
            steps: Vec::new(),
        }
    }

    /// Validated native region, keeping the full logical screen and its sampling offset.
    /// # Errors
    /// Rejects invalid screen/region geometry or non-normalized pixel orientation.
    pub fn capture_region(
        metadata: FrameMetadata,
        screen: (u32, u32),
        offset: (u32, u32),
        rotation: Option<u32>,
    ) -> Result<Self, &'static str> {
        if screen.0 == 0
            || screen.1 == 0
            || metadata.width == 0
            || metadata.height == 0
            || offset
                .0
                .checked_add(metadata.width)
                .is_none_or(|right| right > screen.0)
            || offset
                .1
                .checked_add(metadata.height)
                .is_none_or(|bottom| bottom > screen.1)
            || metadata.rotation != crate::Rotation::Degrees0
            || rotation.is_some_and(|r| r > 3)
        {
            return Err("MAPPING_INVALID_GEOMETRY");
        }
        let mut origin = Self::capture(metadata);
        origin.screen_width = screen.0;
        origin.screen_height = screen.1;
        origin.capture_rotation = rotation;
        origin.steps.push(MappingStep {
            mode: "crop".into(),
            source: screen,
            output: (metadata.width, metadata.height),
            offset,
        });
        Ok(origin)
    }

    /// Append only an actual, validated transform; a failed lineage is not a new capture.
    /// # Errors
    /// Rejects unsupported geometry, invalid sizes and more than 64 geometric transforms.
    pub fn transformed(
        &self,
        mode: &str,
        width: u32,
        height: u32,
        crop: Option<PixelRect>,
    ) -> Result<Self, &'static str> {
        if width == 0 || height == 0 {
            return Err("MAPPING_INVALID_GEOMETRY");
        }
        let source = (self.width, self.height);
        let offset = if mode == "crop" {
            let r = crop.ok_or("MAPPING_INVALID_GEOMETRY")?;
            if r.left >= r.right
                || r.top >= r.bottom
                || r.right > self.width
                || r.bottom > self.height
                || (width, height) != (r.right - r.left, r.bottom - r.top)
            {
                return Err("MAPPING_INVALID_GEOMETRY");
            }
            (r.left, r.top)
        } else {
            if crop.is_some() {
                return Err("MAPPING_INVALID_GEOMETRY");
            }
            let expected = match mode {
                "rotate90" | "rotate270" => (self.height, self.width),
                "resize" => (width, height),
                "rotate180" | "flipX" | "flipY" | "gray" | "threshold" | "filter" | "mask"
                | "despeckle" | "erode" | "dilate" | "open" | "close" => source,
                _ => return Err("MAPPING_UNSUPPORTED_TRANSFORM"),
            };
            if (width, height) != expected {
                return Err("MAPPING_INVALID_GEOMETRY");
            }
            (0, 0)
        };
        let mut result = self.clone();
        if matches!(
            mode,
            "crop" | "resize" | "rotate90" | "rotate180" | "rotate270" | "flipX" | "flipY"
        ) {
            if self.steps.len() >= 64 {
                return Err("MAPPING_LINEAGE_BUDGET");
            }
            result.steps.push(MappingStep {
                mode: mode.into(),
                source,
                output: (width, height),
                offset,
            });
        }
        result.width = width;
        result.height = height;
        Ok(result)
    }

    /// Map a processed pixel to the exact source pixel selected by nearest-neighbour sampling.
    /// # Errors
    /// Rejects coordinates outside the processed image or the original screen.
    pub fn map_point(&self, mut point: PixelPoint) -> Result<PixelPoint, &'static str> {
        if point.x >= self.width || point.y >= self.height {
            return Err("MAPPING_POINT_OUT_OF_BOUNDS");
        }
        for step in self.steps.iter().rev() {
            let (x, y) = (point.x, point.y);
            let (sw, sh) = step.source;
            let (sx, sy) = match step.mode.as_str() {
                "crop" => (x + step.offset.0, y + step.offset.1),
                "resize" => (
                    u32::try_from(u64::from(x) * u64::from(sw) / u64::from(step.output.0))
                        .map_err(|_| "MAPPING_INVALID_GEOMETRY")?,
                    u32::try_from(u64::from(y) * u64::from(sh) / u64::from(step.output.1))
                        .map_err(|_| "MAPPING_INVALID_GEOMETRY")?,
                ),
                "rotate90" => (y, sh - 1 - x),
                "rotate180" => (sw - 1 - x, sh - 1 - y),
                "rotate270" => (sw - 1 - y, x),
                "flipX" => (sw - 1 - x, y),
                "flipY" => (x, sh - 1 - y),
                _ => return Err("MAPPING_UNSUPPORTED_TRANSFORM"),
            };
            point = PixelPoint { x: sx, y: sy };
        }
        if point.x >= self.screen_width || point.y >= self.screen_height {
            return Err("MAPPING_POINT_OUT_OF_BOUNDS");
        }
        Ok(point)
    }

    #[must_use]
    pub fn is_fresher_than(&self, other: &Self) -> bool {
        self.screen_width == other.screen_width
            && self.screen_height == other.screen_height
            && self.timestamp_nanos > other.timestamp_nanos
            && other.timestamp_nanos != 0
            && self.snapshot_id != 0
            && other.snapshot_id != 0
            && self.snapshot_id == other.snapshot_id
            && self.capture_rotation == other.capture_rotation
            && self.steps == other.steps
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{FrameFormat, Rotation};
    fn origin() -> FrameOrigin {
        FrameOrigin::capture(FrameMetadata {
            width: 8,
            height: 6,
            row_stride: 32,
            pixel_stride: 4,
            format: FrameFormat::Rgba8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos: 10,
            snapshot_id: 1,
        })
    }
    #[test]
    fn native_region_keeps_screen_offset_across_transforms_and_freshness() {
        let metadata = FrameMetadata {
            width: 3,
            height: 2,
            row_stride: 12,
            pixel_stride: 4,
            format: FrameFormat::Bgra8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos: 10,
            snapshot_id: 7,
        };
        let region =
            FrameOrigin::capture_region(metadata, (720, 1280), (149, 407), Some(1)).unwrap();
        assert_eq!(
            region.map_point(PixelPoint { x: 2, y: 1 }).unwrap(),
            PixelPoint { x: 151, y: 408 }
        );
        let transformed = region.transformed("rotate90", 2, 3, None).unwrap();
        assert_eq!(
            transformed.map_point(PixelPoint { x: 0, y: 2 }).unwrap(),
            PixelPoint { x: 151, y: 408 }
        );
        let next = FrameOrigin::capture_region(
            FrameMetadata {
                timestamp_nanos: 11,
                ..metadata
            },
            (720, 1280),
            (149, 407),
            Some(1),
        )
        .unwrap();
        assert!(next.is_fresher_than(&region));
        let rotated = FrameOrigin::capture_region(
            FrameMetadata {
                timestamp_nanos: 11,
                ..metadata
            },
            (720, 1280),
            (149, 407),
            Some(3),
        )
        .unwrap();
        assert!(!rotated.is_fresher_than(&region));
        for offset in [(719, 0), (0, 1279), (u32::MAX, 0)] {
            assert!(FrameOrigin::capture_region(metadata, (720, 1280), offset, Some(0)).is_err());
        }
    }

    #[test]
    fn crop_rotate_resize_maps_exact_sample_and_rejects_bounds() {
        let value = origin()
            .transformed(
                "crop",
                4,
                4,
                Some(PixelRect {
                    left: 2,
                    top: 1,
                    right: 6,
                    bottom: 5,
                }),
            )
            .unwrap()
            .transformed("rotate90", 4, 4, None)
            .unwrap()
            .transformed("resize", 8, 8, None)
            .unwrap();
        assert_eq!(
            value.map_point(PixelPoint { x: 2, y: 4 }).unwrap(),
            PixelPoint { x: 4, y: 3 }
        );
        assert!(value.map_point(PixelPoint { x: 8, y: 0 }).is_err());
        assert!(origin().transformed("rotate90", 8, 6, None).is_err());
        let mut old = origin();
        let mut new = old.clone();
        new.timestamp_nanos += 1;
        assert!(new.is_fresher_than(&old));
        new.snapshot_id = 2;
        assert!(!new.is_fresher_than(&old));
        for _ in 0..64 {
            old = old.transformed("flipX", 8, 6, None).unwrap();
        }
        assert!(old.transformed("flipY", 8, 6, None).is_err());
    }
}
