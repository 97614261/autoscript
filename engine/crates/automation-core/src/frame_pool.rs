use std::collections::{BTreeMap, VecDeque};
use std::sync::Arc;

use crate::capture_series::{
    CaptureSeriesCollector, CaptureSeriesConfig, CaptureSeriesError, CaptureSeriesStopReason,
    CaptureSeriesUpdate,
};
use pixel_vision::{
    Color, ColorTolerance, ImageView, PatternSample, PixelFormat, PixelPoint, PreparedTemplate,
    SearchOptions, TemplateMatch, TemplateOptions, VisionError,
};
use runtime_scheduler::{ResourceId, ResourceKind, ResourceOwner, TaskResourceRegistry, TaskToken};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameFormat {
    Rgba8888,
    Bgra8888,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Rotation {
    Degrees0,
    Degrees90,
    Degrees180,
    Degrees270,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameMetadata {
    pub width: u32,
    pub height: u32,
    pub row_stride: u32,
    pub pixel_stride: u8,
    pub format: FrameFormat,
    pub rotation: Rotation,
    pub timestamp_nanos: u64,
    pub snapshot_id: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct FrameHandle {
    pub frame_id: u64,
    pub resource_id: ResourceId,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CapturedFrameId(pub u64);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct CaptureSeriesHandle {
    pub series_id: u64,
    pub resource_id: ResourceId,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FramePoolConfig {
    pub max_retained_frames: usize,
    pub max_total_bytes: usize,
    pub max_unleased_age_nanos: u64,
    pub max_templates: usize,
    pub max_template_bytes: usize,
    pub max_series_frames: usize,
}

impl Default for FramePoolConfig {
    fn default() -> Self {
        Self {
            max_retained_frames: 4,
            max_total_bytes: 64 * 1024 * 1024,
            max_unleased_age_nanos: 5_000_000_000,
            max_templates: 64,
            max_template_bytes: 32 * 1024 * 1024,
            max_series_frames: 120,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    InvalidGeometry,
    ByteLengthMismatch { expected: usize, actual: usize },
    FrameBudgetExceeded,
    FrameIdExhausted,
    InvalidTemplateName,
    DuplicateTemplate(String),
    TemplateBudgetExceeded,
    UnknownFrame(FrameHandle),
    UnknownCapture(CapturedFrameId),
    CaptureAlreadyLeased(CapturedFrameId),
    LeaseNotHeld(FrameHandle),
    Resource(String),
    Series(CaptureSeriesError),
    UnknownSeries(CaptureSeriesHandle),
    SeriesLeaseNotHeld(CaptureSeriesHandle),
    CaptureNotInSeries(CapturedFrameId),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameVisionError {
    Frame(FrameError),
    Vision(VisionError),
}

impl From<FrameError> for FrameVisionError {
    fn from(value: FrameError) -> Self {
        Self::Frame(value)
    }
}

impl From<VisionError> for FrameVisionError {
    fn from(value: VisionError) -> Self {
        Self::Vision(value)
    }
}

#[derive(Debug)]
struct FrameEntry {
    metadata: FrameMetadata,
    pixels: Arc<[u8]>,
    prepared_template: Option<Arc<PreparedTemplate>>,
    leased: bool,
    reserved_series_id: Option<u64>,
}

#[derive(Debug)]
struct SeriesEntry {
    collector: CaptureSeriesCollector,
    frame_bytes: usize,
    reserved_frames: usize,
    reserved_bytes: usize,
    captures: Vec<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SeriesPublish {
    Frame {
        capture: CapturedFrameId,
        timestamp_nanos: u64,
        finished: bool,
        dropped_frames: u32,
    },
    DroppedForCadence,
    Finished {
        partial: bool,
        dropped_frames: u32,
        reason: CaptureSeriesStopReason,
    },
}

#[derive(Debug)]
struct TemplateEntry {
    metadata: FrameMetadata,
    pixels: Arc<[u8]>,
    prepared: Arc<PreparedTemplate>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameView {
    pub handle: FrameHandle,
    pub metadata: FrameMetadata,
    pub pixels: Arc<[u8]>,
    prepared_template: Option<Arc<PreparedTemplate>>,
}

impl FrameView {
    /// Borrows this validated immutable frame as a scalar vision image.
    ///
    /// # Errors
    ///
    /// Rejects a row stride that cannot be represented by this target.
    pub fn image_view(&self) -> Result<ImageView<'_>, FrameError> {
        frame_image_view(self.metadata, &self.pixels)
    }
}

#[derive(Debug)]
pub struct FramePool {
    config: FramePoolConfig,
    next_frame_id: u64,
    total_bytes: usize,
    frames: BTreeMap<u64, FrameEntry>,
    capture_slots: VecDeque<u64>,
    templates: BTreeMap<String, TemplateEntry>,
    template_preparations: BTreeMap<[u8; 32], Arc<PreparedTemplate>>,
    template_bytes: usize,
    next_series_id: u64,
    series: BTreeMap<u64, SeriesEntry>,
    reserved_series_bytes: usize,
    reserved_series_frames: usize,
}

impl FramePool {
    #[must_use]
    pub fn new(config: FramePoolConfig) -> Self {
        Self {
            config,
            next_frame_id: 1,
            total_bytes: 0,
            frames: BTreeMap::new(),
            capture_slots: VecDeque::with_capacity(2),
            templates: BTreeMap::new(),
            template_preparations: BTreeMap::new(),
            template_bytes: 0,
            next_series_id: 1,
            series: BTreeMap::new(),
            reserved_series_bytes: 0,
            reserved_series_frames: 0,
        }
    }

    #[must_use]
    pub const fn total_bytes(&self) -> usize {
        self.total_bytes
    }

    #[must_use]
    pub fn frame_count(&self) -> usize {
        self.frames.len()
    }

    #[must_use]
    pub fn template_count(&self) -> usize {
        self.templates.len()
    }

    /// Number of distinct canonical template contents with cached preprocessing.
    #[must_use]
    pub fn prepared_template_count(&self) -> usize {
        self.template_preparations.len()
    }

    #[must_use]
    pub fn series_count(&self) -> usize {
        self.series.len()
    }

    #[must_use]
    pub const fn reserved_series_bytes(&self) -> usize {
        self.reserved_series_bytes
    }

    /// Registers one decoded, immutable project image under its canonical project path.
    ///
    /// # Errors
    ///
    /// Rejects unsafe paths, duplicate names, malformed pixels, or the template budget.
    pub fn register_template(
        &mut self,
        name: &str,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
    ) -> Result<(), FrameError> {
        if !valid_template_name(name) {
            return Err(FrameError::InvalidTemplateName);
        }
        let expected = validate_metadata(metadata)?;
        if pixels.len() != expected {
            return Err(FrameError::ByteLengthMismatch {
                expected,
                actual: pixels.len(),
            });
        }
        if self.templates.contains_key(name) {
            return Err(FrameError::DuplicateTemplate(name.to_owned()));
        }
        if self.templates.len() >= self.config.max_templates {
            return Err(FrameError::TemplateBudgetExceeded);
        }
        let view = frame_image_view(metadata, &pixels)?;
        let candidate = PreparedTemplate::prepare(view).map_err(|_| FrameError::InvalidGeometry)?;
        let content_hash = candidate.content_hash();
        let preparation_bytes = if self.template_preparations.contains_key(&content_hash) {
            0
        } else {
            candidate.estimated_bytes()
        };
        let added_bytes = pixels
            .len()
            .checked_add(preparation_bytes)
            .ok_or(FrameError::TemplateBudgetExceeded)?;
        if self.template_bytes.saturating_add(added_bytes) > self.config.max_template_bytes {
            return Err(FrameError::TemplateBudgetExceeded);
        }
        let prepared = self
            .template_preparations
            .entry(content_hash)
            .or_insert_with(|| Arc::new(candidate));
        self.template_bytes += added_bytes;
        self.templates.insert(
            name.to_owned(),
            TemplateEntry {
                metadata,
                pixels,
                prepared: Arc::clone(prepared),
            },
        );
        Ok(())
    }

    /// Creates a task-owned frame lease over a registered immutable template without copying its
    /// pixel buffer.
    ///
    /// # Errors
    ///
    /// Rejects an unknown template, exhausted frame budget, or resource conflicts.
    pub fn lease_template(
        &mut self,
        task: TaskToken,
        name: &str,
        resources: &mut TaskResourceRegistry,
    ) -> Result<FrameHandle, FrameError> {
        let template = self
            .templates
            .get(name)
            .ok_or(FrameError::InvalidTemplateName)?;
        let metadata = template.metadata;
        let pixels = Arc::clone(&template.pixels);
        let prepared = Arc::clone(&template.prepared);
        let capture = self.publish_owned(metadata, pixels, Some(prepared), false)?;
        match self.cache(task, capture, resources) {
            Ok(handle) => Ok(handle),
            Err(error) => {
                self.remove_frame(capture.0);
                Err(error)
            }
        }
    }

    /// Copies one validated capture into engine-owned immutable storage and leases it to `task`.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid geometry, byte lengths, pool budget or resource conflicts.
    pub fn insert(
        &mut self,
        task: TaskToken,
        metadata: FrameMetadata,
        pixels: &[u8],
        resources: &mut TaskResourceRegistry,
    ) -> Result<FrameHandle, FrameError> {
        let capture = self.publish_capture(metadata, pixels)?;
        match self.cache(task, capture, resources) {
            Ok(handle) => Ok(handle),
            Err(error) => {
                self.remove_frame(capture.0);
                Err(error)
            }
        }
    }

    /// Publishes the next unleased capture, recycling only unleased or expired storage.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid geometry or when every frame within budget is pinned.
    pub fn publish_capture(
        &mut self,
        metadata: FrameMetadata,
        pixels: &[u8],
    ) -> Result<CapturedFrameId, FrameError> {
        self.publish_capture_owned(metadata, Arc::from(pixels))
    }

    /// Publishes capture storage already owned by a reference-counted buffer.
    ///
    /// # Errors
    ///
    /// Returns an error for invalid geometry or when every frame within budget is pinned.
    pub fn publish_capture_owned(
        &mut self,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
    ) -> Result<CapturedFrameId, FrameError> {
        self.publish_owned(metadata, pixels, None, true)
    }

    fn publish_owned(
        &mut self,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
        prepared_template: Option<Arc<PreparedTemplate>>,
        track_capture: bool,
    ) -> Result<CapturedFrameId, FrameError> {
        let expected = validate_metadata(metadata)?;
        if pixels.len() != expected {
            return Err(FrameError::ByteLengthMismatch {
                expected,
                actual: pixels.len(),
            });
        }
        let pixel_length = pixels.len();
        self.reclaim_expired(metadata.timestamp_nanos);
        while self.frames.len() >= self.config.max_retained_frames
            || self
                .total_bytes
                .saturating_add(self.reserved_series_bytes)
                .saturating_add(pixels.len())
                > self.config.max_total_bytes
        {
            let Some(frame_id) = self
                .frames
                .iter()
                .find_map(|(id, entry)| (!entry.leased).then_some(*id))
            else {
                return Err(FrameError::FrameBudgetExceeded);
            };
            self.remove_frame(frame_id);
        }
        let frame_id = self.next_frame_id;
        self.next_frame_id = self
            .next_frame_id
            .checked_add(1)
            .ok_or(FrameError::FrameIdExhausted)?;
        self.frames.insert(
            frame_id,
            FrameEntry {
                metadata,
                pixels,
                prepared_template,
                leased: false,
                reserved_series_id: None,
            },
        );
        self.total_bytes += pixel_length;
        if track_capture {
            self.capture_slots.push_back(frame_id);
            while self.capture_slots.len() > 2 {
                if let Some(oldest) = self.capture_slots.pop_front() {
                    if self.frames.get(&oldest).is_some_and(|entry| !entry.leased) {
                        self.remove_frame(oldest);
                    }
                }
            }
        }
        Ok(CapturedFrameId(frame_id))
    }

    /// Pins one current capture to a Task resource lease.
    ///
    /// # Errors
    ///
    /// Rejects unknown captures, duplicate initial leases, or resource registry conflicts.
    pub fn cache(
        &mut self,
        task: TaskToken,
        capture: CapturedFrameId,
        resources: &mut TaskResourceRegistry,
    ) -> Result<FrameHandle, FrameError> {
        let entry = self
            .frames
            .get_mut(&capture.0)
            .ok_or(FrameError::UnknownCapture(capture))?;
        if entry.leased {
            return Err(FrameError::CaptureAlreadyLeased(capture));
        }
        entry.leased = true;
        let handle = FrameHandle {
            frame_id: capture.0,
            resource_id: ResourceId::try_new(ResourceKind::Frame, capture.0)
                .ok_or(FrameError::FrameIdExhausted)?,
        };
        if let Err(error) = resources.acquire_new(ResourceOwner::Task(task), handle.resource_id) {
            if let Some(entry) = self.frames.get_mut(&capture.0) {
                entry.leased = false;
            }
            return Err(FrameError::Resource(format!("{error:?}")));
        }
        Ok(handle)
    }

    /// Borrows an immutable frame only after validating the current Task lease.
    ///
    /// # Errors
    ///
    /// Returns an error for unknown handles or missing Task ownership.
    pub fn view(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
    ) -> Result<FrameView, FrameError> {
        let entry = self
            .frames
            .get(&handle.frame_id)
            .ok_or(FrameError::UnknownFrame(handle))?;
        if !resources.holds(ResourceOwner::Task(task), handle.resource_id) {
            return Err(FrameError::LeaseNotHeld(handle));
        }
        Ok(FrameView {
            handle,
            metadata: entry.metadata,
            pixels: Arc::clone(&entry.pixels),
            prepared_template: entry.prepared_template.as_ref().map(Arc::clone),
        })
    }

    /// Searches a leased immutable frame for the first matching color.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed search options, or exhausted comparison budgets.
    pub fn find_color(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        target: Color,
        tolerance: ColorTolerance,
        options: SearchOptions,
    ) -> Result<Option<PixelPoint>, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::find_color(
            frame.image_view()?,
            target,
            tolerance,
            options,
        )?)
    }

    /// Reads one color from a leased immutable frame.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed frames, or out-of-range coordinates.
    pub fn get_color(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        point: PixelPoint,
    ) -> Result<Color, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::get_color(frame.image_view()?, point)?)
    }

    /// Compares one pixel in a leased immutable frame with a target color.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed frames, or out-of-range coordinates.
    pub fn compare_color(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        point: PixelPoint,
        target: Color,
        tolerance: ColorTolerance,
    ) -> Result<bool, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::compare_color(
            frame.image_view()?,
            point,
            target,
            tolerance,
        )?)
    }

    /// Counts matching colors in a leased immutable frame up to a caller-provided limit.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed options/limits, or exhausted comparison budgets.
    #[allow(clippy::too_many_arguments)]
    pub fn count_color(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        target: Color,
        tolerance: ColorTolerance,
        options: SearchOptions,
        limit: usize,
    ) -> Result<usize, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::count_color(
            frame.image_view()?,
            target,
            tolerance,
            options,
            limit,
        )?)
    }

    /// Returns matching colors in deterministic row-major order up to a bounded limit.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed options/limits, or exhausted comparison budgets.
    #[allow(clippy::too_many_arguments)]
    pub fn find_all_colors(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        target: Color,
        tolerance: ColorTolerance,
        options: SearchOptions,
        limit: usize,
    ) -> Result<Vec<PixelPoint>, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::find_all_colors(
            frame.image_view()?,
            target,
            tolerance,
            options,
            limit,
        )?)
    }

    /// Searches a leased immutable frame for a signed-offset color pattern.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed patterns/options, or exhausted comparison budgets.
    pub fn find_pattern(
        &self,
        task: TaskToken,
        handle: FrameHandle,
        resources: &TaskResourceRegistry,
        samples: &[PatternSample],
        options: SearchOptions,
    ) -> Result<Option<PixelPoint>, FrameVisionError> {
        let frame = self.view(task, handle, resources)?;
        Ok(pixel_vision::find_pattern(
            frame.image_view()?,
            samples,
            options,
        )?)
    }

    /// Searches one leased frame using another leased frame as an immutable template.
    ///
    /// # Errors
    ///
    /// Rejects stale leases, malformed options, or exhausted comparison budgets.
    pub fn find_template(
        &self,
        task: TaskToken,
        screen_handle: FrameHandle,
        template_handle: FrameHandle,
        resources: &TaskResourceRegistry,
        options: TemplateOptions,
    ) -> Result<Option<TemplateMatch>, FrameVisionError> {
        let screen = self.view(task, screen_handle, resources)?;
        let template = self.view(task, template_handle, resources)?;
        if let Some(prepared) = template.prepared_template.as_deref() {
            Ok(pixel_vision::find_prepared_template(
                screen.image_view()?,
                prepared,
                options,
            )?)
        } else {
            Ok(pixel_vision::find_template(
                screen.image_view()?,
                template.image_view()?,
                options,
            )?)
        }
    }

    /// Reserves the worst-case storage for a bounded capture series using the first leased frame
    /// as its fixed geometry.
    ///
    /// # Errors
    ///
    /// Rejects invalid limits, stale leases, identifier exhaustion, or insufficient frame/byte
    /// budget before any additional capture is accepted.
    pub fn begin_series(
        &mut self,
        task: TaskToken,
        first: FrameHandle,
        resources: &mut TaskResourceRegistry,
        config: CaptureSeriesConfig,
    ) -> Result<CaptureSeriesHandle, FrameError> {
        let mut collector = CaptureSeriesCollector::new(config).map_err(FrameError::Series)?;
        let first_entry = self
            .frames
            .get(&first.frame_id)
            .ok_or(FrameError::UnknownFrame(first))?;
        if !resources.holds(ResourceOwner::Task(task), first.resource_id) {
            return Err(FrameError::LeaseNotHeld(first));
        }
        collector
            .push(CapturedFrameId(first.frame_id), first_entry.metadata)
            .map_err(FrameError::Series)?;
        let remaining_frames = config.max_frames.saturating_sub(1);
        if config.max_frames > self.config.max_series_frames
            || self
                .frames
                .len()
                .saturating_add(self.reserved_series_frames)
                .saturating_add(remaining_frames)
                > self.config.max_series_frames
        {
            return Err(FrameError::FrameBudgetExceeded);
        }
        let frame_bytes = first_entry.pixels.len();
        let reserved_bytes = frame_bytes
            .checked_mul(remaining_frames)
            .ok_or(FrameError::FrameBudgetExceeded)?;
        if self
            .total_bytes
            .saturating_add(self.reserved_series_bytes)
            .saturating_add(reserved_bytes)
            > self.config.max_total_bytes
        {
            return Err(FrameError::FrameBudgetExceeded);
        }
        let series_id = self.next_series_id;
        self.next_series_id = self
            .next_series_id
            .checked_add(1)
            .ok_or(FrameError::FrameIdExhausted)?;
        let handle = CaptureSeriesHandle {
            series_id,
            resource_id: ResourceId::try_new(ResourceKind::CaptureSeries, series_id)
                .ok_or(FrameError::FrameIdExhausted)?,
        };
        resources
            .acquire_new(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| FrameError::Resource(format!("{error:?}")))?;
        self.reserved_series_frames += remaining_frames;
        self.reserved_series_bytes += reserved_bytes;
        self.series.insert(
            series_id,
            SeriesEntry {
                collector,
                frame_bytes,
                reserved_frames: remaining_frames,
                reserved_bytes,
                captures: Vec::with_capacity(remaining_frames),
            },
        );
        Ok(handle)
    }

    /// Publishes one capture against a previously reserved series budget.
    ///
    /// # Errors
    ///
    /// Rejects unknown/finished series, malformed frames, geometry changes, timestamps, or bytes
    /// that differ from the pre-reserved fixed frame size.
    pub fn publish_series_capture_owned(
        &mut self,
        handle: CaptureSeriesHandle,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
    ) -> Result<SeriesPublish, FrameError> {
        let expected = validate_metadata(metadata)?;
        if pixels.len() != expected {
            return Err(FrameError::ByteLengthMismatch {
                expected,
                actual: pixels.len(),
            });
        }
        let frame_id = self.next_frame_id;
        let proposed = CapturedFrameId(frame_id);
        let update = {
            let series = self
                .series
                .get_mut(&handle.series_id)
                .ok_or(FrameError::UnknownSeries(handle))?;
            series.collector.push(proposed, metadata)
        };
        let update = match update {
            Ok(update) => update,
            Err(error) => {
                self.release_unused_series_budget(handle.series_id);
                return Err(FrameError::Series(error));
            }
        };
        match update {
            CaptureSeriesUpdate::DroppedForCadence => Ok(SeriesPublish::DroppedForCadence),
            CaptureSeriesUpdate::Accepted => {
                self.store_series_frame(handle, metadata, pixels, false, 0)
            }
            CaptureSeriesUpdate::Finished(result, reason) => {
                let includes_proposed = result
                    .samples
                    .last()
                    .is_some_and(|sample| sample.capture == proposed);
                if includes_proposed {
                    self.store_series_frame(handle, metadata, pixels, true, result.dropped_frames)
                } else {
                    self.release_unused_series_budget(handle.series_id);
                    Ok(SeriesPublish::Finished {
                        partial: !result.complete,
                        dropped_frames: result.dropped_frames,
                        reason,
                    })
                }
            }
        }
    }

    /// Converts a reserved series capture into a Task-owned immutable frame lease.
    ///
    /// # Errors
    ///
    /// Rejects stale handles, wrong Task ownership, or captures outside this series.
    pub fn cache_series_capture(
        &mut self,
        task: TaskToken,
        series_handle: CaptureSeriesHandle,
        capture: CapturedFrameId,
        resources: &mut TaskResourceRegistry,
    ) -> Result<FrameHandle, FrameError> {
        if !resources.holds(ResourceOwner::Task(task), series_handle.resource_id) {
            return Err(FrameError::SeriesLeaseNotHeld(series_handle));
        }
        let entry = self
            .frames
            .get_mut(&capture.0)
            .ok_or(FrameError::UnknownCapture(capture))?;
        if entry.reserved_series_id != Some(series_handle.series_id) || entry.leased {
            return Err(FrameError::CaptureNotInSeries(capture));
        }
        let handle = FrameHandle {
            frame_id: capture.0,
            resource_id: ResourceId::try_new(ResourceKind::Frame, capture.0)
                .ok_or(FrameError::FrameIdExhausted)?,
        };
        resources
            .acquire_new(ResourceOwner::Task(task), handle.resource_id)
            .map_err(|error| FrameError::Resource(format!("{error:?}")))?;
        entry.leased = true;
        entry.reserved_series_id = None;
        Ok(handle)
    }

    /// Releases a series reservation after completion, failure, cancellation, or Task cleanup.
    pub fn release_series_resource(&mut self, resource: ResourceId) -> bool {
        if resource.kind() != ResourceKind::CaptureSeries {
            return false;
        }
        let series_id = resource.local_id();
        let Some(series) = self.series.remove(&series_id) else {
            return false;
        };
        self.reserved_series_frames = self
            .reserved_series_frames
            .saturating_sub(series.reserved_frames);
        self.reserved_series_bytes = self
            .reserved_series_bytes
            .saturating_sub(series.reserved_bytes);
        let _ = series.collector.cancel();
        for frame_id in series.captures {
            if self
                .frames
                .get(&frame_id)
                .is_some_and(|entry| !entry.leased && entry.reserved_series_id == Some(series_id))
            {
                self.remove_frame(frame_id);
            }
        }
        true
    }

    fn store_series_frame(
        &mut self,
        handle: CaptureSeriesHandle,
        metadata: FrameMetadata,
        pixels: Arc<[u8]>,
        finished: bool,
        dropped_frames: u32,
    ) -> Result<SeriesPublish, FrameError> {
        let series = self
            .series
            .get_mut(&handle.series_id)
            .ok_or(FrameError::UnknownSeries(handle))?;
        if series.frame_bytes != pixels.len()
            || series.reserved_frames == 0
            || series.reserved_bytes < pixels.len()
        {
            return Err(FrameError::FrameBudgetExceeded);
        }
        let frame_id = self.next_frame_id;
        self.next_frame_id = self
            .next_frame_id
            .checked_add(1)
            .ok_or(FrameError::FrameIdExhausted)?;
        series.reserved_frames -= 1;
        series.reserved_bytes -= pixels.len();
        series.captures.push(frame_id);
        self.reserved_series_frames -= 1;
        self.reserved_series_bytes -= pixels.len();
        self.total_bytes += pixels.len();
        self.frames.insert(
            frame_id,
            FrameEntry {
                metadata,
                pixels,
                prepared_template: None,
                leased: false,
                reserved_series_id: Some(handle.series_id),
            },
        );
        if finished {
            self.release_unused_series_budget(handle.series_id);
        }
        Ok(SeriesPublish::Frame {
            capture: CapturedFrameId(frame_id),
            timestamp_nanos: metadata.timestamp_nanos,
            finished,
            dropped_frames,
        })
    }

    fn release_unused_series_budget(&mut self, series_id: u64) {
        if let Some(series) = self.series.get_mut(&series_id) {
            self.reserved_series_frames = self
                .reserved_series_frames
                .saturating_sub(series.reserved_frames);
            self.reserved_series_bytes = self
                .reserved_series_bytes
                .saturating_sub(series.reserved_bytes);
            series.reserved_frames = 0;
            series.reserved_bytes = 0;
        }
    }

    /// Removes storage after the scheduler reports that the final resource lease vanished.
    pub fn release_resource(&mut self, resource: ResourceId) -> bool {
        if resource.kind() == ResourceKind::CaptureSeries {
            return self.release_series_resource(resource);
        }
        if resource.kind() != ResourceKind::Frame {
            return false;
        }
        let frame_id = resource.local_id();
        let Some(entry) = self.frames.get_mut(&frame_id) else {
            return false;
        };
        entry.leased = false;
        if !self.capture_slots.contains(&frame_id) {
            self.remove_frame(frame_id);
        }
        true
    }

    /// Reclaims unleased frames older than the configured TTL.
    pub fn reclaim_expired(&mut self, now_nanos: u64) {
        let expired = self
            .frames
            .iter()
            .filter_map(|(id, entry)| {
                (!entry.leased
                    && now_nanos.saturating_sub(entry.metadata.timestamp_nanos)
                        >= self.config.max_unleased_age_nanos)
                    .then_some(*id)
            })
            .collect::<Vec<_>>();
        for frame_id in expired {
            self.remove_frame(frame_id);
        }
    }

    fn remove_frame(&mut self, frame_id: u64) {
        if let Some(entry) = self.frames.remove(&frame_id) {
            self.total_bytes = self.total_bytes.saturating_sub(entry.pixels.len());
            self.capture_slots.retain(|id| *id != frame_id);
        }
    }
}

fn frame_image_view(metadata: FrameMetadata, pixels: &[u8]) -> Result<ImageView<'_>, FrameError> {
    let format = match metadata.format {
        FrameFormat::Rgba8888 => PixelFormat::Rgba8888,
        FrameFormat::Bgra8888 => PixelFormat::Bgra8888,
    };
    Ok(ImageView {
        width: metadata.width,
        height: metadata.height,
        row_stride: usize::try_from(metadata.row_stride)
            .map_err(|_| FrameError::InvalidGeometry)?,
        format,
        pixels,
    })
}

fn valid_template_name(name: &str) -> bool {
    name.len() <= 256
        && name.starts_with("assets/images/")
        && !name.contains(['\\', '\0'])
        && name
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}

fn validate_metadata(metadata: FrameMetadata) -> Result<usize, FrameError> {
    if metadata.width == 0
        || metadata.height == 0
        || metadata.pixel_stride != 4
        || metadata.row_stride
            < metadata
                .width
                .saturating_mul(u32::from(metadata.pixel_stride))
    {
        return Err(FrameError::InvalidGeometry);
    }
    usize::try_from(metadata.row_stride)
        .ok()
        .and_then(|stride| {
            usize::try_from(metadata.height)
                .ok()
                .and_then(|height| stride.checked_mul(height))
        })
        .ok_or(FrameError::InvalidGeometry)
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use pixel_vision::{
        Color, ColorTolerance, PixelPoint, PixelRect, SearchOptions, SearchOrder, TemplateOptions,
    };
    use runtime_scheduler::{
        ResourceOwner, TaskGeneration, TaskId, TaskResourceRegistry, TaskToken,
    };

    use super::{FrameError, FrameFormat, FrameMetadata, FramePool, FramePoolConfig, Rotation};
    use crate::{CaptureSeriesConfig, CaptureSeriesError, SeriesPublish};

    fn task(id: u64) -> TaskToken {
        TaskToken {
            id: TaskId(id),
            generation: TaskGeneration(1),
        }
    }

    fn metadata() -> FrameMetadata {
        FrameMetadata {
            width: 2,
            height: 2,
            row_stride: 8,
            pixel_stride: 4,
            format: FrameFormat::Rgba8888,
            rotation: Rotation::Degrees0,
            timestamp_nanos: 10,
            snapshot_id: 3,
        }
    }

    #[test]
    fn immutable_frame_requires_task_lease_and_releases_final_storage() {
        let mut pool = FramePool::new(FramePoolConfig::default());
        let mut resources = TaskResourceRegistry::default();
        let handle = pool
            .insert(task(1), metadata(), &[7; 16], &mut resources)
            .expect("frame");
        assert_eq!(
            pool.view(task(1), handle, &resources)
                .expect("leased view")
                .pixels[0],
            7
        );
        assert_eq!(
            pool.view(task(2), handle, &resources),
            Err(FrameError::LeaseNotHeld(handle))
        );
        let released = resources.release_owner(ResourceOwner::Task(task(1)));
        assert_eq!(released, [handle.resource_id]);
        assert!(pool.release_resource(released[0]));
        pool.reclaim_expired(5_000_000_010);
        assert_eq!(pool.total_bytes(), 0);
    }

    #[test]
    fn pool_fails_closed_instead_of_overwriting_leased_frame() {
        let mut pool = FramePool::new(FramePoolConfig {
            max_retained_frames: 1,
            max_total_bytes: 16,
            ..FramePoolConfig::default()
        });
        let mut resources = TaskResourceRegistry::default();
        pool.insert(task(1), metadata(), &[1; 16], &mut resources)
            .expect("first frame");
        assert_eq!(
            pool.insert(task(1), metadata(), &[2; 16], &mut resources),
            Err(FrameError::FrameBudgetExceeded)
        );
    }

    #[test]
    fn capture_slots_recycle_unleased_frames_but_preserve_pinned_frames() {
        let mut pool = FramePool::new(FramePoolConfig {
            max_retained_frames: 3,
            max_total_bytes: 48,
            max_unleased_age_nanos: 100,
            ..FramePoolConfig::default()
        });
        let mut resources = TaskResourceRegistry::default();
        let first = pool
            .publish_capture(metadata(), &[1; 16])
            .expect("first capture");
        let pinned = pool
            .cache(task(1), first, &mut resources)
            .expect("pin first");
        let mut second_metadata = metadata();
        second_metadata.timestamp_nanos = 11;
        let second = pool
            .publish_capture(second_metadata, &[2; 16])
            .expect("second capture");
        let mut third_metadata = metadata();
        third_metadata.timestamp_nanos = 12;
        pool.publish_capture(third_metadata, &[3; 16])
            .expect("third capture");
        let mut fourth_metadata = metadata();
        fourth_metadata.timestamp_nanos = 13;
        pool.publish_capture(fourth_metadata, &[4; 16])
            .expect("unleased frame is recycled");

        assert_eq!(pool.frame_count(), 3);
        assert_eq!(
            pool.cache(task(2), second, &mut resources),
            Err(FrameError::UnknownCapture(second))
        );
        assert_eq!(
            pool.view(task(1), pinned, &resources)
                .expect("pinned frame survives")
                .pixels[0],
            1
        );
    }

    #[test]
    fn leased_frames_feed_color_and_template_search_without_another_copy() {
        let mut pool = FramePool::new(FramePoolConfig::default());
        let mut resources = TaskResourceRegistry::default();
        let owner = task(1);
        let mut screen_metadata = metadata();
        screen_metadata.width = 3;
        screen_metadata.height = 1;
        screen_metadata.row_stride = 12;
        let screen = pool
            .insert(
                owner,
                screen_metadata,
                &[1, 2, 3, 255, 10, 20, 30, 255, 40, 50, 60, 255],
                &mut resources,
            )
            .expect("screen");
        let mut template_metadata = metadata();
        template_metadata.width = 1;
        template_metadata.height = 1;
        template_metadata.row_stride = 4;
        let template = pool
            .insert(owner, template_metadata, &[10, 20, 30, 255], &mut resources)
            .expect("template");
        let search = SearchOptions {
            roi: PixelRect {
                left: 0,
                top: 0,
                right: 3,
                bottom: 1,
            },
            step_x: 1,
            step_y: 1,
            order: SearchOrder::TopLeftToBottomRight,
            max_pixel_comparisons: 20,
        };
        assert_eq!(
            pool.find_color(
                owner,
                screen,
                &resources,
                Color {
                    red: 10,
                    green: 20,
                    blue: 30,
                    alpha: 255,
                },
                ColorTolerance::EXACT,
                search,
            )
            .expect("find color"),
            Some(PixelPoint { x: 1, y: 0 })
        );
        assert_eq!(
            pool.find_template(
                owner,
                screen,
                template,
                &resources,
                TemplateOptions {
                    search,
                    tolerance: ColorTolerance::EXACT,
                    minimum_match_permille: 1_000,
                    ignore_transparent_template_pixels: true,
                },
            )
            .expect("find template")
            .expect("match")
            .origin,
            PixelPoint { x: 1, y: 0 }
        );
    }

    #[test]
    fn project_template_is_bounded_and_leased_without_copying_pixels() {
        let mut pool = FramePool::new(FramePoolConfig::default());
        let pixels: Arc<[u8]> = Arc::from([10, 20, 30, 255, 40, 50, 60, 255]);
        let mut template_metadata = metadata();
        template_metadata.width = 2;
        template_metadata.height = 1;
        template_metadata.row_stride = 8;
        pool.register_template(
            "assets/images/button.png",
            template_metadata,
            Arc::clone(&pixels),
        )
        .expect("register template");
        assert_eq!(pool.template_count(), 1);
        assert_eq!(pool.prepared_template_count(), 1);
        let mut bgra_metadata = template_metadata;
        bgra_metadata.format = FrameFormat::Bgra8888;
        pool.register_template(
            "assets/images/button-copy.png",
            bgra_metadata,
            Arc::from([30, 20, 10, 255, 60, 50, 40, 255]),
        )
        .expect("register content-equivalent template");
        assert_eq!(pool.template_count(), 2);
        assert_eq!(pool.prepared_template_count(), 1);

        let mut resources = TaskResourceRegistry::default();
        let handle = pool
            .lease_template(task(1), "assets/images/button.png", &mut resources)
            .expect("lease template");
        let view = pool.view(task(1), handle, &resources).expect("view");
        assert!(Arc::ptr_eq(&pixels, &view.pixels));
        assert_eq!(
            pool.register_template("../button.png", template_metadata, Arc::from([0; 8]),),
            Err(FrameError::InvalidTemplateName)
        );
    }

    #[test]
    fn capture_series_reserves_budget_and_converts_frames_to_task_leases() {
        let mut pool = FramePool::new(FramePoolConfig::default());
        let mut resources = TaskResourceRegistry::default();
        let owner = task(1);
        let first = pool
            .insert(owner, metadata(), &[1; 16], &mut resources)
            .expect("first frame");
        let series = pool
            .begin_series(
                owner,
                first,
                &mut resources,
                CaptureSeriesConfig {
                    max_frames: 3,
                    max_duration_nanos: 1_000_000_000,
                    target_fps: 10,
                    allow_partial: false,
                },
            )
            .expect("series reservation");

        let mut second_metadata = metadata();
        second_metadata.timestamp_nanos = 100_000_010;
        let SeriesPublish::Frame {
            capture: second_capture,
            finished: false,
            ..
        } = pool
            .publish_series_capture_owned(series, second_metadata, Arc::from([2; 16]))
            .expect("second frame")
        else {
            panic!("expected second frame");
        };
        let second = pool
            .cache_series_capture(owner, series, second_capture, &mut resources)
            .expect("lease second");

        let mut third_metadata = metadata();
        third_metadata.timestamp_nanos = 200_000_010;
        let SeriesPublish::Frame {
            capture: third_capture,
            finished: true,
            ..
        } = pool
            .publish_series_capture_owned(series, third_metadata, Arc::from([3; 16]))
            .expect("third frame")
        else {
            panic!("expected final frame");
        };
        let third = pool
            .cache_series_capture(owner, series, third_capture, &mut resources)
            .expect("lease third");
        assert_eq!(pool.frame_count(), 3);

        assert!(resources
            .release_lease(ResourceOwner::Task(owner), series.resource_id)
            .expect("release series"));
        assert!(pool.release_series_resource(series.resource_id));
        let released = resources.release_owner(ResourceOwner::Task(owner));
        assert_eq!(released.len(), 3);
        for resource in released {
            assert!(pool.release_resource(resource));
        }
        assert!(pool.view(owner, second, &resources).is_err());
        assert!(pool.view(owner, third, &resources).is_err());
        pool.reclaim_expired(5_000_000_010);
        assert_eq!(pool.total_bytes(), 0);
    }

    #[test]
    fn capture_series_fails_before_start_when_worst_case_bytes_do_not_fit() {
        let mut pool = FramePool::new(FramePoolConfig {
            max_total_bytes: 32,
            ..FramePoolConfig::default()
        });
        let mut resources = TaskResourceRegistry::default();
        let owner = task(1);
        let first = pool
            .insert(owner, metadata(), &[1; 16], &mut resources)
            .expect("first frame");
        assert_eq!(
            pool.begin_series(
                owner,
                first,
                &mut resources,
                CaptureSeriesConfig {
                    max_frames: 3,
                    max_duration_nanos: 1_000_000_000,
                    target_fps: 10,
                    allow_partial: false,
                }
            ),
            Err(FrameError::FrameBudgetExceeded)
        );
        assert_eq!(resources.resource_count(), 1);
    }

    #[test]
    fn strict_series_geometry_change_is_terminal_and_releases_unused_reservation() {
        let mut pool = FramePool::new(FramePoolConfig::default());
        let mut resources = TaskResourceRegistry::default();
        let owner = task(1);
        let first = pool
            .insert(owner, metadata(), &[1; 16], &mut resources)
            .expect("first frame");
        let series = pool
            .begin_series(
                owner,
                first,
                &mut resources,
                CaptureSeriesConfig {
                    max_frames: 2,
                    max_duration_nanos: 1_000_000_000,
                    target_fps: 10,
                    allow_partial: false,
                },
            )
            .expect("series");
        let mut changed = metadata();
        changed.width = 1;
        changed.row_stride = 4;
        changed.timestamp_nanos = 100_000_010;
        assert_eq!(
            pool.publish_series_capture_owned(series, changed, Arc::from([2; 8])),
            Err(FrameError::Series(CaptureSeriesError::GeometryChanged))
        );
        assert!(resources
            .release_lease(ResourceOwner::Task(owner), series.resource_id)
            .expect("release series"));
        assert!(pool.release_series_resource(series.resource_id));
    }
}
