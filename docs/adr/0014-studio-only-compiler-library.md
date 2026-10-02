# ADR 0014：可视化编译器仅由 Studio 分发

状态：已实施并通过主机/打包验证。日期：2026-09-29。

## 问题

共享 engine-jni 直接依赖 flow-compiler，导致 Runner 虽然只读取冻结 Lua，仍打包了编辑器编译器。这违反 Runner 不依赖编译工具链的约束。

## 决策

- 将现有真实编译/草稿校验及 JNI 边界移到 tools/studio-compiler-jni；不是复制实现或创建占位模块。
- android/studio-compiler 只分发这个库，只有 Studio 依赖它；共享 engine-jni 移除 flow-compiler/flow-ir 依赖。
- 继续沿用原 runtime-api → runtime-service 编译调用、项目目录校验和响应格式，UI 不直接接触 JNI。
- 服务在需要编译时尝试加载固定名 studio_compiler_jni；Runner 没有这个库时明确返回编译器不可用，不调用缺失 native 符号、不崩溃。
- 不改变用户打包命令、APK 名称、ABI、minSdk、冻结 Lua 或项目迁移。

## 验证

编译器原单测随实现移动；Cargo dependency tree 检查 engine-jni 不再依赖编译器；分别构建 Studio/Runner 并检查 APK 中只有 Studio 存在 libstudio_compiler_jni.so。JNI 异常/panic 仍由边界转成诊断；设备执行不在本轮验收范围。
