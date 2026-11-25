# Store screenshots

Scripts that capture the Google Play screenshots of the phone and the watch app on the emulators and put them into the
[fastlane](https://docs.fastlane.tools/actions/supply/) layout (`fastlane/metadata/android/<locale>/images/`).
The store listing exists in English (`en-US`) and German (`de-DE`) only; see `LANGS` in `capture.py` to add a language.

| Screenshots | Folder | Size |
|---|---|---|
| Phone: `1_library`, `2_player`, `3_podcasts`, `4_audiobooks`, `5_watch` – headline and subline above the app (`promo.py`) | `phoneScreenshots` | 1440×2560 |
| Watch: `1_watch_home`, `2_phone_home`, `3_downloads`, `4_phone_playback` (`capture.py wear`) | `wearScreenshots` | 454×454 |
| 7" tablet: `1_library`, `2_player`, `3_podcasts`, `4_audiobooks` (`PROFILE=tablet7`) | `sevenInchScreenshots` | 1440×2560 |
| 10" tablet: the same four (`PROFILE=tablet10`) | `tenInchScreenshots` | 2560×1600 |

The phone slides are composed by `promo.py`: a headline that says what the app can do (orange words marked with
`{braces}` in `SLIDES`), a subline, and the screen in a phone frame that runs off the bottom edge, so that the part
with the content is large and the navigation bar is cut off. The last slide shows two watch screenshots. Google Play
allows 2 to 8 phone screenshots with the longer side at most twice the shorter one (9:16 here).

## Tablet screenshots

The four tablet slides use the same raw shots, headlines and sublines as the phone slides (no watch slide), shot on the
`Medium_Tablet` emulator (2560×1600, 320 dpi) with the demo library installed there (`PHONE_SERIAL=<serial>` if it is
not `emulator-5554`; only that one emulator needs to run). The list-detail layout from 840 dp shows the chapter list
or the lyrics next to the player.

- **10"**: the display as it is, landscape. `promo.py shoot /tmp/tab10-1 <raw-name>` for the four screens in the table
  below, then `PROFILE=tablet10 promo.py compose /tmp/tab10-1` (slides 2560×1600, headline in one line above the
  tablet frame).
- **7"**: `adb shell wm size 1200x1920` and `wm density 320` (600×960 dp, 7.1"), portrait, then the same with
  `/tmp/tab7-1` and `PROFILE=tablet7` (slides 1440×2560, like the phone). `reset.sh` undoes size and density.
- Switching the app language recreates the screen, and the side pane of the book player falls back to the description:
  open *Chapters* again after each switch (the lyrics pane stays). `place.py /tmp/tab10-1` and `place.py /tmp/tab7-1`
  put the slides into `fastlane/`; the review sheet works with `sheet.py <run> tenInch|sevenInch …`.
- Set-up on a wiped tablet emulator: `tools/demo-library/install.sh --wipe`, then the setup wizard (music on this
  device, OPML import, books on this device, allow the `Audiobooks` folder, *Sync books now*), `prepare.sh native`.

## Requirements

- Pixel 10 Pro emulator (`emulator-5554`) and Wear OS emulator (`emulator-5556`), the watch paired with the phone.
  Other serials: `PHONE_SERIAL=… WATCH_SERIAL=…`.
- Current **debug** builds of both apps on both emulators (`ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`,
  `:wear:installDebug`).
- The made-up library from [`tools/demo-library`](../demo-library/README.md) and nothing else: the screenshots are
  published, so no real artists, covers, shows or books.
- A few albums, a book and an episode loaded onto the watch and downloaded there (on the emulator
  `adb -s <watch> shell dumpsys battery set ac 1` plus `set status 2` lets it download, `dumpsys battery reset`
  afterwards), and the watch shows the phone's playback (`TRACK` in `capture.py`) under **Phone**.
- Do not run another `uiautomator` against the emulators while capturing, the dumps get in each other's way.
- Python 3 with Pillow; the Lato font (`fonts-lato`), otherwise DejaVu.

## Steps

`promo.py shoot <run> <raw-name>` saves what the phone shows right now in every language (the open screen stays open
when the app language changes). Navigate there by hand (or with `ui.py`) in this order, because the music and the
book player cannot both be the current playback:

| Raw shot | Screen and state |
|---|---|
| `book_player` | Home → Books → play "How to build the EarLoft app", open the player: chapter "The watch learns to wait" at about half, speed 1.25×, sleep timer 30 min, paused |
| `player_lyrics` | Library → Music → Songs → "Gapless (Finally)" (the queue then mixes albums), open the player, turn the sleep timer off, *Show lyrics*, seek to about 1:16, pause |
| `music_home` | Home → Music, scrolled to the top |
| `podcast_library` | Library → Podcasts → New → All, one episode started (progress), the others untouched |

```sh
tools/store-screenshots/prepare.sh native                           # demo status bar, display unchanged
python3 tools/store-screenshots/promo.py shoot /tmp/promo-1 book_player      # … and the other three
python3 tools/store-screenshots/capture.py wear /tmp/promo-1        # watch screenshots (also used by 5_watch)
python3 tools/store-screenshots/promo.py compose /tmp/promo-1
python3 tools/store-screenshots/sheet.py /tmp/promo-1 phone /tmp/sheet-promo-1.png 290 en-US de-DE   # review
python3 tools/store-screenshots/place.py /tmp/promo-1               # into fastlane/, checks the Play rules
tools/store-screenshots/reset.sh                                    # status bar and app languages back
```

Use a new output directory and sheet name every time; the IDE shows stale images for reused paths.
`capture.py phone` still captures plain 1080×1920 screenshots (after `prepare.sh` without `native`), without text.
The scripts switch the language per app with `cmd locale set-app-locales`, find buttons by the texts in the
`strings.xml` files, and print `WARN …` when a screen is not where it should be. Check the sheet before placing.

`place.py` also copies `media/icon.png` (512×512) and `media/featureGraphic.jpeg` into every locale; the feature
graphic is centre-cropped to the 1024×500 Google Play requires.

## Upload

`fastlane supply` uploads this layout: `phoneScreenshots`, `sevenInchScreenshots`, `tenInchScreenshots`, `wearScreenshots`, `icon`, `featureGraphic` and the texts.
Setup is in `Gemfile` and `fastlane/` (`Appfile`, `Fastfile`). One-time:

1. `sudo apt install ruby-dev build-essential` (fastlane compiles a few native gems), then `bundle install`
   (gems go to `vendor/bundle`, which git ignores). If `bundle` is missing: `gem install --user-install bundler` and add
   `$(ruby -e 'puts Gem.user_dir')/bin` to `PATH`.
2. In Google Cloud create a service account with a JSON key; in the Play Console under **Users and permissions** invite
   the service account's e-mail address with the permission to edit the store listing.
3. Save the key as `fastlane/play-service-account.json` (git ignores it) or point `PLAY_JSON_KEY` at it.
   The app must exist in the Play Console and have had its first release uploaded by hand.

```sh
bundle exec fastlane validate_key             # does the key reach the Play Console?
bundle exec fastlane listing                  # texts + graphics + screenshots, Google only validates
bundle exec fastlane listing publish:true     # really update the listing
bundle exec fastlane texts | screenshots      # only the texts or only the screenshots
```

Supply replaces the screenshots that are already in the listing. It does not upload app bundles or release notes.
