use super::{anchor_bucket, Color, PixelRect};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct RegionHistogram {
    pub rect: PixelRect,
    pub relevant: u64,
    pub majority: u64,
}

impl Default for RegionHistogram {
    fn default() -> Self {
        Self {
            rect: PixelRect {
                left: 0,
                top: 0,
                right: 0,
                bottom: 0,
            },
            relevant: 0,
            majority: 0,
        }
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub(super) struct AdditionalTemplateFilter {
    pub auxiliary: Option<(usize, u64)>,
    pub regions: [RegionHistogram; 4],
}

impl AdditionalTemplateFilter {
    pub fn conditions(&self, allowed: u64) -> Vec<RegionCondition> {
        self.regions
            .iter()
            .filter_map(|region| {
                let area = u64::from(region.rect.width()) * u64::from(region.rect.height());
                let required = region.majority.saturating_sub(allowed);
                let maximum = area - (region.relevant - region.majority).saturating_sub(allowed);
                (area != 0 && (required != 0 || maximum < area)).then_some(RegionCondition {
                    rect: region.rect,
                    required,
                    maximum,
                })
            })
            .collect()
    }

    pub fn prepare(
        pixels: &[Color],
        width: u32,
        height: u32,
        ignore: bool,
        majority: Option<(Color, u64)>,
    ) -> Self {
        let Some((primary, _)) = majority else {
            return Self::default();
        };
        let mut result = Self::default();
        let mut histogram = vec![0_u32; 8192];
        let middle_x = width / 2;
        let middle_y = height / 2;
        result.regions = std::array::from_fn(|index| RegionHistogram {
            rect: PixelRect {
                left: if index % 2 == 0 { 0 } else { middle_x },
                right: if index % 2 == 0 { middle_x } else { width },
                top: if index < 2 { 0 } else { middle_y },
                bottom: if index < 2 { middle_y } else { height },
            },
            ..RegionHistogram::default()
        });
        for (index, &color) in pixels.iter().enumerate() {
            if ignore && color.alpha == 0 {
                continue;
            }
            histogram[anchor_bucket(color)] += 1;
            let x = index % width as usize;
            let y = index / width as usize;
            let region = &mut result.regions
                [usize::from(x >= middle_x as usize) + 2 * usize::from(y >= middle_y as usize)];
            region.relevant += 1;
            region.majority += u64::from(color == primary);
        }
        result.auxiliary = histogram
            .into_iter()
            .enumerate()
            .filter(|(bucket, count)| *bucket != anchor_bucket(primary) && *count != 0)
            .max_by_key(|(bucket, count)| (*count, std::cmp::Reverse(*bucket)))
            .map(|(bucket, count)| (bucket, u64::from(count)));
        result
    }
}

#[derive(Debug)]
pub(super) struct IntegralCondition {
    pub counts: Vec<u32>,
    pub required: u64,
    pub maximum: u64,
}

#[derive(Debug, Clone, Copy)]
pub(super) struct RegionCondition {
    pub rect: PixelRect,
    pub required: u64,
    pub maximum: u64,
}

pub(super) fn integral_count(
    counts: &[u32],
    stride: usize,
    left: usize,
    top: usize,
    right: usize,
    bottom: usize,
) -> u64 {
    // Subtract vertical strips first to avoid transient overflow in a sum of prefixes.
    u64::from(counts[bottom * stride + right] - counts[top * stride + right])
        - u64::from(counts[bottom * stride + left] - counts[top * stride + left])
}
