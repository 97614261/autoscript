# ADR 0105: Local fixed vision component management and truthful readiness

Date: 2026-10-14. Status: accepted increment of ADR0094/0104.

## Decision

Provide real local provider management under My / permissions and environment.
Do not mix it with standalone Runner packaging templates or add downloads.
Reuse the shared verifier/store, bounded document import and current runtime's
cached two-kind status. Import, explicit selection, backend-session creation and
successful recognition are different facts. Bundled kinds cannot be externally
overridden through this page; full remains the default build. Selection/deletion
requires an idle connected runtime and rechecks that condition at storage
checkpoints. Delete requires explicit confirmation and never falls back.

SDK initialization or link failures reached through execution mark the existing
backend session LOAD_FAILED. A verified/instantiated provider is not evidence of
successful lazy SDK/model initialization; parameter or not-found results do not
poison availability. A failed active session stays blocked until service/process
recreation as applicable under ADR0100.

Signed fixture bytes, publisher key and signer tool are explicit test inputs so
provider-only changes invalidate cached validation tests. User-facing messages
use fixed status labels, not exception text, paths or model details.

This increment does not add runtime warm-up RPCs or claim project/function
preflight complete. Demand reports must derive actual compiled operations, not
all default-declared capabilities; merely authorizing an SDK must not load it or
block Rust-only projects. Startup preparation and actual device gates remain.
