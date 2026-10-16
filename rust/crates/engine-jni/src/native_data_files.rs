//! Fixed project-private data callback, never an arbitrary native filesystem capability.

use jni::objects::{GlobalRef, JByteArray, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use lua_runtime::LuaScalar;
use runtime_executor::{HostCancelListener, HostRequest};
use runtime_scheduler::{HostResult, RequestId, TaskToken};
use std::borrow::Cow;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

const MAX_BYTES: usize = 65_536;

struct Callback {
    vm: JavaVM,
    listener: GlobalRef,
}
impl std::fmt::Debug for Callback {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("ProjectDataFileCallback")
    }
}

#[derive(Debug, Default)]
pub(super) struct DataFileHub {
    callback: Mutex<Option<Arc<Callback>>>,
    active: Mutex<Option<(RequestId, TaskToken, u64)>>,
    sequence: AtomicU64,
}

impl HostCancelListener for DataFileHub {
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

impl DataFileHub {
    pub fn set(&self, env: &JNIEnv<'_>, listener: JObject<'_>) -> Result<(), String> {
        if listener.is_null() {
            return Err("FILE_ADAPTER_UNAVAILABLE".into());
        }
        let callback = Callback {
            vm: env.get_java_vm().map_err(|_| "FILE_VM_UNAVAILABLE")?,
            listener: env
                .new_global_ref(listener)
                .map_err(|_| "FILE_ADAPTER_UNAVAILABLE")?,
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

    pub fn clear(&self) {
        self.stop();
        *self
            .callback
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = None;
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
            let (path, file_payload) = normalized_arguments(request.opcode, &request.args)?;
            let callback = self
                .callback
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .clone()
                .ok_or("FILE_ADAPTER_UNAVAILABLE")?;
            let work = self
                .sequence
                .fetch_add(1, Ordering::AcqRel)
                .checked_add(1)
                .filter(|work| i64::try_from(*work).is_ok())
                .ok_or("FILE_ID_EXHAUSTED")?;
            *self
                .active
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner) =
                Some((request.request_id, request.task, work));
            let started = Instant::now();
            let cancelled = || {
                context.input_stop_requested.load(Ordering::Acquire)
                    || context
                        .host_queue
                        .take_cancellation(request.request_id, request.task)
                        .is_some()
            };
            let result = (|| {
                if cancelled() {
                    return Err("FILE_CANCELLED".into());
                }
                let mut env = callback
                    .vm
                    .attach_current_thread_as_daemon()
                    .map_err(|_| "FILE_VM_UNAVAILABLE")?;
                env.push_local_frame(8).map_err(|_| "FILE_JNI_FAILED")?;
                let result = (|| {
                    let path = env.new_string(path).map_err(|_| "FILE_JNI_FAILED")?;
                    let file_payload = env
                        .byte_array_from_slice(&file_payload)
                        .map_err(|_| "FILE_JNI_FAILED")?;
                    let result = env
                        .call_method(
                            callback.listener.as_obj(),
                            "dispatch",
                            "(JILjava/lang/String;[B)[B",
                            &[
                                JValue::Long(i64::try_from(work).map_err(|_| "FILE_ID_EXHAUSTED")?),
                                JValue::Int(
                                    i32::try_from(request.opcode)
                                        .map_err(|_| "FILE_REQUEST_INVALID")?,
                                ),
                                JValue::Object(path.as_ref()),
                                JValue::Object(file_payload.as_ref()),
                            ],
                        )
                        .map_err(|_| "FILE_JNI_FAILED")?;
                    let array = JByteArray::from(result.l().map_err(|_| "FILE_RESPONSE_INVALID")?);
                    let length = env
                        .get_array_length(&array)
                        .map_err(|_| "FILE_RESPONSE_INVALID")?;
                    let maximum = if request.opcode >= 14110 {
                        393_729
                    } else {
                        65_537
                    };
                    if !(1..=maximum).contains(&length) {
                        return Err("FILE_RESPONSE_INVALID".into());
                    }
                    let bytes = env
                        .convert_byte_array(array)
                        .map_err(|_| "FILE_RESPONSE_INVALID")?;
                    decode_response(request.opcode, &bytes)
                })();
                let _ = env.exception_clear();
                // SAFETY: all Java references stay in this frame; the result owns Rust bytes.
                let _ = unsafe { env.pop_local_frame(&JObject::null()) };
                if cancelled() {
                    return Err("FILE_CANCELLED".into());
                }
                if started.elapsed() >= request.timeout {
                    return Err("FILE_TIMEOUT".into());
                }
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
                message: "Project data file request did not complete".into(),
            },
        }
    }
}

fn arguments(opcode: u32, args: &[LuaScalar]) -> Result<(&str, &[u8]), String> {
    if !(14100..=14103).contains(&opcode) || args.len() != if opcode == 14101 { 2 } else { 1 } {
        return Err("FILE_REQUEST_INVALID".into());
    }
    let Some(LuaScalar::Bytes(bytes)) = args.first() else {
        return Err("FILE_REQUEST_INVALID".into());
    };
    let path = std::str::from_utf8(bytes).map_err(|_| "FILE_REQUEST_INVALID")?;
    if path.len() > 256
        || (path.is_empty() && opcode != 14102)
        || (!path.is_empty()
            && (path.split('/').count() > 16
                || path.split('/').any(|part| {
                    part.is_empty()
                        || part.starts_with('.')
                        || part.ends_with('.')
                        || part.ends_with(' ')
                        || part.chars().any(|character| {
                            character.is_control()
                                || matches!(
                                    character,
                                    '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|'
                                )
                        })
                })))
    {
        return Err("FILE_REQUEST_INVALID".into());
    }
    let content = if opcode == 14101 {
        let LuaScalar::Bytes(content) = &args[1] else {
            return Err("FILE_REQUEST_INVALID".into());
        };
        if content.len() > MAX_BYTES || std::str::from_utf8(content).is_err() {
            return Err("FILE_REQUEST_INVALID".into());
        }
        content.as_slice()
    } else {
        &[]
    };
    Ok((path, content))
}
fn normalized_arguments(opcode: u32, args: &[LuaScalar]) -> Result<(&str, Cow<'_, [u8]>), String> {
    if opcode == 14130 {
        let [path, LuaScalar::Bytes(content)] = args else {
            return Err("FILE_REQUEST_INVALID".into());
        };
        let (path, _) = arguments(14100, std::slice::from_ref(path))?;
        if std::path::Path::new(path)
            .extension()
            .is_none_or(|ext| !ext.eq_ignore_ascii_case("png"))
            || content.len() > 20 * 1024 * 1024
            || !content.starts_with(b"\x89PNG\r\n\x1a\n")
        {
            return Err("FILE_REQUEST_INVALID".into());
        }
        return Ok((path, Cow::Borrowed(content)));
    }
    if opcode < 14110 {
        let (path, content) = arguments(opcode, args)?;
        return Ok((path, Cow::Borrowed(content)));
    }
    runtime_executor::validate_private_data_call(opcode, args)
        .map_err(|_| "FILE_REQUEST_INVALID")?;
    let Some(LuaScalar::Bytes(path)) = args.first() else {
        return Err("FILE_REQUEST_INVALID".into());
    };
    let path = std::str::from_utf8(path).map_err(|_| "FILE_REQUEST_INVALID")?;
    let payload = args[1..]
        .iter()
        .map(|arg| match arg {
            LuaScalar::Bytes(v) => std::str::from_utf8(v)
                .map(|s| serde_json::Value::String(s.into()))
                .map_err(|_| "FILE_REQUEST_INVALID"),
            LuaScalar::Integer(v) => Ok(serde_json::Value::from(*v)),
            _ => Err("FILE_REQUEST_INVALID"),
        })
        .collect::<Result<Vec<_>, _>>()?;
    let payload = serde_json::to_vec(&payload).map_err(|_| "FILE_REQUEST_INVALID")?;
    if payload.len() > 393_728 {
        return Err("FILE_REQUEST_INVALID".into());
    }
    Ok((path, Cow::Owned(payload)))
}

fn decode_response(opcode: u32, bytes: &[u8]) -> Result<Vec<u8>, String> {
    let maximum = if matches!(opcode, 14110..=14126 | 14140..=14146) {
        393_729
    } else {
        MAX_BYTES + 1
    };
    if bytes.is_empty() || bytes.len() > maximum {
        return Err("FILE_RESPONSE_INVALID".into());
    }
    match bytes[0] {
        0 if std::str::from_utf8(&bytes[1..]).is_ok()
            && (!matches!(opcode, 14101 | 14103 | 14130) || bytes.len() == 1) =>
        {
            Ok(bytes[1..].to_vec())
        }
        1 => {
            let code = std::str::from_utf8(&bytes[1..]).map_err(|_| "FILE_RESPONSE_INVALID")?;
            if matches!(
                code,
                "FILE_UNAVAILABLE"
                    | "FILE_NOT_FOUND"
                    | "FILE_TOO_LARGE"
                    | "FILE_PATH_INVALID"
                    | "FILE_UTF8_INVALID"
                    | "FILE_EXISTS"
                    | "FILE_QUOTA"
                    | "FILE_SESSION_STALE"
                    | "FILE_CANCELLED"
                    | "FILE_TIMEOUT"
                    | "FILE_QUEUE_FULL"
                    | "FILE_REQUEST_INVALID"
                    | "FILE_ADAPTER_UNAVAILABLE"
                    | "FILE_DIRECTORY_NOT_EMPTY"
            ) {
                Err(code.into())
            } else {
                Err("FILE_RESPONSE_INVALID".into())
            }
        }
        _ => Err("FILE_RESPONSE_INVALID".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::{arguments, decode_response, normalized_arguments};
    use lua_runtime::LuaScalar;
    fn bytes(value: &str) -> LuaScalar {
        LuaScalar::Bytes(value.as_bytes().to_vec())
    }

    #[test]
    fn requests_keep_project_identity_out_of_lua_and_reject_escape_or_unbounded_data() {
        assert_eq!(
            arguments(14101, &[bytes("settings/state.json"), bytes("汉字")]).unwrap(),
            ("settings/state.json", "汉字".as_bytes())
        );
        assert_eq!(arguments(14102, &[bytes("")]).unwrap(), ("", &[][..]));
        for path in [
            "../key",
            "/private/script.lua",
            "C:/key",
            "x\\key",
            ".trash/file",
            "x/../../key",
            "x//file",
        ] {
            assert_eq!(
                arguments(14100, &[bytes(path)]),
                Err("FILE_REQUEST_INVALID".into())
            );
        }
        assert!(arguments(14101, &[bytes("state"), LuaScalar::Bytes(vec![255])]).is_err());
        assert!(arguments(
            14101,
            &[bytes("state"), LuaScalar::Bytes(vec![b'x'; 65537])]
        )
        .is_err());
        assert!(arguments(14100, &[bytes("state"), bytes("unexpected")]).is_err());
    }
    #[test]
    fn extended_requests_encode_only_typed_arguments_and_preserve_legacy_wire() {
        let args = [bytes("a"), LuaScalar::Integer(1), bytes("中文")];
        let (path, payload) = normalized_arguments(14118, &args).unwrap();
        assert_eq!(path, "a");
        assert_eq!(std::str::from_utf8(&payload).unwrap(), "[1,\"中文\"]");
        assert!(
            normalized_arguments(14118, &[bytes("a"), LuaScalar::Number(1.0), bytes("x")]).is_err()
        );
        assert!(normalized_arguments(14114, &[bytes("a"), bytes("../outside")]).is_err());
        assert!(normalized_arguments(14127, &[bytes("a")]).is_err());
        let legacy = [bytes("a"), bytes("raw")];
        assert_eq!(
            normalized_arguments(14101, &legacy).unwrap().1.as_ref(),
            b"raw"
        );
        assert_eq!(
            decode_response(14116, b"\x01FILE_DIRECTORY_NOT_EMPTY"),
            Err("FILE_DIRECTORY_NOT_EMPTY".into())
        );
    }

    #[test]
    fn responses_are_bounded_utf8_and_never_return_exception_text_or_void_payload() {
        assert_eq!(decode_response(14100, b"\0"), Ok(vec![]));
        assert_eq!(
            decode_response(14100, "\0汉字".as_bytes()),
            Ok("汉字".as_bytes().to_vec())
        );
        assert_eq!(
            decode_response(14102, b"\0[\"state.json\"]"),
            Ok(b"[\"state.json\"]".to_vec())
        );
        assert_eq!(
            decode_response(14100, b"\x01FILE_CANCELLED"),
            Err("FILE_CANCELLED".into())
        );
        for reply in [
            vec![],
            vec![0, 255],
            vec![2],
            vec![0; 65538],
            b"\x01PRIVATE_KEY_CONTENT".to_vec(),
            b"\x01/private/file.lua".to_vec(),
        ] {
            assert_eq!(
                decode_response(14100, &reply),
                Err("FILE_RESPONSE_INVALID".into())
            );
        }
        assert_eq!(
            decode_response(14101, b"\0unexpected"),
            Err("FILE_RESPONSE_INVALID".into())
        );
    }
}
