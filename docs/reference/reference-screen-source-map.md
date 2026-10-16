# 参考源码与当前实现定位

功能参考一键玩和易编精灵的事件代码与布局，不能仅凭截图推断。资料/ 为本地参考，不参与构建；不复制其品牌、账号服务、二进制资源或未知 Native 语义。

## 参考实现入口

| 主题 | 参考文件 |
|---|---|
| 项目列表与编辑启动 | 新版 DashboardFragment / bc1；旧版 developers.editScript / FloatingShow / FloatingCreateScript |
| 悬浮编辑与吸附 | service_tk.xml / service_tk_ball.xml / item_tree_list.xml；旧版 FloatingView |
| 函数与变量 | service_tk_functionui.xml；旧版 guagua_fun_main.xml / funType / funName |
| 图像配置 | service_tk_image_recognition_control.xml / service_tk_image_recognition_page_image.xml；mm0 / bm0 |
| 脚本界面 | activity_visual_ui.xml / visual_drag_common_controls.xml / visual_right_panel.xml；旧工程 .pz |
| 插件组织与工具 | service_tk_ywj_xz_cz.xml / service_tk_new_file.xml；旧版 guagua_create_plugin.xml / myCreateScriptClickEvent |

## 当前 Studio 源码

Kotlin 根目录为 android/apps/studio-android/src/main/java/com/autoscript/studio/，包名仍为 com.autoscript.studio。

| 职责目录 | 主要入口 |
|---|---|
| navigation | MainActivity.kt / StudioEntryModel.kt / ProfileScreens.kt |
| project | ProjectPage.kt / ProjectSettingsDialog.kt / PluginFileManagerDialog.kt |
| editor | EditorDock.kt / VisualProjectScreen.kt / FunctionLibraryDialog.kt |
| image | VisualImageRecognitionDialog.kt / ImageToolWindow.kt / ImageTemplateSaveDialog.kt |
| input | VisualInputActionDialog.kt / InputPointEditing.kt |
| designer | ProjectInterfacePreview.kt / RunnerUiDesignerScreen.kt |
| runtime | RuntimeLogDialog.kt / RuntimeEnvironmentScreen.kt / SoftwarePermissions.kt |
| packaging | DevicePackagingController.kt / RunnerApkSizeReport.kt |

具体文件路径以 [源码映射](../source-layout-map.json) 为准；UI 不能直接调用 Lua、RootDaemon、OpenCV 或 ONNX 实现。

## 最新行为优先

编辑直接展开系统悬浮窗，仅最小化显示灵动环球；编辑器运行保存后直接执行，项目列表启动先配置。图像按找图/颜色/文字分类，基础输出可见，帧变量在高级设置。历史 Lua 编辑页已退休，但数据/备份/引擎兼容保留。受限插件源码与可视化数据共用校验，不解析执行任意 Lua。

这些最新行为由 [开发约束](../../AGENTS.md) 和 ADR0039–0055 定义，不按旧版界面逐像素替代已确认的新交互。未知旧算法、返回状态、控件单位继续列为转换阻断，见 [转换说明](../guides/legacy-project-conversion.md)。
