# ADR0108: Frozen Runner vision demands

Date: 2026-10-14. Status: accepted, staged implementation.

## Decision

Reuse ADR0106's versioned source-map requirements and existing release format3.
The release's source-map SHA-256 and releaseId already bind this metadata; do not
duplicate it in the release manifest or invent a second dependency truth.
Runner template componentVersion advances to2 (protocol remains27), because a
protocol27/v1 template can still contain the old source-map reader. New packaging
must reject those templates rather than successfully sign an unreadable release.
The version is a fixed shared RuntimeProtocol constant consumed by descriptor
generation, validators, fixtures and matrix verification. Imported v1 artifacts
and existing installations are preserved, not rewritten or auto-upgraded.
Rust release verification and the independent Runner accept only the optional
fixed metadata shape, canonical known components and version1. Historical absence
stays unknown, not empty; invalid metadata fails closed.

For current visual projects the packaging page compiles the formal saved project
and inspects its digest-bound requirements before enabling template selection.
This is a preview, not final authority: device packaging snapshots the exact saved
inputs and derives the template family again from the compiled immutable release.
A changed demand fails explicitly and never silently chooses another template.
Old Lua demand is unknown and retains conservative declaration-based packaging.
Project capabilities remain unchanged and remain the sole permission truth.

Runner reads requirements only from its verified source map and passes them into
the protocol27 startup preparation with the final configured Lua bytes. New
visual releases can therefore use base/CV/ONNX/full families independent of unused
default declarations. Old unknown releases retain lazy execution rejection.
Explicit missing, corrupt or incompatible template choices never fall back.

Default Verify must include independent Runner unit tests so future source-map
reader drift is caught. Actual export/device acceptance and the fixed-provider
SDK accuracy gates still remain; no downloader is added and default full Studio
is not changed.
