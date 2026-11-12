# ADR 0228: Template search ROI misses and boundary diagnostics

## Problem

The user reported a fatal findImages error at plugin flow-6, visual row 2,
node-315: Vision(InvalidRoi), ROI=19x19. The old diagnostic describes only
width/height; it does not establish whether the requested rectangle is outside
the owned frame or a saved polygon conflicts with the rectangle. The existing
outside-frame recovery covers color operations, but not template operations.

## Decision

- Extend the Lua executor's miss behavior to first-image, named-template and
  all-template searches. Inspect typed FrameVisionError values, rather than
  parsing diagnostic strings. Recover only a well-formed rectangle outside the
  owned frame after the normal search has actually rejected it.
- Return the original nil/empty result shapes, including count=0 and
  truncated=false for multi-results. Treat a single template larger than its
  rectangle as a miss, matching the named-template paths' existing behavior.
  Do not clamp, rotate, scale or widen the requested region.
- Preserve malformed/reversed/negative coordinates, invalid options, missing
  or duplicate templates, bad result limits, stale/foreign resources,
  malformed or conflicting polygons, cancellation, deadlines and budgets as
  errors with the original execution semantics.
- Warn in the existing script log with requested bounds, actual frame size
  and the task's visual location. Reuse the color warning store as a shared
  bounded vision store, deduplicate warnings and clear them on task cleanup.
  Respect the existing log switch. Successful searches do not acquire another
  frame view or copy pixels.
- Distinguish an otherwise valid polygon outside the requested rectangle with
  a readable reselect/clear instruction. Include full bounds in remaining
  template error diagnostics. Do not silently discard a polygon.

## Validation

Twenty-three distinct focused Rust cases passed: seven new template ROI
cases, twelve existing color/polygon/budget cases and four search-control
diagnostic/deadline/cancellation cases. Tests use the real Lua/executor bridge,
immutable frames and prepared templates. They cover the reported 19x19 size in
a portrait frame, continuation, exact-size and edge matches, partial overflow,
too-small regions, nil/multi-result shapes, readable plugin/row attribution,
bad options/templates/limits, released/foreign leases, polygon conflicts,
logging disabled, warning limits and stop cleanup.

The initial run passed eighteen cases; one new test used a nonexistent
Task.log helper after all match assertions. Corrected that fixture to the
existing Log.info and reran only the seven new cases and four affected
diagnostic cases. Retained the first log. Evidence:
`build/template-roi-checks-first.log`, `build/template-roi-checks-final.log`
and `build/template-roi-control-checks.log`. Targeted diff/new-file whitespace
checks passed.

These host reproductions do not establish the original device's actual frame
dimensions or exact saved polygon. No device connection, private-script run,
APK/version change, publication, Git commit, Root/server/key/project-backup
change is included. Online remains Studio 0.1.48/code49, ARM64 only.
