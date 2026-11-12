use super::*;
use automation_core::{FrameVisionError, VisionError};
use pixel_vision::polygon::PolygonRegion;

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    pub(super) fn recover_template_roi<T: Default>(
        &mut self,
        task: TaskToken,
        screen: FrameHandle,
        options: TemplateOptions,
        polygon: Option<&PolygonRegion>,
        result: Result<T, FrameVisionError>,
    ) -> Result<T, FrameVisionError> {
        let outside_frame = match result.as_ref().err() {
            Some(FrameVisionError::Vision(VisionError::InvalidRoi)) => true,
            Some(FrameVisionError::Vision(VisionError::TemplateOutsideRoi)) => false,
            _ => return result,
        };
        let roi = options.search.roi;
        // A stale selection may miss, but malformed options and polygons remain errors.
        if options.minimum_match_permille > 1000
            || options.search.step_x == 0
            || options.search.step_y == 0
            || roi.left >= roi.right
            || roi.top >= roi.bottom
            || polygon.is_some_and(|region| !region.fits(roi))
            || self.scheduler.handle().cancellation_requested(task)
        {
            return result;
        }
        let dimensions = self
            .frames
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .view(task, screen, self.scheduler.resources())
            .map(|view| (view.metadata.width, view.metadata.height));
        let Ok((width, height)) = dimensions else {
            return result;
        };
        if outside_frame && roi.right <= width && roi.bottom <= height {
            return result;
        }
        if self.output_switches.logs && self.vision_roi_warnings.len() < MAX_VISION_ROI_WARNINGS {
            let reason = if outside_frame {
                format!("TEMPLATE_ROI_OUT_OF_BOUNDS: 找图范围({},{})-({},{})超出截图{width}x{height}，本次按未找到返回；请切换到取样时的方向或重新选择范围。", roi.left, roi.top, roi.right, roi.bottom)
            } else {
                format!("TEMPLATE_OUTSIDE_ROI: 模板大于找图范围({},{})-({},{})（{}x{}），本次按未找到返回；请扩大范围或重新截图保存模板。", roi.left, roi.top, roi.right, roi.bottom, roi.width(), roi.height())
            };
            let message = self.message_with_run_context(task, &reason);
            if self.vision_roi_warnings.insert((task, message.clone())) {
                self.record_script_log("WARN", &message);
            }
        }
        Ok(T::default())
    }
}
