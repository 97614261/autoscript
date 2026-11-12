# ADR 0204: 扩展识别的多边形范围与发布

日期：2026-11-04

用户要求继续补齐灰度找图、多尺度找图和特征匹配的多边形范围，完成后打包并发布线上。沿用 ADR0203 的顶点编辑、原图坐标和严格几何规则；OCR 仍使用矩形。

## 行为

- 三类表单均可截图框选、调整多边形、回填和恢复矩形。改变固定矩形边界会清除旧多边形；回填保留阈值、输出变量及其余参数。
- 灰度找图在合法模板落点中选择最佳匹配，多尺度找图逐档筛选缩放后模板的完整范围，范围过滤发生在接受结果之前。凹形缺口不能被模板横跨。
- 特征匹配只在选区内部提取来源 ORB 点，最终目标的完整外接矩形也必须容纳于选区；不只检查中心或四角。该规则较旋转四边形保守，避免跨越凹形缺口。
- 未设置多边形的旧节点保留原矩形路径、算法顺序与结果格式。仍由原截图策略决定每次截图或共用图像，不引入截图帧缓存。

## 兼容与资源

- 灰度和特征 Lua 接口在末尾增加可选多边形文本；多尺度接口在原 options JSON 内增加可选 `polygon`。保存和草稿沿原 runtimeApi 1.8 提升及 `Screen.polygonRegionVersion` 检查。
- 保持 JNI dispatch 方法描述符不变，原五项 ROI/阈值后可带有界顶点后缀。旧组件必须显式声明支持，否则返回 `POLYGON_REGION_UNSUPPORTED`，不忽略范围；组件加载与会话包装器转发此能力。原 SPI 版本保持不变。
- Java 与 Rust 使用相同的像素中心扫描行规则。灰度模板合法落点先做扫描行交集，特征提取使用选区 mask；几何计算有预算及取消检查，保留原帧归属、算法预算与资源释放。

## 定向检查

- `build/polygon-extensions-rust-final.log`：19 项多边形定向检查通过，覆盖三类编译/API 与边界、缩放继续下一档、灰度/特征请求的帧归属与顶点传递，以及原几何场景。
- 原路径回归：`polygon-extensions-native-regression.log` 7 项、`polygon-extensions-scaled-regression.log` 2 项、`polygon-extensions-native-lua.log` 1 项通过。
- 首轮灰度测试夹具重复使用结果变量触发原校验，修正为独立变量后通过，首轮失败日志保留。
- `build/polygon-extensions-docs.log`：203 API、225 HTML 页和 49328 本地链接/资源构建检查通过。
- `build/polygon-extensions-android-first.log`：Release 生产及单测源码编译、45 项定向检查通过，无失败或忽略，覆盖截图回填/表单、扫描行和模板范围一致性、旧组件拒绝及包装器能力透传。

## 实际发布

- Studio `0.1.35` / `versionCode 36`，沿原开发签名、full/V4 双架构优化构建及 `packageRelease --rerun` 压实；原包体、签名、对齐和原生库/模型检查通过。构建日志为 `build/polygon-extensions-arm64-distribution.log` 与 `build/polygon-extensions-x86-distribution.log`。
- ARM64：36700190 字节，SHA256 `b58daadf03d50c24cc464979f839d477c0bf67e800f470806218aef38f43d1e3`。
- x86_64：51962842 字节，SHA256 `b9ef7739f399fd9b5c23e90b064a13fa4013d892f8b3e641e7f896e328479c2a`。
- 先签名预览，再按持续授权仅将最旧 `0.1.31` 双架构包移至可恢复归档 `archive-20261104T132739Z-4dc2c37bbaa7c8db`，保留其余历史及全部旧归档。
- 原双向 HMAC HTTP 脚本已发布主 ARM64 与备选 x86_64。公网 no-store 清单原摘要与签名修订一致：`64f4145dd970c4d409c57ac7d85279de8007a3d029bc30598255fe562052a4f6`，下载列表共 8 包、354005008 字节。
- `build/polygon-extensions-online-final.log`：两个包完整下载的摘要/大小/MIME、校验文件、HTTP Range 及下载页 200 均通过。

本轮包含先前累计的本地 App 改动，但未部署账号后台或文档站源指南。不把主机几何/模拟后端检查或发布检查当作真机 OpenCV、设备触摸或横屏键盘验收；未安装设备、运行私人脚本、提交 Git 或修改服务器配置、密钥、项目备份。
