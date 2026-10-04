//! Fixed, low-frequency JNI commands for real Root input recording. No Lua API is added.
use super::*;
use root_protocol::recording::{
    RecordingConfig, RecordingFinish, RecordingResult, ACTION_BYTES, MAX_ACTIONS,
    RESULT_HEADER_BYTES,
};

#[derive(Debug, Default)]
pub(super) struct RecordingControl {
    pub(super) token: AtomicU64,
    priority: Mutex<Option<root_client::PriorityStop>>,
}
impl RecordingControl {
    pub(super) fn reserve_run(&self) -> Result<RunFence<'_>, String> {
        self.token
            .compare_exchange(0, u64::MAX, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| "RECORDING_BUSY")?;
        Ok(RunFence(self))
    }
    pub fn active(&self) -> bool {
        self.token.load(Ordering::Acquire) != 0
    }
    #[cfg(target_os = "android")]
    pub fn attach(&self, priority: Option<root_client::PriorityStop>) {
        self.token.store(0, Ordering::Release);
        *self
            .priority
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = priority;
    }
    pub fn detach(&self) {
        self.token.store(0, Ordering::Release);
        self.priority
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
    }
    pub fn stop(&self) {
        if self.active() {
            if let Some(priority) = self
                .priority
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .as_ref()
            {
                priority.request_stop();
            }
            self.token.store(0, Ordering::Release);
        }
    }
    fn begin(&self, session: &NativeSession, config: RecordingConfig) -> i32 {
        if config.validate().is_err() {
            return 7;
        }
        if !session.root_attached.load(Ordering::Acquire) {
            return 1;
        }
        if self
            .token
            .compare_exchange(0, config.token, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return 10;
        }
        if !matches!(
            session.state.load(Ordering::Acquire),
            STATE_IDLE | STATE_STOPPED | STATE_FAILED
        ) {
            let _ =
                self.token
                    .compare_exchange(config.token, 0, Ordering::AcqRel, Ordering::Acquire);
            return 10;
        }
        let (response, receive) = mpsc::sync_channel(1);
        if session
            .root_control
            .try_send(RootControl::BeginRecording { config, response })
            .is_err()
        {
            self.token.store(0, Ordering::Release);
            return 10;
        }
        session.host_queue.interrupt();
        match receive.recv_timeout(COMMAND_TIMEOUT) {
            Ok(0) => 0,
            Ok(status) => {
                self.token.store(0, Ordering::Release);
                status
            }
            Err(_) => {
                self.stop();
                11
            }
        }
    }
    fn finish(&self, finish: RecordingFinish) -> Result<Vec<u8>, String> {
        if finish.token == 0 || self.token.load(Ordering::Acquire) != finish.token {
            return Err("RECORDING_SESSION_MISMATCH".into());
        }
        let priority = self
            .priority
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
            .ok_or("RECORDING_UNSUPPORTED")?;
        let result = priority
            .finish_recording(finish)
            .map_err(|_| "RECORDING_FINISH_FAILED")?;
        let bytes = result.encode();
        validate_result(&bytes, finish.token)?;
        self.token
            .compare_exchange(finish.token, 0, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| "RECORDING_SESSION_MISMATCH")?;
        Ok(bytes)
    }
}
pub(super) struct RunFence<'a>(&'a RecordingControl);
impl Drop for RunFence<'_> {
    fn drop(&mut self) {
        let _ = self
            .0
            .token
            .compare_exchange(u64::MAX, 0, Ordering::AcqRel, Ordering::Acquire);
    }
}
fn validate_result(bytes: &[u8], token: u64) -> Result<RecordingResult, String> {
    if bytes.len() > RESULT_HEADER_BYTES + MAX_ACTIONS * ACTION_BYTES {
        return Err("RECORDING_BUDGET".into());
    }
    let result = RecordingResult::decode(bytes).map_err(str::to_owned)?;
    if result.token != token {
        return Err("RECORDING_SESSION_MISMATCH".into());
    }
    Ok(result)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeStartRecording(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    token: jlong,
    width: jint,
    height: jint,
    rotation: jint,
    duration: jint,
    budget: jint,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(session) = u64::try_from(handle)
            .map_err(|_| ())
            .and_then(|handle| get_session(handle).map_err(|_| ()))
        else {
            return 9;
        };
        let Ok(config) = (|| {
            Ok::<_, ()>(RecordingConfig {
                token: u64::try_from(token).map_err(|_| ())?,
                width: u32::try_from(width).map_err(|_| ())?,
                height: u32::try_from(height).map_err(|_| ())?,
                rotation: u32::try_from(rotation).map_err(|_| ())?,
                max_duration_ms: u32::try_from(duration).map_err(|_| ())?,
                event_budget: u32::try_from(budget).map_err(|_| ())?,
            })
        })() else {
            return 7;
        };
        session.recording.begin(&session, config)
    }))
    .unwrap_or(11)
}
#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeFinishRecording(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    token: jlong,
    cancel: jboolean,
    discard_current: jboolean,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let session = get_session(u64::try_from(handle).ok()?).ok()?;
        let bytes = session
            .recording
            .finish(RecordingFinish {
                token: u64::try_from(token).ok()?,
                cancel: cancel != 0,
                discard_current_touch: discard_current != 0,
            })
            .ok()?;
        env.byte_array_from_slice(&bytes)
            .ok()
            .map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn result_budget_and_token_are_validated_before_ffi_copy() {
        let result = RecordingResult {
            token: 17,
            elapsed_ms: 10,
            status: 0,
            skipped_gestures: 0,
            actions: vec![],
        }
        .encode();
        assert!(validate_result(&result, 17).is_ok());
        assert!(validate_result(&result, 18).is_err());
        assert!(validate_result(
            &vec![0; RESULT_HEADER_BYTES + MAX_ACTIONS * ACTION_BYTES + 1],
            17
        )
        .is_err());
        assert!(validate_result(&result[..result.len() - 1], 17).is_err());
    }
}
