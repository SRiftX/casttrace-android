"""Fail closed unless the reviewed FFmpeg corresponding-source gate is clear."""
import json
import pathlib
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "third_party/ffmpeg/manifest.json"


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    gaps = manifest.get("sourceGaps", [])
    ready = manifest.get("redistributionReady") is True

    if not ready or gaps:
        print("Release blocked: FFmpeg corresponding-source review is incomplete.")
        if not ready:
            print("- redistributionReady must be true after maintainer review.")
        for gap in gaps:
            print(f"- {gap}")
        return 1

    print("FFmpeg source gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
