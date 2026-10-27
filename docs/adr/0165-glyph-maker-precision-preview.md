# ADR 0165: Fixed glyph authoring preview and correction

Date: 2026-10-26

## Decision

- Reuse the sampling alpha/color membership and comparison limits for a debounced, cancellable black/white preview. Preview never changes the source used for picking colors, sampling or saving. Only the exact current image, ROI and palette may display a generated mask. Owned masks are released on replacement/close.
- Add an expandable pixel selection editor: move the entire rectangle within image bounds, adjust individual edges by one pixel, or apply validated X/Y/width/height. Incomplete or invalid input does not change the actual ROI. No resampling or format change.
- Request GLYPH_DETAILS from the real Rust preview engine. Only current SUCCESS/NOT_FOUND replies with strictly decoded coordinates display boxes. Red indicates unknown shapes; orange indicates a recognized score below 980 permille as an authoring hint, without changing the recognition threshold. Selecting a box can return to the original ROI for explicit resampling; labels are never guessed.
- Each detail row is an explicit selection target with an accessible index and state; its coordinate/score text remains copyable.
- Retain ASGLYPH v1, existing resource save/overwrite confirmation, pending label protection and original pixels.
- Device QA reproduced append-and-test reading the previous composition's pending batch. Both its guard and proof snapshot now read the current batch so the just-appended samples can be tested immediately and the fresh reply stays current.
- Native custom-width dialog panels use a full-height viewport and explicit keyboard resize, matching their existing overlay/legacy host behavior. This prevents the platform from panning the entire authoring panel, including its fixed header/footer, behind a landscape keyboard. The original outside-dismiss and system-bar behavior stays in the shared host.
- MuMu API32 reported IME visibility with a zero native-dialog IME inset while the visible window frame ended 352 pixels above its bottom. When the IME is explicitly visible, the host also uses that observed frame to inset only the content still overlapped. It consumes the applied padding so a resized root or Compose IME padding cannot subtract the same region again; hidden/floating keyboards do not create a bottom gap.

## Validation Scope

The user authorized completion of the four authoring improvements including a concentrated MuMu 0 QA pass. Use only synthetic owned projects; retain app data, restore rotation and keyboard state. No private scripts, physical/remote device, production version change or online publication. Host checks cover mask equivalence and ROI boundaries; device checks cover preview, correction, labeling and same-name saves.

## Results

- 36 authoring host checks and 17 keyboard/panel geometry checks passed. Final Debug production and instrumentation APK builds passed; this is not a new Release or ARM-device acceptance claim.
- On MuMu 0/API32, all 23 authoring cases passed, including a real landscape QA keyboard, field scrolling, fixed save controls, keyboard dismissal, portrait restoration, same-name save confirmation and recognition through the saved dictionary.
- The combined attempt retained its failing palette record. After correcting stale UiAutomation node-cache reads and test targeting, the five palette cases passed separately on the identical production APK, including real ordinary/detailed block execution. Production APK SHA256: `9ad48c0da45405bce37e4677bb4a092630abd7afa9ee521b9645cdfc155a5436`.
- A system-service freeze required the single MuMu restart explicitly approved by the user. Failed attempts were retained. Owned projects were cleaned up, the original Sogou IME and orientation were restored, and the QA keyboard was disabled. No production version bump or online publication.
