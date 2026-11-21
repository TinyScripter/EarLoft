# Privacy Policy for EarLoft

**Last updated:** November 22, 2026  
**Application ID:** de.kleinabersmart.earloft (phone and Wear OS watch)

## Overview

EarLoft is an open-source player for your own collection: music from your Navidrome, Subsonic, Jellyfin or Emby
server, podcasts from RSS and Atom feeds, audiobooks from your Audiobookshelf server or from ARD Sounds, and audio
files on your device.
It is built with privacy as a foundational principle. The developer does not track you, does not collect personal
data and does not operate any server of their own. There is no account to create.

---

## 1. Data Collection and Tracking

* **No personal data collected by the developer:** EarLoft does not send your name, location, contacts, device
  identifiers, library or listening history to the developer or to anyone else.
* **No analytics, ads or trackers:** The app contains no analytics libraries, crash reporting services, tracking SDKs
  or advertising networks.
* **Google Play services:** for Chromecast and the watch, EarLoft uses Google's libraries (the Cast SDK and the Wear OS
  data layer of Google Play services). What they process is governed by Google's privacy policy
  (https://policies.google.com/privacy). According to Google, the Cast SDK collects anonymous data about its use, for
  example finding Cast devices, cast sessions and information about the phone and the app, to monitor and improve
  casting; Google keeps it only briefly, does not share it with third parties and does not use it to build a profile
  of you. The developer receives none of this data.

---

## 2. Network Activity

EarLoft connects to the internet only for the features you use. Each connection goes directly from your device (or
watch) to the respective service:

* **Your own servers:** Navidrome / Subsonic, Jellyfin, Emby and Audiobookshelf servers you add. The app syncs your
  library, streams and downloads files, and sends back what you do in the app: favorites, playlists, play counts and
  listening progress. If you leave scrobbling on (**Settings → Music**), played songs are reported to your music
  server, which may forward them to services you configured there (for example Last.fm or ListenBrainz).
* **Podcast feeds:** the feeds you subscribe to and the sites that host their episodes and images, operated by the
  respective publishers.
* **Podcast directories (optional, off by default):** when you switch on a directory (in **Settings → Podcasts** or
  with the gear on **Add podcast**), it is asked for popular podcasts as soon as you open **Add podcast** (your country
  and language are sent), and for your search terms when you search: Apple Podcasts (Apple's search API), Podcast Index (the API of podcastindex.org),
  fyyd (the API of fyyd.de; only your search terms, it is not asked for popular podcasts) and ARD Sounds (ARD's public
  interface). Shows from ARD Sounds without their own feed are also refreshed through that
  interface.
* **Audiobooks from ARD Sounds (optional, off by default):** when you switch them on in **Settings → Books**, the
  books and radio plays you browse, search for, play or download come from ARD's public interface. The books in your
  library and the shows you follow are checked there regularly, also in the background (about every 12 hours, on
  Wi-Fi), for new parts, new books and their expiry date. Your search terms and these books are sent to the ARD.
* **Pictures from the internet (optional, off by default):** artist photos from Deezer (artist names are sent),
  missing album covers from MusicBrainz and the Cover Art Archive (album title and artist are sent), missing author
  pictures from Audnexus (author names are sent).
* **Lyrics from LRCLIB (optional, off by default):** when you switch on **Settings → Music → Look up lyrics online**,
  the title, artist and length of the song that is shown are sent to LRCLIB (lrclib.net) to find missing or synced
  lyrics.
* **OpenID sign-in for Audiobookshelf (optional):** the sign-in page of your server's identity provider opens in your
  browser. EarLoft only receives the tokens from your Audiobookshelf server.
* **Chromecast (optional):** when you cast, the Cast device on your network receives the address of the song and
  plays it directly from your server. Titles that are only on your phone (or whose server the Cast device can't reach)
  it loads from the phone instead: while you cast, EarLoft runs a small web server on your Wi-Fi that serves only the
  titles and covers of this cast session, over plain HTTP, at addresses with a secret that changes with every session.
  Other cast apps on your network can see these addresses. While the Cast device loads from the phone, the app keeps
  the phone and its Wi-Fi awake.

EarLoft does not contact any other service (apart from Google Play services, see section 1). Links you share point to open sources (MusicBrainz, the podcast's website
and feed, Open Library), never to your own servers; the app itself does not contact these sites when you share.

---

## 3. Storage and Security

* **Local storage:** your library, subscriptions, downloads, listening progress, statistics and settings are stored
  on your device (and on your watch, for what you load onto it).
* **Credentials:** server passwords, API keys and sign-in tokens are stored encrypted with the Android Keystore. For
  Jellyfin and Emby only the sign-in token is kept, not the password.
* **Unencrypted connections:** servers and feeds can be reached over plain HTTP (for example at home). The app warns
  you before using an unencrypted connection to a server.

---

## 4. Backups

* **Android backup:** if you use Android's backup to your Google account, it contains your settings and what no
  server knows: your servers *without* their passwords, subscriptions, listening progress, local playlists, listening
  statistics and which episodes were downloaded. It never contains the library database, downloaded files, passwords,
  API keys or sign-in tokens. Feed addresses are kept as they are: if a publisher puts a personal key into the address
  of a paid feed, that address is part of the backup.
* **Backup file:** **Settings → General → Backup** creates a file with the same content that you keep yourself, again
  without passwords, API keys or sign-ins, but with the feed addresses (see above). Where you store or share this file
  is up to you.

---

## 5. Wear OS, Android Auto and Other Apps

* **Watch:** the phone and the watch exchange data through the Wear OS data layer (Google Play services): your server
  settings including the credentials the watch needs, what you choose to load onto the watch, playback commands and
  listening progress. The watch then connects to your servers and podcast feeds itself, and loads books from ARD Sounds
  directly from the ARD. When the phone and the watch aren't connected over Bluetooth, Google Play services may pass
  this data through Google's cloud; according to Google, it is end-to-end encrypted on the way.
* **Android Auto:** your library is shown in the car through Android Auto on your phone.
* **Media controls:** like every media app, EarLoft shares what is playing (title, artist, cover) with the system for
  the notification, the lock screen, the widget and connected devices. Other apps may only control playback; they
  cannot browse your library or change your queue.

---

## 6. Permissions

All permissions are asked for only when needed, and the app works with whatever you allow:

* **Music and audio** (`READ_MEDIA_AUDIO`, on older Android versions storage access): only when you switch on music,
  podcasts or books on this device, to read your audio files.
* **Notifications** (`POST_NOTIFICATIONS`): for new content and download progress.
* **Devices on your local network** (`ACCESS_LOCAL_NETWORK`, Android 17 and newer): to reach servers in your home
  network.
* **Internet, network state, foreground services** for playback, downloads and transfers to the watch.
* **Keeping the device awake** (`WAKE_LOCK`): so that playback, downloads and casting continue with the screen off.
* **Vibration** (`VIBRATE`): short feedback, e.g. when the sleep timer is extended by shaking.
* On the watch additionally **changing the network state** (`CHANGE_NETWORK_STATE`): to request Wi-Fi for downloads.
* **Starting after a restart** (`RECEIVE_BOOT_COMPLETED`): so that scheduled syncs and downloads continue after the device
  restarts.

The sleep timer of books and episodes can be extended by shaking the phone; for this, the motion sensor (accelerometer) is
read only during the timer's last minute and up to two minutes after it ran out. Its values never leave the device. You can
switch this off in the settings.

EarLoft does not ask for location, microphone, camera, contacts, phone or Bluetooth access.

---

## 7. Open Source

EarLoft is open-source software. You can review the complete source code in the public repository:
https://github.com/TinyScripter/EarLoft

---

## 8. Contact and Inquiries

If you have questions, bug reports or concerns regarding this privacy policy, please open an issue in the public
repository:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
