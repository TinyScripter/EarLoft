# Informativa sulla privacy di EarLoft

**Ultimo aggiornamento:** 20 novembre 2026  
**ID applicazione:** de.kleinabersmart.earloft (telefono e orologio Wear OS)

## Panoramica

EarLoft è un player open source per la tua collezione: musica dal tuo server Navidrome, Subsonic, Jellyfin o Emby,
podcast da feed RSS e Atom, audiolibri dal tuo server Audiobookshelf o da ARD Sounds e file audio sul tuo
dispositivo.
È stata sviluppata con la privacy come principio fondamentale. Lo sviluppatore non ti traccia, non raccoglie dati
personali e non gestisce alcun server proprio. Non c’è nessun account da creare.

---

## 1. Raccolta dei dati e tracciamento

* **Nessun dato personale raccolto dallo sviluppatore:** EarLoft non invia il tuo nome, la tua posizione, i tuoi
  contatti, gli identificatori del dispositivo, la tua libreria o la tua cronologia di ascolto allo sviluppatore né a
  nessun altro.
* **Nessuno strumento di analisi, pubblicità o tracker:** l’app non contiene librerie di analisi, servizi di
  segnalazione degli arresti anomali, SDK di tracciamento o reti pubblicitarie.

---

## 2. Attività di rete

EarLoft si connette a Internet solo per le funzioni che usi. Ogni connessione va direttamente dal tuo dispositivo (o
dall’orologio) al rispettivo servizio:

* **I tuoi server:** i server Navidrome / Subsonic, Jellyfin, Emby e Audiobookshelf che aggiungi. L’app sincronizza
  la tua libreria, riproduce in streaming e scarica i file e rimanda indietro ciò che fai nell’app: preferiti,
  playlist, conteggi degli ascolti e avanzamento dell’ascolto. Se lasci attiva la segnalazione degli ascolti
  (scrobbling, **Impostazioni → Musica**), i brani riprodotti vengono segnalati al tuo server musicale, che può
  inoltrarli ai servizi che hai configurato lì (ad esempio Last.fm o ListenBrainz).
* **Feed dei podcast:** i feed a cui ti iscrivi e i siti che ospitano i loro episodi e le loro immagini, gestiti dai
  rispettivi editori.
* **Cataloghi di podcast (facoltativi, disattivati per impostazione predefinita):** quando attivi un catalogo (in
  **Impostazioni → Podcast** o con l’ingranaggio in **Aggiungi podcast**), gli vengono chiesti i podcast popolari non
  appena apri **Aggiungi podcast** (vengono inviati il tuo paese e la tua lingua) e, quando cerchi, i risultati per i
  tuoi termini di ricerca: Apple Podcasts (l’API di ricerca di Apple), Podcast Index (l’API di podcastindex.org), fyyd
  (l’API di fyyd.de; solo i tuoi termini di ricerca, non gli vengono chiesti i podcast popolari) e ARD Sounds
  (l’interfaccia pubblica dell’ARD). Anche i programmi di ARD Sounds senza un feed proprio vengono aggiornati tramite
  questa interfaccia.
* **Audiolibri da ARD Sounds (facoltativi, disattivati per impostazione predefinita):** quando li attivi in
  **Impostazioni → Libri**, i libri e i radiodrammi che sfogli, cerchi, riproduci o scarichi provengono
  dall’interfaccia pubblica dell’ARD. I libri della tua libreria e i programmi che segui vengono controllati lì
  regolarmente, anche in background (circa ogni 12 ore, con il Wi-Fi), per verificare nuove parti, nuovi libri e la
  data di scadenza. I tuoi termini di ricerca e questi libri vengono inviati all’ARD.
* **Immagini da Internet (facoltative, disattivate per impostazione predefinita):** foto degli artisti da Deezer
  (vengono inviati i nomi degli artisti), copertine degli album mancanti da MusicBrainz e dal Cover Art Archive
  (vengono inviati titolo dell’album e artista), immagini degli autori mancanti da Audnexus (vengono inviati i nomi
  degli autori).
* **Testi da LRCLIB (facoltativi, disattivati per impostazione predefinita):** quando attivi **Impostazioni → Musica →
  Cerca i testi online**, titolo, artista e durata del brano mostrato vengono inviati a LRCLIB (lrclib.net) per
  trovare testi mancanti o sincronizzati.
* **Accesso OpenID per Audiobookshelf (facoltativo):** la pagina di accesso del provider di identità del tuo server si
  apre nel browser. EarLoft riceve solo i token dal tuo server Audiobookshelf.
* **Chromecast (facoltativo):** quando trasmetti, il dispositivo Cast sulla tua rete riceve l’indirizzo del brano e lo
  riproduce direttamente dal tuo server. I titoli presenti solo sul telefono (o il cui server non è raggiungibile dal
  dispositivo Cast) vengono invece caricati dal telefono: durante la trasmissione, EarLoft esegue sul tuo Wi-Fi un
  piccolo server web che fornisce solo i titoli e le copertine di questa sessione di trasmissione, tramite semplice
  HTTP, a indirizzi con un codice segreto che cambia a ogni sessione. Altre app di trasmissione sulla tua rete possono
  vedere questi indirizzi. Mentre il dispositivo Cast carica dal telefono, l’app mantiene attivi il telefono e il suo
  Wi-Fi.

EarLoft non contatta nessun altro servizio. I link che condividi rimandano a fonti aperte (MusicBrainz, il sito web e
il feed del podcast, Open Library), mai ai tuoi server; l’app stessa non contatta questi siti quando condividi.

---

## 3. Archiviazione e sicurezza

* **Archiviazione locale:** la tua libreria, le iscrizioni, i download, l’avanzamento dell’ascolto, le statistiche e
  le impostazioni vengono salvati sul tuo dispositivo (e sull’orologio, per ciò che vi carichi).
* **Credenziali:** password dei server, chiavi API e token di accesso vengono salvati in forma criptata con l’Android
  Keystore. Per Jellyfin ed Emby viene conservato solo il token di accesso, non la password.
* **Connessioni non criptate:** server e feed possono essere raggiunti tramite semplice HTTP (ad esempio a casa).
  L’app ti avvisa prima di usare una connessione non criptata con un server.

---

## 4. Backup

* **Backup di Android:** se usi il backup di Android sul tuo account Google, contiene le tue impostazioni e ciò che
  nessun server conosce: i tuoi server *senza* le relative password, le iscrizioni, l’avanzamento dell’ascolto, le
  playlist locali, le statistiche di ascolto e quali episodi sono stati scaricati. Non contiene mai il database della
  libreria, i file scaricati, le password, le chiavi API o i token di accesso. Gli indirizzi dei feed vengono
  conservati così come sono: se un editore inserisce una chiave personale nell’indirizzo di un feed a pagamento,
  quell’indirizzo fa parte del backup.
* **File di backup:** **Impostazioni → Generali → Backup** crea un file con lo stesso contenuto, che conservi tu
  stesso, anche in questo caso senza password, chiavi API o accessi, ma con gli indirizzi dei feed (vedi sopra). Dove
  salvi o condividi questo file dipende da te.

---

## 5. Wear OS, Android Auto e altre app

* **Orologio:** il telefono e l’orologio si scambiano dati tramite il livello dati di Wear OS (Google Play Services):
  le impostazioni dei tuoi server, incluse le credenziali di cui l’orologio ha bisogno, ciò che scegli di caricare
  sull’orologio, i comandi di riproduzione e l’avanzamento dell’ascolto. L’orologio si connette poi autonomamente ai
  tuoi server e ai feed dei podcast e carica i libri di ARD Sounds direttamente dall’ARD.
* **Android Auto:** la tua libreria viene mostrata in auto tramite Android Auto sul telefono.
* **Controlli multimediali:** come ogni app multimediale, EarLoft condivide con il sistema ciò che è in riproduzione
  (titolo, artista, copertina) per la notifica, la schermata di blocco, il widget e i dispositivi connessi. Le altre
  app possono solo controllare la riproduzione; non possono sfogliare la tua libreria né modificare la tua coda.

---

## 6. Autorizzazioni

Tutte le autorizzazioni vengono richieste solo quando servono, e l’app funziona con quelle che concedi:

* **Musica e audio** (`READ_MEDIA_AUDIO`, nelle versioni di Android meno recenti l’accesso allo spazio di
  archiviazione): solo quando attivi musica, podcast o libri su questo dispositivo, per leggere i tuoi file audio.
* **Notifiche** (`POST_NOTIFICATIONS`): per i nuovi contenuti e lo stato dei download.
* **Dispositivi sulla rete locale** (`ACCESS_LOCAL_NETWORK`, Android 17 e versioni successive): per raggiungere i
  server della tua rete domestica.
* **Internet, stato della rete, servizi in primo piano** per la riproduzione, i download e i trasferimenti
  all’orologio.
* **Mantenere attivo il dispositivo** (`WAKE_LOCK`): affinché riproduzione, download e trasmissione continuino a
  schermo spento.
* **Vibrazione** (`VIBRATE`): brevi feedback, ad es. quando il timer di spegnimento viene prolungato scuotendo il
  telefono.
* Sull’orologio, inoltre, **modificare lo stato della rete** (`CHANGE_NETWORK_STATE`): per richiedere il Wi-Fi per i
  download.
* **Avvio dopo un riavvio** (`RECEIVE_BOOT_COMPLETED`): affinché sincronizzazioni e download pianificati continuino
  dopo il riavvio del dispositivo.

Il timer di spegnimento di libri ed episodi può essere prolungato scuotendo il telefono; a questo scopo il sensore di
movimento (accelerometro) viene letto solo durante l’ultimo minuto del timer e fino a due minuti dopo la sua scadenza.
I suoi valori non lasciano mai il dispositivo. Puoi disattivare questa funzione nelle impostazioni.

EarLoft non chiede l’accesso a posizione, microfono, fotocamera, contatti, telefono o Bluetooth.

---

## 7. Open source

EarLoft è un software open source. Puoi esaminare il codice sorgente completo nel repository pubblico:
https://github.com/TinyScripter/EarLoft

---

## 8. Contatti e richieste

Per domande, segnalazioni di bug o dubbi relativi a questa informativa sulla privacy, apri una issue nel repository
pubblico:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
