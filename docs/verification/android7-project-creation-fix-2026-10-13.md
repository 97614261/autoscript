# Android 7 exact integer compatibility, 2026-10-13

## Confirmed failure

The user's diagnostic screenshot identifies `CREATE/REOPEN`, `NoSuchMethodError`,
`ProjectRandomizationKt.validateRandomizationJson$integer:29` and
`symbol=intValueExact`. The target environment reports API 25. Android SDK 36's
API version table lists BigInteger.intValueExact as introduced in API 31;
BigDecimal.intValueExact is present since API 1. The old code converted a
BigDecimal to BigInteger before exact conversion, so even a new project's
default randomization values failed when its manifest was reopened.

## Change

Project and node-randomization validators now call BigDecimal.intValueExact
directly, preserving rejection of fractional and overflowing values without an
API 31 call or an increased minimum SDK. The same unsupported pattern was found
and fixed in studio-compiler, avoiding a subsequent node-editing failure.
Loop durations already use BigDecimal.longValueExact and do not require a change.
No project format, default policy, permissions or randomization semantics change.
No existing project directory is deleted; successfully committed manifests can
be read by the corrected validator without cleaning application data.

New tests cover exact decimal/exponent values, boundaries, fractional values,
32-bit overflow, huge exponents and non-numeric coercions in both validators.
A source guard rejects reintroduction of the BigInteger conversion pattern.

## Validation

`build-mobile.cmd -Verify` passed: 611 Studio tests and 76 project-store tests,
zero failures/errors, Lint 0 errors (30 warnings, 16 hints), native packaging,
repository layout and whitespace checks. The ARM64 Debug-signed candidate was
updated on the user's specified API 25 environment with `install -r -t` and
returned Success. Existing app data was retained; MuMu and the separate USB
main system were not modified. Device creation acceptance remains a separate
user-triggered check; successful install is not a successful creation test.
