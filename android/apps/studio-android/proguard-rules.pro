# These fields are persisted JSON, including objects allocated through Gson/Unsafe.
# Keep the narrow storage/UI model packages until explicit adapters replace reflection.
-keep,allowoptimization class com.autoscript.project.store.** { <fields>; <init>(...); }
-keep,allowoptimization class com.autoscript.script.ui.UiPage { *; }
-keep,allowoptimization class com.autoscript.script.ui.UiPresentation { *; }
-keep,allowoptimization class com.autoscript.script.ui.UiField { *; }
-keep,allowoptimization class com.autoscript.script.ui.ScriptUiDefinition { *; }
-keep,allowoptimization class com.autoscript.studio.UiDesignerCheckpoint { *; }
-keep,allowoptimization class com.autoscript.studio.RunnerUiDesignerDraft { *; }
-keep,allowoptimization class com.autoscript.studio.RunnerUiFieldDraft { *; }
-keep,allowoptimization enum com.autoscript.studio.RunnerUiControlKind { *; }
-keep,allowoptimization enum com.autoscript.script.ui.UiControl { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
