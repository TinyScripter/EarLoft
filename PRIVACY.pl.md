# Polityka prywatności aplikacji EarLoft

**Ostatnia aktualizacja:** 20 listopada 2026  
**Identyfikator aplikacji:** de.kleinabersmart.earloft (telefon i zegarek z Wear OS)

## Informacje ogólne

EarLoft to odtwarzacz open source Twojej własnej kolekcji: muzyki z Twojego serwera Navidrome, Subsonic, Jellyfin lub
Emby, podcastów z kanałów RSS i Atom, audiobooków z Twojego serwera Audiobookshelf lub z ARD Sounds oraz plików audio
na Twoim urządzeniu.
Ochrona prywatności jest jej podstawową zasadą. Deweloper Cię nie śledzi, nie zbiera danych osobowych i nie prowadzi
żadnego własnego serwera. Nie trzeba zakładać żadnego konta.

---

## 1. Zbieranie danych i śledzenie

* **Deweloper nie zbiera danych osobowych:** EarLoft nie wysyła Twojego imienia i nazwiska, lokalizacji, kontaktów,
  identyfikatorów urządzenia, biblioteki ani historii słuchania deweloperowi ani nikomu innemu.
* **Brak analityki, reklam i elementów śledzących:** aplikacja nie zawiera bibliotek analitycznych, usług raportowania
  awarii, pakietów SDK do śledzenia ani sieci reklamowych.

---

## 2. Aktywność sieciowa

EarLoft łączy się z internetem tylko w ramach funkcji, z których korzystasz. Każde połączenie prowadzi bezpośrednio
z Twojego urządzenia (lub zegarka) do danej usługi:

* **Twoje własne serwery:** dodane przez Ciebie serwery Navidrome / Subsonic, Jellyfin, Emby i Audiobookshelf.
  Aplikacja synchronizuje Twoją bibliotekę, odtwarza strumieniowo i pobiera pliki oraz odsyła to, co robisz
  w aplikacji: ulubione, playlisty, liczbę odtworzeń i postęp słuchania. Jeśli zostawisz włączone zgłaszanie
  odtworzeń (**Ustawienia → Muzyka**), odtworzone utwory są zgłaszane Twojemu serwerowi muzyki, który może przekazywać
  je do skonfigurowanych na nim usług (np. Last.fm lub ListenBrainz).
* **Kanały podcastów:** subskrybowane kanały oraz strony, na których hostowane są ich odcinki i obrazy, prowadzone
  przez danych wydawców.
* **Katalogi podcastów (opcjonalne, domyślnie wyłączone):** gdy włączysz katalog (w **Ustawienia → Podcasty** lub
  kołem zębatym przy **Dodaj podcast**), jest on odpytywany o popularne podcasty, gdy tylko otworzysz **Dodaj podcast**
  (wysyłane są Twój kraj i język), a podczas wyszukiwania – o Twoje wyszukiwane hasła: Apple Podcasts (interfejs
  wyszukiwania Apple), Podcast Index (interfejs API podcastindex.org), fyyd (interfejs API fyyd.de; tylko Twoje
  wyszukiwane hasła, nie jest odpytywany o popularne podcasty) i ARD Sounds (publiczny interfejs ARD). Audycje z ARD
  Sounds bez własnego kanału są również odświeżane przez ten interfejs.
* **Audiobooki z ARD Sounds (opcjonalne, domyślnie wyłączone):** gdy włączysz je w **Ustawienia → Książki**, książki
  i słuchowiska, które przeglądasz, wyszukujesz, odtwarzasz lub pobierasz, pochodzą z publicznego interfejsu ARD.
  Książki z Twojej biblioteki i obserwowane audycje są tam regularnie sprawdzane, także w tle (mniej więcej co
  12 godzin, przez Wi-Fi), pod kątem nowych części, nowych książek i daty wygaśnięcia. Twoje wyszukiwane hasła i te
  książki są wysyłane do ARD.
* **Obrazy z internetu (opcjonalne, domyślnie wyłączone):** zdjęcia wykonawców z Deezer (wysyłane są nazwy
  wykonawców), brakujące okładki albumów z MusicBrainz i Cover Art Archive (wysyłane są tytuł albumu i wykonawca),
  brakujące zdjęcia autorów z Audnexus (wysyłane są imiona i nazwiska autorów).
* **Teksty utworów z LRCLIB (opcjonalne, domyślnie wyłączone):** gdy włączysz **Ustawienia → Muzyka → Wyszukuj teksty
  utworów online**, tytuł, wykonawca i długość wyświetlanego utworu są wysyłane do LRCLIB (lrclib.net), aby znaleźć
  brakujące lub zsynchronizowane teksty.
* **Logowanie OpenID w Audiobookshelf (opcjonalne):** strona logowania dostawcy tożsamości Twojego serwera otwiera się
  w przeglądarce. EarLoft otrzymuje tylko tokeny od Twojego serwera Audiobookshelf.
* **Chromecast (opcjonalnie):** podczas przesyłania urządzenie Cast w Twojej sieci otrzymuje adres utworu i odtwarza
  go bezpośrednio z Twojego serwera. Utwory, które są tylko na telefonie (lub których serwera urządzenie Cast nie może
  osiągnąć), wczytuje zamiast tego z telefonu: podczas przesyłania EarLoft uruchamia w Twojej sieci Wi-Fi mały serwer
  WWW, który udostępnia wyłącznie utwory i okładki bieżącej sesji przesyłania – przez zwykły protokół HTTP, pod
  adresami z tajnym kluczem zmieniającym się w każdej sesji. Inne aplikacje do przesyłania w Twojej sieci mogą widzieć
  te adresy. Gdy urządzenie Cast wczytuje dane z telefonu, aplikacja utrzymuje telefon i jego Wi-Fi w stanie
  aktywnym.

EarLoft nie łączy się z żadną inną usługą. Udostępniane linki prowadzą do otwartych źródeł (MusicBrainz, strona
internetowa i kanał podcastu, Open Library), nigdy do Twoich własnych serwerów; sama aplikacja nie łączy się z tymi
stronami podczas udostępniania.

---

## 3. Przechowywanie danych i bezpieczeństwo

* **Pamięć lokalna:** Twoja biblioteka, subskrypcje, pobrane pliki, postęp słuchania, statystyki i ustawienia są
  przechowywane na Twoim urządzeniu (oraz na zegarku – w zakresie tego, co na niego wgrasz).
* **Dane logowania:** hasła do serwerów, klucze API i tokeny logowania są przechowywane w postaci zaszyfrowanej za
  pomocą Android Keystore. W przypadku Jellyfin i Emby zapisywany jest tylko token logowania, a nie hasło.
* **Połączenia nieszyfrowane:** z serwerami i kanałami można łączyć się przez zwykły protokół HTTP (np. w domu).
  Aplikacja ostrzega Cię, zanim użyje nieszyfrowanego połączenia z serwerem.

---

## 4. Kopie zapasowe

* **Kopia zapasowa Androida:** jeśli korzystasz z kopii zapasowej Androida na koncie Google, zawiera ona Twoje
  ustawienia oraz to, czego nie zna żaden serwer: Twoje serwery *bez* haseł, subskrypcje, postęp słuchania, lokalne
  playlisty, statystyki słuchania oraz informację, które odcinki zostały pobrane. Nigdy nie zawiera bazy danych
  biblioteki, pobranych plików, haseł, kluczy API ani tokenów logowania. Adresy kanałów są zapisywane bez zmian: jeśli
  wydawca umieszcza osobisty klucz w adresie płatnego kanału, ten adres jest częścią kopii zapasowej.
* **Plik kopii zapasowej:** **Ustawienia → Ogólne → Kopia zapasowa** tworzy plik o tej samej zawartości, który
  przechowujesz samodzielnie – również bez haseł, kluczy API i danych logowania, ale z adresami kanałów (patrz wyżej).
  To, gdzie przechowujesz lub komu udostępniasz ten plik, zależy od Ciebie.

---

## 5. Wear OS, Android Auto i inne aplikacje

* **Zegarek:** telefon i zegarek wymieniają dane przez warstwę danych Wear OS (Usługi Google Play): ustawienia Twoich
  serwerów wraz z danymi logowania potrzebnymi zegarkowi, to, co wybierzesz do wgrania na zegarek, polecenia
  odtwarzania i postęp słuchania. Następnie zegarek sam łączy się z Twoimi serwerami i kanałami podcastów, a książki
  z ARD Sounds wczytuje bezpośrednio z ARD.
* **Android Auto:** Twoja biblioteka jest wyświetlana w samochodzie przez Android Auto na Twoim telefonie.
* **Sterowanie multimediami:** jak każda aplikacja multimedialna, EarLoft udostępnia systemowi informacje o tym, co
  jest odtwarzane (tytuł, wykonawca, okładka), na potrzeby powiadomienia, ekranu blokady, widżetu i połączonych
  urządzeń. Inne aplikacje mogą jedynie sterować odtwarzaniem; nie mogą przeglądać Twojej biblioteki ani zmieniać
  Twojej kolejki.

---

## 6. Uprawnienia

O wszystkie uprawnienia aplikacja prosi dopiero wtedy, gdy są potrzebne, i działa z tymi, na które zezwolisz:

* **Muzyka i dźwięk** (`READ_MEDIA_AUDIO`, w starszych wersjach Androida dostęp do pamięci): tylko gdy włączysz muzykę,
  podcasty lub książki na tym urządzeniu – do odczytu Twoich plików audio.
* **Powiadomienia** (`POST_NOTIFICATIONS`): o nowych treściach i postępie pobierania.
* **Urządzenia w sieci lokalnej** (`ACCESS_LOCAL_NETWORK`, Android 17 i nowsze): do łączenia się z serwerami w Twojej
  sieci domowej.
* **Internet, stan sieci, usługi na pierwszym planie** – do odtwarzania, pobierania i przesyłania na zegarek.
* **Zapobieganie przejściu urządzenia w tryb uśpienia** (`WAKE_LOCK`): aby odtwarzanie, pobieranie i przesyłanie
  trwały przy wyłączonym ekranie.
* **Wibracje** (`VIBRATE`): krótka informacja zwrotna, np. gdy wyłącznik czasowy zostanie przedłużony potrząśnięciem.
* Na zegarku dodatkowo **zmiana stanu sieci** (`CHANGE_NETWORK_STATE`): aby zażądać Wi-Fi do pobierania.
* **Uruchamianie po ponownym uruchomieniu** (`RECEIVE_BOOT_COMPLETED`): aby zaplanowane synchronizacje i pobierania
  były kontynuowane po ponownym uruchomieniu urządzenia.

Wyłącznik czasowy książek i odcinków można przedłużyć, potrząsając telefonem; w tym celu czujnik ruchu
(akcelerometr) jest odczytywany tylko w ostatniej minucie odliczania i do dwóch minut po jego zakończeniu. Jego
odczyty nigdy nie opuszczają urządzenia. Możesz to wyłączyć w ustawieniach.

EarLoft nie prosi o dostęp do lokalizacji, mikrofonu, aparatu, kontaktów, telefonu ani Bluetootha.

---

## 7. Open source

EarLoft to oprogramowanie open source. Pełny kod źródłowy możesz przejrzeć w publicznym repozytorium:
https://github.com/TinyScripter/EarLoft

---

## 8. Kontakt i zapytania

Jeśli masz pytania, chcesz zgłosić błąd lub masz zastrzeżenia dotyczące tej polityki prywatności, utwórz zgłoszenie
(issue) w publicznym repozytorium:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
