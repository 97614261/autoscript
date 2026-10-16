# Rust 图像二值阈值 · 2026-10-14

## 实现

- 复用已有 `Screen.transform`（opcode 5010）和任务所有帧，不新增帧注册表、
  脚本 API 或外部组件依赖。
- 新增 `mode=threshold`，先按既有整数权重计算灰度，再与 `threshold`（0–255，
  缺省128）比较；输出通道为0或255，Alpha原样保留，源帧不变。
- 旧的五字段 transform 参数仍可编译，缺少 threshold 时使用128；未知字段、
  越界阈值、非缩放模式的非零尺寸继续拒绝。
- 可视化积木、节点 schema、API 文档和生成契约已同步更新。

## 验证

- `runtime-executor` 图像扩展测试：5 passed、0 failed；覆盖 Alpha、源帧不变、
  取消、旋转/镜像/缩放/灰度以及新增 threshold 的黑白输出。
- `flow-compiler` 图像/工具测试：11 passed、0 failed；覆盖 threshold 合法值、
  旧参数兼容和256越界拒绝。
- 严格 Clippy（runtime-executor、flow-compiler）、API 生成检查和 schema 检查通过：
  522 JSON documents、185 schemas、159 API functions、175 blocks。
- `git diff --check` 通过。

## 边界

该能力只证明 Rust CPU 像素语义和编译/运行契约，不证明 Android Bitmap 预乘Alpha、
设备性能、OpenCV/ONNX provider 或真实 Root 截图链路。更多滤波、形态学和设备验收
仍按长期计划单独设计，不能从此项推断已经完成。
