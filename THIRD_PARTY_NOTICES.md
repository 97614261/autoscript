# Third-party notices

当前源码尚未内置模型、OpenCV、ONNX Runtime或其他二进制第三方制品。

| 组件 | 版本 | 许可证 | 用途 |
|---|---:|---|---|
| serde / serde_derive | 1.0.229 | MIT OR Apache-2.0 | API Schema反序列化 |
| serde_json | 1.0.151 | MIT OR Apache-2.0 | JSON契约及确定性快照 |
| sha2 | 0.10.9 | MIT OR Apache-2.0 | 生成物与Flow源的SHA-256一致性摘要 |
| fs4 | 1.1.0 | MIT OR Apache-2.0 | 跨平台、进程崩溃自动释放的生成事务文件锁 |
| mlua | 0.12.1 | MIT | PUC Lua 5.4的安全Rust嵌入接口 |
| lua-src / PUC Lua | 551.0.2 / 5.4.9 | MIT | vendored脚本解析与运行虚拟机（仅启用lua54） |
| jni | 0.21.1 | MIT OR Apache-2.0 | Rust与Android Runtime Service之间的窄JNI边界 |
| jni-sys | 0.3.0 | MIT OR Apache-2.0 | JNI原始类型声明（由jni传递引入） |
| cesu8 | 1.1.0 | MIT OR Apache-2.0 | JNI字符串编码支持（由jni传递引入） |
| combine | 4.6.7 | MIT | JNI签名解析支持（由jni传递引入） |
| Gson | 2.11.0 | Apache-2.0 | Android Studio端项目清单JSON编解码 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | Studio端项目文件操作调度到IO线程 |

Gradle、AndroidX与Kotlin依赖的许可证清单将在发布物依赖报告接入后由构建任务生成；新增依赖必须先锁定版本和许可证。

任何来自参考产品的界面资源进入发布包前，必须记录来源与用户确认的授权范围；证书、签名密钥、账号、Token和服务端配置禁止复制。
