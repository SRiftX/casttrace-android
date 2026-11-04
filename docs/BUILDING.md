# CastTrace 构建指南

本文覆盖本机 Debug 构建、Docker 构建、FFmpegKit 固定源码构建和正式 APK 发布。普通开发建议使用 Debug 构建；正式分发以 GitHub Actions 发布工作流为准。

## 构建路径

| 路径 | 用途 | FFmpegKit 输入 | 签名与发布 |
| --- | --- | --- | --- |
| 本机 Windows/Linux | 日常开发、单测、lint、Debug APK | 校验过 SHA-256 的 Maven AAR | Debug 签名，不发布 |
| Docker Compose `android` | 不在主机安装 Android SDK 的开发构建 | 同一 Maven AAR | Debug 签名，不发布 |
| Docker Compose `release` | 验证固定 FFmpeg 源码构建、分发材料和 Release 编译 | 本次从固定源码生成的 AAR | 默认不使用正式签名密钥、不创建 GitHub Release |
| GitHub Release workflow | 对外发布 APK | Ubuntu runner 从固定源码构建 | 使用 GitHub Secrets 中的正式密钥，核验签名后发布 |

正式 Release 必须从匹配 `versionName` 的标签触发，并通过仓库的源码分发门禁、测试、lint、签名身份和 APK 元数据检查。不要把本地未签名或 Debug 签名 APK 当作正式包。

## 环境和依赖

本机 Gradle 构建使用 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。Gradle Wrapper 固定为 9.1.0。设备最低为 Android 8.0（API 26）；APK 仅包含 `arm64-v8a` 和 `x86_64`。

FFmpegKit AAR 不提交到 Git。普通开发下载 Maven Central 上固定的 7.1.6 AAR，并检查摘要：

```text
SHA-256: e80e5ee52248a3dcb1dda0524dd784fe4bbb212dcd4d575e82e15d21a002d860
```

Linux/macOS：

```bash
mkdir -p app/libs
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
  'https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/7.1.6/ffmpeg-kit-https-7.1.6.aar' \
  -o app/libs/ffmpeg-kit-https-7.1.6.aar
actual="$(openssl dgst -sha256 app/libs/ffmpeg-kit-https-7.1.6.aar | awk '{print $NF}')"
test "$actual" = 'e80e5ee52248a3dcb1dda0524dd784fe4bbb212dcd4d575e82e15d21a002d860'
```

Windows PowerShell：

```powershell
New-Item -ItemType Directory -Path app\libs -Force | Out-Null
Invoke-WebRequest `
  'https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/7.1.6/ffmpeg-kit-https-7.1.6.aar' `
  -OutFile app\libs\ffmpeg-kit-https-7.1.6.aar
$expected = 'e80e5ee52248a3dcb1dda0524dd784fe4bbb212dcd4d575e82e15d21a002d860'
$actual = (Get-FileHash app\libs\ffmpeg-kit-https-7.1.6.aar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "Unexpected FFmpegKit AAR SHA-256: $actual" }
```

Gradle checks the AAR hash before compiling. Do not replace it with an arbitrary FFmpegKit build. The Maven AAR is for development; the official release builds from pinned sources and does not claim the two AAR files are byte-identical.

## 本机 Debug 构建

设置 `ANDROID_HOME` 指向 Android SDK。Linux/macOS：

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Windows PowerShell：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
./gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

Windows 若 Gradle 报 `Unable to establish loopback connection`，先使用短且可写的临时目录：

```powershell
$env:TEMP = 'C:\tmp'
$env:TMP = 'C:\tmp'
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Android instrumentation 测试需要连接设备或模拟器，见下文的设备测试说明。

## Docker Debug 构建

需要 Docker Engine/Desktop 和 Docker Compose v2。工具链镜像锁定到 Android SDK 35 的多架构镜像摘要；主机不需要另装 JDK、Android SDK 或 NDK。先按上节下载并校验 AAR，然后在仓库根目录运行：

```bash
docker compose run --rm android
```

此命令运行 JVM 单测、lint 并构建 Debug APK。源码和 `app/build/` 输出通过 bind mount 留在主机；Gradle 下载缓存保存在名为 `gradle-cache` 的 Docker volume 中。首次运行会下载 Android/Gradle 依赖，之后可复用缓存。

也可只构建 Debug APK：

```bash
docker compose run --rm android bash -lc 'bash gradlew --no-daemon :app:assembleDebug --console=plain'
```

Compose 的 `release` profile 会额外安装 Ubuntu 24.04 发布构建依赖、JDK 17 和 NDK r26c。它从固定 FFmpegKit 源码构建 AAR，打包对应源码材料，运行来源门禁、测试、lint 和 Release 编译：

```bash
docker compose --profile release run --rm release
```

这条命令会消耗较多 CPU、内存、磁盘和网络，源码构建需要明显长于普通 Gradle 构建的时间。产物仍位于主机的 `build/` 和 `app/build/`。该容器命令不会注入签名密钥，也不会创建 GitHub Release；正式签名发布按 [RELEASING.md](../RELEASING.md) 使用 Actions 工作流。

## 设备测试和签名

容器中的 JVM 单测不需要模拟器。Instrumentation 测试依赖主机上的 MuMu 或 Android Emulator；请在主机执行 adb/设备测试，避免把模拟器 socket 和设备授权凭据带进通用构建容器。

Debug 构建使用 Debug 签名。正式签名密钥不得提交到 Git，也不要放进 Docker image、Compose 文件或命令历史。仓库维护者的本地 Windows 签名流程见 `scripts/Build-SignedRelease.ps1`；自动公开发布只通过已配置正式签名 Secrets 的 GitHub workflow。

## 常见问题

- `Missing FFmpegKit AAR`：确认 `app/libs/ffmpeg-kit-https-7.1.6.aar` 存在，并重新执行 SHA-256 校验。
- `FFmpegKit AAR checksum differs`：文件与固定开发构建不一致；不要绕过 Gradle 校验，重新下载正确版本。
- Docker 中出现 Android SDK/NDK 缺失：确认使用仓库的 `Dockerfile` 和 Compose 服务，不要改用未固定的第三方 Android 镜像。
- Windows `Unable to establish loopback connection`：设置短临时目录后重试，或使用 Docker 构建服务。
- 正式 Release 构建失败：检查 GitHub Actions 运行的具体失败步骤和 `ffmpeg-source-build-diagnostics` artifact；不要通过上传本地 Debug APK 规避发布门禁。
