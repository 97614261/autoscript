# ADR 0138: Chinese names and bounded image selection

Status: accepted, 2026-10-21.

## Decision

- User-defined variable, plugin parameter and jump-label names accept ASCII letters,
  underscore and Chinese ideographs (U+3400–U+4DBF, U+4E00–U+9FFF), followed by the
  same characters or ASCII digits, up to 64 characters. Names are exact keys: no
  transliteration, normalization or hidden alias variables. Generated project,
  plugin, node, page/control IDs and API/protocol identifiers remain unchanged.
  The expression parser consumes UTF-8 character boundaries and still emits only
  typed, quoted table accesses; executable Lua and function whitelists do not widen.
- New visual plugins, including the main plugin, have an empty JSONL document.
  Existing explicit no-op nodes remain intact. Root block identities remain in
  the manifest, and an empty plugin naturally completes successfully.
- Rectangular selections support original-pixel interior translation, bounded to
  the image without changing dimensions. Resize handles retain priority. Pinch
  and cancelled gestures do not commit partially edited geometry.
  Handle drawing and hit testing shrink with thin/downscaled selections: no
  fixed minimum hit radius may consume the entire interior move target.
- Image masks add an explicit `polygon` stroke to the existing version-1 mask
  envelope. A polygon has 3–128 distinct vertices, zero radius and nonzero area.
  Even-odd fill tests pixel centres; coordinates are original-pixel edge positions.
  Scanline work, strokes, total vertices, encoded size, cancellation and output
  budgets remain bounded. Kotlin previews and Rust execution share these semantics.
  Outside a kept polygon becomes transparent. Crop uses its bounding rectangle;
  search/OCR ROIs remain rectangles, not implicit polygon search operations.
- Image controls use bounded scroll regions on short/narrow windows. Primary
  completion/close controls stay reachable; drawing is clipped to its own viewport.
  Orientation changes preserve editing data and do not silently apply operations.
- Root cleanup failure stays fail-closed, invalidates that authenticated connection
  and uses existing bounded reconnect handling. It never resumes the failed action.
  Screenshot failure crosses JNI as a request-owned fixed code, not raw stderr or a
  global last-error slot. Root/screenshot diagnostics remain reachable without a
  current error message; App diagnostics belong in the diagnostic log filter.

## Verification

Add host tests for name boundaries, expression execution, empty plugins, selection
geometry and matching polygon pixels. Android compilation/host tests and MuMu
checks are recorded separately from ARM hardware acceptance. No default model,
algorithm, Root authorization or input-safety check is weakened.
