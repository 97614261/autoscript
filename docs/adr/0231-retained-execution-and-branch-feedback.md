# ADR 0231: Retained execution location and actual branch feedback

## Decision

Keep a presentation-only position through consecutive single-step requests and
checkpoint reads. Executable step tokens and variable snapshots still clear
immediately, so the retained marker cannot authorize another action. Retain a
separate ended/stopped location, distinguish it from the next instruction, and
use the original typed task-failure identity for a red error marker. Do not
substitute an unrelated last sampled position when the failure has no identity.

Let the existing compact location control unfold the required ancestors and
locate the block even when following is already enabled. Preserve manual browsing.
Clear stale evidence after plugin source edits; undoing an edit cannot revive it.
Reject evidence from obsolete projects, sessions, run revisions or sequences,
including queued running reports after a terminal report.

Report actual branch entry from generated Lua for ordinary/gray/multiple-result
image searches, plugin-call success/failure and conditional branches. Empty
branches report their actual outcome too. Frequency-skipped recognition reports
neither branch. The private coroutine marker is strictly validated by the executor
and becomes a bounded structural observation without replacing failure context.
Compiler generation advances from plugin-v16 to plugin-v17.

Runtime protocol 29 appends execution-feedback delivery. The service retains a
bounded per-run reducer: last location, typed failure location, and at most 32
latest branch outcomes. Reports contain identities and phase only, never variables,
resource paths or error text. Use the existing 50ms publication scheduling for
running feedback, then drain structural events and publish immediately for pause
or termination before the state notification. Serialize event draining with
terminal snapshot publication. Avoid an extra feedback parse for ordinary action
and vision events. Mark missing structural observations explicitly; do not claim
a final position from incomplete ended/stopped evidence.

Show friendly results such as found/not found, success/failure, and conditional
branch labels with their plugin and canonical owner row. Owner checkpoints alone
never imply a branch outcome.

## Validation

All 39 focused JVM checks passed across nine suites: execution evidence (5),
execution location (6), step state (6), appearance (2), folding (7), feedback reducer
and codec (5), debug session (3), feedback delivery gate (2), and position gate (3).
The same invocation compiled Debug production and test sources in 5m19s.

Five distinct focused Rust checks passed: three actual generated-Lua branch cases
(recognition hit/miss, plugin return success/failure including empty branches,
and conditional then/else-if/else), plus two executor cases for failure context,
empty outcomes, bounded 400-event observation and invalid slots. Initial fixtures
used the wrong mlua status import and expected task failure instead of the existing
invalid-yield return contract. Corrected those fixtures, reran only failed checks,
and retained the original logs. The final synchronized drain and structural-kind
filter were compiled by the final ARM Release build; no reducer/UI behavior changed
after the focused checks. Scoped diff and new-file whitespace checks passed.

Evidence:

- `build/execution-feedback-v049-checks.log`
- `build/execution-feedback-v049-rust-compiler.log`
- `build/execution-feedback-v049-rust-compiler-final.log`
- `build/execution-feedback-v049-rust-executor.log`
- `build/execution-feedback-v049-rust-executor-final.log`

Do not edit production sources during compilation. No broad tests or full Lint
were rerun. Host checks and generated-Lua execution do not establish device
visual/touch/IME, ARM execution, or private-script acceptance.

## Release

The user authorized implementing all four improvements and publishing them.
Studio 0.1.49/code50 ARM64 includes ADR0228 template ROI handling, ADR0229 image
thumbnails, ADR0230 branch-aware location and this decision. Built through the
original development-signed full/V4 optimized distribution script, compacted with
packageRelease and passed its required packaging checks:

- APK bytes: 36849330
- SHA-256: `8df9398e60a0152c1f2157e55fecf49f979ddfcd48581aa6524379d4904003e5`
- ZIP layout/signing/alignment bytes: 30922
- Public path: `/downloads/zhigou-0.1.49-dev-8df9398e60a0.apk`

Published through the original mutually authenticated HMAC HTTP interface.
After a signed preview, archived the replaced 0.1.48 package under the persistent
latest-only development-download authorization:
`archive-20261111T063348Z-e5ded32a2b5e5d85`. All eight prior private archive records
were preserved unchanged; there are now nine. The public manifest contains one
latest ARM64 package, zero history and zero alternatives. The existing interface
does not delete private archives.

One public verification passed for no-store manifest digest matching the signed
revision, full APK size/hash/MIME, byte range, checksum file, download page HTTP200,
and replaced APK/checksum HTTP404. The final signed revision remained unchanged:
`f7af1477d743be2ca322874120f530879059ad6f0b07d4e02d00e057dc1f8b30`.
Build/publication evidence is retained as `build/execution-feedback-v049-*`.

No new x86 package, device connection/installation, private-script execution,
Git commit, backend/docs-source deployment, Root/server-configuration/key change
or project-backup change occurred. Publication verification does not establish
device acceptance.
