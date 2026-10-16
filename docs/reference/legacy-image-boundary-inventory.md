# Legacy image source boundary inventory

Date: 2026-10-13. Static source review, not legacy runtime acceptance.

## One-key-play reference

Local source set: `资料/易编精灵/16_yijianwan分析/sources/sources/com/yijianwan/kaifaban/`.

- `guagua/ccalljava/CCallJava.java`, `BmpToPng` and `ImageRotation` around lines 842-858: Java delegates to BitmapHelper/Util; `.bmp` can fall back to `.bmg`. These wrappers do not prove a native template-match algorithm or a gray-score definition.
- `guagua/develop/remote/BitmapHelper.java`, `ImageCut` around lines 400-419: decodes a file, calls Bitmap.createBitmap and writes JPEG at quality100. This is lossy encoding, not original-pixel PNG crop; do not copy it into template saving.
- `saveBitmapToPngFile` around line421 explicitly uses PNG. Keep image encoding distinct from geometric cropping and displayed zoom.
- `guagua/floating/FloatingImageBiDui.java` contains image/screenshot zoom and ROI/match overlays; `FloatingImageLvSe.java` uses a generated BMP magnifier preview. Adopt precise selection/visual feedback, not shared temporary screenshot files.

## Yibian reference

Local source set: `资料/易编精灵/01_反编译源码/sources/sources/`.

- `com/yibianjingling/kaifaban/service/capture/imageprocess/ImageProcessingCanvasView.java` lines30-38 enables zoom/fit and disables bitmap filtering. Lines75-79 convert display touch coordinates to the original image and reject points outside it.
- Lines126-137 clone the mask into undo history, cap at10 and free oldest snapshots. This specifically proves bounded mask-edit history, not arbitrary image-algorithm undo coverage.
- Lines155-181 distinguish mask erase (`PorterDuff.CLEAR`) from draw (`SRC`), and keep source, mask and processed bitmap roles separate. Mask brushes are a separately scoped future workbench option; they are not silently added as a gray matching capability.
- `service/view/ZoomableImageView.java` exposes original/display rectangles and preserving-transform image updates. `service/capture/color/CaptureColorMagnifierGridView.java` draws an enlarged pixel grid. Adopt one original-pixel/display-coordinate mapping and do not let filtered previews alter saved pixels.
- `10_页面布局/apktool_out/res/layout/activity_apk_build.xml` and `dialog_apk_plugin_selection.xml` show selective external APK plugin packaging and installed/downloaded/imported states. They do not prove independently selectable OpenCV/ONNX artifact families; those are the new product decision, not a claimed legacy implementation.

## Current implementation to reuse

- [Transform contract](../../schema/api-schema/functions/screen.transform.json): opcode5010, rotate/mirror/gray/nearest-neighbor resize, independent task-owned frame,4194304 pixel budget, source unchanged.
- [Current adapter](../../android/modules/runtime-service/src/main/java/com/autoscript/runtime/service/NativeVisionAdapter.kt): opcode5108 OpenCV gray SQDIFF matching; opcode6101 ONNX recognition. The original shared OpenCV preprocessing has now been replaced with independently checked BGR/bilinear preprocessing; optional backends are separate source sets. Synthetic parity is not all-device acceptance.
- [Size report](../../android/apps/studio-android/src/main/java/com/autoscript/studio/packaging/RunnerApkSizeReport.kt): raw and compressed component sizes are distinct; ZIP layout/signature/padding is separate. A nested Runner is counted as one outer entry, not also as its inner libraries.

No old native source, benchmark or undecompiled method is presented as proven algorithm equivalence. Existing grayscale/rotation/crop contracts will not be renamed just to inflate the function catalog.
