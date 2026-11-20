# Privacybeleid van EarLoft

**Laatst bijgewerkt:** 20 november 2026  
**App-ID:** de.kleinabersmart.earloft (telefoon en Wear OS-horloge)

## Overzicht

EarLoft is een opensource-speler voor je eigen collectie: muziek van je Navidrome-, Subsonic-, Jellyfin- of
Emby-server, podcasts uit RSS- en Atom-feeds, luisterboeken van je Audiobookshelf-server of van ARD Sounds, en
audiobestanden op je apparaat.
Privacy is een van de basisprincipes van de app. De ontwikkelaar volgt je niet, verzamelt geen persoonsgegevens en
heeft geen eigen server. Je hoeft geen account aan te maken.

---

## 1. Gegevensverzameling en tracking

* **Geen persoonsgegevens verzameld door de ontwikkelaar:** EarLoft stuurt je naam, locatie, contacten,
  apparaat-ID’s, bibliotheek en luistergeschiedenis niet naar de ontwikkelaar of naar wie dan ook.
* **Geen analyse, advertenties of trackers:** de app bevat geen analysebibliotheken, crashrapportagediensten,
  tracking-SDK’s of advertentienetwerken.

---

## 2. Netwerkactiviteit

EarLoft maakt alleen verbinding met internet voor de functies die je gebruikt. Elke verbinding gaat rechtstreeks van
je apparaat (of horloge) naar de betreffende dienst:

* **Je eigen servers:** Navidrome-/Subsonic-, Jellyfin-, Emby- en Audiobookshelf-servers die je toevoegt. De app
  synchroniseert je bibliotheek, streamt en downloadt bestanden en stuurt terug wat je in de app doet: favorieten,
  playlists, afspeeltellingen en luistervoortgang. Als je scrobbelen aan laat (**Instellingen → Muziek**), worden
  afgespeelde nummers gemeld aan je muziekserver, die ze kan doorsturen naar diensten die je daar hebt ingesteld
  (bijvoorbeeld Last.fm of ListenBrainz).
* **Podcastfeeds:** de feeds waarop je bent geabonneerd en de sites die hun afleveringen en afbeeldingen hosten,
  beheerd door de betreffende uitgevers.
* **Podcastgidsen (optioneel, standaard uit):** als je een gids inschakelt (in **Instellingen → Podcasts** of met het
  tandwiel bij **Podcast toevoegen**), wordt die om populaire podcasts gevraagd zodra je **Podcast toevoegen** opent
  (je land en taal worden meegestuurd), en om je zoektermen als je zoekt: Apple Podcasts (de zoek-API van Apple),
  Podcast Index (de API van podcastindex.org), fyyd (de API van fyyd.de; alleen je zoektermen, er worden geen
  populaire podcasts opgevraagd) en ARD Sounds (de openbare interface van de ARD). Programma’s van ARD Sounds zonder
  eigen feed worden ook via die interface vernieuwd.
* **Luisterboeken van ARD Sounds (optioneel, standaard uit):** als je ze inschakelt in **Instellingen → Boeken**,
  komen de boeken en hoorspelen die je bekijkt, zoekt, afspeelt of downloadt van de openbare interface van de ARD.
  De boeken in je bibliotheek en de programma’s die je volgt, worden daar regelmatig gecontroleerd, ook op de
  achtergrond (ongeveer elke 12 uur, via wifi), op nieuwe delen, nieuwe boeken en hun vervaldatum. Je zoektermen en
  deze boeken worden naar de ARD gestuurd.
* **Afbeeldingen van internet (optioneel, standaard uit):** artiestfoto’s van Deezer (artiestnamen worden
  meegestuurd), ontbrekende albumcovers van MusicBrainz en het Cover Art Archive (albumtitel en artiest worden
  meegestuurd), ontbrekende auteursafbeeldingen van Audnexus (auteursnamen worden meegestuurd).
* **Songteksten van LRCLIB (optioneel, standaard uit):** als je **Instellingen → Muziek → Songteksten online zoeken**
  inschakelt, worden de titel, de artiest en de lengte van het getoonde nummer naar LRCLIB (lrclib.net) gestuurd om
  ontbrekende of gesynchroniseerde songteksten te vinden.
* **Inloggen met OpenID voor Audiobookshelf (optioneel):** de inlogpagina van de identiteitsprovider van je server
  opent in je browser. EarLoft ontvangt alleen de tokens van je Audiobookshelf-server.
* **Chromecast (optioneel):** als je cast, ontvangt het Cast-apparaat in je netwerk het adres van het nummer en speelt
  het dat rechtstreeks af vanaf je server. Titels die alleen op je telefoon staan (of waarvan het Cast-apparaat de
  server niet kan bereiken), laadt het in plaats daarvan van de telefoon: terwijl je cast, draait EarLoft een kleine
  webserver in je wifi-netwerk die alleen de titels en covers van deze castsessie aanbiedt, via gewoon HTTP, op
  adressen met een geheime code die bij elke sessie verandert. Andere cast-apps in je netwerk kunnen deze adressen
  zien. Zolang het Cast-apparaat van de telefoon laadt, houdt de app de telefoon en de wifi-verbinding actief.

EarLoft neemt geen contact op met andere diensten. Links die je deelt, verwijzen naar open bronnen (MusicBrainz, de
website en feed van de podcast, Open Library), nooit naar je eigen servers; de app zelf neemt geen contact op met
deze sites als je iets deelt.

---

## 3. Opslag en beveiliging

* **Lokale opslag:** je bibliotheek, abonnementen, downloads, luistervoortgang, statistieken en instellingen worden
  op je apparaat opgeslagen (en op je horloge, voor wat je erop zet).
* **Inloggegevens:** serverwachtwoorden, API-sleutels en aanmeldtokens worden versleuteld opgeslagen met de Android
  Keystore. Voor Jellyfin en Emby wordt alleen het aanmeldtoken bewaard, niet het wachtwoord.
* **Onversleutelde verbindingen:** servers en feeds kunnen via gewoon HTTP worden bereikt (bijvoorbeeld thuis). De
  app waarschuwt je voordat een onversleutelde verbinding met een server wordt gebruikt.

---

## 4. Back-ups

* **Android-back-up:** als je de back-up van Android naar je Google-account gebruikt, bevat die je instellingen en
  wat geen enkele server weet: je servers *zonder* wachtwoorden, abonnementen, luistervoortgang, lokale playlists,
  luisterstatistieken en welke afleveringen zijn gedownload. De back-up bevat nooit de bibliotheekdatabase,
  gedownloade bestanden, wachtwoorden, API-sleutels of aanmeldtokens. Feedadressen blijven ongewijzigd: als een
  uitgever een persoonlijke sleutel in het adres van een betaalde feed zet, zit dat adres in de back-up.
* **Back-upbestand:** **Instellingen → Algemeen → Back-up** maakt een bestand met dezelfde inhoud dat je zelf bewaart,
  ook zonder wachtwoorden, API-sleutels of aanmeldingen, maar met de feedadressen (zie hierboven). Waar je dit bestand
  opslaat of met wie je het deelt, bepaal je zelf.

---

## 5. Wear OS, Android Auto en andere apps

* **Horloge:** de telefoon en het horloge wisselen gegevens uit via de Wear OS-gegevenslaag (Google Play-services):
  je serverinstellingen inclusief de inloggegevens die het horloge nodig heeft, wat je op het horloge wilt zetten,
  afspeelopdrachten en luistervoortgang. Het horloge maakt daarna zelf verbinding met je servers en podcastfeeds en
  laadt boeken van ARD Sounds rechtstreeks van de ARD.
* **Android Auto:** je bibliotheek wordt in de auto getoond via Android Auto op je telefoon.
* **Mediabediening:** zoals elke media-app deelt EarLoft wat er wordt afgespeeld (titel, artiest, cover) met het
  systeem voor de melding, het vergrendelscherm, de widget en verbonden apparaten. Andere apps kunnen alleen het
  afspelen bedienen; ze kunnen niet door je bibliotheek bladeren en je wachtrij niet wijzigen.

---

## 6. Rechten

Alle rechten worden pas gevraagd als ze nodig zijn, en de app werkt met wat je toestaat:

* **Muziek en audio** (`READ_MEDIA_AUDIO`, op oudere Android-versies toegang tot opslag): alleen als je muziek,
  podcasts of boeken op dit apparaat inschakelt, om je audiobestanden te lezen.
* **Meldingen** (`POST_NOTIFICATIONS`): voor nieuwe content en de voortgang van downloads.
* **Apparaten in je lokale netwerk** (`ACCESS_LOCAL_NETWORK`, Android 17 en hoger): om servers in je thuisnetwerk te
  bereiken.
* **Internet, netwerkstatus, voorgrondservices** voor afspelen, downloads en overdrachten naar het horloge.
* **Apparaat actief houden** (`WAKE_LOCK`): zodat afspelen, downloads en casten doorgaan als het scherm uit is.
* **Trillen** (`VIBRATE`): korte feedback, bijv. als de slaaptimer wordt verlengd door te schudden.
* Op het horloge daarnaast **netwerkstatus wijzigen** (`CHANGE_NETWORK_STATE`): om wifi aan te vragen voor downloads.
* **Starten na opnieuw opstarten** (`RECEIVE_BOOT_COMPLETED`): zodat geplande synchronisaties en downloads doorgaan
  nadat het apparaat opnieuw is opgestart.

De slaaptimer van boeken en afleveringen kan worden verlengd door de telefoon te schudden; daarvoor wordt de
bewegingssensor (versnellingsmeter) alleen gelezen tijdens de laatste minuut van de timer en tot twee minuten nadat
die is afgelopen. De waarden verlaten het apparaat nooit. Je kunt dit uitzetten in de instellingen.

EarLoft vraagt geen toegang tot locatie, microfoon, camera, contacten, telefoon of Bluetooth.

---

## 7. Open source

EarLoft is opensource-software. Je kunt de volledige broncode bekijken in de openbare repository:
https://github.com/TinyScripter/EarLoft

---

## 8. Contact en vragen

Heb je vragen, bugmeldingen of zorgen over dit privacybeleid, open dan een issue in de openbare repository:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
