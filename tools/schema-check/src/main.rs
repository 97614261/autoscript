use std::env;
use std::path::PathBuf;

fn main() {
    let root = env::args_os().nth(1).map_or_else(
        || env::current_dir().map_err(|error| error.to_string()),
        |value| Ok(PathBuf::from(value)),
    );
    let result = root.and_then(|root| schema_check::check_repository(&root));
    match result {
        Ok(summary) => println!(
            "checked {} JSON documents, {} schemas, {} API functions and {} block definitions",
            summary.json_documents,
            summary.schemas,
            summary.api_functions,
            summary.block_definitions
        ),
        Err(error) => {
            eprintln!("schema-check: {error}");
            std::process::exit(1);
        }
    }
}
