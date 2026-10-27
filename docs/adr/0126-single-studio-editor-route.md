# ADR 0126 Single Studio editor route

Date: 2026-10-19
Status: accepted

## Decision

Studio editing has one production owner: `EditorOverlayService` hosts
`VisualProjectScreen`. Project-list Edit, restore and capture handoff all enter
that service. The project page must not maintain another program tree, source
editor, clipboard, input picker, image configuration or editor-run implementation.

The former `floatingEditorProject` state in ProjectPage was initialized to null
and had no non-null assignment. Its handlers were reachable only from that same
unreachable UI. Remove that disconnected duplicate, rather than introducing a
shared abstraction with a second unused consumer. Also remove the in-app editor
branch reachable only from the disconnected file picker. Do not remove shared
dialogs, runtime contracts, historical project backup or user data.

Project Launch remains distinct from editor testing. A project with a designed
interface opens ScriptLaunchOverlayService; one without an interface compiles
the saved project and starts the frozen runtime plan with the formal control
ball. The project page retains real settings, UI designer, packaging, backup and
import/export routes. Editor Run/Step, combined draft validation, source editing
and capture ownership remain in the existing editor session.

## Evidence and release status

Keep generated schema/compiler coverage separate from executed tests and device
acceptance. A generated row must not state all tests are pending after suite
execution, nor mark all parameters accepted from a partial suite. Link the current
release ledger; keep dated failed attempts and known OCR ambiguity intact.

The arm64 candidate is Debug signed. Packaging and host checks do not imply a
device installation or performance pass. No production OCR model/strategy change,
optional downloader, independent Runner expansion or user-data migration occurs.
