# Compact function-library category tabs, 2026-10-12

The left category column is reduced from 82dp to 76dp; category rows have a 28dp
minimum (previously 38dp). Text is 11sp/14sp with 3dp vertical padding, centered
with the count preserved. Selection markers are centered and reduced to 16dp.

Rows use a minimum rather than a fixed height; up to two centered lines allow
enlarged system fonts or long category names to grow without silently removing
the count. Category filtering, searching, fullscreen and read-only API documents
are unchanged; both library modes share the same category component.

Validation: `build.cmd -Verify` passed; Studio has 588 tests, zero failures/errors.
Lint remains 0 errors, 32 warnings and 17 hints. Debug APK built and installed on
MuMu instance 0 at 127.0.0.1:16384 with app data retained. Repository layout and
whitespace checks passed. The added test verifies shared dimensions, centering,
text/count retention, flexible row sizing and category click wiring.
No device visual acceptance or private-script execution was performed.
