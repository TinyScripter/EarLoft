#!/usr/bin/env python3
"""Replaces the headline and subline of the feature graphic with a translation.

Usage: feature_text.py <fonts-dir> <locale> [out.png]
  <fonts-dir>  folder with Inter-Bold.ttf and Inter-Regular.ttf (https://github.com/rsms/inter, OFL)
  <locale>     a key of TEXTS, e.g. de-DE; "check" re-sets the English text to compare it with the original
  out.png      default media/featureGraphic.<locale>.png (place.py prefers it over media/featureGraphic.png)

The text is erased by letting the background grow into the text strokes (the area behind it is a soft gradient), then
the new lines are set at the positions and sizes measured on media/featureGraphic.png. The positions only fit that
graphic: if it is redrawn, measure again.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

TEXTS = {
    "check": (["Your World of", "Music, Podcasts", "& Books"],
              ["All in one app: seamless,", "organized, and personalized."]),
    "de-DE": (["Deine Welt voller", "Musik, Podcasts", "& Bücher"],
              ["Alles in einer App: nahtlos,", "übersichtlich und persönlich."]),
}

# Measured on the 1024x500 original (Inter Bold 54 px and Inter Regular 27.15 px match the English lines).
HEADLINE_SIZE = 54
SUBLINE_SIZE = 27.15
LEFT = 49                      # left edge of the ink of the first line
HEADLINE_BASELINES = (197, 255, 313)
SUBLINE_BASELINES = (366, 400)
HEADLINE_COLOR = (58, 22, 0)
SUBLINE_COLOR = (47, 4, 0)
ERASE_BOXES = ((40, 146, 500, 322), (40, 340, 440, 412))  # left, top, right, bottom around the old text
MAX_WIDTH = 455                 # the orange wave starts right of x = 505


def erase(rgb):
    """Fills the dark brown text pixels in ERASE_BOXES from the surrounding background (harmonic fill)."""
    img = rgb.astype(float)
    red = img[:, :, 0]
    mask = np.zeros(red.shape, bool)
    for l, t, r, b in ERASE_BOXES:
        # Only little red: the brown text, not the orange wave or the light sound bars behind it.
        mask[t:b, l:r] = red[t:b, l:r] < 190
    for _ in range(3):  # grow by 3 px to take the anti-aliased edges as well
        grown = mask.copy()
        grown[1:] |= mask[:-1]
        grown[:-1] |= mask[1:]
        grown[:, 1:] |= mask[:, :-1]
        grown[:, :-1] |= mask[:, 1:]
        mask = grown
    ys, xs = np.nonzero(mask)
    t, b, l, r = ys.min() - 1, ys.max() + 2, xs.min() - 1, xs.max() + 2
    crop, m = img[t:b, l:r], mask[t:b, l:r]
    # Start from the mean of the row outside the mask, then relax towards the neighbours' average.
    for y in range(crop.shape[0]):
        if m[y].any():
            crop[y, m[y]] = crop[y, ~m[y]].mean(axis=0)
    for _ in range(4000):
        avg = (np.roll(crop, 1, 0) + np.roll(crop, -1, 0) + np.roll(crop, 1, 1) + np.roll(crop, -1, 1)) / 4
        crop[m] = avg[m]
    img[t:b, l:r] = crop
    return img.clip(0, 255).astype(np.uint8)


def main():
    fonts, locale = sys.argv[1], sys.argv[2]
    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out = sys.argv[3] if len(sys.argv) > 3 else f"{repo}/media/featureGraphic.{locale}.png"
    headline, subline = TEXTS[locale]
    src = Image.open(f"{repo}/media/featureGraphic.png").convert("RGB")
    im = Image.fromarray(erase(np.asarray(src)))
    draw = ImageDraw.Draw(im)
    bold = ImageFont.truetype(f"{fonts}/Inter-Bold.ttf", HEADLINE_SIZE)
    regular = ImageFont.truetype(f"{fonts}/Inter-Regular.ttf", SUBLINE_SIZE)
    # One pen position for all lines, so that the lines line up the way the original's do.
    x = LEFT - bold.getbbox(headline[0])[0]
    problems = []
    for font, lines, baselines, color in ((bold, headline, HEADLINE_BASELINES, HEADLINE_COLOR),
                                          (regular, subline, SUBLINE_BASELINES, SUBLINE_COLOR)):
        for line, baseline in zip(lines, baselines):
            draw.text((x, baseline), line, font=font, fill=color, anchor="ls")
            right = x + font.getbbox(line, anchor="ls")[2]
            if right - LEFT > MAX_WIDTH:
                problems.append(f"'{line}' is {right - LEFT} px wide, at most {MAX_WIDTH} fit")
    im.save(out)
    print(out)
    print("\n".join(problems) if problems else "all lines fit")


if __name__ == "__main__":
    main()
