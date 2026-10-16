# ADR 0100: Provider process lifetime and prepared ownership

Date: 2026-10-13. Status: accepted implementation increment of ADR0098.

## Decision

Closing an SDK backend does not prove Android has unloaded its native libraries.
The fixed loader therefore pins the class loader and prepared-file lease before
initializing provider code, including failed initialization. At most one artifact
per fixed provider kind is pinned in a process. Switching its hash or process ABI
requires a process restart; repeated sessions reuse that loader, not a new native
library owner. Backend close is idempotent and excludes further execution.

Each newly prepared snapshot has an empty `owner.lock`, held by a nonblocking
exclusive FileChannel lease until all snapshot owners release it. Recovery skips
locked snapshots without hashing or deleting their bytes. A recognized unlocked
new snapshot is a terminated transaction and may be reclaimed under the registry
lock, including completed snapshots after process death. Unknown content and
legacy completed snapshots without this ownership evidence remain untouched.
The lock is not executable and is not a script-provided path.

Preparation failure and explicit close remove only tracked files and empty owned
directories. Close uses the same nonblocking registry lock as recovery; a busy
or failed cleanup is retryable, not an unbounded wait. The ownership handle stays
held while executable files are removed, and is closed before deleting its lock
file so Windows cleanup is supported as well as Android.

## Scope

This increment protects the loader/storage boundary. It does not switch Studio
from full to base, add a downloader, or claim user-facing provider import,
runtime dispatch integration or Android device acceptance. Those remain in the
optional-vision execution plan.
