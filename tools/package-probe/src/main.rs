use std::path::PathBuf;

fn main() {
    let mut arguments = std::env::args_os().skip(1);
    let Some(path) = arguments.next().map(PathBuf::from) else {
        eprintln!("usage: package-probe <runner.apk>");
        std::process::exit(2);
    };
    if arguments.next().is_some() {
        eprintln!("usage: package-probe <runner.apk>");
        std::process::exit(2);
    }
    match package_probe::probe_path(&path) {
        Ok(report) => {
            println!("APK package probe passed: {}", path.display());
            for library in report.libraries {
                println!(
                    "{}: stored, 16KiB-aligned at {}, {} bytes, ELF machine {}",
                    library.name, library.data_offset, library.byte_len, library.machine
                );
            }
            println!("APK Signing Block: present");
        }
        Err(error) => {
            eprintln!("APK package probe failed: {error}");
            std::process::exit(1);
        }
    }
}
