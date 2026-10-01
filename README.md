# CastTrace Android

<p align="center">
  <a href="https://github.com/lsr-05/casttrace-android/actions/workflows/android-checks.yml"><img alt="Android checks" src="https://github.com/lsr-05/casttrace-android/actions/workflows/android-checks.yml/badge.svg"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-Apache--2.0-green"></a>
</p>

CastTrace 是使用 Kotlin、Jetpack Compose 和 FFmpeg 构建的 Android 多平台直播录制工具，支持批量管理直播间、后台监控、开播自动录制、分段保存和本地播放。

本项目参考 [StreamCap](https://github.com/ihmily/StreamCap) 的平台录制思路，是独立维护的 Android 适配项目，并非上游官方移动端。上游支持的平台和功能不代表本项目全部支持。

## 功能特性

- **直播间管理**：添加直播地址或分享文本，预览主播信息，批量导入并检查重复项。
- **开播自动录制**：按房间检查间隔循环监控，支持单次监控时间窗口。
- **后台与息屏下载**：前台服务、常驻通知和 CPU / Wi-Fi 锁保护监测与录制。
- **分段保存**：支持 FLV、TS 和 MP4，长录制可分段发布。
- **断线恢复**：短暂断流重连、过期地址刷新、中断内容恢复与失败保存重试。
- **视频库**：本地播放、拖动定位、倍速、重命名、删除、整场分段导出与分享。
- **弹幕录制**：抖音、虎牙、斗鱼和 B 站文字弹幕保存、回放及带弹幕 MP4 导出。
- **配置备份**：保存房间和应用设置，便于迁移配置。

## 快速开始

当前源码版本为 **0.2.13**。本仓库本次仅更新源码，不提供正式 APK 下载；FFmpeg 完整对应源码材料仍待补齐，暂不发布二进制安装包。

在自行构建并安装应用后：

1. 在设置中确认保存目录，授予通知和视频读取权限。
2. 添加直播间地址或粘贴平台的分享文本，确认平台和主播。
3. 手动点击“开始录制”，或开启房间的“开播自动录制”。
4. 自动录制还需开启全局自动录制；退出界面后继续检查需开启后台持续检测。
5. 在视频库查看、播放、导出或分享已录制内容。

暂停会保存当前内容并暂停该房间自动录制，再次开始会创建新一场。MP4 是最终保存格式，直播源可以是 FLV 或 HLS；虎牙优先下载 FLV，再封装为 MP4。

## 支持平台

| 平台 | 示例地址 / 输入形式 |
| --- | --- |
| 虎牙 | `https://www.huya.com/房间号`，`hy.fan` 分享链接 |
| 抖音 | `https://live.douyin.com/房间号`，`v.douyin.com` 分享链接 |
| B 站 | `https://live.bilibili.com/房间号`，`b23.tv` 直播分享链接 |
| 斗鱼 | `https://www.douyu.com/房间号` |
| 快手 | `https://live.kuaishou.com/u/主播标识` |
| 自定义直播源 | HTTPS HLS（`.m3u8`）或 FLV 直链 |

平台接口、分享跳转和登录要求可能变化。目录中标记为“计划支持”的平台尚未实现；分享链接识别成功也不代表对应房间正在直播或一定可录制。

## 从源码构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。设备要求 Android 8.0（API 26）及以上，当前 FFmpeg 依赖提供 `arm64-v8a` 和 `x86_64`。

```bash
git clone https://github.com/lsr-05/casttrace-android.git
cd casttrace-android
```

设置 `ANDROID_HOME`，或在未跟踪的 `local.properties` 中配置 SDK 路径。按 [FFmpeg 依赖说明](app/libs/README.md) 下载并校验固定版本的 AAR，放到 `app/libs/`；CI 也会下载并校验相同制品。

```bash
# Linux / macOS
bash gradlew :app:testDebugUnitTest :app:assembleDebug

# Windows PowerShell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。应用显示名称为 CastTrace，包名保留 `com.streamcap.mobile`，以兼容已有安装和数据。

正式签名可使用 `scripts/Build-SignedRelease.ps1`，签名私钥和密码需在本机单独保管，不能提交到仓库。

## 测试与兼容性

```bash
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

设备测试需构建 `assembleDebugAndroidTest`，在已有数据的设备上使用 `adb install -r` 覆盖安装。真实录制测试通过 `recordingRoomId` 指定已配置的房间，播放测试通过 `playbackRecordingId` 指定已保存录制；只有明确选择对象后才执行。

```bash
adb shell am instrument -w -e class com.streamcap.mobile.RecordingAcceptanceTest -e recordingRoomId ROOM_ID -e recordingSeconds 1800 com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.streamcap.mobile.PlaybackAcceptanceTest -e playbackRecordingId RECORDING_ID com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

本轮单元测试 114 项通过；MuMu 完成分享链接、后台、息屏和删除授权专项验收。Mate 20 在 USB 充电、Wi-Fi 条件下完成约 51 分钟真实连续录制与分段 MP4 保存。此结果不代表所有系统、深度待机或手机品牌均已验证。

## 使用注意

- 华为等手机需允许自启动、关联启动和后台活动；电池优化豁免不能代替厂商应用启动管理。
- 强制停止应用、禁用通知、断网或系统限制可能影响后台工作，常驻通知不保证进程永不被回收。
- 保存 MP4 和导出视频需要额外空间；失败时保留原文件，可修复权限或空间后重试。
- 删除外部视频可能需要系统确认，自定义目录需保持有效授权。
- 请在取得必要授权后录制，遵守平台规则和相关法律，不传播未经许可的内容。

## 许可证与致谢

应用源码采用 [Apache License 2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。第三方库分别适用其许可证，FFmpeg 来源、LGPLv3 声明和当前分发限制见 [THIRD_PARTY.md](THIRD_PARTY.md) 与 [依赖说明](app/libs/README.md)。

感谢 [StreamCap](https://github.com/ihmily/StreamCap) 作者及贡献者，以及 [FFmpeg](https://ffmpeg.org/)、Jetpack Compose、Media3、OkHttp 等开源项目。

问题和建议可提交到 [Issues](https://github.com/lsr-05/casttrace-android/issues)。反馈前请去除 Cookie、账号信息、设备标识和私人录制内容。

贡献流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，版本变化见 [CHANGELOG.md](CHANGELOG.md)，安全问题报告见 [SECURITY.md](SECURITY.md)。
