#!/usr/bin/env python3
"""Rasterise the official DSH logo (a single SVG path) into launcher icons.

The desktop client ships its mark as one SVG path (M/C/Z only), so instead of
pulling in an SVG toolchain this flattens the beziers and scan-converts them with
the nonzero winding rule, 4x supersampled for antialiasing.

Glyph itself stays untouched -- only the presentation (dark plate, inset) is ours.
"""
import math
import os
import re
import struct
import sys
import zlib

SS = 4  # supersampling factor

# ---------------------------------------------------------------- path parsing

NUM = re.compile(r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def parse_path(d):
    """Return a list of closed polygons (flat point lists) for an M/C/Z path."""
    tokens = [t for t in re.findall(r"[MCZ]|" + NUM.pattern, d) if t.strip()]
    polys, cur = [], []
    i, x, y = 0, 0.0, 0.0

    def num():
        nonlocal i
        v = float(tokens[i])
        i += 1
        return v

    while i < len(tokens):
        t = tokens[i]
        if t == "M":
            i += 1
            if len(cur) > 1:
                polys.append(cur)
            x, y = num(), num()
            cur = [(x, y)]
        elif t == "C":
            i += 1
            pts = [cur[-1]]
            for _ in range(3):
                pts.append((num(), num()))
            # cubic bezier control points
            p0, p1, p2, p3 = pts
            segs = 32
            for s in range(1, segs + 1):
                u = s / segs
                mu = 1 - u
                bx = (mu ** 3) * p0[0] + 3 * (mu ** 2) * u * p1[0] \
                    + 3 * mu * (u ** 2) * p2[0] + (u ** 3) * p3[0]
                by = (mu ** 3) * p0[1] + 3 * (mu ** 2) * u * p1[1] \
                    + 3 * mu * (u ** 2) * p2[1] + (u ** 3) * p3[1]
                cur.append((bx, by))
            x, y = p3
        elif t in "Zz":
            i += 1
            if len(cur) > 1:
                cur.append(cur[0])
                polys.append(cur)
                cur = []
        else:
            i += 1
    if len(cur) > 1:
        polys.append(cur)
    return polys


# --------------------------------------------------------------- scanline fill

def fill(polys, w, h, box, samples_per_px=1):
    """Coverage buffer (0..1) for the polygons mapped through box=(sx,sy,scale)."""
    ox, oy, sc = box
    edges = []
    for poly in polys:
        pts = [((px - ox) * sc, (py - oy) * sc) for px, py in poly]
        n = len(pts)
        for k in range(n):
            x0, y0 = pts[k]
            x1, y1 = pts[(k + 1) % n]
            if y0 == y1:
                continue
            edges.append((y0, y1, x0, x1))
    if not edges:
        return bytearray(w * h)

    ymin = max(0, int(min(min(e[0], e[1]) for e in edges)))
    ymax = min(h - 1, int(max(max(e[0], e[1]) for e in edges)) + 1)

    cov = bytearray(w * h)
    for yy in range(ymin, ymax + 1):
        yc = yy + 0.5
        xs = []
        for y0, y1, x0, x1 in edges:
            if (y0 <= yc < y1) or (y1 <= yc < y0):
                t = (yc - y0) / (y1 - y0)
                x = x0 + t * (x1 - x0)
                xs.append((x, 1 if y1 > y0 else -1))
        if not xs:
            continue
        xs.sort()
        wind = 0
        row = yy * w
        for k in range(len(xs) - 1):
            wind += xs[k][1]
            if wind != 0:
                a = max(0, int(math.floor(xs[k][0])))
                b = min(w - 1, int(math.ceil(xs[k + 1][0])))
                for xx in range(a, b + 1):
                    cov[row + xx] = 255
    return cov


# ------------------------------------------------------------------- rendering

def render(size, path_polys, logo_frac, bg, fg):
    """Square icon: background plate + centred glyph."""
    n = size * SS
    # fit the 50x50 viewBox into logo_frac of the canvas, centred
    scale = (n * logo_frac) / 50.0
    off = (n - 50.0 * scale) / 2.0
    cov = fill(path_polys, n, n, (0.0, 0.0, 1.0)) if False else None

    # build coverage at supersampled resolution
    box = (0.0, 0.0, 1.0)
    ox = -off / scale
    oy = -off / scale
    cov = fill(path_polys, n, n, (ox, oy, scale))
    px = bytearray(size * size * 4)
    bgc = tuple(bg) + (255,) if len(bg) == 3 else tuple(bg)
    fgc = tuple(fg) + (255,) if len(fg) == 3 else tuple(fg)
    inv = 1.0 / (SS * SS)
    for j in range(size):
        for i2 in range(size):
            acc = 0
            for dy in range(SS):
                row = (j * SS + dy) * n + i2 * SS
                for dx in range(SS):
                    acc += cov[row + dx]
            a = acc * inv / 255.0
            r = int(round(bgc[0] + (fgc[0] - bgc[0]) * a))
            g = int(round(bgc[1] + (fgc[1] - bgc[1]) * a))
            b = int(round(bgc[2] + (fgc[2] - bgc[2]) * a))
            o = (j * size + i2) * 4
            px[o] = r
            px[o + 1] = g
            px[o + 2] = b
            px[o + 3] = 255
    return px


def render_alpha(size, path_polys, logo_frac, fg):
    """Transparent canvas holding only the glyph (adaptive-icon foreground)."""
    n = size * SS
    scale = (n * logo_frac) / 50.0
    off = (n - 50.0 * scale) / 2.0
    cov = fill(path_polys, n, n, (-off / scale, -off / scale, scale))
    px = bytearray(size * size * 4)
    inv = 1.0 / (SS * SS)
    for j in range(size):
        for i2 in range(size):
            acc = 0
            for dy in range(SS):
                row = (j * SS + dy) * n + i2 * SS
                for dx in range(SS):
                    acc += cov[row + dx]
            a = int(round(acc * inv))
            o = (j * size + i2) * 4
            px[o] = fg[0]
            px[o + 1] = fg[1]
            px[o + 2] = fg[2]
            px[o + 3] = a
    return px


def write_png(path, px, size, has_alpha):
    raw = bytearray()
    stride = size * 4
    for j in range(size):
        raw.append(0)
        raw += px[j * stride:(j + 1) * stride]
    ctype = 6 if has_alpha else 2
    if not has_alpha:
        # drop the alpha channel that render() filled with 255
        rgb = bytearray()
        for j in range(size):
            rgb.append(0)
            for i in range(size):
                o = (j * size + i) * 4
                rgb += px[o:o + 3]
        raw = rgb
    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, ctype, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(png)


def main():
    svg = sys.argv[1]
    out = sys.argv[2]
    src = open(svg).read()
    tag = re.search(r'<path\b[^>]*>', src).group(0)
    d = re.search(r'\sd="([^"]+)"', tag).group(1)
    polys = parse_path(d)
    print(f"subpaths: {len(polys)}  points: {sum(len(p) for p in polys)}")

    PLATE = (0x15, 0x15, 0x17)   # the web client's dark boot background
    MARK = (0xFF, 0xFF, 0xFF)    # favicon-dark.svg fill

    legacy = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
    for name, size in legacy.items():
        px = render(size, polys, 0.62, PLATE, MARK)
        write_png(f"{out}/mipmap-{name}/ic_launcher.png", px, size, False)
        print(f"  mipmap-{name}/ic_launcher.png  {size}x{size}")

    # adaptive foreground: 108dp canvas, glyph inside the 66dp safe zone
    fg = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
    for name, size in fg.items():
        px = render_alpha(size, polys, 0.62, MARK)
        write_png(f"{out}/drawable-{name}/ic_launcher_foreground.png", px, size, True)
        print(f"  drawable-{name}/ic_launcher_foreground.png  {size}x{size}")


if __name__ == "__main__":
    main()
