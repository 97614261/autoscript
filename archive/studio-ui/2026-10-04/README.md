# 独立 Lua 编辑器归档

依据 ADR 0040：已删除 ProjectPage 中唯一的 LuaEditorScreen 生产入口。

| 文件 | 原目录 |
| --- | --- |
| LuaEditorScreen.kt | apps/studio-android/src/main/java/com/autoscript/studio/ |
| LuaEditorState.kt | apps/studio-android/src/main/java/com/autoscript/studio/ |
| LuaEditorStateTest.kt | apps/studio-android/src/test/java/com/autoscript/studio/ |

归档不在任何 Gradle sourceSets 中。恢复时移回原目录，复核现有契约，再显式恢复 ProjectPage 路由和项目模式入口；不能只移动文件就宣称恢复可用。清理前基线为 Git 09bcb6e。

LuaSnippetGate、ProjectSourceFiles、函数契约、截图工具、受限插件源码编辑器、历史 ProjectStore 数据和备份、Lua 引擎与 Runner 保持活跃。它们不属于旧编辑器专属页面，不能一起删除。历史用户工程未移动、改写或删除。
