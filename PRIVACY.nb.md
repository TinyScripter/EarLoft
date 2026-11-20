# Personvernerklæring for EarLoft

**Sist oppdatert:** 20. november 2026  
**App-ID:** de.kleinabersmart.earloft (telefon og Wear OS-klokke)

## Oversikt

EarLoft er en spiller med åpen kildekode for din egen samling: musikk fra Navidrome-, Subsonic-, Jellyfin- eller
Emby-serveren din, podkaster fra RSS- og Atom-feeder, lydbøker fra Audiobookshelf-serveren din eller fra ARD Sounds,
og lydfiler på enheten din.
Appen er bygd med personvern som et grunnleggende prinsipp. Utvikleren sporer deg ikke, samler ikke inn
personopplysninger og driver ingen egne servere. Du trenger ikke å opprette noen konto.

---

## 1. Datainnsamling og sporing

* **Utvikleren samler ikke inn personopplysninger:** EarLoft sender ikke navnet ditt, posisjonen din, kontaktene dine,
  enhetsidentifikatorer, biblioteket ditt eller lyttehistorikken din til utvikleren eller til noen andre.
* **Ingen analyse, reklame eller sporere:** Appen inneholder ingen analysebiblioteker, krasjrapporteringstjenester,
  sporings-SDK-er eller reklamenettverk.

---

## 2. Nettverksaktivitet

EarLoft kobler seg bare til internett for funksjonene du bruker. Hver tilkobling går direkte fra enheten din (eller
klokken) til den aktuelle tjenesten:

* **Dine egne servere:** Navidrome-/Subsonic-, Jellyfin-, Emby- og Audiobookshelf-servere du legger til. Appen
  synkroniserer biblioteket ditt, strømmer og laster ned filer og sender tilbake det du gjør i appen: favoritter,
  spillelister, antall avspillinger og lyttefremdrift. Hvis du lar rapportering av avspillinger være slått på
  (**Innstillinger → Musikk**), rapporteres sanger du har spilt av, til musikkserveren din, som kan videresende dem til
  tjenester du har konfigurert der (for eksempel Last.fm eller ListenBrainz).
* **Podkastfeeder:** feedene du abonnerer på, og nettstedene som er vert for episodene og bildene deres, drevet av de
  respektive utgiverne.
* **Podkastkataloger (valgfritt, av som standard):** Når du slår på en katalog (i **Innstillinger → Podkaster** eller
  med tannhjulet under **Legg til podkast**), blir den spurt om populære podkaster så snart du åpner **Legg til
  podkast** (landet og språket ditt sendes), og om søkeordene dine når du søker: Apple Podcasts (Apples søke-API),
  Podcast Index (API-et til podcastindex.org), fyyd (API-et til fyyd.de; bare søkeordene dine, den blir ikke spurt om
  populære podkaster) og ARD Sounds (det offentlige grensesnittet til ARD). Programmer fra ARD Sounds uten egen feed
  oppdateres også via dette grensesnittet.
* **Lydbøker fra ARD Sounds (valgfritt, av som standard):** Når du slår dem på i **Innstillinger → Bøker**, kommer
  lydbøkene og hørespillene du blar i, søker etter, spiller av eller laster ned, fra det offentlige grensesnittet til
  ARD. Bøkene i biblioteket ditt og programmene du følger, sjekkes der jevnlig, også i bakgrunnen (omtrent hver
  12. time, via Wi-Fi), for nye deler, nye bøker og utløpsdatoen deres. Søkeordene dine og disse bøkene sendes til ARD.
* **Bilder fra internett (valgfritt, av som standard):** artistbilder fra Deezer (artistnavn sendes), manglende
  albumomslag fra MusicBrainz og Cover Art Archive (albumtittel og artist sendes), manglende forfatterbilder fra
  Audnexus (forfatternavn sendes).
* **Sangtekster fra LRCLIB (valgfritt, av som standard):** Når du slår på **Innstillinger → Musikk → Søk etter
  sangtekster på nettet**, sendes tittelen, artisten og lengden på sangen som vises, til LRCLIB (lrclib.net) for å
  finne manglende eller synkroniserte sangtekster.
* **OpenID-pålogging for Audiobookshelf (valgfritt):** Påloggingssiden til identitetsleverandøren for serveren din
  åpnes i nettleseren din. EarLoft mottar bare tokenene fra Audiobookshelf-serveren din.
* **Chromecast (valgfritt):** Når du caster, mottar Cast-enheten på nettverket ditt adressen til sangen og spiller
  den av direkte fra serveren din. Spor som bare finnes på telefonen din (eller der Cast-enheten ikke når serveren),
  laster den i stedet fra telefonen: Mens du caster, kjører EarLoft en liten nettserver på Wi-Fi-nettverket ditt som
  bare leverer sporene og omslagene i denne cast-økten, via vanlig HTTP, på adresser med en hemmelighet som endres for
  hver økt. Andre cast-apper på nettverket ditt kan se disse adressene. Mens Cast-enheten laster fra telefonen, holder
  appen telefonen og Wi-Fi-tilkoblingen våken.

EarLoft kontakter ingen andre tjenester. Lenker du deler, peker til åpne kilder (MusicBrainz, nettstedet og feeden til
podkasten, Open Library), aldri til dine egne servere. Appen kontakter ikke selv disse nettstedene når du deler.

---

## 3. Lagring og sikkerhet

* **Lokal lagring:** Biblioteket, abonnementene, nedlastingene, lyttefremdriften, statistikken og innstillingene dine
  lagres på enheten din (og på klokken, for det du laster inn på den).
* **Påloggingsinformasjon:** Serverpassord, API-nøkler og påloggingstokener lagres kryptert med Android Keystore. For
  Jellyfin og Emby beholdes bare påloggingstokenet, ikke passordet.
* **Ukrypterte tilkoblinger:** Servere og feeder kan nås via vanlig HTTP (for eksempel hjemme). Appen advarer deg før
  den bruker en ukryptert tilkobling til en server.

---

## 4. Sikkerhetskopier

* **Android-sikkerhetskopi:** Hvis du bruker Androids sikkerhetskopiering til Google-kontoen din, inneholder den
  innstillingene dine og det ingen server vet: serverne dine *uten* passordene, abonnementer, lyttefremdrift, lokale
  spillelister, lyttestatistikk og hvilke episoder som ble lastet ned. Den inneholder aldri bibliotekdatabasen,
  nedlastede filer, passord, API-nøkler eller påloggingstokener. Feedadresser beholdes som de er: Hvis en utgiver
  legger en personlig nøkkel inn i adressen til en betalt feed, er den adressen en del av sikkerhetskopien.
* **Sikkerhetskopifil:** **Innstillinger → Generelt → Sikkerhetskopi** lager en fil med det samme innholdet som du
  oppbevarer selv, også her uten passord, API-nøkler eller pålogginger, men med feedadressene (se ovenfor). Hvor du
  lagrer eller deler denne filen, bestemmer du selv.

---

## 5. Wear OS, Android Auto og andre apper

* **Klokke:** Telefonen og klokken utveksler data via datalaget i Wear OS (Google Play-tjenester):
  serverinnstillingene dine inkludert påloggingsinformasjonen klokken trenger, det du velger å laste inn på klokken,
  avspillingskommandoer og lyttefremdrift. Klokken kobler seg deretter selv til serverne og podkastfeedene dine, og
  laster bøker fra ARD Sounds direkte fra ARD.
* **Android Auto:** Biblioteket ditt vises i bilen via Android Auto på telefonen din.
* **Mediekontroller:** Som alle medieapper deler EarLoft det som spilles (tittel, artist, omslag), med systemet for
  varselet, låseskjermen, modulen og tilkoblede enheter. Andre apper kan bare styre avspillingen; de kan ikke bla
  gjennom biblioteket ditt eller endre køen din.

---

## 6. Tillatelser

Alle tillatelser spørres om bare når de trengs, og appen fungerer med det du tillater:

* **Musikk og lyd** (`READ_MEDIA_AUDIO`, på eldre Android-versjoner lagringstilgang): bare når du slår på musikk,
  podkaster eller bøker på denne enheten, for å lese lydfilene dine.
* **Varsler** (`POST_NOTIFICATIONS`): for nytt innhold og fremdrift for nedlastinger.
* **Enheter på det lokale nettverket** (`ACCESS_LOCAL_NETWORK`, Android 17 og nyere): for å nå servere på
  hjemmenettverket ditt.
* **Internett, nettverksstatus, forgrunnstjenester** for avspilling, nedlastinger og overføringer til klokken.
* **Holde enheten våken** (`WAKE_LOCK`): slik at avspilling, nedlastinger og casting fortsetter når skjermen er av.
* **Vibrering** (`VIBRATE`): kort tilbakemelding, f.eks. når søvntimeren forlenges ved å riste.
* På klokken i tillegg **endre nettverksstatusen** (`CHANGE_NETWORK_STATE`): for å be om Wi-Fi for nedlastinger.
* **Starte etter omstart** (`RECEIVE_BOOT_COMPLETED`): slik at planlagte synkroniseringer og nedlastinger fortsetter
  etter at enheten er startet på nytt.

Søvntimeren for bøker og episoder kan forlenges ved å riste telefonen. For dette leses bevegelsessensoren
(akselerometeret) bare i timerens siste minutt og opptil to minutter etter at den har gått ut. Verdiene forlater
aldri enheten. Du kan slå av dette i innstillingene.

EarLoft ber ikke om tilgang til posisjon, mikrofon, kamera, kontakter, telefon eller Bluetooth.

---

## 7. Åpen kildekode

EarLoft er programvare med åpen kildekode. Du kan se gjennom hele kildekoden i det offentlige repositoriet:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt og henvendelser

Hvis du har spørsmål, feilrapporter eller bekymringer angående denne personvernerklæringen, kan du åpne en sak i det
offentlige repositoriet:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
