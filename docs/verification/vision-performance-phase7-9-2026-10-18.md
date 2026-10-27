# Vision performance phases 7-9 verification (2026-10-18)

## Phase 7: capture and template hot path

- Root client chunked capture now resizes one bounded pixel destination and writes responses
  directly into it; packet receive storage remains reused between responses.
- Streamed capture uses the same pre-sized destination, preserving offset and packet validation.
- OpenCV gray templates are fingerprinted and retained in an 8-entry / 4 MiB LRU. The cache key
  includes geometry, row stride, format and SHA-256 pixel content; provider close clears it.
- Existing continuous-frame generation, age, stride and rotation checks remain in force.

## Phase 8: project/editor hot path

- `ProjectStore.listProjects()` avoids reparsing unchanged manifests and state files while a
  cheap file stamp remains unchanged.
- Flow node/block identity indexes reuse exact source strings only, with a 256-flow bound and a
  1 MiB per-source guard. Changed or large sources take the original parser path.
- Project ordering and external file changes remain observable through the stamp.

## Phase 9: error and cleanup observability

- Runtime vision events include only structural failure codes; capture and frame failures use
  distinct statuses.
- Script logs, diagnostic logs and vision events remain separate channels.
- Existing stop/host-cancel cleanup and stale-result rejection are unchanged.

## Verification

- Rust: `automation-core` 28 passed / 2 ignored, `engine-jni` 40 passed, `root-client` 25
  passed, `runtime-executor` 104 passed / 1 ignored.
- Android Gradle verification completed with
  `:android:project-store:test :android:runtime-service:test :apps:studio-android:compileDebugKotlin`.
  Project-store passed 81 tests; runtime-service passed 149 tests with one pre-existing skipped
  case; Studio Kotlin compilation completed successfully. The build emitted only existing Android
  API deprecation warnings.
