"""Fail closed unless the exact source-built FFmpegKit AAR is release-ready."""
import hashlib
import json
import pathlib
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
POLICY = ROOT / "third_party/ffmpeg/source-build.json"
MANIFEST = ROOT / "build/compliance/ffmpeg-7.1.6/manifest.json"
AAR = ROOT / "build/compliance/ffmpeg-7.1.6/ffmpeg-kit-https-7.1.6.aar"


def load(path: pathlib.Path) -> dict[str, object]:
    if not path.is_file():
        raise RuntimeError(f"Required release evidence is missing: {path.relative_to(ROOT)}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    policy = load(POLICY)
    manifest = load(MANIFEST)
    if policy.get("redistributionReady") is not True or policy.get("sourceGaps"):
        raise RuntimeError("The reviewed FFmpeg source-build policy blocks redistribution.")
    if manifest.get("redistributionReady") is not True or manifest.get("sourceGaps"):
        raise RuntimeError("The generated FFmpeg source manifest blocks redistribution.")
    if manifest.get("buildCommit") != policy.get("upstreamBuildCommit"):
        raise RuntimeError("The generated FFmpeg source manifest uses an unexpected upstream commit.")

    expected_sources = policy.get("sourceSha256", {})
    actual_sources = {source["name"]: source["sha256"] for source in manifest.get("sources", [])}
    if actual_sources != expected_sources:
        raise RuntimeError("The generated FFmpeg source archive hashes do not match the reviewed pins.")

    digest = manifest.get("sha256", "")
    if len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest.lower()):
        raise RuntimeError("The generated FFmpeg AAR manifest has no valid SHA-256 digest.")
    if not AAR.is_file() or hashlib.sha256(AAR.read_bytes()).hexdigest() != digest.lower():
        raise RuntimeError("The generated FFmpeg AAR does not match its recorded SHA-256 digest.")
    if manifest.get("buildPatches") != policy.get("patches"):
        raise RuntimeError("The generated FFmpeg build patches differ from the reviewed policy.")

    native = manifest.get("native", [])
    expected_abis = set(policy.get("expectedAbis", []))
    actual_abis = {item["abi"] for item in native}
    if actual_abis != expected_abis:
        raise RuntimeError(f"Unexpected FFmpeg native ABIs: {sorted(actual_abis)}")
    for item in native:
        config = item["configuration"]
        if any(option in config for option in policy["forbiddenFfmpegOptions"]):
            raise RuntimeError(f"The {item['abi']} binary contains a forbidden FFmpeg build option.")
        if any(option not in config for option in policy["requiredFfmpegOptions"]):
            raise RuntimeError(f"The {item['abi']} binary is missing a required FFmpeg build option.")

    print(f"FFmpeg source gate passed for AAR SHA-256 {digest}.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, KeyError, TypeError, ValueError, RuntimeError) as error:
        print(f"Release blocked: {error}", file=sys.stderr)
        sys.exit(1)
