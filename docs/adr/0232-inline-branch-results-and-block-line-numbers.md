# ADR 0232: Inline branch results and visible block line numbers

## Decision

Replace the second bottom result line with inline arrows on the actual branch
headings. Each displayed plugin receives all eligible retained branch outcomes,
not just the globally last outcome. Validate each owner and slot against the
complete current tree. Keep one latest result per owner within the existing
32-outcome runtime bound, including empty branches. Earlier image searches retain
their own results when another search executes. A new enter observation clears
only that owner's previous outcome, avoiding stale success/failure during reruns.

Use the existing expansion gutter for both node and branch arrows without moving
labels. Retained outcomes use the branch color and a light background; current
execution, next instruction and failure retain their distinct emphasis. An actual
branch observation can move the cursor from its owner to the corresponding
heading. A parent checkpoint or unrelated result cannot supply that cursor, and
task errors continue to point to the actual failed block.

Following reveals a newly completed branch result even when the next checkpoint
already exists. It unfolds the owning node and necessary ancestors, including
the chosen branch. Subsequent checkpoints resume normal following; explicit
location requests return to the actual current instruction. Manual browsing and
folding still disable following. Do not change stepping, execution order, node
identity, insertion anchors or runtime control tokens.

Number actual blocks in both the main visual tree and multiselect editor. Use
the complete plugin source order, matching diagnostic/execution row numbers;
branch headings do not consume a source row. Folding and focused subtree views
preserve numbers. Duplicate identities do not receive misleading numbers. A
small monospace gutter scales with the number of digits and stays independent
of indentation and expansion arrows.

The bottom position strip is one compact line: state, canonical block row and
title, plus a location action. Remove the separate latest-result line and the
repeated visible ancestor path. Retain full path semantics for accessibility and
an explicit incomplete-record notice. Do not add result panels or new controls.

## Validation

One concentrated invocation passed 31 focused JVM checks:

- Six new branch-result presentation, follow, cursor and numbering cases.
- Six existing execution-location cases.
- Five execution-evidence and step-retention cases.
- Seven branch-folding cases.
- Seven runtime-feedback cases, including two new consecutive-search/rerun cases.

Studio Debug production/test compilation and runtime service tests passed in the
same 4m35s invocation. Scoped diff and added-file whitespace checks passed. The
initial shell invocation split an unquoted Gradle property and failed before
compilation or test execution. Retained that log, corrected quoting, and ran the
focused checks once; no production sources changed during compilation.
Evidence: `build/inline-branch-location-checks.log`,
`build/inline-branch-location-checks-final.log`, and the corresponding XML under
`build/profiles/x86_64`.

The dependency graph rebuilt the internal Runner template; no Studio delivery
APK was produced. No broad tests/full Lint, device connection/installation,
private-script execution, version bump, publication, Git commit, Root or
server/key/project-backup change occurred. Host checks do not establish device
visual/touch/IME or ARM acceptance. Online remains 0.1.49/code50 ARM64.

## Authorized Release

The user subsequently requested packaging and publication. Studio 0.1.50/code51
ARM64 includes this decision, replacing the preceding local-only release limit.
Reused the 31 focused checks and built with the original development-signed
full/V4 optimized distribution workflow, compact packageRelease, and required
packaging checks. No broad tests or full Lint were rerun.

- APK bytes: 36852310 (35.15 MiB).
- SHA-256: `8b0bfa1bb14ff12f9f245592bb1a8a27a0fdcaa16ab2625930148b8f8feb5461`.
- ZIP layout/signing/alignment bytes: 30919.
- Public path: `/downloads/zhigou-0.1.50-dev-8b0bfa1bb14f.apk`.

Published through the original mutually authenticated HMAC HTTP publisher.
Signed preview confirmed the replaced 0.1.49 identity, which was privately
archived under the persistent latest-only development-download authorization:
`archive-20261111T075035Z-7cdfc43e39d5a980`. All nine prior private archive records
are unchanged; there are now ten. Public downloads contain one latest ARM64
package, zero history and zero alternatives. No private archives were deleted.

One public verification passed: no-store manifest digest matching the signed
revision, complete APK download size/hash/MIME, byte range, checksum file,
download page HTTP200, and replaced APK/checksum HTTP404. The final signed
revision remained unchanged throughout verification:
`abf79df8bb4ba63523b8a6454561c1055dcc3fb3f0690bbbd4aa71907e368411`.
Evidence: `build/inline-branch-v050-*`.

No x86 delivery package, device connection/installation, private-script execution,
Git commit, backend/docs-source deployment, Root/server-configuration/key change
or project-backup change occurred. Release and public checks do not establish
device visual/touch/IME or ARM execution acceptance.
