"""Drive the document picker and crop screen with a synthetic 480x360, 2s clip."""
import argparse
import hashlib
import io
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageStat


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--device', required=True)
parser.add_argument('--file-name', default='sticker-crop-qa.mp4')
parser.add_argument('--source', type=Path, required=True, help='Host copy of the synthetic video on the device')
parser.add_argument('--ffmpeg', default='ffmpeg')
parser.add_argument('--rotated', action='store_true')
args = parser.parse_args()
adb = [args.adb, '-s', args.device]
package = 'com.stickerbridge.app'
dump_path = '/data/local/tmp/stickerbridge-crop-test.xml'


def execute(*command):
    return subprocess.check_output([*adb, *map(str, command)], stderr=subprocess.PIPE, timeout=22)


def ui():
    execute('shell', 'uiautomator', 'dump', dump_path)
    return list(ET.fromstring(execute('exec-out', 'cat', dump_path)).iter('node'))


def find(label, key='text'):
    for attempt in range(5):
        matches = [node for node in ui() if node.get(key) == label]
        if matches:
            return matches[0]
        if attempt < 4:
            time.sleep(.3)
    raise AssertionError('Could not find ' + label)


def bounds(node):
    return tuple(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def tap(node):
    left, top, right, bottom = bounds(node)
    execute('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2)


def status():
    raw = execute('shell', 'content', 'call', '--uri', f'content://{package}.control', '--method', 'status')
    return json.JSONDecoder().raw_decode(raw.decode('utf-8').split('json=', 1)[1])[0]


session = status()
execute('shell', 'am', 'start', '-n', f'{package}/.MainActivity')
tap(find('更多'))
tap(find('从视频裁剪表情'))
tap(find(args.file_name))
frame = find('视频预览，拖动选取表情区域', 'content-desc')
left, top, right, bottom = bounds(frame)
video_width, video_height = (360, 480) if args.rotated else (480, 360)
scale = min((right - left) / video_width, (bottom - top) / video_height)
width, height = video_width * scale, video_height * scale
origin_x, origin_y = (left + right - width) / 2, (top + bottom - height) / 2
region = [.35, .35, .65, .65]
target = [round(origin_x + width * region[0]), round(origin_y + height * region[1]),
          round(origin_x + width * region[2]), round(origin_y + height * region[3])]
execute('shell', 'input', 'swipe', *target, 480)
tap(find('转换成 GIF'))
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    state = status()
    if state['jobId'] != session['jobId'] and not state['busy']:
        break
    time.sleep(.2)
else:
    raise AssertionError('Crop conversion did not finish')
assert state['savedResult'], state['detail']
result = state['last']
assert result['inputMethod'] == 'cropped_video' and not result['reusedConversion']
data = execute('exec-out', 'content', 'read', '--uri', result['mediaUri'])
assert hashlib.sha256(data).hexdigest() == result['outputSha256']
image = Image.open(io.BytesIO(data))
expected = round(video_width * .3), round(video_height * .3)
assert image.format == 'GIF' and all(abs(a - b) <= 3 for a, b in zip(image.size, expected))
assert 29 <= image.n_frames <= 31, image.n_frames
reference = subprocess.check_output(
    [args.ffmpeg, '-v', 'error', '-i', str(args.source), '-frames:v', '1', '-f', 'image2pipe',
     '-c:v', 'png', 'pipe:1'], timeout=10)
full = Image.open(io.BytesIO(reference)).convert('RGB')
assert full.size == (video_width, video_height), 'Fixture orientation is wrong'
pixel_box = tuple(round(value * (video_width if index % 2 == 0 else video_height))
                  for index, value in enumerate(region))
expected_frame = full.crop(pixel_box).resize(image.size)
delta = ImageStat.Stat(ImageChops.difference(expected_frame, image.convert('RGB'))).mean
assert sum(delta) / 3 < 24, ('Video crop differs from reference', delta)
duration, frames = 0, set()
for index in range(image.n_frames):
    image.seek(index)
    duration += image.info.get('duration', 0)
    visual = image.convert('RGB')
    frames.add(hashlib.sha256(visual.tobytes()).digest())
assert len(frames) > 8 and abs(duration - 2000) <= 30
print(json.dumps({'size': image.size, 'frames': image.n_frames, 'distinctFrames': len(frames),
                  'durationMs': duration, 'rotated': args.rotated, 'meanPixelError': sum(delta) / 3,
                  'mediaSha256': result['outputSha256']}))
