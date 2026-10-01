# 参与贡献

欢迎提交可复现的问题、平台适配和功能改进。开始较大改动前，请先通过 Issue 说明具体需求和范围。

## 开发环境

使用 JDK 17、Android SDK Platform 35、Build Tools 35.0.0 和仓库自带的 Gradle Wrapper。按 `app/libs/README.md` 下载并校验 FFmpegKit AAR；本机 SDK 路径放在 `local.properties`，不要提交。

```bash
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
bash gradlew :app:assembleDebugAndroidTest
```

Windows 使用 `gradlew.bat`。CI 的 Release 为无正式签名构建，不含私人签名材料。

## 改动与验证

- 保持修改范围明确，遵循现有 Kotlin 和 Compose 风格。
- 修改数据库时提供连续迁移，并提交 `app/schemas` 的对应 schema。
- 修改录制、恢复、删除或导出行为时，确保失败不会丢失原文件，补充相关回归测试。
- 平台解析测试优先使用脱敏响应和合成素材；真实直播测试必须明确选择房间，并获得必要授权。
- 使用已有数据的设备时通过 `adb install -r` 覆盖安装，不要卸载应用或清除用户数据。
- 后台与长录制验证需写明设备系统、实际经过时间、是否充电、网络条件及未覆盖场景。

## 提交 Pull Request

1. Fork 仓库并创建说明用途的分支。
2. 提交源码和必要测试，运行相关检查。
3. 在 PR 中说明具体问题、修改后的行为、验证结果和限制。
4. 若检查失败，说明原因，不要把未执行的测试写成通过。

`preview.ts` 是公开的合成测试素材，详见 `app/src/androidTest/assets/README.md`。不要上传真实录制、设备诊断、Cookie、账号凭据、签名私钥、密码、数据库、APK/AAR 或本机配置。`docs/` 用于本地资料，不提交。

## 本地签名

发布签名不是贡献所必需的。所有者可使用 `scripts/Initialize-ReleaseSigning.ps1 -BackupDirectory <PRIVATE_BACKUP_DIRECTORY>` 首次初始化受保护的本机密钥，再运行 `scripts/Build-SignedRelease.ps1`。已有密钥必须继续复用；恢复密码应与密钥分开保管。

普通贡献者使用 Debug 签名即可。正式 APK 分发还需满足 `THIRD_PARTY.md` 与依赖来源清单中的要求。
