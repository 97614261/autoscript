use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CheckSummary {
    pub json_documents: usize,
    pub schemas: usize,
    pub api_functions: usize,
    pub block_definitions: usize,
}

struct SchemaDocument {
    path: PathBuf,
    canonical_path: PathBuf,
    id: String,
    value: Value,
}

/// Checks every repository schema and API declaration without using the network.
///
/// # Errors
///
/// Returns an error when JSON is malformed, a schema has no unique identity, a `$ref` cannot be
/// resolved to a local schema/fragment, or the script API catalog violates its typed contract.
pub fn check_repository(root: &Path) -> Result<CheckSummary, String> {
    let schema_root = root.join("schema");
    let mut json_paths = Vec::new();
    collect_json_files(&schema_root, &mut json_paths)?;
    json_paths.sort();

    let mut schemas = Vec::new();
    let mut ids = HashSet::new();
    for path in &json_paths {
        let bytes =
            fs::read(path).map_err(|error| format!("cannot read {}: {error}", path.display()))?;
        let value: Value = serde_json::from_slice(&bytes)
            .map_err(|error| format!("invalid JSON {}: {error}", path.display()))?;
        let declares_schema = value.get("$schema").is_some();
        let schema_filename = path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.ends_with(".schema.json"));
        if declares_schema != schema_filename {
            return Err(format!(
                "{} must both use a *.schema.json name and declare $schema",
                path.display()
            ));
        }
        if declares_schema {
            let object = value
                .as_object()
                .ok_or_else(|| format!("schema {} must be a JSON object", path.display()))?;
            let id = object
                .get("$id")
                .and_then(Value::as_str)
                .filter(|id| !id.is_empty())
                .ok_or_else(|| format!("schema {} must declare a non-empty $id", path.display()))?;
            if !ids.insert(id.to_owned()) {
                return Err(format!("duplicate schema $id {id}"));
            }
            let canonical_path = fs::canonicalize(path)
                .map_err(|error| format!("cannot resolve {}: {error}", path.display()))?;
            schemas.push(SchemaDocument {
                path: path.clone(),
                canonical_path,
                id: id.to_owned(),
                value,
            });
        }
    }

    validate_references(&schemas)?;
    let functions = api_codegen::load_catalog(&schema_root.join("api-schema/functions"))?;
    let blocks = api_codegen::load_block_catalog(&schema_root.join("block-catalog/blocks"))?;
    let api_capabilities = functions
        .iter()
        .map(|function| function.capability.as_str())
        .collect::<HashSet<_>>();
    let schema_ids = schemas
        .iter()
        .map(|document| document.id.as_str())
        .collect::<HashSet<_>>();
    for block in &blocks {
        if !schema_ids.contains(block.node_schema_id.as_str()) {
            return Err(format!(
                "block {} references unknown nodeSchemaId {}",
                block.kind, block.node_schema_id
            ));
        }
        for capability in &block.required_capabilities {
            if !api_capabilities.contains(capability.as_str()) {
                return Err(format!(
                    "block {} requires capability {capability} that no script API declares",
                    block.kind
                ));
            }
        }
    }
    Ok(CheckSummary {
        json_documents: json_paths.len(),
        schemas: schemas.len(),
        api_functions: functions.len(),
        block_definitions: blocks.len(),
    })
}

fn collect_json_files(directory: &Path, output: &mut Vec<PathBuf>) -> Result<(), String> {
    let entries = fs::read_dir(directory)
        .map_err(|error| format!("cannot read {}: {error}", directory.display()))?;
    for entry in entries {
        let path = entry
            .map_err(|error| format!("cannot enumerate {}: {error}", directory.display()))?
            .path();
        if path.is_dir() {
            collect_json_files(&path, output)?;
        } else if path
            .extension()
            .is_some_and(|extension| extension == "json")
        {
            output.push(path);
        }
    }
    Ok(())
}

fn validate_references(documents: &[SchemaDocument]) -> Result<(), String> {
    let by_id = documents
        .iter()
        .map(|document| (document.id.as_str(), document))
        .collect::<HashMap<_, _>>();
    let by_path = documents
        .iter()
        .map(|document| (document.canonical_path.as_path(), document))
        .collect::<HashMap<_, _>>();
    for document in documents {
        let mut references = Vec::new();
        collect_references(&document.value, &mut references)?;
        for reference in references {
            let (target, fragment) = reference
                .split_once('#')
                .map_or((reference, None), |(target, fragment)| {
                    (target, Some(fragment))
                });
            let target_document = if target.is_empty() {
                document
            } else if target.starts_with("https://") || target.starts_with("http://") {
                by_id.get(target).copied().ok_or_else(|| {
                    format!(
                        "{} has non-local or unknown $ref {reference}",
                        document.path.display()
                    )
                })?
            } else {
                let unresolved = document
                    .path
                    .parent()
                    .unwrap_or_else(|| Path::new(""))
                    .join(target);
                let canonical = fs::canonicalize(&unresolved).map_err(|error| {
                    format!(
                        "{} has missing $ref {reference}: {error}",
                        document.path.display()
                    )
                })?;
                by_path.get(canonical.as_path()).copied().ok_or_else(|| {
                    format!(
                        "{} references non-schema document {reference}",
                        document.path.display()
                    )
                })?
            };
            if let Some(fragment) = fragment.filter(|fragment| !fragment.is_empty()) {
                if !fragment.starts_with('/') || target_document.value.pointer(fragment).is_none() {
                    return Err(format!(
                        "{} has unresolved JSON pointer in $ref {reference}",
                        document.path.display()
                    ));
                }
            }
        }
    }
    Ok(())
}

fn collect_references<'a>(value: &'a Value, output: &mut Vec<&'a str>) -> Result<(), String> {
    match value {
        Value::Object(object) => {
            if let Some(reference) = object.get("$ref") {
                output.push(
                    reference
                        .as_str()
                        .ok_or_else(|| "$ref values must be strings".to_string())?,
                );
            }
            for child in object.values() {
                collect_references(child, output)?;
            }
        }
        Value::Array(array) => {
            for child in array {
                collect_references(child, output)?;
            }
        }
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::collect_references;
    use serde_json::json;

    #[test]
    fn nested_references_are_discovered() {
        let value = json!({"allOf": [{"$ref": "#/$defs/name"}], "$defs": {"name": {}}});
        let mut references = Vec::new();
        collect_references(&value, &mut references).expect("valid refs");
        assert_eq!(references, ["#/$defs/name"]);
    }

    #[test]
    fn non_string_reference_is_rejected() {
        let value = json!({"$ref": 7});
        assert!(collect_references(&value, &mut Vec::new()).is_err());
    }
}
