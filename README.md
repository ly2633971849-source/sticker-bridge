# 表情直达 · Sticker Bridge

表情直达把抖音里框选的图片、动图或短视频保存到手机相册，适用于 Android 11 及以上。

## 使用

1. 点击首页按钮，按提示在系统无障碍设置中开启「表情直达 · 框选保存」。
2. 在抖音点绿色「选」，再点要保存的表情。也可以把「选」拖到目标上再松手。悬浮按钮不见时，回首页再点一次主按钮。
3. 在手机相册的 `StickerBridge` 文件夹查看结果，再从 QQ 或微信的表情收藏中手动添加。

框选用当前一帧定位，并查找可读取的原件。读到静态图片时保存 PNG，读到完整动图或视频时保存 GIF。没有可读取的原件时，若出现「保存静态图」，需要你确认才会把当前这一帧写入相册。

原文件上限 32 MiB，动态 WebP 上限 12 MiB，重新编码的动画最长 60 秒。已有 GIF 可按原文件保存，GIF 不包含视频声音。具体品牌系统的选框和媒体读取能力以实机结果为准。

## 构建

安装 Python 3.11+、JDK 17 和 Android SDK（`platforms;android-36`、`build-tools;36.0.0`、`platform-tools`）。设置 `JAVA_HOME` 和 `ANDROID_HOME`，并把 SDK 的 `platform-tools` 加入 PATH。在项目目录运行：

```sh
python build.py --output build/sticker-bridge.apk
python -m pip install Pillow
python tests/verify_encoder.py
adb devices
adb -s SERIAL install -r build/sticker-bridge.apk
```

将 `SERIAL` 替换为你自己的测试设备或模拟器。开发包由本地开发密钥签名；签名不同的包无法覆盖已经安装的发行版，优先在独立测试设备运行。

「更多 → 检查更新」检查构建时配置的 HTTPS 地址。「更多 → 复制诊断信息」可将版本、设备与本次处理状态复制到剪贴板，由你决定是否分享。

`main` 是开发版本。Windows 和 Linux 上的自动检查使用独立开发签名，不会发布正式更新；已安装正式包的用户应继续使用应用内的更新入口。

## 参与维护

- [复现和验证问题](docs/REPRODUCING.md)：生成可公开的素材、区分处理路径、提交最小复现。
- [贡献和发布流程](CONTRIBUTING.md)：源码入口、回归测试和发版要求。
- 在 Issues 中使用「Bug 复现」模板，并注明测试设备、应用版本、操作步骤、期望和实际结果。

项目按 [MIT 许可证](LICENSE) 发布。提交素材时请确保你有权公开并许可他人随项目分发。
