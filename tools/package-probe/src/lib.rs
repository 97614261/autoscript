//! Small, dependency-free APK packaging gate for the Rust JNI library.

use std::fmt;
use std::path::Path;

const EOCD_SIGNATURE: u32 = 0x0605_4b50;
const CENTRAL_SIGNATURE: u32 = 0x0201_4b50;
const LOCAL_SIGNATURE: u32 = 0x0403_4b50;
const APK_SIGNING_MAGIC: &[u8; 16] = b"APK Sig Block 42";
const PAGE_ALIGNMENT: usize = 16 * 1024;

const REQUIRED_LIBRARIES: [(&str, u16); 2] = [
    ("lib/arm64-v8a/libengine_jni.so", 183),
    ("lib/x86_64/libengine_jni.so", 62),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProbeError {
    Io(String),
    EndOfCentralDirectoryMissing,
    UnsupportedZipLayout,
    InvalidCentralDirectory,
    InvalidUtf8Name,
    MissingLibrary(&'static str),
    DuplicateLibrary(&'static str),
    LibraryCompressed(&'static str),
    InvalidLocalHeader(&'static str),
    LibraryNotPageAligned {
        name: &'static str,
        offset: usize,
    },
    InvalidElf(&'static str),
    WrongElfMachine {
        name: &'static str,
        expected: u16,
        actual: u16,
    },
    ApkSigningBlockMissing,
}

impl fmt::Display for ProbeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{self:?}")
    }
}

impl std::error::Error for ProbeError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LibraryReport {
    pub name: &'static str,
    pub data_offset: usize,
    pub byte_len: usize,
    pub machine: u16,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PackageReport {
    pub libraries: Vec<LibraryReport>,
    pub has_apk_signing_block: bool,
}

#[derive(Debug)]
struct CentralEntry<'a> {
    name: &'a str,
    compression: u16,
    compressed_size: usize,
    local_offset: usize,
}

/// Reads and validates a built APK.
///
/// # Errors
///
/// Returns a stable structural error if the package is malformed or violates an R0 invariant.
pub fn probe_path(path: &Path) -> Result<PackageReport, ProbeError> {
    let bytes = std::fs::read(path).map_err(|error| ProbeError::Io(error.to_string()))?;
    probe_bytes(&bytes)
}

/// Validates the ZIP and native-library properties needed by the Runner.
///
/// # Errors
///
/// Returns an error for missing ABIs, compressed or misaligned libraries, bad ELF metadata,
/// unsupported ZIP layouts, or a missing APK v2+ signing block.
pub fn probe_bytes(bytes: &[u8]) -> Result<PackageReport, ProbeError> {
    let eocd = find_eocd(bytes).ok_or(ProbeError::EndOfCentralDirectoryMissing)?;
    if read_u16(bytes, eocd + 4)? != 0
        || read_u16(bytes, eocd + 6)? != 0
        || read_u16(bytes, eocd + 8)? != read_u16(bytes, eocd + 10)?
    {
        return Err(ProbeError::UnsupportedZipLayout);
    }
    let entries = usize::from(read_u16(bytes, eocd + 10)?);
    let central_size = usize::try_from(read_u32(bytes, eocd + 12)?)
        .map_err(|_| ProbeError::UnsupportedZipLayout)?;
    let central_offset = usize::try_from(read_u32(bytes, eocd + 16)?)
        .map_err(|_| ProbeError::UnsupportedZipLayout)?;
    let central_end = central_offset
        .checked_add(central_size)
        .ok_or(ProbeError::InvalidCentralDirectory)?;
    if central_end != eocd || central_end > bytes.len() {
        return Err(ProbeError::InvalidCentralDirectory);
    }
    let has_signing_block = central_offset >= APK_SIGNING_MAGIC.len()
        && bytes[central_offset - APK_SIGNING_MAGIC.len()..central_offset] == APK_SIGNING_MAGIC[..];
    if !has_signing_block {
        return Err(ProbeError::ApkSigningBlockMissing);
    }

    let central_entries = parse_central_entries(bytes, central_offset, entries, central_end)?;
    let mut reports = Vec::with_capacity(REQUIRED_LIBRARIES.len());
    for (required_name, expected_machine) in REQUIRED_LIBRARIES {
        let matching: Vec<_> = central_entries
            .iter()
            .filter(|entry| entry.name == required_name)
            .collect();
        let [entry] = matching.as_slice() else {
            return if matching.is_empty() {
                Err(ProbeError::MissingLibrary(required_name))
            } else {
                Err(ProbeError::DuplicateLibrary(required_name))
            };
        };
        if entry.compression != 0 {
            return Err(ProbeError::LibraryCompressed(required_name));
        }
        let data_offset = local_data_offset(bytes, entry.local_offset, required_name)?;
        if data_offset % PAGE_ALIGNMENT != 0 {
            return Err(ProbeError::LibraryNotPageAligned {
                name: required_name,
                offset: data_offset,
            });
        }
        let data_end = data_offset
            .checked_add(entry.compressed_size)
            .ok_or(ProbeError::InvalidElf(required_name))?;
        let elf = bytes
            .get(data_offset..data_end)
            .ok_or(ProbeError::InvalidElf(required_name))?;
        let machine = validate_elf(elf, required_name, expected_machine)?;
        reports.push(LibraryReport {
            name: required_name,
            data_offset,
            byte_len: entry.compressed_size,
            machine,
        });
    }
    Ok(PackageReport {
        libraries: reports,
        has_apk_signing_block: has_signing_block,
    })
}

fn find_eocd(bytes: &[u8]) -> Option<usize> {
    let minimum = bytes.len().saturating_sub(65_557);
    (minimum..bytes.len().saturating_sub(21))
        .rev()
        .find(|offset| {
            read_u32(bytes, *offset).ok() == Some(EOCD_SIGNATURE)
                && read_u16(bytes, *offset + 20)
                    .ok()
                    .is_some_and(|comment_len| {
                        *offset + 22 + usize::from(comment_len) == bytes.len()
                    })
        })
}

fn parse_central_entries(
    bytes: &[u8],
    start: usize,
    count: usize,
    end: usize,
) -> Result<Vec<CentralEntry<'_>>, ProbeError> {
    let mut offset = start;
    let mut result = Vec::with_capacity(count);
    for _ in 0..count {
        if read_u32(bytes, offset)? != CENTRAL_SIGNATURE {
            return Err(ProbeError::InvalidCentralDirectory);
        }
        let name_len = usize::from(read_u16(bytes, offset + 28)?);
        let extra_len = usize::from(read_u16(bytes, offset + 30)?);
        let comment_len = usize::from(read_u16(bytes, offset + 32)?);
        let record_len = 46_usize
            .checked_add(name_len)
            .and_then(|value| value.checked_add(extra_len))
            .and_then(|value| value.checked_add(comment_len))
            .ok_or(ProbeError::InvalidCentralDirectory)?;
        let record_end = offset
            .checked_add(record_len)
            .ok_or(ProbeError::InvalidCentralDirectory)?;
        if record_end > end || read_u16(bytes, offset + 34)? != 0 {
            return Err(ProbeError::UnsupportedZipLayout);
        }
        let name_bytes = bytes
            .get(offset + 46..offset + 46 + name_len)
            .ok_or(ProbeError::InvalidCentralDirectory)?;
        let name = std::str::from_utf8(name_bytes).map_err(|_| ProbeError::InvalidUtf8Name)?;
        result.push(CentralEntry {
            name,
            compression: read_u16(bytes, offset + 10)?,
            compressed_size: usize::try_from(read_u32(bytes, offset + 20)?)
                .map_err(|_| ProbeError::UnsupportedZipLayout)?,
            local_offset: usize::try_from(read_u32(bytes, offset + 42)?)
                .map_err(|_| ProbeError::UnsupportedZipLayout)?,
        });
        offset = record_end;
    }
    if offset != end {
        return Err(ProbeError::InvalidCentralDirectory);
    }
    Ok(result)
}

fn local_data_offset(bytes: &[u8], offset: usize, name: &'static str) -> Result<usize, ProbeError> {
    if read_u32(bytes, offset).ok() != Some(LOCAL_SIGNATURE) {
        return Err(ProbeError::InvalidLocalHeader(name));
    }
    let name_len = usize::from(
        read_u16(bytes, offset + 26).map_err(|_| ProbeError::InvalidLocalHeader(name))?,
    );
    let extra_len = usize::from(
        read_u16(bytes, offset + 28).map_err(|_| ProbeError::InvalidLocalHeader(name))?,
    );
    offset
        .checked_add(30)
        .and_then(|value| value.checked_add(name_len))
        .and_then(|value| value.checked_add(extra_len))
        .filter(|value| *value <= bytes.len())
        .ok_or(ProbeError::InvalidLocalHeader(name))
}

fn validate_elf(elf: &[u8], name: &'static str, expected_machine: u16) -> Result<u16, ProbeError> {
    if elf.len() < 20 || &elf[..4] != b"\x7fELF" || elf[4] != 2 || elf[5] != 1 {
        return Err(ProbeError::InvalidElf(name));
    }
    let machine = u16::from_le_bytes([elf[18], elf[19]]);
    if machine != expected_machine {
        return Err(ProbeError::WrongElfMachine {
            name,
            expected: expected_machine,
            actual: machine,
        });
    }
    Ok(machine)
}

fn read_u16(bytes: &[u8], offset: usize) -> Result<u16, ProbeError> {
    bytes
        .get(offset..offset + 2)
        .and_then(|value| value.try_into().ok())
        .map(u16::from_le_bytes)
        .ok_or(ProbeError::InvalidCentralDirectory)
}

fn read_u32(bytes: &[u8], offset: usize) -> Result<u32, ProbeError> {
    bytes
        .get(offset..offset + 4)
        .and_then(|value| value.try_into().ok())
        .map(u32::from_le_bytes)
        .ok_or(ProbeError::InvalidCentralDirectory)
}

#[cfg(test)]
mod tests {
    use super::{probe_bytes, ProbeError, APK_SIGNING_MAGIC, PAGE_ALIGNMENT};

    fn append_u16(bytes: &mut Vec<u8>, value: u16) {
        bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn append_u32(bytes: &mut Vec<u8>, value: u32) {
        bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn append_entry(bytes: &mut Vec<u8>, name: &str, machine: u16) -> (u32, u32) {
        let local_offset = u32::try_from(bytes.len()).expect("fixture offset");
        append_u32(bytes, 0x0403_4b50);
        append_u16(bytes, 20);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u32(bytes, 0);
        append_u32(bytes, 20);
        append_u32(bytes, 20);
        append_u16(bytes, u16::try_from(name.len()).expect("name"));
        let padding =
            (PAGE_ALIGNMENT - (bytes.len() + 2 + name.len()) % PAGE_ALIGNMENT) % PAGE_ALIGNMENT;
        append_u16(bytes, u16::try_from(padding).expect("padding"));
        bytes.extend_from_slice(name.as_bytes());
        bytes.resize(bytes.len() + padding, 0);
        let mut elf = [0_u8; 20];
        elf[..4].copy_from_slice(b"\x7fELF");
        elf[4] = 2;
        elf[5] = 1;
        elf[18..20].copy_from_slice(&machine.to_le_bytes());
        bytes.extend_from_slice(&elf);
        (local_offset, 20)
    }

    fn append_central(bytes: &mut Vec<u8>, name: &str, local_offset: u32, size: u32) {
        append_u32(bytes, 0x0201_4b50);
        append_u16(bytes, 20);
        append_u16(bytes, 20);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u32(bytes, 0);
        append_u32(bytes, size);
        append_u32(bytes, size);
        append_u16(bytes, u16::try_from(name.len()).expect("name"));
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u16(bytes, 0);
        append_u32(bytes, 0);
        append_u32(bytes, local_offset);
        bytes.extend_from_slice(name.as_bytes());
    }

    fn fixture() -> Vec<u8> {
        let mut bytes = Vec::new();
        let arm_name = "lib/arm64-v8a/libengine_jni.so";
        let x64_name = "lib/x86_64/libengine_jni.so";
        let arm = append_entry(&mut bytes, arm_name, 183);
        let x64 = append_entry(&mut bytes, x64_name, 62);
        bytes.extend_from_slice(&24_u64.to_le_bytes());
        bytes.extend_from_slice(&24_u64.to_le_bytes());
        bytes.extend_from_slice(APK_SIGNING_MAGIC);
        let central_offset = u32::try_from(bytes.len()).expect("central offset");
        append_central(&mut bytes, arm_name, arm.0, arm.1);
        append_central(&mut bytes, x64_name, x64.0, x64.1);
        let central_size = u32::try_from(bytes.len()).expect("central end") - central_offset;
        append_u32(&mut bytes, 0x0605_4b50);
        append_u16(&mut bytes, 0);
        append_u16(&mut bytes, 0);
        append_u16(&mut bytes, 2);
        append_u16(&mut bytes, 2);
        append_u32(&mut bytes, central_size);
        append_u32(&mut bytes, central_offset);
        append_u16(&mut bytes, 0);
        bytes
    }

    #[test]
    fn accepts_signed_aligned_dual_abi_fixture() {
        let report = probe_bytes(&fixture()).expect("valid fixture");
        assert_eq!(report.libraries.len(), 2);
        assert!(report.has_apk_signing_block);
        assert!(report
            .libraries
            .iter()
            .all(|library| library.data_offset % PAGE_ALIGNMENT == 0));
    }

    #[test]
    fn rejects_missing_signature_and_wrong_machine() {
        let mut unsigned = fixture();
        let magic = unsigned
            .windows(APK_SIGNING_MAGIC.len())
            .position(|window| window == APK_SIGNING_MAGIC)
            .expect("magic");
        unsigned[magic] ^= 1;
        assert_eq!(
            probe_bytes(&unsigned),
            Err(ProbeError::ApkSigningBlockMissing)
        );

        let mut wrong_machine = fixture();
        wrong_machine[PAGE_ALIGNMENT + 18..PAGE_ALIGNMENT + 20]
            .copy_from_slice(&62_u16.to_le_bytes());
        assert!(matches!(
            probe_bytes(&wrong_machine),
            Err(ProbeError::WrongElfMachine { .. })
        ));
    }
}
