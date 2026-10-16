# ADR 0044：跨App可视化编辑宿主

2026-10-04，接受。用户明确要求编辑器悬浮在目标App上，而非仅在智构内编辑。

2026-10-05修订：点编辑不自动退主任务到桌面；用户确认只修编辑器停止回编辑，不重开脚本配置界面。服务层收到已运行会话的结束/失败/断连直接恢复并请求展开，去重陈旧通知；初始停止/Root状态更新不算运行结束。宿主就绪在有效面板边界更新成功后发布，不以添加1×1初始窗口充当已展开。

同日后续用户明确要求启动停止后回配置、编辑窗与球同时出现；返回入口与球窗口策略由 ADR 0045 覆盖，以上“仅回编辑”保留为历史决策，不再是当前规则。

## 决策

- Studio拥有独立、非导出的前台编辑服务。复用VisualProjectScreen、EditorDock、ProjectStore和RuntimeClient；Runner不依赖Studio编辑服务，不搬入引擎/Root实现。
- 用户点击编辑时检查系统悬浮授权，未授权只打开系统授权页，不自动授予。仅传有界项目ID，服务加载私有工程，不接受外部路径或用户源码Intent。
- 单一编辑会话：同项目恢复已有窗口，切换项目需先关闭当前会话。前台通知可恢复或请求保存关闭，进程被杀不自动重新运行或重新显示编辑器。
- 采用真实WindowManager系统窗口。保持屏幕坐标系与现有拖动/缩放算法；外层窗口裁剪到编辑面板/灵动环的实际可见边界，外部触摸交给目标App，不使用满屏透明窗拦截目标App。
- Studio共享Dialog适配器在普通Activity保持原实现，在系统宿主使用同样内容的系统弹窗与独立Compose生命周期/键盘焦点。系统文件/通知请求通过非导出桥接Activity，单请求、超时、代次检查与关闭清理；不缓存私密脚本。
- 截图、悬浮选点、录制与脚本运行时隐藏编辑窗口及子弹窗，不销毁草稿；完成后恢复。运行控制由既有运行服务浮球负责，避免双浮球。
- 主动关闭先保存已应用草稿；保存失败保留会话。未提交参数弹窗须先完成或取消，不能以通知关闭静默丢弃参数。权限撤销/窗口异常请求保存关闭；未提交参数或保存失败时保留进程内会话等待用户处理，不承诺被系统杀进程后恢复。实际销毁取消选点/请求并释放视图、位图、绑定和生命周期，不将迟到结果写入新项目。
- 主机测试及构建与设备效果分别报告；跨App触摸、键盘和厂商后台保活需用户统一设备验收。

## 依据

Android系统窗口在API26以上使用TYPE_APPLICATION_OVERLAY，API24–25保留TYPE_PHONE兼容；需SYSTEM_ALERT_WINDOW。编辑服务声明specialUse前台类型及用户触发用途，不绕过系统限制。

参考[系统窗口文档](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)与[Compose Dialog官方实现](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidDialog.android.kt)。项目现有Compose1.7.3无新版本windowType参数，不为此升级依赖；窄适配器使用现有ComponentDialog。
