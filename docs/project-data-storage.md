# 项目私有持久文件

`Files` 使用 `project.json.capabilities` 中显式声明的 `storage.data`，不向已有项目默认授权。Lua 参数只有相对路径和有限 UTF-8 文本；项目身份来自经过会话校验的运行启动请求，Lua 无法改写根目录。

Android 宿主保存于应用私有 `filesDir/script-data/<projectId>/data`。Studio 与 Runner 的 applicationId 不同，因此存储相互独立。源码、资源、签名密钥及应用其他私有文件均不在此根目录中；本目录不通过 Root shell 操作。

## 路径和容量

相对路径最多256 UTF-8字节、16层，禁止绝对路径、空段、`.`、`..`、点前缀、控制字符、反斜杠、冒号及不安全文件名字符，禁止尾部点或空格。仅 `Files.list("")` 可使用空路径。Android 使用 `lstat`、`O_NOFOLLOW`、打开后 `fstat` 检查，拒绝符号链接、特殊文件和多链接普通文件。

每文件上限64 KiB且必须是有效UTF-8。每项目 live data 最多512 KiB、256个文件/目录条目；回收区独立最多512 KiB、256个条目，容量满则拒绝后续删除，不静默清除回收数据。`list` 返回当前目录直接子项的排序JSON字符串数组，目录项以 `/` 结尾；返回最多64 KiB，不列出内部临时文件。

## 执行和恢复

宿主单次仅允许一个文件请求。默认截止2秒，引擎请求还有既定3秒超时；读写在有界块之间检查取消。停止控制不进入文件业务队列，直接触发已注册取消listener；准备新运行、切换项目或销毁服务会失效旧epoch，旧请求不能提交到新项目。平台本地文件系统调用本身不能被强行中断，取消/截止在返回后的下一检查点拒绝结果与提交。

写入采用同目录独占临时文件、`fd.sync()` 和同文件系统原子 `rename`；失败或取消清理暂存文件及本请求新增的空目录，已有目标保持不变。进程被强杀发生在提交前时，目标保持旧内容，可能留下未提交私有临时文件；这些文件占用既有配额且不对Lua列出。这里不宣称断电后的目录元数据持久化保证。

`Files.remove` 只删除明确函数调用指定的单个普通文件，不递归删除目录。宿主原子移动到项目私有 `.trash/<随机ID>/file`，同时保存相对路径journal，供宿主 `ProjectDataStore.restore` 有界恢复；Lua不能访问或清空回收区。当前没有可视化回收管理入口。恢复拒绝覆盖现存目标，并应用相同路径/容量检查。

JNI只调用固定 `dispatch(work, opcode, path, content)` 和 `cancel(work)` 方法，响应是有限UTF-8或固定错误码。变量、内容、路径、异常详情都不写入普通日志；关闭会话释放Java全局listener引用。读写打开时原子加入 close-on-exec，避免私有描述符被后端子进程继承。`ProjectDataDescriptorFlags` 使用经本地NDK两个支持ABI头文件核对的 Linux `O_CLOEXEC=02000000`，不引用API27才公开的 `OsConstants.O_CLOEXEC`，也不使用先open后fcntl的竞态窗口。无需新依赖，兼容minSdk24。

## 回归

`NativeDataFileAdapterTest` 使用真实临时文件检查项目/能力隔离、路径与UTF-8拒绝、文件/条目/总容量边界、回收恢复、原子失败、取消与截止、旧会话和单请求背压。符号链接测试在宿主无创建权限时标为跳过。`engine-jni::native_data_files::tests` 检查不可信请求及JNI响应边界；Android `Os` syscall和真实设备断连/重启持久化需设备验收，不能由JVM测试推定完成。
