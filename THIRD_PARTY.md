# Third-Party Components

Dependencies declared in `app/build.gradle.kts` retain their respective
licenses, including AndroidX/Media3, Kotlin, kotlinx.coroutines, OkHttp and
FFmpegKit. The Apache-2.0 license at this repository root covers the application
source and does not relicense third-party dependencies.

HTML parsing uses jsoup 1.18.3 (MIT license, https://jsoup.org/license).
Bilibili danmaku decompression uses org.brotli:dec:0.1.2 (MIT license).
Its copyright and license are bundled in `app/src/main/assets/licenses/brotli-MIT.txt`.
The Kotlin X-Bogus signature adapter follows rust-srec's MIT-licensed algorithm
at commit 395a5681054a46d63bf6afdeb21f25c9b012d218. Its copyright and license
are bundled in `app/src/main/assets/licenses/rust-srec-MIT.txt`.
JVM parser tests use JSON-java 20240303 (public domain / Unlicense,
https://github.com/stleary/JSON-java); Android uses its built-in `org.json` API.

The development FFmpegKit AAR is excluded from Git and downloaded from Maven
Central as `dev.ffmpegkit-maintained:ffmpeg-kit-https:7.1.6`. Its SHA-256 is
pinned in Gradle. Official Release APKs use an AAR built from the source commits
and patches pinned in `third_party/ffmpeg/source-build.json`; the source-built
AAR is not claimed to be byte-for-byte identical to Maven Central's artifact.
Both are LGPLv3 HTTPS builds containing FFmpeg n7.1.5, with no enable-gpl or
nonfree flags. Full notices are bundled in the application's offline Settings
/ Open-source licenses screen and in the Release source-material archive.

FFmpegKit's binary provenance, license scope, source-build procedure and
redistribution gate are documented in `FFMPEG-LICENSE.md`. The Maven artifact
evidence is in `third_party/ffmpeg/manifest.json`; the release source build and
its input hashes are pinned in `third_party/ffmpeg/source-build.json`. The tag
workflow builds the AAR from those sources and will not publish an APK unless
the resulting source materials and binary checks pass.

Upstream project references:

- https://github.com/ihmily/StreamCap
- https://github.com/arthenica/ffmpeg-kit
- https://github.com/FFmpeg/FFmpeg
