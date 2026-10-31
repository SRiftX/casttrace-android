# Local FFmpegKit Dependency

Local development uses `ffmpeg-kit-https-7.1.6.aar` from this directory. This
Maven artifact is not committed. Place a verified copy here before running
Gradle. Official release builds provide a separately source-built AAR through
`CASTTRACE_FFMPEG_AAR_PATH` and its digest through
`CASTTRACE_FFMPEG_AAR_SHA256`.

SHA256 of the development binary:

```text
E80E5EE52248A3DCB1DDA0524DD784FE4BBB212DCD4D575E82E15D21A002D860
```

The development checksum matches Maven Central exactly:
`dev.ffmpegkit-maintained:ffmpeg-kit-https:7.1.6`.

Download URL:
https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/7.1.6/ffmpeg-kit-https-7.1.6.aar

Gradle and CI enforce this pin. This is the LGPLv3 build containing FFmpeg
n7.1.5 for arm64-v8a and x86_64. Do not substitute an arbitrary artifact.
The formal Release does not redistribute this downloaded Maven binary. It uses
the pinned source build and includes corresponding-source materials; see
`RELEASING.md`, `FFMPEG-LICENSE.md` and
`third_party/ffmpeg/source-build.json`.

The build uses a generated compatibility AAR under `app/build/ffmpeg/`.
`prepareCompatibleFfmpeg` changes only ELF64 `PT_GNU_RELRO.p_memsz` where
16 KiB alignment padding extends beyond every mapped `PT_LOAD` segment at
4 KiB page granularity. The pinned input remains unchanged. On Android 10
the original arm64 `libswresample.so` asks `mprotect` to protect unmapped
pages, producing ENOMEM even with ample memory. The generated artifact
trims only that unmapped padding; all RELRO file data, code, load segments,
alignment and license files are preserved. The x86_64 artifact also needs
this correction in `libavdevice.so` and `libswresample.so`.

This is a local compatibility transformation, not a source change to FFmpeg.
The official release publishes the derived compatible AAR alongside the
corresponding source materials and application build configuration. Any future
artifact update must review this transformation and rerun device loading,
remuxing, decoding and seek tests, including a 16 KiB-page device.
