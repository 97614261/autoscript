# ADR 0158: Explicit signed APK history archiving

Status: accepted, 2026-10-25.

## Decision

The user approved adding remote history archiving to avoid uploading a separate
server script whenever the download history is full. Extend the existing HMAC
v1 service with POST and GET `/api/publisher/v1/archives`; keep the current
loopback binding, challenge, signed responses, revision preconditions, process
lock and single-writer admission. The existing Nginx prefix already permits
these methods; do not change its configuration or expose another port.

POST uses bounded JSON containing version 1 and one to eight distinct full
SHA-256 identities, bound to its exact bytes and `If-Match`. Only actual history
entries are eligible. Never archive the primary or any current alternative,
infer selection from a version prefix, choose the oldest package automatically,
delete an archive, or bypass the existing 8-package / 448 MiB public budget.
The local command first shows exact version/ABI/size/identity and requires
confirmation. Noninteractive approval must explicitly supply the same full
identities and revision; preview is read-only. No pending online archive is
implicitly approved by implementing this interface.

Copy and fully verify the selected APKs/checksums into a private archive before
atomically publishing the reduced manifest. Preserve both manifest bytes and
revision digests in a receipt. Only after the durable backup and new manifest
exist may the exact selected public copies be removed. Keep an in-flight marker
until completion. Crash residue or I/O failure is preserved and blocks future
writes for inspection, never guessed away or automatically retried. Signed
archive receipts allow investigating an ambiguous response without repeating a
mutation. No remote restore, arbitrary paths, Shell, public archive download or
automatic rotation is added.

Private archives use a separate directory, at most 32 transactions / 4 GiB;
capacity exhaustion rejects new archives, never silently deletes old ones.
Existing manually created archive directories outside this namespace are left
untouched. All files remain recoverable; archiving reduces public download
inventory, not total disk use. Unknown content and links are rejected.

Deliver one small publisher-only update bundle without APKs, keys or private
configuration. Its explicit administrator installer validates checksums and
existing service, backs up only its two Python sources, updates them with the
service stopped, validates as the service user, restarts, and restores code on
ordinary failure. It does not initialize downloads, regenerate credentials,
change systemd/Nginx, install software or publish APKs. Actual server deployment
remains the user's action, followed by signed acceptance; local tests do not
establish server acceptance.

## Verification

Cover active/unknown/duplicate identities, stale revision, exact body tampering,
challenge replay, response verification, archive inventory and capacity, retained
history and recoverability, failure before/after manifest commitment, interrupted
operation admission, and PowerShell 5/7 interoperability. Package the exact
tested sources, including explicit update instructions and source checksums.
Do not rebuild Android or repeat unrelated device/performance campaigns.

## Local implementation outcome

Implemented the signed archive routes and read-only capability/receipt discovery,
`archive-apk.cmd` with preview and exact revision confirmation, and the
publisher-only administrator updater. Archive bodies also have a 15-second
monotonic read deadline. Incomplete receipts block new writes even if an operator
has removed the staging marker; neither marker removal nor restart is recovery.

The original service/protocol regression ran 42 tests successfully. After the
bounded-read and incomplete-receipt guards, 18 affected tests passed, including
the new regressions, real PowerShell 5/7 preview/confirmation/readback, and signed
archiving through an isolated actual Nginx proxy. Six updater unit tests passed
with mocked system-service commands (backup, partial install failure, rollback,
preflight refusal and data/key preservation). Two delivery tests passed,
including source/hash inventory and invoking the packaged updater's read-only
bundle check. These are local synthetic fixtures, not an actual administrator
upgrade on the user's Linux server. No Android rebuild, device operation,
online archive or APK upload was performed.

The final local publisher-only delivery is
`20261025T022844Z-242b05991632/zhigou-publisher-20261025T022844Z-242b05991632.tar.gz`,
26,734 bytes, SHA-256
`5f18e98f5e4604c825f0479c1666ca66b449e61f5a89ff66ab27d838e58f60b5`.
Its actual packaged read-only bundle check passed. The exact nine archive entries
are the seven public service/update sources and instructions, delivery metadata
and checksums, with no APK or key. The updater freezes validated source bytes
before installing; its six unit and two delivery tests were rerun after that
final hardening. Deployment remains pending user execution, and Studio 0.1.8
remains a local Android candidate rather than a published update.

## User-operated deployment and first live archive

The user executed the small updater and supplied `PUBLISHER_UPDATE_OK`, with
original source backup `/opt/zhigou-publisher/before-archive-ti5hg6r1`.
A subsequent real signed read confirmed `archive-history-v1`, the unchanged
0.1.7 / 8 eight-package manifest (351,821,236 bytes), and an empty new archive
namespace. This is separate from the earlier local mocked installer tests.

The user then explicitly approved archiving only the oldest 0.1.4 architecture
pair and publishing 0.1.8. Both frozen candidate hashes were rechecked. At signed
revision `7fef2a811d74fe9098d07eec7dbc13c9ae7f1a7c8d45c31c7b0bc1f23e17ee03`,
the new live API confirmed complete recoverable archive
`archive-20261025T023435Z-e19f449a9a7abed5`, under
`/var/lib/zhigou-publisher/archives/`. Its selected hashes are
`5bbcd4a83d02f72e8d793a344704a10f57d2643de813afd6f4ddb59a211d869f`
and `d6a0d72f79be56848dffcb5e6f2467e8c24b8b1e978cd771a98aba785342abef`.
Signed readback matched the exact reduced manifest: all six 0.1.5/0.1.6/0.1.7
packages and their metadata stayed unchanged. No previous manual archive,
configuration, website file or device was changed. APK publication is recorded
separately after its actual outcome, not inferred from the archive success.

Both frozen 0.1.8 / 9 architecture candidates subsequently published through
the existing HMAC API. Actual public download verification passed for both full
APK streams (exact sizes, digests and MIME), and the raw public no-store manifest
matched the signed revision. The download page returned 200. A final signed
archive read confirmed this exact receipt is complete, with 87,861,624 APK bytes
and only the two approved 0.1.4 hashes. The signed release revision remained
`4e8033878b33043c025b9cd50343a8f89122faf1093ad9d8902979568b5f198c` through
verification; eight public packages total 352,010,984 bytes. Full candidate
identities and update delivery are recorded in ADR0137. No further server script
upload was needed for this first live archive.

## Development-history authorization update, 2026-10-25

The user subsequently granted standing permission to archive or remove old
development releases without asking for each publication. Prefer recoverable
archiving, selecting only the oldest history needed for the next release's
capacity. The client still previews signed state, freezes exact identities and
uses its revision precondition; active packages remain protected. This overrides
the earlier per-version approval requirement, not the public/private budgets,
unknown-file protection or archive integrity checks. It does not authorize
silently removing private archives or add server-side automatic rotation.
The immediate authorized selection is the two 0.1.6 history packages, preserving
0.1.7, 0.1.8, 0.1.9 and all existing archives before publishing 0.1.10.
