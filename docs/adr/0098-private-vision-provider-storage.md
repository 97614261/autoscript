# ADR 0098: Private fixed-provider storage and preparation

Date: 2026-10-13. Status: accepted implementation of ADR0096/0097.

## Decision

Keep runtime provider APKs in a separate application-private `vision-providers`
directory, never `runner-components` or project data. Imports are content-addressed
and verified against the current publisher and actual Android manifest before
publication. At most 8 APKs/1 GiB; unknown files remain untouched and count toward
budgets. A nonblocking process lock plus FileChannel lock coordinates Studio and
its `:runner` process without unbounded waits.

Preparation creates a unique private snapshot under `prepared`, with a frozen
APK, the exact selected process ABI's native libraries, and only the fixed ONNX
model if required. At most 2 prepared snapshots/512 MiB total, independently of
the imported APK quota. Never load libraries from an import URI, external storage,
the editable project, or caller-provided paths/classes. Components unavailable
for the process ABI are rejected, not replaced by another algorithm.

Open the owned destination file descriptor and make the new file read-only
before copying bytes; this preserves Android34's dynamic-code requirement without
changing user files. Sync, validate and atomically publish the APK. Preparation
revalidates signatures/descriptor/content and tracks extracted bytes/digests.
Failure/cancellation removes only this operation's tracked files. Explicit delete
only removes the selected validated-format APK name; prepared copies remain owned
by their lease and are not deleted by inventory cleanup.

Returned prepared handles are host-only ownership objects, not Lua/IPC paths.
They are rechecked before a future loader uses them. Native code cannot generally
be unloaded on demand; the later loader must retain loaded snapshots for its
process lifetime and manage backend sessions separately. This batch does not
claim loading delivered. Android24/25/34 read-only and PackageManager acceptance
remain separate from host filesystem tests. No downloader or data migration.

## Lifecycle follow-up

[ADR0100](0100-vision-provider-process-lifetime.md) makes this requirement
concrete: process-pinned loader/snapshot ownership is separate from closable
backend sessions. New snapshots carry a cross-process liveness lock; recovery
skips active transactions and reclaims only recognized terminated ones.
Completed legacy snapshots without liveness evidence are preserved.
