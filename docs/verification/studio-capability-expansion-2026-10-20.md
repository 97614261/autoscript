# Studio能力扩展交付与验收

日期：2026-10-20。范围：用户要求的主线1–6及HTTP/网址、交互图片浮窗、特征匹配、新OCR候选、多指、生产输入法。见[执行清单](../reference/studio-capability-expansion-plan.md)、[使用说明](../guides/script-capability-expansion.md)和[ADR0132](../adr/0132-studio-script-capability-expansion.md)。

本记录保留该扩展批次的历史候选和40项结果。后续外置V2、实时时钟、字符评分及新包验收以[六项质量交付](studio-quality-1-6-2026-10-20.md)为准；下文“需内置”描述该历史包，不代表当前V2能力。

结论：本批代码已接通，共200个API、217种积木，比该批前增加28个API/积木。主机检查通过；MuMu有40项不同的合成用例通过，图像增强12项另连续复跑两轮通过。Studio窗口/执行验收32项与独立视觉测试包8项分别记账，不把SDK直接调用当作完整脚本调用，也不把这些结果当作ARM或所有参数的穷尽验收。

## 交付内容

| 范围 | 已接通内容 |
|---|---|
| 文本与私有文件 | URL/HEX/Base64严格UTF-8编解码、第N次查找与提取、私有相对路径工具、普通文件修改时间 |
| 系统与网络 | 有界异步HTTP、系统打开网址、只读设备/权限信息、所属运行常亮租约 |
| 图像与界面 | 真实任务帧元数据、页面/控件只读枚举；复用滤色、字库、处理方案、识别尾部和稳定等待流程 |
| 图片浮窗 | 声明资源、位置/尺寸、真实点击/拖动事件、独立等待通道、关闭/停止清理，脚本显式读取事件并分支 |
| 特征匹配 | 内置OpenCV的ORB/Hamming/RANSAC，明确纹理/透明/ROI预算，配置、预览、方案、加入与编译执行 |
| 新OCR | 固定英文V5可选模型，438类独立解码边界，预览/积木/执行接通；原V4默认不变 |
| 多指 | Root ASI2桥接、0–4编号、同任务触点所有权、完整CANCEL及失败恢复；MuMu实测双指 |
| 生产输入法 | 显式启用/选择、状态查询、4096字节提交、焦点票据和迟到提交拒绝，不读取周边文字或保存输入历史 |

入口使用可视化编辑的函数库搜索及既有图像工作台，不另建旧Lua编辑页。特征预览在找图测试选择“特征”；OCR选择“V5候选”。新能力不自动修改已有项目能力声明，不自动授予系统权限。

## 最终产物

开发包：`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`，182,191,546字节。

SHA256：`6e3ee67eea69e8de628a26a87e46286dbf77e2659ff7dfb1bdeebd2f4e69045f`。

`build.cmd -Verify`完成开发双ABI构建、配置的Android单元测试、Lint、原生/组件/APK检查，保留数据安装到MuMu 0。ZIP压缩条目181,694,137字节，布局/签名/填充497,409字节，通过现有门禁。独立ARM发布包未生成，未安装真机。

已通过ADB回读已安装`base.apk`的SHA256，与上面的最终候选完全一致；不只依据安装命令返回成功。

验收测试包身份与摘要：

| 测试包 | SHA256 |
|---|---|
| `com.zhigou.kaifaban.acceptance` | `6d65a5c54f72df6ec96cffaf18070deb263c4287aecf2a55517f8ea198599557` |
| `com.autoscript.vision.acceptance` | `3466484661169d82cdada972e63fc16e71ce78a579ff2ac1bf515cd8e2d68942` |

## 主机检查

- Schema：647个JSON、227个schema、200个API、217种积木；生成契约和注册清单一致。
- Rust八包：386通过、3项原有忽略、0失败；包含真实Lua工具调用、类型/能力、Root协商及编译器回归。
- 严格Clippy：`flow-compiler`、`runtime-executor`全部target通过`-D warnings`；不宣称整个workspace严格Clippy通过。
- Android重点模块：Studio 829项（1忽略）、runtime-service 168项（1忽略）、runtime-client 33项、script-ui 55项、project-store 89项、vision-components 45项、vision-spi 2项；均0失败。
- Studio Lint：0错误、37警告、14提示，未将既有警告写成零警告。
- VitePress：15项测试通过；200个API页、21分组、211个HTML、47,349个站内链接/资源检查通过。

日志保存在本机`build/expansion-final-rust-tests.log`、`expansion-final-clippy.log`、`expansion-development-final-build-install.log`及`expansion-acceptance-apks.log`；这些是生成证据，不是项目脚本数据。

## MuMu验收

设备为MuMu 0，`127.0.0.1:16384`，API32，720×1280，x86_64。仅使用新建`project-qa-*`及公开合成像素/文字，成功或失败均清理所属测试工程，不运行私人脚本。

| 分组 | 用例 | 结果与证据 |
|---|---:|---|
| 新能力实际编译/运行 | 11 | 编码与文件/帧、HTTP回环、控件元数据不含值、显示/常亮开关、特征/V5预览及编译执行、浮窗点击/拖动、真实双指、双指停止CANCEL及下一运行恢复、生产IME真实提交；`build/expansion-mumu-studio.log` |
| 滤色/字库工作流 | 9 | 取色、边界输入、全屏保留、方案、撤销重做、真实模板匹配、固定字库制作及取字、备份；`build/expansion-mumu-filter.log` |
| 图像增强/稳定识别 | 12 | 蒙版、原像素保存、清理、方案复制/改名/确认删除/载入、坐标保护、差异分析、多尺度、区域等待、编译处理与Root新帧稳定匹配、备份；连续两轮均通过，`build/expansion-mumu-enhancement.log`、`expansion-mumu-enhancement-repeat.log` |
| 固定SDK合成集 | 8 | ORB缩放/旋转几何和无特征返回、5组V4/V5对照；`build/expansion-mumu-vision.log`。OCR此组通过的是边界/测量/默认不变，不代表每组文字准确识别 |

生产输入法只在明确的自有输入框就绪后临时启用，提交`PUBLIC_QA_Abc123`。随后恢复：

```text
default_input_method = com.sohu.inputmethod.sogou.chuizi/com.sohu.inputmethod.sogou.SogouIME
enabled_input_methods = com.sohu.inputmethod.sogou.chuizi/com.sohu.inputmethod.sogou.SogouIME
selected_input_method_subtype = -1
```

智构输入法测试后停用，未在真机或其他输入框提交内容。

验收结束仅卸载两个本次测试包，避免测试服务/键盘混入正式列表；智构主App保留数据并恢复首页。再次复核原输入法、启用列表和子类型仍为上述值；本机测试APK与日志继续保留，便于复跑。

## 实测发现并修复

1. 已加载图片释放误要求截图能力：API/积木/编译/执行改为`core.task`并兼容旧截图声明，保持任务所有权及已释放句柄拒绝；新旧流程均回归。
2. 图片窗口快捷关闭原先不在主线程：等待注册/队列/窗口移除统一到主线程，JNI等待仍独立，真实点击/拖动/关闭通过。
3. 视觉预览的可选期限改变JVM方法签名：恢复固定七参数JNI重载并新增反射签名测试，补事件取消的R8保留；实际编译特征与V5通过。
4. V5预览客户端误要求模板：与服务端共用目标文件分类，两个固定OCR均只需来源图，其他算法仍必须提供精确目标。
5. 额外收紧：IME票据跨线程可见性；常亮使用透明像素而非零窗口alpha，不更改系统休眠设置。

旧图像UI夹具另有就绪与同名按钮歧义：加入真实文本/预览就绪检查、最上层本应用窗口查找，确认动作限定标题范围，普通点击改为真实触摸。保留全部输出/备份/不执行断言，连续复跑，不用“动作被接受”冒充界面已完成。

## OCR与性能边界

固定V5模型7,872,351字节，SHA256 `c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8`；仅内置ONNX/full提供，原V4字典和模型不变。

最后一轮SDK调用中：400×400 ROI、128×128纹理模板的ORB首次40.25ms，随后约7.39–8.77ms；V5首次113.56ms，随后约29.12–37.73ms。都是少量公开像素调用，不含Root截图和完整脚本循环，不据此声称P95或全流程100ms达标。

| 公开文本 | V4 | V5 |
|---|---|---|
| `1234567890` | `124567890` | 正确 |
| `ABCDEFG`、`abcdefg` | 正确 | 正确 |
| `A1b2Z9` | 正确 | `A1b2z9` |
| `Il1O0` | `I100` | `I100` |

因此V5不提升为默认，也不宣传解决全部大小写或l/1混淆；固定数字/字体仍可使用自建字库。历史更大合成集的准确率边界保留在ADR，不以本次5组样本代替准确率门禁。

## 明确保留的边界

- ARM/API24、具体目标App的五指效果、浏览器页面业务加载及跨真实休眠的常亮效果未由本轮MuMu样例认证；0–4编号支持不等于五指已实测。
- 新特征/V5操作目前需内置后端，旧签名外置provider明确拒绝，不自动换算法、换模型、下载或扩大加载范围。
- HTTP仅有界请求，无自动重定向、重试、下载；DNS/SDK不可强制抢占时保留忙槽位，不无限增线程。
- 本批没有独立Runner产品扩展、下载器、账号、市场、云功能或免Root实现，不把API数量当作老版同名调用全部兼容。
- 用户允许旧包清理，本批仅删除development目录4份明确自动APK备份（约750MiB）：`previous-17d16ca4ae7d42a28ce5844fdf9f0ebb.apk`、`previous-ef8b3e82b37748bf89be9fca7016efb3.apk`、`previous-d18f5eafe7d2492eb195cbfb02708fce.apk`、`previous-89961cc31ee54c7bb0c3468fb286dafb.apk`。文件删除无恢复副本，可从源码重新构建；当前APK、项目、图片和脚本未删除。
