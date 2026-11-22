"""Draws the abstract cover art of the demo library with Pillow (no external images).

Each cover is a gradient from the palette's first two colours, a motif drawn on top and the title set in a
system font. The third palette colour is the dark accent (text shadow, motif outlines). The same input always
gives the same picture (the random generator is seeded with the title).
"""
import math
import os
import random
import zlib

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 1000

FONT_CANDIDATES = {
    "sans": ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
             "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
             "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"],
    "sans-light": ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                   "/usr/share/fonts/truetype/freefont/FreeSans.ttf"],
    "serif": ["/usr/share/fonts/opentype/urw-base35/C059-Bold.otf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
              "/usr/share/fonts/truetype/freefont/FreeSerifBold.ttf"],
    "serif-italic": ["/usr/share/fonts/opentype/urw-base35/C059-Italic.otf",
                     "/usr/share/fonts/truetype/freefont/FreeSerifItalic.ttf",
                     "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"],
}


def font(kind, size):
    for path in FONT_CANDIDATES[kind]:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def rgb(hex_colour):
    h = hex_colour.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(c1, c2, angle):
    """Linear gradient across the square at `angle` degrees."""
    img = Image.new("RGB", (SIZE, SIZE))
    px = img.load()
    dx, dy = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    span = abs(dx) * SIZE + abs(dy) * SIZE
    offset = min(0, dx * SIZE) + min(0, dy * SIZE)
    lut = [mix(c1, c2, i / 255) for i in range(256)]
    for y in range(SIZE):
        for x in range(SIZE):
            t = (x * dx + y * dy - offset) / span
            px[x, y] = lut[max(0, min(255, int(t * 255)))]
    return img


# ------------------------------------------------------------------------------------------------ motifs

def _circle(d, cx, cy, r, **kw):
    d.ellipse((cx - r, cy - r, cx + r, cy + r), **kw)


def motif_rings(d, rnd, c1, c2, dark):
    for i in range(9, 0, -1):
        _circle(d, 500, 430, i * 46, outline=(*dark, 210) if i % 2 else (255, 255, 255, 170), width=14)
    _circle(d, 500, 430, 40, fill=(*dark, 255))


def motif_sun(d, rnd, c1, c2, dark):
    _circle(d, 500, 420, 260, fill=(*mix(c1, (255, 230, 120), 0.4), 255))
    for i in range(7):
        y = 470 + i * 34
        d.rectangle((200, y, 800, y + 6 + i * 2), fill=(*dark, 255))
    for i in range(-8, 9):
        d.line((500 + i * 18, 690, 500 + i * 120, 1000), fill=(*c1, 150), width=3)
    for i in range(8):
        y = 690 + int((i / 8) ** 2 * 310)
        d.line((0, y, 1000, y), fill=(*c1, 150), width=3)


def motif_grid(d, rnd, c1, c2, dark):
    for i in range(0, 1001, 80):
        d.line((i, 0, i, 1000), fill=(255, 255, 255, 60), width=2)
        d.line((0, i, 1000, i), fill=(255, 255, 255, 60), width=2)
    for _ in range(14):
        x, y = rnd.randrange(0, 12) * 80, rnd.randrange(0, 9) * 80
        d.rectangle((x, y, x + 80, y + 80), fill=(*rnd.choice([c1, c2, (255, 255, 255)]), 150))


def motif_lanterns(d, rnd, c1, c2, dark):
    for _ in range(11):
        x, y, s = rnd.randint(80, 920), rnd.randint(80, 640), rnd.randint(40, 95)
        d.line((x, 0, x, y - s), fill=(*dark, 120), width=2)
        d.rounded_rectangle((x - s * 0.7, y - s, x + s * 0.7, y + s), radius=s * 0.6,
                            fill=(255, rnd.randint(190, 230), rnd.randint(120, 170), 230))
        for k in range(1, 4):
            yy = y - s + k * s / 2
            d.line((x - s * 0.65, yy, x + s * 0.65, yy), fill=(*mix(c1, dark, 0.3), 120), width=2)


def motif_stripes(d, rnd, c1, c2, dark):
    for i in range(-12, 14):
        x = i * 90
        colour = (*dark, 200) if i % 3 == 0 else (255, 255, 255, 50) if i % 3 == 1 else (*c2, 120)
        d.polygon([(x, 0), (x + 45, 0), (x + 45 + 600, 1000), (x + 600, 1000)], fill=colour)


def motif_gears(d, rnd, c1, c2, dark):
    for cx, cy, r, teeth in [(380, 400, 230, 14), (690, 620, 150, 10), (720, 230, 95, 8)]:
        pts = []
        for k in range(teeth * 2):
            a = math.pi * k / teeth
            rr = r if k % 2 == 0 else r * 0.82
            for da in (-0.12, 0.12):
                pts.append((cx + rr * math.cos(a + da), cy + rr * math.sin(a + da)))
        d.polygon(pts, fill=(*dark, 220))
        _circle(d, cx, cy, r * 0.35, fill=(*c1, 255))


def motif_waves(d, rnd, c1, c2, dark):
    for row in range(14):
        base = 240 + row * 52
        pts = [(x, base + 26 * math.sin(x / 90 + row * 0.6)) for x in range(0, 1001, 10)]
        d.line(pts, fill=(255, 255, 255, 200 - row * 10) if row % 2 else (*dark, 170), width=7)
    _circle(d, 760, 180, 80, fill=(255, 255, 255, 200))


def motif_planets(d, rnd, c1, c2, dark):
    for _ in range(90):
        x, y = rnd.randint(0, 1000), rnd.randint(0, 1000)
        _circle(d, x, y, rnd.choice([1, 2, 2, 3]), fill=(255, 255, 255, rnd.randint(120, 255)))
    _circle(d, 430, 430, 200, fill=(*c1, 255))
    d.ellipse((150, 380, 710, 480), outline=(255, 255, 255, 220), width=10)
    _circle(d, 760, 250, 70, fill=(*mix(c2, (255, 255, 255), 0.5), 255))
    _circle(d, 230, 760, 40, fill=(*mix(c1, c2, 0.5), 255))


def motif_mountains(d, rnd, c1, c2, dark):
    for layer in range(4):
        base = 520 + layer * 110
        pts = [(0, 1000), (0, base)]
        x = 0
        while x < 1000:
            x += rnd.randint(110, 230)
            pts.append((x, base - rnd.randint(60, 260)))
            x += rnd.randint(80, 160)
            pts.append((x, base))
        pts.append((1000, 1000))
        d.polygon(pts, fill=(*mix((255, 255, 255), dark, 0.2 + layer * 0.25), 255))


def motif_circles(d, rnd, c1, c2, dark):
    for _ in range(16):
        x, y, r = rnd.randint(0, 1000), rnd.randint(0, 760), rnd.randint(60, 230)
        _circle(d, x, y, r, fill=(*rnd.choice([c1, c2, dark, (255, 255, 255)]), rnd.randint(70, 170)))


def motif_lines(d, rnd, c1, c2, dark):
    for i in range(46):
        x = 60 + i * 19
        h = 180 + 260 * abs(math.sin(i / 6))
        d.line((x, 520 - h / 2, x, 520 + h / 2), fill=(*dark, 230), width=5)


def motif_question(d, rnd, c1, c2, dark):
    for i in range(6):
        _circle(d, 500, 430, 120 + i * 70, outline=(*dark, 90 - i * 12), width=10)
    f = font("serif", 520)
    d.text((500, 430), "?", font=f, anchor="mm", fill=(*dark, 255))


def motif_bowl(d, rnd, c1, c2, dark):
    d.pieslice((220, 220, 780, 780), 0, 180, fill=(*dark, 230))
    d.rectangle((200, 490, 800, 520), fill=(*dark, 255))
    for x in (380, 500, 620):
        pts = [(x + 26 * math.sin(y / 35), y) for y in range(160, 460, 6)]
        d.line(pts, fill=(255, 255, 255, 190), width=10)


def motif_orbit(d, rnd, c1, c2, dark):
    for _ in range(70):
        _circle(d, rnd.randint(0, 1000), rnd.randint(0, 1000), rnd.choice([1, 2, 3]), fill=(255, 255, 255, 200))
    for i, r in enumerate((130, 230, 330, 430)):
        _circle(d, 500, 430, r, outline=(255, 255, 255, 120), width=4)
        a = 1.1 + i * 1.7
        _circle(d, 500 + r * math.cos(a), 430 + r * math.sin(a), 22 + i * 6, fill=(*mix(c1, c2, i / 3), 255))
    _circle(d, 500, 430, 70, fill=(255, 220, 120, 255))


def motif_bubbles(d, rnd, c1, c2, dark):
    d.rounded_rectangle((110, 160, 620, 470), radius=80, fill=(255, 255, 255, 230))
    d.polygon([(200, 460), (180, 580), (300, 460)], fill=(255, 255, 255, 230))
    d.rounded_rectangle((400, 400, 890, 680), radius=80, fill=(*c2, 240))
    d.polygon([(780, 670), (820, 780), (700, 670)], fill=(*c2, 240))
    for k in range(3):
        _circle(d, 270 + k * 100, 315, 26, fill=(*dark, 255))


def motif_peaks(d, rnd, c1, c2, dark):
    motif_mountains(d, rnd, c1, c2, dark)
    d.line([(100, 900), (300, 780), (420, 820), (600, 700), (900, 640)], fill=(255, 255, 255, 220), width=8)


def motif_phone(d, rnd, c1, c2, dark):
    d.rounded_rectangle((330, 170, 670, 760), radius=55, fill=(*dark, 255))
    d.rounded_rectangle((355, 215, 645, 715), radius=30, fill=(*mix(c1, (255, 255, 255), 0.4), 255))
    d.rectangle((395, 260, 605, 470), fill=(*c2, 255))
    for k in range(4):
        d.rounded_rectangle((395, 500 + k * 50, 605 - k * 30, 525 + k * 50), radius=10, fill=(*dark, 160))
    for k in range(3):
        r = 120 + k * 70
        d.arc((670 - r, 460 - r, 670 + r, 460 + r), -40, 40, fill=(*dark, 200), width=14)
        d.arc((330 - r, 460 - r, 330 + r, 460 + r), 140, 220, fill=(*dark, 200), width=14)


def motif_lighthouse(d, rnd, c1, c2, dark):
    d.polygon([(500, 300), (1100, 120), (1100, 480)], fill=(255, 250, 220, 120))
    d.polygon([(500, 300), (-100, 160), (-100, 440)], fill=(255, 250, 220, 80))
    d.polygon([(440, 340), (560, 340), (600, 760), (400, 760)], fill=(255, 255, 255, 255))
    for k in range(3):
        y = 420 + k * 110
        d.polygon([(440 - k * 15 - 8, y), (560 + k * 15 + 8, y), (568 + k * 15 + 8, y + 45),
                   (432 - k * 15 - 8, y + 45)], fill=(*dark, 255))
    d.rectangle((455, 270, 545, 340), fill=(255, 240, 160, 255))
    d.polygon([(445, 270), (555, 270), (500, 220)], fill=(*dark, 255))
    pts = [(0, 760)] + [(x, 760 + 18 * math.sin(x / 60)) for x in range(0, 1001, 20)] + [(1000, 1000), (0, 1000)]
    d.polygon(pts, fill=(*dark, 255))


def motif_moon(d, rnd, c1, c2, dark):
    _circle(d, 500, 400, 250, fill=(*mix(c1, (255, 240, 200), 0.5), 255))
    _circle(d, 600, 340, 230, fill=(*mix(c2, dark, 0.45), 255))
    for _ in range(60):
        x, y = rnd.randint(0, 1000), rnd.randint(500, 1000)
        _circle(d, x, y, rnd.choice([2, 3, 4, 6]), fill=(255, rnd.randint(140, 220), 60, rnd.randint(140, 255)))


def motif_anvil(d, rnd, c1, c2, dark):
    d.polygon([(250, 470), (760, 470), (760, 540), (650, 560), (620, 650), (690, 720), (320, 720), (390, 650),
               (360, 560), (250, 540), (170, 490)], fill=(*dark, 255))
    for _ in range(45):
        a = rnd.uniform(math.pi * 1.1, math.pi * 1.9)
        r = rnd.uniform(60, 380)
        _circle(d, 500 + r * math.cos(a), 460 + r * math.sin(a), rnd.choice([3, 4, 6]),
                fill=(255, rnd.randint(200, 255), rnd.randint(60, 160), 255))


def motif_willow(d, rnd, c1, c2, dark):
    d.rectangle((0, 740, 1000, 1000), fill=(*mix(c1, dark, 0.6), 255))
    d.polygon([(465, 760), (535, 760), (520, 420), (480, 420)], fill=(*dark, 255))
    leaf = mix(c2, dark, 0.35)
    for _ in range(14):
        _circle(d, rnd.randint(300, 700), rnd.randint(300, 420), rnd.randint(70, 120), fill=(*leaf, 200))
    for _ in range(70):
        x0, y0 = rnd.randint(200, 800), rnd.randint(330, 430)
        sway = rnd.uniform(-1, 1)
        pts = [(x0 + sway * (t / 12) ** 1.4, y0 + t) for t in range(0, rnd.randint(200, 380), 8)]
        d.line(pts, fill=(*mix(leaf, (255, 255, 255), rnd.uniform(0, 0.3)), 230), width=5)


MOTIFS = {name[len("motif_"):]: fn for name, fn in globals().items() if name.startswith("motif_")}


# ------------------------------------------------------------------------------------------------- text

def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split():
        test = f"{line} {word}".strip()
        if draw.textlength(test, font=fnt) <= width or not line:
            line = test
        else:
            lines.append(line)
            line = word
    lines.append(line)
    return lines


def fit(draw, text, kind, width, max_size, max_lines):
    size = max_size
    while size > 30:
        fnt = font(kind, size)
        lines = wrap(draw, text, fnt, width)
        if len(lines) <= max_lines and all(draw.textlength(ln, font=fnt) <= width for ln in lines):
            return fnt, lines
        size -= 4
    fnt = font(kind, size)
    return fnt, wrap(draw, text, fnt, width)


def text_block(img, lines, fnt, top, fill, shadow, align="left", spacing=1.12):
    d = ImageDraw.Draw(img)
    size = fnt.size
    for i, ln in enumerate(lines):
        y = top + i * size * spacing
        if align == "center":
            x = (SIZE - d.textlength(ln, font=fnt)) / 2
        else:
            x = 70
        d.text((x + 4, y + 4), ln, font=fnt, fill=shadow)
        d.text((x, y), ln, font=fnt, fill=fill)
    return top + len(lines) * size * spacing


def cover(path, title, subtitle, palette, motif, kind):
    """kind: "album" (small text at the bottom), "podcast" (big title) or "book" (title, author, serif)."""
    rnd = random.Random(zlib.crc32(title.encode()))
    c1, c2, dark = (rgb(c) for c in (palette + ("#111111",))[:3])
    img = gradient(c1, c2, rnd.choice([35, 60, 120, 145])).convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    MOTIFS[motif](ImageDraw.Draw(layer), rnd, c1, c2, dark)
    img = Image.alpha_composite(img, layer)
    # A soft dark band behind the text keeps it legible on any motif.
    band = Image.new("RGBA", img.size, (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    draw = ImageDraw.Draw(img)
    white, shadow = (255, 255, 255, 255), (*dark, 200)
    if kind == "album":
        bd.rectangle((0, 760, SIZE, SIZE), fill=(*dark, 150))
        img = Image.alpha_composite(img, band.filter(ImageFilter.GaussianBlur(30)))
        fnt, lines = fit(draw, title.upper(), "sans", 860, 78, 2)
        y = text_block(img, lines, fnt, 1000 - 90 - len(lines) * fnt.size * 1.12 - 30, white, shadow)
        text_block(img, [subtitle], font("sans-light", 40), y + 6, (255, 255, 255, 220), shadow)
    elif kind == "podcast":
        bd.rectangle((0, 680, SIZE, SIZE), fill=(*dark, 170))
        img = Image.alpha_composite(img, band.filter(ImageFilter.GaussianBlur(30)))
        fnt, lines = fit(draw, title, "sans", 880, 110, 2)
        y = text_block(img, lines, fnt, 1000 - 100 - len(lines) * fnt.size * 1.1 - 40, white, shadow, "center", 1.1)
        text_block(img, [subtitle], font("sans-light", 42), y + 10, (255, 255, 255, 220), shadow, "center")
    else:
        bd.rectangle((0, 0, SIZE, 250), fill=(*dark, 150))
        bd.rectangle((0, 800, SIZE, SIZE), fill=(*dark, 170))
        img = Image.alpha_composite(img, band.filter(ImageFilter.GaussianBlur(30)))
        fnt, lines = fit(draw, title, "serif", 880, 96, 2)
        text_block(img, lines, fnt, 50 if len(lines) == 1 else 30, white, shadow, "center", 1.05)
        text_block(img, [subtitle.upper()], font("sans-light", 44), 885, (255, 255, 255, 230), shadow, "center")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert("RGB").save(path, "JPEG", quality=88)
    return path
