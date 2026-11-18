"""Write a synthetic JPEG that declares a motion photo but has no video payload."""
import argparse
import io
from pathlib import Path
import struct

from PIL import Image, ImageDraw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    image = Image.new('RGB', (96, 72), (22, 47, 86))
    ImageDraw.Draw(image).rectangle((20, 18, 76, 54), fill=(239, 172, 60))
    with io.BytesIO() as stream:
        image.save(stream, format='JPEG')
        jpeg = stream.getvalue()
    xmp = (
        b'http://ns.adobe.com/xap/1.0/\x00'
        b'<x:xmpmeta xmlns:x="adobe:ns:meta/">'
        b'<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        b'<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" '
        b'GCamera:MicroVideo="1"/>'
        b'</rdf:RDF></x:xmpmeta>'
    )
    args.output.write_bytes(jpeg[:2] + b'\xff\xe1' + struct.pack('>H', len(xmp) + 2) + xmp + jpeg[2:])


if __name__ == '__main__':
    main()
