# ADR 0224: Compact plugin-call editing and ARM release

## Request

Unify and simplify plugin-call insertion/editing and related argument panels,
then publish using the existing workflow with only necessary checks.

## Decisions

- Share the compact named target/typed-parameter form between the insertion
  panel and a dedicated 440dp landscape call editor. Replace horizontally
  scrolling IDs and large text fields with display names and compact rows.
- Keep separate, unsaved parameter drafts per selected target. Changing
  targets and returning restores their values; the original project arguments
  remain untouched until confirmation. A missing original target requires
  explicit selection instead of silently choosing another plugin.
- Preserve typed parsing, optional omission, unknown top-level arguments,
  original call kind and success/failure child branches. Keep dirty-close
  protection, existing transaction/context validation and one undo.
- Remove inapplicable category tabs when editing a fixed flow/argument node,
  repeated headings and long default explanations. Keep all existing actions,
  input/return channels and explicit errors.
- Include ADR 0222 clickable colors and ADR 0223 touch/one-pass insertion
  changes in Studio 0.1.47/code48, ARM64 only. Retain the original development
  signer, full/V4 optimized distribution profile, compact packageRelease and
  bidirectionally signed HMAC HTTP publication.

## Validation And Publication

Reuse the prior 11 color and 25 touch/insertion checks. Run only the seven
call-insertion/draft checks, the ARM release build/package checks and one
public verification. No broad test/Lint rerun, x86 build, device installation,
private scripts, Git commit, or Root/server/key/project-backup changes.

Archive the replaced public APK after a signed preview using the existing
development-stage authorization, leaving one latest public ARM package.
Existing private archives remain; the current HTTP interface cannot delete
them.

## Outcome

- Seven focused JVM checks passed, including two new target-draft cases.
  Release production/test compilation passed. Prior color/touch checks were
  reused; no full test or Lint rerun was performed.
- The original ARM distribution build, compact packageRelease and required
  native/model/signature/alignment/identity checks passed. Package size:
  36,816,358 bytes; ZIP layout/signing/alignment: 30,921 bytes; SHA-256:
  `cbd54c1a0b88f0a6384e8235f5b08b53b170475586aba4336e04fe94bfaa3fca`.
- Studio 0.1.47/code48 was published through the original signed HTTP client.
  After signed preview, the replaced 0.1.46 package was moved into private
  `archive-20261109T144903Z-902f4f6a445348fe`. The six prior archives were
  preserved. Public downloads contain only the latest ARM64 package.
- One public pass verified no-store manifest/signed-revision equality, full
  APK digest/size/MIME, Range, checksum, download page and old URLs returning
  404. The signed revision remained
  `9f8d85f98c0654c4ad0e6d5eb06c99dd6fdfad6abf050bf04e5a9f4ec7c82f30`.

Evidence: `build/plugin-call-v047-*`, the profile test XML and package reports.
No device visual/touch/keyboard acceptance is claimed. No installation,
private scripts, x86 package, Git commit, site/backend source deployment, or
Root/server configuration/key/project-backup changes were made.
