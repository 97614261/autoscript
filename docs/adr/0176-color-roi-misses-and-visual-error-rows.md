# ADR 0176: Outside-Frame Color Searches And Visual Error Rows

Date: 2026-10-28

## Decision

Treat a well-formed, nonempty color-search ROI outside the owned input frame as
a normal miss in the Lua executor. This covers color count, first/all color
search and multi-point color search, including optional alternative colors.
Return zero, nil or an empty list as appropriate and allow the next block to
execute. Do not clamp, scale or rotate coordinates to search another region.

Only recover an actual InvalidRoi result after ordinary argument and resource
validation, then recheck the requested bounds against the owned frame metadata.
Negative, reversed or empty bounds, invalid colors/limits, expired/foreign
handles, cancellation and other failures retain their original handling. The
pixel library remains strict. Successful operations incur no recovery lookup.

Warn through the existing bounded script log when logging is enabled. Include
requested bounds, actual frame size and the task's own visual position. Deduplicate
the same operation, position, region and size per task, with at most 128 warning
keys per executor; clear keys when the task terminates. No repeated popups.

New generated checkpoints optionally carry the plugin display name, canonical
visual row and catalog title. Rows count every node, including disabled nodes
and descendants, and do not depend on folding or generated Lua line numbers.
Titles come from the existing block catalog at build time. Names are safely
encoded as Lua strings. Runtime diagnostic text includes these fields plus the
existing plugin/node identities; structural telemetry still contains identities
only. Do not attach another task's position to a failure without its own context.

Retain the original checkpoint payload when the runtime does not advertise the
additional details, and accept old payloads without inventing a visual row.
Bump the compiler generation version so cached generated code is refreshed by
the existing verification path. Do not migrate project nodes or change runtimeApi.

## Validation

Use the real Lua/host bridge with portrait frame dimensions and the reported
landscape ROI, both color paths, all four result shapes, partially outside
regions, valid boundary matches, argument/frame errors, warning deduplication,
bounded state, log switches and stop cleanup. Test compiler-to-executor errors
inside a nested plugin after disabled/comment rows, using a Unicode plugin name.
Refresh the generated compiler fixture through actual compiler output and check
the existing compiler and runtime context cases. These are host checks, not
device recognition or interface acceptance. No APK, installation or publication
is part of this change.

## Delivery

The user subsequently requested online packaging. Release Studio 0.1.29,
versionCode 30, through the existing development-signed full/V4 distribution
build and authenticated HTTP publication flow for ARM64 and x86_64. Reuse the
completed host tests and perform package/public download checks only. Both
packages are published and their full public bytes match local hashes; the
public manifest matches the signed revision. The oldest 0.1.25 architecture
pair is recoverably archived under the user's standing authorization. No
device installation or device recognition acceptance is claimed.
