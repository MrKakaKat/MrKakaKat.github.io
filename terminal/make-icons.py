#!/usr/bin/env python3
"""Generate icon-192.png and icon-512.png for the Heatmap PWA (stdlib only)."""
import struct, zlib
from pathlib import Path

BG = (0x0a, 0x13, 0x22)
# (top, height, left, right) in units of a 100x100 canvas, colour.
# Everything sits inside the central 80% so the icon survives maskable cropping.
BANDS = [
    ((22, 6, 18, 62), (0x16, 0x3e, 0x78)),
    ((31, 7, 18, 74), (0x12, 0x80, 0x96)),
    ((41, 8, 18, 82), (0xec, 0xb0, 0x48)),
    ((52, 7, 18, 68), (0xff, 0xf6, 0xde)),
    ((62, 6, 18, 56), (0x7f, 0xdc, 0xb8)),
    ((71, 6, 18, 44), (0xf2, 0xb8, 0x4b)),
]

def render(n):
    px = [[BG] * n for _ in range(n)]
    s = n / 100
    for (top, h, l, r), col in BANDS:
        y0, y1, x0, x1 = round(top * s), round((top + h) * s), round(l * s), round(r * s)
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[y][x] = col
    return px

def png(px):
    n = len(px)
    raw = b''.join(b'\0' + bytes(c for p in row for c in p) for row in px)
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))

here = Path(__file__).resolve().parent
for n in (192, 512):
    (here / f'icon-{n}.png').write_bytes(png(render(n)))
