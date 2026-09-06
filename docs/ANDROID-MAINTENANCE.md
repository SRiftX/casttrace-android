# CastTrace Android 维护与环境记录

更新时间：2026-09-06

这份文档记录 Android 端的本地开发环境、构建方式、设备调试入口、已完成改动和已知限制。后续修改前先查看这里，再更新对应章节。

## 工程位置

| 项目 | 路径 | 用途 |
| --- | --- | --- |
| Android 主工程 | `<ANDROID_PROJECT_DIR>` | 当前 Android 应用源码、构建和测试 |
| 桌面参考工程 | `<DESKTOP_REFERENCE_DIR>` | 对照桌面端解析、命名和录制逻辑 |
| Android 包名 | `com.streamcap.mobile` | 保持不变，避免覆盖已有安装数据 |
| APK 输出 | `app\build\outputs\apk\debug\app-debug.apk` | Debug 安装包 |
| Git 远程仓库 | 本地 `origin` | Android 端代码，当前按私人仓库使用；不要把远程地址写入文档 |

## 本地依赖

- JDK：17（当前环境为 Microsoft OpenJDK 17.0.18）。
- Gradle Wrapper：8.9。
- Android SDK：`<ANDROID_SDK_DIR>`。
- 编译目标：Android API 35，最低 API 26，目标 API 35。
- Kotlin：1.9.23。
- FFmpegKit：`app\libs\ffmpeg-kit-https-7.1.6.aar`，约 20 MB，本地二进制不提交 Git。
- FFmpeg AAR SHA-256：`E80E5EE52248A3DCB1DDA0524DD784FE4BBB212DCD4D575E82E15D21A002D860`。

首次构建前确认 AAR 存在。缺少它时，Gradle 无法完成依赖解析。AAR 的来源和许可信息见 `app\libs\README.md`、`THIRD_PARTY.md`。

## 构建命令

PowerShell：

```powershell
$env:TEMP = 'C:\tmp'
$env:TMP = 'C:\tmp'
$env:ANDROID_HOME = '<ANDROID_SDK_DIR>'
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

只构建 APK：

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

构建产物：`app\build\outputs\apk\debug\app-debug.apk`。

## ADB 与 MuMu

ADB 路径：

```text
<ANDROID_SDK_DIR>\platform-tools\adb.exe
```

同一 MuMu 实例可能显示多个 ADB serial。把实际设备 serial 保存为本机环境变量，脚本中始终使用明确的 `-s` 参数，不要使用未指定设备的 `adb` 命令。

```powershell
$adb = '<ANDROID_SDK_DIR>\platform-tools\adb.exe'
$device = '<ANDROID_DEVICE_SERIAL>'
& $adb devices -l
& $adb -s $device install -r app\build\outputs\apk\debug\app-debug.apk
```

查看应用日志：

```powershell
& $adb -s $device logcat --pid=$(& $adb -s $device shell pidof com.streamcap.mobile)
```

## 已完成的主要功能

- 录制保存阶段显示保存、索引修复和分段进度。
- 录制文件按直播间名称分目录保存：`Movies/StreamCap/<直播间名称>/`，不按日期拆目录。
- 自定义 SAF 保存目录也会创建直播间子目录。
- 录制列表支持状态筛选和直播间筛选。
- 多选需要先点击明确的“进入多选”按钮；进入后显示复选框、全选、分享和删除。
- 多选工具栏的全选图标周围整个区域都可点击。
- 删除成功和删除失败使用不同提示，不再把成功文本放进失败弹窗，也不再显示 `kotlin.Unit`。
- 播放器支持拖动定位、快进/快退和倍速播放。
- 长录制按直播间配置的时间分段保存，默认 30 分钟；HLS 和 FLV 分段文件使用统一的 `name_002.ts.part` / `name_002.flv.part` 命名，并兼容旧命名。
- 每个已发布分段都会立即写入数据库，后续分段失败或进程中断时，前面已保存的文件仍可见、可播放。
- 强制退出或系统回收后，应用重新启动会扫描临时录制，自动修复并发布仍可读取的分段。
- 断流收尾时会裁剪不完整的 TS 包或 FLV Tag；MP4 封装使用 `.mp4` staging 文件，失败不会留下损坏的成品文件。
- 播放器可直接读取 TS 分段，支持多个 MP4/TS/FLV 分段连续播放、总时长显示和跨分段拖动定位。
- 电池优化豁免、后台运行和自启动设置入口已加入。
- 监控查询使用后台线程，最多 4 个并发检查，避免阻塞界面。
- 录制完成后保留直播间名称和当时的直播标题元数据。

## 本次版本的实现说明

以下内容对应提交 `248b93e`，后续升级或排查长录制问题时按这个流程核对：

### 录制、分段与发布

- `RecordingRequest.segmentSeconds` 控制 HLS 和 FLV 的自动分段；房间设置在录制入口再次限制为 30 分钟到 7 天，旧数据库中的过小值会按 30 分钟兜底。
- HLS 使用 `HlsRecorder` 按媒体分片边界下载并写入 MPEG-TS，FLV 使用 `DirectHttpRecorder` 按完整 Tag 写入；FLV 仅在达到阈值且遇到视频关键帧时切换，避免从任意帧开始导致首段无法播放。
- 临时文件统一位于应用专属目录的 `pending-recordings` 下。新文件名为 `直播间_时间戳.ts.part` 或 `直播间_时间戳.flv.part`，后续分段为 `_002`、`_003`；播放器和恢复逻辑同时识别旧版本的 `name.ts_002.part` 命名。
- 每发布一个分段就更新 `recording_sessions.outputUris` 和 `outputUri` 首段兼容字段。发布后才删除对应临时文件，因此封装后半程中断不会丢失已经完成的分段。
- MP4 先写入隐藏的 `.name.writing.mp4` staging 文件，检查成功后再重命名为最终文件；封装失败会清理 staging 文件，避免 MediaStore 或自定义目录出现损坏的同名成品。
- MediaStore 发布使用 `IS_PENDING=1` 写入，复制完成后才切换为 `IS_PENDING=0`；SAF 目录使用直播间子目录并在复制失败时删除目标文档。

### 断流、强制退出与恢复

- TS 收尾通过 `RecordingFileRepair.repairTransportStream` 按 188 字节包边界裁剪尾部残包；FLV 收尾通过 `repairFlv` 逐 Tag 校验长度，丢弃未完成的最后一个 Tag。
- 录制服务进入 `FINALIZING` 后先修复所有临时分段，再逐段封装和发布。状态、字节数、时长和已发布 URI 在每段完成后立即落库。
- 应用被强制停止、系统回收或进程崩溃后，`RecordingService.monitor` 会在重新扫描直播间前调用恢复入口；`TaskRecoveryWorker` 也只调用这个统一入口，避免启动恢复逻辑分叉。
- 恢复会扫描活动会话的临时文件族：可读取的分段会修复、封装、发布并标记 `COMPLETED`；没有临时文件但已有 `outputUris` 的会话会保留已发布分段并完成归档；完全没有可用媒体时才标记失败。
- 自动恢复不会伪造完整录制时长；MP4 优先使用封装器读取的轨道时长，TS/FLV 使用下载器累计时长。真实断流续录仍受平台线路和重试次数限制。

### 播放、删除与兼容

- 播放源解析优先读取 `outputUris`，再回退到旧的单值 `outputUri`，最后才读取 `temporaryPath` 下的临时分段。
- Media3 对 TS、FLV、MP4 显式设置 MIME 类型；多分段作为多个 `MediaItem` 连续播放，时间轴按各窗口时长合计，进度条可以跨分段拖动。
- 删除操作按数据库中的全部 URI 执行，并额外查找同名前缀的 MediaStore 分段；删除后通过查询确认内容 URI 已不存在，避免“实际删除成功但提示失败”。
- 数据库当前为 Room version 4：1→2 增加房间录制和分段配置，2→3 增加 `outputUris` 并从旧 `outputUri` 回填，3→4 增加 `roomTitle`。修改实体字段时必须同步迁移和导出 schema。

### 相关代码入口

| 领域 | 入口文件 | 升级时重点检查 |
| --- | --- | --- |
| 录制服务与恢复 | `app/src/main/java/com/streamcap/mobile/service/RecordingService.kt` | `FINALIZING` 状态、逐段落库、强制退出恢复 |
| HLS 分段 | `app/src/main/java/com/streamcap/mobile/recording/hls/HlsRecorder.kt` | playlist 刷新、序号重置、关键帧切段、加密分片 |
| FLV 分段 | `app/src/main/java/com/streamcap/mobile/recording/flv/DirectHttpRecorder.kt` | Tag 完整性、时间戳连续性、重连后的文件命名 |
| 文件修复 | `app/src/main/java/com/streamcap/mobile/recording/repair/RecordingFileRepair.kt` | TS 188 字节边界、FLV Tag 长度校验 |
| MP4 封装 | `app/src/main/java/com/streamcap/mobile/recording/ffmpeg/FfmpegMp4Muxer.kt` | staging 后缀必须保持 `.mp4`，失败清理临时产物 |
| 存储发布与删除 | `app/src/main/java/com/streamcap/mobile/storage/RecordingStorage.kt` | MediaStore pending、SAF 权限、分段删除和目录命名 |
| 播放源与时间轴 | `app/src/main/java/com/streamcap/mobile/ui/player/RecordingPlaybackSourceResolver.kt`、`RecordingPlayerScreen.kt` | 新旧分段命名、MIME、跨段 seek |
| 数据模型与迁移 | `app/src/main/java/com/streamcap/mobile/data/database/Entities.kt`、`Migrations.kt`、`StreamCapDatabase.kt` | Room 版本号、旧字段回填、schema 导出 |

## 后续升级检查清单

新增录制格式、分段策略或存储位置时，按以下顺序更新：

1. 先更新 `RecordingRequest`、录制器和 `RecordingProgress.outputFiles`，确保终态携带全部分段文件。
2. 再更新 `RecordingService` 的修复、封装、发布和恢复路径，并保证每个已发布 URI 都能在中途崩溃后从数据库恢复。
3. 同步更新播放器解析、导出/分享、重命名和删除逻辑；不能只修改录制端的扩展名。
4. 如果修改 `LiveRoomEntity` 或 `RecordingSessionEntity`，增加连续 Room migration、导出 schema 和旧数据库回填测试。
5. 为完整包、残包、断流重连、分段命名和多段播放补充单元测试；再执行 MuMu 安装、启动、instrumentation 和 logcat 检查。
6. 更新本节、验证记录、已知限制和近期提交，明确哪些结果是模拟数据，哪些是实际直播源验收。

## 验证记录

- 已完成一次真实 MuMu 录制验收：约 625 秒，生成发布文件，状态为 `PAUSED`。
- 已通过 `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest`。
- 最近一次 APK 已安装到 MuMu 测试设备（serial 不写入仓库）。
- 2026-09-06 在 MuMu `127.0.0.1:7555` 完成复验：Debug APK 和 AndroidTest APK 安装成功，应用启动进程正常，未发现 `FATAL EXCEPTION`；`am instrument` 返回 `OK (14 tests)`，其中 10 项实际通过，4 项因未传入直播间或录制参数按测试设计跳过。
- 本次复验使用的命令为 `.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；单元测试共 26 项，失败 0、错误 0、跳过 0。
- 尚未完成 30 分钟或 1 小时真实直播源验收；不能把构建通过当作长时间录制验收。
- 之前直接运行 Gradle 时未按本文件设置 `TEMP`、`TMP` 和 `ANDROID_HOME`，出现的 Java loopback 连接失败属于本机启动环境问题，不能作为项目本身的测试失败结论。按文档设置环境变量后，构建和单元测试均已通过。
- 2026-09-06 修复后的单元测试覆盖 HLS 多分段命名、断流刷新、FLV 收尾修复和 TS 收尾修复；完整 `testDebugUnitTest` 结果为 26 项通过。
- 2026-09-06 使用 MuMu `127.0.0.1:7555` 直接执行 `com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner`，结果为 `OK (14 tests)`；其中需要外部直播间或录制参数的用例按设计跳过，其余用例实际通过。

## 已知限制

1. 仓库目前没有 `.github/workflows`，单元测试、APK 构建和 AndroidTest 不会自动在 CI 执行。
2. FFmpegKit AAR 依赖本地文件；新机器或 CI 需要单独准备并校验 SHA-256。
3. Android 设备上的直播平台接口、网络线路和系统后台策略可能变化；解析成功不代表所有设备都能长期录制。
4. 旧版本已经保存到 `Movies/StreamCap` 根目录的文件不会自动搬迁；新录制会按直播间目录保存。
5. 设备验收测试使用已有房间或录制数据时，需要显式传入测试参数，避免测试重置应用数据。

## 推荐维护流程

1. 在 Android 工程目录修改 Android 端代码；桌面端只用于查找行为线索。
2. 修改后先运行单元测试和 Debug 构建。
3. 需要设备验证时固定使用本机保存的设备 serial，安装 APK 后查看 logcat。
4. 涉及录制、解析、播放器或存储时，补充对应的真实设备验证记录。
5. 提交信息写清行为变化；不要提交 AAR、APK、录制视频、数据库或本地配置。
6. 推送 GitHub 前确认仓库仍为私人仓库，并检查 `git status` 和最近提交。
7. 长视频问题优先检查 `recording_sessions.outputUris`、`pending-recordings` 临时文件族和 MediaStore 中的分段文件，不要只查看旧的 `outputUri` 单值字段。

## 近期提交

```text
239ab57 docs: update Android maintenance notes
248b93e Fix long recording preservation and repair
ae7fa11 Keep monitoring work off UI thread
8473772 Run room checks concurrently
91b18bc Expand select all touch target
37bc16d Make multi-select explicit
01c8238 Fix successful recording deletion message
7cf4641 Polish recording selection toolbar
af01f27 Harden background recording battery settings
7595ce2 Organize recordings by room and add multi-file actions
3372caf Show finalization stages and classify failures
```
