# ADR 0104: Demand-bound fixed vision runtime resolution

Date: 2026-10-14. Status: accepted incremental runtime wiring of ADR0096/0099.

## Decision

Keep the default full build's bundled backends. A build lacking a particular
bundled SDK may use only an explicitly selected fixed private provider of that
kind. Import is not selection; an absent/corrupt exact selection never falls back
to another artifact or algorithm. Selection is bounded, atomically published
under the component registry lock and independent of project capability data.

Service script and preview adapters share one bounded resolver, but retain their
own request cancellation/lifecycle gates. Verification, preparation and class
loading run on a single bounded IO executor, never a Binder thread. Request
cancellation interrupts loading at checkpoints; late unpublished sessions close.
SDK execution uses a nonblocking session gate. Session close cancels active work
and defers SDK release until that work finishes; native files remain process-pinned
as ADR0100 requires. Recreated services reuse exact pinned code without allocating
another prepared snapshot. Changing a loaded artifact requires process restart.

Availability reads return a bounded cached two-kind snapshot and request a
single-flight asynchronous refresh; no fixed polling or private-store hashing
occurs on Binder. Distinguish CHECKING, INSTALLED (verified, not loaded), AVAILABLE
(bundled or loaded), MISSING, INCOMPATIBLE, CORRUPT, DEVICE_UNSUPPORTED, LOAD_FAILED,
RESTART_REQUIRED and BUSY. Safe source/loaded metadata does not claim a successful
recognition. Method shape/protocol26 remains unchanged; clients already parse
bounded status strings rather than executing provider identities.

User-facing selection/import and run/function preflight are subsequent increments.
This runtime path alone does not meet component/device acceptance or authorize
switching the default Studio build to base. No downloader or arbitrary class,
model, native path, project capability mutation or non-Root backend is added.
