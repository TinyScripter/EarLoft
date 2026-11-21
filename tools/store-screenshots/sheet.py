#!/usr/bin/env python3
"""Builds a contact sheet: rows = locales, columns = the four screenshots.

Usage: sheet.py <rundir> <phone|wear> <sheet.png> <width-per-shot> <locale> [<locale> ...]
"""
import os
import sys

from PIL import Image, ImageDraw

run, kind, target, w = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
locales = sys.argv[5:]
names = sorted({f for l in locales for f in os.listdir(f"{run}/{l}/{kind}")}) if locales else []
rows = []
for l in locales:
    tiles = []
    for n in names:
        p = f"{run}/{l}/{kind}/{n}"
        if os.path.exists(p):
            im = Image.open(p)
            tiles.append(im.resize((w, round(im.height * w / im.width))))
        else:
            tiles.append(Image.new("RGB", (w, w), "red"))
    rows.append((l, tiles))
th = max(t.height for _, tl in rows for t in tl)
pad, label = 8, 22
sheet = Image.new("RGB", (len(names) * (w + pad) + pad, len(rows) * (th + label + pad) + pad), "#888")
draw = ImageDraw.Draw(sheet)
for r, (l, tiles) in enumerate(rows):
    y = pad + r * (th + label + pad)
    draw.text((pad, y + 4), l, fill="white")
    for c, t in enumerate(tiles):
        sheet.paste(t, (pad + c * (w + pad), y + label))
sheet.save(target)
print(target, sheet.size)
