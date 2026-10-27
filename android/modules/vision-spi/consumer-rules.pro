# Fixed signed provider APKs use the original host SPI JVM identities.
-keep class com.autoscript.runtime.service.NativeVision** { *; }
-keep class com.autoscript.runtime.service.VisionBuffers** { *; }
-keep class com.autoscript.runtime.service.OnnxImagePreprocessing** { *; }
-keep class com.autoscript.runtime.service.OnnxImageInput { *; }
-keep class com.autoscript.runtime.service.VisionRect { *; }
