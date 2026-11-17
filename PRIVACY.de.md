# Datenschutzerklärung für EarLoft

**Stand:** 17. November 2026  
**App-ID:** de.kleinabersmart.earloft (Handy und Wear-OS-Uhr)

Maßgeblich ist die englische Fassung (PRIVACY.md); dies ist eine Übersetzung.

## Überblick

EarLoft ist ein quelloffener Player für deine eigene Sammlung: Musik von deinem Navidrome-, Subsonic-, Jellyfin- oder
Emby-Server, Podcasts aus RSS- und Atom-Feeds, Hörbücher von deinem Audiobookshelf-Server oder aus ARD Sounds und
Audiodateien auf deinem Gerät.
Datenschutz ist ein Grundprinzip der App. Der Entwickler verfolgt dich nicht, sammelt keine personenbezogenen Daten
und betreibt keinen eigenen Server. Es gibt kein Konto, das du anlegen musst.

---

## 1. Datenerhebung und Tracking

* **Keine personenbezogenen Daten beim Entwickler:** EarLoft sendet weder deinen Namen noch deinen Standort, deine
  Kontakte, Gerätekennungen, deine Bibliothek oder deinen Hörverlauf an den Entwickler oder an sonst jemanden.
* **Keine Analyse, Werbung oder Tracker:** Die App enthält keine Analyse-Bibliotheken, keine Absturzberichts-Dienste,
  keine Tracking-SDKs und keine Werbenetzwerke.

---

## 2. Netzwerkzugriffe

EarLoft verbindet sich nur für die Funktionen mit dem Internet, die du nutzt. Jede Verbindung geht direkt von deinem
Gerät (bzw. deiner Uhr) zum jeweiligen Dienst:

* **Deine eigenen Server:** die Navidrome-/Subsonic-, Jellyfin-, Emby- und Audiobookshelf-Server, die du hinzufügst.
  Die App gleicht deine Bibliothek ab, streamt und lädt Dateien und meldet zurück, was du in der App tust: Favoriten,
  Playlists, Wiedergabezähler und Hörfortschritt. Wenn **Einstellungen → Musik → Wiedergaben an den Server melden**
  eingeschaltet bleibt, werden gespielte Titel an deinen Musikserver gemeldet; der kann sie an Dienste weitergeben,
  die du dort eingerichtet hast (zum Beispiel Last.fm oder ListenBrainz).
* **Podcast-Feeds:** die Feeds, die du abonnierst, und die Seiten, auf denen deren Folgen und Bilder liegen; betrieben
  von den jeweiligen Anbietern.
* **Podcast-Verzeichnisse (optional, standardmäßig aus):** Schaltest du in **Einstellungen → Podcasts** ein
  Verzeichnis ein, wird es nach beliebten Podcasts gefragt, sobald du **Podcast hinzufügen** öffnest (dabei werden dein
  Land und deine Sprache gesendet), und bei einer Suche nach deinen Suchbegriffen: Apple Podcasts (Apples Such-API),
  Podcast Index (die API von podcastindex.org) und ARD Sounds (öffentliche Schnittstelle der ARD). Sendungen aus ARD
  Sounds ohne eigenen Feed werden ebenfalls über diese Schnittstelle aktualisiert.
* **Hörbücher aus ARD Sounds (optional, standardmäßig aus):** Schaltest du sie in **Einstellungen → Bücher** ein,
  kommen die Bücher und Hörspiele, die du durchstöberst, suchst, abspielst oder herunterlädst, über die öffentliche
  Schnittstelle der ARD. Die Bücher in deiner Bibliothek und die Sendungen, denen du folgst, werden dort regelmäßig,
  auch im Hintergrund (etwa alle 12 Stunden, im WLAN), auf neue Teile, neue Bücher und ihr Ablaufdatum geprüft. Deine
  Suchbegriffe und diese Bücher werden an die ARD gesendet.
* **Bilder aus dem Internet (optional, standardmäßig aus):** Künstlerfotos von Deezer (Künstlernamen werden gesendet),
  fehlende Album-Cover von MusicBrainz und dem Cover Art Archive (Albumtitel und Künstler werden gesendet), fehlende
  Autorenbilder von Audnexus (Autorennamen werden gesendet).
* **Songtexte von LRCLIB (optional, standardmäßig aus):** Schaltest du **Einstellungen → Musik → Songtexte im Internet
  suchen** ein, werden Titel, Künstler und Länge des angezeigten Songs an LRCLIB (lrclib.net) gesendet, um fehlende
  oder synchronisierte Songtexte zu finden.
* **OpenID-Anmeldung bei Audiobookshelf (optional):** Die Anmeldeseite des Identitätsanbieters deines Servers öffnet
  sich in deinem Browser. EarLoft erhält nur die Tokens von deinem Audiobookshelf-Server.
* **Chromecast (optional):** Wenn du castest, bekommt das Cast-Gerät in deinem Netzwerk die Adresse des Titels und
  spielt ihn direkt von deinem Server ab.

EarLoft kontaktiert keine anderen Dienste. Links, die du teilst, zeigen auf offene Quellen (MusicBrainz, die Website
und den Feed des Podcasts, Open Library), nie auf deine eigenen Server; die App selbst ruft diese Seiten beim Teilen
nicht auf.

---

## 3. Speicherung und Sicherheit

* **Lokale Speicherung:** Deine Bibliothek, Abos, Downloads, dein Hörfortschritt, Statistiken und Einstellungen werden
  auf deinem Gerät gespeichert (und auf deiner Uhr, soweit du etwas darauf lädst).
* **Zugangsdaten:** Server-Passwörter, API-Schlüssel und Anmelde-Tokens werden mit dem Android Keystore verschlüsselt
  gespeichert. Bei Jellyfin und Emby wird nur das Anmelde-Token behalten, nicht das Passwort.
* **Unverschlüsselte Verbindungen:** Server und Feeds sind auch über einfaches HTTP erreichbar (zum Beispiel zu Hause).
  Die App warnt dich, bevor sie eine unverschlüsselte Verbindung zu einem Server nutzt.

---

## 4. Sicherungen

* **Android-Sicherung:** Nutzt du die Android-Sicherung in dein Google-Konto, enthält sie deine Einstellungen und das,
  was kein Server kennt: deine Server *ohne* Passwörter, Abos, Hörfortschritt, lokale Playlists, Hörstatistiken und
  welche Folgen heruntergeladen waren. Sie enthält nie die Bibliotheksdatenbank, heruntergeladene Dateien, Passwörter,
  API-Schlüssel oder Anmelde-Tokens. Feed-Adressen bleiben, wie sie sind: Steckt ein Anbieter einen persönlichen
  Schlüssel in die Adresse eines bezahlten Feeds, ist diese Adresse Teil der Sicherung.
* **Sicherungsdatei:** **Einstellungen → Allgemein → Sicherung** erstellt eine Datei mit demselben Inhalt, die du
  selbst aufbewahrst, ebenfalls ohne Passwörter, API-Schlüssel und Anmeldungen, aber mit den Feed-Adressen (siehe
  oben). Wo du diese Datei speicherst oder mit wem du sie teilst, entscheidest du.

---

## 5. Wear OS, Android Auto und andere Apps

* **Uhr:** Handy und Uhr tauschen Daten über die Wear-OS-Datenschicht (Google Play-Dienste) aus: deine
  Server-Einstellungen samt den Zugangsdaten, die die Uhr braucht, was du auf die Uhr laden willst,
  Wiedergabebefehle und den Hörfortschritt. Die Uhr verbindet sich dann selbst mit deinen Servern und Podcast-Feeds.
* **Android Auto:** Deine Bibliothek wird im Auto über Android Auto auf deinem Handy angezeigt.
* **Mediensteuerung:** Wie jede Medien-App teilt EarLoft dem System mit, was gerade läuft (Titel, Künstler, Cover), für
  die Benachrichtigung, den Sperrbildschirm, das Widget und verbundene Geräte. Andere Apps können nur die Wiedergabe
  steuern; sie können deine Bibliothek nicht durchsuchen und deine Warteschlange nicht ändern.

---

## 6. Berechtigungen

Alle Berechtigungen werden erst abgefragt, wenn sie gebraucht werden, und die App funktioniert mit dem, was du
erlaubst:

* **Musik und Audio** (`READ_MEDIA_AUDIO`, auf älteren Android-Versionen Speicherzugriff): nur wenn du Musik, Podcasts
  oder Bücher auf diesem Gerät einschaltest, um deine Audiodateien zu lesen.
* **Benachrichtigungen** (`POST_NOTIFICATIONS`): für neue Inhalte und den Download-Fortschritt.
* **Geräte im lokalen Netzwerk** (`ACCESS_LOCAL_NETWORK`, ab Android 17): um Server in deinem Heimnetz zu erreichen.
* **Internet, Netzwerkstatus, Vordergrunddienste** für Wiedergabe, Downloads und Übertragungen auf die Uhr.

EarLoft fragt nicht nach Zugriff auf Standort, Mikrofon, Kamera, Kontakte, Telefon oder Bluetooth.

---

## 7. Open Source

EarLoft ist quelloffene Software. Den vollständigen Quellcode kannst du im öffentlichen Repository einsehen:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt und Anfragen

Bei Fragen, Fehlerberichten oder Anliegen zu dieser Datenschutzerklärung eröffne bitte ein Issue im öffentlichen
Repository:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
