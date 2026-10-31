# Release Process

This repository publishes CastTrace APKs as GitHub Releases. A release tag is
accepted only when it matches `versionName` in `app/build.gradle.kts`, points to
a commit on `main`, passes the FFmpeg corresponding-source gate, and produces an
APK signed with the established CastTrace release certificate.

## Release prerequisites

- The release commit is merged into `main` and its Android checks pass.
- `third_party/ffmpeg/source-build.json` pins every source archive, build option,
  patch, ABI and toolchain used by the release FFmpegKit build.
- The GitHub Actions repository secrets are configured:
  - `CASTTRACE_KEYSTORE_BASE64`: base64-encoded PKCS#12 signing keystore.
  - `CASTTRACE_STORE_PASSWORD`: keystore password.
  - `CASTTRACE_KEY_ALIAS`: signing key alias.
  - `CASTTRACE_KEY_PASSWORD`: signing key password.
- The configured certificate SHA-256 is
  `D39B0394EE312DE5B1F25F8A215A0E81CA9C47229835D2F96DD0187694523C57`.

Do not commit a keystore or password. Keep an offline backup of the signing key;
Android updates must keep the same application id and signing identity.

## Local release checks

Use Ubuntu 24.04, JDK 17, Android SDK Platform 35, Build Tools 35.0.0, NDK
26.2.11394342 (r26c), Python 3 and the native build packages installed by the
release workflow (`autoconf`, `automake`, `libtool`, `autopoint`, `gettext`,
`gperf`, `bison`, `ragel`, `yasm`, `nasm`, `meson`, `ninja-build` and the
remaining packages in `.github/workflows/release-apk.yml`). Download or install
the pinned sources only through the tracked build recipe. From the repository root:

```bash
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/26.2.11394342"
bash scripts/build_ffmpeg_source.sh
export CASTTRACE_FFMPEG_AAR_PATH="$PWD/build/compliance/ffmpeg-7.1.6/ffmpeg-kit-https-7.1.6.aar"
export CASTTRACE_FFMPEG_AAR_SHA256="$(sha256sum "$CASTTRACE_FFMPEG_AAR_PATH" | cut -d' ' -f1)"
python scripts/prepare_ffmpeg_compliance.py --aar "$CASTTRACE_FFMPEG_AAR_PATH"
python scripts/verify_release_readiness.py
bash gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

The release workflow repeats these checks from a clean runner. Its APK uses the
source-built FFmpegKit AAR; ordinary local development may use the pinned Maven
AAR described in `app/libs/README.md`. The Maven artifact is not claimed to be
byte-for-byte equivalent to the source build.

## Publish

1. Update `versionName` and `versionCode` in `app/build.gradle.kts`, then update
   `CHANGELOG.md` and user-facing release notes.
2. Merge the release commit into `main`; wait for Android checks to pass.
3. Confirm the corresponding-source policy and signing secrets are ready.
4. Create and push the matching annotated tag, for example:

   ```bash
   git tag -a v0.2.16 -m "CastTrace 0.2.16"
   git push origin v0.2.16
   ```

5. Follow the `Publish Android APK` Actions run. The workflow verifies the
   source inputs, builds FFmpegKit, runs tests and lint, signs the APK, checks
   its package/version/certificate, and creates the GitHub Release.
6. Verify every uploaded asset and its SHA-256 using `SHA256SUMS`. The release
   contains the APK, the Gradle-compatible FFmpeg AAR, CastTrace source, FFmpeg
   corresponding-source materials and checksums.
7. Install the APK over an existing CastTrace installation with `adb install
   -r` and verify app startup, native FFmpeg loading, playback and recording
   before announcing the release.

The APK is intended for Android 8.0+ on `arm64-v8a` and `x86_64`. Keep the
repository public if the Release is intended for unauthenticated downloads.
