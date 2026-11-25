#!/usr/bin/env python3
"""Store screenshots with a headline: what the app can do above the part of the screen that shows it.

Usage:
  promo.py shoot <rundir> <raw-name> [fastlane-locale ...]
      Saves what the phone shows right now as <rundir>/<locale>/raw/<raw-name>.png, once per language (the app
      language is switched with `cmd locale set-app-locales`; the open screen stays open). Navigate to the screen
      first; see README.md for the screens and the state they need.
  promo.py compose <rundir> [fastlane-locale ...]
      Builds <rundir>/<locale>/phone/N_name.png (1440x2560) from the raw shots and, for the watch slide, from the
      watch screenshots in <rundir>/<locale>/wear/ (capture.py wear). place.py copies them into fastlane/.
  With PROFILE=tablet7 (portrait raw shots, slides 1440x2560 in <locale>/sevenInch) or PROFILE=tablet10 (landscape raw
  shots, slides 2560x1600 in <locale>/tenInch) the same commands make the tablet screenshots; those have no watch slide.

The phone is drawn with a dark frame and runs off the bottom edge, so the upper part of the screen (where the
content is) is shown large and the navigation bar is cut off. Texts are in SLIDES; {braces} mark the orange words,
a no-break space (\\u00a0) keeps words on one line.
"""
import io
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402
from PIL import Image, ImageDraw, ImageFilter, ImageFont  # noqa: E402

PHONE = os.environ.get("PHONE_SERIAL", "emulator-5554")
PKG = "de.kleinabersmart.earloft.debug"
LANGS = {"en-US": "en-US", "de-DE": "de-DE"}

# Per device class: canvas, output folder, text sizes, device frame, soft background shapes (cx, cy, radius, alpha).
PROFILES = {
    "phone": dict(size=(1440, 2560), out="phone", head=112, sub=54, head_top=150, lines=(2, 2), text_w=(1260, 1180),
                  top=700, frame=960, bezel=24, radii=(110, 88), camera=True,
                  shapes=[(1320, 420, 520, 70), (60, 1500, 600, 55), (1250, 2300, 480, 60)], waves=2150),
    "tablet7": dict(size=(1440, 2560), out="sevenInch", head=112, sub=54, head_top=150, lines=(2, 2), text_w=(1260, 1180),
                    top=700, frame=1240, bezel=28, radii=(72, 48), camera=False,
                    shapes=[(1320, 420, 520, 70), (60, 1500, 600, 55), (1250, 2300, 480, 60)], waves=2150),
    "tablet10": dict(size=(2560, 1600), out="tenInch", head=96, sub=46, head_top=56, lines=(1, 1), text_w=(2360, 2200),
                     top=340, frame=2000, bezel=26, radii=(64, 40), camera=False,
                     shapes=[(2380, 200, 520, 70), (80, 900, 520, 55), (2300, 1500, 480, 60)], waves=1420),
}
P = PROFILES[os.environ.get("PROFILE", "phone")]
W, H = P["size"]
PHONE_TOP = P["top"]  # the same on every slide, below the headline and the subline
NAVY = (27, 22, 48)
GREY = (84, 78, 98)
ORANGE = (240, 133, 25)
BG_TOP, BG_BOTTOM = (255, 246, 237), (253, 226, 201)

# (output name, raw shot or "watch", {locale: (headline, subline)})
SLIDES = [
    ("1_library", "music_home", {
        "en-US": ("{Music, podcasts} and {audiobooks} in one app",
                  "From Navidrome, Jellyfin, Emby, Audiobookshelf, any podcast feed and your phone"),
        "de-DE": ("{Musik, Podcasts} und {Hörbücher} in einer App",
                  "Von Navidrome, Jellyfin, Emby, Audiobookshelf, jedem Podcast-Feed und deinem Handy"),
    }),
    ("2_player", "player_lyrics", {
        "en-US": ("{Lyrics}, equalizer, {cover\u00a0colors}",
                  "Synchronized lyrics, gapless playback and ReplayGain"),
        "de-DE": ("{Songtexte}, Equalizer, {Cover-Farben}",
                  "Synchronisierte Songtexte, lückenlose Wiedergabe und ReplayGain"),
    }),
    ("3_podcasts", "podcast_library", {
        "en-US": ("{Podcasts} from any feed",
                  "Chapters, transcripts, speed per podcast and automatic downloads"),
        "de-DE": ("{Podcasts} aus jedem Feed",
                  "Kapitel, Transkripte, Tempo pro Podcast und automatische Downloads"),
    }),
    ("4_audiobooks", "book_player", {
        "en-US": ("{Audiobooks}: speed, sleep timer, chapters",
                  "Progress and bookmarks sync with Audiobookshelf – also after listening offline"),
        "de-DE": ("{Hörbücher}: Tempo, Sleep-Timer, Kapitel",
                  "Fortschritt und Lesezeichen synchron mit Audiobookshelf – auch nach dem Hören offline"),
    }),
    ("5_watch", "watch", {
        "en-US": ("Also on your {Wear\u00a0OS\u00a0watch}",
                  "Music, podcasts and audiobooks offline on the watch – or control your phone"),
        "de-DE": ("Auch auf der {Wear-OS-Uhr}",
                  "Musik, Podcasts und Hörbücher offline auf der Uhr – oder das Handy steuern"),
    }),
]

FONTS = {
    "head": ["/usr/share/fonts/truetype/lato/Lato-Black.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
    "sub": ["/usr/share/fonts/truetype/lato/Lato-Medium.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
}


def font(kind, size):
    for path in FONTS[kind]:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


# ------------------------------------------------------------------------------------------------- shoot

def shoot(run, raw_name, only):
    for locale, tag in LANGS.items():
        if only and locale not in only:
            continue
        ui.adb(PHONE, "shell", "cmd", "locale", "set-app-locales", PKG, "--locales", tag)
        import time
        time.sleep(4.5)
        data = ui.adb(PHONE, "exec-out", "screencap", "-p", binary=True)
        path = f"{run}/{locale}/raw/{raw_name}.png"
        os.makedirs(os.path.dirname(path), exist_ok=True)
        Image.open(io.BytesIO(data)).convert("RGB").save(path)
        print(f"{locale} {raw_name} -> {path}", flush=True)


# ----------------------------------------------------------------------------------------------- compose

def background():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        d.line((0, y, W, y), fill=tuple(round(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)))
    # Soft round shapes and thin orange waves, as in the feature graphic.
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for cx, cy, r, a in P["shapes"]:
        ld.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(255, 255, 255, a))
    for k in range(5):
        pts = [(x, P["waves"] + k * 34 + 60 * math.sin(x / 210 + k * 0.5)) for x in range(-10, W + 20, 12)]
        ld.line(pts, fill=(*ORANGE, 70), width=3)
    return Image.alpha_composite(img.convert("RGBA"), layer)


def words(text):
    """Splits "{orange words}, rest" into words; a word is a list of (text, is_orange) runs, so that punctuation
    right after an orange word stays attached without a space."""
    out, word, orange = [], [], False
    for ch in text:
        if ch in "{}":
            orange = ch == "{"
        elif ch == " ":
            if word:
                out.append(word)
            word = []
        elif word and word[-1][1] == orange:
            word[-1] = (word[-1][0] + ch, orange)
        else:
            word.append((ch, orange))
    if word:
        out.append(word)
    return out


def plain(word):
    return "".join(t for t, _ in word)


def wrap(draw, items, fnt, width):
    lines, line = [], []
    for item in items:
        test = " ".join(plain(w) for w in line + [item])
        if line and draw.textlength(test, font=fnt) > width:
            lines.append(line)
            line = [item]
        else:
            line.append(item)
    if line:
        lines.append(line)
    return lines


def text_block(img, text, kind, size, top, colour, max_lines, width=1260, spacing=1.16):
    d = ImageDraw.Draw(img)
    items = words(text)
    while True:
        fnt = font(kind, size)
        lines = wrap(d, items, fnt, width)
        if len(lines) <= max_lines or size < 30:
            break
        size -= 4
    y = top
    for line in lines:
        total = d.textlength(" ".join(plain(w) for w in line), font=fnt)
        x = (W - total) / 2
        for word in line:
            for run, orange in word:
                d.text((x, y), run, font=fnt, fill=ORANGE if orange else colour)
                x += d.textlength(run, font=fnt)
            x += d.textlength(" ", font=fnt)
        y += size * spacing
    return y


def shadow(img, box, radius, blur=40, offset=18, alpha=90):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    ImageDraw.Draw(layer).rounded_rectangle((x0, y0 + offset, x1, y1 + offset), radius, fill=(40, 20, 0, alpha))
    return Image.alpha_composite(img, layer.filter(ImageFilter.GaussianBlur(blur)))


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    return mask


def phone(img, raw, top, width=None):
    """Device frame (phone or tablet) with the screenshot, running off the bottom edge."""
    width = width or P["frame"]
    bezel = P["bezel"]
    outer, inner = P["radii"]
    screen_w = width - 2 * bezel
    scale = screen_w / raw.width
    screen = raw.resize((screen_w, round(raw.height * scale)), Image.LANCZOS)
    x0 = (W - width) // 2
    frame = (x0, top, x0 + width, top + screen.height + 2 * bezel)
    img = shadow(img, frame, outer)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(frame, outer, fill=(24, 22, 30, 255))
    d.rounded_rectangle((frame[0] + 3, frame[1] + 3, frame[2] - 3, frame[3] - 3), outer - 3, outline=(70, 66, 80, 255), width=2)
    img.paste(screen, (x0 + bezel, top + bezel), rounded(screen, inner))
    if P["camera"]:
        cam = (W // 2, top + bezel + round(46 * scale / 0.72))
        d.ellipse((cam[0] - 17, cam[1] - 17, cam[0] + 17, cam[1] + 17), fill=(12, 12, 16, 255))
    return img


def watch(img, shot, centre, diameter):
    cx, cy = centre
    ring = 46
    r = diameter // 2 + ring
    d = ImageDraw.Draw(img)
    strap_w = round(diameter * 0.62)
    for sign in (-1, 1):  # straps above and below
        y0, y1 = (cy - r - 230, cy) if sign < 0 else (cy, cy + r + 230)
        d.rounded_rectangle((cx - strap_w // 2, y0, cx + strap_w // 2, y1), 60, fill=(48, 44, 58, 255))
    img = shadow(img, (cx - r, cy - r, cx + r, cy + r), r, blur=36)
    d = ImageDraw.Draw(img)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(28, 26, 34, 255))
    d.ellipse((cx - r + 6, cy - r + 6, cx + r - 6, cy + r - 6), outline=(92, 88, 104, 255), width=4)
    crown = (cx + r - 6, cy - 50, cx + r + 30, cy + 50)
    d.rounded_rectangle(crown, 14, fill=(60, 56, 70, 255))
    face = shot.resize((diameter, diameter), Image.LANCZOS)
    mask = Image.new("L", face.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, diameter - 1, diameter - 1), fill=255)
    img.paste(face, (cx - diameter // 2, cy - diameter // 2), mask)
    return img


def compose(run, only):
    for locale in sorted(os.listdir(run)):
        if (only and locale not in only) or not os.path.isdir(f"{run}/{locale}/raw"):
            continue
        out_dir = f"{run}/{locale}/{P['out']}"
        os.makedirs(out_dir, exist_ok=True)
        for old in os.listdir(out_dir):
            os.remove(f"{out_dir}/{old}")
        for name, source, texts in SLIDES:
            if source == "watch" and P["out"] != "phone":
                continue
            needed = [f"{run}/{locale}/wear/1_watch_home.png", f"{run}/{locale}/wear/4_phone_playback.png"] \
                if source == "watch" else [f"{run}/{locale}/raw/{source}.png"]
            if not all(os.path.exists(p) for p in needed):
                print(f"WARN {locale} {name}: missing {', '.join(p for p in needed if not os.path.exists(p))}")
                continue
            headline, subline = texts[locale]
            img = background()
            y = text_block(img, headline, "head", P["head"], P["head_top"], NAVY, P["lines"][0], width=P["text_w"][0])
            y = text_block(img, subline, "sub", P["sub"], y + 34 * P["sub"] // 54, GREY, P["lines"][1],
                           width=P["text_w"][1], spacing=1.3)
            if source == "watch":
                wear = f"{run}/{locale}/wear"
                home = Image.open(f"{wear}/1_watch_home.png").convert("RGB")
                play = Image.open(f"{wear}/4_phone_playback.png").convert("RGB")
                img = watch(img, home, (470, PHONE_TOP + 560), 600)
                img = watch(img, play, (975, PHONE_TOP + 1250), 600)
            else:
                raw = Image.open(f"{run}/{locale}/raw/{source}.png").convert("RGB")
                img = phone(img, raw, max(PHONE_TOP, round(y) + 40))
            img.convert("RGB").save(f"{out_dir}/{name}.png", "PNG", optimize=True)
            print(f"{locale} {name}", flush=True)


if __name__ == "__main__":
    command, run_dir = sys.argv[1], sys.argv[2]
    if command == "shoot":
        shoot(run_dir, sys.argv[3], set(sys.argv[4:]))
    elif command == "compose":
        compose(run_dir, set(sys.argv[3:]))
    else:
        sys.exit(__doc__)
