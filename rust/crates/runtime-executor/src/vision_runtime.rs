use crate::{
    host_failure_input, template_search_control::TemplateSearchControl, vision_extensions,
    ExecutorError, HostBackend, RuntimeExecutor,
};
use automation_core::{FrameFormat, FrameMetadata, Rotation};
use lua_runtime::{LuaInput, LuaScalar};
use runtime_scheduler::{Clock, TaskToken};

impl<C: Clock, H: HostBackend> RuntimeExecutor<C, H> {
    pub(super) fn apply_extended_vision(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let result = self.extended_vision(task, opcode, args);
        self.resume_inputs.insert(
            task,
            result
                .unwrap_or_else(|message| host_failure_input("VISION_EXTENSION_FAILED", &message)),
        );
        self.scheduler.yield_budget(task)?;
        Ok(())
    }
    fn extended_vision(
        &mut self,
        task: TaskToken,
        opcode: u32,
        args: &[LuaScalar],
    ) -> Result<LuaInput, String> {
        if opcode == 5122 {
            return self.recognition_results.read(task, args);
        }
        let [LuaScalar::Integer(handle), LuaScalar::Bytes(options)] = args else {
            return Err("requires frame and JSON options".into());
        };
        if options.len() > 16384 {
            return Err("options exceed budget".into());
        }
        let frame = self.legacy_frame_view(task, *handle)?;
        let mut control = TemplateSearchControl::new(self.scheduler.handle(), task);
        if opcode == 5010 {
            let spec: vision_extensions::Transform =
                serde_json::from_slice(options).map_err(|_| "invalid transform options")?;
            let (width, height, pixels) =
                vision_extensions::transform(&frame, &spec, &mut || control.checkpoint())?;
            let metadata = FrameMetadata {
                width,
                height,
                row_stride: width * 4,
                pixel_stride: 4,
                format: FrameFormat::Rgba8888,
                rotation: Rotation::Degrees0,
                ..frame.metadata
            };
            let handle = self
                .frames
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .insert(task, metadata, &pixels, self.scheduler.resources_mut())
                .map_err(|e| format!("{e:?}"))?;
            return Ok(LuaInput::Values(vec![
                LuaScalar::Boolean(true),
                LuaScalar::Integer(
                    i64::try_from(handle.resource_id.get()).map_err(|_| "frame handle overflow")?,
                ),
            ]));
        }
        let result = if opcode == 5120 {
            let spec = serde_json::from_slice(options).map_err(|_| "invalid pattern options")?;
            vision_extensions::multi_color(&frame, &spec, &mut || control.checkpoint())?
        } else {
            let spec = serde_json::from_slice(options).map_err(|_| "invalid component options")?;
            vision_extensions::components(&frame, &spec, &mut || control.checkpoint())?
        };
        let bytes = serde_json::to_vec(&result).map_err(|_| "result encoding failed")?;
        if bytes.len() > 65536 {
            return Err("result exceeds byte budget".into());
        }
        Ok(LuaInput::Values(vec![
            LuaScalar::Boolean(true),
            LuaScalar::Bytes(bytes),
        ]))
    }
    pub(super) fn save_frame_png(
        &mut self,
        task: TaskToken,
        args: &[LuaScalar],
    ) -> Result<(), ExecutorError> {
        let prepared = (|| {
            let [LuaScalar::Integer(frame), LuaScalar::Bytes(path)] = args else {
                return Err("savePng requires frame and path".to_owned());
            };
            let path_text = std::str::from_utf8(path).map_err(|_| "invalid path")?;
            if std::path::Path::new(path_text)
                .extension()
                .is_none_or(|ext| !ext.eq_ignore_ascii_case("png"))
            {
                return Err("PNG path must end with .png".into());
            }
            crate::data_runtime::validate_file_request(14100, &[LuaScalar::Bytes(path.clone())])
                .map_err(str::to_owned)?;
            let frame = self.legacy_frame_view(task, *frame)?;
            let mut control = TemplateSearchControl::new(self.scheduler.handle(), task);
            let png = crate::frame_png::encode(&frame, &mut || control.checkpoint())?;
            Ok(vec![LuaScalar::Bytes(path.clone()), LuaScalar::Bytes(png)])
        })();
        match prepared {
            Ok(args) => self.dispatch_host(task, 14130, args),
            Err(message) => {
                self.resume_inputs
                    .insert(task, host_failure_input("PNG_SAVE_FAILED", &message));
                self.scheduler.yield_budget(task)?;
                Ok(())
            }
        }
    }
}
