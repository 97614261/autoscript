//! Distinguish a bounded search deadline from priority task cancellation.

use std::time::{Duration, Instant};

use automation_core::{FrameVisionError, SearchOptions, VisionError};
use runtime_scheduler::{SchedulerHandle, TaskToken};

const SEARCH_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Interruption {
    Cancelled,
    TimedOut,
}

pub(super) struct TemplateSearchControl {
    started: Instant,
    scheduler: SchedulerHandle,
    task: TaskToken,
    interruption: Option<Interruption>,
}

impl TemplateSearchControl {
    pub(super) fn new(scheduler: SchedulerHandle, task: TaskToken) -> Self {
        Self {
            started: Instant::now(),
            scheduler,
            task,
            interruption: None,
        }
    }

    pub(super) fn checkpoint(&mut self) -> bool {
        self.check_at(
            self.started.elapsed(),
            self.scheduler.cancellation_requested(self.task),
        )
    }

    fn check_at(&mut self, elapsed: Duration, cancelled: bool) -> bool {
        // The priority Stop channel wins even if the deadline expires at the same checkpoint.
        if cancelled {
            self.interruption = Some(Interruption::Cancelled);
        } else if self.interruption.is_none() && elapsed >= SEARCH_TIMEOUT {
            self.interruption = Some(Interruption::TimedOut);
        }
        self.interruption.is_some()
    }

    pub(super) fn error(&self, error: FrameVisionError) -> String {
        match error {
            FrameVisionError::Vision(VisionError::Cancelled) => match self.interruption {
                Some(Interruption::TimedOut) =>
                    "VISION_SEARCH_TIMEOUT: template search exceeded 5000ms; narrow ROI or reduce templates".into(),
                _ => "VISION_SEARCH_CANCELLED: template search was cancelled".into(),
            },
            FrameVisionError::Vision(VisionError::ComparisonBudgetExceeded) =>
                "VISION_COMPARISON_BUDGET_EXCEEDED: narrow ROI or reduce templates".into(),
            other => format!("{other:?}"),
        }
    }

    pub(super) fn describe_error(
        &self,
        error: FrameVisionError,
        search: SearchOptions,
        templates: usize,
    ) -> String {
        format!(
            "{}; ROI={}x{}; templates={templates}; comparison_limit={}",
            self.error(error),
            search.roi.width(),
            search.roi.height(),
            search.max_pixel_comparisons
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use runtime_scheduler::{
        ManualClock, MonoTime, Scheduler, SchedulerConfig, TaskGeneration, TaskId,
    };

    fn control() -> TemplateSearchControl {
        let scheduler =
            Scheduler::new(ManualClock::new(MonoTime::ZERO), SchedulerConfig::default());
        TemplateSearchControl::new(
            scheduler.handle(),
            TaskToken {
                id: TaskId(1),
                generation: TaskGeneration(1),
            },
        )
    }

    #[test]
    fn deadline_is_not_reported_as_task_cancellation() {
        let mut control = control();
        assert!(!control.check_at(Duration::from_millis(4999), false));
        assert!(control.check_at(SEARCH_TIMEOUT, false));
        assert!(control
            .error(VisionError::Cancelled.into())
            .starts_with("VISION_SEARCH_TIMEOUT:"));
        assert!(control.check_at(Duration::ZERO, false));
    }

    #[test]
    fn priority_stop_wins_over_deadline_and_does_not_consume_the_stop() {
        let mut control = control();
        assert!(control.check_at(SEARCH_TIMEOUT, true));
        assert_eq!(control.interruption, Some(Interruption::Cancelled));
        assert!(control
            .error(VisionError::Cancelled.into())
            .starts_with("VISION_SEARCH_CANCELLED:"));
        let mut control = self::control();
        assert!(control.check_at(SEARCH_TIMEOUT, false));
        control.scheduler.request_stop();
        assert!(control.checkpoint());
        assert!(control.scheduler.cancellation_requested(control.task));
        assert_eq!(control.interruption, Some(Interruption::Cancelled));
    }

    #[test]
    fn unrelated_errors_remain_errors_not_no_match_or_timeout() {
        let mut control = control();
        control.check_at(SEARCH_TIMEOUT, false);
        assert_eq!(
            control.error(VisionError::InvalidRoi.into()),
            "Vision(InvalidRoi)"
        );
        assert!(control
            .error(VisionError::ComparisonBudgetExceeded.into())
            .starts_with("VISION_COMPARISON_BUDGET_EXCEEDED:"));
    }

    #[test]
    fn budget_diagnostics_include_geometry_and_count_without_resource_paths() {
        let options = crate::template_search_options(0, 0, 720, 1280, 0).unwrap();
        let error =
            control().describe_error(VisionError::ComparisonBudgetExceeded.into(), options, 4);
        assert!(error.contains("ROI=720x1280; templates=4; comparison_limit=1000000000"));
        assert!(!error.contains("assets/"));
    }
}
