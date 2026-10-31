"""Package and verify the pinned FFmpegKit source-built release AAR."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "build/compliance/ffmpeg-7.1.6"
EVIDENCE = OUT / "evidence"
PATCHES = ROOT / "third_party/ffmpeg/patches"
REVIEW = ROOT / "third_party/ffmpeg/source-build.json"
ASSETS = ROOT / "app/src/main/assets/licenses"
SOURCES = [
    ("gnu-config", "arthenica/gnu-config", "805517123cbfe33d17c989a18e78c5789fab0437"),
    ("ffmpegkit", "ffmpegkit-maintained/ffmpeg", "0cc470227a1730ce3d467967677de4f54e759f7a"),
    ("ffmpeg", "arthenica/FFmpeg", "3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587"),
    ("cpu-features", "arthenica/cpu_features", "936b9ab5515dead115606559502e3864958f7f6e"),
    ("gmp", "arthenica/gmp", "66a538082b4a485bd2b34012db96e44a86a8e712"),
    ("gnutls", "arthenica/gnutls", "ca61668d7764fc29fb4cc2aa396cb035e176636d"),
    ("libiconv", "arthenica/libiconv", "4b9c27bec873392533dd3ef309cd62962a644e18"),
    ("nettle", "arthenica/nettle", "990abad16ceacd070747dcc76ed16a39c129321e"),
    ("libiconv-gnulib", "arthenica/gnulib", "485d983b7795548fb32b12fbe8370d40789e88c4"),
    ("gnutls-declared-gnulib", "arthenica/gnulib", "3639c57a970191e0bf7a9789bd1341786d0255a1"),
]
LICENSE_NAMES = (
    "COPYING", "COPYING.LIB", "COPYING.LESSER", "LICENSE", "LICENSE.md",
    "LICENSE.LGPLv3", "LICENSE.GPLv3", "COPYING.GPLv3", "COPYING.LGPLv2.1",
    "COPYING.LGPLv3", "COPYING.LESSERv3", "COPYINGv3", "THIRD-PARTY-NOTICES.txt",
)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: pathlib.Path) -> pathlib.Path:
    if not target.exists():
        part = target.with_name(target.name + ".download")
        curl = "curl.exe" if __import__("os").name == "nt" else "curl"
        subprocess.run(
            [curl, "-sSfL", "--proto", "=https", "--proto-redir", "=https", "--retry", "3",
             "--connect-timeout", "30", "--max-time", "900", url, "-o", str(part)],
            check=True,
        )
        part.replace(target)
    return target


def collect(item: tuple[str, str, str]) -> dict[str, object]:
    name, repo, commit = item
    url = f"https://codeload.github.com/{repo}/tar.gz/{commit}"
    target = download(url, OUT / f"{name}-{commit}.tar.gz")
    actual_hash = sha256(target)
    expected_hash = EXPECTED_HASHES.get(name)
    if not expected_hash or actual_hash != expected_hash:
        raise RuntimeError(f"Unexpected source archive SHA-256 for {name}: {actual_hash}")

    with tarfile.open(target) as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        if not members:
            raise RuntimeError(f"Empty source archive: {name}")
        for basename in LICENSE_NAMES:
            matches = [
                member for member in members
                if len(pathlib.PurePosixPath(member.name).parts) == 2
                and member.name.endswith("/" + basename)
            ]
            if matches:
                content = archive.extractfile(matches[0])
                assert content is not None
                (ASSETS / f"{name}-{basename}.txt").write_bytes(content.read())

        if name == "ffmpegkit":
            for suffix, output in (
                ("/android-7.1-lts/scripts/source.sh", "source-versions.sh"),
                ("/.github/workflows/build-71-free-https.yml", "upstream-build.yml"),
            ):
                member = next(member for member in members if member.name.endswith(suffix))
                content = archive.extractfile(member)
                assert content is not None
                (EVIDENCE / output).write_bytes(content.read())
        if name == "gnutls":
            for suffix in (
                "doc/COPYING.LESSER", "lib/accelerated/x86/license.txt", "lib/inih/LICENSE.txt",
                "devel/perlasm/license.txt", "devel/perlasm/license-gnutls.txt",
                "devel/perlasm/license-vpaes.txt",
            ):
                member = next(member for member in members if member.name.endswith("/" + suffix))
                content = archive.extractfile(member)
                assert content is not None
                (ASSETS / ("gnutls-" + suffix.replace("/", "-") + ".txt")).write_bytes(content.read())

    result = {
        "name": name,
        "repository": f"https://github.com/{repo}",
        "commit": commit,
        "url": url,
        "file": target.name,
        "sha256": actual_hash,
        "bytes": target.stat().st_size,
    }
    print(f"Verified {name} source archive {commit} ({target.stat().st_size} bytes)", flush=True)
    return result


def inspect_aar(aar: pathlib.Path, reviewed: dict[str, object]) -> list[dict[str, str]]:
    native = []
    found_abis: set[str] = set()
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if name.startswith("res/raw/license") and name.endswith(".txt"):
                (ASSETS / pathlib.PurePosixPath(name).name).write_bytes(archive.read(name))
            match = re.fullmatch(r"jni/(arm64-v8a|x86_64)/libavcodec\.so", name)
            if not match:
                continue
            abi = match.group(1)
            found_abis.add(abi)
            strings = re.findall(rb"[ -~]{20,}", archive.read(name))
            configurations = [value.decode("ascii", errors="replace") for value in strings if b"--enable-" in value]
            versions = [value.decode("ascii", errors="replace") for value in strings if value.startswith(b"FFmpeg version ")]
            if len(configurations) != 1 or len(versions) != 1:
                raise RuntimeError(f"Could not identify FFmpeg configuration/version for {abi}.")
            config = configurations[0]
            if any(flag in config for flag in reviewed["forbiddenFfmpegOptions"]):
                raise RuntimeError(f"The {abi} build enables GPL or nonfree FFmpeg components.")
            required = reviewed["requiredFfmpegOptions"]
            if any(flag not in config for flag in required):
                raise RuntimeError(f"The {abi} build is missing required HTTPS/LGPL flags.")
            native.append({"abi": abi, "file": name, "configuration": config, "version": versions[0]})
    if found_abis != set(reviewed["expectedAbis"]):
        raise RuntimeError(f"Unexpected FFmpeg AAR ABIs: {sorted(found_abis)}")
    return native


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aar", type=pathlib.Path, required=True, help="The AAR produced by build_ffmpeg_source.sh")
    args = parser.parse_args()
    aar = args.aar.resolve()
    if not aar.is_file():
        raise FileNotFoundError(aar)

    reviewed = json.loads(REVIEW.read_text(encoding="utf-8"))
    if reviewed.get("redistributionReady") is not True or reviewed.get("sourceGaps"):
        raise RuntimeError("The reviewed FFmpeg source-build policy blocks redistribution.")
    global EXPECTED_HASHES
    EXPECTED_HASHES = reviewed.get("sourceSha256", {})
    if set(EXPECTED_HASHES) != {name for name, _, _ in SOURCES}:
        raise RuntimeError("Manifest source archive pins do not cover every build input.")

    for directory in (OUT, EVIDENCE, ASSETS):
        directory.mkdir(parents=True, exist_ok=True)
    for name in ("LICENSE", "NOTICE"):
        (ASSETS / ("CastTrace-" + name + ".txt")).write_bytes((ROOT / name).read_bytes())

    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as executor:
        sources = list(executor.map(collect, SOURCES))
    native = inspect_aar(aar, reviewed)
    digest = sha256(aar)
    build_script = ROOT / "scripts/build_ffmpeg_source.sh"
    manifest = {
        "artifact": reviewed["artifact"],
        "sha256": digest,
        "bytes": aar.stat().st_size,
        "license": "LGPL-3.0",
        "buildCommit": reviewed["upstreamBuildCommit"],
        "buildRecipe": reviewed["buildRecipe"],
        "buildPatches": reviewed["patches"],
        "buildEnvironment": reviewed["buildEnvironment"],
        "native": native,
        "sources": sources,
        "systemLibraries": reviewed["systemLibraries"],
        "redistributionReady": reviewed["redistributionReady"],
        "sourceGaps": reviewed["sourceGaps"],
        "verificationScope": reviewed["verificationScope"],
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"
    (OUT / "manifest.json").write_bytes(manifest_bytes)

    sidecar = OUT / "ffmpeg-kit-https-7.1.6.aar.sha256"
    sidecar.write_text(f"{digest}  ffmpeg-kit-https-7.1.6.aar\n", encoding="ascii")
    bundle = OUT / "casttrace-ffmpeg-7.1.6-source-materials.zip"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_STORED) as archive:
        for source in sources:
            archive.write(OUT / source["file"], source["file"])
        archive.write(OUT / "manifest.json", "manifest.json")
        archive.write(sidecar, sidecar.name)
        archive.write(build_script, "build/build_ffmpeg_source.sh")
        archive.write(REVIEW, "build/source-build.json")
        for patch in sorted(PATCHES.glob("*.patch")):
            archive.write(patch, "build/patches/" + patch.name)
        for path in sorted(EVIDENCE.rglob("*")):
            if path.is_file():
                archive.write(path, "evidence/" + path.relative_to(EVIDENCE).as_posix())
        for path in sorted(ASSETS.glob("*.txt")):
            archive.write(path, "licenses/" + path.name)
    print(f"Source-built FFmpegKit AAR SHA-256: {digest}")
    print(f"Corresponding-source materials ready: {bundle}")


EXPECTED_HASHES: dict[str, str] = {}

if __name__ == "__main__":
    main()
