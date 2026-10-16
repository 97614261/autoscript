# Compact visual program rows, 2026-10-12

User requested lower rows in the visual block area. The main program tree and
the edit-operation tree share a 28dp minimum height (previously 35dp). Vertical
label padding is 3dp per side (previously 6dp). Text remains 12sp with a 16sp line
height, single-line ellipsis and no extra font padding. Minimum rather than fixed
height lets enlarged system text grow without clipping.

The expand hit area uses the same row minimum; the multi-select checkbox hit
area is 24dp within the row, retaining its 17dp visual box. Full-row selection,
long press, indentation, disable states and execution/step highlighting are
unchanged. This is compact desktop-like editor density, not a claim of a 48dp
mobile accessibility target.

Validation: `build.cmd -Verify` completed successfully. Studio has 587 tests,
zero failures/errors; Lint has 0 errors, 32 warnings and 17 hints (existing
baseline). Debug APK installed on MuMu 0 at 127.0.0.1:16384 preserving data.
Repository layout and whitespace checks passed. Tests cover shared minimum/padding,
unchanged typography, flexible sizing and click/execution-highlight wiring.
Running-row scrolling remains index-based rather than assuming a fixed pixel row.
No device visual acceptance or private-script execution was performed.
