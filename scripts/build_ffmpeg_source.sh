#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/compliance/ffmpeg-7.1.6"
mkdir -p "$OUT" "$ROOT/build"

BUILD_COMMIT="$(python3 -c 'import json, pathlib, sys; print(json.loads((pathlib.Path(sys.argv[1]) / "third_party/ffmpeg/source-build.json").read_text())["upstreamBuildCommit"])' "$ROOT")"
WORKSPACE="$(mktemp -d "$ROOT/build/ffmpeg-source-build.XXXXXX")"
SOURCE="$WORKSPACE/ffmpegkit"
trap 'printf "FFmpeg source build workspace: %s\n" "$WORKSPACE"' EXIT

git init -q "$SOURCE"
git -C "$SOURCE" remote add origin https://github.com/ffmpegkit-maintained/ffmpeg.git
git -C "$SOURCE" fetch --depth=1 origin "$BUILD_COMMIT"
git -C "$SOURCE" checkout --detach FETCH_HEAD

for patch in "$ROOT"/third_party/ffmpeg/patches/*.patch; do
  git -C "$SOURCE" apply --unidiff-zero --check "$patch"
  git -C "$SOURCE" apply --unidiff-zero "$patch"
done

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to the Android SDK." >&2
  exit 1
fi
if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/26.2.11394342"
fi
export ANDROID_NDK_ROOT
if [[ ! -f "$ANDROID_NDK_ROOT/source.properties" ]]; then
  echo "Android NDK r26c was not found at $ANDROID_NDK_ROOT." >&2
  exit 1
fi

cd "$SOURCE/android-7.1-lts"
./android.sh --enable-gnutls --enable-gmp \
  --enable-android-media-codec --enable-android-zlib \
  --disable-arm-v7a --disable-arm-v7a-neon --disable-x86

AAR="$SOURCE/android-7.1-lts/prebuilt/bundle-android-aar/ffmpeg-kit/ffmpeg-kit.aar"
if [[ ! -s "$AAR" ]]; then
  echo "The FFmpegKit source build did not produce an AAR." >&2
  exit 1
fi
cp "$AAR" "$OUT/ffmpeg-kit-https-7.1.6.aar"
sha256sum "$OUT/ffmpeg-kit-https-7.1.6.aar"
