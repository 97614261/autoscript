# ADR 0145: Studio reference page design

Date: 2026-10-23

## Decision

Implement the user's uploaded project/profile references as native Compose UI, with a
shared pale violet background, compact rounded surfaces and a violet navigation pill.
The latest reference supersedes older project action-strip layouts: launch/edit/UI/settings
and backup/rename/package/delete form a four-column, two-row grid when space permits.
Narrow windows or large fonts reduce the column count; no fixed-height text clipping.
Only one project expands, with the first project initially expanded. IDs remain unchanged;
copy uses the full identity. Project actions, guards and explicit deletion confirmation remain.

Profile uses actual package version and actual runtime state, keeping each existing entry
once. No account, market, cloud or duplicate project shortcuts are introduced.

The launch reference is a native starting-window background followed by a short first-layout
exit transition. It does not wait for Root, perform extra project scans, imply measured loading
percentages or impose a minimum loading duration. Android 12+ retains the system splash
contract. Original brand pixels/aspect ratio/speed lines stay unchanged; only surrounding
light paths and shapes are decorative. Reduced motion/power saving disables the transition.

No new dependencies, engine/protocol/model changes, server deployment or private script runs.
Host checks and synthetic device UI checks are recorded separately from real-device acceptance.

2026-10-24 profile simplification: the user clarified that permissions, environment,
backups and function documentation remain. Remove the usage-guide entry/page; About
contains only the actual installed version and the existing update controls.
Remove its architecture/storage/runtime/legal descriptions, duplicate documentation
link and now-unreachable profile-only placeholder account/legal/help screens and
routes. Keep shared settings, function-library and updater implementations and the
existing explicit installation/security checks. This UI cleanup does not authorize
a package, installation, release or server update.

The targeted Studio entry/runtime-status host suites passed all 15 tests, and
Debug Kotlin plus Android-test Kotlin compilation passed. The workspace fixture
now checks the removed guide and About's version/update-only controls; it was
compiled, not run on a device for this UI change. At that implementation checkpoint,
no APK or device was updated. The user's subsequent installation request covered a
data-preserving MuMu 0 Debug update; the later explicit online request is recorded
as the Studio 0.1.6 publication in ADR0157, not a new device-acceptance result.
