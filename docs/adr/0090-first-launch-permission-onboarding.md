# ADR 0090: First-launch permission onboarding

- Date: 2026-10-12
- Status: Accepted

## Decision

When Studio starts for the first time and a required user-facing system permission
is missing, the existing software-permissions dialog opens before the main pages.
The dialog explains the two basic prerequisites: overlay permission is used by the
cross-app editor and notifications are used for editor recovery and run status.
The user must click `开始授权` before the next missing permission is requested;
Android settings and the Android notification permission prompt remain explicit
user actions. Overlay is requested before notifications. Android versions below
33 do not show a notification runtime request.

The dialog continues to display Runner connection, Root readiness and actual input
features separately. It never grants Root, Accessibility or Shizuku permissions.
Root remains a device-manager action and is only reported in the environment page.
If a user closes the guide before completing prerequisites, the next fresh app
process can show it again; an already prepared device is not interrupted on every
launch. Manual permission-page entry remains available from My/Settings, the
environment screen, project settings and editor tools.

## Verification

Pure tests cover action ordering, API 33 notification behavior and the completed
permission case. The ARM64 candidate passed 610 Studio tests, zero failures or
errors, Lint (0 errors, 30 warnings and 16 hints), native packaging checks and
repository layout checks. The candidate is at
`build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`.
The requested device was offline when the final install was attempted, so the
APK was not installed in that retry. A real first-launch system-settings flow is
still separate device QA; this feature does not auto-grant or run a script.
