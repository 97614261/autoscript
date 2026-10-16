# ADR0106: Digest-bound compiled vision requirements

Date: 2026-10-14. Status: accepted, staged implementation.

## Decision

Vision SDK demands come from emitted visual operations, never the project's
default capability declarations or textual searching of generated Lua. Add an
optional versioned `visionRequirements` object to the existing source map. The
generation record's sourceMapDigest binds these bytes to the frozen generation.
Only fixed component identifiers `opencv` and `onnx` are permitted; ordering is
canonical and duplicate identifiers are invalid. Bump the generator identity.

Collect only nodes present in the renderer's source-map entries: disabled nodes
and disabled descendants cannot introduce requirements. Include all emitted
plugin definitions conservatively, including UI/callable plugins; this is not a
claim of branch reachability. Isolated node-test compilation naturally uses its
own emitted artifact. Do not create a second frame registry or execution API.

Studio consumes requirements only after verifying source-map digest/generation.
Absent metadata on historical artifacts means unknown, not an empty demand list;
malformed or unknown-version metadata fails closed. Existing project declarations,
resources and authorization checks remain unchanged. No user code is executed to
infer demands and no source, variables or resource paths enter ordinary logs.

## Delivery gates

This increment provides compiler metadata and plan consumption. Cancellable SDK
preparation, service/client preflight, exact frozen-run binding, function/test
availability presentation and device acceptance remain subsequent requirements.
No readiness/initialization/recognition success is claimed from metadata alone;
default full Studio remains unchanged and no downloader is implemented.
