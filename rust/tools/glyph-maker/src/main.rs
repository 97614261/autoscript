use std::env;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;

fn main() {
    if let Err(error) = run() {
        eprintln!("glyph-maker failed: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let arguments = env::args().skip(1).collect::<Vec<_>>();
    let [source_path, dictionary_path, preview_path] = arguments.as_slice() else {
        return Err("usage: glyph-maker <source.json> <output.asglyph> <preview.html>".to_owned());
    };
    let metadata = fs::metadata(source_path).map_err(|error| error.to_string())?;
    if metadata.len() > glyph_maker::MAX_SOURCE_BYTES as u64 {
        return Err("source JSON exceeds 8 MiB".to_owned());
    }
    let source = fs::read(source_path).map_err(|error| error.to_string())?;
    let output = glyph_maker::compile_source(&source).map_err(|error| format!("{error:?}"))?;
    write_new(Path::new(dictionary_path), &output.dictionary)?;
    if let Err(error) = write_new(Path::new(preview_path), &output.preview_html) {
        let _ = fs::remove_file(dictionary_path);
        return Err(error);
    }
    println!(
        "wrote {} glyphs to {} and {}",
        output.glyph_count, dictionary_path, preview_path
    );
    Ok(())
}

fn write_new(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|error| format!("{}: {error}", path.display()))?;
    file.write_all(bytes).map_err(|error| error.to_string())?;
    file.sync_all().map_err(|error| error.to_string())
}
