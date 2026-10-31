#!/usr/bin/env python3
"""Run every saved platform sample on an already-installed QA package, domestic first.

This is a preflight runner. MEDIA_OK is not a recording/continuity acceptance.
Use ConcurrentRecordingAcceptanceTest and audit_recording.py for saved media.
No APK install, data clearing, account login or chat posting is performed.
"""
import argparse
import datetime
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run(argv):
    return subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=240)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-idle-service", action="store_true",
                        help="Allow a persistent QA service after independently confirming every recording is stopped")
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "build/platform-preflight-results.json")
    args = parser.parse_args()
    data = json.loads((ROOT / "qa/platform-coverage.json").read_text(encoding="utf-8"))
    catalog = (ROOT / "app/src/main/java/com/streamcap/mobile/platform/PlatformCatalog.kt").read_text(encoding="utf-8")
    keys = re.findall(r'PlatformCatalogEntry\("([^\"]+)"', catalog)
    assert set(keys) == {r["key"] for r in data["platforms"]}, "Coverage matrix differs from catalog"
    adb = [args.adb, "-s", args.serial]
    device = run(adb + ["get-state"])
    if device.returncode or device.stdout.strip() != "device":
        raise SystemExit("ADB target is not authorized/online")
    package = "com.streamcap.mobile.platformqa"
    component = package + ".test/androidx.test.runner.AndroidJUnitRunner"
    # A running recording must not be interrupted by a new instrumentation process.
    services = run(adb + ["shell", "dumpsys", "activity", "services", package]).stdout
    if re.search(r"isForeground=true", services) and not args.allow_idle_service:
        raise SystemExit("QA foreground service is active; finish its work before running instrumentation")
    results = []
    for item in sorted(data["platforms"], key=lambda r: r["region"] != "domestic"):
        candidate = item["candidate"]
        if not candidate or item["key"] == "rednote":
            result = {"key": item["key"], "state": "NO_ROOM_CANDIDATE", "boundary": item["state"]}
        else:
            # Matrix URLs are persistent public room samples, with no account credentials.
            if "'" in candidate or "\n" in candidate:
                raise ValueError("Invalid candidate URL")
            command = ("am instrument -w -r -e class com.streamcap.mobile.PlatformPreflightTest "
                       "-e preflightUrls '" + candidate + "' " + component)
            instrument = run(adb + ["shell", command])
            logs = run(adb + ["logcat", "-d", "-s", "PlatformPreflight:I", "*:S"]).stdout
            matches = [line.split("RESULT ", 1)[1] for line in logs.splitlines()
                       if "RESULT " in line and "url=" + candidate in line]
            line = matches[-1] if matches else ""
            state = re.search(r"state=([A-Z_]+)", line)
            # Never propagate API bodies or signed media URLs from exception messages.
            reason = line.split(" reason=", 1)[-1] if " reason=" in line else ""
            reason = re.sub(r"https?://[^\s]+", "<url>", reason)[:160]
            result = {"key": item["key"], "candidate": candidate,
                      "state": state.group(1) if state else "NO_RESULT",
                      "reason": reason,
                      "instrumentation_ok": "OK (1 test)" in instrument.stdout}
        results.append(result)
        print(item["key"], result["state"], flush=True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"date": datetime.datetime.now().isoformat(),
            "warning": "Preflight only; no recording acceptance implied", "results": results},
            ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    assert len(results) == len(keys)
    active = {r["key"] for r in data["platforms"] if r["catalog"] not in {"PLANNED", "DISCONTINUED"}}
    failed = [r["key"] for r in results if r["key"] in active and r["state"] != "MEDIA_OK"]
    if failed:
        print("Preflight did not pass: " + ", ".join(failed), flush=True)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
