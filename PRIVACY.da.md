# Privatlivspolitik for EarLoft

**Sidst opdateret:** 20. november 2026  
**Program-id:** de.kleinabersmart.earloft (telefon og Wear OS-ur)

## Oversigt

EarLoft er en afspiller med åben kildekode til din egen samling: musik fra din Navidrome-, Subsonic-, Jellyfin- eller
Emby-server, podcasts fra RSS- og Atom-feeds, lydbøger fra din Audiobookshelf-server eller fra ARD Sounds samt lydfiler
på din enhed.
Den er bygget med privatliv som et grundlæggende princip. Udvikleren sporer dig ikke, indsamler ingen personoplysninger
og driver ingen egen server. Der er ingen konto, du skal oprette.

---

## 1. Dataindsamling og sporing

* **Udvikleren indsamler ingen personoplysninger:** EarLoft sender ikke dit navn, din placering, dine kontakter,
  enheds-id’er, dit bibliotek eller din lyttehistorik til udvikleren eller til nogen anden.
* **Ingen analyse, annoncer eller trackere:** Appen indeholder ingen analysebiblioteker, nedbrudsrapporteringstjenester,
  sporings-SDK’er eller annoncenetværk.

---

## 2. Netværksaktivitet

EarLoft opretter kun forbindelse til internettet for de funktioner, du bruger. Hver forbindelse går direkte fra din
enhed (eller dit ur) til den pågældende tjeneste:

* **Dine egne servere:** Navidrome-/Subsonic-, Jellyfin-, Emby- og Audiobookshelf-servere, som du tilføjer. Appen
  synkroniserer dit bibliotek, streamer og downloader filer og sender det tilbage, du gør i appen: favoritter,
  playlister, antal afspilninger og lyttefremskridt. Hvis du lader scrobbling være slået til (**Indstillinger →
  Musik**), rapporteres afspillede sange til din musikserver, som kan videresende dem til tjenester, du har
  konfigureret der (for eksempel Last.fm eller ListenBrainz).
* **Podcastfeeds:** de feeds, du abonnerer på, og de websteder, der hoster deres afsnit og billeder, og som drives af
  de respektive udgivere.
* **Podcastkataloger (valgfrit, slået fra som standard):** Når du slår et katalog til (under **Indstillinger →
  Podcasts** eller med tandhjulet under **Tilføj podcast**), bliver det spurgt om populære podcasts, så snart du åbner
  **Tilføj podcast** (dit land og dit sprog sendes), og om dine søgeord, når du søger: Apple Podcasts (Apples
  søge-API), Podcast Index (API’et fra podcastindex.org), fyyd (API’et fra fyyd.de; kun dine søgeord, det bliver ikke
  spurgt om populære podcasts) og ARD Sounds (ARD’s offentlige grænseflade). Programmer fra ARD Sounds uden eget feed
  opdateres også via denne grænseflade.
* **Lydbøger fra ARD Sounds (valgfrit, slået fra som standard):** Når du slår dem til under **Indstillinger → Bøger**,
  kommer de bøger og hørespil, du gennemser, søger efter, afspiller eller downloader, fra ARD’s offentlige grænseflade.
  Bøgerne i dit bibliotek og de programmer, du følger, tjekkes der regelmæssigt, også i baggrunden (ca. hver 12. time,
  via Wi-Fi), for nye dele, nye bøger og deres udløbsdato. Dine søgeord og disse bøger sendes til ARD.
* **Billeder fra internettet (valgfrit, slået fra som standard):** kunstnerfotos fra Deezer (kunstnernavne sendes),
  manglende albumcovers fra MusicBrainz og Cover Art Archive (albumtitel og kunstner sendes), manglende
  forfatterbilleder fra Audnexus (forfatternavne sendes).
* **Sangtekster fra LRCLIB (valgfrit, slået fra som standard):** Når du slår **Indstillinger → Musik → Søg efter
  sangtekster online** til, sendes titel, kunstner og længde på den viste sang til LRCLIB (lrclib.net) for at finde
  manglende eller synkroniserede sangtekster.
* **OpenID-login til Audiobookshelf (valgfrit):** Loginsiden fra din servers identitetsudbyder åbnes i din browser.
  EarLoft modtager kun tokens fra din Audiobookshelf-server.
* **Chromecast (valgfrit):** Når du caster, modtager Cast-enheden på dit netværk adressen på sangen og afspiller den
  direkte fra din server. Titler, der kun findes på din telefon (eller hvis server Cast-enheden ikke kan nå), indlæser
  den i stedet fra telefonen: Mens du caster, kører EarLoft en lille webserver på dit Wi-Fi, der kun leverer titlerne
  og coverne fra denne cast-session, via almindelig HTTP, på adresser med en hemmelig nøgle, der skifter ved hver
  session. Andre cast-apps på dit netværk kan se disse adresser. Mens Cast-enheden indlæser fra telefonen, holder
  appen telefonen og dens Wi-Fi vågen.

EarLoft kontakter ingen andre tjenester. Links, du deler, peger på åbne kilder (MusicBrainz, podcastens websted og
feed, Open Library), aldrig på dine egne servere; appen kontakter ikke selv disse websteder, når du deler.

---

## 3. Lagring og sikkerhed

* **Lokal lagring:** Dit bibliotek, dine abonnementer, downloads, lyttefremskridt, statistik og indstillinger gemmes
  på din enhed (og på dit ur for det, du overfører til det).
* **Loginoplysninger:** Serveradgangskoder, API-nøgler og login-tokens gemmes krypteret med Android Keystore. For
  Jellyfin og Emby gemmes kun login-tokenet, ikke adgangskoden.
* **Ukrypterede forbindelser:** Servere og feeds kan nås via almindelig HTTP (for eksempel derhjemme). Appen advarer
  dig, før den bruger en ukrypteret forbindelse til en server.

---

## 4. Backups

* **Android-backup:** Hvis du bruger Androids backup til din Google-konto, indeholder den dine indstillinger og det,
  som ingen server kender: dine servere *uden* deres adgangskoder, abonnementer, lyttefremskridt, lokale playlister,
  lyttestatistik og hvilke afsnit der blev downloadet. Den indeholder aldrig biblioteksdatabasen, downloadede filer,
  adgangskoder, API-nøgler eller login-tokens. Feedadresser bevares uændrede: Hvis en udgiver lægger en personlig
  nøgle ind i adressen på et betalt feed, er denne adresse en del af backuppen.
* **Backupfil:** **Indstillinger → Generelt → Backup** opretter en fil med det samme indhold, som du selv opbevarer,
  igen uden adgangskoder, API-nøgler eller logins, men med feedadresserne (se ovenfor). Hvor du gemmer eller deler
  denne fil, bestemmer du selv.

---

## 5. Wear OS, Android Auto og andre apps

* **Ur:** Telefonen og uret udveksler data via Wear OS-datalaget (Google Play-tjenester): dine serverindstillinger
  inklusive de loginoplysninger, uret har brug for, det, du vælger at overføre til uret, afspilningskommandoer og
  lyttefremskridt. Uret opretter derefter selv forbindelse til dine servere og podcastfeeds og indlæser bøger fra ARD
  Sounds direkte fra ARD.
* **Android Auto:** Dit bibliotek vises i bilen via Android Auto på din telefon.
* **Mediestyring:** Som alle medieapps deler EarLoft det, der afspilles (titel, kunstner, cover), med systemet til
  notifikationen, låseskærmen, widgetten og forbundne enheder. Andre apps kan kun styre afspilningen; de kan ikke
  gennemse dit bibliotek eller ændre din kø.

---

## 6. Tilladelser

Alle tilladelser anmodes der først om, når der er brug for dem, og appen fungerer med det, du tillader:

* **Musik og lyd** (`READ_MEDIA_AUDIO`, på ældre Android-versioner adgang til lagerplads): kun når du slår musik,
  podcasts eller bøger på denne enhed til, for at læse dine lydfiler.
* **Notifikationer** (`POST_NOTIFICATIONS`): for nyt indhold og downloadstatus.
* **Enheder på dit lokale netværk** (`ACCESS_LOCAL_NETWORK`, Android 17 og nyere): for at nå servere på dit
  hjemmenetværk.
* **Internet, netværksstatus, forgrundstjenester** til afspilning, downloads og overførsler til uret.
* **Holde enheden vågen** (`WAKE_LOCK`): så afspilning, downloads og casting fortsætter, når skærmen er slukket.
* **Vibration** (`VIBRATE`): kort feedback, f.eks. når sleep-timeren forlænges ved at ryste.
* På uret desuden **ændre netværksstatus** (`CHANGE_NETWORK_STATE`): for at anmode om Wi-Fi til downloads.
* **Start efter genstart** (`RECEIVE_BOOT_COMPLETED`): så planlagte synkroniseringer og downloads fortsætter, når
  enheden genstarter.

Sleep-timeren for bøger og afsnit kan forlænges ved at ryste telefonen; til dette læses bevægelsessensoren
(accelerometeret) kun i timerens sidste minut og op til to minutter efter, at den er udløbet. Dens værdier forlader
aldrig enheden. Du kan slå dette fra i indstillingerne.

EarLoft beder ikke om adgang til placering, mikrofon, kamera, kontakter, telefon eller Bluetooth.

---

## 7. Åben kildekode

EarLoft er software med åben kildekode. Du kan gennemgå hele kildekoden i det offentlige repository:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt og henvendelser

Hvis du har spørgsmål, fejlrapporter eller bekymringer vedrørende denne privatlivspolitik, kan du oprette et issue i
det offentlige repository:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
