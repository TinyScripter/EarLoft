# EarLoftin tietosuojakäytäntö

**Päivitetty viimeksi:** 20.11.2026  
**Sovellustunnus:** de.kleinabersmart.earloft (puhelin ja Wear OS -kello)

## Yleiskatsaus

EarLoft on avoimen lähdekoodin soitin omalle kokoelmallesi: musiikkia Navidrome-, Subsonic-, Jellyfin- tai
Emby-palvelimeltasi, podcasteja RSS- ja Atom-syötteistä, äänikirjoja Audiobookshelf-palvelimeltasi tai ARD Soundsista
sekä laitteesi äänitiedostoja.
Se on rakennettu yksityisyys perusperiaatteenaan. Kehittäjä ei seuraa sinua, ei kerää henkilötietoja eikä ylläpidä
omaa palvelinta. Tiliä ei tarvitse luoda.

---

## 1. Tietojen kerääminen ja seuranta

* **Kehittäjä ei kerää henkilötietoja:** EarLoft ei lähetä nimeäsi, sijaintiasi, yhteystietojasi, laitetunnisteita,
  kirjastoasi tai kuunteluhistoriaasi kehittäjälle eikä kenellekään muulle.
* **Ei analytiikkaa, mainoksia tai seurantaa:** Sovelluksessa ei ole analytiikkakirjastoja, kaatumisraportointipalveluja,
  seuranta-SDK:ita eikä mainosverkostoja.

---

## 2. Verkkoliikenne

EarLoft muodostaa internetyhteyden vain käyttämiäsi ominaisuuksia varten. Jokainen yhteys kulkee suoraan laitteeltasi
(tai kellostasi) kyseiseen palveluun:

* **Omat palvelimesi:** lisäämäsi Navidrome- / Subsonic-, Jellyfin-, Emby- ja Audiobookshelf-palvelimet. Sovellus
  synkronoi kirjastosi, suoratoistaa ja lataa tiedostoja ja lähettää takaisin sen, mitä teet sovelluksessa: suosikit,
  soittolistat, toistokerrat ja kuuntelun edistymisen. Jos jätät kuuntelujen ilmoittamisen päälle (**Asetukset →
  Musiikki**), soitetut kappaleet ilmoitetaan musiikkipalvelimellesi, joka voi välittää ne siellä määrittämillesi
  palveluille (esimerkiksi Last.fm tai ListenBrainz).
* **Podcast-syötteet:** tilaamasi syötteet sekä sivustot, joilla niiden jaksoja ja kuvia säilytetään ja joita
  kyseiset julkaisijat ylläpitävät.
* **Podcast-hakemistot (valinnainen, oletuksena pois päältä):** kun otat hakemiston käyttöön (kohdassa **Asetukset →
  Podcastit** tai rataskuvakkeella näkymässä **Lisää podcast**), siltä kysytään suosittuja podcasteja heti, kun avaat
  näkymän **Lisää podcast** (maasi ja kielesi lähetetään), ja hakusanojasi, kun haet: Apple Podcasts (Applen haku-API),
  Podcast Index (podcastindex.orgin API), fyyd (fyyd.de:n API; vain hakusanasi, siltä ei kysytä suosittuja podcasteja)
  ja ARD Sounds (ARD:n julkinen rajapinta). ARD Soundsin ohjelmat, joilla ei ole omaa syötettä, päivitetään myös tämän
  rajapinnan kautta.
* **Äänikirjat ARD Soundsista (valinnainen, oletuksena pois päältä):** kun otat ne käyttöön kohdassa **Asetukset →
  Kirjat**, selaamasi, hakemasi, toistamasi tai lataamasi kirjat ja kuunnelmat tulevat ARD:n julkisesta rajapinnasta.
  Kirjastosi kirjat ja seuraamasi ohjelmat tarkistetaan siellä säännöllisesti myös taustalla (noin 12 tunnin välein,
  Wi-Fi-yhteydellä) uusien osien, uusien kirjojen ja saatavuuden päättymispäivän varalta. Hakusanasi ja nämä kirjat
  lähetetään ARD:lle.
* **Kuvat internetistä (valinnainen, oletuksena pois päältä):** artistikuvat Deezeristä (artistien nimet lähetetään),
  puuttuvat albumien kansikuvat MusicBrainzista ja Cover Art Archivesta (albumin nimi ja artisti lähetetään), puuttuvat
  kirjailijakuvat Audnexuksesta (kirjailijoiden nimet lähetetään).
* **Laulujen sanat LRCLIBistä (valinnainen, oletuksena pois päältä):** kun otat käyttöön asetuksen **Asetukset →
  Musiikki → Hae sanat verkosta**, näytettävän kappaleen nimi, artisti ja pituus lähetetään LRCLIBiin (lrclib.net)
  puuttuvien tai synkronoitujen sanojen löytämiseksi.
* **OpenID-kirjautuminen Audiobookshelfiin (valinnainen):** palvelimesi tunnistautumispalvelun kirjautumissivu
  avautuu selaimessasi. EarLoft saa tunnisteet vain Audiobookshelf-palvelimeltasi.
* **Chromecast (valinnainen):** kun toistat Cast-laitteella, verkkosi Cast-laite saa kappaleen osoitteen ja toistaa
  sen suoraan palvelimeltasi. Kappaleet, jotka ovat vain puhelimessasi (tai joiden palvelimeen Cast-laite ei saa
  yhteyttä), se lataa sen sijaan puhelimesta: Cast-toiston aikana EarLoft käyttää Wi-Fi-verkossasi pientä
  verkkopalvelinta, joka tarjoaa vain tämän Cast-istunnon kappaleet ja kansikuvat salaamattoman HTTP-yhteyden kautta
  osoitteissa, joissa on jokaisessa istunnossa vaihtuva salainen osa. Verkkosi muut Cast-sovellukset voivat nähdä nämä
  osoitteet. Kun Cast-laite lataa puhelimesta, sovellus pitää puhelimen ja sen Wi-Fi-yhteyden hereillä.

EarLoft ei ota yhteyttä muihin palveluihin. Jakamasi linkit osoittavat avoimiin lähteisiin (MusicBrainz, podcastin
verkkosivusto ja syöte, Open Library), eivät koskaan omille palvelimillesi; sovellus itse ei ota yhteyttä näihin
sivustoihin jakaessasi.

---

## 3. Tallennus ja tietoturva

* **Paikallinen tallennus:** kirjastosi, tilauksesi, latauksesi, kuuntelun edistyminen, tilastot ja asetukset
  tallennetaan laitteellesi (ja kelloosi siltä osin kuin siirrät sinne sisältöä).
* **Kirjautumistiedot:** palvelinten salasanat, API-avaimet ja kirjautumistunnisteet tallennetaan Android Keystoren
  avulla salattuina. Jellyfinin ja Embyn kohdalla säilytetään vain kirjautumistunniste, ei salasanaa.
* **Salaamattomat yhteydet:** palvelimia ja syötteitä voi käyttää salaamattoman HTTP-yhteyden kautta (esimerkiksi
  kotona). Sovellus varoittaa sinua ennen kuin palvelimeen käytetään salaamatonta yhteyttä.

---

## 4. Varmuuskopiot

* **Androidin varmuuskopio:** jos käytät Androidin varmuuskopiointia Google-tiliisi, se sisältää asetuksesi ja sen,
  mitä mikään palvelin ei tiedä: palvelimesi *ilman* salasanoja, tilaukset, kuuntelun edistymisen, paikalliset
  soittolistat, kuuntelutilastot ja tiedon siitä, mitkä jaksot on ladattu. Se ei koskaan sisällä kirjaston
  tietokantaa, ladattuja tiedostoja, salasanoja, API-avaimia tai kirjautumistunnisteita. Syötteiden osoitteet
  säilytetään sellaisinaan: jos julkaisija lisää maksullisen syötteen osoitteeseen henkilökohtaisen avaimen, osoite
  sisältyy varmuuskopioon.
* **Varmuuskopiotiedosto:** **Asetukset → Yleiset → Varmuuskopio** luo tiedoston, jossa on sama sisältö ja jota
  säilytät itse – jälleen ilman salasanoja, API-avaimia tai kirjautumisia, mutta syötteiden osoitteineen (katso
  yllä). Sinä päätät, mihin tallennat tiedoston tai kenen kanssa jaat sen.

---

## 5. Wear OS, Android Auto ja muut sovellukset

* **Kello:** puhelin ja kello vaihtavat tietoja Wear OS:n datakerroksen (Google Play -palvelut) kautta: palvelinten
  asetukset kellon tarvitsemine kirjautumistietoineen, kelloon siirrettäväksi valitsemasi sisältö, toistokomennot ja
  kuuntelun edistyminen. Kello muodostaa sitten itse yhteyden palvelimiisi ja podcast-syötteisiin ja lataa ARD Soundsin
  kirjat suoraan ARD:ltä.
* **Android Auto:** kirjastosi näytetään autossa puhelimesi Android Auton kautta.
* **Mediasäätimet:** kuten kaikki mediasovellukset, EarLoft jakaa toistettavan sisällön tiedot (nimi, artisti,
  kansikuva) järjestelmän kanssa ilmoitusta, lukitusnäyttöä, widgetiä ja yhdistettyjä laitteita varten. Muut
  sovellukset voivat vain ohjata toistoa; ne eivät voi selata kirjastoasi tai muuttaa jonoasi.

---

## 6. Luvat

Kaikkia lupia pyydetään vasta tarvittaessa, ja sovellus toimii sillä, minkä sallit:

* **Musiikki ja ääni** (`READ_MEDIA_AUDIO`, vanhemmissa Android-versioissa tallennustilan käyttö): vain kun otat
  käyttöön tämän laitteen musiikin, podcastit tai kirjat, äänitiedostojesi lukemiseksi.
* **Ilmoitukset** (`POST_NOTIFICATIONS`): uutta sisältöä ja latausten edistymistä varten.
* **Lähiverkon laitteet** (`ACCESS_LOCAL_NETWORK`, Android 17 ja uudemmat): kotiverkkosi palvelimien tavoittamiseksi.
* **Internet, verkon tila, etualan palvelut** toistoa, latauksia ja kelloon siirtoa varten.
* **Laitteen pitäminen hereillä** (`WAKE_LOCK`): jotta toisto, lataukset ja Cast-toisto jatkuvat näytön ollessa pois
  päältä.
* **Värinä** (`VIBRATE`): lyhyt palaute, esim. kun uniajastinta jatketaan ravistamalla.
* Kellossa lisäksi **verkon tilan muuttaminen** (`CHANGE_NETWORK_STATE`): Wi-Fi-yhteyden pyytämiseksi latauksia varten.
* **Käynnistys uudelleenkäynnistyksen jälkeen** (`RECEIVE_BOOT_COMPLETED`): jotta ajoitetut synkronoinnit ja lataukset
  jatkuvat laitteen uudelleenkäynnistyksen jälkeen.

Kirjojen ja jaksojen uniajastinta voi jatkaa ravistamalla puhelinta; tätä varten liikeanturia (kiihtyvyysanturi)
luetaan vain ajastimen viimeisen minuutin aikana ja enintään kaksi minuuttia sen päättymisen jälkeen. Sen arvot eivät
koskaan poistu laitteelta. Voit poistaa tämän käytöstä asetuksissa.

EarLoft ei pyydä pääsyä sijaintiin, mikrofoniin, kameraan, yhteystietoihin, puhelimeen tai Bluetoothiin.

---

## 7. Avoin lähdekoodi

EarLoft on avoimen lähdekoodin ohjelmisto. Voit tarkastella koko lähdekoodia julkisessa tietovarastossa:
https://github.com/TinyScripter/EarLoft

---

## 8. Yhteydenotot ja kysymykset

Jos sinulla on kysymyksiä, virheraportteja tai huolia tähän tietosuojakäytäntöön liittyen, avaa issue julkisessa
tietovarastossa:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
