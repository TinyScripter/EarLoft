# Zásady ochrany soukromí aplikace EarLoft

**Poslední aktualizace:** 20. listopadu 2026  
**ID aplikace:** de.kleinabersmart.earloft (telefon a hodinky s Wear OS)

## Přehled

EarLoft je open-source přehrávač pro vaši vlastní sbírku: hudba z vašeho serveru Navidrome, Subsonic, Jellyfin nebo
Emby, podcasty z kanálů RSS a Atom, audioknihy z vašeho serveru Audiobookshelf nebo z ARD Sounds a zvukové soubory
ve vašem zařízení.
Ochrana soukromí je jedním z jeho základních principů. Vývojář vás nesleduje, neshromažďuje osobní údaje
a neprovozuje žádný vlastní server. Není potřeba zakládat žádný účet.

---

## 1. Shromažďování údajů a sledování

* **Vývojář neshromažďuje žádné osobní údaje:** EarLoft neodesílá vaše jméno, polohu, kontakty, identifikátory
  zařízení, knihovnu ani historii poslechu vývojáři ani nikomu jinému.
* **Žádná analytika, reklamy ani sledovací nástroje:** Aplikace neobsahuje žádné analytické knihovny, služby pro
  hlášení pádů, sledovací SDK ani reklamní sítě.

---

## 2. Síťová aktivita

EarLoft se připojuje k internetu jen kvůli funkcím, které používáte. Každé spojení vede přímo z vašeho zařízení (nebo
hodinek) k příslušné službě:

* **Vaše vlastní servery:** servery Navidrome / Subsonic, Jellyfin, Emby a Audiobookshelf, které přidáte. Aplikace
  synchronizuje vaši knihovnu, streamuje a stahuje soubory a odesílá zpět, co v aplikaci děláte: oblíbené položky,
  playlisty, počty přehrání a průběh poslechu. Pokud ponecháte zapnuté hlášení přehrání (**Nastavení → Hudba**),
  přehrané skladby se hlásí vašemu hudebnímu serveru, který je může předávat službám, jež jste na něm nastavili
  (například Last.fm nebo ListenBrainz).
* **Kanály podcastů:** kanály, které odebíráte, a weby, na kterých jsou uloženy jejich epizody a obrázky,
  provozované příslušnými vydavateli.
* **Katalogy podcastů (volitelné, ve výchozím stavu vypnuté):** když katalog zapnete (v **Nastavení → Podcasty** nebo
  ozubeným kolem u **Přidat podcast**), dotazuje se aplikace na populární podcasty, jakmile otevřete **Přidat podcast**
  (odesílá se vaše země a jazyk), a při hledání na vaše hledané výrazy: Apple Podcasts (vyhledávací API společnosti
  Apple), Podcast Index (API webu podcastindex.org), fyyd (API webu fyyd.de; jen vaše hledané výrazy, na populární
  podcasty se nedotazuje) a ARD Sounds (veřejné rozhraní ARD). Pořady z ARD Sounds bez vlastního kanálu se přes toto
  rozhraní také aktualizují.
* **Audioknihy z ARD Sounds (volitelné, ve výchozím stavu vypnuté):** když je zapnete v **Nastavení → Knihy**,
  knihy a rozhlasové hry, které procházíte, hledáte, přehráváte nebo stahujete, pocházejí z veřejného rozhraní ARD.
  Knihy ve vaší knihovně a pořady, které sledujete, se tam pravidelně kontrolují, i na pozadí (přibližně každých
  12 hodin, přes Wi-Fi) kvůli novým částem, novým knihám a datu konce jejich dostupnosti. Vaše hledané
  výrazy a tyto knihy se odesílají ARD.
* **Obrázky z internetu (volitelné, ve výchozím stavu vypnuté):** fotky interpretů z Deezeru (odesílají se jména
  interpretů), chybějící obaly alb z MusicBrainz a Cover Art Archive (odesílá se název alba a interpret), chybějící
  obrázky autorů z Audnexus (odesílají se jména autorů).
* **Texty skladeb z LRCLIB (volitelné, ve výchozím stavu vypnuté):** když zapnete **Nastavení → Hudba → Hledat texty
  online**, odesílá se název, interpret a délka zobrazené skladby do LRCLIB (lrclib.net), aby se našly chybějící nebo
  synchronizované texty.
* **Přihlášení přes OpenID k Audiobookshelf (volitelné):** ve vašem prohlížeči se otevře přihlašovací stránka
  poskytovatele identity vašeho serveru. EarLoft dostává tokeny jen od vašeho serveru Audiobookshelf.
* **Chromecast (volitelné):** když odesíláte, zařízení Cast ve vaší síti obdrží adresu skladby a přehraje ji přímo
  z vašeho serveru. Skladby, které jsou jen ve vašem telefonu (nebo jejichž server zařízení Cast nedosáhne), načítá
  místo toho z telefonu: během odesílání provozuje EarLoft ve vaší síti Wi-Fi malý webový server, který poskytuje jen
  skladby a obaly této relace odesílání, přes nešifrované HTTP, na adresách s tajným klíčem, který se s každou relací
  mění. Tyto adresy mohou vidět jiné aplikace pro odesílání ve vaší síti. Zatímco zařízení Cast načítá z telefonu,
  aplikace udržuje telefon a jeho Wi-Fi v aktivním stavu.

EarLoft nekontaktuje žádnou jinou službu. Odkazy, které sdílíte, vedou na otevřené zdroje (MusicBrainz, web a kanál
podcastu, Open Library), nikdy na vaše vlastní servery; aplikace sama tyto weby při sdílení nekontaktuje.

---

## 3. Úložiště a zabezpečení

* **Místní úložiště:** vaše knihovna, odběry, stažené soubory, průběh poslechu, statistiky a nastavení se ukládají
  ve vašem zařízení (a ve vašich hodinkách, pokud jde o to, co do nich nahrajete).
* **Přihlašovací údaje:** hesla k serverům, klíče API a přihlašovací tokeny se ukládají zašifrované pomocí
  Android Keystore. U Jellyfin a Emby se uchovává jen přihlašovací token, nikoli heslo.
* **Nešifrovaná spojení:** servery a kanály lze zastihnout i přes nešifrované HTTP (například doma). Aplikace vás
  před použitím nešifrovaného spojení se serverem upozorní.

---

## 4. Zálohy

* **Záloha Androidu:** pokud používáte zálohu Androidu do svého účtu Google, obsahuje vaše nastavení a to, co žádný
  server neví: vaše servery *bez* jejich hesel, odběry, průběh poslechu, místní playlisty, statistiky poslechu a to,
  které epizody byly staženy. Nikdy neobsahuje databázi knihovny, stažené soubory, hesla, klíče API ani přihlašovací
  tokeny. Adresy kanálů zůstávají beze změny: pokud vydavatel vloží do adresy placeného kanálu osobní klíč, je tato
  adresa součástí zálohy.
* **Soubor zálohy:** **Nastavení → Obecné → Záloha** vytvoří soubor se stejným obsahem, který si uchováváte sami,
  opět bez hesel, klíčů API a přihlášení, ale s adresami kanálů (viz výše). Kde tento soubor uložíte nebo s kým ho
  sdílíte, je na vás.

---

## 5. Wear OS, Android Auto a další aplikace

* **Hodinky:** telefon a hodinky si vyměňují data přes datovou vrstvu Wear OS (služby Google Play): nastavení vašich
  serverů včetně přihlašovacích údajů, které hodinky potřebují, to, co se rozhodnete do hodinek nahrát, příkazy
  přehrávání a průběh poslechu. Hodinky se pak samy připojují k vašim serverům a kanálům podcastů a knihy z ARD Sounds
  načítají přímo od ARD.
* **Android Auto:** vaše knihovna se v autě zobrazuje přes Android Auto ve vašem telefonu.
* **Ovládání médií:** jako každá mediální aplikace sdílí EarLoft se systémem, co se přehrává (název, interpret, obal),
  pro oznámení, obrazovku uzamčení, widget a připojená zařízení. Ostatní aplikace mohou jen ovládat přehrávání;
  nemohou procházet vaši knihovnu ani měnit vaši frontu.

---

## 6. Oprávnění

O všechna oprávnění aplikace žádá, až když jsou potřeba, a funguje s tím, co povolíte:

* **Hudba a zvuk** (`READ_MEDIA_AUDIO`, ve starších verzích Androidu přístup k úložišti): jen když zapnete hudbu,
  podcasty nebo knihy v tomto zařízení, aby mohla číst vaše zvukové soubory.
* **Oznámení** (`POST_NOTIFICATIONS`): pro nový obsah a průběh stahování.
* **Zařízení v místní síti** (`ACCESS_LOCAL_NETWORK`, Android 17 a novější): pro přístup k serverům ve vaší domácí
  síti.
* **Internet, stav sítě, služby v popředí** pro přehrávání, stahování a přenosy do hodinek.
* **Udržování zařízení v aktivním stavu** (`WAKE_LOCK`): aby přehrávání, stahování a odesílání pokračovalo i při
  vypnuté obrazovce.
* **Vibrace** (`VIBRATE`): krátká odezva, např. když časovač vypnutí prodloužíte zatřesením.
* V hodinkách navíc **změna stavu sítě** (`CHANGE_NETWORK_STATE`): pro vyžádání Wi-Fi ke stahování.
* **Spuštění po restartu** (`RECEIVE_BOOT_COMPLETED`): aby naplánované synchronizace a stahování pokračovaly i po
  restartu zařízení.

Časovač vypnutí u knih a epizod lze prodloužit zatřesením telefonem; za tímto účelem se pohybový senzor
(akcelerometr) čte jen během poslední minuty časovače a až dvě minuty po jeho uplynutí. Jeho hodnoty nikdy neopustí
zařízení. Tuto funkci můžete v nastavení vypnout.

EarLoft nežádá o přístup k poloze, mikrofonu, fotoaparátu, kontaktům, telefonu ani k Bluetooth.

---

## 7. Open source

EarLoft je open-source software. Kompletní zdrojový kód si můžete prohlédnout ve veřejném repozitáři:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt a dotazy

Máte-li otázky, hlášení chyb nebo připomínky k těmto zásadám ochrany soukromí, založte prosím issue ve veřejném
repozitáři:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
