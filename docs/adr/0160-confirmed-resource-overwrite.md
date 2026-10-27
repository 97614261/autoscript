# ADR 0160: Explicit same-name resource replacement

Status: accepted, 2026-10-25.

The user requests same-name overwrite for image and other named saves. Resource
imports and authoring offer cancel, numbered copy or confirmed replacement.
Existing non-interactive callers retain numbered-copy behavior. Confirmation
binds the canonical declared path, existing content digest and incoming content
digest; stale or changed inputs are refused, not silently overwritten. Validate
type/size first, stage and sync bytes, retain a rollback copy until commit, and
invalidate generated runtime artifacts. Preserve declaration/path and script
references. Unknown files are never replacement targets. Replacement does not
count as another resource or double-count the old audio in the quota.

An ephemeral snapshot resource revision refreshes image thumbnails and template
metadata after replacement, without changing the project schema. Frozen running
scripts keep their existing resources; new execution freezes the updated file.
Image/glyph previews and sample drafts remain owned until successful saving;
cancelling the conflict prompt does not discard them. Image/filter recipe saves
also offer explicit same-name replacement using their existing expected-list
conflict checks. Project/plugin identity renaming and backup recovery retain
their dedicated semantics. No online publication or device restart is implied.

## Implementation and publication authorization

Implemented the shared save conflict prompt, resource-store replacement proof
and rollback, screenshot/processed-image/glyph/import callers, and same-name
image/filter recipe choices. Cancellation retains form and preview ownership;
resource revisions refresh thumbnails/template bounds and invalidate recognition
proofs. The focused 37 host tests passed, as did production and Android-test
Kotlin compilation, Debug lint (zero errors) and the local ARM64 Debug build.
These checks do not imply a new device acceptance result.

The user then requested online publication. Studio was incremented to
0.1.10 / versionCode 11 and isolated optimized full-profile development-signed
Release packages were built for ARM64 and x86_64. Both passed the existing
native/model inventory, compact ZIP, alignment, signature and actual package
identity checks; the default V4 model and optional V5 remain unchanged.
The prior focused tests were reused, with no new installation or broad campaign.

- ARM64: 36,424,834 bytes; SHA-256
  `e75aec866ff480280262e0b0825fb90c7f23cd7dfd36970f583b10bb361cb3ee`.
- x86_64: 51,678,394 bytes; SHA-256
  `344da0cea77aae3a23a152871445470d0619bde574fadb64854b8444444832e2`.

Under the user's subsequent standing development-history authorization,
ADR0158's signed interface recoverably archived only the exact 0.1.6 pair,
receipt `archive-20261025T123018Z-4a2ca8d3cf34af74` (complete, 88,046,452 bytes).
The remaining 0.1.7/0.1.8/0.1.9 packages and all existing archives were retained.
Both new packages then returned signed publication success through the existing
HTTP client, ARM64 primary and x86_64 alternative. Release notes include the
pending image deletion/usage lookup and screenshot dim-layer fixes from ADR0159;
old dark templates must be recaptured, not automatically altered.

Public delivery verification completed: the raw no-store JSON manifest matched
signed revision
`55c32ee73fb621f19445625365e7dccc329687573603501a57ff9ce373374584`.
Both full public APK streams matched the exact sizes/digests above and APK MIME;
the download page returned HTTP 200 HTML. A final signed read confirmed the
revision stayed unchanged, with eight public packages totaling 352,267,560
bytes and exactly the retained 0.1.7/0.1.8/0.1.9 history pairs. The signed archive
receipt was complete. No server configuration or device was changed.
