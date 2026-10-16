use crate::{CapturedFrameId, FrameFormat, FrameMetadata, Rotation};

const MAX_SERIES_FRAMES: usize = 120;
const MAX_SERIES_DURATION_NANOS: u64 = 60_000_000_000;
const MAX_TARGET_FPS: u16 = 120;
const NANOS_PER_SECOND: u64 = 1_000_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureSeriesConfig {
    pub max_frames: usize,
    pub max_duration_nanos: u64,
    pub target_fps: u16,
    pub allow_partial: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureSeriesSample {
    pub capture: CapturedFrameId,
    pub timestamp_nanos: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureSeriesResult {
    pub samples: Vec<CaptureSeriesSample>,
    pub dropped_frames: u32,
    pub complete: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaptureSeriesStopReason {
    FrameLimit,
    DurationLimit,
    GeometryChanged,
    Cancelled,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CaptureSeriesUpdate {
    Accepted,
    DroppedForCadence,
    Finished(CaptureSeriesResult, CaptureSeriesStopReason),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaptureSeriesError {
    InvalidFrameLimit,
    InvalidDuration,
    InvalidTargetFps,
    TimestampNotMonotonic,
    GeometryChanged,
    AlreadyFinished,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct FrameGeometry {
    width: u32,
    height: u32,
    row_stride: u32,
    pixel_stride: u8,
    format: FrameFormat,
    rotation: Rotation,
    snapshot_id: u64,
}

impl From<FrameMetadata> for FrameGeometry {
    fn from(metadata: FrameMetadata) -> Self {
        Self {
            width: metadata.width,
            height: metadata.height,
            row_stride: metadata.row_stride,
            pixel_stride: metadata.pixel_stride,
            format: metadata.format,
            rotation: metadata.rotation,
            snapshot_id: metadata.snapshot_id,
        }
    }
}

/// Event-driven policy for selecting a bounded series from incoming capture events.
///
/// This collector never sleeps or polls. The capture backend pushes events into it, and the
/// caller remains responsible for retaining or releasing the referenced captures.
#[derive(Debug)]
pub struct CaptureSeriesCollector {
    config: CaptureSeriesConfig,
    samples: Vec<CaptureSeriesSample>,
    geometry: Option<FrameGeometry>,
    started_at: Option<u64>,
    last_seen_at: Option<u64>,
    last_accepted_at: Option<u64>,
    dropped_frames: u32,
    finished: bool,
}

impl CaptureSeriesCollector {
    /// Creates a collector after validating all hard resource limits.
    ///
    /// # Errors
    ///
    /// Rejects zero or excessive frame, duration, and FPS limits.
    pub fn new(config: CaptureSeriesConfig) -> Result<Self, CaptureSeriesError> {
        if !(1..=MAX_SERIES_FRAMES).contains(&config.max_frames) {
            return Err(CaptureSeriesError::InvalidFrameLimit);
        }
        if !(1..=MAX_SERIES_DURATION_NANOS).contains(&config.max_duration_nanos) {
            return Err(CaptureSeriesError::InvalidDuration);
        }
        if !(1..=MAX_TARGET_FPS).contains(&config.target_fps) {
            return Err(CaptureSeriesError::InvalidTargetFps);
        }
        Ok(Self {
            config,
            samples: Vec::with_capacity(config.max_frames),
            geometry: None,
            started_at: None,
            last_seen_at: None,
            last_accepted_at: None,
            dropped_frames: 0,
            finished: false,
        })
    }

    /// Consumes one capture event and deterministically accepts, drops, or finishes the series.
    ///
    /// # Errors
    ///
    /// Rejects non-monotonic timestamps, geometry changes when partial results are disabled, and
    /// events received after the series is terminal.
    pub fn push(
        &mut self,
        capture: CapturedFrameId,
        metadata: FrameMetadata,
    ) -> Result<CaptureSeriesUpdate, CaptureSeriesError> {
        if self.finished {
            return Err(CaptureSeriesError::AlreadyFinished);
        }
        if self
            .last_seen_at
            .is_some_and(|last| metadata.timestamp_nanos <= last)
        {
            self.clear_terminal();
            return Err(CaptureSeriesError::TimestampNotMonotonic);
        }
        self.last_seen_at = Some(metadata.timestamp_nanos);

        let incoming_geometry = FrameGeometry::from(metadata);
        if let Some(geometry) = self.geometry {
            if geometry != incoming_geometry {
                if self.config.allow_partial && !self.samples.is_empty() {
                    return Ok(self.finish(false, CaptureSeriesStopReason::GeometryChanged));
                }
                self.clear_terminal();
                return Err(CaptureSeriesError::GeometryChanged);
            }
        } else {
            self.geometry = Some(incoming_geometry);
            self.started_at = Some(metadata.timestamp_nanos);
        }

        let Some(started_at) = self.started_at else {
            self.clear_terminal();
            return Err(CaptureSeriesError::AlreadyFinished);
        };
        if metadata.timestamp_nanos.saturating_sub(started_at) > self.config.max_duration_nanos {
            return Ok(self.finish(true, CaptureSeriesStopReason::DurationLimit));
        }

        let minimum_interval = NANOS_PER_SECOND / u64::from(self.config.target_fps);
        if self
            .last_accepted_at
            .is_some_and(|last| metadata.timestamp_nanos.saturating_sub(last) < minimum_interval)
        {
            self.dropped_frames = self.dropped_frames.saturating_add(1);
            return Ok(CaptureSeriesUpdate::DroppedForCadence);
        }

        self.samples.push(CaptureSeriesSample {
            capture,
            timestamp_nanos: metadata.timestamp_nanos,
        });
        self.last_accepted_at = Some(metadata.timestamp_nanos);
        if self.samples.len() == self.config.max_frames {
            return Ok(self.finish(true, CaptureSeriesStopReason::FrameLimit));
        }
        Ok(CaptureSeriesUpdate::Accepted)
    }

    /// Ends the series in response to cancellation.
    #[must_use]
    pub fn cancel(mut self) -> Option<CaptureSeriesResult> {
        if self.finished || self.samples.is_empty() || !self.config.allow_partial {
            self.samples.clear();
            return None;
        }
        match self.finish(false, CaptureSeriesStopReason::Cancelled) {
            CaptureSeriesUpdate::Finished(result, _) => Some(result),
            CaptureSeriesUpdate::Accepted | CaptureSeriesUpdate::DroppedForCadence => None,
        }
    }

    fn finish(&mut self, complete: bool, reason: CaptureSeriesStopReason) -> CaptureSeriesUpdate {
        self.finished = true;
        CaptureSeriesUpdate::Finished(
            CaptureSeriesResult {
                samples: std::mem::take(&mut self.samples),
                dropped_frames: self.dropped_frames,
                complete,
            },
            reason,
        )
    }

    fn clear_terminal(&mut self) {
        self.samples.clear();
        self.finished = true;
    }
}

#[cfg(test)]
mod tests {
    use super::{
        CaptureSeriesCollector, CaptureSeriesConfig, CaptureSeriesError, CaptureSeriesStopReason,
        CaptureSeriesUpdate,
    };
    use crate::{CapturedFrameId, FrameFormat, FrameMetadata, Rotation};

    fn metadata(timestamp_nanos: u64) -> FrameMetadata {
        FrameMetadata {
            width: 2,
            height: 1,
            row_stride: 8,
            pixel_stride: 4,
            format: FrameFormat::Rgba8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos,
            snapshot_id: 1,
        }
    }

    fn config(allow_partial: bool) -> CaptureSeriesConfig {
        CaptureSeriesConfig {
            max_frames: 2,
            max_duration_nanos: 2_000_000_000,
            target_fps: 10,
            allow_partial,
        }
    }

    #[test]
    fn cadence_drop_and_frame_limit_are_bounded() {
        let mut collector = CaptureSeriesCollector::new(config(false)).expect("collector");
        assert_eq!(
            collector.push(CapturedFrameId(1), metadata(1)),
            Ok(CaptureSeriesUpdate::Accepted)
        );
        assert_eq!(
            collector.push(CapturedFrameId(2), metadata(50_000_001)),
            Ok(CaptureSeriesUpdate::DroppedForCadence)
        );
        let CaptureSeriesUpdate::Finished(result, reason) = collector
            .push(CapturedFrameId(3), metadata(100_000_001))
            .expect("finish")
        else {
            panic!("expected finished series");
        };
        assert_eq!(reason, CaptureSeriesStopReason::FrameLimit);
        assert_eq!(result.samples.len(), 2);
        assert_eq!(result.dropped_frames, 1);
        assert!(result.complete);
    }

    #[test]
    fn geometry_change_returns_partial_only_when_enabled() {
        let mut partial = CaptureSeriesCollector::new(config(true)).expect("collector");
        partial
            .push(CapturedFrameId(1), metadata(1))
            .expect("first");
        let mut changed = metadata(100_000_001);
        changed.width = 3;
        changed.row_stride = 12;
        let CaptureSeriesUpdate::Finished(result, reason) = partial
            .push(CapturedFrameId(2), changed)
            .expect("partial result")
        else {
            panic!("expected partial result");
        };
        assert_eq!(reason, CaptureSeriesStopReason::GeometryChanged);
        assert_eq!(result.samples.len(), 1);
        assert!(!result.complete);

        let mut strict = CaptureSeriesCollector::new(config(false)).expect("collector");
        strict.push(CapturedFrameId(1), metadata(1)).expect("first");
        assert_eq!(
            strict.push(CapturedFrameId(2), changed),
            Err(CaptureSeriesError::GeometryChanged)
        );
        assert_eq!(
            strict.push(CapturedFrameId(3), metadata(200_000_001)),
            Err(CaptureSeriesError::AlreadyFinished)
        );
    }

    #[test]
    fn limits_and_non_monotonic_timestamps_fail_closed() {
        assert_eq!(
            CaptureSeriesCollector::new(CaptureSeriesConfig {
                max_frames: 0,
                ..config(false)
            })
            .expect_err("zero frames"),
            CaptureSeriesError::InvalidFrameLimit
        );
        let mut collector = CaptureSeriesCollector::new(config(false)).expect("collector");
        collector
            .push(CapturedFrameId(1), metadata(10))
            .expect("first");
        assert_eq!(
            collector.push(CapturedFrameId(2), metadata(10)),
            Err(CaptureSeriesError::TimestampNotMonotonic)
        );
    }
}
