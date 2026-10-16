use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PositionRandomization {
    pub enabled: bool,
    pub x_px: u16,
    pub y_px: u16,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WaitRandomizationUnit {
    #[default]
    Milliseconds,
    Percent,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WaitRandomization {
    pub enabled: bool,
    pub unit: WaitRandomizationUnit,
    pub min: i32,
    pub max: i32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectRandomization {
    pub touch: PositionRandomization,
    pub recognition: PositionRandomization,
    pub wait: WaitRandomization,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RandomizationMode {
    #[default]
    Follow,
    Disabled,
    Custom,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NodeRandomization {
    pub mode: RandomizationMode,
    pub position: PositionRandomization,
    pub wait: WaitRandomization,
}

impl PositionRandomization {
    #[must_use]
    pub const fn valid(self) -> bool {
        self.x_px <= 4096 && self.y_px <= 4096
    }
}
impl WaitRandomization {
    #[must_use]
    pub const fn valid(self) -> bool {
        let limit = match self.unit {
            WaitRandomizationUnit::Milliseconds => 60_000,
            WaitRandomizationUnit::Percent => 100,
        };
        self.min >= -limit && self.max <= limit && self.min <= self.max
    }
}
impl ProjectRandomization {
    #[must_use]
    pub const fn valid(&self) -> bool {
        self.touch.valid() && self.recognition.valid() && self.wait.valid()
    }
    #[must_use]
    pub const fn active(&self) -> bool {
        self.touch.enabled || self.recognition.enabled || self.wait.enabled
    }
}
impl NodeRandomization {
    #[must_use]
    pub const fn valid(&self) -> bool {
        self.position.valid() && self.wait.valid()
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn ranges_are_bounded_even_when_disabled() {
        use super::{PositionRandomization, WaitRandomization, WaitRandomizationUnit};
        assert!(!PositionRandomization {
            enabled: false,
            x_px: 4097,
            y_px: 0
        }
        .valid());
        assert!(!WaitRandomization {
            enabled: false,
            unit: WaitRandomizationUnit::Percent,
            min: 0,
            max: 101
        }
        .valid());
        assert!(!WaitRandomization {
            enabled: true,
            unit: WaitRandomizationUnit::Milliseconds,
            min: 1,
            max: 0
        }
        .valid());
        assert!(WaitRandomization {
            enabled: true,
            unit: WaitRandomizationUnit::Percent,
            min: -100,
            max: 100
        }
        .valid());
    }
}
