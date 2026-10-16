# ADR 0099: Vision availability contract

## Status

Accepted incrementally on 2026-10-13.

## Decision

The runtime Binder protocol exposes a bounded `getVisionAvailability()` query
for the fixed OpenCV/ONNX provider families. Because this is a new AIDL method,
the protocol version advances from 25 to 26; clients must reject older Runner
services before calling the method. The query reports compile-profile state in
the first increment and never claims recognition success, private snapshot
installation, or device acceptance.

The first increment reported compile-profile state only. The subsequent
[ADR0104 runtime increment](0104-runtime-fixed-vision-resolution.md) connects
explicit selection/preparation/loading on bounded IO and returns cached
bundled/provider state, distinguishing verified-not-loaded from loaded sessions.
Binder threads still must not scan or hash the private component store. This
does not complete user-facing component management or device acceptance.

## Consequences

- Old protocol-25 Runner packages are rejected instead of receiving an unknown
  transaction for the new method.
- No image bytes, model paths, class names, or arbitrary native paths cross the
  query boundary.
- The protocol upgrade must be reflected in Runner descriptors and packaging
  checks; historical verification records may continue to mention protocol 25.
