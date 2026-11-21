#!/usr/bin/env python3
"""Captures the Play Store screenshots of the phone or the watch app in every listed language.

Usage: capture.py <phone|wear> <outdir> [fastlane-locale ...]
Run prepare.sh first (phone display size, status bar) and see README.md for the required emulator state.

Phone (1080x1920, demo-mode status bar):  1_about, 2_music_home, 3_podcast_library, 4_playback
Watch (454x454):                          1_watch_home, 2_phone_home, 3_downloads, 4_phone_playback
Files land in <outdir>/<fastlane-locale>/<kind>/<name>.png as RGB PNG (Play rejects alpha).
"""
import io
import os
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402
from PIL import Image  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
PHONE = os.environ.get("PHONE_SERIAL", "emulator-5554")
WATCH = os.environ.get("WATCH_SERIAL", "emulator-5556")
PKG = "de.kleinabersmart.earloft.debug"
TRACK = "Starting From Zero (feat. Michael McDonald)"

# (fastlane locale, locale tag for the app, values-qualifier; None = default English)
# The store listing exists in English and German only. Add a tuple, e.g. ("fr-FR", "fr-FR", "fr"), for more.
LANGS = [
    ("en-US", "en-US", None),
    ("de-DE", "de-DE", "de"),
]


def strings(module, qualifier):
    folder = "values" if qualifier is None else f"values-{qualifier}"
    root = ET.parse(f"{REPO}/{module}/src/main/res/{folder}/strings.xml").getroot()
    out = {}
    for el in root.findall("string"):
        out[el.get("name")] = "".join(el.itertext()).replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    return out


def lookup(module, qualifier, key):
    s = strings(module, qualifier)
    if key in s:
        return s[key]
    return strings(module, None)[key]  # untranslated names fall back to English


def log(msg):
    print(msg, flush=True)


def save(serial, path):
    raw = ui.adb(serial, "exec-out", "screencap", "-p", binary=True)
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    return img.size


def set_locale(serial, tag):
    ui.adb(serial, "shell", "cmd", "locale", "set-app-locales", PKG, "--locales", tag)


def has_text(nodes, text, contains=False):
    return bool(ui.find(nodes, text, contains))


# ----------------------------------------------------------------------------------------------- phone

def phone(out, only):
    for fl, tag, q in LANGS:
        if only and fl not in only:
            continue
        d = f"{out}/{fl}/phone"
        about_summary = lookup("app", q, "settings_about_summary")
        about_text = lookup("app", q, "about_text")[:40]
        set_locale(PHONE, tag)
        time.sleep(4)

        ui.tap(PHONE, 180, 1750)  # Home (also closes the player or any sub page)
        time.sleep(1.5)
        ui.tap(PHONE, 209, 380)   # Music
        time.sleep(2.5)
        log(f"{fl} music_home {save(PHONE, f'{d}/2_music_home.png')}")

        ui.tap(PHONE, 480, 1560)  # mini player -> full player (opened from Home so the Home tab stays selected)
        time.sleep(3)
        if not [n for n in ui.find(ui.dump(PHONE), TRACK) if n["bounds"][1] > 700]:
            log(f"WARN {fl}: full player not open")
        log(f"{fl} playback {save(PHONE, f'{d}/4_playback.png')}")
        ui.tap(PHONE, 180, 1750)  # Home closes the player
        time.sleep(1.5)

        ui.tap(PHONE, 540, 1750)  # Library
        time.sleep(1.5)
        ui.tap(PHONE, 540, 380)   # Podcasts
        time.sleep(1.5)
        ui.tap(PHONE, 135, 538)   # first tab: New
        time.sleep(3)
        log(f"{fl} podcast_library {save(PHONE, f'{d}/3_podcast_library.png')}")
        ui.tap(PHONE, 209, 380)   # back to Music so every later Home starts the same way
        time.sleep(1)

        ui.tap(PHONE, 900, 1750)  # Settings
        time.sleep(2)
        nodes = ui.dump(PHONE)
        if not has_text(nodes, about_text, contains=True):
            hit = ui.find(nodes, about_summary)
            if hit:
                ui.tap(PHONE, *ui.center(hit[0]))
            else:
                log(f"WARN {fl}: About entry not found, using fixed position")
                ui.tap(PHONE, 400, 1340)
            time.sleep(2.5)
            nodes = ui.dump(PHONE)
        if not has_text(nodes, about_text, contains=True):
            log(f"WARN {fl}: About text not on screen")
        log(f"{fl} about {save(PHONE, f'{d}/1_about.png')}")

    ui.tap(PHONE, 180, 1750)


# ------------------------------------------------------------------------------------------------ wear

def wear_swipe(dy):
    """Scrolls the list by about dy pixels (positive = content moves up)."""
    start = 330 if dy > 0 else 150
    ui.adb(WATCH, "shell", "input", "swipe", "227", str(start), "227", str(start - dy), "900")


def wear_find(text, tries=4):
    for _ in range(tries):
        hit = ui.find(ui.dump(WATCH), text)
        if hit:
            return hit
        time.sleep(1.5)
    return []


def wear(out, only):
    for fl, tag, q in LANGS:
        if only and fl not in only:
            continue
        d = f"{out}/{fl}/wear"
        s = {k: lookup("wear", q, k) for k in ("start_section_watch", "start_section_phone", "start_current_playback", "storage_title")}
        set_locale(WATCH, tag)
        ui.adb(WATCH, "shell", "am", "force-stop", PKG)
        time.sleep(1)
        ui.adb(WATCH, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
        time.sleep(8)

        if not wear_find(s["start_section_watch"], tries=5):
            log(f"WARN {fl}: watch home header not found")
        time.sleep(1)
        log(f"{fl} watch_home {save(WATCH, f'{d}/1_watch_home.png')}")

        header = None
        for _ in range(12):
            hit = ui.find(ui.dump(WATCH), s["start_section_phone"])
            if hit:
                cy = ui.center(hit[0])[1]
                header = hit[0]
                if 95 <= cy <= 125:
                    break
                wear_swipe(max(-150, min(150, cy - 110)))
            else:
                wear_swipe(150)
            time.sleep(1.2)
        if header is None:
            log(f"WARN {fl}: Phone header not found")
        time.sleep(4)  # let the scroll indicator fade
        log(f"{fl} phone_home {save(WATCH, f'{d}/2_phone_home.png')}")

        nodes = ui.dump(WATCH)
        hdr = ui.find(nodes, s["start_section_phone"])
        hy = ui.center(hdr[0])[1] if hdr else 0
        below = [n for n in ui.find(nodes, s["start_current_playback"]) if ui.center(n)[1] > hy]
        if below:
            ui.tap(WATCH, *ui.center(below[0]))
            time.sleep(4)
            if not has_text(ui.dump(WATCH), TRACK):
                log(f"WARN {fl}: phone player not open")
            log(f"{fl} phone_playback {save(WATCH, f'{d}/4_phone_playback.png')}")
            ui.adb(WATCH, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(2.5)
        else:
            log(f"WARN {fl}: phone 'now playing' entry not found")

        hit = ui.find(ui.dump(WATCH), s["storage_title"])
        if not hit or ui.center(hit[0])[1] < 100:
            wear_swipe(-130)
            time.sleep(2)
            hit = ui.find(ui.dump(WATCH), s["storage_title"])
        if hit:
            ui.tap(WATCH, *ui.center(hit[0]))
            time.sleep(4)
            if not has_text(ui.dump(WATCH), lookup("wear", q, "storage_title")):
                log(f"WARN {fl}: storage screen title missing")
            log(f"{fl} downloads {save(WATCH, f'{d}/3_downloads.png')}")
            ui.adb(WATCH, "shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(1.5)
        else:
            log(f"WARN {fl}: storage entry not found")


if __name__ == "__main__":
    kind, outdir, only = sys.argv[1], sys.argv[2], set(sys.argv[3:])
    (phone if kind == "phone" else wear)(outdir, only)
    log("DONE " + kind)
