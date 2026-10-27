# ADR0148: Bounded Visual Preparation and Hot-Path Reuse

Date: 2026-10-23. Status: implemented; host and MuMu verification complete.

## Scope

The user approved batch two of the fixed twelve-stage performance plan: stages
4-7 (explicit same-frame workflows, pixel/color matching, grayscale/filter/glyph/
ORB, and OCR cold/hot calls). This does not authorize a different default model,
cross-input screen reuse, arbitrary model loading, private scripts or publishing.
Keep default full/V4, the existing short-cache lifetime and disabled experimental
transport. Existing frame borrowing/batching and grayscale-template preparation
are audited and tested, not counted again as newly implemented capabilities.

## Decision

Use only bounded immutable preparation. A test-only tolerant comparison-block
experiment proves equivalent failure pixels, budgets and cancellation boundaries,
but repeated alternating CPU measurements regressed, so production keeps its
scalar tolerant path. Existing exact blocks remain unchanged. Transparent pixels
keep their existing skipped-comparison semantics.

Glyph sampling hoists repeated coordinate calculations while retaining exact
integer resampling, dictionary order/tie handling, thresholds and work budgets.
Like pixel-vision, the glyph-ocr crate uses opt-level 3 in development builds;
development overflow/debug checks and unwind cleanup are not disabled.
Filter/gray processing keeps original pixels and explicit intermediate frames;
no hidden output or transformation fusion changes rounding/Alpha semantics.

ORB may retain at most two fixed-template descriptor sets (128 KiB combined
accounting limit), keyed by complete bytes plus geometry/format, never a screen
feature set. Successful active preparation is published only after cancellation
checks; evictions and backend close release native descriptors. Detector,
matching thresholds, RANSAC and current-frame computation remain unchanged.

ONNX interpolation coordinates/weights may be prepared once per input rather
than per output row/channel. Output float bits must agree with the previous
implementation and OpenCV reference, including padded rows, half-scale special
case, BGRA/RGBA and row stride. Keep one-thread BASIC sessions and model hashes;
existing per-backend model/dictionary reuse remains, with cold and hot timings
reported separately. Do not switch production strategy based on speed alone.

## Verification

Host differential/budget/cancellation tests and synthetic device checks precede
adoption. Bind baselines/candidates to exact APKs and disclose timing boundaries;
pixel CPU tests do not replace complete-script or SDK measurements. Shared-frame
mixed recognition must not capture/release a borrowed frame; final release and
stop/cancel cleanup retain existing ownership. Keep failed experiments and do
not claim a universal latency bound or native ARM acceptance from MuMu results.

Necessary architecture and regression records are maintained; the user requested
only a concise final result, not a standalone long report.

## Local Acceptance

`build.cmd -Verify` passed Android unit tests, Lint, full dual-ABI native packaging
and repository layout checks. Rust workspace tests, strict all-target Clippy,
format and whitespace checks passed. Production defaults remain full/V4, the
80 ms cache and disabled experimental transport. The verified 96,445,850-byte
Studio APK was installed to MuMu 0 only (API 32, x86_64, 720x1280), retaining data:

- Studio SHA-256: `7d19ff0a90e50b947557522790ed980f78b93fce2702d45a7247f0cad111161a`.
- Studio test APK: `3416ef0f105bc968b32dd9b3e452c2e383b8710cbf173d5602ad6071a614b10a`.
- Independent SDK test APK: `8ad3659c2154ffdff286fcd6f3130b0d491b9adf544efca6d22cd4b6db9e2b97`.
- Installed Studio base.apk digest matched the local production APK; the existing
  certificate `8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c`
  is unchanged. ARM compilation is not native ARM acceptance or publication.

The exact production APK passed editor smoke 9/9 twice, region geometry 6/6,
expanded performance 10/10 twice, historical performance 6/6 and mixed same-frame
3/3 twice, all with cleanup. The mixed fixture verifies 111 genuinely fresh
captures, one per iteration, matching pixels, colors and all glyph results before
explicit release (65 public 32x48 dictionary variants). Color/glyph lack separate
production timing events; per-iteration ordered script-value oracles verify them
outside the complete timer, rather than inventing per-stage metrics.

Complete-script P50/P95/P99 in ms, 100 hot samples after 11 excluded calls:

| Mixed workload | Baseline | Candidate round 1 | Candidate round 2 |
| --- | --- | --- | --- |
| Shared full frame | 33/43/47 | 28/36/40 | 28/35/39 |
| Shared region frame | 18/23/26 | 15/17/18 | 16/21/22 |

Each includes public Root tap, actual acquisition/transport, all three recognition
operations, release and script dispatch; logging and the disclosed 25 ms pacing
follow the timer. Inputs invalidate the production cache. Expanded three-distinct
independent full-frame P95 was 111/104 ms, versus shared-full 35/36 ms and shared-
region 19/17 ms. Do not claim all workloads are below 100 ms. CPU frequency was
not locked; these are observed package results, not guaranteed causal percentages.

The SDK fixture passed 28 OpenCV tensor-bit parity cases, 8 ORB cache ownership/
mutation/format/eviction/cancellation checks, and 8 existing ORB/V4/V5 regressions.
The 180 fixed-font accuracy outputs exactly match the baseline: glyph 23/60,
V4 48/60 and V5 42/60 exact strings. Consistency is not perfect recognition;
existing unknown glyphs and ambiguous characters remain. Across that public
corpus, glyph warm P50/P95 changed from 20.424/43.745 ms to 1.999/3.434 ms, including
the development optimization profile. Training/setup is reported separately.

Two clean alternating SDK package rounds retain identical response fingerprints.
ORB median improved in both, but tails and OCR/grayscale totals fluctuate, so no
stable whole-OCR or whole-grayscale improvement is claimed. Same-process
alternating preprocessing (including allocation, 100 samples per variant) reduced
P50 from 19.814 to 19.214 ms and 10.483 to 10.090 ms on ordinary text dimensions;
half-scale was 17.423 to 16.531 ms and the maximum-width case 106.586 to 100.786 ms.
Release CPU glyph-coordinate reference/candidate P50 was 2.072/0.889 ms per 1000
comparisons; that microbenchmark is not a device/script latency measurement.

Ignored evidence is retained in `build/verification/performance-batch2`. Initial
SDK/preprocessing trials overlapping host compilation are not speed baselines.
`studio-mixed-baseline.log` retains the initial test-oracle failure: valueVariable
replaces message text, so the corrected oracle checks ordered values, not labels.
`studio-mixed-baseline-corrected.log` is the separate passing baseline. Tolerant
block CPU regressions remain recorded and test-only, not silently adopted.
