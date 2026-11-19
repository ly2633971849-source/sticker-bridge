"""Verify ISO media classification and source index compatibility with JDK 17."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]


def java_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        tool = Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
    else:
        tool = shutil.which(name)
    if not tool or not Path(tool).is_file():
        raise SystemExit(f'Cannot find {name}; install JDK 17 and set JAVA_HOME')
    return str(tool)


with tempfile.TemporaryDirectory(prefix='stickerbridge-media-brands-') as output:
    subprocess.run(
        [java_tool('javac'), '-encoding', 'UTF-8', '-d', output,
         str(root / 'app/src/main/java/com/stickerbridge/app/IsoMediaBrands.java'),
         str(root / 'app/src/main/java/com/stickerbridge/app/SourceIndexFiles.java'),
         str(root / 'tests/MediaBrandsProbe.java'),
         str(root / 'tests/SourceIndexFilesProbe.java')],
        check=True,
    )
    subprocess.run(
        [java_tool('java'), '-cp', output, 'com.stickerbridge.app.MediaBrandsProbe', output],
        check=True,
    )
    subprocess.run(
        [java_tool('java'), '-cp', output, 'com.stickerbridge.app.SourceIndexFilesProbe', output],
        check=True,
    )
