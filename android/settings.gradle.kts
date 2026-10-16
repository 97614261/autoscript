pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "autoscript"

include(":apps:studio-android")
include(":apps:runner-template-android")
include(":android:core-model")
include(":android:core-designsystem")
include(":android:brand-ui")
include(":android:script-ui")
include(":android:auth-api")
include(":android:auth-local")
include(":android:runtime-api")
include(":android:engine-jni")
include(":android:studio-compiler")
include(":android:runtime-client")
include(":android:runtime-service")
include(":android:vision-spi")
include(":android:vision-components")
include(":apps:vision-opencv-component")
include(":apps:vision-onnx-component")
include(":android:project-store")

// Keep logical dependency/task identities stable while containing physical modules.
project(":android").projectDir = file("modules")
listOf(
    "core-model", "core-designsystem", "brand-ui", "script-ui", "auth-api", "auth-local",
    "runtime-api", "engine-jni", "studio-compiler", "runtime-client",
    "runtime-service", "project-store", "vision-spi", "vision-components",
).forEach { project(":android:$it").projectDir = file("modules/$it") }
