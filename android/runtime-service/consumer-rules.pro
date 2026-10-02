-keepclassmembers class com.autoscript.runtime.service.NativeWakeListener {
    public void onNativeWake();
}

# RootDaemon loads this fixed entry from the installed Studio/Runner APK, not through app UI.
-keep class com.autoscript.runtime.service.RootInputBridge { public static void main(java.lang.String[]); }
-keep class com.autoscript.runtime.service.NativeVisionAdapter { public byte[] dispatch(long,int,java.nio.ByteBuffer,int[],java.nio.ByteBuffer,int[],int[]); public void cancel(long); }
