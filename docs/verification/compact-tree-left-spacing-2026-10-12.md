# Compact tree left spacing, 2026-10-12

User requested less left-side spacing in the visual program tree and edit list.
Both use a 2dp leading inset and 8dp indentation per level, bounded to 32 levels.
Previously the program tree used 5dp + 12dp/level and the edit list used
5dp + 18dp/level. Expand columns shrink from 24dp to 16dp; edit checkbox columns
shrink from 24dp to 20dp, retaining the 17dp visible box and 28dp minimum height.

Default root text starts at about 19dp in the program tree (previously 30dp), and
39dp in the edit list (previously 54dp). This is layout arithmetic, not a device
measurement. Guide lines align to ancestor expand-column centers; horizontal
content bounds use the same indentation step. Sibling alignment, folding,
checkbox selection, typography, row height and execution highlighting remain.
No saved hierarchy, executable arguments or project files are modified.

Tests verify common bounded indentation, guide/arrow alignment and shared wiring.
Validation: `build.cmd -Verify` passed. Studio has 596 tests, zero failures/errors;
Lint has 0 errors, 32 warnings and 17 hints (existing baseline). Debug APK built
and installed on MuMu 0 at 127.0.0.1:16384 preserving data. Repository layout and
whitespace checks passed. Device visual acceptance and private-script execution
were not performed.
