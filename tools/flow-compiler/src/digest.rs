use crate::model::{FlowSource, GenerationRecord, VerificationError};
use flow_ir::ProjectManifest;
use sha2::{Digest, Sha256};

const FLOW_DIGEST_DOMAIN: &[u8] = b"AutoScript.FlowDigest.v1\0";
const GENERATION_ID_DOMAIN: &[u8] = b"AutoScript.GenerationId.v1\0";

pub(crate) fn flow_digest(
    manifest: &ProjectManifest,
    sources: &[FlowSource<'_>],
) -> Result<String, VerificationError> {
    let mut ordered = manifest.flows.iter().collect::<Vec<_>>();
    ordered.sort_by(|left, right| left.flow_id.as_bytes().cmp(right.flow_id.as_bytes()));
    let mut hasher = Sha256::new();
    hasher.update(FLOW_DIGEST_DOMAIN);
    let metadata = serde_json::to_vec(&serde_json::json!({
        "entryFlowId": manifest.entry_flow_id,
        "flows": ordered,
        "variables": manifest.variables,
        "popupStyle": manifest.debug_settings.popup_style,
    }))
    .map_err(|_| VerificationError::FlowDigestMismatch)?;
    put_bytes(&mut hasher, &metadata);
    put_length(&mut hasher, ordered.len());
    for flow in ordered {
        let source = sources
            .iter()
            .find(|source| source.flow_id == flow.flow_id)
            .ok_or(VerificationError::FlowDigestMismatch)?;
        put_bytes(&mut hasher, flow.flow_id.as_bytes());
        put_bytes(&mut hasher, flow.path.as_bytes());
        put_bytes(&mut hasher, source.exact_bytes);
    }
    Ok(hex_digest(hasher.finalize()))
}

pub(crate) fn content_digest(content: &[u8]) -> String {
    hex_digest(Sha256::digest(content))
}

pub(crate) fn generation_id(
    flow_digest: &str,
    generator_version: &str,
    runtime_api: &str,
) -> String {
    let mut hasher = Sha256::new();
    hasher.update(GENERATION_ID_DOMAIN);
    put_bytes(&mut hasher, flow_digest.as_bytes());
    put_bytes(&mut hasher, generator_version.as_bytes());
    put_bytes(&mut hasher, runtime_api.as_bytes());
    let digest = hex_digest(hasher.finalize());
    format!("gen-{}", &digest[..32])
}

pub(crate) fn verify_record(
    record: &GenerationRecord,
    manifest: &ProjectManifest,
    sources: &[FlowSource<'_>],
    main_lua: &[u8],
    source_map_json: &[u8],
    generator_version: &str,
) -> Result<(), VerificationError> {
    if record.runtime_api != manifest.runtime_api {
        return Err(VerificationError::RuntimeApiMismatch);
    }
    if record.generator_version != generator_version {
        return Err(VerificationError::GeneratorVersionMismatch);
    }
    if record.flow_digest != flow_digest(manifest, sources)? {
        return Err(VerificationError::FlowDigestMismatch);
    }
    if record.lua_digest != content_digest(main_lua) {
        return Err(VerificationError::LuaDigestMismatch);
    }
    if record.source_map_digest != content_digest(source_map_json) {
        return Err(VerificationError::SourceMapDigestMismatch);
    }
    let expected_generation_id = generation_id(
        &record.flow_digest,
        &record.generator_version,
        &record.runtime_api,
    );
    if record.generation_id != expected_generation_id {
        return Err(VerificationError::GenerationRecordMismatch);
    }
    Ok(())
}

fn put_length(hasher: &mut Sha256, length: usize) {
    let length = u64::try_from(length).unwrap_or(u64::MAX);
    hasher.update(length.to_be_bytes());
}

fn put_bytes(hasher: &mut Sha256, bytes: &[u8]) {
    put_length(hasher, bytes.len());
    hasher.update(bytes);
}

fn hex_digest(bytes: impl AsRef<[u8]>) -> String {
    let bytes = bytes.as_ref();
    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        let _ = write!(output, "{byte:02x}");
    }
    output
}
