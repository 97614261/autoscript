# Vision management and readiness feedback: 2026-10-14

## Implemented

[ADR0105](../adr/0105-vision-component-management-readiness.md) connects local
management under My -> permissions/runtime environment -> visual components.

- Real signed private import, bounded inventory, exact selection, confirmed clear
  and confirmed deletion use the existing shared verifier/store. Import is not
  selection. Removing a chosen APK leaves its choice missing; no fallback occurs.
- The page uses the existing compact dialog/full-screen toggle, scrollable cards,
  explicit refresh/cancel and fixed status labels. Full builds show bundled
  backends and cannot override them with an external inventory choice. SDKs are
  not Android-installed from this page; there is no downloader.
- Mutation requires a confirmed nonzero signed runtime generation, a connected
  idle/stopped/failed engine and unchanged event revision. Storage checkpoints
  recheck the frozen context, including rapid run/stop round trips. Closing the
  page cancels work, ignores late picker replies and cannot reopen it. A previously
  committed import may remain in inventory after late cancellation, never selected
  or executed implicitly; the UI explains that and requires refresh.
- Runner template and runtime-provider imports share bounded document IO and FD
  cleanup but keep independent stores/validation/purpose. Inventory reads use the
  bounded choice record rather than redundantly hashing the selected APK again.
- Runtime client rejects duplicate/unknown/oversized availability snapshots and
  stale remote generations. UI status parsing accepts only the fixed vocabulary,
  source and loaded fields; exception text or arbitrary remote strings are not
  rendered. Runtime replacement clears displayed readiness.
- Lazy SDK/model initialization failures and linkage errors reached in execution
  mark LOAD_FAILED, retained across asynchronous status refresh. Parameter errors
  do not poison component readiness. ONNX initialization uses a fixed typed error
  without exposing SDK exception text/model paths. Backend creation is still not
  a successful recognition or accuracy acceptance claim.
- Signed component tests now declare exported APKs, configured publisher key and
  signer tool as actual Gradle inputs. Provider-only changes cannot legitimately
  reuse a validation result based merely on unchanged shared test classes.

## Evidence

- Studio status tests distinguish bundled, verified-but-unloaded and initialization
  failures, reject unknown/unbounded data, require confirmed signed generation and
  reject changed generation/revision/active engine states.
- Runtime-client snapshot tests reject duplicate, unknown, incomplete and oversized
  records. Runtime resolver tests prove initialization failure versus parameter
  error behavior and continued blocking after a reported initialization failure.
- Compile/targeted client/service tests passed. First full Verify also passed;
  subsequent mutation-context tightening was recompiled/retested in the final gate.
- Final `build.cmd -Verify`: successful in 2m37s, 1075 tasks. Studio 678 tests
  (1 existing skip), RuntimeService 124 (1 existing skip), RuntimeClient 29,
  vision-components 43; 0 failures/errors. The earlier full pass reran signature
  tests after SDK/SPI artifact changes; final fixture bytes were unchanged and
  correctly cached. Lint: 0 errors, 36 warnings, 16 hints.
- APK/ABI/model/layout checks passed; MuMu 0 update installation succeeded,
  retaining app data. Final full-development APK 178,334,244 bytes; compressed
  payload 172,422,552; layout/signing/padding 5,911,692. This is not the base/on-demand
  APK size or a performance measurement.
- `scripts/check-rust.ps1 -Offline` passed formatting, strict Clippy, workspace
  tests, schemas and contract comparison: 522 JSON documents, 185 schemas,
  159 runtime APIs and 175 blocks. No new runtime API/AIDL method or protocol change.

## Remaining

- Compiled-operation demand metadata and bounded cancellable runtime startup
  preparation/preflight; do not use all default-declared project capabilities as
  demands. Authorizing an SDK must not require loading it for a Rust-only script.
- Function/workbench details and run/test preflight linked to actual readiness;
  current recognition dispatch still guards demand, but this is not early startup
  preparation. Cold load shares the existing five-second recognition deadline.
- Rendered management UI/SAF cancellation/rotation and real provider import/select/
  loading/recognition on devices. JVM tests do not prove PathClassLoader, physical
  PackageManager reads or successful inference. No private script was executed.
- ARM/API24/25, workbench lifecycle/alpha, standalone exports and condition-separated
  performance/memory evidence. Default Studio remains full until device gates pass.
