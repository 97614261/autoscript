# Compiled vision requirements verification

Date: 2026-10-14. Scope: ADR0106 metadata and Studio frozen-plan consumption.

## Implemented

- Renderer source-map entries select the actually emitted native visual nodes.
  Requirements use fixed canonical identifiers, are deduplicated, and exclude
  disabled nodes and disabled parent subtrees.
- All emitted plugin definitions are included conservatively. This metadata is
  not runtime branch reachability, permission, component selection, initialization
  or recognition evidence.
- Metadata reuses source-map.json and its generation-bound SHA-256; no extra
  generation transaction or project format rewrite. Generator identity is v14.
- Studio parses only after digest/generation checks and preserves requirements
  through runtime-plan copies. Missing historical metadata remains null/unknown;
  empty current metadata is a known empty set. Malformed versions, types, unknown
  identifiers, extra fields, duplicates and noncanonical order reject.

## Evidence

- `cargo test -p flow-compiler --offline --locked native_vision_tests`: six tests
  passed, including both SDKs under an active/disabled parent, duplicated gray
  calls, all-capability noop, historical absence and digest change on tampering.
- `scripts/check-rust.ps1 -Offline`: final formatting, strict Clippy, complete
  workspace tests, schema and codegen checks passed. Flow compiler has 121 tests.
  Schema gate: 522 JSON documents, 185 schemas, 159 APIs, 175 blocks.
- `build.cmd -Verify`: full build passed in 4m7s, followed by a final incremental
  pass in 6s. Studio reports 681 tests, zero errors/failures, one existing skip.
  Lint: zero errors, 36 warnings, 16 hints. Both passes updated MuMu0, retaining
  existing app data. Final endpoint: 127.0.0.1:16385.
- APK: 178,413,676 bytes; compressed entries 172,568,297 bytes; other layout,
  signing/padding 5,845,379 bytes. Default full profile, arm64-v8a and x86_64,
  no embedded Runner; fixed OCR model content SHA-256 unchanged.
- Repository layout gate passed: 220 local links, zero local-only references,
  22 contained Rust members; archive excluded.

The first Rust full gate caught the golden Lua fixture's outdated generation ID
after the intentional generator bump. Only that ID was updated; the golden
program body was not weakened. A subsequent test-helper-only strict Clippy
ownership warning was fixed, and the final full Rust gate passed.

## Still required

At this gate, demand metadata was not yet passed into startup or packaging.
The [startup follow-up](vision-startup-preparation-2026-10-14.md) now adds
cancellable preparation, synthetic SDK warm-up, source/selection receipts and
Studio startup/isolated-step integration, with four profile host gates.
Packaging/standalone demand propagation, function/workbench preflight and provider
device acceptance remain.
Legacy Lua demand is unknown and must not be inferred from capability defaults.
Default full Studio remains, no downloads or user-private scripts executed.
