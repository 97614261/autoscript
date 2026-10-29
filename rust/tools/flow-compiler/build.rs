use std::{env, fs, path::PathBuf};

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let catalog = manifest
        .ancestors()
        .nth(3)
        .expect("repository root")
        .join("schema/block-catalog/blocks");
    println!("cargo:rerun-if-changed={}", catalog.display());
    let blocks = api_codegen::load_block_catalog(&catalog).expect("valid block catalog");
    let mut labels = blocks
        .iter()
        .map(|block| (block.kind.as_str(), block.title.as_str()))
        .collect::<Vec<_>>();
    labels.sort_unstable();
    let output = format!("const BLOCK_TITLES: &[(&str, &str)] = &{labels:?};\n");
    fs::write(
        PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("block_titles.rs"),
        output,
    )
    .expect("write block diagnostic titles");
}
