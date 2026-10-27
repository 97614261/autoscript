# ADR 0140: Signed HTTP APK publishing without SSH

Status: accepted, 2026-10-21. Supersedes ADR0139's SSH-only transport.

## Decision

The user's computer cannot connect by SSH. The user explicitly accepted HTTP
publishing with HMAC authentication instead of a domain, TLS certificate or SSH
tunnel. Keep the Python process on 127.0.0.1; an explicit Nginx location proxies
only the versioned publishing API. Do not expose the backend port.

Replace bearer authentication, including on loopback, with a domain-separated
HMAC-SHA256 protocol. Reuse the existing private 256-bit random token as a key;
never transmit the key in a header, URL, body or log. The Windows tool uses an
explicit HTTP/HTTPS endpoint, retains normal HTTPS certificate verification and
refuses redirects. The site's public download contract and App are unchanged.

Each operation first requests an authenticated, response-signed random server
challenge bound to a fresh 128-bit client nonce. Challenges are valid for 120
monotonic seconds, single use, and held in a maximum-64-entry locked cache.
Consumed entries remain until expiry. Restart invalidates all previous challenges;
it does not reopen old signatures. No synchronized client/server clock is needed.

The request signature binds protocol/direction, method, exact path, client nonce,
challenge, content type/length, metadata digest, revision and APK digest. The
existing streaming upload verifies that actual bytes match the signed digest.
Response signatures bind direction, method/path, nonce, request signature, HTTP
status and exact response bytes. Verify before parsing or accepting success/error.
Unsigned proxy errors and transport failures are not successful publications and
must not cause automatic retries: commitment can be uncertain after disconnect.

Signed HTTP is not confidential, not TLS-equivalent and not proof of reachability
or availability. Public APKs, descriptions and traffic can be observed or blocked;
an attacker may relay an already-authorized operation, but cannot alter its bound
contents. Use one unique key per server. No browser admin UI, CORS, arbitrary file
upload, credentials in website/App, component downloader or automatic deletion.

Nginx disables request buffering and retries for this endpoint, caps body size,
and forwards to the loopback-only, four-connection, single-publication service.
The existing APK signature/identity checks, revision/atomic commit, 8-build and
448 MiB budgets remain. A small private service-update bundle must not include
APKs, private configuration or keys; actual server changes remain user-operated.

## Verification

Test altered method/path/nonce/challenge/metadata/length/digest/revision, replay,
expiry, bounded challenge state, restart invalidation, response/status tampering,
legacy bearer rejection and direct PowerShell/Python interoperability. Separate
host tests from the user's Linux/Nginx deployment and public-network acceptance.

2026-10-25: ADR0158 adds explicit signed history-archive POST/read operations
under the same versioned prefix. It preserves the challenge/HMAC and download
budgets; it does not authorize automatic selection/deletion or arbitrary files.
The server needs a user-operated code update before this extension is available.
