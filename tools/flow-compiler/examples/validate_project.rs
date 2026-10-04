//! Read-only conversion gate. Never print private args, source, or generated Lua.
use flow_compiler::{compile_project, CompileError, FlowSource, SUPPORTED_NODE_VERSIONS};
use flow_ir::{load_jsonl, parse_project_manifest, DiagnosticCode, LoadOptions, Severity};
use std::collections::BTreeMap;
use std::io::Read;
use std::path::Path;

fn bounded_read(path: &Path, limit: u64) -> Result<Vec<u8>, &'static str> {
    let file = std::fs::File::open(path).map_err(|_| "input file unavailable")?;
    let mut bytes = Vec::new();
    file.take(limit + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "input read failed")?;
    if bytes.len() as u64 > limit {
        return Err("input exceeds size budget");
    }
    Ok(bytes)
}

fn check(directory: &Path) -> Result<bool, &'static str> {
    let manifest =
        parse_project_manifest(&bounded_read(&directory.join("project.json"), 1024 * 1024)?)
            .map_err(|_| "project manifest rejected")?;
    let mut bytes = Vec::new();
    let mut total = 0;
    for flow in &manifest.flows {
        let source = bounded_read(&directory.join(&flow.path), 64 * 1024 * 1024)?;
        total += source.len();
        if total > 128 * 1024 * 1024 {
            return Err("project source exceeds size budget");
        }
        bytes.push(source);
    }
    let reports = manifest
        .flows
        .iter()
        .zip(&bytes)
        .map(|(flow, source)| {
            load_jsonl(
                source,
                LoadOptions {
                    flow_id: &flow.flow_id,
                    root_block_id: &flow.root_block_id,
                    flow_schema_version: manifest.flow_schema_version,
                    supported_nodes: SUPPORTED_NODE_VERSIONS,
                },
            )
        })
        .collect::<Vec<_>>();
    let structural_errors = reports
        .iter()
        .flat_map(|report| &report.diagnostics)
        .filter(|diagnostic| {
            diagnostic.severity == Severity::Error
                && !matches!(
                    diagnostic.code,
                    DiagnosticCode::UnknownNodeKind | DiagnosticCode::UnsupportedNodeVersion
                )
        })
        .count();
    let mut load_errors = BTreeMap::<String, usize>::new();
    for diagnostic in reports.iter().flat_map(|report| &report.diagnostics) {
        if diagnostic.severity == Severity::Error {
            *load_errors
                .entry(format!("{:?}", diagnostic.code))
                .or_default() += 1;
        }
    }
    let sources = manifest
        .flows
        .iter()
        .zip(&bytes)
        .zip(&reports)
        .map(|((flow, source), report)| FlowSource {
            flow_id: &flow.flow_id,
            exact_bytes: source,
            report,
        })
        .collect::<Vec<_>>();
    let mut errors = BTreeMap::<&str, usize>::new();
    let compiled = match compile_project(&manifest, &sources) {
        Ok(_) => true,
        Err(diagnostics) => {
            for error in diagnostics {
                let category = match error {
                    CompileError::UnsupportedNodeKind { .. } => "unsupportedNodeKind",
                    CompileError::InvalidNodeArguments { .. } => "invalidNodeArguments",
                    CompileError::FlowNotCompilable { .. } => "flowNotCompilable",
                    CompileError::MissingCapability { .. } => "missingCapability",
                    CompileError::RecursiveFlowCall => "recursiveFlowCall",
                    _ => "otherCompileError",
                };
                *errors.entry(category).or_default() += 1;
            }
            false
        }
    };
    println!(
        "{}",
        serde_json::json!({
            "manifestValid": true, "plugins": manifest.flows.len(), "variables": manifest.variables.len(),
        "structuralErrors": structural_errors, "loadErrors": load_errors,
        "compiled": compiled, "compileErrors": errors
        })
    );
    Ok(compiled && structural_errors == 0)
}

fn main() {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    if arguments.len() != 1 {
        eprintln!("usage: validate_project <project-directory>");
        std::process::exit(1);
    }
    match check(Path::new(&arguments[0])) {
        Ok(true) => {}
        Ok(false) => std::process::exit(2),
        Err(reason) => {
            eprintln!("validation failed: {reason}");
            std::process::exit(1);
        }
    }
}
