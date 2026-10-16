# ADR 0093: Recording completion return and packaging gates

Date: 2026-10-13. Status: accepted for this implementation batch.

## Decision

- Append an observed recording start method and a one-way bounded completion listener to Binder protocol 25. Keep the existing recording methods and transaction order. Both peers negotiate the exact protocol before use.
- REC stop, cancellation and expiry deliver at most one terminal reply for the current recording. Studio retains one reply, validates recording/service/generation identity, and consumes it once. Explicit finish still returns its own reply; it must not also trigger automatic return. No polling or script execution is introduced.
- The still-mounted recording editor returns to review after completion. Closing or changing the editor invalidates the lease; late results cannot reopen the window. Results remain drafts and require explicit insertion and save. Recording generation is opaque nonzero signed 64-bit, as elsewhere in the runtime.
- MuMu development packaging checks final APK layout and native/template ABI contents before installation, including SkipBuild. A verified APK-only rebuild may repair incremental ZIP holes; old backups are not deleted automatically. Exhausted backup capacity fails final validation rather than installing an invalid candidate.
- Remove the unreachable preview-only recorder route and obsolete placeholder inspector pages. Keep runtime/data compatibility, shared live components and the deferred AI entry intact.
- Package UI presents fixed Runner identity as read-only information, not disabled edit fields. It has only real build and log tabs; it explicitly warns that fixed-package outputs cannot coexist and use a device-private signing key.

## Boundaries

No custom package identity, encryption, account/network functionality or new recording resource format. No changes to the recorded action wire format, Root commands, runtime scripting recording, or project persistence. Device lifecycle acceptance remains separate from local tests/build.
