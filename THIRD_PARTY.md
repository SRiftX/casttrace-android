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

The local FFmpegKit binary is excluded from Git. Its SHA-256 matches Maven
Central `dev.ffmpegkit-maintained:ffmpeg-kit-https:7.1.6` exactly. This is the
LGPLv3 HTTPS build containing FFmpeg n7.1.5, with no enable-gpl/nonfree flags.
It is not Apache-2.0 licensed. Full notices are bundled in the application's
offline Settings / Open-source licenses screen.

The exact upstream native source cache and floating GnuTLS gnulib input remain
unverified, so public binary redistribution is still blocked. Collected source
archives are investigation materials, not a complete corresponding-source claim.
See `app/libs/README.md` and `third_party/ffmpeg/manifest.json` for
the provenance, obligations and remaining gap.
Publishing this source repository does not automatically publish an APK.

Upstream project references:

- https://github.com/ihmily/StreamCap
- https://github.com/arthenica/ffmpeg-kit
- https://github.com/FFmpeg/FFmpeg
