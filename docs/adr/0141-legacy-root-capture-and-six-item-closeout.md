# ADR 0141: Legacy Root capture and six-item closeout

Status: accepted, 2026-10-22; user requested execution of the six-item closeout plan.

## Decision

Extend the existing ADR0048 fixed Root capture bridge on Android API24-28 with the exact static `SurfaceControl.screenshot(int, int): Bitmap` method. Zero dimensions request the current display at native scale. No secure/protected-layer option, arbitrary reflection target, file transport, new protocol, permission bypass, or non-Root backend is introduced. Newer Android keeps its existing HardwareBuffer path. Missing/failed APIs retain the existing once-per-connection bounded screencap fallback and truthful backend metrics.

The fixed legacy method signature and return type are checked before use. Each request acquires a new bitmap, enforces the existing dimension/64MiB limit, retains getPixels/BGRA semantics, and recycles every owned bitmap. Legacy pixels use natural display orientation: the fixed DisplayManagerGlobal default-display geometry is checked before/after capture and pixels are quarter-turned without scaling to current logical orientation. Dimension/rotation changes fail closed through the existing fallback, not guessed coordinates. Existing cancellation watchdogs, authenticated streaming and half-frame failure handling remain unchanged. Successful compilation is not evidence of OEM compatibility, orientation correctness or performance improvement.

QA reports fresh-capture backend counts and existing bounded acquisition/transport/publish/bridge timing fields for the same five performance conditions, excluding the first call and ten warmups. Overlapping stages and percentile columns must not be added. Old-frame cache hits cannot pass the fresh-capture benchmark. No private screenshot is retained by default.

The other closeout items evaluate fixed V4/V5/glyph accuracy without replacing defaults, exercise public ARM capability cases and bounded long runs, record unavailable native/API24 targets honestly, and freeze local deliverables without publishing online. Production IME selection requires separate explicit consent and restoration; QA IME permission does not imply it.

## Verification

Host tests validate exact static-method selection and resource ownership. Device tests must verify current-frame pixels, portrait/landscape geometry, actual backend selection, same-condition full-loop timings, stop/recovery, and scratch/resource cleanup. Failures and fallback results remain in the evidence ledger. See [execution record](../verification/studio-closeout-six-2026-10-22.md).
