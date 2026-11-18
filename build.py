"""Build and verify a StickerBridge APK with Python 3.11+, JDK 17 and Android SDK 36."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / 'build'
ANDROID = '{http://schemas.android.com/apk/res/android}'
EXE = '.exe' if os.name == 'nt' else ''
KEY_ENV = 'STICKERBRIDGE_KEYSTORE'
ALIAS_ENV = 'STICKERBRIDGE_KEY_ALIAS'
STORE_PASSWORD_ENV = 'STICKERBRIDGE_STORE_PASSWORD'
KEY_PASSWORD_ENV = 'STICKERBRIDGE_KEY_PASSWORD'
SIGNING_ENV = (KEY_ENV, ALIAS_ENV, STORE_PASSWORD_ENV, KEY_PASSWORD_ENV)


def run(args, signing=False):
    env = dict(os.environ)
    if not signing:
        for name in SIGNING_ENV:
            env.pop(name, None)
    process = subprocess.run([str(arg) for arg in args], capture_output=True, env=env, stdin=subprocess.DEVNULL)

    def decode(data):
        try:
            return data.decode('utf-8')
        except UnicodeDecodeError:
            return data.decode('gbk' if os.name == 'nt' else 'utf-8', 'replace')

    output = decode(process.stdout) + decode(process.stderr)
    if signing:
        for name in (STORE_PASSWORD_ENV, KEY_PASSWORD_ENV):
            if env.get(name):
                output = output.replace(env[name], '[redacted]')
    if output.strip():
        print(output.strip(), flush=True)
    if process.returncode:
        raise RuntimeError(f'{Path(str(args[0])).name} exited with {process.returncode}')


def java_tool(jdk, name):
    found = shutil.which(name) if jdk is None else jdk / 'bin' / (name + EXE)
    if not found or not Path(found).is_file():
        raise FileNotFoundError(f'Cannot find {name}; install JDK 17 and set JAVA_HOME or --jdk')
    return Path(found)


def manifest_url(value):
    if not value:
        return ''
    url = urlsplit(value)
    if (not value.startswith('https://') or not url.hostname or url.username is not None
            or url.password is not None or any(char in value for char in '?#')
            or not re.fullmatch(r'[A-Za-z0-9.\-:\[\]]+', url.netloc)
            or not re.fullmatch(r'/[A-Za-z0-9._/\-]+', url.path)
            or any(part in ('.', '..') for part in url.path.split('/'))
            or url.port == 0):
        raise ValueError('Update manifest must be an HTTPS URL without credentials, query or fragment')
    return value


def sign_args(release, jdk):
    if release:
        missing = [name for name in SIGNING_ENV if not os.environ.get(name)]
        if missing:
            raise ValueError('Missing release signing environment: ' + ', '.join(missing))
        key = Path(os.environ[KEY_ENV])
        if not key.is_file():
            raise FileNotFoundError(f'No keystore at the path in {KEY_ENV}')
        return ['--ks', key, '--ks-key-alias', os.environ[ALIAS_ENV],
                '--ks-pass', 'env:' + STORE_PASSWORD_ENV, '--key-pass', 'env:' + KEY_PASSWORD_ENV]

    key = BUILD / 'development.keystore'
    if not key.exists():
        run([java_tool(jdk, 'keytool'), '-genkeypair', '-keystore', key,
             '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=StickerBridge Development'])
    return ['--ks', key, '--ks-key-alias', 'androiddebugkey',
            '--ks-pass', 'pass:android', '--key-pass', 'pass:android']


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='backslashreplace')
    sys.stderr.reconfigure(encoding='utf-8', errors='backslashreplace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME') or None)
    parser.add_argument('--output', type=Path, default=BUILD / 'sticker-bridge.apk')
    parser.add_argument('--update-manifest-url', default=os.environ.get('STICKERBRIDGE_UPDATE_MANIFEST_URL', ''))
    parser.add_argument('--release', action='store_true', help='Use the configured release signing identity')
    parser.add_argument('--debuggable', action='store_true', help='Allow adb run-as for local testing')
    options = parser.parse_args()
    if not options.sdk:
        parser.error('install Android SDK 36 / Build Tools 36.0.0 and set ANDROID_HOME or --sdk')
    if options.release and options.debuggable:
        parser.error('--debuggable is not allowed with --release')
    try:
        update_url = manifest_url(options.update_manifest_url)
    except ValueError as error:
        parser.error(str(error))
    if options.release and not update_url:
        parser.error('--release needs an explicit HTTPS --update-manifest-url or STICKERBRIDGE_UPDATE_MANIFEST_URL')

    sdk, jdk = options.sdk, options.jdk
    tools = sdk / 'build-tools' / '36.0.0'
    platform = sdk / 'platforms' / 'android-36' / 'android.jar'
    aapt = tools / ('aapt2' + EXE)
    align = tools / ('zipalign' + EXE)
    java, javac = java_tool(jdk, 'java'), java_tool(jdk, 'javac')
    d8, apksigner = tools / 'lib' / 'd8.jar', tools / 'lib' / 'apksigner.jar'
    for required in (platform, aapt, align, d8, apksigner):
        if not required.is_file():
            raise FileNotFoundError(f'Missing installed tool: {required}')
    app_manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    app = ET.parse(app_manifest).getroot()
    min_sdk = app.find('uses-sdk').attrib[ANDROID + 'minSdkVersion']
    BUILD.mkdir(exist_ok=True)
    signer = sign_args(options.release, jdk)
    destination = options.output
    if destination.resolve() == signer[1].resolve():
        parser.error('APK output must be different from the signing keystore')
    classes, generated, dex = [BUILD / part for part in ('classes', 'generated', 'dex')]
    for folder in (classes, generated, dex):
        folder.mkdir(parents=True, exist_ok=True)
    build_root = BUILD.resolve()
    for folder, pattern in ((classes, '*.class'), (dex, '*.dex')):
        target = folder.resolve()
        if not target.is_relative_to(build_root):
            raise RuntimeError('Build output escaped the project directory')
        for old in target.rglob(pattern):
            if not old.resolve().is_relative_to(target):
                raise RuntimeError('Unexpected linked build output')
            old.unlink()

    resources = BUILD / 'resources.zip'
    resource_apk = BUILD / 'resources.apk'
    run([aapt, 'compile', '--dir', ROOT / 'app/src/main/res', '-o', resources])
    overlay_args = []
    if update_url:
        values = BUILD / 'generated-update-res' / 'values'
        values.mkdir(parents=True, exist_ok=True)
        overlay = ET.Element('resources')
        ET.SubElement(overlay, 'string', {'name': 'app_update_manifest', 'translatable': 'false'}).text = update_url
        ET.ElementTree(overlay).write(values / 'update.xml', encoding='utf-8', xml_declaration=True)
        run([aapt, 'compile', values / 'update.xml', '-o', BUILD])
        overlay_args = ['-R', BUILD / 'values_update.arsc.flat']
    if options.debuggable:
        app.find('application').set(ANDROID + 'debuggable', 'true')
        app_manifest = BUILD / 'debug-AndroidManifest.xml'
        ET.register_namespace('android', ANDROID[1:-1])
        ET.ElementTree(app).write(app_manifest, encoding='utf-8', xml_declaration=True)
    run([aapt, 'link', '-I', platform, '--manifest', app_manifest, '--java', generated,
         '-o', resource_apk, resources, *overlay_args])
    sources = sorted((ROOT / 'app/src/main/java').rglob('*.java')) + sorted(generated.rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', platform, '-d', classes, *sources])
    jar_path = BUILD / 'classes.jar'
    with zipfile.ZipFile(jar_path, 'w', zipfile.ZIP_DEFLATED) as jar:
        for file in sorted(classes.rglob('*.class')):
            jar.write(file, file.relative_to(classes).as_posix())
    run([java, '-cp', d8, 'com.android.tools.r8.D8', '--min-api', min_sdk, '--lib', platform, '--output', dex, jar_path])
    unsigned, aligned = BUILD / 'unsigned.apk', BUILD / 'aligned.apk'
    shutil.copyfile(resource_apk, unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as apk:
        for file in sorted(dex.glob('*.dex')):
            apk.write(file, file.name)
    run([align, '-f', '-p', '4', unsigned, aligned])
    destination.parent.mkdir(parents=True, exist_ok=True)
    run([java, '-jar', apksigner, 'sign', *signer, '--out', destination, aligned], signing=options.release)
    run([java, '-jar', apksigner, 'verify', '--verbose', destination])
    manifest = {'apk': destination.name, 'package': app.attrib['package'], 'version': app.attrib[ANDROID+'versionName'], 'version_code': int(app.attrib[ANDROID+'versionCode']), 'bytes': destination.stat().st_size, 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(), 'source_files': len(sources), 'sdk': 36, 'min_sdk': int(min_sdk), 'signing': 'release' if options.release else 'development', 'debuggable': options.debuggable, 'update_manifest_configured': bool(update_url), 'recording_permission': False, 'mode': 'selection_to_album', 'manual_candidate_matching': False, 'background_source_identification': True, 'sticker_only': True, 'native_comment_original': False, 'motion_photo': False, 'accessibility_service': True, 'gesture_injection': False, 'automatic_qq_import': False, 'picker_recovery': True}
    manifest.update({'persistent_feedback':True,'result_feedback_ms':3000,'hint_feedback_ms':2500,'single_feedback_view':True,'streaming_animation':True,'maximum_animation_ms':60000,'maximum_transcoded_frames':240,'warm_source_index':True})
    manifest.update({'monotonic_workflow_progress':True,'completion_after_album_publish':True,'automatic_large_sticker_bounds':True,'automatic_inner_image_bounds':True})
    manifest.update({'simple_home':True,'picker_hidden_on_own_home':True,'legacy_im_prompt_detection':True,'exclude_sticker_category_buttons':True})
    manifest.update({'sticker_only':False,'native_comment_original':True,'short_videos':True,'motion_photo':True,'video_share_import':True,'maximum_source_bytes':32*1024*1024,'verified_conversion_reuse':True,'conversion_cache_entries':8,'conversion_cache_bytes':64*1024*1024,'bounded_matcher_templates':True})
    manifest.update({'visual_live_photo_badge_check':True,'reject_static_motion_fallback':True,'revalidate_cached_static_metadata':True})
    manifest.update({'phone_only_frame_export':True,'file_import_requires_accessibility':False,'async_recent_preview':True})
    (BUILD / 'build-result.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(manifest, ensure_ascii=False))

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
