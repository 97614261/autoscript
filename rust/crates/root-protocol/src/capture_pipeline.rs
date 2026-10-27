//! Mandatory final commit for a negotiated, not-yet-complete pixel stream.

use crate::CaptureTransferSummary;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CapturePipelineCompletion {
    pub capture_id: u64,
    pub pixel_bytes: u64,
    pub acquisition_us: u64,
    pub stages_us: [u64; 6],
    pub transfer: CaptureTransferSummary,
}

impl CapturePipelineCompletion {
    #[must_use]
    pub fn encode(self) -> [u8; 97] {
        let mut bytes = [0; 97];
        bytes[0] = 4;
        let values = [
            self.capture_id,
            self.pixel_bytes,
            self.acquisition_us,
            self.stages_us[0],
            self.stages_us[1],
            self.stages_us[2],
            self.stages_us[3],
            self.stages_us[4],
            self.stages_us[5],
            self.transfer.encode_us,
            self.transfer.write_us,
            self.transfer.packets,
        ];
        for (target, value) in bytes[1..].chunks_exact_mut(8).zip(values) {
            target.copy_from_slice(&value.to_le_bytes());
        }
        bytes
    }

    #[must_use]
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != 97 || bytes[0] != 4 {
            return None;
        }
        let mut values = [0; 12];
        for (value, source) in values.iter_mut().zip(bytes[1..].chunks_exact(8)) {
            *value = u64::from_le_bytes(source.try_into().ok()?);
        }
        let transfer = CaptureTransferSummary {
            encode_us: values[9],
            write_us: values[10],
            packets: values[11],
        };
        if values[0] == 0
            || !(1..=64 * 1024 * 1024).contains(&values[1])
            || values[2..9].iter().any(|value| *value > 10_000_000)
            || CaptureTransferSummary::decode(&transfer.encode()).is_none()
            || transfer.packets != values[1].div_ceil(60 * 1024)
        {
            return None;
        }
        Some(Self {
            capture_id: values[0],
            pixel_bytes: values[1],
            acquisition_us: values[2],
            stages_us: values[3..9].try_into().ok()?,
            transfer,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn completion_is_exact_bounded_and_binds_byte_length_to_packet_count() {
        let completion = CapturePipelineCompletion {
            capture_id: 42,
            pixel_bytes: 64_000,
            acquisition_us: 50,
            stages_us: [10, 20, 30, 40, 50, 60],
            transfer: CaptureTransferSummary {
                encode_us: 2,
                write_us: 3,
                packets: 2,
            },
        };
        assert_eq!(
            CapturePipelineCompletion::decode(&completion.encode()),
            Some(completion)
        );
        for length in 0..97 {
            assert!(CapturePipelineCompletion::decode(&completion.encode()[..length]).is_none());
        }
        for index in 0..12 {
            let mut bytes = completion.encode();
            bytes[1 + index * 8..9 + index * 8].copy_from_slice(&u64::MAX.to_le_bytes());
            // Identity may use all nonzero u64 bits; all other fields are bounded.
            assert_eq!(
                CapturePipelineCompletion::decode(&bytes).is_none(),
                index != 0
            );
        }
        for index in [0, 1, 11] {
            let mut bytes = completion.encode();
            bytes[1 + index * 8..9 + index * 8].fill(0);
            assert!(CapturePipelineCompletion::decode(&bytes).is_none());
        }
    }
}
