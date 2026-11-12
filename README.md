# EarLoft

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Wear OS](https://img.shields.io/badge/Wear_OS-3%2B-4285F4?logo=wearos&logoColor=white)
![Navidrome](https://img.shields.io/badge/Navidrome-supported-00A4EF)
![Jellyfin](https://img.shields.io/badge/Jellyfin-supported-00A4DC?logo=jellyfin&logoColor=white)
![Emby](https://img.shields.io/badge/Emby-supported-52B54B?logo=emby&logoColor=white)

**EarLoft** is an audio player for your *own* collection: **music**, **podcasts** and **books**. It plays the
files on your phone together with your self-hosted music server ([Navidrome](https://www.navidrome.org),
[Jellyfin](https://jellyfin.org) or [Emby](https://emby.media)) and your
[Audiobookshelf](https://www.audiobookshelf.org) server in **one app** – on your phone, on your Wear OS watch and in
Android Auto. Podcasts come straight from their public RSS feeds. No streaming subscription, no account with a third
party.

Why do you need yet another media player? Well, you don't. I built this app primarily for myself, because this is the way
I like to listen to music, podcasts and audiobooks. There are a lot of good apps that deliver a fantastic experience in
one or two of these areas, but not in all three. Or they want subscription money. Or, if they do cover all three, they
don't support Android Auto or Wear OS, or they can't keep all your media offline on the watch. That's why this app
exists.

## Features

Three main areas: **Music**, **Podcasts** and **Books**. Each has a home view that shows what's new, and your complete
library.

### Everywhere

- **Offline listening.** Download music, podcasts and books; everything you browse comes from a local database, and
  covers and pictures are kept on the device as well, so the app stays usable without a connection.
- **Search** across all three areas or only the selected one, ignoring accents ("Bjork" finds "Björk").
- **Material 3 design** in the colors of the logo or, if you like, of your wallpaper (Material You); album pages take
  their colors from the cover, and the player and the widget can follow the cover or the app colors (**Settings →
  General**). Light, dark or following the system, phone and tablet layouts, English and German.
- **Home screen widget** (4×1) with cover and playback controls, and **Chromecast** (see
  [Known limitations](#known-limitations)).
- **Notifications about new content:** new albums, episodes and books, each area with its own notification (and
  notification channel). Tapping it opens the album, podcast or book directly.
- **Share** songs, albums, artists, podcasts and books as links to open sources (MusicBrainz, the podcast's
  website and feed, Open Library), never to your own servers. Feed links shared from other apps open as a podcast
  preview.
- **Backup to a file** (**Settings → General → Backup**): servers, subscriptions, playlists, progress, statistics and
  all settings in one file you keep yourself, to move to a new phone or start over. Passwords, API keys and sign-ins
  are never part of it; you enter them again after restoring. Restoring adds what is missing and replaces the settings.

### Music

- **One library, no duplicates.** A song on your phone *and* on your server shows up once; the app picks the best
  source (device file, download, then stream).
- **Gapless playback** and **ReplayGain**, so live albums play without breaks and all albums at a similar volume.
- **Synchronized lyrics** when your files or server provide them.
- **Favorites, playlists and play counts sync with your server**, also offline; scrobbling is supported.

### Podcasts

- **Find podcasts** in Apple Podcasts and ARD Sounds (Podcast Index will follow), or add any RSS or Atom feed directly.
  The directories are off until you switch them on, because your search terms are sent to them. Feeds refresh
  automatically in the background.
- **Try before you subscribe:** listen to and download podcasts without subscribing. Subscriptions you no longer
  follow can be archived instead of deleted, so you keep their listening statistics.
- **Continue listening** is an editable queue: the next episode starts when one ends.
- **Chapters, show notes and downloads** per episode, with automatic downloads of new episodes per podcast.
- **Podcast files on your phone** (folder **Podcasts**) show up as podcasts. Their features are limited, though, so I
  wouldn't listen to podcasts this way.
- **Import and export** your subscriptions as OPML, or subscriptions, progress and listening time as a database file.
- **Statistics:** listening time per podcast and per month.
- **Database export and import:** compatible with the great AntennaPod, so you can always switch back to the app you
  prefer. ARD Sounds and archived subscriptions come along.

### Books

- **From your Audiobookshelf server and from your phone.** Books on your phone (folder **Audiobooks**, m4b with
  chapters or folders of MP3 files) show up next to the ones from your server.
- **Sign in with an API key or with OpenID** (for example through your single sign-on); the access token is renewed
  automatically, also for the watch.
- **Progress syncs both ways** with your Audiobookshelf server, also after listening offline.
- **Made for long listening:** speed per book, adjustable jumps (default −10 s / +30 s, also for podcasts), chapters,
  remaining time, sleep timer. If you stopped within the first 30 s of a chapter, playback resumes at its beginning.
- **Browse by series and authors**, download books for offline use.
- **Statistics** from your Audiobookshelf server, next to the listening time counted on this device.

### Your sources

- **Navidrome and other Subsonic servers (music).** Add one or more [Navidrome](https://www.navidrome.org) servers
  (OpenSubsonic API; other Subsonic-compatible servers should work but are not actively tested). Sign in with your
  username and password; the password is stored encrypted with the Android Keystore.
- **Jellyfin and Emby (music).** Add a [Jellyfin](https://jellyfin.org) or [Emby](https://emby.media) server with
  your username and password. Only the music libraries are used, and the app keeps the sign-in token, not the password
  (tested with Jellyfin 12.4 and Emby 4.10).
- **Audiobookshelf (books).** Add your [Audiobookshelf](https://www.audiobookshelf.org) server with an API key or sign
  in with OpenID, if your server offers it.
- **Podcast feeds.** Any public RSS or Atom feed.
- **Your phone.** Music from the device, books from the **Audiobooks** folder and podcasts from the **Podcasts**
  folder. Each is off until you switch it on in the settings; only then does the app ask for access to your audio
  files.

Servers at home (`http://192.168.x.x`) work too. The app warns you before using an unencrypted connection.

### Artist photos, album covers and author pictures (optional, off by default)

Missing pictures can be filled in from the internet. These options are **off by default** because they send names to
third parties, and you can switch them on in **Settings → Music**:

- **Artist photos** from [Deezer](https://www.deezer.com) (artist names are sent).
- **Missing album covers** from [MusicBrainz](https://musicbrainz.org) and the
  [Cover Art Archive](https://coverartarchive.org) (album title and artist are sent).
- **Missing author pictures** from [Audnexus](https://api.audnex.us) (author names are sent).

Covers embedded in your files or provided by your server are always used first.

### On your watch (Wear OS)

- **Music, podcasts and books without your phone.** Tap the watch icon on an album, a playlist, the favorites, an
  episode or a book (or choose **Load onto watch**), on the phone or on the watch under **Phone**. The watch downloads
  them by itself over Wi-Fi directly from your server or the podcast feed, or receives them from the phone (files
  stored on the phone, or when the server is not reachable).
- **Play to Bluetooth headphones**, perfect for running or the gym. The watch speaker is not used. Books and podcasts
  have chapters, a sleep timer and the jump steps set on the phone; progress and listening time go back to the phone
  and from there to Audiobookshelf.
- **Two areas:** **Watch** lists what is on the watch (music, podcasts, books, storage & downloads); **Phone** shows
  the phone's music, podcasts and books, to play them on the phone or to load them onto the watch, and controls the
  phone's playback.
- **Watch quality:** by default songs are converted by the server to Opus 128 kbit/s to save space on the watch;
  you can choose the original files instead. Changing the setting affects new downloads only. Podcasts and books are
  loaded as they are.
- **Storage & downloads** on the watch shows what is loaded and frees space: single entries, one area or everything.

### Android Auto

Android Auto support is included and works fine with my car. So far this is the only real car I could test it with.

- **Music, podcasts and books**, each area as its own tab next to **Recent**, which shows the latest of every area.
  Areas you switched off in the app are left out.
- Albums, podcasts and books as a grid with covers; episodes and books show their progress and whether they are
  downloaded.
- **Search** across all areas you use, and a favorite button for songs.
- Changes on the phone (new songs, a new queue) show up in the car right away.

## Privacy

- Your library, listening history and settings stay on your device and on your own server.
- The app contacts only the servers and podcast feeds you add (and the sites that host their episodes and images). The
  only exceptions are the optional artwork lookups described above and the podcast directories you switch on (your
  search terms are sent to them).
- Server passwords, API keys and sign-in tokens are stored encrypted with the Android Keystore. The OpenID sign-in
  runs in your browser; EarLoft only receives the tokens from your Audiobookshelf server. Android backups contain your
  settings and what no server knows (your servers without their passwords, subscriptions, progress, local playlists
  and listening statistics), but never the library database, downloads or credentials. The same goes for the backup
  file you create yourself.
- There are no ads and no analytics.

## Permissions

EarLoft asks for three permissions, each only when it is needed, and works with whatever you allow. You can refuse each of them and change your
mind later under **System settings → Apps → EarLoft → Permissions** (or **→ Notifications**). The app does not ask for
location, microphone, camera, contacts, phone or Bluetooth access.

### On the phone

**Music and audio** (`READ_MEDIA_AUDIO`; on Android 8–12 *Files and media*, `READ_EXTERNAL_STORAGE`)

- *Asked:* when you switch on **Use music on this device** (**Settings → Music**), **Use podcasts on this device**
  (**Settings → Podcasts**) or **Use books on this device** (**Settings → Books**). All three are off by default.
- *Used for:* reading the music on your phone and the **Audiobooks** and **Podcasts** folders.
- *If you don't allow it:* the switch stays off and EarLoft works with your servers and podcast feeds only. Music,
  books and podcasts stored on the phone don't show up and can't be played or loaded onto the watch from the phone.
  Once Android stops showing the dialog after a refusal, the app opens its system settings page instead. If you take
  the permission away later while a switch is on, the app shows a page to allow it again or to continue without music
  on this device.

**Notifications** (`POST_NOTIFICATIONS`, Android 13 and newer)

- *Asked:* in the setup at the first start, when you tap **Allow notifications**.
- *If you don't allow it:* no notifications about new music, episodes and books, and no progress notifications for
  downloads and transfers to the watch (the downloads and transfers still run). The playback controls in the
  notification shade and on the lock screen keep working, because Android exempts media playback from this
  permission. If only some notifications bother you, switch off single channels instead (*New music*, *New episodes*,
  *New books*, *Downloads*, *Transfers to the watch*).

**Devices on your local network** (`ACCESS_LOCAL_NETWORK`, Android 17 and newer)

- *Asked:* when you save a server.
- *Used for:* reaching servers in your home network, for example `http://192.168.x.x`.
- *If you don't allow it:* connections to addresses in your home network time out, so syncing, streaming and
  downloading from such servers fail and adding one ends with a connection error. Servers you reach over the internet
  (public address) and podcast feeds are not affected.

### On the watch

**Devices on your local network** (`ACCESS_LOCAL_NETWORK`, watches with Android 17 / API 37 and newer)

- *Asked:* when the watch app starts.
- *If you don't allow it:* the watch can't download directly from servers in your home network. After a few failed
  attempts it asks the phone instead, which can only send what it stores itself (files on the phone and downloads);
  everything else stays "failed". Podcasts and servers reached over the internet still load directly. The watch does
  not ask for any other permission.

### Granted without asking

Android grants these at installation; they can't be refused and are listed for completeness: internet and network
state (servers, feeds, "Wi-Fi only" downloads; the watch also requests Wi-Fi for downloads), foreground services for
playback and downloads, keeping the device awake during playback and downloads, and restarting scheduled background
work (feed refresh, sync, downloads) after a reboot.

## Installation

EarLoft is not yet available in an app store. Build it yourself (see [Building from source](#building-from-source));
once a release build is published, it will be listed on the
[Releases page](https://github.com/TinyScripter/EarLoft/releases).

**Requirements:** Android 8.0 or newer (phone), Wear OS 3 or newer (watch). The phone and watch app share one
application ID (`de.kleinabersmart.earloft`), so install both for the watch features.

## Getting started

1. Open the app. At the first start a short setup guides you through the sections you want (music, podcasts, books),
   their sources (servers, subscriptions, files on this device, images from the internet), notifications, the watch and
   the appearance. You can skip it at any time and open it again under **Settings → General → Show setup again**. The
   steps below do the same by hand. Access to your audio files is only asked for when you switch on music, podcasts or
   books on this device, access to your local network (from Android 17) when you add a server. See
   [Permissions](#permissions) for what each one is needed for.
2. **Music:** switch on **Settings → Music → Use music on this device** for the music on your phone. For a server:
   **Settings → Music → Add server**, choose the server type (Navidrome / Subsonic, Jellyfin or Emby), then enter the
   address (for example `https://music.example.com`) and your credentials. The first sync can take a while for large
   libraries.
3. **Podcasts:** switch to **Podcasts** on Home or Library and tap **Add podcast**, then search a directory or paste
   the feed address. Or copy podcast files into the **Podcasts** folder of your phone and switch on **Settings →
   Podcasts → Use podcasts on this device**.
4. **Books:** **Settings → Books → Add server**, then enter your Audiobookshelf address and either tap **Sign in with
   OpenID** (shown when your server offers it) or enter an API key (created in Audiobookshelf under *Settings → API
   Keys*). If your default browser can't hand the sign-in back to the app, EarLoft tells you which address to add in
   Audiobookshelf under *Settings → Authentication → Allowed Mobile Redirect URIs*. Or just copy books into the
   **Audiobooks** folder of your phone and switch on **Settings → Books → Use books on this device**.
5. Play something. On an album, playlist, book or episode you can download it (download arrow) or load it onto your
   watch (watch icon). Downloads are listed under **Settings → General → Downloads**; the watch is managed under
   **Settings → Watch** (shown once a watch with EarLoft is paired).

## Known limitations

- Chromecast plays from your servers and podcast feeds, not files stored on the phone. The Cast device must reach the
  server address itself. If a song is on several servers and the Cast device cannot reach the first one, the app
  switches to the next one after about 20 seconds. Long queues are sent in parts. Casting books depends on the server
  accepting a token in the address and has not been tested widely (with an OpenID sign-in not at all).
- Podcasts: no "download all episodes" for a podcast, and local podcast files have no description unless their tags
  contain one.
- Local books and podcasts are picked up when the app is opened or refreshed, not while it is running.
- Lyrics from `.lrc` files next to your music and a folder view are not supported, because Android does not let apps
  read arbitrary folders without a folder permission. Embedded lyrics and server lyrics work.

## Designed and tested by me, coded by Claude
The whole App has been coded with Claude Code. All final tests were made by me on my Pixel 10 Pro, Pixel Watch 3 and
Galaxy Tab S8.

---

# For developers

## Tech stack

| Area | Technology |
|---|---|
| Language / build | Kotlin 2.4, Gradle 9.8 (wrapper), Android Gradle Plugin 9.6 (alpha, R8 pinned to an earlier alpha), version catalog, convention plugins in `build-logic` |
| Phone UI | Jetpack Compose (BOM 2026.09), Material 3, Navigation 3, MaterialKolor |
| Wear UI | Compose for Wear OS Material 3 |
| Playback | Media3 1.11: ExoPlayer, `MediaLibraryService`, `OkHttpDataSource`, `SimpleCache`, Cast |
| Database | Room with FTS4 search and auto-migrations |
| Network / images | OkHttp 5, kotlinx.serialization, Coil 3; Subsonic, Jellyfin/Emby, Audiobookshelf and RSS/Atom (own XML parser) |
| DI | Hilt |
| Background work | WorkManager (sync queue, downloads, offline images) |
| Secrets / sign-in | Android Keystore (AES-GCM) + DataStore; Auth Tab (AndroidX Browser) with PKCE for the Audiobookshelf OpenID sign-in |
| Widget | Jetpack Glance |
| Phone ↔ watch | Wearable Data Layer (`DataClient`, `MessageClient`, `ChannelClient`) |

SDK levels: `minSdk` 26 (phone) and 30 (watch), `compileSdk` / `targetSdk` 37, Java 17.

## Architecture in short

- **Local database as single source of truth.** The UI only reads from Room; network access runs in the background.
- **Logical song vs. physical source.** A `songs` row is the song the user sees; each file or server entry is a
  `song_sources` row. A merge resolver (MusicBrainz IDs, then normalized artist/album/title) joins them across sources.
- **Sources are resolved at load time.** Queue items only carry `earloft://song/<id>`; a `ResolvingDataSource` decides
  between local file, download and stream when playback starts, so queues survive downloads finishing or servers
  going offline.
- **One `OkHttpClient`.** Interceptors add the server authentication (Subsonic, Jellyfin/Emby, Audiobookshelf), so
  stream and cover URLs stay stable and cacheable and contain no secrets. An expired Audiobookshelf access token is
  renewed once, even when several requests fail at the same time.
- **Offline changes** (favorites, playlists, scrobbles) go through a sync queue that coalesces actions and retries
  with backoff.
- **Images are kept offline.** After each library change a worker loads missing covers, podcast and author pictures
  over Wi-Fi into app storage (outside the cache) and removes the ones no longer needed.
- **Books and podcast episodes share one model.** Both are `books` rows (`kind = BOOK` or `EPISODE`) with files, chapters
  and a progress entry; a position in the whole book is converted to a file and offset when playback starts. The same
  player service, download workers and "continue listening" logic serve both.
- **Browse tree for Android Auto and the system media controls** comes from one `MediaLibraryService`, split by area
  (music, podcasts, books). The watch app has its own player and database and talks to the phone over the Wearable
  Data Layer.

## Project structure

```text
:app                  Phone UI, navigation, widget, Cast, notifications, DI wiring
:wear                 Wear OS app (standalone downloads and playback, remote control of the phone)
:core:model           Plain Kotlin data classes
:core:common          Dispatchers, normalization, shared helpers
:core:database        Room entities, DAOs, FTS, migrations
:core:network         OkHttp client, Subsonic / Jellyfin / Emby / Audiobookshelf APIs (incl. OpenID), podcast feed parser
:core:datasource      Local (MediaStore: music, Audiobooks, Podcasts), Subsonic and Jellyfin/Emby data sources
:core:data            Repositories (music, books, podcasts), library syncer, merge resolver, sync queue, search, offline images
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
  a Canary build of Android Studio. R8 is pinned to an earlier version in the root `build.gradle.kts`, because newer
  R8 alphas produce release builds that crash at startup; check a release build before raising it.
- **JDK 17 or newer** (the build targets Java 17; the Gradle wrapper downloads Gradle itself).
- **Android SDK Platform 37** and current build tools (Android Studio offers to install them).
- For the watch: a Wear OS device or emulator (API 30+). For the phone: Android 8.0+ (API 26+).

### Build

```bash
git clone https://github.com/TinyScripter/EarLoft.git
cd EarLoft

./gradlew :app:assembleDebug      # phone APK  -> app/build/outputs/apk/debug/
./gradlew :wear:assembleDebug     # watch APK  -> wear/build/outputs/apk/debug/
```

Install on a connected device or emulator:

```bash
./gradlew :app:installDebug
./gradlew :wear:installDebug
```

Phone and watch app share the application ID, so with both a phone and a watch connected, choose the device with
`ANDROID_SERIAL` (for example `ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug`); otherwise Gradle installs
the phone app on the watch too.

If Gradle cannot find the SDK, create `local.properties` in the project root with `sdk.dir=/path/to/Android/Sdk`
(Android Studio does this for you).

### Tests

```bash
./gradlew testDebugUnitTest :core:model:test :core:common:test   # unit tests of all modules
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
[issue tracker](https://github.com/TinyScripter/EarLoft/issues). For code changes, please open an issue first
to discuss them and make sure unit tests pass (see [Tests](#tests)).

## License

EarLoft is free software, licensed under the **GNU General Public License v3.0**. You can redistribute it and/or
modify it under the terms of the GPLv3 as published by the Free Software Foundation. The full text is in
[LICENSE](LICENSE).

This program is distributed in the hope that it will be useful, but **without any warranty**; without even the implied
warranty of merchantability or fitness for a particular purpose. See the
[GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.

Third-party libraries keep their own licenses; the list is shown in the app under **Settings → About → Licenses**.
