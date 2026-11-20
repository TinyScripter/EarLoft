# Integritetspolicy för EarLoft

**Senast uppdaterad:** 20 november 2026  
**App-id:** de.kleinabersmart.earloft (telefon och Wear OS-klocka)

## Översikt

EarLoft är en spelare med öppen källkod för din egen samling: musik från din Navidrome-, Subsonic-, Jellyfin- eller
Emby-server, poddar från RSS- och Atom-flöden, ljudböcker från din Audiobookshelf-server eller från ARD Sounds samt
ljudfiler på din enhet.
Den är byggd med integritet som grundprincip. Utvecklaren spårar dig inte, samlar inte in personuppgifter och driver
inga egna servrar. Det finns inget konto att skapa.

---

## 1. Datainsamling och spårning

* **Inga personuppgifter samlas in av utvecklaren:** EarLoft skickar inte ditt namn, din plats, dina kontakter,
  enhets-id:n, ditt bibliotek eller din lyssningshistorik till utvecklaren eller till någon annan.
* **Ingen analys, inga annonser eller spårare:** Appen innehåller inga analysbibliotek, tjänster för kraschrapporter,
  spårnings-SDK:er eller annonsnätverk.

---

## 2. Nätverksaktivitet

EarLoft ansluter till internet bara för de funktioner du använder. Varje anslutning går direkt från din enhet (eller
klocka) till respektive tjänst:

* **Dina egna servrar:** Navidrome-/Subsonic-, Jellyfin-, Emby- och Audiobookshelf-servrar som du lägger till. Appen
  synkroniserar ditt bibliotek, streamar och laddar ned filer och skickar tillbaka det du gör i appen: favoriter,
  spellistor, antal uppspelningar och lyssningsförlopp. Om du låter scrobbling vara på (**Inställningar → Musik**)
  rapporteras spelade låtar till din musikserver, som kan vidarebefordra dem till tjänster som du har konfigurerat där
  (till exempel Last.fm eller ListenBrainz).
* **Poddflöden:** flödena du prenumererar på och webbplatserna som lagrar deras avsnitt och bilder, som drivs av
  respektive utgivare.
* **Poddkataloger (valfritt, av som standard):** när du aktiverar en katalog (i **Inställningar → Poddar** eller via
  kugghjulet vid **Lägg till podd**) tillfrågas den om populära poddar så snart du öppnar **Lägg till podd** (ditt land
  och språk skickas), och om dina sökord när du söker: Apple Podcasts (Apples sök-API), Podcast Index (API:et för
  podcastindex.org), fyyd (API:et för fyyd.de; bara dina sökord, den tillfrågas inte om populära poddar) och ARD Sounds
  (ARD:s offentliga gränssnitt). Program från ARD Sounds utan eget flöde uppdateras också via det gränssnittet.
* **Ljudböcker från ARD Sounds (valfritt, av som standard):** när du aktiverar dem i **Inställningar → Böcker** kommer
  böckerna och hörspelen som du bläddrar bland, söker efter, spelar eller laddar ned från ARD:s offentliga gränssnitt.
  Böckerna i ditt bibliotek och programmen du följer kontrolleras där regelbundet, även i bakgrunden (ungefär var
  12:e timme, via wifi), efter nya delar, nya böcker och deras utgångsdatum. Dina sökord och dessa böcker skickas till
  ARD.
* **Bilder från internet (valfritt, av som standard):** artistfoton från Deezer (artistnamn skickas), saknade
  albumomslag från MusicBrainz och Cover Art Archive (albumtitel och artist skickas), saknade författarbilder från
  Audnexus (författarnamn skickas).
* **Låttexter från LRCLIB (valfritt, av som standard):** när du aktiverar **Inställningar → Musik → Sök efter låttexter
  online** skickas titel, artist och längd för den låt som visas till LRCLIB (lrclib.net) för att hitta saknade eller
  synkade låttexter.
* **OpenID-inloggning för Audiobookshelf (valfritt):** inloggningssidan för din servers identitetsleverantör öppnas i
  din webbläsare. EarLoft tar bara emot token från din Audiobookshelf-server.
* **Chromecast (valfritt):** när du castar tar Cast-enheten i ditt nätverk emot låtens adress och spelar den direkt
  från din server. Spår som bara finns på din telefon (eller vars server Cast-enheten inte kan nå) hämtar den i stället
  från telefonen: medan du castar kör EarLoft en liten webbserver i ditt wifi-nätverk som bara tillhandahåller spåren
  och omslagen för den här cast-sessionen, via okrypterad HTTP, på adresser med en hemlighet som ändras för varje
  session. Andra cast-appar i ditt nätverk kan se dessa adresser. Medan Cast-enheten hämtar från telefonen håller appen
  telefonen och dess wifi aktiva.

EarLoft kontaktar inga andra tjänster. Länkar som du delar pekar till öppna källor (MusicBrainz, poddens webbplats och
flöde, Open Library), aldrig till dina egna servrar; appen kontaktar inte själv dessa webbplatser när du delar.

---

## 3. Lagring och säkerhet

* **Lokal lagring:** ditt bibliotek, dina prenumerationer, nedladdningar, ditt lyssningsförlopp, din statistik och
  dina inställningar lagras på din enhet (och på din klocka, för det du överför dit).
* **Inloggningsuppgifter:** serverlösenord, API-nycklar och inloggningstoken lagras krypterade med Android Keystore.
  För Jellyfin och Emby sparas bara inloggningstoken, inte lösenordet.
* **Okrypterade anslutningar:** servrar och flöden kan nås via okrypterad HTTP (till exempel hemma). Appen varnar dig
  innan en okrypterad anslutning till en server används.

---

## 4. Säkerhetskopior

* **Android-säkerhetskopiering:** om du använder Androids säkerhetskopiering till ditt Google-konto innehåller den dina
  inställningar och det som ingen server känner till: dina servrar *utan* deras lösenord, prenumerationer,
  lyssningsförlopp, lokala spellistor, lyssningsstatistik och vilka avsnitt som har laddats ned. Den innehåller aldrig
  biblioteksdatabasen, nedladdade filer, lösenord, API-nycklar eller inloggningstoken. Flödesadresser behålls som de
  är: om en utgivare lägger in en personlig nyckel i adressen till ett betalflöde ingår den adressen i
  säkerhetskopian.
* **Säkerhetskopia som fil:** **Inställningar → Allmänt → Säkerhetskopiering** skapar en fil med samma innehåll som du
  själv förvarar, även den utan lösenord, API-nycklar eller inloggningar, men med flödesadresserna (se ovan). Var du
  lagrar eller delar filen bestämmer du själv.

---

## 5. Wear OS, Android Auto och andra appar

* **Klocka:** telefonen och klockan utbyter data via Wear OS-datalagret (Google Play-tjänster): dina
  serverinställningar inklusive de inloggningsuppgifter klockan behöver, vad du väljer att överföra till klockan,
  uppspelningskommandon och lyssningsförlopp. Klockan ansluter sedan själv till dina servrar och poddflöden och hämtar
  böcker från ARD Sounds direkt från ARD.
* **Android Auto:** ditt bibliotek visas i bilen via Android Auto på din telefon.
* **Mediekontroller:** som alla medieappar delar EarLoft det som spelas (titel, artist, omslag) med systemet för
  aviseringen, låsskärmen, widgeten och anslutna enheter. Andra appar får bara styra uppspelningen; de kan inte bläddra
  i ditt bibliotek eller ändra din kö.

---

## 6. Behörigheter

Alla behörigheter begärs först när de behövs, och appen fungerar med det du tillåter:

* **Musik och ljud** (`READ_MEDIA_AUDIO`, på äldre Android-versioner åtkomst till lagringsutrymme): bara när du
  aktiverar musik, poddar eller böcker på den här enheten, för att läsa dina ljudfiler.
* **Aviseringar** (`POST_NOTIFICATIONS`): för nytt innehåll och nedladdningsförlopp.
* **Enheter i ditt lokala nätverk** (`ACCESS_LOCAL_NETWORK`, Android 17 och senare): för att nå servrar i ditt
  hemnätverk.
* **Internet, nätverksstatus, förgrundstjänster** för uppspelning, nedladdningar och överföringar till klockan.
* **Hålla enheten vaken** (`WAKE_LOCK`): så att uppspelning, nedladdningar och castning fortsätter med skärmen
  avstängd.
* **Vibration** (`VIBRATE`): kort återkoppling, t.ex. när insomningstimern förlängs genom att skaka.
* På klockan dessutom **ändra nätverksstatus** (`CHANGE_NETWORK_STATE`): för att begära wifi för nedladdningar.
* **Starta efter omstart** (`RECEIVE_BOOT_COMPLETED`): så att schemalagda synkroniseringar och nedladdningar fortsätter
  efter att enheten har startats om.

Insomningstimern för böcker och avsnitt kan förlängas genom att skaka telefonen; för detta läses rörelsesensorn
(accelerometern) bara under timerns sista minut och upp till två minuter efter att den har gått ut. Värdena lämnar
aldrig enheten. Du kan stänga av detta i inställningarna.

EarLoft begär inte åtkomst till plats, mikrofon, kamera, kontakter, telefon eller Bluetooth.

---

## 7. Öppen källkod

EarLoft är programvara med öppen källkod. Du kan granska hela källkoden i det offentliga arkivet:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt och frågor

Om du har frågor, felrapporter eller synpunkter på den här integritetspolicyn kan du öppna ett ärende i det offentliga
arkivet:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
