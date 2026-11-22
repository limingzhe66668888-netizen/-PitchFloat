# -*- coding: utf-8 -*-
"""生成应用图标（纯标准库，无 Pillow 依赖）。"""
import math
import os
import struct
import zlib


def write_png(path, w, h, f):
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        for x in range(w):
            rows.extend(f(x, y))

    def chunk(t, d):
        return (struct.pack('>I', len(d)) + t + d
                + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(rows), 9))
    png += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as fp:
        fp.write(png)


# ---------- 启动图标 192x192：深蓝圆底 + 青色音高波浪 ----------
W = H = 192
CX = CY = 95.5
R = 92.0
BG = (33, 41, 89)
WAVE = (124, 219, 255)


def launcher(x, y):
    d = math.hypot(x - CX, y - CY)
    if d > R + 1.2:
        return (0, 0, 0, 0)
    aa = max(0.0, min(1.0, R + 0.6 - d))
    t = (x - 28.0) / 136.0
    wy = CY + 30.0 * math.sin(t * math.pi * 3.0)
    dist = abs(y - wy)
    if dist < 6.0:
        c = WAVE
    elif dist < 9.0:
        m = (dist - 6.0) / 3.0
        c = tuple(int(WAVE[i] * (1 - m) + BG[i] * m) for i in range(3))
    else:
        c = BG
    return (c[0], c[1], c[2], int(255 * aa))


write_png('res/drawable/ic_launcher.png', W, H, launcher)


# ---------- 通知图标 24x24：白色圆点 + 透明正弦镂空 ----------
def stat(x, y):
    d = math.hypot(x - 11.5, y - 11.5)
    if d > 10.8:
        return (0, 0, 0, 0)
    t = (x - 3.0) / 18.0
    wy = 11.5 + 5.0 * math.sin(t * math.pi * 2.0)
    if abs(y - wy) < 2.4:
        return (0, 0, 0, 0)
    aa = max(0.0, min(1.0, 10.3 + 0.5 - d))
    return (255, 255, 255, int(255 * aa))


write_png('res/drawable/ic_stat.png', 24, 24, stat)
print('icons written: res/drawable/ic_launcher.png, res/drawable/ic_stat.png')
