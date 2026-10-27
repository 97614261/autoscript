import groovy.json.JsonOutput

val kind = when (project.name) {
    "vision-opencv-component" -> "opencv"
    "vision-onnx-component" -> "onnx"
    else -> error("Unknown fixed vision component project")
}
val sdkVersion = if (kind == "opencv") "4.10.0" else "1.19.2"
val componentApplicationId = "com.zhigou.component.$kind"
val providerClass = "com.autoscript.runtime.service.components.${if (kind == "opencv") "OptionalOpenCvProvider" else "OptionalOnnxProvider"}"
val abis = rootProject.extra["autoscript.abis"] as List<*>
val generatedAssets = layout.buildDirectory.dir("generated/component-assets")
val spiSource = rootProject.file("modules/vision-spi/src/main/java/com/autoscript/runtime/service/NativeVisionProvider.kt")
val generateVisionComponent = tasks.register("generateVisionComponent") {
    inputs.file(spiSource)
    inputs.property("kind", kind); inputs.property("sdkVersion", sdkVersion); inputs.property("abis", abis)
    outputs.dir(generatedAssets)
    doLast {
        val source = spiSource.readText()
        val spiVersion = Regex("const val VERSION = ([0-9]+)").find(source)!!.groupValues[1].toInt()
        val licenses = if (kind == "opencv") listOf("assets/vision/OpenCV-LICENSE.txt") else
            listOf("assets/vision/ONNXRuntime-LICENSE.txt", "assets/vision/PaddleOCR-LICENSE.txt", "assets/vision/NOTICE.txt")
        val descriptor = linkedMapOf<String, Any>("formatVersion" to 1, "kind" to kind, "componentVersion" to spiVersion,
            "spiVersion" to spiVersion, "sdkVersion" to sdkVersion, "applicationId" to componentApplicationId,
            "versionCode" to spiVersion, "versionName" to sdkVersion, "minSdk" to 24, "abis" to abis,
            "providerClass" to providerClass, "licenses" to licenses)
        if (kind == "onnx") descriptor["model"] = mapOf("path" to "assets/vision/en_PP-OCRv4_rec_mobile.onnx",
            "size" to Regex("const val MODEL_BYTES = ([0-9_]+)").find(source)!!.groupValues[1].replace("_", "").toInt(),
            "sha256" to Regex("const val MODEL_SHA256 = \"([0-9a-f]{64})\"").find(source)!!.groupValues[1])
        if (kind == "onnx") descriptor["candidateModel"] = mapOf("path" to "assets/vision/en_PP-OCRv5_rec_mobile.onnx",
            "size" to Regex("const val V5_BYTES = ([0-9_]+)").find(source)!!.groupValues[1].replace("_", "").toInt(),
            "sha256" to Regex("const val V5_SHA256 = \"([0-9a-f]{64})\"").find(source)!!.groupValues[1])
        val file = generatedAssets.get().file("packaging/vision-component.json").asFile
        file.parentFile.mkdirs(); file.writeText(JsonOutput.toJson(descriptor))
    }
}
tasks.named("preBuild").configure { dependsOn(generateVisionComponent) }
dependencies {
    add("implementation", project(":android:vision-spi"))
    add("implementation", if (kind == "opencv") "org.opencv:opencv:$sdkVersion" else "com.microsoft.onnxruntime:onnxruntime-android:$sdkVersion")
    add("testImplementation", "junit:junit:4.13.2")
}
