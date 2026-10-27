# 项目卡片按钮与底部行距收紧

## 范围

- 操作条取消八等分宽度，按按钮内容排布；正常字号保持一行，窄屏大字体整项换行。
  启动、编辑及其余六项操作的顺序、回调和禁用条件不变。
- 底部保留原 10sp 字号和完整年月日时间，明确设置 13sp 行高；去掉重复分隔及嵌套留白，
  面板内部上下各 2dp，ID 行与时间行不再额外留间距。大字体按内容增高，不固定高度裁切。
- 短 ID、展开完整 ID、选择和复制原始完整身份的行为不变，不改项目数据或运行逻辑。

## 验证

- `build.cmd -Verify` 通过，开发包保留数据安装到 MuMu 0 / Android 12 / API32 / x86_64。
  九个执行模块共 1328 项单测，1326 通过、2 项既有跳过，0 失败和错误。
- 生产包 SHA-256：`82e4bb117f3cbc5371ee0e70cb6e0df82c1baccb5740a671273ff577e35c28f7`。
  此后仅调整 QA 节点读取，不再修改生产包。包检查保留默认 full 和固定模型摘要。
- 最终 QA SHA-256：`470d3e9bb3b480367e127beff8ef011777a7112289f8daa3c822b5a3cf84d0b3`。
  `workspaceOnly` 7/7 通过，完成码 -1、`pass=true/cleanup=true`。
- 最终 QA 变更后单独重跑 Lint 通过：0 错误、38 警告、12 提示；日志为
  `build/verification/project-card-compact-20261022-final-lint.log`。
- 新断言覆盖 320dp、360dp 常规字号：八按钮完整同排，包含图标和完整文字的宽度下限，
  创建/更新时间同排，底部两行内容高度不超过 34dp。320dp / 1.6 倍字体允许整项换行，
  仍检查文字宽度、可见性及会话占用时的启动禁用和编辑恢复。
- 保留完整 ID 展开/收起、完整日期、排序及搜索、八操作回调、我的页入口和大字体滚动、
  空列表及无结果状态的既有断言。两张最终卡片截图已目视复核。
- 早期 QA 分别在收起/展开定位与大字体旧坐标处失败，均 `cleanup=true`。白名单诊断确认
  Compose 状态已收起，但可访问性缓存仍报告旧标签；测试查询增加实际节点刷新，保留全部
  回调、宽度和行高断言。失败日志不删除，不以早期失败记录冒充最终通过。

## 证据与边界

- 构建：`build/verification/project-card-compact-20261022-verify.log`。
- 初次及中间失败：`project-card-compact-20261022-device.log`、
  `project-card-compact-20261022-device-retest.log`、
  `project-card-compact-20261022-device-large-font-diagnostic.log`，均位于 `build/verification/`。
- 最终设备报告：`build/verification/project-card-compact-20261022-device-fresh-nodes.log`。
- 最终截图：`build/verification/project-card-compact-20261022/final-project-page-compact-320.png`、
  `build/verification/project-card-compact-20261022/final-project-page-large-font.png`。
- QA 使用公开合成页面，操作只计数，不执行私人脚本，不覆盖用户剪贴板，不改系统字号或输入法。
  本批不安装手机或更新网站，不把 MuMu 页面验收当作 ARM 虚拟 Android / 原生手机验收。
