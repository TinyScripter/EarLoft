#!/usr/bin/env python3
"""Copies a capture run into the fastlane layout and adds the store graphics.

Usage: place.py <rundir> [fastlane-locale ...]
  <rundir>/<locale>/phone/N_name.png -> fastlane/metadata/android/<locale>/images/phoneScreenshots/
  <rundir>/<locale>/wear/N_name.png  -> fastlane/metadata/android/<locale>/images/wearScreenshots/
  media/icon.png                     -> .../images/icon.png            (512x512, 32-bit PNG)
  media/featureGraphic.jpeg          -> .../images/featureGraphic.jpeg (1024x500, centre-cropped if wider)
Checks the Play rules on the way: phone longer side at most twice the shorter one and 320-3840 px, watch 1:1 and at
least 384 px, RGB (no alpha) for screenshots, under 8 MB.
"""
import os
import shutil
import sys

from PIL import Image

run, only = sys.argv[1], set(sys.argv[2:])
repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
base = f"{repo}/fastlane/metadata/android"
folders = {"phone": "phoneScreenshots", "wear": "wearScreenshots"}
problems, copied = [], 0

for locale in sorted(l for l in os.listdir(run) if not only or l in only):
    for kind, folder in folders.items():
        src = f"{run}/{locale}/{kind}"
        if not os.path.isdir(src):
            problems.append(f"{locale}/{kind}: missing")
            continue
        files = sorted(os.listdir(src))
        if len(files) != 4:
            problems.append(f"{locale}/{kind}: {len(files)} files instead of 4")
        targets = [locale]
        for t in targets:
            dst = f"{base}/{t}/images/{folder}"
            if os.path.isdir(dst):
                shutil.rmtree(dst)
            os.makedirs(dst)
            for f in files:
                im = Image.open(f"{src}/{f}")
                w, h = im.size
                if im.mode != "RGB":
                    problems.append(f"{t}/{kind}/{f}: mode {im.mode}")
                if kind == "phone" and not (320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)):
                    problems.append(f"{t}/{kind}/{f}: bad size {w}x{h}")
                if kind == "wear" and (w != h or w < 384):
                    problems.append(f"{t}/{kind}/{f}: bad size {w}x{h}")
                im.save(f"{dst}/{f}", "PNG", optimize=True)
                if os.path.getsize(f"{dst}/{f}") > 8 * 1024 * 1024:
                    problems.append(f"{t}/{kind}/{f}: over 8 MB")
                copied += 1

def graphics(locale):
    dst = f"{base}/{locale}/images"
    os.makedirs(dst, exist_ok=True)
    icon = f"{repo}/media/icon.png"
    if os.path.exists(icon):
        im = Image.open(icon)
        if im.size != (512, 512):
            problems.append(f"icon: {im.size}, Play wants 512x512")
        im.convert("RGBA").save(f"{dst}/icon.png", "PNG", optimize=True)
    feature = f"{repo}/media/featureGraphic.jpeg"
    if os.path.exists(feature):
        im = Image.open(feature).convert("RGB")
        w, h = im.size
        if h == 500 and w >= 1024:
            left = (w - 1024) // 2
            im = im.crop((left, 0, left + 1024, 500))
        if im.size != (1024, 500):
            problems.append(f"featureGraphic: {im.size}, Play wants exactly 1024x500")
        im.save(f"{dst}/featureGraphic.jpeg", "JPEG", quality=95, subsampling=0)


for locale in sorted(l for l in os.listdir(run) if not only or l in only):
    graphics(locale)

print(f"{copied} files placed")
print("\n".join(problems) if problems else "all checks passed")
