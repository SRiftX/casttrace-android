# 参与贡献

欢迎提交可复现的问题、平台适配和功能改进。较大改动开始前，请先开 Issue 说明用户场景、预期行为和修改范围。

## 开发环境

- JDK 17
- Android SDK Platform 35、Build Tools 35.0.0
- 按 [app/libs/README.md](app/libs/README.md) 下载并校验 FFmpegKit 7.1.6 AAR；该 AAR 不提交到 Git
- `local.properties` 或 `ANDROID_HOME` 指向本机 Android SDK。不要提交 `local.properties`

Windows 上如果 Gradle 报 `Unable to establish loopback connection`，使用短且可写的临时目录后重试：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:TEMP = 'C:\tmp'
$env:TMP = 'C:\tmp'
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
./gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux 和 macOS：

```bash
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

发布构建和 Android instrumentation 测试见 [RELEASING.md](RELEASING.md) 与 README。

## 修改约定

- 保持修改范围明确，遵循现有 Kotlin、Compose 和 Hilt 约定；跨层职责先查阅 [ARCHITECTURE.md](ARCHITECTURE.md)。
- 新增平台时先更新 `PlatformCatalog`，只有通用解析器不足时才添加专用 Resolver，并为网址、别名和 Resolver 选择补充 JVM 测试。
- 数据库修改必须提交 Room schema 和连续迁移，不要只增加 schema 版本号。
- 修改录制、恢复、删除或导出时，确保失败不会丢失原始媒体；覆盖重试、暂停/停止、进程恢复及外部存储授权路径。
- 录制 Intent、通知操作、application id 和签名身份属于兼容契约；变更前要明确迁移方案。
- 平台和媒体测试优先使用脱敏响应与合成素材。真实直播测试必须指定房间并取得录制授权。

## 验证

提交 PR 前至少运行与改动相关的单测、lint 和构建。跨模块改动运行完整本地检查：

```bash
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

设备回归先构建测试包，再在已有数据的设备上用 `adb install -r` 覆盖安装。不要卸载应用或清除数据。真实录制测试需要显式参数，例如：

```bash
adb shell am instrument -w \
  -e class com.streamcap.mobile.RecordingAcceptanceTest \
  -e recordingRoomId ROOM_ID \
  -e recordingSeconds 1800 \
  com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

测试报告需记录设备型号、Android 版本、网络和供电条件、实际时长及未覆盖场景。没有运行的测试应明确标注，不能写成通过。

## Pull Request

1. Fork 仓库并创建说明用途的分支。
2. 提交源码与必要测试，避免把格式化或生成文件混入无关改动。
3. PR 描述问题、触发条件、修改后的行为、验证结果和已知限制。
4. 检查 diff 不包含密钥、私有诊断、真实录屏或本机路径。

根目录 README、CONTRIBUTING、ARCHITECTURE、RELEASING、CHANGELOG 和 THIRD_PARTY 是对外维护文档。个人工作记录与设备原始证据留在本机 `docs/`；该目录被 `.gitignore` 排除，不作为对外文档入口。

不要提交 APK、AAB、AAR、签名私钥、密码、DPAPI 凭据、本机配置、数据库、Cookie、账号信息或私人录制内容。测试用 `preview.ts` 是公开的合成素材，规则见 `app/src/androidTest/assets/README.md`。

## 本地签名

发布签名不是贡献所必需的，普通贡献者使用 Debug 签名。仓库维护者应继续使用同一正式密钥，通过 `scripts/Build-SignedRelease.ps1` 本地构建签名包；不要重新生成、提交或向聊天发送密钥和密码。正式 APK 自动发布需要单独配置 GitHub Actions secrets，并且必须先通过 [RELEASING.md](RELEASING.md) 中的 FFmpeg 源码分发门槛。
