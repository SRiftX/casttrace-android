# CastTrace Android

Native Android live-stream recording and playback, built with Kotlin and
Jetpack Compose. This repository contains the Android application only.

CastTrace is an Android adaptation based on the desktop
[StreamCap project by ihmily and contributors](https://github.com/ihmily/StreamCap).
It is independently named and is not an official upstream Android release.
See [NOTICE](NOTICE) and [LICENSE](LICENSE) for attribution and licensing.

## Features

- Live-room resolution for Huya, Douyin, Bilibili, Douyu and Kuaishou.
- FLV and HLS acquisition, foreground recording, per-room settings and storage.
- MP4 remuxing, saved-file playback, seeking and playback-speed controls.
- Huya MP4 recordings prefer FLV input, following the desktop source strategy.
- Temporary recordings can be previewed through an indexed MP4 snapshot.
- Room records and settings remain on the Android device.

Platform interfaces can change. Support does not imply every platform and
device combination has been validated.

## Build

Requirements: JDK 17 and Android SDK Platform 35 / Build Tools 35.0.0.
Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir`.

This source snapshot uses a local FFmpegKit AAR. Before building, prepare
`app/libs/ffmpeg-kit-https-7.1.6.aar` as described in
[app/libs/README.md](app/libs/README.md). The binary is deliberately not in Git.

```sh
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. The APK is generated at
`app/build/outputs/apk/debug/app-debug.apk` and is not tracked.

The internal application ID and package namespace remain `com.streamcap.mobile`
to preserve compatibility with existing development installations and data.
The display name and Gradle project name are CastTrace.

## Device Tests

`RecordingAcceptanceTest` is opt-in: pass `recordingRoomId` for an existing,
enabled live room. It records for at least 620 seconds after media arrives,
pauses, then requires a published recording of at least 600 seconds.
`PlaybackAcceptanceTest` takes `playbackRecordingId` and checks saved playback,
seeking and measured 2x playback. These tests use real media and device storage.

Build the test APK with `assembleDebugAndroidTest`. On an emulator containing
data, install using `adb install -r` and invoke `am instrument` directly;
automated Gradle device-test installation can reset application data.

```sh
adb shell am instrument -w -e class com.streamcap.mobile.RecordingAcceptanceTest -e recordingRoomId ROOM_ID com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.streamcap.mobile.PlaybackAcceptanceTest -e playbackRecordingId RECORDING_ID com.streamcap.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

## Dependencies And Publication

The application source retains the upstream Apache-2.0 license. Third-party
libraries have their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md).
No recording files, APKs, signing keys, local SDK settings or private diagnostics
are included. Repository visibility is managed in GitHub settings; a private
repository can be made public later without changing this source layout.
