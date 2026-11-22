# Demo library

A made-up library for store screenshots and demos: 11 albums, 5 podcast subscriptions and 5 audiobooks. Every name,
text and cover is invented (`catalog.py`); the covers are drawn by `covers.py`, and the audio is silence with
realistic durations; "Gapless (Finally)" carries made-up synchronized lyrics (`LYRICS`). Nothing here comes from real
artists, shows or books, so screenshots can be published.

| | Where on the device | Read by the app as |
|---|---|---|
| Albums | `Music/<Artist>/<Album>/NN - <Title>.mp3` (tags, embedded cover, `cover.jpg`) | Local storage |
| Audiobooks | `Audiobooks/<Author>/[<Series>/<N> – ]<Title> {<Narrator>}/` with an m4b (chapter marks) or one MP3 per chapter, `cover.jpg`, `desc.txt`, `metadata.opf` | Local storage, audiobook folder |
| Podcasts | RSS feeds from `serve.py` on `http://127.0.0.1:8765/<slug>/feed.xml`, `Download/demo-podcasts.opml` | Podcast subscriptions |

## Requirements

- Python 3 with Pillow, `ffmpeg` with `libmp3lame` and `aac`.
- Fonts: DejaVu, FreeFont or URW base35 (`covers.py` falls back to Pillow's built-in font).
- The phone emulator (`PHONE_SERIAL`, default `emulator-5554`) with the current debug build.

## Steps

```sh
python3 tools/demo-library/build.py               # into build/demo-library (about 3 min, 175 MB)
tools/demo-library/install.sh --wipe              # clears the app and Music/, Audiobooks/, Podcasts/ first
```

`install.sh` pushes the files, rescans the media store, starts `serve.py` in the background (log in
`/tmp/demo-feed-server.log`) and forwards the port with `adb reverse`. It refuses to run against anything that is not
an emulator. Then, in the setup that the cleared app shows:

1. **Music**: switch on *Use music on this device* and allow access to audio. Leave the online services (artist
   images, missing covers, lyrics) off: they would look up the made-up names and could bring real photos.
2. **Podcasts → Add podcast → ⋮ → Import subscriptions (OPML)**: pick `Download/demo-podcasts.opml`, then *Add*.
3. **Books**: switch on *Use books on this device*; leave ARD Sounds off. Afterwards, under **Settings → Books →
   Covers and texts from book folders**, allow the `Audiobooks` folder (descriptions, series, genres from
   `metadata.opf`) and tap *Sync books now*.
4. Play a few things so that "Continue listening", the queue and the progress bars have content.

For podcast episodes on the watch, forward the port there as well: `adb -s <watch> reverse tcp:8765 tcp:8765`.

Podcast release dates count back from the moment `build.py` runs, so the newest episodes are "new". Rebuild the
feeds before taking screenshots on another day: delete `build/demo-library/feeds` and run `build.py` again (music
and audiobooks are kept), then restart `serve.py`.
After a reboot of the host or the emulator, run `install.sh` again (without `--wipe`) to restart the server and the
port forwarding.

The watch gets the library from the phone: choose albums under **Load to watch** on the phone.
