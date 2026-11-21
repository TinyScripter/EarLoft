# Store screenshots

Scripts that capture the Google Play screenshots of the phone and the watch app on the emulators and put them into the
[fastlane](https://docs.fastlane.tools/actions/supply/) layout (`fastlane/metadata/android/<locale>/images/`).
The store listing exists in English (`en-US`) and German (`de-DE`) only; see `LANGS` in `capture.py` to add a language.

| Screenshots | Folder | Size |
|---|---|---|
| Phone: `1_about`, `2_music_home`, `3_podcast_library`, `4_playback` | `phoneScreenshots` | 1080×1920 |
| Watch: `1_watch_home`, `2_phone_home`, `3_downloads`, `4_phone_playback` | `wearScreenshots` | 454×454 |

Google Play wants the longer side of a phone screenshot to be at most twice the shorter one, which a Pixel 10 Pro
screenshot (1280×2856) is not. `prepare.sh` therefore sets the display to 1080×1920 and turns on the demo-mode status
bar (9:41, full battery).

## Requirements

- Pixel 10 Pro emulator (`emulator-5554`) and Wear OS emulator (`emulator-5556`), the watch paired with the phone.
  Other serials: `PHONE_SERIAL=… WATCH_SERIAL=…`.
- Current **debug** builds of both apps on both emulators (`ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`,
  `:wear:installDebug`).
- The phone has a music track loaded and paused (the screenshots show whatever is current: `TRACK` in `capture.py`),
  a podcast with two new episodes, and the watch shows the phone's playback under **Phone**.
- Do not run another `uiautomator` against the emulators while capturing, the dumps get in each other's way.
- Python 3 with Pillow.

## Steps

```sh
tools/store-screenshots/prepare.sh                                  # phone display and status bar
python3 tools/store-screenshots/capture.py phone /tmp/shots-1 &     # both devices can run in parallel
python3 tools/store-screenshots/capture.py wear /tmp/shots-1
python3 tools/store-screenshots/sheet.py /tmp/shots-1 phone /tmp/sheet-phone-1.png 400 en-US de-DE   # review
python3 tools/store-screenshots/place.py /tmp/shots-1               # into fastlane/, checks the Play rules
tools/store-screenshots/reset.sh                                    # display, status bar and app languages back
```

Use a new output directory and sheet name every time; the IDE shows stale images for reused paths.
The scripts switch the language per app with `cmd locale set-app-locales`, find buttons by the texts in the
`strings.xml` files, and print `WARN …` when a screen is not where it should be. Check the sheet before placing.

`place.py` also copies `media/icon.png` (512×512) and `media/featureGraphic.jpeg` into every locale; the feature
graphic is centre-cropped to the 1024×500 Google Play requires.

## Upload

`fastlane supply` uploads this layout (`phoneScreenshots`, `wearScreenshots`, `icon`, `featureGraphic` and the texts).
It needs a Play Console service-account key, and the app must have had its first release uploaded by hand.
Supply replaces the screenshots that are already in the listing.
