# 截图页面与参考实现映射

本清单是 UI 重建的验收输入。每个页面先读对应反编译布局、绘制资源和事件代码，再在 Studio 中以自有代码重建；不复制参考产品名称、服务地址、账号逻辑或二进制资源。

## 新版易编精灵（01–15）

| 截图 | 参考布局与行为代码 | Studio 落点 |
|---|---|---|
| 01 首页宣传页 | `fragment_home.xml`、`HomeFragment.java`（WebView 内容容器） | `MainActivity.kt` / 首页模块 |
| 02 注册页 | `RegisterActivity.java`、账号注册布局 | `ProfileScreens.kt` 本地预留注册入口 |
| 03 开发者文档 | 文档 Web Activity 与首页链接处理 | 内置文档页，不接参考远端服务 |
| 04 法律文档 | `LegalDocumentActivity.java` | `ProfileScreens.kt` 法律页 |
| 05 登录页 | `LoginActivity.java` | `ProfileScreens.kt` 本地登录预留页 |
| 07 我的 | `fragment_profile.xml`、`ProfileFragment.java` | `ProfileScreens.kt` |
| 08 / 08b 工作台 | `fragment_dashboard.xml`、`project_tree_1.xml`、`DashboardFragment.java`、`bc1.java` | `ProjectPage.kt` |
| 09 创建项目 | `service_tk_dashboard_create_project.xml`、`ey.java` | `ProjectPage.kt` / `ProjectStore.kt` |
| 10 可视化设计器 | `activity_visual_ui.xml`、`VisualUiActivity.java` | `VisualProjectScreen.kt` / `RunnerUiDesignerScreen.kt` |
| 11 打包器 | `activity_apk_build.xml`、`ApkBuildActivity.java` | `ProjectToolScreens.kt` |
| 12 运行环境 | `activity_runtime_environment.xml`、`RuntimeEnvironmentActivity.java` | `RuntimeEnvironmentScreen.kt` |
| 13 备份管理 | `activity_backup_management.xml`、`BackupManagementActivity.java` | `WorkspaceUtilityScreens.kt` |
| 14 学习项目 | `activity_learning_projects.xml`、`LearningProjectsActivity.java` | `WorkspaceUtilityScreens.kt` |
| 15 开发者后台 | `BackendManagementActivity.java` 与后台浏览器布局 | `WorkspaceUtilityScreens.kt`，本地预留，不接参考站点 |

## 旧版一键玩（yj / F / P）

| 截图 | 参考布局与行为代码 | Studio 落点 |
|---|---|---|
| yjA / yjB / yjD | `activity_main_my.xml`、`activity_main_deve.xml`、`developers.java` | 我的与工作台基础页 |
| yjG | `guagua_create_script_view.xml`、`developers.createScript()` | 新建项目弹窗；真实创建落入 `ProjectStore` |
| yjH / yjI | `guagua_items_developer_script.xml`、`developers.getScriptList()`、`developers.editScript()` | 项目列表与直接编辑入口 |
| yjJ | `FloatingShow.createCreateScriptWindow()`、`FloatingCreateScript.java`、`FloatingView.java` | `LegacyScriptDock.kt`：编辑后显示边缘工具柄，点击展开面板 |
| F01–F12 | `guagua_fun_main.xml`、`funType.java`、`funName.java` 与函数子项实现 | `LegacyScriptDock.kt` 函数库；命令插入 Lua 编辑器或转入积木目录 |
| P01–P12 | `FloatingCreateScript.java`、`myCreateScriptClickEvent.java`、`guagua_create_plugin.xml`、录制/插件/调试布局 | 后续按录制、插件、调试三个功能批次落入运行时与编辑器 |

## 已确认的编辑器事件链

旧版的实际行为不是点击“编辑”直接出现完整面板：

`developers.editScript()` → `FloatingShow.createCreateScriptWindow()` → `FloatingCreateScript`；收起后由 `FloatingShow.createSmallWindow()` / `FloatingView` 保存位置、吸附边缘，点小窗再调用 `showCreateScriptWindow()`。

Studio 保留这一交互次序，但第一版将它实现为应用内工具柄与面板，避免未授权时申请系统悬浮窗权限。工具柄已接入 Lua 与积木编辑入口；函数库的选择会插入 Lua 片段，积木项目会打开现有积木目录供选择等效节点。

## 2026-09-13 第二轮源码核对

- `01` 不是普通 Android 内容布局：`fragment_home.xml` 只有全屏 `WebView` 与加载遮罩，真实页面来自 `assets/web/home/index.html` 及轮播图片。因此 Compose 首页只能复用其栅格、间距和信息层级，不能把 `fragment_home.xml` 误当成宣传卡布局。
- `02/03/04/05/15` 同样由本地或受限 Web 容器承载。Studio 保留顶部栏、搜索/缩放工具和卡片层级，但不打包参考产品品牌、账号桥、服务地址及后台页面。
- `07` 已按 `250dp` 头部、`72dp` 功能行、`42dp` 功能图标、`16dp` 卡片圆角与 `#3a6eff` 主色重新校准。
- `10` 已按 `40dp` 顶/底栏、`100dp` 左栏、`150dp` 全高右栏重新约束；进入页面横屏，退出恢复竖屏。
- `11` 已补齐应用图标、应用名、包名、输出路径、项目保护、`.sue` 转换、随包插件和固定底部构建按钮；初始状态不显示安装、分享、发布更新。
- `12` 已移除标题栏内错误的“重启服务”，恢复标题居中及右侧 `48dp` 占位；重启入口位于运行模式卡，模式选项使用灰色分组与白色选中块。
- `13` 已移除布局中不存在的顶部导入按钮，恢复 `58dp` 标题栏、槽位用量、说明条和备份项目卡层级。本地版的卡片操作仍走自有备份实现。
- `14` 已恢复 `56dp` 蓝色标题栏、`2dp` 进度条和仅包含图标/标题的中央空态。
- Material 3 未显式赋值的容器色会落到默认淡紫色，和参考源码白卡不一致；设计系统现已补齐全部 `surfaceContainer*` 色阶，页面卡片统一回到白色与蓝灰背景。
