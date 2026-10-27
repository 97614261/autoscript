# Foreground Studio update verification - 2026-10-21

Decision: [ADR0137](../adr/0137-foreground-studio-updates.md).

## Scope

Studio 0.1.2 / versionCode 3 checks the shared website manifest while foreground,
offers a cancellable in-App download after confirmation, verifies size/SHA-256,
APK v2 signer against the installed App and actual package/version/minSdk/ABI,
then requests Android's normal installation confirmation. Unknown-source access
is requested only through an explicit action. No Root installation, uninstall,
project migration, component/model download or remote deployment is included.

Current plus architecture alternatives are eligible; history and same-code
development builds are not upgrades. Earlier Apps need one manual bootstrap
installation. Automatic checks can be disabled and are throttled to six hours;
background cancellation does not count as a completed check.

## Host verification

- Website Node tests: 36 passed.
- Deployment helper tests: 11 passed.
- Update/rollback helper tests: 13 passed, 7 Windows-only skips (20 total).
- Rust package-probe: 5 passed, including strict explicit x86-only policy.
- Rust package-probe formatting and Clippy with `-D warnings`: passed.
- Update-focused Studio tests: 18 passed in the initial complete Debug run.
- Final `build.cmd -Verify`: passed; 1,313 tests total, 1,311 passed and two
  skipped, zero failures/errors. Debug lint: zero errors, 38 warnings, 11 hints.
  Log: `build/app-auto-update-debug-final.log` (ignored local output).
- Final Debug 0.1.2 / 3 was installed over MuMu 0 successfully, retaining data.
  This used the existing build script, not the new in-App updater.

Sandbox-only filesystem denials on temporary symlinks/atomic renames were rerun
outside the sandbox. They are not recorded as passing Linux activation tests.

## Issues found during candidate verification

The first x86_64 Release verification failed: three Studio tests and 23 shared
vision-component tests. Older readers required arm64 even for a complete signed
x86-only artifact; snapshot fixtures and package-probe assumed phone or dual-ABI
content. Readers now accept a nonempty unique subset of the two existing ABIs,
with exact native-library/ELF agreement unchanged. Tests select a real fixture
ABI; the CLI has a separate strict x86-only policy without weakening the default
dual-ABI gate. Empty, unknown, partial and mismatched ABIs remain rejected.

The first earlier Debug run also reported a stale lint analysis while sources
were changing. Its later frozen recheck passed; final evidence is listed below,
not inferred from the failed run.

## Final candidate and website artifacts

Both optimized candidates passed full Release host verification, lint, R8,
APK signature/alignment, native-library/model and 2 MiB layout-budget gates.
Studio Release: 892 tests, 891 passed and one skipped per architecture. Shared
vision-components: 47 passed per architecture; the updated x86 ELF fixture also
passed a separate final run. No installation of either Release candidate was run.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Dual-ABI Debug | 96,193,811 | `917564af859a9067a373c849bb4b3ea1238b4b62e92072d2c6e0453e9cfd5167` |
| ARM64 optimized candidate | 36,220,220 | `d9788c9a0b18ac91f3c8770381fbfa057b5423398a4710d3e92e6fbac995d44d` |
| x86_64 optimized candidate | 51,482,944 | `53685f689f51d1e936da55db3effe3c09fd6fa832976fbf88f88b06cb85cec9b` |
| Website delivery archive | 264,978,029 | `f65e376655c40f959de1f66f34ac7aec8efb9571002852ba38b54a03e91ad8da` |

All are local ignored build outputs. Both APKs use the existing development
certificate, not a production signing key. Default full vision, V4/V5 model
hashes and Root/runtime boundaries are unchanged.

Website build: 200 API pages, 219 HTML pages and 47,866 local links/assets checked.
Current ARM64 and its x86_64 alternative both publish numeric version code 3;
all three earlier download files remain available in website history. No download
history was deleted or archived in this batch.

The independent delivery directory is
`build/docs-site-deploy/20261021T151057Z-d9788c9a0b18/`, containing the static
archive, exact checksums, deployment/update tools, `delivery.json` and `DEPLOY.md`.
The existing `/home/admin/zhigou-deploy` upload base remains valid; use a separate
batch subdirectory and follow this delivery's instructions. Remote activation
and public-network checks have not been run.

All six files listed by the delivery's `CHECKSUMS.sha256` were independently
hashed and matched. The server deployment reader validated the built release
and all five APK entries; its archive inspector accepted all 716 archive members.
This validates local inputs, not Nginx activation, remote downloads or installation.

## Acceptance boundaries

Host checks and MuMu installation through the existing build script do not prove
the updater's actual Android permission/installer interaction. No end-to-end
in-App upgrade, ARM phone installation/capture or API24 installation was run in
this batch. Development-signed Release candidates remain device-unverified.
Server publication and public HTTP/HTTPS acceptance are user-operated and have
not been performed. Keystore credentials and the real origin are not included.
