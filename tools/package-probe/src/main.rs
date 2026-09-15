fn main() {
    let arguments = std::env::args_os().skip(1).collect::<Vec<_>>();
    let (path, require_release) = match arguments.as_slice() {
        [path] => (std::path::PathBuf::from(path), false),
        [flag, path] if flag == "--require-embedded-release" => {
            (std::path::PathBuf::from(path), true)
        }
        _ => {
            eprintln!("usage: package-probe [--require-embedded-release] <runner.apk>");
            std::process::exit(2);
        }
    };
    let result = if require_release {
        package_probe::probe_path_requiring_embedded_release(&path)
    } else {
        package_probe::probe_path(&path)
    };
    match result {
        Ok(report) => {
            println!("APK package probe passed: {}", path.display());
            for library in report.libraries {
                println!(
                    "{}: stored, 16KiB-aligned at {}, {} bytes, ELF machine {}",
                    library.name, library.data_offset, library.byte_len, library.machine
                );
            }
            println!("APK Signing Block: present");
            println!(
                "Embedded AutoScript release: {}",
                if report.has_embedded_release {
                    "present"
                } else {
                    "template only"
                },
            );
        }
        Err(error) => {
            eprintln!("APK package probe failed: {error}");
            std::process::exit(1);
        }
    }
}
