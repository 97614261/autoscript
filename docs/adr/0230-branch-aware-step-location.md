# ADR 0230: Branch-aware execution location in the visual editor

## Decision

Use the existing validated running position and paused checkpoint to identify
the actual branch ancestor path. Highlight those headings with the same color
as the current block, and mark only the nearest branch as entered (or recently
visited for sampled history). Apply this to recognition, plugin-call and
conditional branches. Do not infer an outcome from a checkpoint on the owner
or from an empty branch.

Show a compact single-line location above the bottom controls: execution
state, canonical block row, displayed block title and ordered branch path with
owner rows. Derive rows from the full plugin tree, before view-only headings,
folding or focused-subtree projection. Reject missing or duplicate identities.
The path can scroll horizontally; moving to another block resets its scroll.
Remove the redundant generic paused instruction in favor of this location.

Keep the execution triangle in the existing gutter and make it more visible.
An executing container uses a smaller expansion arrow beside it, without
moving the block label. Following opens the existing required ancestors,
reveals an immediately preceding branch heading and scrolls horizontally only
as needed to expose the marker and a readable title. Manual browsing keeps the
existing follow-off behavior; the location strip can restore following.

This is presentation of existing observations. It does not change execution,
stepping, runtime observation delivery, selected-node test scope, source,
selection, insertion anchors or clipboard identities. Positions retain the
existing session/project/run eligibility and clear lifecycle; no completed
branch outcome is invented when no descendant checkpoint is available.

## Validation

All 15 focused JVM checks passed: six new location/branch-path/scroll cases,
two existing execution-appearance cases and seven branch-folding cases.
Studio Debug production and test Kotlin compilation passed in the same final
4m26s invocation. Scoped diff and new-file whitespace checks passed.

The initial invocation compiled production sources before the final horizontal
follow helper was added; test compilation then correctly rejected that old
artifact. Freeze the sources and rerun the same focused set, retaining both
logs: `build/branch-step-location-checks.log` and
`build/branch-step-location-checks-final.log`. No focused case had passed in
the initial invocation. Do not edit sources while compilation is in progress.

The initial Gradle dependency graph rebuilt the internal Runner template;
this is not a Studio delivery APK. No broad tests or full Lint were rerun.
No installation, device connection, publication, Git commit, private script,
Root or server/key/backup change occurred. Host checks do not establish device
visual/touch/IME or actual script-branch acceptance. Online remains Studio
0.1.48/code49 ARM64; ADR0228/0229 changes remain local as well.
