# Third-Party Components

Dependencies declared in `app/build.gradle.kts` retain their respective
licenses, including AndroidX/Media3, Kotlin, kotlinx.coroutines, OkHttp and
FFmpegKit. The Apache-2.0 license at this repository root covers the application
source and does not relicense third-party dependencies.

The local FFmpegKit binary is excluded from Git. The inspected binary contains
FFmpeg and external-library license/source notices, including GNU license
terms. Its exact redistribution provenance is not established by a filename
alone. This repository makes no claim that its binary is Apache-2.0 licensed.

Before distributing an APK or vendoring an FFmpegKit binary, establish the
specific build's provenance and applicable licenses and provide the required
notices and corresponding source. Publishing this source repository does not
automatically publish any APK or local binary dependency.

Upstream project references:

- https://github.com/ihmily/StreamCap
- https://github.com/arthenica/ffmpeg-kit
- https://github.com/FFmpeg/FFmpeg
