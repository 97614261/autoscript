//! Typed, snapshot-bound transforms between automation coordinate spaces.

macro_rules! point_type {
    ($name:ident) => {
        #[derive(Debug, Clone, Copy, PartialEq)]
        pub struct $name {
            pub x: f32,
            pub y: f32,
        }
    };
}

macro_rules! rect_type {
    ($name:ident) => {
        #[derive(Debug, Clone, Copy, PartialEq)]
        pub struct $name {
            pub left: f32,
            pub top: f32,
            pub right: f32,
            pub bottom: f32,
        }

        impl $name {
            #[must_use]
            pub fn is_valid(self) -> bool {
                self.left.is_finite()
                    && self.top.is_finite()
                    && self.right.is_finite()
                    && self.bottom.is_finite()
                    && self.left <= self.right
                    && self.top <= self.bottom
            }

            #[must_use]
            pub fn width(self) -> f32 {
                self.right - self.left
            }

            #[must_use]
            pub fn height(self) -> f32 {
                self.bottom - self.top
            }
        }
    };
}

point_type!(DesignPoint);
point_type!(DisplayPoint);
point_type!(WindowPoint);
point_type!(FramePoint);
rect_type!(DesignRect);
rect_type!(DisplayRect);
rect_type!(WindowRect);
rect_type!(FrameRect);

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Size {
    pub width: f32,
    pub height: f32,
}

impl Size {
    fn is_valid(self) -> bool {
        self.width.is_finite() && self.height.is_finite() && self.width > 0.0 && self.height > 0.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Insets {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Insets {
    pub const ZERO: Self = Self {
        left: 0.0,
        top: 0.0,
        right: 0.0,
        bottom: 0.0,
    };

    fn is_valid(self) -> bool {
        self.left.is_finite()
            && self.top.is_finite()
            && self.right.is_finite()
            && self.bottom.is_finite()
            && self.left >= 0.0
            && self.top >= 0.0
            && self.right >= 0.0
            && self.bottom >= 0.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScaleMode {
    Letterbox,
    Crop,
    Stretch,
}

/// Clockwise rotation needed to display the raw captured frame upright.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameRotation {
    Degrees0,
    Degrees90,
    Degrees180,
    Degrees270,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CoordinateSpec {
    pub snapshot_id: u64,
    pub design_size: Size,
    pub display_size: Size,
    pub window_bounds: DisplayRect,
    pub content_insets: Insets,
    pub frame_size: Size,
    pub frame_rotation: FrameRotation,
    pub scale_mode: ScaleMode,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CoordinateSnapshot {
    pub snapshot_id: u64,
    pub design_size: Size,
    pub display_size: Size,
    pub window_bounds: DisplayRect,
    pub content_insets: Insets,
    pub frame_size: Size,
    pub frame_rotation: FrameRotation,
    pub scale_mode: ScaleMode,
    content_bounds: DisplayRect,
    scale_x: f32,
    scale_y: f32,
    design_origin_x: f32,
    design_origin_y: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CoordinateError {
    InvalidSnapshotId,
    NonPositiveSize,
    InvalidWindowBounds,
    InvalidInsets,
    NonFinitePoint,
    OutsideDesign,
    OutsideDisplay,
    OutsideWindow,
    OutsideFrame,
}

impl CoordinateSnapshot {
    /// Validates all geometry once and freezes the transform under one snapshot identity.
    ///
    /// # Errors
    ///
    /// Rejects invalid sizes, bounds, insets, or a zero snapshot identity.
    pub fn new(spec: CoordinateSpec) -> Result<Self, CoordinateError> {
        if spec.snapshot_id == 0 {
            return Err(CoordinateError::InvalidSnapshotId);
        }
        if !spec.design_size.is_valid()
            || !spec.display_size.is_valid()
            || !spec.frame_size.is_valid()
        {
            return Err(CoordinateError::NonPositiveSize);
        }
        if !spec.window_bounds.is_valid()
            || spec.window_bounds.width() <= 0.0
            || spec.window_bounds.height() <= 0.0
            || spec.window_bounds.left < 0.0
            || spec.window_bounds.top < 0.0
            || spec.window_bounds.right > spec.display_size.width
            || spec.window_bounds.bottom > spec.display_size.height
        {
            return Err(CoordinateError::InvalidWindowBounds);
        }
        if !spec.content_insets.is_valid() {
            return Err(CoordinateError::InvalidInsets);
        }
        let content_bounds = DisplayRect {
            left: spec.window_bounds.left + spec.content_insets.left,
            top: spec.window_bounds.top + spec.content_insets.top,
            right: spec.window_bounds.right - spec.content_insets.right,
            bottom: spec.window_bounds.bottom - spec.content_insets.bottom,
        };
        if !content_bounds.is_valid()
            || content_bounds.width() <= 0.0
            || content_bounds.height() <= 0.0
        {
            return Err(CoordinateError::InvalidInsets);
        }
        let available_x = content_bounds.width() / spec.design_size.width;
        let available_y = content_bounds.height() / spec.design_size.height;
        let (scale_x, scale_y) = match spec.scale_mode {
            ScaleMode::Letterbox => {
                let scale = available_x.min(available_y);
                (scale, scale)
            }
            ScaleMode::Crop => {
                let scale = available_x.max(available_y);
                (scale, scale)
            }
            ScaleMode::Stretch => (available_x, available_y),
        };
        let design_origin_x =
            content_bounds.left + (content_bounds.width() - spec.design_size.width * scale_x) / 2.0;
        let design_origin_y = content_bounds.top
            + (content_bounds.height() - spec.design_size.height * scale_y) / 2.0;
        Ok(Self {
            snapshot_id: spec.snapshot_id,
            design_size: spec.design_size,
            display_size: spec.display_size,
            window_bounds: spec.window_bounds,
            content_insets: spec.content_insets,
            frame_size: spec.frame_size,
            frame_rotation: spec.frame_rotation,
            scale_mode: spec.scale_mode,
            content_bounds,
            scale_x,
            scale_y,
            design_origin_x,
            design_origin_y,
        })
    }

    /// Creates a full-display letterbox snapshot with an upright, display-sized frame.
    ///
    /// # Errors
    ///
    /// Returns a geometry error for invalid dimensions or identity.
    pub fn letterbox(
        snapshot_id: u64,
        design_size: Size,
        display_size: Size,
    ) -> Result<Self, CoordinateError> {
        Self::new(CoordinateSpec {
            snapshot_id,
            design_size,
            display_size,
            window_bounds: DisplayRect {
                left: 0.0,
                top: 0.0,
                right: display_size.width,
                bottom: display_size.height,
            },
            content_insets: Insets::ZERO,
            frame_size: display_size,
            frame_rotation: FrameRotation::Degrees0,
            scale_mode: ScaleMode::Letterbox,
        })
    }

    #[must_use]
    pub const fn content_bounds(self) -> DisplayRect {
        self.content_bounds
    }

    /// Converts a visible design point to display coordinates.
    ///
    /// # Errors
    ///
    /// Rejects points outside the design or cropped outside the content bounds.
    pub fn design_to_display(self, point: DesignPoint) -> Result<DisplayPoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if !contains_size(self.design_size, point.x, point.y) {
            return Err(CoordinateError::OutsideDesign);
        }
        let result = DisplayPoint {
            x: point.x.mul_add(self.scale_x, self.design_origin_x),
            y: point.y.mul_add(self.scale_y, self.design_origin_y),
        };
        if !contains_rect(self.content_bounds, result.x, result.y) {
            return Err(CoordinateError::OutsideDisplay);
        }
        Ok(result)
    }

    /// Applies the exact inverse transform used by playback and recording.
    ///
    /// # Errors
    ///
    /// Rejects points outside the content or mapped design canvas.
    pub fn display_to_design(self, point: DisplayPoint) -> Result<DesignPoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if !contains_rect(self.content_bounds, point.x, point.y) {
            return Err(CoordinateError::OutsideDisplay);
        }
        let result = DesignPoint {
            x: (point.x - self.design_origin_x) / self.scale_x,
            y: (point.y - self.design_origin_y) / self.scale_y,
        };
        if !contains_size(self.design_size, result.x, result.y) {
            return Err(CoordinateError::OutsideDesign);
        }
        Ok(result)
    }

    /// Converts window-local coordinates, including inset areas, to display space.
    ///
    /// # Errors
    ///
    /// Rejects points outside the current window snapshot.
    pub fn window_to_display(self, point: WindowPoint) -> Result<DisplayPoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if point.x < 0.0
            || point.y < 0.0
            || point.x > self.window_bounds.width()
            || point.y > self.window_bounds.height()
        {
            return Err(CoordinateError::OutsideWindow);
        }
        Ok(DisplayPoint {
            x: self.window_bounds.left + point.x,
            y: self.window_bounds.top + point.y,
        })
    }

    /// Converts a display point to window-local coordinates.
    ///
    /// # Errors
    ///
    /// Rejects points outside the current window bounds.
    pub fn display_to_window(self, point: DisplayPoint) -> Result<WindowPoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if !contains_rect(self.window_bounds, point.x, point.y) {
            return Err(CoordinateError::OutsideWindow);
        }
        Ok(WindowPoint {
            x: point.x - self.window_bounds.left,
            y: point.y - self.window_bounds.top,
        })
    }

    /// Maps an upright display coordinate into the possibly rotated raw frame buffer.
    ///
    /// # Errors
    ///
    /// Rejects points outside the full display.
    pub fn display_to_frame(self, point: DisplayPoint) -> Result<FramePoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if !contains_size(self.display_size, point.x, point.y) {
            return Err(CoordinateError::OutsideDisplay);
        }
        let x = point.x / self.display_size.width;
        let y = point.y / self.display_size.height;
        let (u, v) = match self.frame_rotation {
            FrameRotation::Degrees0 => (x, y),
            FrameRotation::Degrees90 => (y, 1.0 - x),
            FrameRotation::Degrees180 => (1.0 - x, 1.0 - y),
            FrameRotation::Degrees270 => (1.0 - y, x),
        };
        Ok(FramePoint {
            x: u * self.frame_size.width,
            y: v * self.frame_size.height,
        })
    }

    /// Maps a raw frame coordinate into the upright display represented by this snapshot.
    ///
    /// # Errors
    ///
    /// Rejects points outside the frame.
    pub fn frame_to_display(self, point: FramePoint) -> Result<DisplayPoint, CoordinateError> {
        validate_point(point.x, point.y)?;
        if !contains_size(self.frame_size, point.x, point.y) {
            return Err(CoordinateError::OutsideFrame);
        }
        let u = point.x / self.frame_size.width;
        let v = point.y / self.frame_size.height;
        let (x, y) = match self.frame_rotation {
            FrameRotation::Degrees0 => (u, v),
            FrameRotation::Degrees90 => (1.0 - v, u),
            FrameRotation::Degrees180 => (1.0 - u, 1.0 - v),
            FrameRotation::Degrees270 => (v, 1.0 - u),
        };
        Ok(DisplayPoint {
            x: x * self.display_size.width,
            y: y * self.display_size.height,
        })
    }
}

fn validate_point(x: f32, y: f32) -> Result<(), CoordinateError> {
    if x.is_finite() && y.is_finite() {
        Ok(())
    } else {
        Err(CoordinateError::NonFinitePoint)
    }
}

fn contains_size(size: Size, x: f32, y: f32) -> bool {
    x >= 0.0 && y >= 0.0 && x <= size.width && y <= size.height
}

fn contains_rect(rect: DisplayRect, x: f32, y: f32) -> bool {
    x >= rect.left && y >= rect.top && x <= rect.right && y <= rect.bottom
}

#[cfg(test)]
mod tests {
    use super::{
        CoordinateError, CoordinateSnapshot, CoordinateSpec, DesignPoint, DisplayPoint,
        DisplayRect, FramePoint, FrameRotation, Insets, ScaleMode, Size, WindowPoint,
    };

    #[test]
    fn letterbox_round_trip_rejects_the_bars() {
        let snapshot = CoordinateSnapshot::letterbox(
            7,
            Size {
                width: 100.0,
                height: 100.0,
            },
            Size {
                width: 300.0,
                height: 200.0,
            },
        )
        .expect("valid geometry");
        let display = snapshot
            .design_to_display(DesignPoint { x: 25.0, y: 50.0 })
            .expect("visible");
        assert_eq!(display, DisplayPoint { x: 100.0, y: 100.0 });
        assert_eq!(
            snapshot.display_to_design(display).expect("inverse"),
            DesignPoint { x: 25.0, y: 50.0 }
        );
        assert_eq!(
            snapshot.display_to_design(DisplayPoint { x: 25.0, y: 50.0 }),
            Err(CoordinateError::OutsideDesign)
        );
    }

    #[test]
    fn window_insets_and_crop_are_frozen_in_the_snapshot() {
        let snapshot = CoordinateSnapshot::new(CoordinateSpec {
            snapshot_id: 8,
            design_size: Size {
                width: 100.0,
                height: 100.0,
            },
            display_size: Size {
                width: 400.0,
                height: 300.0,
            },
            window_bounds: DisplayRect {
                left: 50.0,
                top: 20.0,
                right: 350.0,
                bottom: 280.0,
            },
            content_insets: Insets {
                left: 10.0,
                top: 20.0,
                right: 10.0,
                bottom: 20.0,
            },
            frame_size: Size {
                width: 400.0,
                height: 300.0,
            },
            frame_rotation: FrameRotation::Degrees0,
            scale_mode: ScaleMode::Crop,
        })
        .expect("snapshot");
        assert_eq!(
            snapshot
                .window_to_display(WindowPoint { x: 10.0, y: 20.0 })
                .expect("window point"),
            DisplayPoint { x: 60.0, y: 40.0 }
        );
        assert_eq!(
            snapshot
                .design_to_display(DesignPoint { x: 50.0, y: 50.0 })
                .expect("center"),
            DisplayPoint { x: 200.0, y: 150.0 }
        );
    }

    #[test]
    fn rotated_frame_and_display_round_trip() {
        let snapshot = CoordinateSnapshot::new(CoordinateSpec {
            snapshot_id: 9,
            design_size: Size {
                width: 200.0,
                height: 100.0,
            },
            display_size: Size {
                width: 200.0,
                height: 100.0,
            },
            window_bounds: DisplayRect {
                left: 0.0,
                top: 0.0,
                right: 200.0,
                bottom: 100.0,
            },
            content_insets: Insets::ZERO,
            frame_size: Size {
                width: 100.0,
                height: 200.0,
            },
            frame_rotation: FrameRotation::Degrees90,
            scale_mode: ScaleMode::Stretch,
        })
        .expect("snapshot");
        let display = DisplayPoint { x: 50.0, y: 25.0 };
        let frame = snapshot.display_to_frame(display).expect("to frame");
        assert_eq!(frame, FramePoint { x: 25.0, y: 150.0 });
        assert_eq!(snapshot.frame_to_display(frame).expect("inverse"), display);
    }
}
