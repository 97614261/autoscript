# ADR 0129 Project color filter workflow

Date: 2026-10-19
Status: accepted

## Decision

Add native bounded color filtering to the existing immutable frame transform and
Studio processing pipeline. Do not rename grayscale or thresholding as color
filtering, change existing matching algorithms, add another frame registry or
infer exact equivalence to the legacy native implementation.

Version 1 filter rules contain RGB, three channel tolerances, include/exclude
and absolute/relative mode. Absolute mode compares channel distances. Relative
mode compares pairwise channel differences, with each permitted difference equal
to the sum of the corresponding channel tolerances; a common RGB brightness
offset therefore does not change membership. Global absolute/relative overrides
rule modes; custom uses each rule's mode. Includes form a union, exclusions win;
with no includes all nontransparent pixels are initially included. Inversion is
applied after this decision and never makes an originally transparent pixel
visible. Binary output preserves source alpha and writes white/black; keep output
preserves selected RGBA and clears other pixels. Maximum 32 rules, 8192 serialized
bytes, 4194304 pixels and 64 million pixel-rule comparisons per operation; all
work observes cancellation and the existing transform deadline.

Extend Screen.transform with mode=filter and a strict optional filter object.
The visual transform stores its canonical JSON as the optional filter string;
the compiler validates it with the same Rust rule type. Old modes reject filter
data rather than silently ignoring it. Kotlin authoring previews use golden
vectors shared with Rust. Preview, immutable processing history and generated
processing blocks carry the exact same settings. Existing real matching/OCR
preview proofs, variable declarations, insertion context checks and resource
ownership remain in force.

Project-owned named presets live in .studio/image-filters.json as portable
authoring metadata, not runtime resources: at most 32 names and 128 KiB, strict
version and fields, compare-before-write atomic updates, explicit confirmed
delete, validated backup/restore. Applying a preset copies its rules into the
operation; runtime never reads mutable preset files. Invalid preset files remain
untouched. No generated or private runtime state is added to backups.

The screenshot/library workbench provides color picking, compact rule editing,
original/filtered comparison, explicit apply, save-new-image, real matching/OCR
tests, dictionary authoring and insertion of the complete processing pipeline.
Transparent filtered templates can use ordinary matching's existing transparent
pixel exclusion. For binary matching, both the saved template and runtime frame
must use the intended filter; the UI states that requirement explicitly rather
than claiming an unprocessed template was filtered automatically. Dictionary
sampling from a processed frame does not change the original source or imply
that raw-screen glyph recognition applies the filter automatically.

## Verification

Require strict malformed-input and cross-language golden tests, real compiler
and executor tests, preset conflict/backup/restore tests, Studio tests, lint and
Debug builds. Synthetic MuMu workflows and real Rust preview acceptance are
recorded separately from compilation and do not establish arbitrary-font or
legacy native algorithm equivalence.
