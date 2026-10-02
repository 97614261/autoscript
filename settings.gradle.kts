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
include(":android:script-ui")
include(":android:auth-api")
include(":android:auth-local")
include(":android:runtime-api")
include(":android:engine-jni")
include(":android:studio-compiler")
include(":android:runtime-client")
include(":android:runtime-service")
include(":android:project-store")
