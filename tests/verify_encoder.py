"""Decode real encoder output with Pillow, including LZW dictionary growth/reset."""
from pathlib import Path
import subprocess
import hashlib
import json
import os
import shutil
from PIL import Image, ImageSequence

root = Path(__file__).resolve().parents[1]
out = root / 'build' / 'encoder-test'
out.mkdir(parents=True, exist_ok=True)

def java_tool(name):
    home = os.environ.get('JAVA_HOME')
    tool = Path(home) / 'bin' / (name + ('.exe' if os.name == 'nt' else '')) if home else shutil.which(name)
    if not tool or not Path(tool).is_file():
        raise SystemExit(f'Cannot find {name}; install JDK 17 and set JAVA_HOME')
    return str(tool)

subprocess.run([java_tool('javac'), '-d', str(out), str(root/'app/src/main/java/com/stickerbridge/app/GifWriter.java'), str(root/'tests/GifWriterProbe.java')], check=True)
subprocess.run([java_tool('java'), '-cp', str(out), 'GifWriterProbe', str(out)], check=True)
results = []
for i, size in enumerate([(1,1),(37,23),(290,300),(320,240)]):
    image = Image.open(out/f'case{i}.gif')
    assert image.size == size
    assert image.n_frames == 3
    frames = []
    for n, frame in enumerate(ImageSequence.Iterator(image)):
        rgba = frame.convert('RGBA')
        assert frame.info['duration'] == 120+n*50
        if i == 1:
            assert rgba.getpixel((0,0))[3] == 0
            assert rgba.getpixel((10,10))[3] == 255
        if i == 2:
            assert rgba.getpixel((0,0)) == (255,255,255,255)
        frames.append(hashlib.sha256(rgba.tobytes()).hexdigest())
    assert len(set(frames)) == 3
    results.append({'case': i, 'size': size, 'frames': len(frames), 'decoded': True})
print(json.dumps(results))
