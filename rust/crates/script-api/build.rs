use api_codegen::{load_catalog, render_rust};
use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let root = manifest_dir.ancestors().nth(3).expect("workspace root");
    let functions_dir = root.join("schema/api-schema/functions");
    println!("cargo:rerun-if-changed={}", functions_dir.display());
    let functions = load_catalog(&functions_dir).expect("valid API schema");
    let output = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("api_registry.rs");
    fs::write(output, render_rust(&functions)).expect("write generated API registry");
}
