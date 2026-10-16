# 智构品牌资源

用户2026-10-15提供的Logo为唯一造型来源。原SVG实际包含RGBA位图，未描摹或重设计。提取后仅清除低彩度半透明白雾，紫粉主体、画布坐标、比例、折带及左右速度线保留。

- `source/zhigou_logo.svg`：上传文件原样保存。
- `exports/master`：1024透明PNG母版。
- `exports/app/light`：256/192/144/96/72/48/36/24/16应用图标。
- `exports/transparent`：同九种尺寸透明标志。
- `exports/styles`：512浅色/深色/圆形/圆角/纯色/渐变，单色及线性版。
- `exports/floating`：256/128/64/48悬浮球及同轮廓浅紫粉Logo，外圈柔光、内部浅色，不改alpha。
- `exports/states`：待机、执行、运行、完成静态状态。
- `exports/animation`：真正多帧的idle/running/success GIF；success为单次播放。
- `exports/zhigou-resource-board.png`：保持上传参考分区及文字结构的展示板，所有标志由同一母版直接合成。
- `assets.json`：原始摘要、所有输出尺寸和SHA-256。

生成：`node scripts/branding/generate-zhigou-assets.cjs`（从仓库根运行，需已有sharp）。可用`--sharp`指定已安装sharp的绝对路径；不自动下载依赖。首次导入原稿可明确传`--source`，通常重建不传，避免覆盖原稿。

检查：`node scripts/branding/generate-zhigou-assets.cjs --check`，只使用Node内置库，验证原稿和42个输出摘要，不改文件。

App内通过共享`brand-ui`的传统View显示同一PNG及外部光效，不用GIF，也不将完整导出集塞入APK。悬浮球RGB线性提亮，Logo本体不旋转/拉伸。运行使用错速双光环、彗星拖尾及掠光，30fps有界刷新；暂停和失败静态显示，手动停止不冒充成功；隐藏/分离销毁动画，不为播放完成效果延迟停止清理。桌面彩色图标日夜默认浅色底，形状由系统启动器遮罩控制；Android13主题图标仍需启动器支持及用户启用，使用相同alpha遮罩，不承诺所有桌面自动换色。
