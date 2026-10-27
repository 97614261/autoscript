# 数学文本数据补强与验证

日期：2026-10-19。

简单玩公开API对照后的第一批已接通九个API与九种积木：Math.unary、Math.binary、Math.roundTo、Text.formatNumber、Text.regexReplace、Text.filter、Text.reverse、Data.encode、Data.sort。目录现为172个API、189种积木。配置、显式变量、保存、编译、运行及可逆源码使用同一契约；[使用说明](../guides/script-utilities.md#数学文本数据补强)和[ADR0131](../adr/0131-bounded-math-text-data-utilities.md)保留语义与预算。

## 已通过的代码检查

| 检查 | 结果 |
|---|---|
| flow-compiler 单元测试 | 128通过 |
| runtime-executor 单元测试 | 117通过、1项既有忽略 |
| lua-runtime 单元测试 | 10通过 |
| script-api 单元测试 | 2通过 |
| 上述四crate严格Clippy | `--lib -- -D warnings`通过，不扩大为整个工作区全目标结论 |
| Rust格式化 | cargo fmt通过 |
| schema-check | 563份JSON、199个schema、172个API、189种积木通过 |
| API生成快照 | api-codegen generate/check通过 |
| Studio单元测试 | 826项：825通过、1项既有跳过，0失败/错误 |
| Studio Lint | 0错误，37警告、14提示 |
| 文档站 | 15测试通过；172个API页面、183个HTML页面、35953个本地链接/资源检查通过 |
| 构建安装 | build.cmd -Verify通过，包含双ABI Debug与MuMu保留数据安装 |
| 定向测试APK | assembleDebugAndroidTest通过 |

测试覆盖数学定义域、有限数/结果上限、整数/浮点来源、位数与补零、UTF-8、字面替换、正则1024次和输出膨胀、Unicode类别与反转、JSON转义/深度/键预算、混合数组拒绝、能力拒绝和真实Lua包装。新操作不依赖Android宿主实现，不引入依赖或放宽文件/模型边界。

## MuMu实际验收

设备：MuMu 0，`127.0.0.1:16384`，API32、x86_64、720×1280。只创建并运行自有临时工程，未运行私人脚本。

使用`pureUtilityOnly=true`执行StudioAcceptanceInstrumentation：11项全部通过，报告`pass=true`、`cleanup=true`。

- 九种真实参数窗均能打开、确认并完整保留类型化参数。
- 九种积木源码导出/解析保留结构与参数，工程备份恢复保留插件原文。
- 编译并通过设备Runtime执行，逐项核对数学、格式化、替换、过滤、反转、JSON编码和排序结果。
- 所属运行正常停止，临时工程及恢复工程清理，App恢复首页。

这些结果不是九种工具全部参数组合的真机认证，也不表示已实现全部简单玩API。主机边界测试和设备定向用例分别列明，不据此声称性能提升。

## 安装包

路径：`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`。

- 安装身份：`com.zhigou.kaifaban`。
- 长度：174142541字节；ZIP有效压缩载荷174000146字节，布局/签名/填充142395字节，通过16MiB门禁。
- SHA-256：`43633e6fc8c911f931563cab4ffa72cac25b8ee0342b63e1dd8e432db44b95a6`。
- arm64-v8a与x86_64、默认full视觉配置；原OCR模型摘要保持不变。
- 用户明确批准后只删除`previous-81be966148a0470f8b9d5356524b0c82.apk`旧自动备份，未删除项目/图片/脚本或其他备份。重建前候选包由原脚本验证备份。

本地原始记录位于忽略的build目录：pure-utilities-rust-tests.log、pure-utilities-android-build.log、pure-utilities-test-apk.log、pure-utilities-mumu.log。它们不作为仓库必需链接。

## 仍在本批之外

本批是[公开API对照](../reference/jdwan-api-comparison-2026-10-19.md)推荐顺序的第一批，不是全部差项收尾。图像便捷操作、平台只读查询、文件/编码扩展及需要独立设计的HTTP/交互窗尚未由本批实现。URL/HEX/文本Base64、第N处标记、通用Lua表编码、正则捕获替换及对象字段排序也不在这九个契约内；延期或另项范围保持原决定。
