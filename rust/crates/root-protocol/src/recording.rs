//! Low-frequency, bounded recording data. No shell strings or device paths are supplied by clients.
pub const MAX_ACTIONS: usize = 512;
pub const ACTION_BYTES: usize = 32;
pub const RESULT_HEADER_BYTES: usize = 24;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordingConfig {
    pub token: u64,
    pub width: u32,
    pub height: u32,
    pub rotation: u32,
    pub max_duration_ms: u32,
    pub event_budget: u32,
    pub deadline_success: bool,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordingFinish {
    pub token: u64,
    pub cancel: bool,
    pub discard_current_touch: bool,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RecordingAction {
    pub kind: u32,
    pub start_ms: u32,
    pub duration_ms: u32,
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
    pub key_code: u32,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecordingResult {
    pub token: u64,
    pub elapsed_ms: u32,
    pub status: u32,
    pub skipped_gestures: u32,
    pub actions: Vec<RecordingAction>,
}

impl RecordingConfig {
    /// Checks the authenticated recording's token, screen and resource limits.
    ///
    /// # Errors
    /// Returns `RECORDING_CONFIG_INVALID` when any field exceeds the wire contract.
    pub fn validate(self) -> Result<Self, &'static str> {
        if self.token == 0
            || self.token > i64::MAX as u64
            || !(1..=8192).contains(&self.width)
            || !(1..=8192).contains(&self.height)
            || u64::from(self.width) * u64::from(self.height) > 33_554_432
            || self.rotation > 3
            || !(1000..=60000).contains(&self.max_duration_ms)
            || !(128..=16384).contains(&self.event_budget)
        {
            Err("RECORDING_CONFIG_INVALID")
        } else {
            Ok(self)
        }
    }
    #[must_use]
    pub fn encode(self) -> Vec<u8> {
        let mut bytes = self.token.to_le_bytes().to_vec();
        for value in [
            self.width,
            self.height,
            self.rotation,
            self.max_duration_ms,
            self.event_budget,
            u32::from(self.deadline_success),
        ] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes
    }
    /// Decodes an exact config frame without accepting trailing or reserved data.
    ///
    /// # Errors
    /// Rejects invalid wire sizes, reserved bits, tokens and resource limits.
    pub fn decode(bytes: &[u8]) -> Result<Self, &'static str> {
        if bytes.len() != 32 || read_u32(bytes, 28)? > 1 {
            return Err("RECORDING_CONFIG_INVALID");
        }
        Self {
            token: read_u64(bytes, 0)?,
            width: read_u32(bytes, 8)?,
            height: read_u32(bytes, 12)?,
            rotation: read_u32(bytes, 16)?,
            max_duration_ms: read_u32(bytes, 20)?,
            event_budget: read_u32(bytes, 24)?,
            deadline_success: read_u32(bytes, 28)? == 1,
        }
        .validate()
    }
}
impl RecordingFinish {
    #[must_use]
    pub fn encode(self) -> Vec<u8> {
        let mut bytes = self.token.to_le_bytes().to_vec();
        bytes.extend_from_slice(
            &(u32::from(self.cancel) | (u32::from(self.discard_current_touch) << 1)).to_le_bytes(),
        );
        bytes.extend_from_slice(&0u32.to_le_bytes());
        bytes
    }
    /// Decodes the bounded finish flags and session-owned recording token.
    ///
    /// # Errors
    /// Rejects a malformed frame, invalid token or unknown finish flags.
    pub fn decode(bytes: &[u8]) -> Result<Self, &'static str> {
        if bytes.len() != 16 || read_u32(bytes, 12)? != 0 {
            return Err("RECORDING_FINISH_INVALID");
        }
        let token = read_u64(bytes, 0)?;
        let flags = read_u32(bytes, 8)?;
        if token == 0 || token > i64::MAX as u64 || flags > 3 {
            return Err("RECORDING_FINISH_INVALID");
        }
        Ok(Self {
            token,
            cancel: flags & 1 != 0,
            discard_current_touch: flags & 2 != 0,
        })
    }
}
impl RecordingAction {
    #[must_use]
    pub fn valid(self) -> bool {
        match self.kind {
            1..=3 => {
                (1..=5000).contains(&self.duration_ms)
                    && self.key_code == 0
                    && [self.x1, self.y1, self.x2, self.y2]
                        .iter()
                        .all(|point| (0..8192).contains(point))
            }
            4 => {
                matches!(self.key_code, 3 | 4 | 24 | 25 | 66 | 82)
                    && self.duration_ms == 0
                    && [self.x1, self.y1, self.x2, self.y2] == [0; 4]
            }
            _ => false,
        }
    }
    pub fn encode(self, bytes: &mut Vec<u8>) {
        for value in [
            self.kind,
            self.start_ms,
            self.duration_ms,
            self.x1.cast_unsigned(),
            self.y1.cast_unsigned(),
            self.x2.cast_unsigned(),
            self.y2.cast_unsigned(),
            self.key_code,
        ] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
    }
}
impl RecordingResult {
    #[must_use]
    pub fn encode(&self) -> Vec<u8> {
        let mut bytes = self.token.to_le_bytes().to_vec();
        for value in [
            u32::try_from(self.actions.len()).unwrap_or(u32::MAX),
            self.elapsed_ms,
            self.status,
            self.skipped_gestures,
        ] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        for action in &self.actions {
            action.encode(&mut bytes);
        }
        bytes
    }
    /// Decodes a complete, bounded result; partial gestures are never accepted.
    ///
    /// # Errors
    /// Rejects malformed counts, timestamps, actions or nonempty failure payloads.
    pub fn decode(bytes: &[u8]) -> Result<Self, &'static str> {
        if bytes.len() < RESULT_HEADER_BYTES
            || bytes.len() > RESULT_HEADER_BYTES + MAX_ACTIONS * ACTION_BYTES
        {
            return Err("RECORDING_RESULT_INVALID");
        }
        let token = read_u64(bytes, 0)?;
        let count = read_u32(bytes, 8)? as usize;
        let elapsed_ms = read_u32(bytes, 12)?;
        let status = read_u32(bytes, 16)?;
        let skipped_gestures = read_u32(bytes, 20)?;
        if token == 0
            || token > i64::MAX as u64
            || count > MAX_ACTIONS
            || bytes.len() != RESULT_HEADER_BYTES + count * ACTION_BYTES
            || elapsed_ms > 60000
            || status > 8
            || u64::from(skipped_gestures) + count as u64 > MAX_ACTIONS as u64
            || status != 0 && (count != 0 || skipped_gestures != 0)
        {
            return Err("RECORDING_RESULT_INVALID");
        }
        let mut actions = Vec::with_capacity(count);
        let mut previous = 0;
        for chunk in bytes[RESULT_HEADER_BYTES..].chunks_exact(ACTION_BYTES) {
            let action = RecordingAction {
                kind: read_u32(chunk, 0)?,
                start_ms: read_u32(chunk, 4)?,
                duration_ms: read_u32(chunk, 8)?,
                x1: read_i32(chunk, 12)?,
                y1: read_i32(chunk, 16)?,
                x2: read_i32(chunk, 20)?,
                y2: read_i32(chunk, 24)?,
                key_code: read_u32(chunk, 28)?,
            };
            if !action.valid()
                || action.start_ms < previous
                || action.start_ms.saturating_add(action.duration_ms) > elapsed_ms
            {
                return Err("RECORDING_RESULT_INVALID");
            }
            previous = action.start_ms + action.duration_ms;
            actions.push(action);
        }
        Ok(Self {
            token,
            elapsed_ms,
            status,
            skipped_gestures,
            actions,
        })
    }
}
fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, &'static str> {
    bytes
        .get(offset..offset + 4)
        .and_then(|slice| slice.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or("RECORDING_WIRE_INVALID")
}
fn read_i32(bytes: &[u8], offset: usize) -> Result<i32, &'static str> {
    bytes
        .get(offset..offset + 4)
        .and_then(|slice| slice.try_into().ok())
        .map(i32::from_le_bytes)
        .ok_or("RECORDING_WIRE_INVALID")
}
fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, &'static str> {
    bytes
        .get(offset..offset + 8)
        .and_then(|slice| slice.try_into().ok())
        .map(u64::from_le_bytes)
        .ok_or("RECORDING_WIRE_INVALID")
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> RecordingConfig {
        RecordingConfig {
            token: 11,
            width: 1080,
            height: 1920,
            rotation: 0,
            max_duration_ms: 60000,
            event_budget: 8192,
            deadline_success: false,
        }
    }
    #[test]
    fn fixed_config_and_finish_reject_flags_dimensions_token_or_trailing_data() {
        assert_eq!(
            RecordingConfig::decode(&config().encode()).unwrap(),
            config()
        );
        for change in [
            RecordingConfig {
                token: 0,
                ..config()
            },
            RecordingConfig {
                rotation: 4,
                ..config()
            },
            RecordingConfig {
                width: 8192,
                height: 8192,
                ..config()
            },
            RecordingConfig {
                event_budget: 1,
                ..config()
            },
        ] {
            assert!(change.validate().is_err());
        }
        let finish = RecordingFinish {
            token: 11,
            cancel: true,
            discard_current_touch: true,
        };
        assert_eq!(RecordingFinish::decode(&finish.encode()).unwrap(), finish);
        let mut bytes = finish.encode();
        bytes[8] = 4;
        assert!(RecordingFinish::decode(&bytes).is_err());
        let mut bytes = config().encode();
        bytes.push(0);
        assert!(RecordingConfig::decode(&bytes).is_err());
    }
    #[test]
    fn result_count_time_shape_and_cancel_payloads_are_untrusted() {
        let action = RecordingAction {
            kind: 1,
            start_ms: 10,
            duration_ms: 10,
            x1: 1,
            y1: 2,
            x2: 1,
            y2: 2,
            key_code: 0,
        };
        let result = RecordingResult {
            token: 11,
            elapsed_ms: 100,
            status: 0,
            skipped_gestures: 2,
            actions: vec![action],
        };
        assert_eq!(RecordingResult::decode(&result.encode()).unwrap(), result);
        for result in [
            RecordingResult {
                status: 4,
                ..result.clone()
            },
            RecordingResult {
                elapsed_ms: 19,
                ..result.clone()
            },
            RecordingResult {
                skipped_gestures: 512,
                ..result.clone()
            },
            RecordingResult {
                actions: vec![action, action],
                ..result.clone()
            },
        ] {
            assert!(RecordingResult::decode(&result.encode()).is_err());
        }
        let mut bytes = result.encode();
        bytes.pop();
        assert!(RecordingResult::decode(&bytes).is_err());
        assert!(RecordingResult::decode(&vec![0; 24 + MAX_ACTIONS * ACTION_BYTES + 1]).is_err());
    }

    #[test]
    fn timed_recording_flag_roundtrips_and_unknown_flags_are_rejected() {
        let timed = RecordingConfig {
            deadline_success: true,
            ..config()
        };
        assert_eq!(RecordingConfig::decode(&timed.encode()).unwrap(), timed);
        let mut bytes = timed.encode();
        bytes[28] = 2;
        assert!(RecordingConfig::decode(&bytes).is_err());
    }
}
