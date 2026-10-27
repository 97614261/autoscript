fn main() {
    let arguments = std::env::args_os().skip(1).collect::<Vec<_>>();
    let (path, require_release, abi) = match arguments.as_slice() {
        [path] => (std::path::PathBuf::from(path), false, None),
        [flag, path] if flag == "--require-embedded-release" => {
            (std::path::PathBuf::from(path), true, None)
        }
        [flag, path] if flag == "--arm64-only" || flag == "--x86-only" => {
            (std::path::PathBuf::from(path), false, Some(flag))
        }
        [abi, flag, path]
            if (abi == "--arm64-only" || abi == "--x86-only")
                && flag == "--require-embedded-release" =>
        {
            (std::path::PathBuf::from(path), true, Some(abi))
        }
        _ => {
            eprintln!(
                "usage: package-probe [--arm64-only|--x86-only] [--require-embedded-release] <runner.apk>"
            );
            std::process::exit(2);
        }
    };
    let result = if abi.is_some_and(|value| value == "--arm64-only") {
        package_probe::probe_path_arm64(&path, require_release)
    } else if abi.is_some_and(|value| value == "--x86-only") {
        package_probe::probe_path_x86(&path, require_release)
    } else if require_release {
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
