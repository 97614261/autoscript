use std::ops::Deref;
use std::sync::Arc;

/// Immutable shared pixels. Moving a Vec retains its original allocation, including spare capacity.
#[derive(Debug, Clone)]
pub struct SharedPixels(Arc<Storage>);

#[derive(Debug)]
enum Storage {
    Slice(Arc<[u8]>),
    Vector(Vec<u8>),
}

impl SharedPixels {
    #[must_use]
    pub fn allocation_bytes(&self) -> usize {
        match self.0.as_ref() {
            Storage::Slice(pixels) => pixels.len(),
            Storage::Vector(pixels) => pixels.capacity(),
        }
    }
}

impl From<Vec<u8>> for SharedPixels {
    fn from(value: Vec<u8>) -> Self {
        Self(Arc::new(Storage::Vector(value)))
    }
}

impl From<Arc<[u8]>> for SharedPixels {
    fn from(value: Arc<[u8]>) -> Self {
        Self(Arc::new(Storage::Slice(value)))
    }
}

impl Deref for SharedPixels {
    type Target = [u8];
    fn deref(&self) -> &Self::Target {
        match self.0.as_ref() {
            Storage::Slice(pixels) => pixels,
            Storage::Vector(pixels) => pixels,
        }
    }
}

impl AsRef<[u8]> for SharedPixels {
    fn as_ref(&self) -> &[u8] {
        self
    }
}

impl PartialEq for SharedPixels {
    fn eq(&self, other: &Self) -> bool {
        self.as_ref() == other.as_ref()
    }
}
impl Eq for SharedPixels {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn moving_vec_preserves_address_capacity_and_immutable_clone() {
        let mut pixels = Vec::with_capacity(64);
        pixels.extend_from_slice(&[1, 2, 3, 4]);
        let address = pixels.as_ptr();
        let shared = SharedPixels::from(pixels);
        let clone = shared.clone();
        assert_eq!(shared.as_ptr(), address);
        assert_eq!(clone.as_ptr(), address);
        assert_eq!(shared.allocation_bytes(), 64);
        drop(shared);
        assert_eq!(clone.as_ref(), &[1, 2, 3, 4]);
    }

    #[test]
    fn legacy_slice_retains_address_and_value_equality() {
        let pixels: Arc<[u8]> = Arc::from([1, 2, 3, 4]);
        let address = pixels.as_ptr();
        let shared = SharedPixels::from(pixels);
        assert_eq!(shared.as_ptr(), address);
        assert_eq!(shared.allocation_bytes(), 4);
        assert_eq!(shared, SharedPixels::from(vec![1, 2, 3, 4]));
    }
}
