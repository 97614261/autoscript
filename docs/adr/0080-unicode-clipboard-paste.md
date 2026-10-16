# ADR 0080：Root受控Unicode粘贴

2026-10-12平台文本输入范围：提供Input.pasteText，原生积木名“粘贴文本（含中文）”。先明确写入4096 UTF-8字节内的纯字符剪贴板，再发送结构化Root键码279；不调用input text、不传任意shell、不安装/启用/切换输入法。API24已有该粘贴键，见[Android官方KeyEvent说明](https://developer.android.com/reference/android/view/KeyEvent#KEYCODE_PASTE)。

分别声明input.basic与device.clipboard；写剪贴板前同时检查两种能力，写入失败不发送粘贴键。每段宿主等待可取消，停止发生在中间时不继续发送输入；已经写入的剪贴板不自动撤回。录制不捕获剪贴板或还原文本，既有录制设备键白名单不扩展为含隐私语义的粘贴动作。

调用者先聚焦目标输入框。只有目标控件支持粘贴才有效；Root确认键事件不证明内容已被目标App接收，游戏自绘控件、密码框或禁止粘贴的App仍可能不支持。文案和函数说明明确这一限制，不宣称通用中文输入100%实现。专用输入法仍未获授权，不添加。
