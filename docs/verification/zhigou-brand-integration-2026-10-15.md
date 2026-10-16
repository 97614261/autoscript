# 智构新Logo接入 · 2026-10-15

按用户上传SVG和资源展示板接入实际App，不使用生成展示图裁切代替生产资产。决定见[ADR0116](../adr/0116-shared-zhigou-logo-assets.md)。

## 交付

- SVG原稿及嵌入PNG摘要、透明母版、九种尺寸应用图标/透明图、四种浮球、深浅色/圆形/圆角/单色/线性、四状态、三种真正GIF和原分区展示板已生成，见[品牌目录](../../android/branding/README.md)。共42项资源摘要（40导出文件、2生产PNG），全部摘要校验通过。
- 原SVG SHA-256：`d526bdb9a1b2296ce804f4c3537990924b2ef3b1fc6205489b6fdabe8b1c7b1e`。源文件为位图包装，不宣称矢量路径；只清除低彩度半透明白雾，不重设计轮廓/比例/左右速度线。
- App图标（含API24/25、圆形、自适应、深色及Android13单色遮罩）、首页、我的、编辑器图标、编辑/配置吸附球、运行控制及录制入口已接同源资源。旧的两份重复PNG已由唯一brand-ui母版取代，未删用户项目。
- 运行态与暂停/失败严格区分；完成光效仅在明确正常完成且既有短提示仍存活的控制球上播放，不为动画创建/保留窗口或延迟停止。泛化STOPPED不冒充成功。首页只展示视觉，不读项目或增加入口。
- GIF为256px：idle43帧、running48帧、success48帧，总时长均2400ms，帧间隔50ms；idle/running循环、success一次。App不播放GIF，使用取消友好的View动画，隐藏/分离停止；APK只包含母版及遮罩。
- 资源包：`build/zhigou-brand-resources-2026-10-15-v2.zip`，仅作交付展示资源，不进入App或项目备份。

## 实际验证

- 最终full隔离构建通过：1055 tasks，72 executed、983 up-to-date；brand-ui、Studio、runtime-service单测和 Lint Fatal/Error 均通过，生产及测试 APK 构建通过。
- 首轮构建期间源码仍在更新，旧主产物与新测试签名不一致而失败；完整源码固定后重新执行上述门禁通过，首轮不计通过。原日志`build/zhigou-brand-integration-gate.log`和最终日志`build/zhigou-brand-final-gate.log`保留。
- APK177265443字节，SHA-256 `AD5CF145EBA302844F89C3E9CF84B72FAC84500D2F1E0E7D3307789D0E105025`。压缩载荷、布局/签名/填充及双ABI剥离、动态符号、固定ONNX检查通过，无Runner/候选模型。APK内恰好两份品牌PNG且摘要与导出一致，无SVG/GIF全集。
- MuMu 0显式`127.0.0.1:16384`保留数据安装生产及测试包成功；安装后SHA-256复核一致。不清数据、不运行私人脚本、不切输入法；原Sogou默认与QA键盘停用状态保持。
- 新品牌专项5/5通过：图标/遮罩可读取；浅色Logo与外部光晕；待机/执行/运行/完成动效；暂停/失败静态；隐藏和分离后停止动画。`build/zhigou-ball-v2-device-animation.log`完成码-1、pass=true、cleanup=true，仅绘制自身合成View，不截图其他App。
- 基础编辑浮窗专项9/9通过：展开无球、最大化还原、更多不跳动、最小化点球恢复、选中单步、高亮暂停单步继续、停止保留最小化、关闭窗与球、Root点击及新截图。`build/zhigou-ball-v2-overlay-regression.log`完成码-1、pass=true、cleanup=true。未拿之前36项结果冒称新包全量复验。
- Node语法/42资源摘要、PowerShell解析、仓库布局与改动源码空白/冲突检查通过；日常Verify及CI加入brand-ui单测。

### 动效增量验收

- 悬浮球状态已重新区分：待机慢呼吸；`EXECUTING` 为短周期点火环；`RUNNING` 为双向彗星环；`PAUSED` 为静止琥珀分段环；`FAILED` 为静止红色断环。Logo 轮廓、比例和速度线未改变。
- brand-ui 单测及 Debug/Release 编译通过；加入球体内部背景动画后的 Debug APK SHA-256 为 `CCBBD4F6B463B6BDF1569BC434179916E5539CF54115C82F837ECEBAF392AE9D`，已安装到 MuMu `127.0.0.1:16384`。
- 最新动效专项仍为5/5通过，日志见 `build/zhigou-ball-v6-device-animation.log`；开发 profile 的旧发布副本未被静默替换，因输出恢复检查发现候选包 ZIP 布局预算超限，候选文件已保留供后续收包优化。

## 边界

MuMu/API32未实际展示Android13主题启动器效果，遮罩接线/包资源验证不冒充该设备验收。不同启动器的深色刷新/圆形遮罩、ARM真机/API24仍需对应设备。无包名、脚本格式、算法或生产模型变更，未提交Git。
