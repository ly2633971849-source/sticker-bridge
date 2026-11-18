"""Stage a signed StickerBridge release for an HTTPS static update feed."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit


PACKAGE = 'com.stickerbridge.app'
APK_LIMIT = 32 * 1024 * 1024
MANIFEST_LIMIT = 32 * 1024


@dataclass(frozen=True)
class ApkInfo:
    version_code: int
    version_name: str
    signer: str
    digest: str
    size: int


def run(*args):
    result = subprocess.run([str(arg) for arg in args], capture_output=True, timeout=60)
    if result.returncode:
        message = result.stderr.decode('utf-8', 'replace').strip()
        raise ValueError(f'{Path(args[0]).name}: {message or "APK verification failed"}')
    return result.stdout.decode('utf-8', 'replace')


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def characters(text):
    return len(text.encode('utf-16-le')) // 2


def inspect_apk(path, aapt, java, apksigner):
    size = path.stat().st_size
    if not path.is_file() or not 0 < size <= APK_LIMIT:
        raise ValueError(f'{path.name}: expected a signed APK of at most 32 MiB')
    fields = re.search(
        r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^'\r\n]+)'",
        run(aapt, 'dump', 'badging', path), re.MULTILINE,
    )
    if not fields or fields[1] != PACKAGE:
        raise ValueError(f'{path.name}: APK package must be {PACKAGE}')
    version_code, version_name = int(fields[2]), fields[3]
    if not 0 < version_code <= 2_100_000_000 or not 0 < characters(version_name) <= 128:
        raise ValueError(f'{path.name}: unsupported version code or version name')
    signature = run(java, '-jar', apksigner, 'verify', '--print-certs', path)
    signers = re.findall(
        r'^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$',
        signature, re.MULTILINE,
    )
    if len(signers) != 1:
        raise ValueError(f'{path.name}: expected exactly one verified signing certificate')
    return ApkInfo(version_code, version_name, signers[0].lower(), digest(path), size)


def directory_url(raw):
    url = urlsplit(raw)
    if (not raw.startswith('https://') or not url.hostname or url.username is not None
            or url.password is not None or any(char in raw for char in '?#')
            or not re.fullmatch(r'[A-Za-z0-9.\-:\[\]]+', url.netloc)
            or not re.fullmatch(r'[A-Za-z0-9._/\-]*', url.path)
            or any(part in ('.', '..') for part in url.path.split('/'))
            or url.port == 0):
        raise ValueError('Base URL must be an HTTPS directory without credentials, query or fragment')
    return raw.rstrip('/') + '/'


def stage(options):
    base = directory_url(options.base_url)
    notes_path = options.notes_file
    if notes_path.stat().st_size > MANIFEST_LIMIT:
        raise ValueError('Update notes file is too large')
    notes = notes_path.read_text(encoding='utf-8-sig').strip()
    if not 0 < characters(notes) <= 4000:
        raise ValueError('Update notes must contain 1–4000 UTF-16 code units')

    tools = options.sdk / 'build-tools' / '36.0.0'
    extension = '.exe' if os.name == 'nt' else ''
    aapt, java, apksigner = tools / f'aapt2{extension}', options.jdk / 'bin' / f'java{extension}', tools / 'lib/apksigner.jar'
    baseline = inspect_apk(options.baseline_apk, aapt, java, apksigner)
    if not 0 < options.apk.stat().st_size <= APK_LIMIT:
        raise ValueError('Candidate APK must be at most 32 MiB')
    options.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=options.output) as folder:
        temporary = Path(folder)
        candidate = temporary / 'candidate.apk'
        shutil.copyfile(options.apk, candidate)
        artifact = inspect_apk(candidate, aapt, java, apksigner)
        if artifact.signer != baseline.signer:
            raise ValueError('Candidate and baseline have different signing certificates')
        if (artifact.version_code < baseline.version_code
                or artifact.version_code == baseline.version_code and artifact.digest != baseline.digest):
            raise ValueError('Increase versionCode for a different APK')

        apk_name = f'sticker-bridge-{artifact.version_code}.apk'
        release = {
            'versionCode': artifact.version_code,
            'versionName': artifact.version_name,
            'notes': notes,
            'apkUrl': base + apk_name,
            'sha256': artifact.digest,
            'sizeBytes': artifact.size,
        }
        encoded = (json.dumps(release, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
        if len(encoded) > MANIFEST_LIMIT:
            raise ValueError('Update manifest is larger than 32 KiB')
        latest = options.output / 'latest.json'
        if latest.exists() and json.loads(latest.read_bytes())['versionCode'] > artifact.version_code:
            raise ValueError('Refusing to replace the staged manifest with an older version')
        target = options.output / apk_name
        if target.exists():
            if digest(target) != artifact.digest:
                raise ValueError(f'{apk_name} already exists with different bytes')
        else:
            candidate.replace(target)
        manifest = temporary / 'latest.json'
        manifest.write_bytes(encoded)
        manifest.replace(latest)
    print(json.dumps(release, ensure_ascii=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--baseline-apk', required=True, type=Path)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--notes-file', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    jdk = os.environ.get('JAVA_HOME')
    parser.add_argument('--sdk', type=Path, default=sdk, required=not sdk)
    parser.add_argument('--jdk', type=Path, default=jdk, required=not jdk)
    options = parser.parse_args()
    try:
        stage(options)
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
