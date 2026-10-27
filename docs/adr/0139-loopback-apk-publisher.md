# ADR 0139: Loopback APK publishing

Status: accepted, 2026-10-21.

Transport superseded by ADR0140 after the user confirmed SSH is blocked: signed
HTTP/HTTPS replaces the SSH-only/bearer protocol. Other transaction boundaries remain.

## Decision

The user authorized a small APK publishing service, not an account system or a
general upload backend. Keep VitePress/Vue and the existing release.json contract.
Use Python 3.9+ standard library, a non-root systemd service and a persistent
downloads directory independent of the static site releases. Bind only to
127.0.0.1, authenticate with a private token and upload through an SSH tunnel.
Do not expose a plaintext public write endpoint or embed credentials in the site,
App, repository or logs. There is no server deployment authorization in this batch.

The Windows uploader verifies the actual APK v2 signature, fixed developer
certificate, package/version/minSdk and ABI/profile using existing Android tools.
The authenticated service checks the submitted contract, actual bytes, digest and
bounded ZIP inventory. Client metadata is trusted publisher input, not independent
server-side APK signature verification. The App's own certificate checks remain.

Serialize publishing, use a revision precondition, stage bounded uploads privately,
publish immutable APK/checksum files before atomic manifest replacement, and retain
history within the existing eight-build/448 MiB budget. Errors only roll back files
created by that request. Unknown files, interrupted prior transactions or exhausted
budgets stop publication; never scan-delete data or silently downgrade versionCode.
An acknowledged publication is not a device acceptance result or a stable release.

The download page revalidates a bounded same-origin manifest at runtime; failure
disables snapshot download links and displays an explicit retry state. Built HTML
retains the verified snapshot for static delivery checks, labelled as a snapshot
until refreshed. Nginx serves persistent downloads directly; the write API is not
proxied publicly. Existing static deployment checks must explicitly opt into the
persistent downloads root, rather than comparing a newer manifest with an old
snapshot or overwriting it.

## Verification

Test authentication, malformed/partial uploads, hashes and ZIP inventory, revision
conflicts, idempotency, alternatives/history, downgrade and budget rejection,
rollback and concurrent publishing. Keep local tests and actual Linux/systemd,
SSH, Nginx and public deployment acceptance separate. Do not rebuild Android or
claim a server is running from a successful documentation build.
