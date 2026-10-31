# FFmpegKit License and Source Materials

## Binary used by the app

Local development uses `dev.ffmpegkit-maintained:ffmpeg-kit-https:7.1.6` from Maven Central. The AAR is excluded from Git and its SHA-256 is pinned in `app/build.gradle.kts`. Official Release builds instead compile FFmpegKit from the source commits, patches and toolchain pinned in [`third_party/ffmpeg/source-build.json`](third_party/ffmpeg/source-build.json). The Maven and source-built AARs are not claimed to be byte-for-byte identical. Both builds are LGPLv3 HTTPS variants with FFmpeg n7.1.5 and without GPL or nonfree FFmpeg components; the app packages `arm64-v8a` and `x86_64`.

The build task `prepareCompatibleFfmpeg` adjusts only ELF64 `PT_GNU_RELRO.p_memsz` when 16 KiB alignment padding extends beyond the mapped `PT_LOAD` segments at 4 KiB page granularity. This avoids Android 10 `mprotect` failures on unmapped padding. The source AAR remains unchanged; the transformation is in `app/build.gradle.kts` and is part of the application build configuration.

## License scope

The root [Apache License 2.0](LICENSE) covers CastTrace application source. It does not relicense FFmpegKit or its linked libraries. Full notices shipped with the app are under `app/src/main/assets/licenses/` and can be read offline from Settings → Open-source licenses.

The FFmpegKit build and its dependencies retain their own terms. The current native configuration identifies FFmpeg, GMP, GnuTLS, Nettle/Hogweed and libiconv on LGPL-compatible build paths. `cpu_features` and `ndk_compat` retain their upstream notices. Android zlib and MediaCodec are system libraries. The exact component versions, source commits, build options and hashes are recorded in [`third_party/ffmpeg/manifest.json`](third_party/ffmpeg/manifest.json).

## Corresponding source and release gate

An APK release includes the corresponding FFmpegKit/native-library source archives, pinned dependency source, build recipe and patches, application source/build configuration, license notices and SHA-256 checksums. The tag workflow builds the AAR from the pinned inputs, verifies its version, ABIs and native configuration, packages the source materials, and passes that same AAR to Gradle for tests and APK packaging. The policy records the exact verification scope and does not claim byte-for-byte equivalence with the separate Maven development artifact.

## Rebuilding

The pinned build uses Ubuntu 24.04, JDK 17, Android SDK Platform 35, NDK r26c and `gettext` for GnuTLS bootstrap. Reproduce it with [`scripts/build_ffmpeg_source.sh`](scripts/build_ffmpeg_source.sh), which fetches pinned commits, applies the tracked patches and writes the AAR under `build/compliance/`. Then run [`scripts/prepare_ffmpeg_compliance.py`](scripts/prepare_ffmpeg_compliance.py) and [`scripts/verify_release_readiness.py`](scripts/verify_release_readiness.py) to package and check corresponding-source materials.

```sh
./android.sh --enable-gnutls --enable-gmp \
  --enable-android-media-codec --enable-android-zlib \
  --disable-arm-v7a --disable-arm-v7a-neon --disable-x86
```

The source-built AAR is supplied to Gradle using `CASTTRACE_FFMPEG_AAR_PATH` and `CASTTRACE_FFMPEG_AAR_SHA256`. Gradle then creates a compatible AAR by trimming only unmapped ELF `PT_GNU_RELRO` padding that can cause `mprotect` failures on Android 10. This transformation is in the application build configuration and is included in the CastTrace source archive; the Release also publishes the resulting compatible AAR. Any native-library or transformation change requires renewed native loading, remuxing, playback and device compatibility tests.

To rebuild CastTrace for development, download and verify the pinned Maven AAR, use JDK 17 and Android SDK 35, then run the Gradle commands in [README.md](README.md). To reproduce a Release, use the source-build sequence in [RELEASING.md](RELEASING.md). A modified LGPL library can be rebuilt and relinked using the application source and build configuration included with a release. Do not use CastTrace's private signing key for modified builds.

## References

- [FFmpegKit maintained source](https://github.com/ffmpegkit-maintained/ffmpeg)
- [FFmpeg](https://github.com/FFmpeg/FFmpeg)
- [GNU LGPL v3](https://www.gnu.org/licenses/lgpl-3.0.html)
- [Release process](RELEASING.md)
