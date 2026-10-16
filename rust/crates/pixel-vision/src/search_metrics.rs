/// Optional per-call structural observations. No image content or template identity is retained.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct TemplateSearchMetrics {
    pub template_setup_us: u64,
    pub prefilter_build_us: u64,
    /// Includes validation, candidate traversal and exact comparison; not CPU-only time.
    pub search_us: u64,
    pub templates_visited: u64,
    pub prepared_cache_hits: u64,
    pub templates_prepared: u64,
    pub prefilters_built: u64,
    pub candidates_visited: u64,
    pub prefilter_rejected: u64,
    pub comparison_pixels: u64,
    /// Consumed pixel budget during integral construction only, excluding rejected candidates.
    pub prefilter_work: u64,
}

pub(crate) fn elapsed_us(started: std::time::Instant) -> u64 {
    u64::try_from(started.elapsed().as_micros()).unwrap_or(u64::MAX)
}
