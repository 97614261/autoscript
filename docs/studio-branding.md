# 智构 · 灵动环

用户于 2026-10-01 确认名称“智构”和“灵动环”方案，并进一步确认编辑与运行浮球保持一致，点击浮球展开暂停/继续、停止。不改变 `com.autoscript.studio`、项目私有目录或 Runner 模板的桌面名称。

## 资源与显示

- `apps/studio-android/src/main/res/values/branding.xml`：名称和固定白色底色。
- `apps/studio-android/src/main/res/drawable-nodpi/zhigou_brand_mark.png`：1254×1254 透明 PNG，蓝紫渐变和带面立体质感，中间孔洞保留 alpha；App 与吸附球共用此文件，不依赖生成工具缓存路径。
- API 24/25 桌面资源为 48dp 图层、40dp 标志；普通与圆形底板分别提供。
- API 26+ 使用自适应图标，前景四边 20% 留白，以便系统圆形/圆角方形遮罩与动效。API 33+ 的主题图标复用同一前景 alpha 轮廓，由系统着色；正常桌面图标和吸附球仍为蓝紫 3D，不另画不一致的单色标志。
- 吸附球沿用 35dp 尺寸、拖动与贴边逻辑，白色底板、1dp 淡边框和 2dp 内边距；空闲点击恢复编辑器，运行点击展开暂停/继续、停止。编辑面板底栏运行时显示暂停和独立停止，暂停后显示继续；普通暂停不再要求单步位置。
- 系统运行浮球使用 `android/runtime-service/src/main/res/drawable-nodpi/zhigou_brand_mark.png` 的同版素材，独立 Runtime 无需依赖 Studio；两份源码素材由 `BrandingResourceTest` 检查逐字节一致，Studio 资源合并时使用同名资源。旧 AS 文本和常驻相机不再用于普通运行，相机只在显式截图模式出现。

## 素材来源

使用内置图像生成工具，从用户确认的右侧“灵动环”参考图提取正式素材，再进行透明底清理；未引入在线依赖或外部图库。

最终清理提示词：

> Precise production-asset cleanup edit. Keep the ribbon mark's shape, crossing order, blue-cyan-violet colors and 3D shading EXACTLY unchanged. Remove the isolated gray speck at the extreme lower-right outside the mark, and remove every stray pixel or halo outside the ribbon contour. Make the surrounding canvas and central hole fully transparent alpha. Reframe into a SQUARE canvas by adding transparent top/bottom space, without stretching the mark. Center the ribbon optically; mark occupies about 80% of square width, with ample transparent padding. SINGLE symbol only. No background plate, no labels, no cast shadow, no checkerboard drawn in the image. Export a clean transparent PNG logo, not a design board. This is cleanup, not a redesign.

提示词的 80% 是生成目标；实际显示留白由 XML/Compose 控制，不假定生成工具精确执行尺寸比例。PNG 原尺寸解码约 6MiB；各浮球按 View/Compose 生命周期加载同一标志，不新增每帧解码、动画或引擎工作。

验证使用 `build.cmd -Verify`：Studio 单元测试、Lint、Debug 构建及 MuMu 0 号实例覆盖安装。桌面缓存若延迟刷新，可返回桌面或重开启动器；不卸载或清除用户数据。

初始品牌批次验证：`BUILD SUCCESSFUL`（36 秒），资源回归测试 2 项通过；Lint 0 错误，60 警告（其中 v26 图标缺少 monochrome 的 2 项提示，v33 已提供专用资源）。MuMu 0（`127.0.0.1:16385`）覆盖安装成功，保留原有数据。未进行人工逐项点击或系统主题切换实测。

运行浮球修复后的完整验证：`build.cmd -Verify` 成功（1 分 54 秒），品牌资源测试 3 项通过；Studio、Runtime 客户端、Runtime 服务 Lint 均为 0 错误，原有警告仍存在。MuMu 0 再次覆盖安装成功，保留数据。另补验 Runtime 客户端/服务单元测试和独立 Runner Kotlin 编译；启动等待测试 3 项、运行浮球状态测试 2 项通过。首次点击启动、暂停/继续和截图模式隔离仍需用户在设备上确认，未做人工逐项点击实测。
