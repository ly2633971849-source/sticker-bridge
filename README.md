# 表情直达 · Sticker Bridge

Android 应用：把图片存为 PNG，把可读取的动图或短视频转换为 GIF，保存到相册的 `StickerBridge` 文件夹。需要在 QQ 使用时，从 QQ 的表情收藏里手动添加。

| 使用方式 | 操作 | 要求 |
| --- | --- | --- |
| 导入原文件 | 首页点「导入图片或视频」，或者在系统分享中选「表情直达」 | Android 11 或以上；无需电脑、无需无障碍 |
| 从已有视频裁剪 | 「更多 → 从视频裁剪表情」，选手机里的短视频，在预览上拖出要保留的区域 | 离线生成 GIF；不用登录抖音，无需开启录屏或无障碍 |
| 在抖音里框选 | 在系统设置开启「表情直达 · 框选保存」，回抖音点绿色「选」再点图片 | 能选到的静态区域保存为 PNG；识别成动态但拿不到原件时，提示导入，不把封面当动图 |

框选使用 Android 的单帧截图接口，不录屏。分享链接不等于分享文件，能否取得实况照片或视频原件取决于来源应用。如果悬浮按钮消失，先试「更多 → 恢复悬浮按钮」。应用不自动操作 QQ，也不去除原素材自带的水印。

原文件上限 32 MiB，动态 WebP 上限 12 MiB，重新编码的动画最长 60 秒。已有 GIF 可按原文件保存；GIF 不包含视频声音。最低 Android 版本是安装条件，具体品牌系统的选框和媒体读取仍应按实际设备验证。

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

「更多 → 检查更新」只检查构建时配置的 HTTPS 地址。没有配置时，本地构建不连接任何更新站点；导入和框选功能仍可测试。「更多 → 复制诊断信息」可将版本、设备与本次处理状态复制到剪贴板，由你决定是否分享。

`main` 是开发版本。Windows 和 Linux 上的自动检查使用独立开发签名，不会发布正式更新；已安装正式包的用户应继续使用应用内的更新入口。

## 参与维护

- [复现和验证问题](docs/REPRODUCING.md)：生成可公开的素材、区分处理路径、提交最小复现。
- [贡献和发布流程](CONTRIBUTING.md)：源码入口、回归测试和发版要求。
- 在 Issues 中使用「Bug 复现」模板，并注明测试设备、应用版本、操作步骤、期望和实际结果。

项目按 [MIT 许可证](LICENSE) 发布。提交素材时请确保你有权公开并许可他人随项目分发。
