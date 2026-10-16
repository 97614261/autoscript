# Android工作区

- apps/：智构Studio与独立Runner。
- modules/：共享Android模块；逻辑Gradle身份仍为:android:*。
- branding/：用户提供的Logo原稿、确定性导出PNG/GIF及资源摘要；仅brand-ui中的母版/遮罩进入App，完整导出集不打包。
- gradle/、settings.gradle.kts、build.gradle.kts、gradle.properties及标准gradlew：本工作区配置。
- local.properties、.gradle/、.kotlin/及各模块build/是本地配置/缓存，不入Git。

Android Studio打开本目录。日常打包仍从仓库根使用`build.cmd`或`build-mobile.cmd`；根`gradlew.bat`和`gradlew`转发到本工程。
直接在此运行Gradle时，schema、examples和rust位于上一级；手机配置须传绝对的仓库`build/profiles/arm64/.gradle`缓存路径。

开发APK位于apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk；手机隔离产物仍在仓库build/profiles/arm64/。
依赖边界与位置见[ADR0057](../docs/adr/0057-contained-android-workspace.md)，根入口见[项目说明](../README.md)。
