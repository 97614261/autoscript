use release_packager::{prepare_project, verify_bundle, PrepareOptions};
use std::path::Path;

fn main() {
    let arguments = std::env::args().skip(1).collect::<Vec<_>>();
    if let Err(error) = run(&arguments) {
        eprintln!("release-packager failed: {error}");
        std::process::exit(1);
    }
}

fn run(arguments: &[String]) -> Result<(), String> {
    match arguments {
        [command, directory] if command == "verify" => {
            let release = verify_bundle(Path::new(directory)).map_err(|error| error.to_string())?;
            println!(
                "release verified: {} {} ({})",
                release.application_id, release.version_name, release.release_id
            );
            Ok(())
        }
        [command, project, output, application_id, version_code, version_name]
            if command == "prepare" =>
        {
            let options = PrepareOptions {
                application_id: application_id.clone(),
                version_code: version_code
                    .parse::<u32>()
                    .map_err(|_| "versionCode must be an unsigned integer".to_owned())?,
                version_name: version_name.clone(),
            };
            let release = prepare_project(Path::new(project), Path::new(output), &options)
                .map_err(|error| error.to_string())?;
            println!(
                "release prepared: {} {} ({})",
                release.application_id, release.version_name, release.release_id
            );
            Ok(())
        }
        _ => Err(
            "usage:\n  release-packager prepare <project-dir> <output-dir> <application-id> <version-code> <version-name>\n  release-packager verify <release-dir>"
                .to_owned(),
        ),
    }
}
