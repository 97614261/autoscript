# 项目设置同页优化（2026-10-12）

决策见 [ADR 0081](../adr/0081-inline-project-run-settings.md)。

新建 Studio 项目使用生成积木契约中的全部18种能力；既有项目不自动改变声明，设置增加全选/清空及中文名称。能力声明不等于系统授权，不自动打开随机化。

运行页内联启动延迟、提示总开关、自动提示分类及变量筛选、普通触摸/命中动作/等待随机化、提示尺寸/位置/颜色/字体/圆角/时长/文字对齐。输入未保存前仅保存在草稿，能力与运行设置统一校验、冲突检查并原子写入。日志追踪/识图统计保持独立。

删除原项目设置二级运行配置状态及调用；删除无引用的 ProjectDebugConfigurationDialog、DenseDebugSwitch、DebugHeader、DebugEnvironmentPanel、DebugInfoRow、DebugPromptFilterPanel、DebugFilterPickerRow、DebugSwitchRow、DebugInteractiveSwitch。调试页保留仍在使用的输出、弹窗样式和启动延迟；日志直接使用 RuntimeLogSettingsDialog。仅清理对应设置/调试布局，没有删除用户数据或其他业务入口；源码恢复可用此前版本，不需要数据迁移。

新增测试覆盖契约能力默认集合、完整草稿往返、数值/颜色拒绝、100×30px和原点保留、日志设置不受影响、能力/运行设置联合保存、陈旧配置冲突与无效草稿不写入。

本地单元测试、Lint、Debug构建及仓库布局检查通过；MuMu 0 更新安装保留数据。未进行界面点击或运行效果实测，需要用户检查横竖屏布局及保存后重开。
