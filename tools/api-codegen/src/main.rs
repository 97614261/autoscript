use api_codegen::{
    load_block_catalog, load_catalog, render_block_kotlin, render_kotlin, reviewable_outputs,
};
use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    if let Err(error) = run() {
        eprintln!("api-codegen: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut args = env::args().skip(1);
    let mode = args.next().unwrap_or_else(|| "check".into());
    let root = args.next().map_or_else(
        || env::current_dir().map_err(|error| error.to_string()),
        |value| Ok(PathBuf::from(value)),
    )?;
    let functions = load_catalog(&root.join("schema/api-schema/functions"))?;
    if mode == "blocks-kotlin" {
        let output = args.next().ok_or_else(|| {
            "usage: api-codegen blocks-kotlin <repository-root> <output-file>".to_string()
        })?;
        if args.next().is_some() {
            return Err("usage: api-codegen blocks-kotlin <repository-root> <output-file>".into());
        }
        let blocks = load_block_catalog(&root.join("schema/block-catalog/blocks"))?;
        let path = PathBuf::from(output);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
        }
        fs::write(&path, render_block_kotlin(&blocks))
            .map_err(|error| format!("cannot write {}: {error}", path.display()))?;
        println!("generated {}", path.display());
        return Ok(());
    }
    if mode == "kotlin" {
        let output = args.next().ok_or_else(|| {
            "usage: api-codegen kotlin <repository-root> <output-file>".to_string()
        })?;
        if args.next().is_some() {
            return Err("usage: api-codegen kotlin <repository-root> <output-file>".into());
        }
        let path = PathBuf::from(output);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
        }
        fs::write(&path, render_kotlin(&functions))
            .map_err(|error| format!("cannot write {}: {error}", path.display()))?;
        println!("generated {}", path.display());
        return Ok(());
    }
    if args.next().is_some() || (mode != "generate" && mode != "check") {
        return Err(
            "usage: api-codegen [generate|check] [repository-root]\n       api-codegen kotlin <repository-root> <output-file>\n       api-codegen blocks-kotlin <repository-root> <output-file>"
                .into(),
        );
    }

    let outputs = reviewable_outputs(&root, &functions);
    for (path, expected) in outputs {
        if mode == "check" {
            let actual = fs::read_to_string(&path)
                .map_err(|error| format!("cannot read generated {}: {error}", path.display()))?;
            if actual != expected {
                return Err(format!("generated output is stale: {}", path.display()));
            }
        } else {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)
                    .map_err(|error| format!("cannot create {}: {error}", parent.display()))?;
            }
            fs::write(&path, expected)
                .map_err(|error| format!("cannot write {}: {error}", path.display()))?;
            println!("generated {}", path.display());
        }
    }
    Ok(())
}
