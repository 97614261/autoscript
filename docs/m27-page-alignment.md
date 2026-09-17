# M27 页面对齐计划：逐页核对新版易编精灵 / 旧版一键玩源码

- 状态：B0–B5 + 源码逐页核对已实施；**主机门禁 2026-09-16 通过，设备验收未做**
- 编写日期：2026-09-15（2026-09-16 按构建结果回填）
- 基线：`wip/m27` @ `7ecc784` + 未提交工作树
- 参考真相：`资料/易编精灵/10_页面布局/apktool_out`（新版 XML/strings/colors/assets）、`资料/易编精灵/16_yijianwan分析`（旧版 XML/sources）、`资料/截图/`（用户真机照片，1280×2772）、`10_页面布局/截图/`、`17_yijianwan截图/`、`18_插件弹窗/`

## 0. 结论先行

- 页面骨架已经基本按新版 XML 落地（工作台、创建弹窗、悬浮程序树、运行环境、备份、打包、学习、后台、设计器）。差异集中在四类：**图标全是 Canvas/文字替代**、**若干尺寸/颜色偏差**、**三个页面结构缺块**（首页轮播、打包"应用外观"与更新/日志页、我的页头）、**四个弹窗还是 mock 或 M3 默认样式**（文件、源文件管理行、编辑窗口、插件管理）。
- 有 3 个不是"对齐"而是"功能诚实性"的问题必须一起处理：路由回退落到不存在于参考产品的"项目文件页"、文件弹窗展示硬编码假数据、备份槽位永远"尚未备份"。
- 前置阻塞（已解决）：`project.rs` 的 Flow 路径策略已重写为"单层 + 中文名"，Schema 与 `ProjectStore` 同步；`cargo test -p flow-ir` 通过。

**实施后状态（2026-09-16）**：B0–B5 全部落地，见第 4 节状态列。主机门禁已通过——`cargo test -p flow-ir`、`schema-check`、全量 `testDebugUnitTest`、Studio/Runner 两个 Debug APK。首次编译共暴露 6 条错误（3 处 KDoc 与 `@Composable` 重复、1 处参数错位、1 处缺 import、1 条我写错的测试断言），已全部修正。**设备验收仍未执行**，页面对齐的实际效果要等 MuMu 截图比对才算数。

## 1. 设计 token 对照（来自 `res/values/colors.xml`）

| 参考名 | 值 | Studio 现状 | 处理 |
|---|---|---|---|
| `app_accent` / `lan3` / `profile_accent` / `visual_editor_accent` | `#3A6EFF` | 多处硬编码 `0xFF3A6EFF`；主题 `primary = #3D6EF7` | 主题 primary 改 `#3A6EFF` |
| `app_accent_dark` | `#2446C7` | 打包按钮已用 | 收进 token |
| `app_accent_soft` / `profile_accent_soft` | `#EAF0FF` | 多处硬编码 | 收进 token |
| `app_border` / `profile_divider` | `#EBEFF5` | 多处硬编码；主题 `outline = #DCE2EC` | 主题 outline 改 `#EBEFF5` |
| `app_page_bg` / `profile_page_bg` | `#F5F7FB` | 一致 | — |
| `app_text_primary` / `profile_text_primary` | `#182033` | 主题 `#172033` | 改 `#182033`（设计器保留 `#172033`） |
| `app_text_secondary` | `#7A8499` | 主题 `onSurfaceVariant = #74819A` | 改 `#7A8499` |
| `app_mint` / `profile_success` | `#22A06B` | ProjectPage 已用 | 收进 token |
| `app_purple` | `#735BFF` | ProjectPage 已用 | 收进 token |
| `profile_danger` | `#E5484D` | 主题 `error = #E84855` | 改 `#E5484D` |
| `profile_warning` | `#F59E0B` | 无 | 新增 |
| 弹窗标题蓝 | `#3D5AFE` | EditorEntryDialogs 已用 | 收进 token |
| 弹窗文字按钮 | `#3F51B5` | 已用 | 收进 token |
| `hs`（分割线） | `#D5D5D5` @ 0.88 | `#E2E5EA` 等 | 统一 |
| `hui2`（弱文字） | `#777777` | `#8A909B` 等 | 统一 |
| `visual_editor_toolbar_icon` | `#46618A` | `#4F6687` | 改 |
| `visual_editor_toolbar_bg` | `#F3F6FB` | 顶栏 `#F7F8FB` | 改 |
| `visual_editor_page_bg` | `#EEF2F7` | 一致 | — |
| `visual_editor_side_bg` / `right_panel_bg` | `#E5EBF3` / `#FFFFFF` | 左栏白 | 左栏容器改 `#E5EBF3`，列表白 |

做法：在 `android/core-designsystem/.../AutoScriptTheme.kt` 增加一个 `AutoScriptPalette` 对象承载上表命名色，本批触碰到的页面改用它；不做全仓库替换。

字符串对照（`strings.xml`）：底部导航 `title_home = 主页`（Studio 现为"首页"，需改）、`title_dashboard = 工作台`、`title_notifications = 我的`；源文件管理 `app_text_50 = 源文件管理`、`app_text_97 = 已选择0项`、`app_text_96 = 全选`、`app_text_113 = 另存为`、`app_text_111 = 添加分组`、`app_text_112 = 移出分组`、`app_text_125 = 选取`、`app_text_114/115 = 源文件/分组`、`app_text_131–134 = 上移/下移/左移/右移`、`app_text_137 = 剪切`、`app_text_141 = 注释`、`app_text_142 = 全选`。

## 2. 逐页差异与改法

状态列：✅ 已对齐、🟡 微调、🔴 结构缺块/需重做、⚠️ 功能诚实性问题。

### 2.1 应用外壳与主导航

| 项 | 参考 | Studio 现状 | 改法 | 文件 |
|---|---|---|---|---|
| 底部导航标签 | `主页 / 工作台 / 我的` | `首页 / 工作台 / 我的` 🟡 | 改"主页"；同步 smoke 脚本 | `StudioNavigation.kt` |
| 导航图标 | 矢量 `ic_home_black_24dp` / `ic_dashboard_black_24dp` / `ic_profile_center_24dp`，22dp，选中 `#3A6EFF` | Canvas 手绘 🟡 | 自绘矢量 drawable（房子/四宫格/人像轮廓），保持 22dp | `MainActivity.kt`、新增 `res/drawable/nav_*.xml` |

### 2.2 主页（`fragment_home.xml` → `assets/web/home/index.html`）🔴

参考是全屏 WebView：外层 `padding 13/18`，两块：
1. **轮播**：圆角 24、边 1px `rgba(74,117,188,.16)`、4 页横向滑动，每页 `padding 23/22/20`，元素依次为 eyebrow 胶囊（10.5sp 加粗、5dp 圆点）、大标题（24–28sp 850）、两行 lead（12.5sp）、底部玻璃"能力卡"（15dp 圆角，左 27dp 标记方块 + 2–3 列 b/small）、右下 `01 / 04` 等宽 9sp、左下圆点指示（活动圆点拉长 21px）。
2. **价值面板**：圆角 22、渐变浅蓝底、标题 14.5sp 830、2×2 tile（最小高 49，圆角 12，左 27dp 标记 `#EAF0FF` 底 `#3A6EFF` 字，右 b 11sp / small 9.2sp）。

Studio `HomePage`：单张静态卡 + 两个多余 `TextButton`（进入工作台/运行环境），没有轮播，价值 tile 排版不同。

改法：`HorizontalPager` 4 页复刻结构；背景不能用参考的插画 jpg，用四套渐变 + 简单几何装饰（春/夏/秋/冬色系取 CSS 里的 `--accent`：`#61A88A` `#218FBD` `#BF7B34` `#668ECF`）。文案按 R0 边界改写：第 3 页"免 Root"改为"Root 运行 · 本机执行"，并注明免 Root 后续；第 4 页 AI 标"预留"。去掉两个多余按钮（参考首页无入口，靠底栏切换）。价值面板 4 tile 文案沿用参考（"免 Root"改"本机运行"）。

### 2.3 工作台（`fragment_dashboard.xml` + `project_tree_1.xml` + `DashboardFragment/bc1`）

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 头部 64dp / 100×36 新建按钮 / 48dp 快捷条 / 34dp"项目列表"标题 | — | ✅ | — |
| 空态 | 52dp 图标框 + 16sp 标题 + 11sp 说明 + **128×38"新建第一个项目"按钮** | 缺按钮 🟡 | 补按钮 |
| 卡片 72dp 行、展开分割线、48dp 六按钮、8sp 标签、颜色 mint/accent/purple/mint/secondary/danger | — | ✅ | — |
| 卡片元信息 | `创建 yyyy-MM-dd HH:mm  ·  720×1280  ·  ID …8位` | ✅ | — |
| 图标 | 矢量 folder / chevron / run / edit / page-template / backup / android / delete | Canvas 手绘 🟡 | 自绘矢量 drawable，尺寸 20/18/16 |
| 快捷条图标 | cloud / book / code-window 22dp | Canvas | 同上 |
| `footer` 形参 | 无 | 未使用的 `footer` 参数 | 删除 |
| 展开动画 | 120ms 高度动画 + 箭头旋转 100ms | 无动画 | `animateContentSize` + 旋转，可选 |

### 2.4 创建项目弹窗（`service_tk_dashboard_create_project.xml` + `ey.java`）

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 42dp 标题栏、12dp 内边距、`#F8FAFC` 分区、36dp 分辨率行、40dp 底部按钮、2dp 圆角、阴影 | — | ✅ | — |
| 分辨率文案 | `720x1280`（ASCII x） | `720×1280` 🟡 | 改 ASCII x（卡片元信息保持 ×，与 `bc1` 一致） |
| 名称长度 | `LengthFilter(30)` | 128 | UI 限 30 |
| 帮助态 | 标题改为居中"创建说明"，左侧出现返回箭头，"?"隐藏 | 标题不变、"?"切换 🟡 | 按参考切换标题与左返回 |
| 项目类型（可视化/Lua） | 参考无 | 藏在帮助面板 | 保留在帮助面板；不改 |

### 2.5 我的（`fragment_profile.xml` + `ProfileFragment` + `ProfileRainHeaderView`）

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 头部 250dp | `ProfileRainHeaderView`：底色 `rgb(3,6,10)` + 右上径向光晕 + 动态雨丝 | 平铺 `#3A6EFF` 🔴 | 改深色底 + 径向渐变 + 静态/轻动画雨丝（Canvas） |
| 头像 76dp 白圈 + 人像图标 | 矢量 | 文字"本" 🟡 | 人像矢量 |
| 标题 21sp 白 / 副标 13sp `#DFFFFFFF` | `登录 / 注册`、`登录后可创建项目并管理个人资料` | ✅ | — |
| "功能服务" 15sp 20dp | ✅ | — | — |
| 卡片 16dp 圆角 1px 描边、72dp 行、42dp 图标框、14sp 标题、11sp 副标、20dp 箭头、72dp 缩进分割 | 行尺寸 ✅；图标是文字 🟡；箭头是 "›" | 四个矢量图标（芯片/群组/文档/下载）+ chevron 矢量 |
| 行内容 | 运行环境；加入QQ群；开发文档 + 离线文档与接口说明；检查更新 + 当前版本 | 第二行改为"社区与反馈"（允许） | 保持 |
| 底部"用户协议 · 隐私政策" 52dp 行 12sp | TextButton 🟡 | 改纯文本可点 |
| 子页 | 无 关于/设置 入口 | `ProfileSubpage.ABOUT/SETTINGS` 无入口（死页） | 关于内容并入"检查更新"页；删除 ABOUT/SETTINGS 或保留但不暴露 |

### 2.6 运行环境（`activity_runtime_environment.xml` + `item_runtime_status.xml`）

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 64dp 蓝头、居中 18sp 标题、48dp 占位 | ✅ | — | — |
| 运行模式卡 20dp 圆角、18dp 内边距、42dp 图标框、16sp 标题 | 图标是"◉"文字 🟡 | 芯片矢量 |
| "重启服务" | 88×38，`bg_runtime_restart_button`（浅蓝底、描边、12sp 加粗 accent） | M3 `OutlinedButton` 🟡 | 自绘 Surface |
| 分组标题 | 15sp 加粗，20dp 边距，24dp 上距 | `titleLarge` 19sp 🟡 | 改 15sp |
| 状态行 | 58dp、8dp 圆点、13sp 标签、12sp 右值（maxWidth 190，两行）、可选 chevron | 紧凑 12dp 间距行，多一个"重新读取服务状态"按钮 🟡 | 改 58dp 行；刷新动作并入"重启服务" |
| 行内容 | 设备 Root 状态 / 截屏服务 / 按键服务 …（红绿点 + 说明） | Runner 服务 / Root / 引擎 / 协议 | 改为：设备 Root 状态、截屏服务(Root screencap)、按键服务(Root input)、Runner 服务、悬浮窗权限、通知权限；值文字用真实状态 |
| 模式选项 | 灰组 `bg_runtime_mode_group` + 白色选中块 | ✅ | — |

### 2.7 备份管理（`activity_backup_management.xml` + `item_backup_project.xml`）⚠️

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 58dp 头、40dp 返回图标、18sp 标题、10sp 用量文字、11sp 提示条 | 返回是 "‹" 文字 🟡 | 矢量返回 |
| 卡片 82dp 行、34dp 图标框(7dp padding 云图标)、14sp 名、9sp 元信息、9sp 状态、右侧 30dp"删除"危险描边按钮 | 40dp 文字图标、`OutlinedButton "备份"` 🟡 | 按参考重排；点击卡片进槽位页；右侧"删除" |
| 导入本地 `.asproject` | 参考无（云端） | `onImport` 已无 UI 入口 ⚠️ | 头部右侧加"导入"文字按钮（本地版必要偏差） |
| 数据来源 | 云端备份列表 | 直接列所有项目、状态永远"未记录" ⚠️ | 列出**有本地槽位备份**的项目；见 2.8 |

### 2.8 备份槽位（`activity_backup_slots.xml` + `item_backup_slot.xml`，用户照片"备份.jpg"）⚠️

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 58dp 头、40dp 返回、18sp 项目名、10sp"可用 3 槽位"、11sp 提示条 | 64dp/21sp 🟡 | 改尺寸 |
| 卡片 minHeight 92：48dp 标题块（12sp 加粗 + 8sp 元信息）、可选 30dp 备注行、40dp 按钮行（三个 28dp：备份/恢复/删除，10sp 加粗；空槽只显示"备份"） | 15sp/10sp、一个 34dp 按钮 🟡 | 改尺寸；占用槽显示三按钮 |
| 底部 58dp 进度面板（10sp 文案 + 4dp 进度条） | 无 | 备份/恢复进行时显示 |
| 持久化 | 云端槽位 | `onBackup` 只是打开系统文件选择器导出 ⚠️ | `ProjectStore` 新增本地槽位：`backups/<projectId>/slot-N.asproject` + `slot-N.json`(时间/大小/备注)，复用 `exportProjectBackup/importProjectBackup`；恢复 = 导入为新项目（现有语义，不覆盖） |

### 2.9 打包应用（`activity_apk_build.xml` + `page_apk_update.xml` + `page_apk_build_log.xml`）🔴⚠️

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 64dp 头、42dp 返回、19sp 标题、50×28 "APK" 胶囊、36dp Tab | ✅ | — | — |
| **应用外观**分区 | 15sp 标题 + 卡片：68dp 行（46dp 图标预览 + 13sp"桌面图标" + 10sp 来源 + 36dp"恢复默认"）+ 42dp"从文件管理器选择图片" | 只有选择按钮 🔴 | 补齐分区 |
| 安装信息 | 10sp 标签、46dp 输入、"历史包名" 28dp | ✅ | 输入改可编辑 |
| 代码与资源保护 | 52dp 两行 + iOS 开关 45×26 | ✅（M3 Switch） | 自绘 iOS 风格开关 |
| 随包插件 | 32dp 行 + **11sp 说明"未勾选插件，打包应用不会携带插件 APK"** | 缺说明行 🟡 | 补 |
| 更新页 | "发布项目更新" 16sp；两个 72dp 版本框；日期/说明 10sp；92dp 更新说明输入；52dp 两个开关行；44dp"发布热更新" | 通用信息卡 🔴 | 按布局复刻，全部禁用并标"未连接后台" |
| 日志页 | 深色 `#181C23` 控制台：44dp 头 + "复制日志" 32dp；1dp `#2A313C`；空态 11sp `#7D8590`；3dp 进度 | 通用信息卡 🔴 | 复刻；日志来源见下 |
| 开始打包 | 服务器辅助打包 | 按钮无 onClick、外观像可用 ⚠️ | R0 无本机打包：按钮禁用态 + 日志页写一条"本机打包未开放，请使用主机 `release-packager`"；不伪造 |

### 2.10 学习项目 / 开发者后台（14 / 15）

- 学习项目：✅，只把"◇"换成书本矢量。
- 开发者后台：参考是 WebView；Studio 复刻了层级。把 "▣ ☷ ♙" 三个文字图标换矢量；其余不动。低优先级。

### 2.11 悬浮程序树（`service_tk.xml` + `service_tk_ball.xml` + `item_tree_list.xml`，用户照片"点编辑.jpg"）

照片证实目标是**新版**白色面板，不是旧版绿色 P01。Studio 已按新版 XML 实现：232dp 宽、35dp 标题栏、右栏 45×25 九按钮、底部 25dp 七图标 + 20dp 缩放柄、35dp 树行、控制台。保留。需要修：

| 项 | 问题 | 改法 | 位置 |
|---|---|---|---|
| 标题栏拖动 | `pointerInput(projectName,"title-drag")` 闭包捕获首次组合时的 `minPanelX/maxPanelX/maxPanelY/resolvedPanelY`，面板缩放或旋转后边界失效（"拖不远"） | `rememberUpdatedState(onDrag)`，边界作为 key 或在回调内重算 | `LegacyScriptDock.kt:315-325, 916-921` |
| 小球吸附 | `onDragEnd` 用组合时捕获的 `resolvedBallX` 判断左右（"回跳"） | 回调内直接读 `ballX` 状态 | `LegacyScriptDock.kt:210-223` |
| 左上菜单 | 参考 `image_density_small` 黑色 tint；照片显示蓝色 | 以照片为准，保持蓝 | — |

### 2.12 源文件管理（`service_tk_ywj_xz_cz.xml` + `item_tree_list_file_1/2/3.xml` + `service_tk_new_file.xml`）🔴⚠️

这是旧版"插件选择/插件分组"（P02/P07）在新版里的对应物，按新版对齐。

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 第 1 行 40dp：标题"源文件管理" 14sp `#3D5AFE` + 菜单图标 | 42dp ✅ | 40dp |
| 搜索行 40dp（可隐藏） | ✅ | — |
| **第 2 行 40dp 常驻**："已选择N项" 13sp 蓝 + "全选" + 复选框（9dp 右距） | 只在多选时替换头部 🔴 | 常驻第二行 |
| 列表行 | 文件夹 45dp（35dp 文件夹图 5/5 边距、名 + 8sp 修改时间、右复选框）；文件 45dp（35dp 缩略/图标、名 + 8sp 时间 + 8sp 大小）；".." 45dp | 58dp、14sp/9sp、"▸"前缀 🟡 | 按三种 item 复刻 |
| 底部普通态 40dp：取消 \| 加入 \| 确定 | ✅ | — |
| 底部多选态 40dp：另存为 / 重命名 / 取消 / 删除 / 添加分组 / **移出分组**（图标 + 8sp） | 52dp、五项、无"移出分组" 🟡 | 六项 40dp |
| 新建弹窗 `service_tk_new_file` | 18sp 标题 25dp 左距、15sp 输入 30/10/30/5、maxLength 30、左"取消"、右"分组"+"源文件"（`#3F51B5`） | ✅（限 40） | 限 30 |
| **持久化** | 真实文件 | `rememberSaveable` 内存态，进出丢失 ⚠️ | ✅ 已接适配层：条目 = 清单 Flow，分组 = `.studio/source-groups.json`；对话框只留选择/排序/搜索等视图状态，写操作全部经 `SourceManagerAction` 交给宿主 |

### 2.13 编辑窗口（`tk_bjck.xml`，底栏"编辑"按钮）🔴

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 顶部 40dp：面包屑 8sp `lan3`（如 `X_s:限次循环(3次)>B_z:循环>…`）+ 隐藏"全选"复选框 + 隐藏"展开/收起选中项" 34dp 图标 | 58dp 原始 JSON 文本 🔴 | 显示选中节点祖先链的中文标题 |
| 列表区 | 树列表复用 `item_tree_list` 35dp | 42dp 行 + "☑/□" 文字 | 复用程序树行组件（复选框显示） |
| 底部两行各 40dp：第 2 行 撤销 / 恢复 / 上移 / 下移 / 左移 / 右移；第 1 行 关闭 / 注释 / 删除 / 复制 / 粘贴 / 剪切（图标 + 8sp `lan3`，左右 15dp 内边距） | 两行 47dp 文字符号 + 额外"修改选中节点参数"行 🔴 | 矢量图标、顺序与文案按 XML；"注释"在 Visual 项目映射为"参数"编辑（保留原值回显） |
| 面板尺寸 | 全屏减 5dp 上下 | 96%×90% | 改 |

### 2.14 文件弹窗（`service_tk_xt_wj_gl_layout.xml`）🔴⚠️

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 40dp 路径行（12sp 蓝，`ellipsize=start`）+ 菜单；搜索行；**常驻第 2 行**"已选择N项 / 全选"；列表；三种底栏：单"取消" / "取消 \| 选取" / 图标行（移动 · 粘贴 · 重命名 · 删除 · 取消 · 复制 · 移动，8sp） | 无第 2 行、只有单"取消" 🔴 | 复刻三种底栏，按模式切换 |
| 数据 | 项目沙箱真实目录 | **硬编码假目录与 2026 日期** ⚠️ | 由 `projectFileCatalog(snapshot)` 驱动：`源文件`(visual/flows) / `Lua`(main.lua) / `图片`(assets/images) / `字库`(dictionaries) / `界面`(runnerUi) / `project.json`；不暴露 `.studio`、`generated` |
| 点击文件 | `.lua` → 文本编辑窗；其它 → 对应工具 | 插入注释片段 | `.lua` → `LuaEditorScreen`；Flow → `VisualProjectScreen`；图片/字库 → 资源设置 |
| 操作 | 复制/移动/粘贴/重命名/删除 | 无 | 走适配层：Flow 用 move/rename/copy；图片/字库用 `importResource/deleteResource`（复制=重新导入）；`main.lua`/`project.json` 只读，按钮禁用并提示原因 |

### 2.15 函数库（新版 `service_tk_functionui.xml` vs 旧版 `guagua_fun_main.xml` / F01–F02）— 需拍板

- 新版：40dp 头（右侧搜索、关闭图标）、1px 分割、三列 RecyclerView（权重 1.2/1.2/0.9，`hs2` 底）、30dp 行黑字居中、搜索态整列替换 + 提示"输入中文函数名、英文函数名或变量名"。**Studio 现状即此结构。**
- 旧版：白色 60dp 标题"变量、函数、判断语句和模版" 18sp `#33AAFF`、右上 30dp "?"、2dp `#6699FF` 分割、三列 80/100/200dp 灰底 `#D3D3D3`、选中紫底、底部全宽 40dp"关闭"；分类固定：变量管理/判断语句/函数库/模版/插件库 → 按键/寻图/文件/网络/其它/界面。
- 推荐：**版式沿用新版**（与照片里的新版面板一致），**分类与命令改为只列本项目已注册的 25 个 API 与积木目录**（`BlockCatalog`/`schema/api-schema`），不再出现 `Net.get`、`Language.shell`、`Accessibility.*` 等未实现片段——现在的 `legacyFunctionCommands` 有 30 余条假命令，插入后要么匹配不到积木，要么违反 R0 边界。

### 2.16 插件管理（旧版 P06 `插件管理` 弹窗）→ 左上菜单"插件"

新版没有对应弹窗，按旧版 P06 复刻：白底、60dp 标题"插件管理" 18sp `#33AAFF`、右上"?"、2dp 蓝分割、8 个单选行（约 45dp、18sp）、底部 40dp"取消 \| 确定"。选项映射到真实能力（仅可视化项目）：

| 旧版选项 | 映射 |
|---|---|
| 插件创建 | `ProjectStore.createFlow` |
| 插件删除 | `deleteFlow`（引用安全检查） |
| 插件另存 | `copyFlow`（新 flowId/rootBlockId） |
| 插件检错 | `runtimeClient.compileVisualProject` 当前 Flow 诊断 |
| 全部插件检错 | 同上，整项目 |
| 插件分组 | 打开源文件管理并进入多选 |
| 存储为模版 | 禁用，"预留" |
| 未调用插件 | 列出未被 `flow.call` 引用的 Flow（需把 `isFlowReferenced` 提到适配层公开） |

Studio 现有 `LegacyToolDialogScreen.PLUGINS` 三行占位删除。

### 2.17 脚本界面设计器（`activity_visual_ui.xml` + `visual_drag_common_controls.xml` + `visual_right_panel.xml`）

| 项 | 参考 | 现状 | 改法 |
|---|---|---|---|
| 40dp 顶/底栏、100dp 左栏、150dp 右栏、画布蓝框 | ✅ | — | — |
| 顶栏图标 tint `#46618A`、8sp 标签 `#172033`；底栏标签用 icon 色 | `#4F6687` / `#303A4A` 🟡 | 改 token |
| 顶栏底色 `#F3F6FB` | `#F7F8FB` 🟡 | 改 |
| 右栏 Tab 30dp（指示 2dp、选中 accent、未选 `#738096`） | 40dp 🟡 | 改 |
| 控件卡：100×30 CardView + compatPadding，**每张在等分单元格内居中**（7 张平分列高） | LazyColumn 20dp 间距 🟡 | `Column` + `weight(1f)` 单元格 |
| 属性编辑 | 右栏切换到属性页（`visual_right_panel_property_page.xml`），"属性"按钮切换 | `AlertDialog` 🔴 | 右栏内属性页（字段 ID/标题/默认值/最小最大/选项/必填/删除） |
| 左栏元素列表行 | `item_visual_ui`/`visual_element_list_item` | 30dp 文本 ✅ | — |

### 2.18 Lua 文本编辑（`service_tk_text_editing.xml`）与全屏可视化编辑器

- 参考的 Lua 编辑是悬浮窗内的深色编辑窗：顶栏 `#242A33`（主题/设置/保存/撤销/恢复/关闭 白色图标）、1dp `#344052`、24dp 文件栏（8sp 文件信息 / 页码 / `UTF-8`）、12sp 代码区。Studio `LuaEditorScreen` 是 M3 浅色 TextButton 工具栏。改法：顶部区域按参考复刻为深色栏；运行/暂停/停止移到悬浮面板底栏（已有）；"校验/缩进"保留在设置图标菜单里。
- `VisualProjectScreen`（全屏积木编辑器）参考产品没有对应页；它由 2.14 的文件弹窗打开 Flow 时进入。本批只把顶栏改成与 `tk_bjck` 一致的 40dp 图标栏，不重做。

### 2.19 项目文件页（`ProjectFilesScreen.kt`）⚠️ 路由问题

参考产品没有此页。当前它是 M3 默认样式，而且会在从"界面/打包/图片/录制"按返回时**意外出现**：这些页面 `onBack` 只清各自标志位，不清 `opened`（`ProjectPage.kt:560/572/581/592`），于是落到 `ProjectPage.kt:637` 的文件页分支。

改法：工具页 `onBack` 同时 `opened = null`；文件入口统一走悬浮面板"文件"弹窗（2.14）；`ProjectFilesScreen.kt` 删除（`ProjectFileCatalog.kt` 保留，供文件弹窗使用）。

✅ 已实施：新增 `leaveOpenedProject()` 统一收口所有工具页的 `onBack`；`ProjectFilesScreen.kt` 已 `git rm`；`ProjectFileCatalog.kt` 保留并顺带修了资源类型判定（清单写的是 `glyphDictionary`，旧代码只认 `glyph_dictionary`，导致字库文件不出现在文件弹窗里）。

## 3. "项目源文件适配层"（2.12/2.14/2.16 的共同后端）

已新增 `apps/studio-android/.../ProjectSourceFiles.kt`（纯 Kotlin，JVM 可测，已有 `ProjectSourceFilesTest`）：

```
data class SourceFileEntry(flowId, name, path, lastModified, sizeBytes, group, isEntry)
data class SourceFileTree(groups: List<SourceGroup>, entries: List<SourceFileEntry>)
data class SourceDeleteResult(snapshot, deleted, failures)
class ProjectSourceFiles(store: ProjectStore) {
  fun load(snapshot): SourceFileTree                            // 清单 Flow + 虚拟分组
  fun createFlow(snapshot, name, group) / renameFlow / copyFlow // 直通 ProjectStore，返回新快照
  fun deleteFlows(snapshot, flowIds): SourceDeleteResult        // 逐个删，失败原因逐条返回
  fun createGroup / renameGroup / deleteGroups / addToGroup / removeFromGroup
  fun unreferencedFlows(snapshot): List<ProjectFlow>
}
```

关键决策（与最初设想不同，按新版源码核对后改）：**分组是虚拟视图，不是目录**。新版易编精灵 `ae1` 把分组存在独立的 `分组配置.json` 里，源文件仍在同一目录，所以这里同样把分组存到 `.studio/source-groups.json`（不进 `project.json`、不进备份、删 Flow 时自动剔除），Flow 路径固定单层 `visual/flows/<名称>.jsonl`。

`flowId` 是引用身份，`path` 只是位置：重命名/移动只改 `path`，`flow.call` 引用不断；`copyFlow` 则必须换新的 `flowId`/`rootBlockId` 并重写全部 `nodeId`/`blockId`，否则副本与原件身份二义。

名称规则三处必须一致，已对齐：`flow-ir::valid_flow_name`、`ProjectStore.isValidFlowName`、`project.schema.json` 的 `path` pattern —— 1–64 码点，字符集为字母、数字、`_`、`-`、CJK `U+4E00–U+9FFF`，`.` 和空格只能在中间（于是 `.`、`..`、隐藏名、路径分隔符和 Windows 保留字符都构造不出来）。

已完成的前置（Batch 0）：
- `engine/crates/flow-ir/src/project.rs`：`valid_flow_path`/`valid_flow_name`/`flow_name_edge_char` + 两条测试（中文单层通过；嵌套/首尾点空格/反斜杠/冒号/片假名/超长拒绝）。`cargo test -p flow-ir` 19 passed。
- `schema/project-schema/project.schema.json`：`path` 改为 `maxLength: 83` + 中文单层 pattern，附 `$comment` 说明三处一致性。注意 `schema-check` 只校验 `$id`/`$ref`，不校验 pattern，所以一致性靠 Rust/Kotlin 测试兜底。
- `ProjectStoreTest.kt`：中文名创建/重命名不断引用、非法名与重名拒绝、复制换身份保结构、分组虚拟化与随 Flow 剔除、备份槽位持久化与"恢复为新项目且不进项目列表"。

## 4. 实施批次（不构建，静态改代码 + JVM 单测）

| 批次 | 内容 | 主要文件 | 状态 |
|---|---|---|---|
| **B0 前置** | 第 3 节三项：Rust 路径策略 + 测试、Schema 同步、ProjectStore 单测 | `project.rs`、`project.schema.json`、`ProjectStoreTest.kt` | ✅ `cargo test -p flow-ir` 19 passed |
| **B1 主导航 + 工作台族** | 2.1 标签/矢量；2.2 首页轮播；2.3 空态按钮/矢量/去 footer；2.4 弹窗微调；2.19 路由修复 + 删文件页；token 对象 | `StudioNavigation.kt`、`MainActivity.kt`、`ProjectPage.kt`、`AutoScriptTheme.kt`、`res/drawable/*` | ✅ 未构建 |
| **B2 悬浮编辑器族** | 2.11 拖动/吸附闭包；2.12 源文件管理 + 适配层接入；2.13 编辑窗口；2.14 文件弹窗真实数据；2.16 插件管理；2.15 函数库 | `LegacyScriptDock.kt`、`LegacySourceManagerDialog.kt`、`EditorEntryDialogs.kt`、新增 `ProjectSourceFiles.kt` + 测试、新增 `LegacyFunctionCatalog.kt` | ✅ 未构建 |
| **B3 我的 / 运行环境 / 备份 / 打包 / 学习 / 后台** | 2.5–2.10；备份槽位持久化 | `MainActivity.kt`(ProfilePage)、`ProfileScreens.kt`、`RuntimeEnvironmentScreen.kt`、`WorkspaceUtilityScreens.kt`、`ProjectBackupSlotsScreen.kt`、`ProjectToolScreens.kt`、`ProjectStore.kt`(+槽位) + 测试 | ✅ 未构建 |
| **B4 设计器 + Lua 编辑器** | 2.17、2.18 | `RunnerUiDesignerScreen.kt`、`LuaEditorScreen.kt`、`VisualProjectScreen.kt`(仅顶栏) | ✅ 未构建 |
| **B5 验证准备** | 重写 `scripts/m27-ui-smoke.ps1`（旧步骤引用的"首页/从项目开始/查看运行环境/登录入口/远程账号尚未开放/隐私政策与用户协议/本地版使用约定/关于 AutoScript/从文件导入备份/本地只读概览/项目文件"多数已不存在）；补悬浮面板、源文件管理、编辑窗口步骤；逐步保存截图 + UIAutomator XML | `scripts/m27-ui-smoke.ps1`、本文件、交接文档 | ✅ |

每批结束：`cargo test -p flow-ir`（仅 B0）、Kotlin 侧只做静态阅读与 JVM 单测源码补齐；**到 B5 结束停下，等用户说"可以构建"**，再跑 `scripts/m27-gate.ps1` → MuMu smoke → 交接文档第 9 节 16 项手工验收。

B5 已完成的静态核对（代替不了编译，但能挡掉大部分低级错误）：
- studio 模块引用的 `R.drawable.*` 全部存在；
- 引用的 `AutoScriptPalette.*`、`AutoScriptPalette.VisualEditor.*`、`AutoScriptDimens.*` 全部存在；
- smoke 脚本断言的每一条中文文字都能在源码里找到；
- 删除的符号（`ProjectFilesScreen`、旧函数目录、`RunnerUiFieldDialog`、`LegacyNewSourceDialog`）没有残留引用。

## 5. 验收方式

1. 构建通过后，在 MuMu 720×1280 逐页截图，与 `10_页面布局/截图/*.png`、`资料/截图/*.jpg`（缩放到 720 宽）并排比对；记录每页"尺寸/颜色/图标/文案"四项差异，输出 `build/m27-*/page-diff.md`。
2. UIAutomator 断言关键文本与可点击区域（新 smoke 脚本）。
3. 功能诚实性专项：源文件新建/重命名/分组/另存为后重启 App 仍在；文件弹窗列表与 `project.json` 一致；备份槽位备份→列表出现→恢复为新项目；打包按钮禁用并有日志说明；从"界面/打包"返回直接回工作台。
4. 交接文档第 9 节 16 项手工清单。

## 6. 已拍板的决策（2026-09-15，用户确认）

1. **函数弹窗版式**：采用**新版 `service_tk_functionui` 版式**，但命令只列本项目真实存在的 25 个脚本 API 与积木目录条目（`LegacyFunctionCatalog.kt`）。原先 30 余条 `Net.get`、`Language.shell`、`Accessibility.*` 之类的假命令已删除——它们插入后要么匹配不到积木，要么直接违反 R0 边界。缺能力的条目按 `project.json.capabilities` 显示为禁用，不伪造可用。
2. **空分组持久化**：核对新版源码后**改为虚拟分组**（不是原推荐的真实目录）。依据：新版 `ae1` 自己就把分组存在 `分组配置.json` 里，源文件并不移动。因此 Flow 路径保持单层，分组存 `.studio/source-groups.json`，空分组可以存在，备份不携带分组。
3. **项目文件页去留**：**删除** `ProjectFilesScreen.kt`，文件入口统一走悬浮面板"文件"弹窗。
4. **本批范围**：**B0→B5 全做完再申请构建**。

## 7. 下一步（等待用户授权）

当前工作树从未执行过 Gradle，因此"能编译"是未知数，必须作为第一道门禁：

```powershell
.\scripts\m27-gate.ps1
.\scripts\m27-ui-smoke.ps1 -StudioApk .\build\m27-<时间戳>\studio-debug.apk `
  -ReportDirectory .\build\m27-<时间戳> -DeviceSerial 127.0.0.1:16384 -ProjectName VisualSmoke
```

smoke 脚本要求工作台里已有一个名为 `VisualSmoke` 的可视化项目，且该项目至少有一个积木节点（"编辑窗口"步骤需要能选中节点）。之后按交接文档第 9 节做 16 项手工验收。

## 8. 源码逐页核对结果（2026-09-15，B5 之后）

核对方法固定为三步，后续任何页面对齐都按这个做，不凭截图猜：

1. `10_页面布局/apktool_out/res/layout/*.xml` 拿几何（dp/sp/颜色/顺序）；
2. 行为看混淆 Java：先在 `res/values/public.xml` 查控件 id 的十六进制，换算成十进制后在 `01_反编译源码/sources` 里 grep `findViewById(<十进制>)`，定位到 ViewHolder/Adapter（例：`bt_backup_slot_restore` = `0x7f0800b3` = `2131230899` → `x/ek.java` → `x/dk.java`）；
3. 文案只认 `res/values/strings.xml` 的明文。**参考 App 的大部分中文文案是运行时 `wt.y(密文, 密钥)` 解密的，反编译读不到**，这部分只能对照截图。

### 8.1 核对范围与结果

18 个页面/弹窗全部按上面方法核过：8 个零差异（工作台壳、我的、运行环境、备份管理、打包、Lua 编辑窗、源文件行、新建弹窗），8 个只有 1–2 处小偏差，2 个有结构差异（源文件管理面板尺寸、文件弹窗底栏项数）。色板 `app_*` 6 个、`visual_editor_*` 14 个、`hs/hs2/hui2/lan3` 与 `AutoScriptPalette` 逐个一致；`strings.xml` 16 条明文文案逐字一致。

### 8.2 已修正的差异

| 页面 | 差异 | 修法 |
|---|---|---|
| 全局 | 参考 58 个布局共 204 处 `1.0px` 发线，我们一律写成 `1.dp`（MuMu 粗一倍、真机粗三倍） | 设计系统新增 `hairline()`（1px 按密度换算），**只改参考写 `1px` 的地方**：源文件管理、编辑窗口、函数库、加入位置弹窗、我的（含卡描边）、创建项目弹窗。参考写 `1.0dp` 的（文件弹窗、项目卡、Lua 编辑窗、运行环境卡描边）保持不动 |
| 全局 | `hs` 是 `#E1D5D5D5`（88% alpha），`Divider` 写成了不透明；另有散落的 `#E2E2E2`、`#E1E4E9` | `Divider = 0xE1D5D5D5`，新增 `DividerSoft = hs2 #F5F5F5`，硬编码全部收回 token |
| 主导航 | 参考选中标签 14sp、`itemRippleColor` 透明 | 选中 14sp / 未选 12sp；`indication = null` |
| 项目卡 | 图标内径 20dp，参考 32−8×2=16dp | 16dp |
| 删除项目 | 裸 `AlertDialog`；参考 `service_tk_dashboard_delete_project` 是 48dp 红标题 + 项目名 + `8 − 3 = ?` 算术验证 + 64dp 双按钮 | 新 `ProjectDeleteDialog`，答错只提示不关闭，答对才删 |
| 运行环境 | 第二组标题 marginTop 参考 20、我们 22 | `RuntimeSectionLabel(topPadding)` |
| 备份槽位 | 备注行逻辑反了（`dk.java`：占用槽位恒显示，空备注填默认文案）；三个按钮都无二次确认；`backupToSlot(remark)` 是死代码 | 备注行按 `occupied` 显示；新增 `SlotConfirmDialog`（按 `service_tk_prompt_dialog` 复刻），备份那次带备注输入，`onBackup(slot, remark)` 真正传下去 |
| 源文件管理 | 面板 96%×90% + 限宽 520；底栏项 padding 12 | `fillMaxSize().padding(vertical = 5.dp)`（参考根布局 paddingTop/Bottom 5）；padding 15 |
| 程序树行 | 文字 `#202839`，参考 `#000000` | `Color.Black`（悬浮面板与编辑窗口两处） |
| 函数库 | 面板 96% 宽；列间竖线 `#E1E4E9`，参考是 `hs2` 同底色 | 满宽；`DividerSoft` |
| 文件弹窗 | 多选底栏 5 项，参考 7 项 | 按 `line_tk_xt_wj_gl_layout_4` 顺序补齐：移动·粘贴·重命名·删除·取消·复制·移动（无安全语义的保持禁用） |
| 设计器 | 底栏标签色应为 `toolbar_icon`；“坐标显示”缺 monospace；控件卡图标应 tint `lan3` | `DesignerToolbarItem(bottomBar)`；`FontFamily.Monospace`；tint 按可用性取 `Accent`/`TextSecondary` |
| 小球 | 缺 `float_ball_stop_content`（运行中 25dp 停止键） | `LegacyScriptDock(running, onStop)`，三个宿主按各自 `canStop` 接入；ProjectPage 新增 `stopRunningProject()` |

### 8.2b 核对后追加的两批改动（构建前）

| 范围 | 参考 | 改法 |
|---|---|---|
| 提示/确认弹窗族 | `service_tk_prompt_dialog`（18sp `#304ffe` 标题、右对齐 `#2962ff` 取消/确定）、`service_tk_new_file`、悬浮面板“加入位置”样式 | 新增 `LegacyPromptDialogs.kt`：`LegacyPromptDialog` / `LegacyInputDialog` / `LegacyOptionDialog<T>`。替换掉 ProjectPage 三个（插入位置/缩进分支/粘贴位置）、VisualProjectScreen 四个（新建 Flow + 同上三个）、LuaEditorScreen 一个（放弃修改）裸 `AlertDialog`。单选弹窗改为“先选后确定”，与参考一致 |
| 悬浮面板控制台 | `line_tk_console_container`；性能行默认 GONE | **发现三个宿主都没有把 `consoleLines` 传给 dock**，控制台永远是空占位，直接违反验收第 12 项。新增 `RuntimeConsoleLog`（有界 200 行、只记变化字段、带时间戳），在 `StudioApp` 里跟 `RuntimeClient.onStateChanged` 绑定，经 ProjectPage → Lua/Visual 编辑器 → dock 贯通；删掉硬编码的 `CPU --%` 假性能行；空态文案改为如实说明“脚本 `Log(...)` 输出流尚未接到 Studio”。附 `RuntimeConsoleLogTest` 四条用例 |

### 8.2c 悬浮面板弹窗的“假 API”清理

核对 `schema/api-schema/functions`（25 个）后发现，多个按参考版式复刻的弹窗生成的 Lua 片段调用了**本项目不存在**的函数：调试页 `Log.info` / `Debug.checkpoint`，图像页 `Vision.findImage/findColor/findText/findTextOnnx`，工具页 `Capture.open`，循环页 `Runtime.getLoopCount/getLoopElapsedMs`，常用页 `Runtime.setParameter/getParameter/…`。可视化项目会落到积木选择器所以没事，Lua 项目会原样写进 `main.lua`，运行时 `attempt to index global 'Vision'`。

| 处理 | 说明 |
|---|---|
| `LuaSnippetGate` | 新增统一守门：允许的命名空间从 `LegacyFunctionCatalog.luaGroups()` 真实片段推导（Task/Input/Screen/Ocr/Legacy/Math/System），字符串与注释里的调用不计。`LuaEditorScreen.onInsert` 与 ProjectPage 悬浮编辑器的 Lua 路径都先过它，拒绝时给出“`X.y` 不在当前脚本 API 契约里”的提示。附 `LuaSnippetGateTest` |
| 图像页 | 三个真实页改为生成 `Screen.loadImage + Screen.findImage`、`Screen.findMultiColor`、`Ocr.loadDictionary + Ocr.glyph` 的真实签名（相似度换算为千分位）；ONNX 页标红说明属于后续阶段，不生成调用 |
| 工具页 | “屏幕截图”改为插入 `Screen.capture()` |
| 调试页 | 去掉 `Log.info` 插入，“加入”按钮不再显示；七个开关全部禁用并标“（未开放）”；开发环境页选中项改为 Root（原来错选“系统录屏”） |
| 变量检查 / 变量信息 | 原来显示“当前未发现变量问题”“暂无运行变量”这类假结果，改为说明能力未接入 |
| 循环页 / 常用页 | `Runtime.*` 片段未改写（没有对应能力），由守门拒绝并提示 |
| 积木选择器 | VisualProjectScreen 顶栏“添加积木”原来是 M3 `AlertDialog` + `TextButton` 分类；改为复用悬浮面板的 `LegacyFunctionLibrary`（`service_tk_functionui` 三列版式），插入语义（子槽位、自动保存、能力检查）保持不变 |

### 8.2d 设备截图并排比对的结果（2026-09-16，20 张）

smoke 8/8 通过后，把 `m27-ui-shots\*.png` 与 `10_页面布局\截图\`、`资料\截图\` 逐张比对。

**零差异**：工作台（含项目卡展开）、悬浮面板、设计器三栏结构、运行环境卡片与模式组、打包页分区、我的页功能行。其中工作台与悬浮面板几乎逐像素一致。

**已修的差异**：

| 问题 | 证据 | 修法 |
|---|---|---|
| 打包页“恢复默认”“历史包名”文字重叠 | 参考单行；我们外层 `height(36dp)` + 文字折行 → 第二行被裁 | `maxLines = 1, softWrap = false` |
| 浅蓝按钮缺描边 | `bg_runtime_restart_button` / 备份槽位按钮在参考里是浅蓝底 **+ 轮廓** | 运行环境“重启服务”加 1dp accent 描边；`SlotButton` 三种样式统一按前景色 55% alpha 描边，禁用态去描边 |
| 设计器保留了状态栏 | 参考横屏截图无状态栏，画布顶到屏幕边 | 进入时 `WindowInsetsControllerCompat.hide(statusBars())`，退出恢复，与横屏切换同一个 `DisposableEffect` |
| 我的页顶部一条突兀的浅色带 | 参考雨丝头部画到屏幕最上沿，状态栏深色底 + 白图标 | 新增 `SystemBarScope`：进入页面把状态栏染成 `ProfileHeader` 并切白图标，离开精确还原。**不动 `decorFitsSystemWindows`**——edge-to-edge 会改变全部 20 个已对齐页面的 inset，风险远大于收益 |
| 悬浮面板盖住项目列表 | 参考面板约占屏高 37%，我们 324dp 在 640dp 屏上占 50%；且最小高度也写成 324dp，缩放柄拖不动 | 默认 300dp，最小 260dp |
| `systemUiVisibility` 与新 API 混用 | `SystemBarScope` 要切图标明暗，两套机制会互相覆盖 | `MainActivity` 改用 `WindowInsetsControllerCompat`，去掉废弃调用 |

**“参考单行、我们折行”已定位并修复**：`font_scale=1.0`、`density=320` 均为标准值（现已由 smoke 每轮记入 `deviceInfo`），排除设备设置。真因是参考 `assets/web/home/index.html` 的 `h1` 规则里有 `letter-spacing: -.045em`，我抄了 `font-size` 却漏了字距，宽出 4.5%，刚好把一行挤成两行。标题（`-.045em`）和价值面板标题（`-.025em`）都已补上，截图确认恢复单行。眉标和页码的字距当初抄对了，所以只有这两处出问题。

### 8.2e 两个反复出现的错误模式（值得记住）

1. **抄 CSS 只抄 font-size，漏掉同一条规则里的 letter-spacing。** 三处折行都是这个原因。以后从 HTML 复刻文字，字号、字重、字距要一起看。
2. **用 `padding(vertical)` 给固定高度里的文字做垂直居中。** 它减少可用空间而不是移动内容：`height(36dp).padding(vertical=11dp)` 把内容框压到 14dp，10sp 的行高放不下，字被上下裁掉，看起来像两行重叠。正确写法是 `height(36dp).padding(horizontal=…).wrapContentHeight()`。这个 bug 我第一次判断成“横向换行”，加 `maxLines/softWrap` 没修好，是第二轮截图才证明没修对的。

### 8.3 核对后仍保留的有意差异

- 编辑窗口方向图标：参考用同一张 `image_direction` 旋转 90/270/0/180，我们用 `ic_arrow_right_24` 旋转，基准朝向不同，旋转值不可直接比，**留到 MuMu 截图确认**。
- 创建项目弹窗的“?”是文字不是矢量（`image_dashboard_help_24`），视觉差异小，暂不动。
- 文件弹窗七项底栏在 360dp 宽屏上总宽约 350dp，与参考一样贴边；参考也没做滚动，保持一致。

### 8.4 顺带修正的文档错误

`docs/m27-ui-plan.md` 第 3 节把项目卡展开栏写成七个（多了“设置”），`project_tree_1.xml` 和我们的实现都是六个，已改。
