# ADR 0091: Safe project creation diagnostics

- Date: 2026-10-13
- Status: Accepted

## Decision

After repeated generic creation failures on the user's API 25 environment and
unreliable debugger queries, the user approved a diagnostic build. Record only
the creation phase, bounded exception type, whitelisted code location and a
strictly extracted missing method/field/class symbol for linkage failures.
Do not include exception messages, project names, paths, manifest data, scripts
or arbitrary stack traces. Studio may emit this safe summary under the dedicated
ProjectCreation diagnostic log tag and show the same summary in its error area.

The store remains Android-independent. Its existing exception gains optional
typed creation diagnostics; Studio logs only that typed summary. Successful
creation, validation, storage permissions and persisted project formats do not
change. Failure cleanup remains scoped to the creation staging directory; a
committed project is never removed to obtain diagnostic information.

## Verification

Tests must verify privacy, bounded cause traversal, staged failures and retaining
committed files after a reopen failure. ARM64 tests/Lint/packaging precede a
data-preserving install. A concrete device failure is still needed before claiming
the cause has been found or fixed.
