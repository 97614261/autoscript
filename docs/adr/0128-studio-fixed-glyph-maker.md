# ADR 0128 Studio fixed glyph maker

Date: 2026-10-19
Status: accepted

## Decision

Add a Studio-only authoring tool for user-sampled fixed-font digits, letters and
symbols. This is not general OCR, model training or an ONNX dependency. Runtime
recognition remains the existing Rust glyph-ocr engine and ASGLYPH v1 contract.

Use existing project images, bounded document image selection, or the screenshot
processing workbench as the image source. Keep original pixels and half-open
regions. Sampling uses the same per-RGB-channel tolerance as the runtime. Batch
sampling follows occupied row/column runs, preserving each line's vertical
extent for decimal points and other short symbols. Touching characters must be
sampled separately; no inferred character labels or font synthesis is performed.

The Studio authoring codec is an independently tested bounded ASGLYPH v1 writer
and reader, not a second runtime recognizer. Authoring is limited to 256 samples
and 2 MiB packed bitmap data; images remain within 4,194,304 pixels and 4096 per
edge. Existing dictionaries beyond authoring limits are rejected without changes.
Reject incomplete labels, identical masks with conflicting labels, empty masks,
bad reserved fields, noncanonical padding and trailing bytes. Same-character
variants are allowed. Test unsaved dictionaries only through the existing
cancellable runtime recognition preview using request-owned temporary files.

Save an explicit new dictionary resource through ProjectStore.importResource,
checking the frozen project resource baseline. Never overwrite the opened
dictionary, create variables or run scripts. Existing backup, resource selection
and packaging behavior applies without a format or capability change. Closing
with unsaved samples requires confirmation. Cancellation revokes UI publication;
temporary files and owned bitmaps are released when background work finishes.

## Workflow refinement

Expose authoring directly in the image home and screenshot toolbar. Screenshot
and workbench entry reuse the borrowed original bitmap, selected region and
picked color. Keep append on the sampling page; offer an explicit append-and-test
shortcut and fixed bottom name, test and save actions. Duplicate-only appends
do not create an edit or replace the previous undo step. Controlled text inputs
must display quick fills even when still focused.

An optional save-and-configure callback carries the newly declared dictionary
and validated recognition settings, never scripts, output variables or implicit
actions. Only the identical borrowed source may hand its region back to its
owning image window. Standalone authoring does not import sample-image geometry
into screen recognition. Existing configuration, actual preview proof, variable
declarations, insertion-position selection and full project validation still
apply. Reuse an unchanged prior save only when the exact path remains declared;
explicit save-new continues to create a separate resource. Saving does not erase
the last preview message; sample/source/settings edits invalidate it.

Authoring display may enlarge the original or selected region, up to the existing
16x viewport limit, without resampling source pixels. View fit and ROI reset are
separate operations. Thumbnail geometry uses float scaling so a large original
glyph cannot collapse through integer division. Sample selection uses a bounded
adaptive lazy grid and a separate pixel editor, with explicit previous/next selection and unsaved-label
guards. Batch labels show exact code-point counts and paired previews; presets
remain user actions, never inferred labels. Replacing a labelled pending batch
requires confirmation, and source changes wait for append or explicit discard.

Sample undo/redo retains at most 13 states including current, and 8 MiB of packed
masks counted across undo/current/redo. Mask arrays are immutable by convention;
edits replace arrays. Duplicate-only edits do not discard redo. Version identities
are monotonic and restored on undo/redo, so returning to the saved version does
not manufacture dirty state or duplicate resource saves. Opening a dictionary
copy resets history with a fresh identity. No authoring state enters project
backup or alters ASGLYPH v1.

## Compact authoring workbench

Use compact source, extraction, annotation, sample and testing sections, with
stable bottom save actions. Search filters original sample indices without
implicitly changing selection. Correct a single pending label only after a
complete sequence exists, replacing one Unicode code point without shifting
other labels; incomplete corrections cannot be appended.

The pixel editor provides automatic point correction, ink, erase, magnification
and panning. Each pointer stroke owns one bounded packed-mask copy and commits
only on pointer-up as a single history operation. Interpolate motion in original
pixel coordinates. Cancelled or multi-pointer strokes do not commit; empty masks
and conflicting labels reject the entire stroke. View enlargement, grid and
panning never resample saved masks. Optional expected text is a UI-only exact
comparison against the current successful Rust preview, not recognition input
or a persisted dictionary parameter.

## Multi-color foreground (2026-10-20)

Authoring, preview and script recognition share a union of at most 16 opaque
RGB colors with independent scalar channel tolerances. The first color keeps
the existing foregroundRgb/tolerance parameters; an optional trailing
additionalColors string carries up to 15 `RRGGBB:tolerance` entries separated by
semicolons. Empty means the legacy single-color behavior. Reject malformed,
duplicate, oversized or partially edited palettes rather than dropping entries.
The same additive option applies to glyph details, reversible block source and
saved image recipes. No extra screenshot, frame registry or hidden variable is
introduced. Color comparisons are capped at 64 million and retain cancellation.
Generated multi-color calls check `Ocr.glyphPaletteVersion == 1` before resource
loading, so older engines cannot silently ignore the new trailing argument.

ASGLYPH v1 continues to contain only explicit glyph masks and labels, not hidden
filtering metadata. Save-and-recognize carries the palette as recognition
settings; saving a dictionary alone does not change its binary format. Color
changes invalidate preview proofs without rewriting already sampled glyphs.

## Validation

Require codec malformed-input tests, an exact fixture checked by both Kotlin and
Rust, foreground/region/batch-label tests, Studio tests, lint and Debug build.
Device UI and Rust recognition preview acceptance are recorded separately from
compilation. No new external dependency or production OCR model is introduced.
