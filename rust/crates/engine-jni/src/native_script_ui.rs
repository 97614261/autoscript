//! Fixed UI adapter: bounded strings only, no arbitrary Android objects or member access.
use jni::objects::{GlobalRef, JByteArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use runtime_executor::{HostCancelListener, HostRequest};
use runtime_scheduler::{HostCompletion, HostResult, RequestId, SchedulerHandle, TaskToken};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

/// One reusable wait thread, one queued job, no polling or unbounded spawn.
struct DialogWorker<T> {
    sender: SyncSender<T>,
    thread: JoinHandle<()>,
    busy: Arc<AtomicBool>,
}
impl<T> std::fmt::Debug for DialogWorker<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DialogWorker").finish_non_exhaustive()
    }
}
impl<T: Send + 'static> DialogWorker<T> {
    fn start<C: FnOnce()>(
        mut dispatch: impl FnMut(T) -> C + Send + 'static,
    ) -> std::io::Result<Self> {
        let (sender, receiver) = mpsc::sync_channel(1);
        let busy = Arc::new(AtomicBool::new(false));
        let waiting = Arc::clone(&busy);
        let thread = thread::Builder::new()
            .name("autoscript-ui-dialog".into())
            .spawn(move || {
                while let Ok(job) = receiver.recv() {
                    let complete = dispatch(job);
                    // Release before publishing the result: a sequential next dialog can
                    // queue safely even while completion notification is finishing.
                    waiting.store(false, Ordering::Release);
                    complete();
                }
            })?;
        Ok(Self {
            sender,
            thread,
            busy,
        })
    }
    fn submit(&self, job: T) -> Result<(), String> {
        if self
            .busy
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err("UI_DIALOG_BUSY".into());
        }
        self.sender.try_send(job).map_err(|_| {
            self.busy.store(false, Ordering::Release);
            "UI_WORKER_UNAVAILABLE".into()
        })
    }
    fn join(self) {
        drop(self.sender);
        let _ = self.thread.join();
    }
}

type DialogJob = (HostRequest, SchedulerHandle);

struct Callback {
    vm: JavaVM,
    listener: GlobalRef,
}
impl std::fmt::Debug for Callback {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ScriptUiCallback")
    }
}

#[derive(Debug, Default)]
pub(super) struct ScriptUiHub {
    callback: Mutex<Option<Arc<Callback>>>,
    active: Mutex<Option<(RequestId, TaskToken, u64)>>,
    sequence: AtomicU64,
    worker: Mutex<Option<DialogWorker<DialogJob>>>,
}

impl HostCancelListener for ScriptUiHub {
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
    }
}

impl ScriptUiHub {
    pub fn dispatch_dialog(
        self: &Arc<Self>,
        request: HostRequest,
        context: &super::RootWorkerContext,
        completion: &SchedulerHandle,
    ) -> Result<(), String> {
        let mut worker = self
            .worker
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if worker
            .as_ref()
            .is_some_and(|worker| worker.thread.is_finished())
        {
            if let Some(previous) = worker.take() {
                previous.join();
            }
        }
        if worker.is_none() {
            let hub = Arc::clone(self);
            let context = context.clone();
            *worker = Some(
                DialogWorker::start(move |(request, completion): DialogJob| {
                    let result = hub.dispatch(&request, &context);
                    let context = context.clone();
                    move || {
                        if completion
                            .submit_host_completion(HostCompletion {
                                request_id: request.request_id,
                                task: request.task,
                                result,
                            })
                            .is_ok()
                        {
                            super::notify_root_wake(&context);
                        }
                    }
                })
                .map_err(|_| "UI_WORKER_UNAVAILABLE")?,
            );
        }
        worker
            .as_ref()
            .ok_or("UI_WORKER_UNAVAILABLE")?
            .submit((request, completion.clone()))
    }

    pub fn join(&self) {
        let worker = self
            .worker
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .take();
        if let Some(worker) = worker {
            worker.join();
        }
    }

    pub fn set(&self, env: &JNIEnv<'_>, listener: JObject<'_>) -> Result<(), String> {
        let callback = Callback {
            vm: env.get_java_vm().map_err(|_| "UI_VM_UNAVAILABLE")?,
            listener: env
                .new_global_ref(listener)
                .map_err(|_| "UI_LISTENER_INVALID")?,
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
    }

    fn cancel_work(&self, work: u64) {
        let Ok(work) = i64::try_from(work) else {
            return;
        };
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
                    &[JValue::Long(work)],
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
        let outcome = (|| -> Result<Vec<u8>, String> {
            let (id, operation, value) = normalized_request(request)?;
            let (id, operation, value) = (id.as_str(), operation.as_str(), value.as_str());
            if id.len() > 64
                || operation.len() > 32
                || value.len() > (if request.opcode == 1203 { 8192 } else { 32768 })
                || [id, operation, value]
                    .iter()
                    .any(|text| text.contains('\0'))
            {
                return Err("UI_REQUEST_INVALID".into());
            }
            let callback = self
                .callback
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
                .ok_or("UI_ADAPTER_UNAVAILABLE")?;
            let work = self
                .sequence
                .fetch_add(1, Ordering::AcqRel)
                .checked_add(1)
                .filter(|work| i64::try_from(*work).is_ok())
                .ok_or("UI_ID_EXHAUSTED")?;
            {
                let mut active = self
                    .active
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                if active.is_some() {
                    return Err("UI_QUEUE_FULL".into());
                }
                *active = Some((request.request_id, request.task, work));
            }
            let result = (|| {
                if context.input_stop_requested.load(Ordering::Acquire)
                    || context
                        .host_queue
                        .take_cancellation(request.request_id, request.task)
                        .is_some()
                {
                    return Err("UI_CANCELLED".into());
                }
                let mut env = callback
                    .vm
                    .attach_current_thread_as_daemon()
                    .map_err(|_| "UI_VM_UNAVAILABLE")?;
                env.push_local_frame(8).map_err(|_| "UI_JNI_FAILED")?;
                let result = (|| {
                    let id = env.new_string(id).map_err(|_| "UI_JNI_FAILED")?;
                    let operation = env.new_string(operation).map_err(|_| "UI_JNI_FAILED")?;
                    let value = env.new_string(value).map_err(|_| "UI_JNI_FAILED")?;
                    let result = env
                        .call_method(
                            callback.listener.as_obj(),
                            "dispatch",
                            "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)[B",
                            &[
                                JValue::Long(i64::try_from(work).map_err(|_| "UI_ID_EXHAUSTED")?),
                                JValue::Object(id.as_ref()),
                                JValue::Object(operation.as_ref()),
                                JValue::Object(value.as_ref()),
                            ],
                        )
                        .map_err(|_| "UI_JAVA_FAILED")?;
                    let array = JByteArray::from(result.l().map_err(|_| "UI_RESPONSE_INVALID")?);
                    let length = env
                        .get_array_length(&array)
                        .map_err(|_| "UI_RESPONSE_INVALID")?;
                    if !(1..=32769).contains(&length) {
                        return Err("UI_RESPONSE_INVALID".into());
                    }
                    let bytes = env
                        .convert_byte_array(array)
                        .map_err(|_| "UI_RESPONSE_INVALID")?;
                    decode_response(&bytes)
                })();
                let _ = env.exception_clear();
                // SAFETY: the result owns Rust bytes; no Java references escape this local frame.
                let _ = unsafe { env.pop_local_frame(&JObject::null()) };
                result
            })();
            *self
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
            result
        })();
        match outcome {
            Ok(payload) => HostResult::Success(payload),
            Err(code) => HostResult::Failure {
                code,
                message: "UI property request did not complete".into(),
            },
        }
    }
}

fn normalized_request(request: &HostRequest) -> Result<(String, String, String), String> {
    use lua_runtime::LuaScalar;
    let text = |i: usize| match request.args.get(i) {
        Some(LuaScalar::Bytes(b)) => {
            String::from_utf8(b.clone()).map_err(|_| "UI_REQUEST_INVALID".to_owned())
        }
        _ => Err("UI_REQUEST_INVALID".to_owned()),
    };
    let time = |i: usize| match request.args.get(i) {
        Some(LuaScalar::Integer(t)) if (1000..=1_800_000).contains(t) => Ok(*t),
        _ => Err("UI_REQUEST_INVALID".to_owned()),
    };
    match request.opcode {
        17000 if request.args.len()==11 => {
            let fields=["name","x","y","width","height","text","fontPx","textColor","background","imagePath","shapesJson"];
            let mut object=serde_json::Map::new();
            for (field,arg) in fields.iter().zip(&request.args) {
                let value=match arg {LuaScalar::Bytes(v)=>serde_json::Value::String(std::str::from_utf8(v).map_err(|_|"UI_REQUEST_INVALID")?.to_owned()),LuaScalar::Integer(v)=>serde_json::Value::from(*v),_=>return Err("UI_REQUEST_INVALID".into())};
                object.insert((*field).into(),value);
            }
            Ok(("runtime-tools".into(),"floatingShow".into(),serde_json::Value::Object(object).to_string()))
        }
        17001 if request.args.len()==1 => Ok(("runtime-tools".into(),"floatingHide".into(),text(0)?)),
        17002 if request.args.len()==1 => Ok(("runtime-tools".into(),"floatingClose".into(),text(0)?)),
        17003 if request.args.len()==1 => Ok(("runtime-tools".into(),"floatingBounds".into(),text(0)?)),
        16000 if request.args.is_empty() => Ok(("runtime-tools".into(),"clipboardGet".into(),String::new())),
        16001 | 2202 if request.args.len()==1 => Ok(("runtime-tools".into(),"clipboardSet".into(),text(0)?)),
        16002 if request.args.len()==1 => Ok(("runtime-tools".into(),"appInfo".into(),text(0)?)),
        16003 if request.args.len()==1 => Ok(("runtime-tools".into(),"appOpen".into(),text(0)?)),
        2200 if request.args.len() == 2 => {
            let [LuaScalar::Integer(duration), LuaScalar::Integer(budget)] = request.args.as_slice() else { return Err("UI_REQUEST_INVALID".into()); };
            if !(1000..=60000).contains(duration) || !(128..=512).contains(budget) { return Err("UI_REQUEST_INVALID".into()); }
            Ok(("runtime-tools".into(), "record".into(), serde_json::json!({"durationMs":duration,"eventBudget":budget}).to_string()))
        }
        2201 if request.args.len() == 1 => Ok(("runtime-tools".into(), "recordValidate".into(), text(0)?)),
        15000 if request.args.len() == 1 => Ok(("runtime-tools".into(), "audioPlay".into(), text(0)?)),
        15001 if request.args.is_empty() => Ok(("runtime-tools".into(), "audioStop".into(), String::new())),
        15002 if request.args.is_empty() => Ok(("runtime-tools".into(), "audioGetVolume".into(), String::new())),
        15003 if request.args.len() == 1 => {
            let [LuaScalar::Integer(volume)] = request.args.as_slice() else { return Err("UI_REQUEST_INVALID".into()); };
            if !(0..=100).contains(volume) { return Err("UI_REQUEST_INVALID".into()); }
            Ok(("runtime-tools".into(), "audioSetVolume".into(), volume.to_string()))
        }
        1203 if request.args.len() == 3 => Ok((text(0)?, text(1)?, text(2)?)),
        1204 if request.args.len() == 5 => Ok(("window".into(), "dialogConfirm".into(), serde_json::json!({
            "title":text(0)?, "message":text(1)?, "confirmText":text(2)?, "cancelText":text(3)?, "timeoutMs":time(4)?
        }).to_string())),
        1205 if request.args.len() == 3 => {
            let Some(LuaScalar::Boolean(block)) = request.args.get(1) else { return Err("UI_REQUEST_INVALID".into()); };
            Ok((text(0)?, "dialogShow".into(), serde_json::json!({"blockOutside":block,"timeoutMs":time(2)?}).to_string()))
        }
        _ => Err("UI_REQUEST_INVALID".into()),
    }
}

fn decode_response(bytes: &[u8]) -> Result<Vec<u8>, String> {
    if !(1..=32769).contains(&bytes.len()) {
        return Err("UI_RESPONSE_INVALID".into());
    }
    match bytes[0] {
        0 if !bytes[1..].contains(&0) && std::str::from_utf8(&bytes[1..]).is_ok() => {
            Ok(bytes[1..].to_vec())
        }
        1 if (1..=64).contains(&bytes[1..].len())
            && bytes[1..].iter().all(|byte| {
                byte.is_ascii_uppercase() || byte.is_ascii_digit() || *byte == b'_'
            }) =>
        {
            Err(String::from_utf8(bytes[1..].to_vec()).map_err(|_| "UI_RESPONSE_INVALID")?)
        }
        _ => Err("UI_RESPONSE_INVALID".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::{decode_response, DialogWorker};
    use std::sync::mpsc;
    use std::time::Duration;

    #[test]
    fn sequential_dialogs_queue_before_previous_notification_returns() {
        let (results, received) = mpsc::channel();
        let (release, finish) = mpsc::channel();
        let finish = std::sync::Arc::new(std::sync::Mutex::new(finish));
        let worker = DialogWorker::start(move |job: u32| {
            let results = results.clone();
            let finish = finish.clone();
            move || {
                results.send(job).unwrap();
                if job == 0 {
                    finish
                        .lock()
                        .unwrap()
                        .recv_timeout(Duration::from_secs(2))
                        .unwrap();
                }
            }
        })
        .unwrap();
        worker.submit(0).unwrap();
        assert_eq!(received.recv_timeout(Duration::from_secs(2)).unwrap(), 0);
        worker.submit(1).unwrap();
        assert_eq!(worker.submit(2), Err("UI_DIALOG_BUSY".into()));
        release.send(()).unwrap();
        assert_eq!(received.recv_timeout(Duration::from_secs(2)).unwrap(), 1);
        for job in 2..100 {
            worker.submit(job).unwrap();
            assert_eq!(received.recv_timeout(Duration::from_secs(2)).unwrap(), job);
        }
        worker.join();
    }

    #[test]
    fn overlapping_dialog_is_rejected_and_worker_joins_after_release() {
        let (entered, active) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let (completed, done) = mpsc::channel();
        let worker = DialogWorker::start(move |job: u32| {
            entered.send(job).unwrap();
            released.recv_timeout(Duration::from_secs(2)).unwrap();
            let completed = completed.clone();
            move || {
                completed.send(job).unwrap();
            }
        })
        .unwrap();
        worker.submit(1).unwrap();
        assert_eq!(active.recv_timeout(Duration::from_secs(2)).unwrap(), 1);
        assert_eq!(worker.submit(2), Err("UI_DIALOG_BUSY".into()));
        release.send(()).unwrap();
        assert_eq!(done.recv_timeout(Duration::from_secs(2)).unwrap(), 1);
        worker.join();
    }

    #[test]
    fn accepts_exact_utf8_data_and_stable_error_codes() {
        assert_eq!(decode_response(b"\0#8020aB43"), Ok(b"#8020aB43".to_vec()));
        assert_eq!(decode_response(b"\0"), Ok(vec![]));
        assert_eq!(
            decode_response(b"\x01UI_CANCELLED"),
            Err("UI_CANCELLED".into())
        );
    }

    #[test]
    fn rejects_nuls_invalid_utf8_oversize_and_arbitrary_error_messages() {
        for bytes in [
            vec![],
            vec![2],
            vec![0, 255],
            vec![0, 0],
            vec![0; 32770],
            b"\x01private/script.lua".to_vec(),
        ] {
            assert_eq!(decode_response(&bytes), Err("UI_RESPONSE_INVALID".into()));
        }
    }
}
