# Android APK 发布

正式 APK 只能从已合入 `main` 的版本标签构建。发布工作流会运行 JVM 单测和 lint，校验 FFmpeg AAR、对应源码分发状态及签名证书，最后把 APK、应用源码、FFmpeg 源码材料和 SHA-256 校验文件放入同一 GitHub Release。

## 当前发布门槛

`third_party/ffmpeg/manifest.json` 中的 `redistributionReady` 必须为 `true`，`sourceGaps` 必须为空。当前清单仍为 `false`，原因是上游构建缓存和浮动子模块等原生源码输入尚未核实。因此 tag 工作流会停止，不会上传或发布 APK。单独签名成功或收集到源码归档都不等于该门槛已通过。

关闭门槛前，维护者必须取得或自行构建与实际 AAR 对应的完整 LGPL 源码和构建输入，校验归档，并更新清单、`THIRD_PARTY.md`、README 与许可证说明。不能只把未核实的源码材料包标记为完整对应源码。

## GitHub Actions 配置

发布工作流需要仓库 Actions secrets。它们只用于签名，不应放在普通变量、仓库文件或命令行参数中：

| Secret | 内容 |
| --- | --- |
| `CASTTRACE_KEYSTORE_BASE64` | 正式 `.p12` 密钥文件的 Base64 内容 |
| `CASTTRACE_STORE_PASSWORD` | 密钥库密码 |
| `CASTTRACE_KEY_ALIAS` | 正式密钥别名，当前为 `casttrace-release` |
| `CASTTRACE_KEY_PASSWORD` | 密钥密码 |

需由仓库所有者在 GitHub 仓库 Settings → Secrets and variables → Actions 添加。`CASTTRACE_KEYSTORE_BASE64` 必须是单行 Base64，不要加 PEM 头、换行或空格。私钥不进入 GitHub Release、构建日志或生成的源码包。签名身份必须继续与既有安装兼容，不能临时生成新密钥替代。

## 发布步骤

1. 更新 `versionName`、`versionCode` 和 `CHANGELOG.md`，确认 README 与许可证材料反映本次发布状态。
2. 确保改动已合入 `main`，并通过 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleRelease` 以及必要的设备回归。
3. 确认源码清单通过发布门槛，并验证正式密钥已配置且证书指纹与既有身份一致。
4. 创建并推送匹配 `versionName` 的标签，例如 `v0.2.14`。
5. GitHub Actions 会再次验证标签位于 `main` 历史、版本号匹配、测试通过、签名正确，然后创建 GitHub Release。
6. 下载 Release 中的 APK、应用源码包、FFmpeg 对应源码材料包和 `SHA256SUMS`，核对文件哈希与 APK 签名后再对外宣传。

Release 资产应包含：

- `casttrace-<version>-release.apk`
- `casttrace-<version>-source.zip`
- `casttrace-ffmpeg-7.1.6-source-materials.zip`
- `SHA256SUMS`

任何一个门槛失败，工作流都不会创建包含 APK 的 Release。当前不要为源码状态未就绪的版本创建正式标签。

## 本地签名验证

仓库维护者可在本机使用受保护的密钥生成候选 APK：

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:TEMP = 'C:\tmp'
$env:TMP = 'C:\tmp'
New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
./scripts/Build-SignedRelease.ps1
```

脚本会验证 APK 签名并输出 SHA-256。此本地步骤只用于验证构建，不能绕过源码分发门槛，也不代表该 APK 可以公开发布。密钥保管细节见仓库所有者本机维护的签名说明。
