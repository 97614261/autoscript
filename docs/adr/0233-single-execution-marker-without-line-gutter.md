# ADR 0233: One execution marker without visible line numbers

## Decision

The user requested removing line numbers and showing only one triangle with a
highlighted execution row. Remove the number gutters from both visual editors,
their width/allocation helpers, and the visible row number in the compact bottom
strip. Keep diagnostic row numbers and accessible execution context unchanged.

Use one validated execution target for both node rows and branch headings. Only
the row whose key matches that target receives an execution triangle and execution
background. Earlier results and ancestor branch headings receive neither. Actual
success/failure feedback still selects the corresponding heading, including an
empty branch. Subsequent execution moves the single marker; errors still use the
actual failed block identity. Invalid or ambiguous targets receive no marker.

Retain the existing result-follow tracker, manual browsing, explicit location,
source/session eligibility and original runtime execution. The bounded outcomes
remain internal evidence rather than persistent visual markers. A paused result
heading is labeled recently executed, not next instruction. The bottom strip
shows the same target's block title and optional branch label, with no visible
line number. Remove the extra execution flash and execution side stripe; preserve
ordinary editing selection and expansion controls in the original gutter.

## Validation

One focused invocation passed all 22 JVM checks: seven result/marker cases,
six location cases, seven folding cases and two execution-appearance cases.
The updated marker cases cover consecutive searches, moving from a result to a
child, exactly one matching display key, and rejection of missing, unsupported
or duplicate identities. Studio Debug production and test compilation passed in
the same 2m28s invocation. Scoped diff and added-file whitespace checks passed.
Evidence: `build/single-execution-marker-checks.log` and the corresponding XML in
`build/profiles/x86_64/apps/studio-android/test-results/testDebugUnitTest`.

No broad tests/full Lint, Studio delivery APK, version bump, device connection or
installation, private-script execution, publication, Git commit or Root/server/
key/project-backup change occurred. Host checks do not establish device visual,
touch, IME or ARM execution acceptance. Online remains 0.1.50/code51 ARM64.

## Authorized Release

The user subsequently requested packaging and publication. Studio 0.1.51/code52
ARM64 includes this decision, superseding the preceding local-only release limit.
Reused the 22 focused checks and built with the original development-signed
full/V4 optimized distribution workflow, compact packageRelease, and required
packaging checks. No broad tests or full Lint were rerun.

- APK bytes: 36850430 (35.14 MiB).
- SHA-256: `474c50ea84a0ad5c56c8d69349a62608b1bedd6140e34f9da91848840e0e22f1`.
- ZIP layout/signing/alignment bytes: 30921.
- Public path: `/downloads/zhigou-0.1.51-dev-474c50ea84a0.apk`.

Published through the original mutually authenticated HMAC HTTP publisher.
After a signed preview, archived the replaced 0.1.50 under the persistent
latest-only development-download authorization:
`archive-20261111T082108Z-0d8d9aa1926e6ef9`. All ten prior private archive records
are unchanged; there are now eleven. Public downloads contain one latest ARM64
package, zero history and zero alternatives. No private archives were deleted.

One public verification passed for no-store manifest digest matching the signed
revision, complete APK size/hash/MIME, byte range, checksum file, download page
HTTP200, and replaced APK/checksum HTTP404. The final signed revision remained
unchanged throughout verification:
`e24c153502aa486f7c02ef7f95e988d2fb2aac3b2637e7d650a0f08c4f134bf7`.
Evidence: `build/single-marker-v051-*`.

No x86 delivery package, device connection/installation, private-script execution,
Git commit, backend/docs-source deployment or Root/server/key/project-backup
change occurred. Release and public checks do not establish device acceptance.
