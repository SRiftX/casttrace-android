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
- 电池优化豁免、后台运行和自启动设置入口已加入。
- 监控查询使用后台线程，最多 4 个并发检查，避免阻塞界面。
- 录制完成后保留直播间名称和当时的直播标题元数据。

## 验证记录

- 已完成一次真实 MuMu 录制验收：约 625 秒，生成发布文件，状态为 `PAUSED`。
- 已通过 `testDebugUnitTest`、`assembleDebug`、`assembleDebugAndroidTest`。
- 最近一次 APK 已安装到 MuMu 测试设备（serial 不写入仓库）。
- 尚未完成 30 分钟或 1 小时真实直播源验收；不能把构建通过当作长时间录制验收。
- 之前直接运行 Gradle 时未按本文件设置 `TEMP`、`TMP` 和 `ANDROID_HOME`，出现的 Java loopback 连接失败属于本机启动环境问题，不能作为项目本身的测试失败结论。按文档设置环境变量后，构建和单元测试均已通过。

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

## 近期提交

```text
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
