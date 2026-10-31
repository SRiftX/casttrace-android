"""Collect the pinned FFmpegKit source bundle and verify the unchanged native artifact.

Uses curl for HTTPS downloads and gh for public GitHub metadata. Never executes
downloaded build scripts. Archives stay under ignored build/compliance/.
"""
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess
import tarfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/compliance/ffmpeg-7.1.6'
EVIDENCE = ROOT / 'third_party/ffmpeg'
ASSETS = ROOT / 'app/src/main/assets/licenses'
AAR_SHA = 'e80e5ee52248a3dcb1dda0524dd784fe4bbb212dcd4d575e82e15d21a002d860'
MAVEN = 'https://repo.maven.apache.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-https/7.1.6/'
BUILD_COMMIT = '0cc470227a1730ce3d467967677de4f54e759f7a'
SOURCES = [
    ('ffmpegkit', 'ffmpegkit-maintained/ffmpeg', BUILD_COMMIT),
    ('ffmpeg', 'arthenica/FFmpeg', '3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587'),
    ('cpu-features', 'arthenica/cpu_features', '936b9ab5515dead115606559502e3864958f7f6e'),
    ('gmp', 'arthenica/gmp', '66a538082b4a485bd2b34012db96e44a86a8e712'),
    ('gnutls', 'arthenica/gnutls', 'ca61668d7764fc29fb4cc2aa396cb035e176636d'),
    ('libiconv', 'arthenica/libiconv', '4b9c27bec873392533dd3ef309cd62962a644e18'),
    ('nettle', 'arthenica/nettle', '990abad16ceacd070747dcc76ed16a39c129321e'),
    ('libiconv-gnulib', 'arthenica/gnulib', '485d983b7795548fb32b12fbe8370d40789e88c4'),
    # Declared submodule, not proof of the revision selected by --remote in CI.
    ('gnutls-declared-gnulib', 'arthenica/gnulib', '3639c57a970191e0bf7a9789bd1341786d0255a1'),
]
EXPECTED_SOURCE_HASHES = {}

def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1048576), b''):
            digest.update(chunk)
    return digest.hexdigest()

def api(endpoint):
    result = subprocess.run(['gh', 'api', endpoint], capture_output=True, check=True)
    return json.loads(result.stdout)

def download(url, target):
    if not target.exists():
        part = target.with_name(target.name + '.download')
        subprocess.run(['curl.exe' if __import__('os').name == 'nt' else 'curl', '-sSfL',
                        '--proto', '=https', '--proto-redir', '=https', '--retry', '3',
                        '--connect-timeout', '30', '--max-time', '900', url, '-o', str(part)], check=True)
        part.replace(target)
    return target

def collect(item):
    name, repo, ref = item
    if re.fullmatch('[a-f0-9]{40}', ref):
        commit = ref
    else:
        obj = api(f'repos/{repo}/git/ref/tags/{ref}')['object']
        while obj['type'] == 'tag':
            obj = api(f'repos/{repo}/git/tags/{obj["sha"]}')['object']
        assert obj['type'] == 'commit'
        commit = obj['sha']
    url = f'https://codeload.github.com/{repo}/tar.gz/{commit}'
    target = download(url, OUT / f'{name}-{commit}.tar.gz')
    actual_hash = sha(target)
    expected_hash = EXPECTED_SOURCE_HASHES.get(name)
    assert expected_hash and actual_hash == expected_hash, f'Unexpected source archive hash: {name}'
    with tarfile.open(target) as archive:
        # Read rather than extract an untrusted archive into the workspace.
        members = [m for m in archive.getmembers() if m.isfile()]
        assert members, f'Empty source archive: {name}'
        for basename in ('COPYING', 'COPYING.LIB', 'COPYING.LESSER', 'LICENSE', 'LICENSE.md', 'LICENSE.LGPLv3', 'LICENSE.GPLv3', 'COPYING.GPLv3', 'COPYING.LGPLv2.1', 'COPYING.LGPLv3', 'COPYING.LESSERv3', 'COPYINGv3', 'THIRD-PARTY-NOTICES.txt'):
            matches = [m for m in members if len(pathlib.PurePosixPath(m.name).parts) == 2 and m.name.endswith('/' + basename)]
            if matches:
                (ASSETS / f'{name}-{basename}.txt').write_bytes(archive.extractfile(matches[0]).read())
        if name == 'ffmpegkit':
            for suffix, output in [('/android-7.1-lts/scripts/source.sh', 'source-versions.sh'),
                                   ('/.github/workflows/build-71-free-https.yml', 'upstream-build.yml')]:
                member = next(m for m in members if m.name.endswith(suffix))
                (EVIDENCE / output).write_bytes(archive.extractfile(member).read())
        if name == 'gnutls':
            for suffix in ('doc/COPYING.LESSER', 'lib/accelerated/x86/license.txt',
                           'lib/inih/LICENSE.txt', 'devel/perlasm/license.txt',
                           'devel/perlasm/license-gnutls.txt', 'devel/perlasm/license-vpaes.txt'):
                member = next(m for m in members if m.name.endswith('/' + suffix))
                (ASSETS / ('gnutls-' + suffix.replace('/', '-') + '.txt')).write_bytes(archive.extractfile(member).read())
    result = {'name': name, 'repository': f'https://github.com/{repo}', 'ref': ref,
              'commit': commit, 'url': url, 'file': target.name, 'sha256': actual_hash, 'bytes': target.stat().st_size}
    print(f'Collected {name}: {commit} ({target.stat().st_size} bytes)', flush=True)
    return result

def main():
    reviewed_manifest = json.loads((EVIDENCE / 'manifest.json').read_text(encoding='utf8'))
    global EXPECTED_SOURCE_HASHES
    EXPECTED_SOURCE_HASHES = {source['name']: source['sha256'] for source in reviewed_manifest.get('sources', [])}
    assert set(EXPECTED_SOURCE_HASHES) == {name for name, _, _ in SOURCES}, 'Manifest source archive pins are incomplete.'
    for directory in (OUT, EVIDENCE, ASSETS):
        directory.mkdir(parents=True, exist_ok=True)
    for name in ('LICENSE', 'NOTICE'):
        (ASSETS / ('CastTrace-' + name + '.txt')).write_bytes((ROOT / name).read_bytes())
    aar = ROOT / 'app/libs/ffmpeg-kit-https-7.1.6.aar'
    assert sha(aar) == AAR_SHA, 'Unexpected FFmpeg binary; do not reuse these conclusions.'
    sidecar = download(MAVEN + aar.name + '.sha256', OUT / 'maven-aar.sha256').read_text().strip()
    assert sidecar.lower() == AAR_SHA
    pom = download(MAVEN + 'ffmpeg-kit-https-7.1.6.pom', OUT / 'maven.pom')
    (EVIDENCE / 'maven.pom').write_bytes(pom.read_bytes())
    java = download(MAVEN + 'ffmpeg-kit-https-7.1.6-sources.jar', OUT / 'ffmpeg-kit-https-7.1.6-sources.jar')
    java_hash = download(MAVEN + java.name + '.sha256', OUT / 'java-sources.sha256').read_text().strip()
    assert sha(java) == java_hash
    binaries = []
    with zipfile.ZipFile(aar) as archive:
        for name in archive.namelist():
            if name.startswith('res/raw/license') and name.endswith('.txt'):
                (ASSETS / pathlib.PurePosixPath(name).name).write_bytes(archive.read(name))
            if name.endswith('/libavcodec.so'):
                strings = re.findall(rb'[ -~]{20,}', archive.read(name))
                config = next(s.decode() for s in strings if b'--enable-' in s)
                assert '--enable-gpl' not in config and '--enable-nonfree' not in config
                assert '--enable-version3' in config and '--enable-gnutls' in config
                binaries.append({'file': name, 'configuration': config, 'version': next(s.decode() for s in strings if s.startswith(b'FFmpeg version '))})
    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as executor:
        sources = list(executor.map(collect, SOURCES))
    manifest = {'artifact': reviewed_manifest['artifact'], 'sha256': AAR_SHA,
                'url': reviewed_manifest['url'], 'license': reviewed_manifest['license'],
                'buildCommit': reviewed_manifest['buildCommit'], 'buildRun': reviewed_manifest['buildRun'],
                'buildAssociation': reviewed_manifest['buildAssociation'],
                'native': binaries, 'sources': sources,
                'javaSources': {'file': java.name, 'url': MAVEN + java.name, 'sha256': sha(java)},
                'systemLibraries': reviewed_manifest['systemLibraries'],
                'redistributionReady': reviewed_manifest['redistributionReady'],
                'sourceGaps': reviewed_manifest['sourceGaps'],
                'verificationScope': reviewed_manifest['verificationScope']}
    for target in (EVIDENCE / 'manifest.json', OUT / 'manifest.json'):
        target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    with zipfile.ZipFile(OUT / 'casttrace-ffmpeg-7.1.6-source-materials.zip', 'w', zipfile.ZIP_STORED) as bundle:
        for source in sources:
            bundle.write(OUT / source['file'], source['file'])
        for path in (OUT / 'manifest.json', java, pom):
            bundle.write(path, path.name)
        for path in EVIDENCE.glob('*'):
            if path.is_file(): bundle.write(path, 'evidence/' + path.name)
        for path in ASSETS.glob('*.txt'):
            bundle.write(path, 'licenses/' + path.name)
    status = 'passed' if manifest['redistributionReady'] and not manifest['sourceGaps'] else 'blocked'
    print(f'Source materials collected; public binary release gate {status}: ' + str(OUT / 'casttrace-ffmpeg-7.1.6-source-materials.zip'))

if __name__ == '__main__':
    main()
