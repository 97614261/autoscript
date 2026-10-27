//! Explicit logical-screen region provenance. Pixels remain unscaled and local to the region.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureRegionRequest {
    pub screen_width: u32,
    pub screen_height: u32,
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

impl CaptureRegionRequest {
    #[must_use]
    pub fn valid(self) -> bool {
        (1..=16_384).contains(&self.screen_width)
            && (1..=16_384).contains(&self.screen_height)
            && u64::from(self.screen_width) * u64::from(self.screen_height) * 4 <= 64 * 1024 * 1024
            && self.left < self.right
            && self.top < self.bottom
            && self.right <= self.screen_width
            && self.bottom <= self.screen_height
    }

    #[must_use]
    pub fn encode(self) -> [u8; 24] {
        let mut bytes = [0; 24];
        for (target, value) in bytes.chunks_exact_mut(4).zip([
            self.screen_width,
            self.screen_height,
            self.left,
            self.top,
            self.right,
            self.bottom,
        ]) {
            target.copy_from_slice(&value.to_le_bytes());
        }
        bytes
    }

    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let [screen_width, screen_height, left, top, right, bottom] = words(bytes)?;
        let value = Self {
            screen_width,
            screen_height,
            left,
            top,
            right,
            bottom,
        };
        value.valid().then_some(value)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureRegionMetadata {
    pub request: CaptureRegionRequest,
    /// Android rotation 0..3, or None when bounded screencap does not report it.
    pub rotation: Option<u32>,
    /// True only when the bridge reads/encodes the region, not a full readback crop.
    pub regional_readback: bool,
}

impl CaptureRegionMetadata {
    #[must_use]
    pub fn encode(self) -> [u8; 32] {
        let mut bytes = [0; 32];
        bytes[..24].copy_from_slice(&self.request.encode());
        bytes[24..28].copy_from_slice(&self.rotation.unwrap_or(4).to_le_bytes());
        bytes[28..].copy_from_slice(&u32::from(self.regional_readback).to_le_bytes());
        bytes
    }

    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let [_, _, _, _, _, _, rotation, mode] = words(bytes)?;
        let request = CaptureRegionRequest::decode(&bytes[..24])?;
        match (mode, rotation) {
            (0, 4) => Some(Self {
                request,
                rotation: None,
                regional_readback: false,
            }),
            (1, 0..=3) => Some(Self {
                request,
                rotation: Some(rotation),
                regional_readback: true,
            }),
            _ => None,
        }
    }

    #[must_use]
    pub fn matches_dimensions(self, width: u32, height: u32) -> bool {
        width == self.request.right - self.request.left
            && height == self.request.bottom - self.request.top
    }
}

fn words<const N: usize>(bytes: &[u8]) -> Option<[u32; N]> {
    if bytes.len() != N * 4 {
        return None;
    }
    let mut result = [0; N];
    for (target, bytes) in result.iter_mut().zip(bytes.chunks_exact(4)) {
        *target = u32::from_le_bytes(bytes.try_into().ok()?);
    }
    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request() -> CaptureRegionRequest {
        CaptureRegionRequest {
            screen_width: 720,
            screen_height: 1280,
            left: 10,
            top: 20,
            right: 210,
            bottom: 142,
        }
    }

    #[test]
    fn fixed_region_roundtrip_and_geometry_rejection() {
        let r = request();
        assert_eq!(CaptureRegionRequest::decode(&r.encode()), Some(r));
        assert!(CaptureRegionRequest::decode(&r.encode()[..23]).is_none());
        for invalid in [
            CaptureRegionRequest { right: 10, ..r },
            CaptureRegionRequest { bottom: 1281, ..r },
            CaptureRegionRequest {
                screen_width: u32::MAX,
                ..r
            },
            CaptureRegionRequest { top: u32::MAX, ..r },
        ] {
            assert!(CaptureRegionRequest::decode(&invalid.encode()).is_none());
        }
    }

    #[test]
    fn provenance_distinguishes_unknown_rotation_and_actual_region_readback() {
        for rotation in 0..4 {
            let value = CaptureRegionMetadata {
                request: request(),
                rotation: Some(rotation),
                regional_readback: true,
            };
            assert_eq!(CaptureRegionMetadata::decode(&value.encode()), Some(value));
            assert!(value.matches_dimensions(200, 122));
            assert!(!value.matches_dimensions(720, 1280));
        }
        let fallback = CaptureRegionMetadata {
            request: request(),
            rotation: None,
            regional_readback: false,
        };
        assert_eq!(
            CaptureRegionMetadata::decode(&fallback.encode()),
            Some(fallback)
        );
        let mut invalid = fallback.encode();
        invalid[24..28].copy_from_slice(&0_u32.to_le_bytes());
        assert!(CaptureRegionMetadata::decode(&invalid).is_none());
    }
}
