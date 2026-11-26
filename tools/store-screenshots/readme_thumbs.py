#!/usr/bin/env python3
"""Makes the small screenshots of README.md from the English store screenshots in fastlane/.

Usage: readme_thumbs.py
Writes media/readme/: phone and tablet slides as JPEG, watch screens cut round as PNG. They are twice the width the
README shows them at, so they stay sharp on high-density screens; the README links each one to its full-size original.
"""
import os

from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = f"{REPO}/fastlane/metadata/android/en-US/images"
DST = f"{REPO}/media/readme"

# (folder, file, width in the README); the README shows the phone slides at 150, tablets at 280, watches at 110 px.
SHOTS = [
    ("phoneScreenshots", "1_library.png", 150),
    ("phoneScreenshots", "2_player.png", 150),
    ("phoneScreenshots", "3_podcasts.png", 150),
    ("phoneScreenshots", "4_audiobooks.png", 150),
    ("tenInchScreenshots", "2_player.png", 280),
    ("tenInchScreenshots", "4_audiobooks.png", 280),
    ("wearScreenshots", "1_watch_home.png", 110),
    ("wearScreenshots", "4_phone_playback.png", 110),
]
PREFIX = {"phoneScreenshots": "phone", "tenInchScreenshots": "tablet10", "wearScreenshots": "watch"}


def main():
    os.makedirs(DST, exist_ok=True)
    for folder, name, width in SHOTS:
        im = Image.open(f"{SRC}/{folder}/{name}").convert("RGB")
        w, h = im.size
        size = (width * 2, round(h * width * 2 / w))
        im = im.resize(size, Image.LANCZOS)
        base = f"{DST}/{PREFIX[folder]}_{os.path.splitext(name)[0]}"
        if folder == "wearScreenshots":
            # Cut round like the watch face, with a soft edge drawn at 4x and scaled down.
            mask = Image.new("L", (size[0] * 4, size[1] * 4), 0)
            ImageDraw.Draw(mask).ellipse((0, 0, size[0] * 4 - 1, size[1] * 4 - 1), fill=255)
            im.putalpha(mask.resize(size, Image.LANCZOS))
            im.save(f"{base}.png", optimize=True)
            print(f"{base}.png")
        else:
            im.save(f"{base}.jpg", "JPEG", quality=85, optimize=True, progressive=True)
            print(f"{base}.jpg")


if __name__ == "__main__":
    main()
