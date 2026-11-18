"""Run the selection scope regression without a phone or third-party app."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
sdk_home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk_home:
    raise SystemExit('Set ANDROID_HOME to an Android SDK with Android 36 installed')
android = Path(sdk_home) / 'platforms' / 'android-36' / 'android.jar'
if not android.is_file():
    raise SystemExit('Install Android SDK platform android-36')


def java_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        tool = Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
    else:
        tool = shutil.which(name)
    if not tool or not Path(tool).is_file():
        raise SystemExit(f'Cannot find {name}; install JDK 17 and set JAVA_HOME')
    return str(tool)


with tempfile.TemporaryDirectory(prefix='stickerbridge-targets-') as output:
    subprocess.run(
        [java_tool('javac'), '-encoding', 'UTF-8', '-cp', str(android), '-d', output,
         str(root / 'app/src/main/java/com/stickerbridge/app/SelectionTargets.java'),
         str(root / 'tests/SelectionTargetsProbe.java')],
        check=True,
    )
    subprocess.run(
        [java_tool('java'), '-cp', os.pathsep.join((output, str(android))),
         'com.stickerbridge.app.SelectionTargetsProbe'],
        check=True,
    )
