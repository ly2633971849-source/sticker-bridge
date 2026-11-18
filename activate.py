"""Install the picker-to-album app and activate its read-only original-media source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
PACKAGE = 'com.stickerbridge.app'

def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    sdk_adb = Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb') if sdk else None
    parser.add_argument('--adb', default=str(sdk_adb) if sdk_adb and sdk_adb.is_file() else shutil.which('adb'))
    parser.add_argument('--apk', default=str(ROOT / 'build' / 'sticker-bridge.apk'))
    parser.add_argument('--no-open', action='store_true', help='Keep the current phone app in front during an update')
    parser.add_argument('--skip-install', action='store_true', help='Activate an already installed, hash-verified copy after a phone installer confirmation')
    options = parser.parse_args()
    if not options.adb:
        parser.error('Android platform-tools adb is required; set ANDROID_HOME or use --adb')
    base = [options.adb, '-s', options.device]

    def adb(*args, data=None, check=True):
        p = subprocess.run([*base, *args], input=data, capture_output=True, timeout=90)
        if check and p.returncode:
            # Pairing contents are never printed.
            raise RuntimeError(p.stderr.decode('utf-8', 'replace') or p.stdout.decode('utf-8', 'replace'))
        return p

    adb('get-state')
    if options.skip_install:
        paths = adb('shell', 'pm', 'path', PACKAGE).stdout.decode('utf-8').splitlines()
        installed = [line.removeprefix('package:').strip() for line in paths if line.startswith('package:') and line.rstrip().endswith('/base.apk')]
        if len(installed) != 1 or not installed[0].startswith('/data/app/'):
            raise RuntimeError('Cannot bind the already installed application; install this APK first.')
        remote = adb('shell', 'sha256sum', installed[0]).stdout.decode('ascii').split()[0]
        expected = hashlib.sha256(Path(options.apk).read_bytes()).hexdigest()
        if remote != expected:
            raise RuntimeError('The installed application differs from this APK; update it before activation.')
        print('Installed APK verified; continuing activation.', flush=True)
    else:
        install = adb('install', '-r', options.apk)
        print(install.stdout.decode('utf-8', 'replace').strip(), flush=True)
    if not options.no_open:
        adb('shell', 'am start -n com.stickerbridge.app/.MainActivity')
    pair = adb('shell', 'content call --uri content://com.stickerbridge.app.control --method pair')
    match = re.search(rb'token=([a-f0-9]{48})', pair.stdout)
    if not match:
        raise RuntimeError('The application did not return a setup credential to the ADB shell.')
    token = match.group(1).decode('ascii')
    forwarding = adb('forward', 'tcp:0', 'tcp:47381').stdout.decode('ascii').strip()
    if not forwarding.isdigit():
        raise RuntimeError('ADB did not assign a local forwarding port.')
    port = int(forwarding)

    def request(body):
        body = dict(body, token=token)
        raw = json.dumps(body).encode()
        with socket.create_connection(('127.0.0.1', port), 2) as s:
            s.settimeout(90)
            s.sendall(struct.pack('>i', len(raw)) + raw)
            def exact(n):
                result = bytearray()
                while len(result)<n:
                    block = s.recv(n-len(result))
                    if not block:
                        raise EOFError()
                    result.extend(block)
                return bytes(result)
            size = struct.unpack('>i', exact(4))[0]
            if size > 8192:
                raise RuntimeError('Unexpected helper response size.')
            return json.loads(exact(size))

    try:
        try:
            request({'op': 'stop'})
            time.sleep(0.3)
        except (OSError, EOFError):
            pass
        adb('push', options.apk, '/data/local/tmp/stickerbridge-helper.apk')
        adb('shell', 'umask 077; cat > /data/local/tmp/stickerbridge.key', data=token.encode())
        inner = 'export CLASSPATH=/data/local/tmp/stickerbridge-helper.apk; exec app_process /system/bin com.stickerbridge.app.ShellBridge /data/local/tmp/stickerbridge.key'
        command = 'nohup sh -c ' + shlex.quote(inner) + ' > /data/local/tmp/stickerbridge-helper.log 2>&1 < /dev/null &'
        adb('shell', command)
        ready = None
        for attempt in range(12):
            try:
                ready = request({'op': 'ping'})
                if ready.get('ok'):
                    break
            except (OSError, EOFError):
                pass
            time.sleep(0.4)
        if not ready or not ready.get('ok') or not ready.get('readOnly'):
            raise RuntimeError('Optional original-media service did not respond.')
    finally:
        adb('forward', '--remove', 'tcp:' + forwarding, check=False)
        adb('shell', 'rm -f /data/local/tmp/stickerbridge.key', check=False)
    facts = {'helperReady': True, 'installed': True}
    (ROOT/'build').mkdir(parents=True, exist_ok=True)
    (ROOT/'build'/'activation-result.json').write_text(json.dumps(facts, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(facts, ensure_ascii=False))

if __name__ == '__main__':
    main()
