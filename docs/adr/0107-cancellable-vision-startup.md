# ADR0107: Cancellable vision startup preparation

Date: 2026-10-14. Status: accepted, staged implementation.

## Decision

Protocol27 appends one-shot vision preparation and cancellation methods. A fixed
bounded completion listener reports request/generation/status plus fixed component
and state identifiers, never SDK exception text. Preparation uses compiled SDK
demands, not all declared capabilities. Historical unknown demand keeps legacy
execution behavior and lazy runtime rejection; it is not a known empty demand.

Preparation reserves one service request, runs off Binder/engine/Root work queues,
has a 30-second deadline and no queued replacement request. Cancellation and stop
invalidate readiness immediately; a native call still returning keeps its busy
lease until actual completion. Late success cannot authorize a later start.
Completion is bound to the exact frozen Lua SHA-256 and service generation; start
checks the receipt and current selected component identities. The receipt is not
a permission grant and cannot modify project declarations. Component management
checks service preparation state before mutation.

SDK preparation resolves the exact fixed provider and runs a tiny owned synthetic
RGBA fixture through the same backend. It performs no capture or input and never
publishes synthetic match/OCR output. This proves initialization/inference can
return, not device recognition accuracy. Native lifetimes remain process-pinned.
Fresh provider verification runs on bounded resolver IO, not Binder. At start,
fixed selection identity reads may be bounded but never scan/hash component APKs.

Client freezes source/demands before preparing and awaits the single completion
event. Closing/stopping/cancelling cancels the request; callback identities and
service state reject stale completions. Empty known demand performs no SDK work.
Default full stays unchanged; real provider/device and standalone acceptance are
separate release gates. No downloader or new user script API is introduced.
