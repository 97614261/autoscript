//! Fixed UI adapter: bounded strings only, no arbitrary Android objects or member access.
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
        f.write_str("ScriptUiCallback")
    }
}

#[derive(Debug, Default)]
pub(super) struct ScriptUiHub {
    callback: Mutex<Option<Arc<Callback>>>,
    active: Mutex<Option<(RequestId, TaskToken, u64)>>,
    sequence: AtomicU64,
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
        let outcome = (|| -> Result<Vec<u8>, String> {
            let [lua_runtime::LuaScalar::Bytes(id), lua_runtime::LuaScalar::Bytes(operation), lua_runtime::LuaScalar::Bytes(value)] =
                request.args.as_slice()
            else {
                return Err("UI_REQUEST_INVALID".into());
            };
            let strings = [id, operation, value]
                .map(|bytes| std::str::from_utf8(bytes).map_err(|_| "UI_REQUEST_INVALID"));
            let [id, operation, value] = strings;
            let (id, operation, value) = (id?, operation?, value?);
            if id.len() > 64
                || operation.len() > 32
                || value.len() > 8192
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
                .filter(|work| *work <= i64::MAX as u64)
                .ok_or("UI_ID_EXHAUSTED")?;
            *self
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) =
                Some((request.request_id, request.task, work));
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
                                JValue::Long(work as i64),
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
    use super::decode_response;

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
