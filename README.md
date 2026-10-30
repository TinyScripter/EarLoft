# kleinaberlaut

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Wear OS](https://img.shields.io/badge/Wear_OS-3%2B-4285F4?logo=wearos&logoColor=white)
![Navidrome](https://img.shields.io/badge/Navidrome-supported-00A4EF)

**kleinaberlaut** is a music player for your *own* music collection. It plays the files on your phone and the music on
your self-hosted [Navidrome](https://www.navidrome.org) server in **one shared library** – on your phone and on your
Wear OS watch. No streaming subscription, no account with a third party.

> The name is just a name. kleinaberlaut is german and means "small but loud". For now, I'd like to keep it that way.

## Features

### On your phone

- **One library, no duplicates.** A song that exists on your phone *and* on your server shows up once. The app picks
  the best source automatically: file on the device, then a downloaded copy, then the stream.
- **Albums, artists, songs, genres, playlists and favorites**, with fast search that ignores accents
  (searching for "Bjork" finds "Björk").
- **Gapless playback**, so live albums and DJ mixes play without a break between tracks.
  Streams are played in their original quality by default, because server-side transcoding can introduce gaps.
- **ReplayGain** (track, album or automatic) so quiet and loud albums play at a similar volume.
- **Lyrics**, synchronized and scrolling along with the music when your files or server provide them.
- **Offline listening.** Download albums, playlists or single songs, or let the app download your favorites (or a
  whole server) automatically. Everything you browse comes from a local database, so the app stays usable without a
  connection.
- **Favorites, playlists and play counts sync with your server.** Changes you make offline are sent as soon as you
  are online again. Scrobbling to the server is supported.
- **Queue management:** reorder by drag and drop, shuffle, repeat, and continue where you stopped after a restart.
- **Material You design** with colors taken from the album cover, light and dark mode.
- **Home screen widget** (4×1) with cover and playback controls.
- **Chromecast** for songs that are on a server (not for files that exist only on your phone).

### Navidrome and other Subsonic servers

Add one or more [Navidrome](https://www.navidrome.org) servers (OpenSubsonic API; other Subsonic-compatible servers
should work but are not actively tested). Sign in with your username and password; the password is stored encrypted
with the Android Keystore. Servers at home (`http://192.168.x.x`) work too. The app warns you before using an
unencrypted connection.

### Artist photos and album covers (optional, off by default)

Missing pictures can be filled in from the internet. Both options are **off by default** because they send names to
third parties, and you can switch them on in **Settings**:

- **Artist photos** from [Deezer](https://www.deezer.com) (artist names are sent).
- **Missing album covers** from [MusicBrainz](https://musicbrainz.org) and the
  [Cover Art Archive](https://coverartarchive.org) (album title and artist are sent).

Covers embedded in your files or provided by your server are always used first.

### On your watch (Wear OS)

- **Music without your phone.** Choose albums or playlists on the phone (**Load onto watch**). The watch downloads them
  by itself over Wi-Fi directly from your server, or receives them from the phone if the server is not reachable.
- **Play to Bluetooth headphones**, perfect for running or the gym. The watch speaker is not used.
- **Watch quality:** by default songs are converted by the server to Opus 128 kbit/s to save space on the watch;
  you can choose the original files instead. Changing the setting affects new downloads only.
- Your phone's playback can also be controlled from the watch's media controls.

### Android Auto (in development)

Android Auto support is **work in progress** and has not been tested in a car or on the Desktop Head Unit yet.
It is implemented as a regular media app with four tabs (Recently played, Library, Playlists, Favorites), search, voice
commands ("Play …") and a favorite button. Expect rough edges, and please report what you find.

## Privacy

- Your library, listening history and settings stay on your device and on your own server.
- The app contacts only the servers you add. The only exceptions are the two optional artwork lookups described above.
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

1. Open the app and allow access to your music (and notifications) when asked, or skip it if you only use servers.
2. To add a server: **Settings → Servers → Add server**, then enter the address (for example
   `https://music.example.com`) and your credentials. The first sync can take a while for large libraries.
3. Play something. On an album or playlist you can download it (download arrow) or load it onto your watch.
   Downloaded music is listed under **Downloads**; the watch is managed under **Settings → Watch**.

## Known limitations

- Android Auto is in development (see above).
- Chromecast only plays songs that are available on a server and is limited to 50 songs in the queue.
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
| Network / images | OkHttp 5, kotlinx.serialization, Coil 3 |
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
:core:network         OkHttp client, Subsonic auth interceptor, API and DTOs
:core:datasource      Local (MediaStore) and Subsonic data sources
:core:data            Repositories, library syncer, merge resolver, sync queue
:core:download        Download workers and storage management
:core:playback        Media library service, ReplayGain, source resolution, Auto browse tree
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

### Trying it with a local Navidrome

Any Navidrome instance works. For an emulator, start Navidrome on your computer and forward the port, so the app can
use `http://127.0.0.1:4533`:

```bash
adb reverse tcp:4533 tcp:4533
```

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
