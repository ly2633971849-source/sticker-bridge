# 复现与回归验证

记录入口、输入和出错阶段。正常操作从抖音框选开始。独立验证转换时，维护者可以用 Android 系统分享提供同一素材，单独记录结果。

## 用系统分享独立验证转换

不用登录抖音或 QQ 也能验证基本转换。安装 [FFmpeg](https://ffmpeg.org/) 后，在本机生成一段约两秒的测试画面，将文件放进测试设备的下载目录：

```sh
ffmpeg -f lavfi -i testsrc2=size=176x144:rate=15 -t 2 -an -c:v libx264 -pix_fmt yuv420p repro-motion.mp4
adb -s SERIAL push repro-motion.mp4 /sdcard/Download/
```

在系统文件应用里选中 `repro-motion.mp4`，通过系统分享发送给「表情直达」。预期得到约两秒的 GIF，并能在系统相册找到。若相册只显示首帧，可用支持播放 GIF 的查看器核对帧数。

静态对照可用 `ffmpeg -i repro-motion.mp4 -frames:v 1 repro-still.png` 提取一帧，再通过系统分享验证能输出静态 PNG。测试结束后可以从下载目录和相册删除本次创建的测试文件。

也可以在已解锁的测试设备上运行自动检查。先用 `python build.py --debuggable` 构建并安装测试 APK。脚本通过系统分享发送合成视频，再从相册读回 GIF，校验摘要、帧数和时长。

```sh
python tests/verify_video_import_android.py --device SERIAL --source repro-motion.mp4 --name synthetic-motion
```

测试 AVIF 动画时，先用带 libaom 的 FFmpeg 生成两秒样本。通过系统分享发送该文件，在支持解码该 AV1 文件的设备上应得到 24 帧 GIF。测试脚本可用 `--mime image/avif` 指定输入类型。同一容器可能同时标记静图和图像序列，主机判型测试用 `python tests/verify_media_brands.py`。

```sh
ffmpeg -f lavfi -i testsrc2=size=160x144:rate=12 -t 2 -an -c:v libaom-av1 -cpu-used 6 -pix_fmt yuv420p repro-sequence.avif
python tests/verify_video_import_android.py --device SERIAL --source repro-sequence.avif --mime image/avif --name synthetic-sequence
```

针对「实况只有封面」的回归，用脚本从零生成一张声明包含动画、实际上没有视频片段的 JPEG。预期显示动画缺失，且相册没有新增静态封面：

```sh
python tests/make_motion_cover.py repro-incomplete.jpg
python tests/verify_video_import_android.py --device SERIAL --source repro-incomplete.jpg --mime image/jpeg --expect-missing-motion --name synthetic-incomplete
```

如果设备的媒体扫描接口没有立即登记新文件，可从系统文件应用手动分享。自动测试在本机 `build/video-import/` 写入日志和产物，只整理需要公开的结果字段。

`tests/verify_video_crop_android.py` 是独立视频裁剪入口的历史回归，复现旧版裁剪问题时使用对应旧版和当时的复现指南。

## 按改动选择验证

| 改动范围 | 复现步骤和判据 |
| --- | --- |
| 编码 | `python tests/verify_encoder.py`；独立解码验证尺寸、帧数、延时和透明像素。Android 上再核对同一素材的输出。 |
| 系统分享、相册 | 分别通过系统分享发送合成 PNG 和 MP4，核对输出格式、帧数和相册可见。空、损坏或超限输入应给出提示，相册记录数应与测试前相等。 |
| 抖音选框 | 记录安卓版本、品牌系统版本、抖音版本和所在页面。测试静态图片、明确标为动态但无可用原件、滑动后重新选择、切到后台后回来；检查选框是否贴合、保存提示是否消失。 |
| 原件索引升级 | `python tests/verify_media_brands.py` 覆盖旧格式索引复用、新记录优先和旧 HEIF 标签失效。在独立测试设备上，用旧版成功框选一个自己可复用的 GIF 或 WebP，再覆盖安装候选版，保留应用数据并框选同一素材。记录原件查找耗时、结果格式和帧数，用同一候选包的全新安装结果作对照。 |
| 评论区原件查找 | `python tests/verify_source_lookup.py` 验证候选顺序、分段预算、重复候选和相似素材。脚本报告模拟候选读取量。在 Android 上用同一份无动图标签的动画和静态对照复测，再记录实际查找耗时、输出帧数和相册写入。开发包的 `source-resolution.json` 中 `searchPasses` 列出实际运行的阶段、候选数量和耗时。 |
| 框选失败后的确认 | 在没有原件读取服务的独立测试设备上框选同一张普通图片，提示「保存当前画面？」时先点「取消」，核对相册记录数与测试前相等。再框选一次，确认「保存静态图」，从相册读回一张新的单帧 PNG，核对画面与预览一致。 |
| 进度显示 | 对照悬浮提示和应用内保存状态，读取阶段显示阶段和耗时，转换阶段显示百分比。相册发布成功后显示 100%，读取失败或取消不显示完成。 |
| 速度 | 记录冷启动后的第一次保存以及同一份素材的第二次保存，分别标明入口和是否可能复用缓存。对比时使用相同设备与素材，勿将抖音联网播放与应用转换混为同一测量。 |
| 应用更新 | 使用独立 HTTPS 测试源和模拟器，测试同版提示、有效升级、取消、下载内容摘要不匹配。有效下载仍需系统安装确认；校验失败不可进入安装器。 |

如果选框已经贴合，却在「正在查找原件」结束后失败，请复现后查看诊断。`SOURCE_LOOKUP` 下的 `CONNECTION` 表示原件读取服务不可用，`LOOKUP_TIMEOUT` 表示超过查找时限，`NO_ORIGINAL` 表示未找到可靠匹配，`MOTION_UNAVAILABLE` 表示没有拿到已识别动图的完整原件。这些结果只涉及应用这次能否取得原件。可以在同一个评论里对比已加载素材在断网后能否继续循环，以及结束抖音进程后离线重开是否还能播放。前者表明这段播放已有足够数据，后者支持有可跨进程保留的素材或状态。若离线连评论区都进不去，该项结果不可判定。要独立验证转换，可通过系统分享发送同一素材的媒体文件，并检查相册中是否新增 GIF。

优先用 Android 11（最低版本）与较新的模拟器各验证一次转换。涉及选框或系统安装器时，再选择一台真机。没有测到的机型填「未测」。

评论区识别的主机回归：`python tests/verify_selection_targets.py`。覆盖选中的评论标签、数量缩写、回复标记，以及评论区与上方视频的边界。实机复测时至少换一个评论区，确认底部提示改了也能选，其他页面的计数按钮不会开启评论区推断。

## 提交可公开的信息

提供应用版本、手机型号、Android 和品牌系统版本、相关来源应用版本、图片或视频类型、复现次数、逐步操作、期望与实际结果。若文件可公开，附上最小素材以及尺寸、帧数、时长；优先提交合成样本或生成命令。涉及第三方内容时先改用自己的同格式素材。

复现后、退出应用前，可点「更多 → 复制诊断信息」。摘要只保留当前进程最近一项保存任务的阶段和耗时，以及应用版本、设备型号和选框服务状态。应用先显示预览，点「复制」后才写入剪贴板，不会联网发送；由你决定是否粘贴到 Issue。无法公开输入时，附带格式和触发条件，并说明没有可分享素材。

只在确有帮助时贴出异常行或裁剪后的区域，并在公开前移除用户名、设备序列号、账号、聊天内容、绝对路径、链接凭据和配对码。不要上传完整 logcat、无障碍树、相册索引、界面录屏或应用缓存；测试截图如必须保留，先检查背景是否可识别。
