# Both runtime JNI and the Studio-only compiler bind to this fixed Kotlin class name.
-keep class com.autoscript.engine.jni.NativeEngineBridge { *; }
-keep class com.autoscript.engine.jni.NativeEngineBridge$Companion { *; }
