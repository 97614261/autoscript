# Root 认证加速依赖核对

日期：2026-10-23；对应[ADR0143](../adr/0143-arm-authentication-and-shared-capture-storage.md)。

继续使用 RustCrypto `sha2 0.10.9`，不替换 HMAC-SHA256 或自行实现加密。该版本的 AArch64 SHA256 硬件分支受 `asm` feature 控制；原构建未启用，设备虽报告 SHA2 仍走软件压缩。

ADR0143最初仅 `target_os=android && target_arch=aarch64` 开启 feature。传递依赖 `sha2-asm 0.6.4`（锁文件固定版本和注册表摘要）来自同一 RustCrypto 项目，许可证MIT，声明本库需通过sha2调用而非直接使用。该0.6系列不是最新sha2大版本，不据此声称已升级所有密码学依赖。cc是既有构建依赖，无新增运行网络、动态下载或SDK。

源代码核对：sha2的AArch64选择经`cpufeatures 0.2.17`查询`getauxval(AT_HWCAP)`及`HWCAP_SHA2`，不支持时调用原软件实现。Android的getauxval自API18提供，低于本项目minSdk24；最终仍须实际构建和设备验证。没有向整个目标传`+sha2`/`+crypto`强制CPU指令。上游局部汇编/内建指令受运行时检测保护，本仓库不新增unsafe。Windows继续不启用sha2-asm。

同日ADR0146在独立MuMu协议/CPU对照后扩展到Android x86_64。sha2的SHA/SSE检测仍在，没有SHA指令时改用sha2-asm中的通用x64标量汇编，不强制启用SHA/AVX等指令，不替换算法、密钥或摘要格式。该上游汇编原MIT许可已经随包携带；不增加新的下载或运行组件。公开RFC4231、篡改/重放及流结构测试与完整截图测量分开；结果见[续优报告](../verification/capture-pipeline-2026-10-23.md)。

MIT原许可随runtime-service的`assets/licenses/SHA2-ASM-LICENSE.txt`打入App及运行模板。实际压缩体积、两ABI产物和测试结果见[执行报告](../verification/capture-hotpath-2026-10-23.md)，不把静态库大小当APK增量。

验证包括RFC4231标准向量、篡改/重放/关联及流结束检查；同一ARM设备分别运行运行时检测版和`sha2/force-soft`独立测试可执行文件。强制软件测试不是不支持SHA2设备的实测，不扩大为原生/API24验收。认证CPU微基准不含截图/Socket，不能冒充完整流程耗时。
