//! Narrow JNI boundary for owner-validated native vision leases.
use automation_core::{FrameFormat, FrameView};
use jni::objects::{GlobalRef, JByteArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use runtime_executor::{HostCancelListener, HostRequest};
use runtime_scheduler::{HostResult, RequestId, TaskToken};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

struct Callback {
    vm: JavaVM,
    listener: GlobalRef,
}
impl std::fmt::Debug for Callback {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("NativeVisionCallback")
    }
}

#[derive(Debug, Default)]
pub(super) struct VisionHub {
    callback: Mutex<Option<Arc<Callback>>>,
    active: Mutex<Option<(RequestId, TaskToken, u64)>>,
    sequence: AtomicU64,
    #[cfg(target_os = "android")]
    root: Mutex<Option<root_client::PriorityStop>>,
    #[cfg(target_os = "android")]
    input_owner: Mutex<Option<(RequestId, TaskToken)>>,
    cleanup_pending: std::sync::atomic::AtomicBool,
    cleanup_failed: std::sync::atomic::AtomicBool,
}
impl HostCancelListener for VisionHub {
    fn cancel(&self, request: RequestId, task: TaskToken) {
        let work = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .filter(|(id, owner, _)| *id == request && *owner == task)
            .map(|(_, _, work)| work);
        if let Some(work) = work {
            self.cancel_work(work);
        }
        #[cfg(target_os = "android")]
        {
            let owner = self
                .input_owner
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if *owner == Some((request, task)) {
                if let Some(stop) = self
                    .root
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner)
                    .as_ref()
                {
                    stop.request_stop();
                }
            }
        }
    }
}
impl VisionHub {
    pub fn set(&self, env: &JNIEnv<'_>, listener: JObject<'_>) -> Result<(), String> {
        let callback = Callback {
            vm: env.get_java_vm().map_err(|_| "VISION_VM_UNAVAILABLE")?,
            listener: env
                .new_global_ref(listener)
                .map_err(|_| "VISION_LISTENER_INVALID")?,
        };
        *self
            .callback
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = Some(Arc::new(callback));
        Ok(())
    }
    pub fn stop(&self) {
        let work = self
            .active
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .map(|(_, _, work)| work);
        if let Some(work) = work {
            self.cancel_work(work);
        }
        #[cfg(target_os = "android")]
        if let Some(stop) = self
            .root
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
        {
            self.cleanup_pending.store(true, Ordering::Release);
            stop.request_stop();
        }
    }
    #[cfg(target_os = "android")]
    pub fn attach_root(&self, client: &super::platform_root::Client) {
        *self
            .root
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = client.priority_stop();
        self.cleanup_failed.store(false, Ordering::Release);
        self.cleanup_pending.store(false, Ordering::Release);
    }
    pub fn begin_input(&self, request: &HostRequest) {
        #[cfg(target_os = "android")]
        {
            *self
                .input_owner
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) =
                Some((request.request_id, request.task));
        }
        #[cfg(not(target_os = "android"))]
        let _ = request;
    }
    pub fn end_input(&self) {
        #[cfg(target_os = "android")]
        {
            *self
                .input_owner
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        }
    }
    pub fn rearm_input(
        &self,
        client: &super::platform_root::Client,
        global_stop: &std::sync::atomic::AtomicBool,
    ) -> bool {
        #[cfg(target_os = "android")]
        {
            if let Some(stop) = client.priority_stop() {
                if stop.wait_clean(std::time::Duration::from_secs(8)).is_err() {
                    return false;
                }
                // No I/O while holding this lock. Global stop and re-arm are ordered together.
                let _root = self
                    .root
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                if !global_stop.load(Ordering::Acquire) {
                    return stop.resume().is_ok();
                }
            }
        }
        #[cfg(not(target_os = "android"))]
        let _ = (client, global_stop);
        true
    }
    pub fn cleanup_finished(&self, success: bool) {
        if !success {
            self.cleanup_failed.store(true, Ordering::Release);
        }
        self.cleanup_pending.store(false, Ordering::Release);
    }
    pub fn cleanup_status(&self) -> (bool, bool) {
        #[cfg(target_os = "android")]
        if self
            .root
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .as_ref()
            .is_some_and(|stop| stop.status() == root_client::StopStatus::Failed)
        {
            return (false, true);
        }
        (
            self.cleanup_pending.load(Ordering::Acquire),
            self.cleanup_failed.load(Ordering::Acquire),
        )
    }
    pub fn detach_root(&self, clean: bool) {
        #[cfg(target_os = "android")]
        {
            *self
                .root
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
        }
        self.end_input();
        self.cleanup_finished(clean);
    }
    fn cancel_work(&self, work: u64) {
        let callback = self
            .callback
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone();
        if let Some(callback) = callback {
            if let Ok(mut env) = callback.vm.attach_current_thread_as_daemon() {
                let _ = env.call_method(
                    callback.listener.as_obj(),
                    "cancel",
                    "(J)V",
                    &[JValue::Long(work as i64)],
                );
                let _ = env.exception_clear();
            }
        }
    }
    pub fn dispatch(
        &self,
        request: &HostRequest,
        context: &super::RootWorkerContext,
    ) -> HostResult {
        let result = (|| -> Result<Vec<u8>, String> {
            let input = request
                .native_vision
                .as_ref()
                .ok_or("VISION_REQUEST_INVALID")?;
            let callback = self
                .callback
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
                .ok_or("VISION_ADAPTER_UNAVAILABLE")?;
            let work = self
                .sequence
                .fetch_add(1, Ordering::AcqRel)
                .checked_add(1)
                .filter(|value| *value <= i64::MAX as u64)
                .ok_or("VISION_ID_EXHAUSTED")?;
            *self
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) =
                Some((request.request_id, request.task, work));
            let outcome = (|| {
                if context.input_stop_requested.load(Ordering::Acquire)
                    || context
                        .host_queue
                        .take_cancellation(request.request_id, request.task)
                        .is_some()
                {
                    return Err("VISION_CANCELLED".to_owned());
                }
                let mut env = callback
                    .vm
                    .attach_current_thread_as_daemon()
                    .map_err(|_| "VISION_VM_UNAVAILABLE")?;
                env.push_local_frame(32)
                    .map_err(|_| "VISION_BUFFER_FAILED")?;
                let operation = (|| {
                    // SAFETY: the immutable Arc leases live throughout this synchronous call.
                    // The fixed Java adapter only reads these buffers and never retains them.
                    let buffer = unsafe {
                        env.new_direct_byte_buffer(
                            input.frame.pixels.as_ptr().cast_mut(),
                            input.frame.pixels.len(),
                        )
                    }
                    .map_err(|_| "VISION_BUFFER_FAILED")?;
                    let template = match &input.template {
                        Some(template) => Some(
                            unsafe {
                                env.new_direct_byte_buffer(
                                    template.pixels.as_ptr().cast_mut(),
                                    template.pixels.len(),
                                )
                            }
                            .map_err(|_| "VISION_BUFFER_FAILED")?,
                        ),
                        None => None,
                    };
                    let metadata = int_array(&mut env, &frame_metadata(&input.frame)?)?;
                    let template_metadata = int_array(
                        &mut env,
                        &input
                            .template
                            .as_ref()
                            .map(frame_metadata)
                            .transpose()?
                            .unwrap_or_default(),
                    )?;
                    let r = input.region;
                    let options = int_array(
                        &mut env,
                        &[
                            r.left as i32,
                            r.top as i32,
                            r.right as i32,
                            r.bottom as i32,
                            i32::from(input.minimum_score),
                        ],
                    )?;
                    let null = JObject::null();
                    let value = env.call_method(
                        callback.listener.as_obj(),
                        "dispatch",
                        "(JILjava/nio/ByteBuffer;[ILjava/nio/ByteBuffer;[I[I)[B",
                        &[
                            JValue::Long(work as i64),
                            JValue::Int(request.opcode as i32),
                            JValue::Object(buffer.as_ref()),
                            JValue::Object(metadata.as_ref()),
                            JValue::Object(
                                template.as_ref().map_or(&null, |buffer| buffer.as_ref()),
                            ),
                            JValue::Object(template_metadata.as_ref()),
                            JValue::Object(options.as_ref()),
                        ],
                    );
                    let value = match value {
                        Ok(value) => value,
                        Err(_) => {
                            let _ = env.exception_clear();
                            return Err("VISION_JAVA_FAILED".into());
                        }
                    };
                    let array = JByteArray::from(value.l().map_err(|_| "VISION_RESPONSE_INVALID")?);
                    let length = env
                        .get_array_length(&array)
                        .map_err(|_| "VISION_RESPONSE_INVALID")?;
                    if !(1..=2048).contains(&length) {
                        return Err("VISION_RESPONSE_TOO_LARGE".into());
                    }
                    let bytes = env
                        .convert_byte_array(array)
                        .map_err(|_| "VISION_RESPONSE_INVALID")?;
                    match bytes.first() {
                        Some(0) => Ok(bytes[1..].to_vec()),
                        _ => Err(String::from_utf8(bytes[1..].to_vec())
                            .unwrap_or_else(|_| "VISION_OPERATION_FAILED".into())),
                    }
                })();
                let _ = env.exception_clear();
                // SAFETY: only Rust owned bytes leave this frame, no Java references escape.
                let _ = unsafe { env.pop_local_frame(&JObject::null()) };
                operation
            })();
            *self
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
            outcome
        })();
        match result {
            Ok(payload) => HostResult::Success(payload),
            Err(code) => HostResult::Failure {
                code,
                message:
                    "Native vision did not complete; check ROI, budgets and backend availability"
                        .into(),
            },
        }
    }
}
fn frame_metadata(frame: &FrameView) -> Result<Vec<i32>, String> {
    let m = frame.metadata;
    Ok(vec![
        i32::try_from(m.width).map_err(|_| "VISION_GEOMETRY_INVALID")?,
        i32::try_from(m.height).map_err(|_| "VISION_GEOMETRY_INVALID")?,
        i32::try_from(m.row_stride).map_err(|_| "VISION_GEOMETRY_INVALID")?,
        match m.format {
            FrameFormat::Rgba8888 => 1,
            FrameFormat::Bgra8888 => 2,
        },
    ])
}
fn int_array<'a>(
    env: &mut JNIEnv<'a>,
    values: &[i32],
) -> Result<jni::objects::JIntArray<'a>, String> {
    let array = env
        .new_int_array(values.len() as i32)
        .map_err(|_| "VISION_BUFFER_FAILED")?;
    env.set_int_array_region(&array, 0, values)
        .map_err(|_| "VISION_BUFFER_FAILED")?;
    Ok(array)
}
