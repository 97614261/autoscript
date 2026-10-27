# ADR 0136: Manual Studio updates and Root tool diagnostics

Date: 2026-10-21
Status: Accepted

## Decision

The user authorized a simple App updater and fixes for ARM screenshot failures.
Add manual checking under My / About. Read only the bounded website download
manifest, select the current release or its ABI alternatives (never history), and
compare numeric versionCode. Equal-code development builds are not upgrades.
Increment Studio to 0.1.1 / 2; future published upgrades must increment versionCode.
Keep Runner identity/versioning independent.

The distribution site is an explicit local build setting or user preference, not
a server address checked into source. Checking requires a user click; no startup
polling, private data upload, background download, component/model downloader or
silent installation. Confirmation opens the configured site's fixed download
page in a browser. Android's installer remains responsible for package/signature
and installation consent. Manifest checksums/signers are display metadata, not
proof of authenticity. HTTP is explicitly labelled unencrypted; redirecting the
manifest to a different origin is rejected, and HTTPS validation is never disabled.
Preserve the existing network policy used by script HTTP requests; do not introduce
a new global restriction or relaxation as part of the updater.

Studio installs native libraries through the package manager rather than relying
on executing a copied daemon from app-writable data on hardened Android devices.
The existing bounded extraction fallback remains for historical packaging. This
fixes a compatibility risk, not proof of the reported phone's exact cause.

Root startup reports fixed stage/result codes, including sanitized process failure
categories. Do not persist shell output, private paths, session keys or scripts.
App diagnostics use a separate bounded asynchronous file, independent of active
script logs, and are visible in the existing runtime diagnostic console. Screenshot
requests wait cancellably for the original session's Root-ready event, reject
busy/replaced sessions, and always return actionable failures. Keep authentication,
capture protocol, deadlines and priority stopping unchanged.

## Verification

Cover manifest bounds/ABI selection/version comparison/unsafe URLs, diagnostic
rotation/redaction, and Root readiness/cancellation on the host. Build and inspect
the optimized ARM APK including extractNativeLibs and fixed Root entry points.
Record connected-device evidence separately; no claim of phone acceptance without
that phone's actual Root and screenshot results. No website deployment is implied.
