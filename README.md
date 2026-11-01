# kleinaberlaut

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Wear OS](https://img.shields.io/badge/Wear_OS-3%2B-4285F4?logo=wearos&logoColor=white)
![Navidrome](https://img.shields.io/badge/Navidrome-supported-00A4EF)

**kleinaberlaut** is an audio player for your *own* collection: **music**, **podcasts** and **audiobooks**. It plays the
files on your phone together with your self-hosted [Navidrome](https://www.navidrome.org) music server and your
[Audiobookshelf](https://www.audiobookshelf.org) server in **one app** – on your phone and, for music, on your
Wear OS watch. Podcasts come straight from their public RSS feeds. No streaming subscription, no account with a third
party.

> kleinaberlaut is german and means "small but loud". For now, I'd like to keep it that way.

## Features

A switch at the top of **Home** and **Library** changes between the three areas: **Music**, **Podcasts** and
**Books**. **Search** has the same switch and searches in the selected area, or everywhere (see below).

### Everywhere

- **Offline listening.** Download music, podcast episodes and books. Everything you browse comes from a local database,
  so the app stays usable without a connection. **Settings → Downloads** lists and removes them.
- **Material You design** with colors taken from the cover (albums, books and episodes), light and dark mode, phone and
  tablet layouts (navigation rail and list/detail view on wide screens). English and German.
- **Search** over music, podcasts and books, with accent-insensitive matching (searching for "Bjork" finds "Björk").
  A chip in the search bar switches between **search everywhere** and searching only the selected area. Results are
  grouped by area, each with its own categories:
  - Music: artists, albums, songs
  - Podcasts: subscriptions, episodes
  - Books: series, authors, books
- **Play / pause buttons on covers.** Every album, book and episode tile in the overviews (Home, Library, search) has
  a play/pause button; the one that is playing shows pause.
- **Sleep timer**, a **mini player** and a full **player screen** that fits the content (songs, episodes, books).
- **Home screen widget** (4×1) with cover and playback controls: previous/next for music, −30 s / +10 s for books and
  episodes, which also show the elapsed and total time. The widget takes its colors from the cover.
- **Chromecast** for songs that are on a server, and for books and episodes (see [Known limitations](#known-limitations)).

### Music

- **One library, no duplicates.** A song that exists on your phone *and* on your server shows up once. The app picks
  the best source automatically: file on the device, then a downloaded copy, then the stream.
- **Albums, artists, songs, playlists and favorites**, with "recently added" and "recently played" on Home.
- **Gapless playback**, so live albums and DJ mixes play without a break between tracks.
  Streams are played in their original quality by default, because server-side transcoding can introduce gaps.
- **ReplayGain** (track, album or automatic) so quiet and loud albums play at a similar volume.
- **Lyrics**, synchronized and scrolling along with the music when your files or server provide them.
- **Smart downloads.** Download albums, playlists or single songs, or let the app download your favorites (or a whole
  server) automatically, Wi-Fi only by default.
- **Favorites, playlists and play counts sync with your server.** Changes you make offline are sent as soon as you
  are online again. Scrobbling to the server is supported.
- **Queue management:** reorder by drag and drop, shuffle, repeat, and continue where you stopped after a restart.

### Podcasts

- **Subscribe with a feed address.** Add any podcast by its RSS or Atom feed URL. Feeds refresh automatically in the
  background (interval and mobile data are configurable).
- **Continue listening.** Home shows the episodes you started (the most recent first) followed by new ones, as a row
  you can edit: reorder by drag and drop or remove episodes. This row is also the queue: when an episode ends, the
  next one starts (can be switched off).
- **Per podcast:** newest or oldest first, "play the next episode automatically", automatic downloads of the newest
  unheard episodes, search within the podcast's episodes, and a page with the full description.
- **Episode details:** show notes with working links, **chapters** (from the feed's Podcasting 2.0 chapter list or
  embedded in the file), and an episode menu (long press) to mark as played, reset the position or delete the download.
- **Downloads** per episode, resumable, with an option to remove downloads of heard episodes after a chosen time.
- **Podcast files on your phone.** Audio files in the **Podcasts** folder of your device show up as podcasts, one per
  subfolder or album, including date, description and chapters from the file's tags.

### Books (Audiobookshelf)

- **Connect your Audiobookshelf server** with an API key (several servers are possible, each can be switched off).
  Books, covers, descriptions and chapters are synced into the app.
- **Your progress syncs both ways.** The position is sent to the server while you listen and offline changes are
  delivered later; when you open a book the app jumps to the newest position, whether it was set here or elsewhere.
- **Built for long listening:** speed from 0.5× to 3× per book, −30 s / +10 s jumps (also on the notification and
  the widget), chapters with next/previous, remaining time for the book and for the chapter.
- **Library views:** all books, **series** (ordered by volume) and **authors** (with author pictures from your server).
- **Downloads** per book, resumable after interruptions, and playback from the file while offline.
- **Books on your phone.** Audio files in the **Audiobooks** folder of your device show up as books: a single file
  (e.g. m4b with chapters) or a folder of MP3 files, one chapter per file.

### Your sources

- **Navidrome and other Subsonic servers (music).** Add one or more [Navidrome](https://www.navidrome.org) servers
  (OpenSubsonic API; other Subsonic-compatible servers should work but are not actively tested). Sign in with your
  username and password; the password is stored encrypted with the Android Keystore.
- **Audiobookshelf (books).** Add your [Audiobookshelf](https://www.audiobookshelf.org) server with an API key.
- **Podcast feeds.** Any public RSS or Atom feed.
- **Your phone.** Music from the device, books from the **Audiobooks** folder and podcasts from the **Podcasts**
  folder (each can be switched off in the settings).

Servers at home (`http://192.168.x.x`) work too. The app warns you before using an unencrypted connection.

### Artist photos, album covers and author pictures (optional, off by default)

Missing pictures can be filled in from the internet. These options are **off by default** because they send names to
third parties, and you can switch them on in **Settings → General**:

- **Artist photos** from [Deezer](https://www.deezer.com) (artist names are sent).
- **Missing album covers** from [MusicBrainz](https://musicbrainz.org) and the
  [Cover Art Archive](https://coverartarchive.org) (album title and artist are sent).
- **Missing author pictures** from [Audnexus](https://api.audnex.us) (author names are sent).

Covers embedded in your files or provided by your server are always used first.

### On your watch (Wear OS, music)

- **Music without your phone.** Choose albums or playlists on the phone (**Load onto watch**). The watch downloads them
  by itself over Wi-Fi directly from your server, or receives them from the phone if the server is not reachable.
- **Play to Bluetooth headphones**, perfect for running or the gym. The watch speaker is not used.
- **Watch quality:** by default songs are converted by the server to Opus 128 kbit/s to save space on the watch;
  you can choose the original files instead. Changing the setting affects new downloads only.
- Your phone's playback can also be controlled from the watch's media controls.

### Android Auto (in development)

Android Auto support is **work in progress** and has not been tested in a car or on the Desktop Head Unit yet.
It covers music only and is implemented as a regular media app with four tabs (Recently played, Library, Playlists, Favorites), search, voice
commands ("Play …") and a favorite button. Expect rough edges, and please report what you find.

## Privacy

- Your library, listening history and settings stay on your device and on your own server.
- The app contacts only the servers and podcast feeds you add (and the sites that host their episodes and images). The
  only exceptions are the optional artwork lookups described above.
- Server passwords are stored encrypted with the Android Keystore. Neither the database, the downloads nor the
  credentials are included in Android backups.
- There are no ads and no analytics.

## Installation

kleinaberlaut is not yet available in an app store. Build it yourself (see [Building from source](#building-from-source));
once a release build is published, it will be listed on the
[Releases page](https://github.com/TinyScripter/kleinaberlaut/releases).

**Requirements:** Android 8.0 or newer (phone), Wear OS 3 or newer (watch). The phone and watch app share one
application ID (`keinabersmart.kleinaberlaut`), so install both for the watch features.

## Getting started

1. Open the app and allow access to your audio files (and notifications) when asked, or skip it if you only use
   servers and feeds.
2. **Music:** **Settings → Music → Add server**, then enter the address (for example `https://music.example.com`) and
   your credentials. The first sync can take a while for large libraries.
3. **Podcasts:** switch to **Podcasts** on Home or Library and tap **Add podcast**, then paste the feed address.
4. **Books:** **Settings → Audiobooks → Add server**, then enter your Audiobookshelf address and an API key (created in
   Audiobookshelf under *Settings → API Keys*). Or just copy audiobooks into the **Audiobooks** folder of your phone.
5. Play something. On an album, playlist, book or episode you can download it (download arrow); music can also be
   loaded onto your watch. Downloads are listed under **Settings → Downloads**; the watch is managed under
   **Settings → Watch**.

## Known limitations

- Android Auto is in development (see above), and so far covers music only.
- The watch app plays music only; books and podcasts are not yet available on Wear OS or in Android Auto.
- Chromecast: songs are limited to 50 in the queue. Casting books and episodes depends on the server accepting a
  token in the address and has not been tested widely.
- Podcasts: no "download all episodes" for a podcast, and local podcast files have no description unless their tags
  contain one.
- Local books and podcasts are picked up when the app is opened or refreshed, not while it is running.
- Lyrics from `.lrc` files next to your music and a folder view are not supported, because Android does not let apps
  read arbitrary folders without a folder permission. Embedded lyrics and server lyrics work.

---

# For developers

## Tech stack

| Area | Technology |
|---|---|
| Language / build | Kotlin 2.4, Gradle 9.8 (wrapper), Android Gradle Plugin 9.6 (alpha), version catalog, convention plugins in `build-logic` |
| Phone UI | Jetpack Compose (BOM 2026.09), Material 3, Navigation 3, MaterialKolor |
| Wear UI | Compose for Wear OS Material 3 |
| Playback | Media3 1.11: ExoPlayer, `MediaLibraryService`, `OkHttpDataSource`, `SimpleCache`, Cast |
| Database | Room with FTS4 search and auto-migrations |
| Network / images | OkHttp 5, kotlinx.serialization, Coil 3; Subsonic, Audiobookshelf and RSS/Atom (own XML parser) |
| DI | Hilt |
| Background work | WorkManager (sync queue, downloads) |
| Secrets | Android Keystore (AES-GCM) + DataStore |
| Widget | Jetpack Glance |
| Phone ↔ watch | Wearable Data Layer (`DataClient`, `MessageClient`, `ChannelClient`) |

SDK levels: `minSdk` 26 (phone) and 30 (watch), `compileSdk` / `targetSdk` 37, Java 17.

## Architecture in short

- **Local database as single source of truth.** The UI only reads from Room; network access runs in the background.
- **Logical song vs. physical source.** A `songs` row is the song the user sees; each file or server entry is a
  `song_sources` row. A merge resolver (MusicBrainz IDs, then normalized artist/album/title) joins them across sources.
- **Sources are resolved at load time.** Queue items only carry `kal://song/<id>`; a `ResolvingDataSource` decides
  between local file, download and stream when playback starts, so queues survive downloads finishing or servers
  going offline.
- **One `OkHttpClient`.** An interceptor adds the Subsonic authentication, so stream and cover URLs stay stable and
  cacheable and contain no secrets.
- **Offline changes** (favorites, playlists, scrobbles) go through a sync queue that coalesces actions and retries
  with backoff.
- **Books and podcast episodes share one model.** Both are `books` rows (`kind = BOOK` or `EPISODE`) with files, chapters
  and a progress entry; a position in the whole book is converted to a file and offset when playback starts. The same
  player service, download workers and "continue listening" logic serve both.
- **Browse tree for Auto, the watch and the system UI** comes from one `MediaLibraryService`.

Details and decisions are in [`ideas/konzept_und_architektur.md`](ideas/konzept_und_architektur.md) (concept),
[`ideas/wear_konzept.md`](ideas/wear_konzept.md) (watch) and [`ideas/stand.md`](ideas/stand.md) (current status and
open points). Those documents are written in German.

## Project structure

```text
:app                  Phone UI, navigation, widget, Cast, DI wiring
:wear                 Wear OS app (standalone downloads and playback)
:core:model           Plain Kotlin data classes
:core:common          Dispatchers, normalization, shared helpers
:core:database        Room entities, DAOs, FTS, migrations
:core:network         OkHttp client, Subsonic / Audiobookshelf APIs, podcast feed parser
:core:datasource      Local (MediaStore: music, Audiobooks, Podcasts) and Subsonic data sources
:core:data            Repositories (music, books, podcasts), library syncer, merge resolver, sync queue
:core:download        Download workers (songs, books, episodes), resumable transfers, storage
:core:playback        Media library service, ReplayGain, source resolution, book/episode playback, Auto browse tree
:core:designsystem    Theme and shared phone components
:core:wearsync        Phone ↔ watch protocol
build-logic/          Convention plugins
```

## Building from source

### Prerequisites

- A recent **Android Studio** that supports the Android Gradle Plugin version in
  [`gradle/libs.versions.toml`](gradle/libs.versions.toml). The project currently uses an AGP 9 alpha, so you may need
  a Canary build of Android Studio.
- **JDK 17 or newer** (the build targets Java 17; the Gradle wrapper downloads Gradle itself).
- **Android SDK Platform 37** and current build tools (Android Studio offers to install them).
- For the watch: a Wear OS device or emulator (API 30+). For the phone: Android 8.0+ (API 26+).

### Build

```bash
git clone https://github.com/TinyScripter/kleinaberlaut.git
cd kleinaberlaut

./gradlew :app:assembleDebug      # phone APK  -> app/build/outputs/apk/debug/
./gradlew :wear:assembleDebug     # watch APK  -> wear/build/outputs/apk/debug/
```

Install on a connected device or emulator:

```bash
./gradlew :app:installDebug
./gradlew :wear:installDebug
```

If Gradle cannot find the SDK, create `local.properties` in the project root with `sdk.dir=/path/to/Android/Sdk`
(Android Studio does this for you).

### Tests

```bash
./gradlew testDebugUnitTest       # unit tests of all modules
```

### Release build and signing

Release builds are optimized with R8. To sign them, create `keystore.properties` in the project root (it is not
checked in):

```properties
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without this file `release` builds still run, but the APK stays unsigned.

```bash
./gradlew :app:assembleRelease :wear:assembleRelease
```

### Trying it with a local Navidrome (or Audiobookshelf)

Any Navidrome instance works. For an emulator, start Navidrome on your computer and forward the port, so the app can
use `http://127.0.0.1:4533`:

```bash
adb reverse tcp:4533 tcp:4533
```

Audiobookshelf works the same way, with its own port (by default `13378`).

## Contributing

Bug reports and ideas are welcome in the
[issue tracker](https://github.com/TinyScripter/kleinaberlaut/issues). For code changes, please open an issue first
to discuss them and make sure unit tests pass (`./gradlew testDebugUnitTest`).

## License

kleinaberlaut is free software, licensed under the **GNU General Public License v3.0**. You can redistribute it and/or
modify it under the terms of the GPLv3 as published by the Free Software Foundation. The full text is in
[LICENSE](LICENSE).

This program is distributed in the hope that it will be useful, but **without any warranty**; without even the implied
warranty of merchantability or fitness for a particular purpose. See the
[GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.

Third-party libraries keep their own licenses; the list is shown in the app under **Settings → About → Licenses**.
