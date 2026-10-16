# Project creation diagnostic build, 2026-10-13

Decision: [ADR 0091](../adr/0091-project-creation-safe-diagnostics.md).

The user approved a diagnostic build after the API 25 runtime reported generic
creation errors, ordinary logs omitted the caught cause, and debugger queries
failed. Read-only checks found adequate storage and committed project files.
These observations narrow the investigation but do not establish a root cause.

ProjectStore now reports six phases and an optional typed diagnostic on its
existing exception. Studio shows and logs only the safe summary under
ProjectCreation. No arbitrary exception message, project name/path, manifest or
script contents are logged. No stored project format or authorization changes.
Committed directories remain intact when reopening fails.

Five new tests passed: privacy, missing code symbol extraction, cause cycles,
owned staging cleanup and committed-directory preservation. ARM64
`build-mobile.cmd -Verify` passed unit tests, Lint (0 errors, 30 warnings,
16 hints), native packaging and repository layout checks. The resulting
Debug-signed candidate was updated on the user's selected API 25 network-ADB
environment using `install --no-streaming -r -t`; installation returned Success.
No MuMu update or installation into the separate API 37 USB system was performed.

The new build still needs one user-triggered creation attempt. This record does
not claim that creation is fixed or that the underlying failure is known.

Follow-up: the user's subsequent diagnostic screenshot identifies the API 31
BigInteger exact-integer method in the API 25 manifest validator. See the
[compatibility fix record](android7-project-creation-fix-2026-10-13.md).
