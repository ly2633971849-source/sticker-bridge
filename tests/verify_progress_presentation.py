"""Verify the shared in-app and floating progress display with JDK 17."""
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        return name
    suffix = '.exe' if os.name == 'nt' else ''
    return str(Path(java_home) / 'bin' / (name + suffix))


with tempfile.TemporaryDirectory(prefix='stickerbridge-progress-') as classes:
    subprocess.run(
        [java_tool('javac'), '-encoding', 'UTF-8', '-d', classes,
         str(ROOT / 'app/src/main/java/com/stickerbridge/app/ProgressPresentation.java'),
         str(ROOT / 'tests/ProgressPresentationProbe.java')],
        check=True,
    )
    subprocess.run(
        [java_tool('java'), '-cp', classes, 'com.stickerbridge.app.ProgressPresentationProbe'],
        check=True,
    )
