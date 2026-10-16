//! Studio-only JNI compiler boundary. Never packaged in the frozen-script Runner.
mod draft_set;
mod release_prepare;
mod visual_compile;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

#[no_mangle]
pub extern "system" fn Java_com_autoscript_studio_compiler_NativeReleaseBridge_nativePrepareRelease(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: jni::sys::jlong,
    project_directory: JString<'_>,
    output_directory: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        for path in [&project_directory, &output_directory] {
            let length = env
                .call_method(path, "length", "()I", &[])
                .and_then(jni::objects::JValueGen::i)
                .map_err(|error| error.to_string())?;
            if !(1..=4096).contains(&length) {
                return Err("device release path exceeds budget".to_owned());
            }
        }
        let project: String = env
            .get_string(&project_directory)
            .map_err(|e| e.to_string())?
            .into();
        let output: String = env
            .get_string(&output_directory)
            .map_err(|e| e.to_string())?
            .into();
        Ok::<_, String>(release_prepare::prepare(request_id, &project, &output))
    }));
    let reply = result.ok().and_then(Result::ok).unwrap_or_else(|| {
        "{\"status\":\"failed\",\"message\":\"release preparation failed\"}".to_owned()
    });
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_studio_compiler_NativeReleaseBridge_nativeCancelRelease(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    request_id: jni::sys::jlong,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| release_prepare::cancel(request_id)));
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeCompileVisualProject(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|error| error.to_string())?
            .into();
        Ok::<_, String>(visual_compile::compile_project_directory(
            std::path::Path::new(&directory),
        ))
    }));
    let reply = match result {
        Ok(Ok(reply)) => reply,
        Ok(Err(error)) => visual_compile::internal_error_reply(&error),
        Err(_) => visual_compile::internal_error_reply("visual compiler panicked"),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeCompileVisualNodeTest(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
    flow: JString<'_>,
    node: JString<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|e| e.to_string())?
            .into();
        let flow: String = env.get_string(&flow).map_err(|e| e.to_string())?.into();
        let node: String = env.get_string(&node).map_err(|e| e.to_string())?.into();
        if directory.len() > 4096 || flow.len() > 128 || node.len() > 128 {
            return Err("test identity exceeds budget".to_owned());
        }
        Ok::<_, String>(visual_compile::compile_node_test(
            std::path::Path::new(&directory),
            &flow,
            &node,
        ))
    }));
    let reply = result
        .ok()
        .and_then(Result::ok)
        .unwrap_or_else(|| visual_compile::internal_error_reply("node test compiler failed"));
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeValidateVisualDraft(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
    flow_id: JString<'_>,
    draft: JByteArray<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|error| error.to_string())?
            .into();
        let flow_id: String = env
            .get_string(&flow_id)
            .map_err(|error| error.to_string())?
            .into();
        let draft = env
            .convert_byte_array(draft)
            .map_err(|error| error.to_string())?;
        Ok::<_, String>(visual_compile::validate_project_draft(
            std::path::Path::new(&directory),
            &flow_id,
            &draft,
        ))
    }));
    let reply = match result {
        Ok(Ok(reply)) => reply,
        Ok(Err(error)) => visual_compile::internal_error_reply(&error),
        Err(_) => visual_compile::internal_error_reply("visual draft validator panicked"),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}

#[no_mangle]
pub extern "system" fn Java_com_autoscript_engine_jni_NativeEngineBridge_nativeValidateVisualDraftSet(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    project_directory: JString<'_>,
    draft: JByteArray<'_>,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let length = env.get_array_length(&draft).map_err(|_| "JNI_FAILURE")?;
        if usize::try_from(length).map_err(|_| "RESOURCE_LIMIT")? > draft_set::MAX_SET_BYTES {
            return Err("RESOURCE_LIMIT");
        }
        let directory: String = env
            .get_string(&project_directory)
            .map_err(|_| "JNI_FAILURE")?
            .into();
        let bytes = env.convert_byte_array(draft).map_err(|_| "JNI_FAILURE")?;
        Ok(visual_compile::validate_project_draft_set(
            std::path::Path::new(&directory),
            &bytes,
        ))
    }));
    let reply = match result {
        Ok(Ok(reply)) => reply,
        Ok(Err(code)) => visual_compile::internal_error_reply(code),
        Err(_) => visual_compile::internal_error_reply("DRAFT_SET_PANIC"),
    };
    env.new_string(reply)
        .map_or(std::ptr::null_mut(), JString::into_raw)
}
