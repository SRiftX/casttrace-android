"""Offline QA: decode samples, compare media/wall time, detect replayed packet runs.

Usage: python scripts/audit_recording.py --ffmpeg PATH --wall-seconds 2400 part1.mp4 part2.mp4
Does not modify source media. A clean result is a sampling gate, not full visual QA.
"""
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


def repeated_runs(lines):
    last = {}
    runs = {}
    longest = 0
    lag_at_longest = 0
    packets = 0
    for line in lines:
        if line.startswith("#") or not line.strip():
            continue
        digest = line.rsplit(",", 1)[-1].strip()
        previous = last.get(digest)
        if previous is not None:
            lag = packets - previous
            end, length = runs.get(lag, (-2, 0))
            length = length + 1 if end == packets - 1 else 1
            runs[lag] = (packets, length)
            if length > longest:
                longest, lag_at_longest = length, lag
        last[digest] = packets
        packets += 1
    return {"packets": packets, "longest_repeated_run": longest, "replay_lag": lag_at_longest}


def packet_timestamps(lines):
    # Copy-mode framehash preserves demuxed DTS; PTS may legitimately reorder
    # with B frames, so only DTS is checked for backwards movement here.
    previous = None
    backwards = 0
    for line in lines:
        if line.startswith("#") or not line.strip():
            continue
        dts = int(line.split(",")[1])
        if previous is not None and dts < previous:
            backwards += 1
        previous = dts
    return {"backwards_dts": backwards}


def audit(ffmpeg, path):
    probe = subprocess.run([ffmpeg, "-hide_banner", "-i", path, "-t", "0", "-f", "null", "-"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
    match = re.search(r"Duration: (\d+):(\d+):(\d+\.\d+)", probe.stderr)
    if not match:
        raise RuntimeError(f"Cannot read media duration: {path}")
    hours, minutes, seconds = map(float, match.groups())
    duration = hours * 3600 + minutes * 60 + seconds
    video = next((line.strip() for line in probe.stderr.splitlines() if "Video:" in line), "unknown")
    hashes = subprocess.Popen([ffmpeg, "-v", "error", "-i", path, "-map", "0:v:0", "-c", "copy",
                               "-f", "framehash", "-hash", "SHA256", "-"], stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
    packet_lines = list(hashes.stdout)
    replay = repeated_runs(packet_lines)
    if hashes.wait() != 0:
        raise RuntimeError(f"Packet inspection failed: {path}")
    samples = []
    for position in [0, duration / 2, max(0, duration - 8)]:
        decoded = subprocess.run([ffmpeg, "-v", "error", "-ss", str(position), "-i", path,
                                  "-t", "3", "-fps_mode", "passthrough", "-enc_time_base", "demux",
                                  "-f", "null", "-"], capture_output=True)
        # Preserve source precision: default output timebase can round nearby
        # VFR timestamps to the same tick and falsely warn in the null muxer.
        samples.append({"at_seconds": position, "ok": decoded.returncode == 0 and not decoded.stderr.strip(),
                        "stderr": decoded.stderr.decode("utf-8", errors="replace").strip()})
    return {"file": path, "duration_seconds": duration, "video": video, **replay,
            **packet_timestamps(packet_lines), "decode_samples": samples}, packet_lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--wall-seconds", type=float, required=True)
    parser.add_argument("--output", help="Save the generated JSON evidence report")
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    if args.wall_seconds <= 0:
        parser.error("wall-seconds must be positive")
    results = [audit(args.ffmpeg, path) for path in args.files]
    parts = [part for part, _ in results]
    # Preserve one identity history over the entire recording, including boundaries.
    combined = repeated_runs(line for _, lines in results for line in lines)
    total = sum(part["duration_seconds"] for part in parts)
    drift = total - args.wall_seconds
    passed = combined["longest_repeated_run"] < 60 and abs(drift) <= max(30, args.wall_seconds * .02) and all(
        part["longest_repeated_run"] < 60 and part["backwards_dts"] == 0 and all(sample["ok"] for sample in part["decode_samples"])
        for part in parts)
    report = json.dumps({"parts": parts, "media_seconds": total, "wall_seconds": args.wall_seconds,
                      "drift_seconds": drift, "sampling_gate_passed": passed, "all_parts_packet_check": combined,
                      "limitations": "Packet identity heuristic; sampled decoding; does not prove absence of gaps or full audiovisual continuity."},
                     ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(report + "\n", encoding="utf-8")
    print(report)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
