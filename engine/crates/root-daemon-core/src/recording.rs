//! Safe evdev decoder; platform code supplies capability-checked axis ranges and monotonic events.
use root_protocol::recording::{RecordingAction, RecordingConfig, RecordingResult, MAX_ACTIONS};

#[derive(Debug, Clone, Copy)]
pub struct Axis {
    pub minimum: i32,
    pub maximum: i32,
}
#[derive(Debug, Clone, Copy)]
pub struct InputEvent {
    pub at_us: u64,
    pub event_type: u16,
    pub code: u16,
    pub value: i32,
}
#[derive(Debug, Clone, Copy, Default)]
struct Slot {
    tracking: Option<i32>,
    x: Option<i32>,
    y: Option<i32>,
}
#[derive(Debug)]
struct Touch {
    tracking: i32,
    start_ms: u32,
    first: (i32, i32),
    last: (i32, i32),
    points: Vec<(i32, i32)>,
}

pub struct RecordingDecoder {
    config: RecordingConfig,
    x: Axis,
    y: Axis,
    started_us: u64,
    last_us: u64,
    slot: usize,
    slots: [Slot; 32],
    touch: Option<Touch>,
    actions: Vec<RecordingAction>,
    raw_count: u32,
    status: u32,
    skipped_gestures: u32,
}
impl RecordingDecoder {
    pub fn new(
        config: RecordingConfig,
        x: Axis,
        y: Axis,
        started_us: u64,
    ) -> Result<Self, &'static str> {
        config.validate()?;
        if x.maximum <= x.minimum
            || y.maximum <= y.minimum
            || i64::from(x.maximum) - i64::from(x.minimum) > i64::from(i32::MAX)
            || i64::from(y.maximum) - i64::from(y.minimum) > i64::from(i32::MAX)
        {
            return Err("RECORDING_AXIS_INVALID");
        }
        Ok(Self {
            config,
            x,
            y,
            started_us,
            last_us: started_us,
            slot: 0,
            slots: [Slot::default(); 32],
            touch: None,
            actions: Vec::new(),
            raw_count: 0,
            status: 0,
            skipped_gestures: 0,
        })
    }
    pub fn status(&self) -> u32 {
        self.status
    }
    pub fn fail(&mut self, status: u32) {
        self.status = status;
        self.actions.clear();
        self.skipped_gestures = 0;
        self.touch = None;
    }
    pub fn event(&mut self, event: InputEvent, touch_device: bool) {
        if self.status != 0 {
            return;
        }
        self.raw_count = self.raw_count.saturating_add(1);
        if self.raw_count > self.config.event_budget {
            self.fail(3);
            return;
        }
        if event.at_us < self.last_us || event.at_us < self.started_us {
            self.fail(7);
            return;
        }
        self.last_us = event.at_us;
        let elapsed = (event.at_us - self.started_us) / 1000;
        if elapsed > u64::from(self.config.max_duration_ms) {
            self.fail(5);
            return;
        }
        let now = elapsed as u32;
        if event.event_type == 0 && event.code == 3 {
            self.fail(7);
            return;
        } // SYN_DROPPED: no partial reconstruction.
        if touch_device && event.event_type == 3 {
            match event.code {
                47 => match usize::try_from(event.value) {
                    Ok(slot) if slot < 32 => self.slot = slot,
                    _ => self.fail(2),
                },
                57 => {
                    let slot = &mut self.slots[self.slot];
                    if event.value == -1 {
                        if slot.tracking.is_some() && (slot.x.is_none() || slot.y.is_none()) {
                            self.fail(7);
                            return;
                        }
                        slot.tracking = None;
                    } else if event.value >= 0 {
                        if slot.tracking.is_some() {
                            self.fail(7);
                            return;
                        }
                        *slot = Slot {
                            tracking: Some(event.value),
                            x: None,
                            y: None,
                        };
                        if self
                            .slots
                            .iter()
                            .filter(|slot| slot.tracking.is_some())
                            .count()
                            > 1
                        {
                            self.fail(2);
                        }
                    } else {
                        self.fail(7);
                    }
                }
                53 => self.slots[self.slot].x = Some(event.value),
                54 => self.slots[self.slot].y = Some(event.value),
                _ => {}
            }
        }
        if touch_device && event.event_type == 0 && event.code == 0 {
            self.synchronize(now);
        }
        if event.event_type == 1 && event.value == 1 {
            if let Some(key_code) = android_key(event.code) {
                if self.touch.is_some() {
                    self.fail(8);
                    return;
                }
                self.add(RecordingAction {
                    kind: 4,
                    start_ms: now,
                    duration_ms: 0,
                    x1: 0,
                    y1: 0,
                    x2: 0,
                    y2: 0,
                    key_code,
                });
            }
        }
    }
    fn synchronize(&mut self, now: u32) {
        let current = self
            .slots
            .iter()
            .find(|slot| slot.tracking.is_some())
            .copied();
        match current {
            Some(slot) => {
                let (Some(x), Some(y)) = (slot.x, slot.y) else {
                    return;
                };
                let Some(point) = map_point(self.config, self.x, self.y, x, y) else {
                    self.fail(7);
                    return;
                };
                match &mut self.touch {
                    Some(touch) => {
                        if slot.tracking != Some(touch.tracking) {
                            self.fail(7);
                            return;
                        }
                        touch.last = point;
                        if touch.points.last() != Some(&point) {
                            if touch.points.len() >= 1024 {
                                self.fail(3);
                                return;
                            }
                            touch.points.push(point);
                        }
                    }
                    None => {
                        self.touch = Some(Touch {
                            tracking: slot.tracking.expect("active slot"),
                            start_ms: now,
                            first: point,
                            last: point,
                            points: vec![point],
                        })
                    }
                }
            }
            None => {
                if let Some(touch) = self.touch.take() {
                    let duration = now.saturating_sub(touch.start_ms).max(1);
                    if duration > 5000 {
                        self.skip_gesture();
                        return;
                    }
                    let dx = i64::from(touch.last.0 - touch.first.0);
                    let dy = i64::from(touch.last.1 - touch.first.1);
                    let distance = dx * dx + dy * dy;
                    let stationary = touch.points.iter().all(|point| {
                        let x = i64::from(point.0 - touch.first.0);
                        let y = i64::from(point.1 - touch.first.1);
                        x * x + y * y <= 64
                    });
                    if !stationary
                        && (distance <= 64
                            || touch.points.iter().any(|point| {
                                let cross = i64::from(point.0 - touch.first.0) * dy
                                    - i64::from(point.1 - touch.first.1) * dx;
                                cross * cross > 144 * distance
                            }))
                    {
                        self.skip_gesture();
                        return;
                    }
                    if !stationary && duration < 50 {
                        self.skip_gesture();
                        return;
                    }
                    self.add(RecordingAction {
                        kind: if stationary {
                            if duration >= 500 {
                                2
                            } else {
                                1
                            }
                        } else {
                            3
                        },
                        start_ms: touch.start_ms,
                        duration_ms: duration,
                        x1: touch.first.0,
                        y1: touch.first.1,
                        x2: if stationary {
                            touch.first.0
                        } else {
                            touch.last.0
                        },
                        y2: if stationary {
                            touch.first.1
                        } else {
                            touch.last.1
                        },
                        key_code: 0,
                    });
                }
            }
        }
    }
    fn add(&mut self, action: RecordingAction) {
        if self.actions.len() + self.skipped_gestures as usize >= MAX_ACTIONS {
            self.fail(3);
        } else {
            self.actions.push(action);
        }
    }
    fn skip_gesture(&mut self) {
        if self.actions.len() + self.skipped_gestures as usize >= MAX_ACTIONS {
            self.fail(3);
        } else {
            self.skipped_gestures += 1;
        }
    }
    pub fn finish(
        mut self,
        elapsed_ms: u32,
        cancel: bool,
        discard_current_touch: bool,
    ) -> RecordingResult {
        if cancel {
            self.fail(4);
        } else if self.status == 0
            && (self.touch.is_some() || self.slots.iter().any(|slot| slot.tracking.is_some()))
            && !discard_current_touch
        {
            self.fail(6);
        }
        RecordingResult {
            token: self.config.token,
            elapsed_ms: elapsed_ms.min(self.config.max_duration_ms),
            status: self.status,
            skipped_gestures: self.skipped_gestures,
            actions: if self.status == 0 {
                self.actions
            } else {
                Vec::new()
            },
        }
    }
}

pub fn android_key(code: u16) -> Option<u32> {
    match code {
        102 => Some(3),
        158 | 1 => Some(4),
        115 => Some(24),
        114 => Some(25),
        28 => Some(66),
        139 => Some(82),
        _ => None,
    }
}
pub fn map_point(
    config: RecordingConfig,
    x_axis: Axis,
    y_axis: Axis,
    x: i32,
    y: i32,
) -> Option<(i32, i32)> {
    if !(x_axis.minimum..=x_axis.maximum).contains(&x)
        || !(y_axis.minimum..=y_axis.maximum).contains(&y)
    {
        return None;
    }
    let x = i64::from(x) - i64::from(x_axis.minimum);
    let y = i64::from(y) - i64::from(y_axis.minimum);
    let xr = i64::from(x_axis.maximum) - i64::from(x_axis.minimum);
    let yr = i64::from(y_axis.maximum) - i64::from(y_axis.minimum);
    let w = i64::from(config.width - 1);
    let h = i64::from(config.height - 1);
    let point = match config.rotation {
        0 => (x * w / xr, y * h / yr),
        1 => (y * w / yr, (xr - x) * h / xr),
        2 => ((xr - x) * w / xr, (yr - y) * h / yr),
        3 => ((yr - y) * w / yr, x * h / xr),
        _ => return None,
    };
    Some((i32::try_from(point.0).ok()?, i32::try_from(point.1).ok()?))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config() -> RecordingConfig {
        RecordingConfig {
            token: 71,
            width: 1001,
            height: 2001,
            rotation: 0,
            max_duration_ms: 60000,
            event_budget: 8192,
        }
    }
    fn decoder() -> RecordingDecoder {
        RecordingDecoder::new(
            config(),
            Axis {
                minimum: 0,
                maximum: 1000,
            },
            Axis {
                minimum: 0,
                maximum: 2000,
            },
            1000000,
        )
        .unwrap()
    }
    fn send(d: &mut RecordingDecoder, ms: u64, event_type: u16, code: u16, value: i32) {
        d.event(
            InputEvent {
                at_us: 1000000 + ms * 1000,
                event_type,
                code,
                value,
            },
            true,
        );
    }
    fn down(d: &mut RecordingDecoder, ms: u64, x: i32, y: i32) {
        send(d, ms, 3, 57, 1);
        send(d, ms, 3, 53, x);
        send(d, ms, 3, 54, y);
        send(d, ms, 0, 0, 0);
    }
    fn up(d: &mut RecordingDecoder, ms: u64) {
        send(d, ms, 3, 57, -1);
        send(d, ms, 0, 0, 0);
    }
    #[test]
    fn captures_real_protocol_b_tap_long_press_swipe_and_keys() {
        let mut d = decoder();
        down(&mut d, 10, 10, 20);
        up(&mut d, 100);
        down(&mut d, 200, 20, 30);
        up(&mut d, 800);
        down(&mut d, 900, 30, 40);
        send(&mut d, 1100, 3, 53, 130);
        send(&mut d, 1100, 3, 54, 240);
        send(&mut d, 1100, 0, 0, 0);
        up(&mut d, 1400);
        send(&mut d, 1500, 1, 158, 1);
        let result = d.finish(1600, false, false);
        assert_eq!(result.status, 0);
        assert_eq!(
            result
                .actions
                .iter()
                .map(|action| action.kind)
                .collect::<Vec<_>>(),
            vec![1, 2, 3, 4]
        );
        assert_eq!(result.actions[2].duration_ms, 500);
        assert_eq!(result.actions[3].key_code, 4);
        assert_eq!(RecordingResult::decode(&result.encode()).unwrap(), result);
    }
    #[test]
    fn rejects_multitouch_dropped_events_and_out_of_axis_without_partial_actions() {
        let mut d = decoder();
        down(&mut d, 10, 10, 20);
        send(&mut d, 20, 3, 47, 1);
        send(&mut d, 20, 3, 57, 2);
        assert_eq!(d.finish(30, false, true).status, 2);
        let mut d = decoder();
        send(&mut d, 20, 0, 3, 0);
        assert_eq!(d.finish(30, false, true).status, 7);
        let mut d = decoder();
        down(&mut d, 10, -1, 20);
        assert_eq!(d.finish(30, false, true).status, 7);
    }
    #[test]
    fn cancel_timeout_budget_and_unfinished_touch_never_commit_partial_data() {
        let mut d = decoder();
        down(&mut d, 10, 10, 20);
        up(&mut d, 100);
        assert!(d.finish(100, true, true).actions.is_empty());
        let mut d = decoder();
        down(&mut d, 10, 10, 20);
        assert_eq!(d.finish(100, false, false).status, 6);
        let mut d = decoder();
        send(&mut d, 60001, 0, 0, 0);
        assert_eq!(d.finish(60000, false, true).status, 5);
        let mut c = config();
        c.event_budget = 128;
        let mut d = RecordingDecoder::new(
            c,
            Axis {
                minimum: 0,
                maximum: 1000,
            },
            Axis {
                minimum: 0,
                maximum: 2000,
            },
            1000000,
        )
        .unwrap();
        for ms in 0..129 {
            send(&mut d, ms, 0, 0, 0);
        }
        assert_eq!(d.finish(200, false, true).status, 3);
    }
    #[test]
    fn orientation_maps_exact_endpoints_and_skips_curves_without_losing_taps() {
        let axis = Axis {
            minimum: 0,
            maximum: 1000,
        };
        for (rotation, expected) in [
            (0, (0, 0)),
            (1, (0, 2000)),
            (2, (1000, 2000)),
            (3, (1000, 0)),
        ] {
            let mut c = config();
            c.rotation = rotation;
            assert_eq!(map_point(c, axis, axis, 0, 0), Some(expected));
        }
        let mut d = decoder();
        down(&mut d, 0, 50, 50);
        up(&mut d, 5);
        down(&mut d, 10, 0, 0);
        send(&mut d, 20, 3, 53, 100);
        send(&mut d, 20, 3, 54, 100);
        send(&mut d, 20, 0, 0, 0);
        send(&mut d, 30, 3, 53, 200);
        send(&mut d, 30, 3, 54, 0);
        send(&mut d, 30, 0, 0, 0);
        up(&mut d, 100);
        let result = d.finish(100, false, true);
        assert_eq!(result.status, 0);
        assert_eq!(result.skipped_gestures, 1);
        assert_eq!(result.actions.len(), 1);
        assert_eq!(result.actions[0].kind, 1);
        assert!(RecordingResult::decode(&result.encode()).is_ok());
    }
}
