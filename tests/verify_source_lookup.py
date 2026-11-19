"""Run the source lookup schedule regression without an Android device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        name += '.exe' if os.name == 'nt' else ''
        path = Path(java_home) / 'bin' / name
        return str(path)
    return shutil.which(name) or name


with tempfile.TemporaryDirectory(prefix='stickerbridge-lookup-') as classes:
    subprocess.run(
        [java_tool('javac'), '-encoding', 'UTF-8', '-d', classes,
         str(ROOT / 'app/src/main/java/com/stickerbridge/app/SourceLookupPlan.java'),
         str(ROOT / 'tests/SourceLookupPlanProbe.java')],
        check=True,
    )
    subprocess.run(
        [java_tool('java'), '-cp', classes, 'com.stickerbridge.app.SourceLookupPlanProbe'],
        check=True,
    )
