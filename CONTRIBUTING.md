# 贡献和发布

修复从一个可复现的问题开始。先按[复现指南](docs/REPRODUCING.md)记录版本、输入和实际结果，判断问题是出在目标选取、原文件读取、转换，还是相册发布。修复后用同一个输入验证，并在 PR 里写明仍未测试的设备或媒体类型。涉及性能时，首次转换和重复导入分开计时。

| 处理路径 | 源码入口 |
| --- | --- |
| 首页、系统选文件、系统分享入口 | `MainActivity.java` |
| 悬浮按钮、目标边界及画面识别 | `BridgeAccessibility.java`、`SelectionTargets.java`、`PreviewGeometry.java`、`MotionBadge.java` |
| 原件候选和索引兼容性 | `SourceClient.java`、`SourceIndexFiles.java` |
| 素材读取、转换、缓存和相册发布 | `Jobs.java`、`MediaFiles.java`、`ConversionCache.java` |
| 视频帧、WebP 和 GIF | `VideoFrames.java`、`WebpAnimation.java`、`GifWriter.java`、`GifInfo.java` |
| 更新检查、校验、安装 | `AppUpdates.java`、`UpdateFileProvider.java` |
| 可公开的诊断摘要 | `Diagnostics.java` |

源码位于 `app/src/main/java/com/stickerbridge/app/`。变更 Java 逻辑至少重新构建；转换改动跑 GIF 主机测试，并用能触发错误的最小素材在 Android 上验证。界面和相册行为需要手机或模拟器，主机测试无法替代。不要用已存在的相册文件证明这次保存成功，检查有无新增结果。

变更文件判型或原件索引时，说明哪些旧记录还能复用、哪些需要重建。用相同素材对照旧版与候选版，同时记录覆盖升级和全新安装的结果。发布记录附测试用例和 Git 提交号，保留升级后查找耗时以便定位性能回退。

## 发布检查

1. 合并已通过构建和相关回归的改动，说明本次支持的设备范围。提升 AndroidManifest.xml 的 `versionCode`，并更新 `versionName`。
2. 维护者使用现有发行签名构建，配置自己的 HTTPS 更新清单地址。签名文件和口令仅在维护者机器或受控发布环境中保存，不提交到 Git，也不上传到静态文件服务器。

   在安全的环境中设置 `STICKERBRIDGE_KEYSTORE`、`STICKERBRIDGE_KEY_ALIAS`、`STICKERBRIDGE_STORE_PASSWORD`、`STICKERBRIDGE_KEY_PASSWORD`，再构建：

   ```sh
   python build.py --release --update-manifest-url https://example.org/stickerbridge/latest.json --output build/candidate.apk
   ```

3. 用 `prepare_update.py` 检查包名、版本与上一实际发行包的签名。示例地址仅为占位符：

   ```sh
   python prepare_update.py --apk build/candidate.apk --baseline-apk build/previous.apk --base-url https://example.org/stickerbridge/ --notes-file changes.txt --output build/update-release
   ```

4. 先发布带版本号的 APK，按 HTTPS 下载核对长度与 SHA-256，再原子更新 `latest.json`。已发布版本的 APK 字节不要改变。最后在安装了较早发行版的测试设备上验证「检查更新 → 下载 → 系统确认 → 再次检查」。若发错版本，发布版本号更高的修复包。

PR 可包含源码、最小合成素材、测试命令和预期结果。提交内容按项目的 MIT 许可证分发；只提交你有权公开和再许可的素材，并提供生成命令或来源说明。不要提交签名文件、生产部署配置、设备原始日志、聊天截图或未经授权的媒体。
