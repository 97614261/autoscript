# ADR0147: Explicit Region Readback and Performance Baselines

Date: 2026-10-23. Status: implemented; MuMu verified, native ARM not verified.

## Decision

The first performance batch adds an explicit Screen.captureRegion operation and
visual block. Existing Screen.capture, preview capture, and implicit recognition
capture remain full-screen. Region captures are fresh requests, not cache hits.
No model, algorithm, frame-cache lifetime, or input replay behavior changes.

The Root protocol negotiates CAPTURE_REGION together with authenticated streaming,
stage and transfer timings. A fixed request binds the expected logical screen size
and a nonempty half-open rectangle. A distinct metadata tag binds the same request,
actual orientation when known, and the readback mode to the authenticated stream.
Old peers reject the unsupported operation without changing full-screen capture.
All bytes, contiguous offsets, request identity, sequence, completion packet count,
deadlines, cancellation and connection invalidation retain existing protection.
No frame is published until the complete authenticated transfer succeeds.

The device regression also found that an idle full preview after an authenticated
script stop remained behind the clean stop gate. A new explicit preview now waits
for confirmed cleanup and resumes that existing gate before its single fresh
capture. Failed cleanup invalidates the connection; it never replays old work or
weakens the priority stop path. Input runtime state is not reset by a preview.

The persistent bridge still acquires a full native-resolution software Bitmap,
but getPixels and the pipe/Root transfer read only the requested rectangle. It
validates display geometry before and after capture/readback. This is region
readback/transfer, NOT source-region system acquisition or zero-copy hardware
readback. Hidden source-crop APIs are not enabled without orientation-equivalence
and device evidence. Bounded screencap fallback crops exact bytes before transport
and explicitly reports full-readback fallback; unknown rotation is not invented.

Region frames retain original logical screen size, offset, orientation provenance,
timestamp and coordinate generation through the existing bounded FrameOrigin and
frame pool. Recognition coordinates remain local to the region frame; explicit
Screen.mapPoint maps to original screen pixels. File/processed images do not gain
screen provenance. Region storage is budgeted by actual allocation capacity.

Public synthetic benchmarks extend the existing fixtures without silently changing
their historical workloads. Separate exact/relaxed/transparent/different-template,
full/region and shared/independent fresh-frame cases. Cold and hot samples, failed
runs, P50/P95/P99, complete timings and overlapping stages remain distinct. An
experimental transport is promoted only after repeatable complete-workflow gains.
No standalone user-facing report is required; retain necessary regression and
architecture records. Only self-owned QA may run; no private scripts, phone changes,
input-method switching, data clearing, or online publishing are implied.

## Validation

The final development/full APK SHA-256 is
`4cd01c029ee94fb44379b6dd6984c8b34024296e46ccc5378d13811e0746dcad`
(91,983,058 bytes). APK v2 verification has one expected development signer;
the installed MuMu APK has the same digest. Layout overhead is 36,330 bytes,
below the existing 2 MiB limit. The final acceptance APK SHA-256 is
`9c8a2224ed2ccdc18f964d0994992d40c65199848be13e8155f1b09be3d5e7e8`.
Default full/V4 and the disabled experimental pipeline remain unchanged.

Rust workspace tests passed (613 passed, 18 ignored), as did strict all-targets
Clippy, formatting, schema/code-generation checks, Android tests/Lint/build,
and documentation generation/build/link checks. Studio JVM results were 915 tests,
zero failures/errors, one skipped. Separate CPU correctness cases cover three
resolutions through 1440x2560; their benchmark timings are not Android evidence.

Six final device runs bind both exact APK digests: editor 9/9 twice, regional
geometry/mapping 6/6, expanded performance 10/10 twice, historical performance
6/6. All reported successful cleanup and instrumentation completion. Geometry
includes portrait-landscape-portrait, two public colors in each orientation and
explicit point mapping before/after flipX. Only MuMu 0 (PGT-AN20, API 32, x86_64)
was operated; this is not native ARM/API24 acceptance or an online release.

The timing fixture uses a 720x1280 screen and a 200x122 public template. Full-frame
matching ROI is [480,948,700,1090); region capture is [470,938,700,1090) (230x152),
with local ROI [10,10,230,152). Exact cases use similarity 1000/tolerance 0;
relaxed cases use 900/12 with 5% template mismatch; transparent cases have 20%
alpha-zero pixels. The three-template cases use distinct 80x40 crops. Each case
has one cold sample, ten warmups and 100 complete hot samples; independent triples
capture 300 fresh hot frames. A real Root tap invalidates the short cache before
each capture. All measured captures report no cache hit, root-surfacecontrol and
authenticated-stream. Complete timings include input, capture, matching, explicit
mapping where applicable, frame release and script dispatch, but exclude the
following log write and disclosed 25 ms observation pacing.

The final two rounds give P50/P95/P99 in milliseconds:

| Workload | Round 1 | Round 2 |
| --- | --- | --- |
| Full, exact | 27/33/34 | 27/36/37 |
| Region, exact | 14/19/20 | 15/19/21 |
| Full, relaxed | 27/36/40 | 25/30/35 |
| Region, relaxed | 14/17/17 | 15/20/23 |
| Full, transparent | 25/34/35 | 26/34/36 |
| Region, transparent | 18/20/21 | 14/18/19 |
| Three distinct templates, shared full frame | 26/33/36 | 29/34/38 |
| Three distinct templates, independent full frames | 77/94/108 | 80/92/100 |
| Three distinct templates, shared region frame | 15/18/19 | 15/19/21 |

The same production APK's two earlier rounds used the previous test-only preflight
and recorded independent-full P95 of 101/104 ms. Keep those results: final repeated
passes do not establish a universal sub-100 ms bound. CPU frequency was not locked;
historical baseline builds are not controlled causal comparisons. Region transport
uses three packets instead of sixty for the full frame; stage timings overlap and
must not be added or used as additive percentile attribution. Native acquisition
still obtains the full Bitmap, as specified above.

Local ignored evidence is retained under `build/verification/performance-batch1`.
Final logs are `mumu-editor-shipping.log`, `mumu-editor-shipping-repeat.log`,
`mumu-region-shipping.log`, `mumu-expanded-shipping.log`,
`mumu-expanded-shipping-repeat.log` and `mumu-historical-shipping.log`.
The initial `mumu-region-geometry.log` failure led to the preview stop-gate fix.
`mumu-editor-final.log` retains an 8/9 smoke failure at the fixed-delay pixel oracle;
the diagnostic rerun passed, but does not prove that failure's original cause.
The final smoke preflight confirms the public green pixel is actually presented,
then separately checks one fresh Root capture after authenticated stop/cleanup.
It does not retry a failed Root assertion or alter the measured performance loop.
The final editor smoke passed twice; prior failures remain separate evidence.
