-keepclassmembers class com.autoscript.runtime.service.NativeWakeListener {
    public void onNativeWake();
    public void onNativeRootDisconnected();
}

# RootDaemon loads this fixed entry from the installed Studio/Runner APK, not through app UI.
-keep class com.autoscript.runtime.service.RootInputBridge { public static void main(java.lang.String[]); }
-keep class com.autoscript.runtime.service.RootCaptureBridge { public static void main(java.lang.String[]); }
-keep class com.autoscript.runtime.service.NativeVisionAdapter { public byte[] dispatch(long,int,java.nio.ByteBuffer,int[],java.nio.ByteBuffer,int[],int[]); public void cancel(long); }
-keep class com.autoscript.runtime.service.NativeScriptUiAdapter { public byte[] dispatch(long,java.lang.String,java.lang.String,java.lang.String); public void cancel(long); public void cancelEvent(long); }
-keep class com.autoscript.runtime.service.NativeDataFileAdapter { public byte[] dispatch(long,int,java.lang.String,byte[]); public void cancel(long); }

# SDK JNI code also resolves Java types/constructors/members by their original names.
# Do not infer reachability solely from Kotlin callers when shrinking a full build.
-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
