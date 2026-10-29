# ADR 0175: Current Function Library And Migrated Skill Branches

Date: 2026-10-28

## Decision

The App function library exposes current blocks and API references only. Remove
the legacy group, legacy search aliases and entries, source-helper suggestions,
and legacy favorites/recents from the available choices. New project capability
defaults derive from the same insertion catalog. Existing project declarations
remain intact, and the parser/compiler retain legacy contracts so opening an
older project does not destroy data or make it unreadable.

The private migrated skill project is converted in an independent copy of its
latest formal SAF export. Its top-level skill condition contains only mode 2 and
the original fallback. Delete the old mode-0 subtree, including its comments;
preserve retained input parameters, branch contents, other flow bytes and image
resources.

The retained legacy color counts have equal red/green/blue tolerances. Convert
them to current `vision.countcolor` with the first color and additional colors,
exact half-open region endpoints, unlimited counting and fresh private capture.
The old and current implementations use the same any-color pixel counter for
this case. This does not establish equivalence for arbitrary legacy parameters
or for any legacy native recognition algorithm.

Private scripts, templates, generated bundles and migration audit records stay
in ignored build output; they are not shipped in the public APK. The converted
project is imported separately, retaining the user's previous projects. This
change does not authorize game execution, server backup operations or an online
release.

## Validation

Check current library/API coverage, absence of legacy search entries and
preservation of readable old contracts and declared capabilities. Compile the
converted project with the current compiler, round-trip every converted color,
tolerance and region, and compare all retained node parameters and image bytes.
Synthetic input ordering uses the real arbiter but mocked vision and waits; it
is not an actual game, screenshot or recognition test.
