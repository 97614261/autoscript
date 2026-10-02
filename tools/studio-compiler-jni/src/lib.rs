//! Studio-only JNI compiler boundary. Never packaged in the frozen-script Runner.
mod visual_compile;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

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
