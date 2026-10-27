//! Explicit Studio/runtime color membership; never a second template matcher.
use serde::{Deserialize, Serialize};

pub const MAX_RULES: usize = 32;
pub const MAX_JSON_BYTES: usize = 8192;
pub const MAX_COMPARISONS: u64 = 64_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Space {
    Absolute,
    Relative,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Output {
    Binary,
    Keep,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Rule {
    pub rgb: u32,
    pub red_tolerance: u8,
    pub green_tolerance: u8,
    pub blue_tolerance: u8,
    pub exclude: bool,
    pub space: Space,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Filter {
    pub version: u8,
    pub space: Space,
    pub output: Output,
    pub invert: bool,
    pub rules: Vec<Rule>,
}

impl Filter {
    /// # Errors
    /// Rejects unknown fields, invalid types/versions and oversized rule sets.
    pub fn parse(text: &str) -> Result<Self, &'static str> {
        if text.is_empty() || text.len() > MAX_JSON_BYTES {
            return Err("invalid filter byte budget");
        }
        let filter: Self = serde_json::from_str(text).map_err(|_| "invalid color filter JSON")?;
        filter.validate(1)?;
        Ok(filter)
    }

    /// # Errors
    /// Rejects empty rules, invalid colors and work exceeding the fixed budget.
    pub fn validate(&self, pixels: u64) -> Result<(), &'static str> {
        if self.version != 1
            || self.rules.is_empty()
            || self.rules.len() > MAX_RULES
            || self
                .rules
                .iter()
                .any(|r| r.rgb > 0xff_ffff || r.space == Space::Custom)
        {
            return Err("invalid color filter rules");
        }
        if pixels == 0
            || pixels > 4_194_304
            || pixels.saturating_mul(self.rules.len() as u64) > MAX_COMPARISONS
        {
            return Err("color filter exceeds pixel-rule budget");
        }
        Ok(())
    }

    #[must_use]
    pub fn pixel(&self, pixel: [u8; 4]) -> [u8; 4] {
        self.prepare().pixel(pixel)
    }

    #[must_use]
    pub fn prepare(&self) -> PreparedFilter<'_> {
        PreparedFilter {
            filter: self,
            has_includes: self.rules.iter().any(|r| !r.exclude),
        }
    }

    fn render(&self, pixel: [u8; 4], selected: bool) -> [u8; 4] {
        match self.output {
            Output::Keep => {
                if selected {
                    pixel
                } else {
                    [0; 4]
                }
            }
            Output::Binary => {
                let value = if selected { 255 } else { 0 };
                [value, value, value, pixel[3]]
            }
        }
    }
}

pub struct PreparedFilter<'a> {
    filter: &'a Filter,
    has_includes: bool,
}
impl PreparedFilter<'_> {
    #[must_use]
    pub fn pixel(&self, pixel: [u8; 4]) -> [u8; 4] {
        if pixel[3] == 0 {
            return [0; 4];
        }
        let filter = self.filter;
        let mut included = !self.has_includes;
        for rule in &filter.rules {
            if rule.matches(
                pixel,
                if filter.space == Space::Custom {
                    rule.space
                } else {
                    filter.space
                },
            ) {
                if rule.exclude {
                    return filter.render(pixel, filter.invert);
                }
                included = true;
            }
        }
        filter.render(pixel, included ^ filter.invert)
    }
}

impl Rule {
    fn matches(&self, pixel: [u8; 4], space: Space) -> bool {
        let [_, red, green, blue] = self.rgb.to_be_bytes();
        let dr = i32::from(pixel[0]) - i32::from(red);
        let dg = i32::from(pixel[1]) - i32::from(green);
        let db = i32::from(pixel[2]) - i32::from(blue);
        let (tr, tg, tb) = (
            i32::from(self.red_tolerance),
            i32::from(self.green_tolerance),
            i32::from(self.blue_tolerance),
        );
        if space == Space::Relative {
            (dr - dg).abs() <= tr + tg && (dr - db).abs() <= tr + tb && (dg - db).abs() <= tg + tb
        } else {
            dr.abs() <= tr && dg.abs() <= tg && db.abs() <= tb
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn rule(rgb: u32, exclude: bool) -> Rule {
        Rule {
            rgb,
            red_tolerance: 0,
            green_tolerance: 0,
            blue_tolerance: 0,
            exclude,
            space: Space::Absolute,
        }
    }
    fn filter() -> Filter {
        Filter {
            version: 1,
            space: Space::Absolute,
            output: Output::Binary,
            invert: false,
            rules: vec![rule(0x00ff_0000, false)],
        }
    }
    #[test]
    fn golden_absolute_relative_alpha_and_exclusion() {
        let mut f = filter();
        assert_eq!(f.pixel([255, 0, 0, 128]), [255, 255, 255, 128]);
        assert_eq!(f.pixel([254, 0, 0, 255]), [0, 0, 0, 255]);
        assert_eq!(f.pixel([255, 0, 0, 0]), [0, 0, 0, 0]);
        f.rules[0] = rule(0x0080_6040, false);
        f.space = Space::Relative;
        assert_eq!(f.pixel([160, 128, 96, 255]), [255; 4]);
        assert_eq!(f.pixel([160, 129, 96, 255]), [0, 0, 0, 255]);
        f.space = Space::Custom;
        f.rules[0].space = Space::Relative;
        f.rules.push(rule(0x00a0_8060, true));
        assert_eq!(f.pixel([160, 128, 96, 255]), [0, 0, 0, 255]);
        f.invert = true;
        assert_eq!(f.pixel([160, 128, 96, 255]), [255; 4]);
        f.output = Output::Keep;
        assert_eq!(f.pixel([160, 128, 96, 128]), [160, 128, 96, 128]);
        f.invert = false;
        f.rules = vec![rule(0, true)];
        assert_eq!(f.pixel([20, 30, 40, 255]), [20, 30, 40, 255]);
        assert_eq!(f.pixel([0, 0, 0, 255]), [0; 4]);
    }
    #[test]
    fn strict_parse_and_work_budgets() {
        let f = filter();
        let text = serde_json::to_string(&f).unwrap();
        assert_eq!(Filter::parse(&text).unwrap(), f);
        assert!(Filter::parse(&text.replace("\"version\":1", "\"version\":2")).is_err());
        assert!(
            Filter::parse(&text.replace("\"version\":1", "\"extra\":1,\"version\":1")).is_err()
        );
        assert!(Filter::parse(&text.replace("16711680", "16777216")).is_err());
        assert!(Filter::parse(&"x".repeat(MAX_JSON_BYTES + 1)).is_err());
        let mut large = f.clone();
        large.rules = vec![rule(0, false); 32];
        assert!(large.validate(4_194_304).is_err());
        assert!(large.validate(2_000_000).is_ok());
        large.rules.push(rule(0, false));
        assert!(large.validate(1).is_err());
    }
}
