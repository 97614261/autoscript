# ADR 0211: Route remaining operations into the two log categories

## Request

The user asked to include the remaining functions and application operations in the two categories introduced by ADR 0210. This is local implementation, without a new delivery APK, installation, publication or Git commit.

## Script Activity

- Generate readable operation titles and parameter labels from all 203 existing API contracts and block titles. Fail generation when a title or parameter label is missing. Keep `Log.write` on its existing explicit-output path to avoid a duplicate copy of its text.
- Use the executor's common dispatch boundary for synchronous utility results, task/timer control, file operations, HTTP/application/clipboard/IME/audio requests, floating windows and script UI. Preserve the specialized touch, capture, image/color search and OCR completion paths. No scheduling, capability, Root, input-arbitration or recognition result is changed.
- Record synchronous results from actual resume values. Pending host operations own their completion observation; they do not produce an early success record. Sleep records distinguish waiting, wakeup and cancellation. A denied sleep cannot enter the pending-log map. Prompt operations report a request or a disabled setting, rather than claiming Android has displayed a window.
- Keep actual scalar results, bounded text previews and array/object counts. HTTP records keep the method and received status, including an actual HTTP 404 response. A response code is not fabricated as an executor transport failure.
- Redact HTTP addresses, headers and bodies, input/clipboard text, file contents, loaded template/dictionary paths and sensitive returned device/UI data. Keep useful relative file operation paths and numerical/search parameters. Script authors can still explicitly print their chosen values. Exceptions retain only validated structural failure codes in action records; the existing system failure channel retains bounded troubleshooting details.
- Pure variable assignment/calculation, conditions, loops and direct plugin calls retain their actual executed checkpoint rows. Do not invent evaluated values or selected branches from block titles. API-backed blocks use their detailed operation record rather than an additional generic step. Existing saved trace events remain readable for compatibility.
- Freeze names and visual rows at execution time. Preserve repeated actions, bounded storage and explicit loss reporting. Unicode supplementary characters in bounded details are accepted without silently dropping the whole record.

## System Logs

- Connect public operation feedback from project management, local/server backups, optional accounts/password changes, save/compile/editor/source operations, packaging/component management, updates, image library/tools/processing/recognition/glyph creation, recording, permission settings, UI design/draft recovery and AI operations to the existing system journal.
- Do not observe form input or model answers. AI errors that can contain prompt/source snippets store a generic operation failure; details remain in the existing AI panel. Record successful login and application of an AI proposal explicitly before navigation.
- Translate every existing Root/capture diagnostic enum to readable Chinese while retaining its code. Distinguish normal idle release, manual disconnection and cancellation from failures. Preserve script-written text as text: it cannot impersonate a trusted diagnostic or navigation record.
- Persist real connection-state deltas from the foreground application or editor. Remote polling does not repersist snapshots. Hide matching live mirrors in the system viewer, while keeping repeated script actions.
- Application feedback uses a bounded asynchronous writer and an explicit dropped-record count, avoiding synchronous filesystem writes in UI actions. Keep the previous journal limits, cross-process locking, crash-handler delegation and Android-version limits from ADR 0210.
- No new visible log categories, log settings page or performance report is introduced.

## Verification

The first Rust compilation exposed an incorrect contract helper path; it was corrected to the existing executor helper. Expanding API coverage also exposed one template-path privacy assertion and two mixed-event assumptions. Preserve template-path redaction, select metric records for the existing metric assertions, and separately verify the intended readable OCR actions.

The final runtime-executor suite passed 164 tests, with the two previously ignored cases still ignored. This includes real temporary Lua execution against a public virtual host, API catalogue coverage, actual utility values, asynchronous file failure/location, denied capabilities, sleep completion/cancellation, sensitive data and no duplicate explicit output. Evidence: `build/log-coverage-rust-*.log`.

The first Android check compiled production/test sources and passed the diagnostic-enum coverage case. One newly added step-projection case ran against the previously compiled runtime-api, before the generated title catalogue change was rebuilt. Its second fixture also used a shortened title rather than the real block title; correct it to the catalogue title. Preserve the failed XML in `build/log-coverage-first-results/` and the original build log. Final incremental Release production/test compilation and all 47 targeted JVM checks passed (46 Studio and one runtime-service enum-coverage case). Evidence: `build/log-coverage-android-final.log` and `build/log-coverage-results.json`. Targeted diff whitespace checks passed. No further code edits follow the successful checks.

These are host checks and source compilation, not actual device UI, crash/ANR, touch or ARM acceptance. No user project or private script is executed. Existing online 0.1.40 ARM64 bytes, server configuration, credentials and project backups are unchanged. Gradle's existing dependencies may rebuild the internal Runner template; no Studio delivery package is requested.
