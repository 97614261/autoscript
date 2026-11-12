# ADR 0219: Compact recognition actions and live script logs

## Request

Compare the legacy find/click behavior, make current image recognition settings
more compact, expose output bindings by default, and investigate missing image
search logs. No package publication or device installation is requested.

## Decisions

- Keep the screenshot toolbar and existing capture/matching algorithms. Use
  dense numeric fields, paired parameters and dropdowns for action choices;
  keep numeric presets, named search directions and inline variable selectors.
  Expand output bindings initially, retaining optional advanced parameters.
- Add optional `actionAnchor`, `actionRandom`, `actionRandomX/Y` to ordinary
  and multi-result image search. Existing persisted arguments default to
  top-left/follow; only newly initialized image forms default to center.
- Resolve an action from the actual matched template dimensions. Fixed offsets
  apply to the selected center/top-left origin. Inline randomization follows
  the original project/node policy, disables position randomization, replaces
  it with bounded symmetric offsets, or chooses within the screen-clipped
  matched rectangle. Rectangle mode ignores hidden origin/fixed offsets. It
  does not promise an opaque-pixel or polygon-interior hit.
- Reuse the existing coroutine-local random input facade and snapshot-aware
  screen bounds. Reset rectangle constraints at each node. Long presses draw
  one point; existing timing, release, branching and cancellation rules remain.
  Raw match coordinates and output variables do not change with click position.
- Promote saved single/batch flows and unsaved single/all draft validation to
  runtimeApi 1.8 for new action modes. Preserve 1.7 for legacy top-left/follow.
  Increment the generator identity so old generated Lua is not reused. No new
  host opcode, Root command, frame cache or native matching algorithm is added.
- Fix the live editor log request: its 200-row UI capacity exceeded the
  100-entry service bound and the client returned an empty list before IPC.
  Clamp positive requests to 100; nonpositive requests remain empty. Keep the
  service limit and bounded logging unchanged.
- Hide successful internal image-load/release/result-read helper projections
  only within recognized image-search blocks. Keep actual search ranges,
  found/miss results, completed input actions, helper errors and explicit
  result-reader blocks. Raw persisted observations remain available.

## Legacy Comparison

The inspected legacy `assets/web/图色封装.lua` uses the matched top-left plus
fixed offsets and `math.random(0, R)` per axis. That is positive-only jitter.
Current symmetric offsets, center actions and rectangle sampling are explicit
choices; old saved projects are not silently converted to either convention.

## Verification

Fourteen focused compiler/Lua execution checks and one JNI draft check pass.
These cover found/miss, unchanged outputs, real per-template centers, custom
random bounds, screen intersection, one-pixel templates, gesture lifetime and
single/all unsaved-draft API promotion without disk writes. Forty-seven Android
checks pass: Studio 45, log client 1 and storage single/batch save 1. Existing
parser, capture-source, ROI, history/error/location and insertion cases are
included. Contract snapshots, additive JSON/default checks and targeted Rust
format checks pass.

The first Debug build passed; full lint found two existing image UI errors.
Correct only polygon-canvas indentation and replace one unused
`BoxWithConstraints` with `Box`, keeping its bounds/content unchanged. Final
incremental Debug build and full lint pass with zero errors (49 warnings remain). The already-passing tests were not rerun
for these two layout-neutral changes.

Initial test-fixture duplication/required multi-result branch failures and the
sandbox Gradle-cache download failure are retained alongside successful
corrections. A multi-result execution test first exposed the new mode API 1.8
requirement; the production single/batch save and draft promotion fixes are
covered by the final checks. Evidence: `build/recognition-action-*`.

No version bump, install, device connection, private-script execution, online
publication or Git commit is performed. No server configuration, keys, project
backups or Root behavior is changed. Host geometry and mocked Lua input checks
do not claim actual device visual, touch, keyboard or ARM matching acceptance.
