# Règles de confidentialité d’EarLoft

**Dernière mise à jour :** 20 novembre 2026  
**ID de l’application :** de.kleinabersmart.earloft (téléphone et montre Wear OS)

La version anglaise (PRIVACY.md) fait foi ; ce document en est une traduction.

## Présentation

EarLoft est un lecteur open source pour votre propre collection : la musique de votre serveur Navidrome, Subsonic,
Jellyfin ou Emby, les podcasts issus de flux RSS et Atom, les livres audio de votre serveur Audiobookshelf ou d’ARD
Sounds, et les fichiers audio de votre appareil.
Il est conçu avec le respect de la vie privée comme principe fondamental. Le développeur ne vous suit pas, ne collecte
aucune donnée personnelle et n’exploite aucun serveur qui lui soit propre. Il n’y a aucun compte à créer.

---

## 1. Collecte de données et suivi

* **Aucune donnée personnelle collectée par le développeur :** EarLoft n’envoie ni votre nom, ni votre position, ni
  vos contacts, ni les identifiants de votre appareil, ni votre bibliothèque, ni votre historique d’écoute au
  développeur ou à quiconque.
* **Aucune analyse d’audience, publicité ni traceur :** l’application ne contient aucune bibliothèque d’analyse, aucun
  service de rapports de plantage, aucun SDK de suivi ni aucun réseau publicitaire.

---

## 2. Activité réseau

EarLoft ne se connecte à Internet que pour les fonctionnalités que vous utilisez. Chaque connexion va directement de
votre appareil (ou de votre montre) au service concerné :

* **Vos propres serveurs :** les serveurs Navidrome / Subsonic, Jellyfin, Emby et Audiobookshelf que vous ajoutez.
  L’application synchronise votre bibliothèque, lit en streaming et télécharge des fichiers, et renvoie ce que vous
  faites dans l’application : favoris, playlists, nombre d’écoutes et progression d’écoute. Si vous laissez le
  scrobbling activé (**Paramètres → Musique**), les morceaux écoutés sont signalés à votre serveur de musique, qui peut
  les transmettre aux services que vous y avez configurés (par exemple Last.fm ou ListenBrainz).
* **Flux de podcasts :** les flux auxquels vous êtes abonné et les sites qui hébergent leurs épisodes et leurs images,
  exploités par les éditeurs respectifs.
* **Annuaires de podcasts (facultatif, désactivé par défaut) :** lorsque vous activez un annuaire (dans
  **Paramètres → Podcasts** ou avec la roue dentée de **Ajouter un podcast**), il est interrogé sur les podcasts
  populaires dès que vous ouvrez **Ajouter un podcast** (votre pays et votre langue sont envoyés), et sur vos termes de
  recherche lorsque vous effectuez une recherche : Apple Podcasts (l’API de recherche d’Apple), Podcast Index (l’API de
  podcastindex.org), fyyd (l’API de fyyd.de ; uniquement vos termes de recherche, il n’est pas interrogé sur les
  podcasts populaires) et ARD Sounds (l’interface publique de l’ARD). Les émissions d’ARD Sounds sans flux propre sont
  également actualisées via cette interface.
* **Livres audio d’ARD Sounds (facultatif, désactivé par défaut) :** lorsque vous les activez dans
  **Paramètres → Livres**, les livres et pièces radiophoniques que vous parcourez, recherchez, lisez ou téléchargez
  proviennent de l’interface publique de l’ARD. Les livres de votre bibliothèque et les émissions que vous suivez y sont
  vérifiés régulièrement, y compris en arrière-plan (environ toutes les 12 heures, en Wi-Fi), pour détecter de
  nouvelles parties, de nouveaux livres et leur date d’expiration. Vos termes de recherche et ces livres sont envoyés à
  l’ARD.
* **Images d’Internet (facultatif, désactivé par défaut) :** photos d’artistes depuis Deezer (les noms des artistes
  sont envoyés), pochettes d’album manquantes depuis MusicBrainz et Cover Art Archive (le titre de l’album et l’artiste
  sont envoyés), images d’auteurs manquantes depuis Audnexus (les noms des auteurs sont envoyés).
* **Paroles depuis LRCLIB (facultatif, désactivé par défaut) :** lorsque vous activez
  **Paramètres → Musique → Rechercher les paroles en ligne**, le titre, l’artiste et la durée du morceau affiché sont
  envoyés à LRCLIB (lrclib.net) pour trouver des paroles manquantes ou synchronisées.
* **Connexion OpenID pour Audiobookshelf (facultatif) :** la page de connexion du fournisseur d’identité de votre
  serveur s’ouvre dans votre navigateur. EarLoft reçoit uniquement les jetons de votre serveur Audiobookshelf.
* **Chromecast (facultatif) :** lorsque vous castez, l’appareil Cast de votre réseau reçoit l’adresse du morceau et le
  lit directement depuis votre serveur. Les morceaux qui se trouvent uniquement sur votre téléphone (ou dont l’appareil
  Cast ne peut pas joindre le serveur) sont chargés depuis le téléphone : pendant le cast, EarLoft exécute sur votre
  réseau Wi-Fi un petit serveur web qui ne fournit que les morceaux et pochettes de cette session de cast, en HTTP simple,
  à des adresses contenant un secret qui change à chaque session. Les autres applications de cast de votre réseau
  peuvent voir ces adresses. Tant que l’appareil Cast charge depuis le téléphone, l’application maintient le téléphone
  et son Wi-Fi actifs.

EarLoft ne contacte aucun autre service. Les liens que vous partagez renvoient vers des sources ouvertes (MusicBrainz,
le site web et le flux du podcast, Open Library), jamais vers vos propres serveurs ; l’application elle-même ne
contacte pas ces sites lorsque vous partagez.

---

## 3. Stockage et sécurité

* **Stockage local :** votre bibliothèque, vos abonnements, vos téléchargements, votre progression d’écoute, vos
  statistiques et vos paramètres sont stockés sur votre appareil (et sur votre montre, pour ce que vous y chargez).
* **Identifiants :** les mots de passe des serveurs, les clés API et les jetons de connexion sont stockés chiffrés à
  l’aide du Keystore Android. Pour Jellyfin et Emby, seul le jeton de connexion est conservé, pas le mot de passe.
* **Connexions non chiffrées :** les serveurs et les flux peuvent être joints en HTTP simple (par exemple à la
  maison). L’application vous avertit avant d’utiliser une connexion non chiffrée vers un serveur.

---

## 4. Sauvegardes

* **Sauvegarde Android :** si vous utilisez la sauvegarde Android sur votre compte Google, elle contient vos paramètres
  et ce qu’aucun serveur ne connaît : vos serveurs *sans* leurs mots de passe, vos abonnements, votre progression
  d’écoute, vos playlists locales, vos statistiques d’écoute et la liste des épisodes téléchargés. Elle ne contient
  jamais la base de données de la bibliothèque, les fichiers téléchargés, les mots de passe, les clés API ni les jetons
  de connexion. Les adresses des flux sont conservées telles quelles : si un éditeur intègre une clé personnelle dans
  l’adresse d’un flux payant, cette adresse fait partie de la sauvegarde.
* **Fichier de sauvegarde :** **Paramètres → Général → Sauvegarde** crée un fichier au même contenu, que vous conservez
  vous-même, là encore sans mots de passe, clés API ni connexions, mais avec les adresses des flux (voir ci-dessus).
  L’endroit où vous stockez ou partagez ce fichier relève de votre choix.

---

## 5. Wear OS, Android Auto et autres applications

* **Montre :** le téléphone et la montre échangent des données via la couche de données Wear OS (services Google
  Play) : les paramètres de vos serveurs, y compris les identifiants dont la montre a besoin, ce que vous choisissez de
  charger sur la montre, les commandes de lecture et la progression d’écoute. La montre se connecte ensuite elle-même à
  vos serveurs et à vos flux de podcasts, et charge les livres d’ARD Sounds directement depuis l’ARD.
* **Android Auto :** votre bibliothèque s’affiche dans la voiture via Android Auto sur votre téléphone.
* **Commandes multimédias :** comme toute application multimédia, EarLoft partage ce qui est en cours de lecture
  (titre, artiste, pochette) avec le système pour la notification, l’écran de verrouillage, le widget et les appareils
  connectés. Les autres applications peuvent uniquement contrôler la lecture ; elles ne peuvent ni parcourir votre
  bibliothèque ni modifier votre file d’attente.

---

## 6. Autorisations

Toutes les autorisations ne sont demandées qu’en cas de besoin, et l’application fonctionne avec ce que vous autorisez :

* **Musique et audio** (`READ_MEDIA_AUDIO`, accès au stockage sur les anciennes versions d’Android) : uniquement
  lorsque vous activez la musique, les podcasts ou les livres de cet appareil, pour lire vos fichiers audio.
* **Notifications** (`POST_NOTIFICATIONS`) : pour les nouveaux contenus et la progression des téléchargements.
* **Appareils sur votre réseau local** (`ACCESS_LOCAL_NETWORK`, Android 17 et versions ultérieures) : pour joindre les
  serveurs de votre réseau domestique.
* **Internet, état du réseau, services de premier plan** pour la lecture, les téléchargements et les transferts vers la
  montre.
* **Maintien de l’appareil en activité** (`WAKE_LOCK`) : pour que la lecture, les téléchargements et le cast se
  poursuivent écran éteint.
* **Vibreur** (`VIBRATE`) : bref retour haptique, par exemple lorsque le minuteur de mise en veille est prolongé en
  secouant l’appareil.
* Sur la montre, en plus, **modification de l’état du réseau** (`CHANGE_NETWORK_STATE`) : pour demander le Wi-Fi pour
  les téléchargements.
* **Démarrage après un redémarrage** (`RECEIVE_BOOT_COMPLETED`) : pour que les synchronisations et téléchargements
  planifiés se poursuivent après le redémarrage de l’appareil.

Le minuteur de mise en veille des livres et des épisodes peut être prolongé en secouant le téléphone ; pour cela, le
capteur de mouvement (accéléromètre) n’est lu que pendant la dernière minute du minuteur et jusqu’à deux minutes après
son expiration. Ses valeurs ne quittent jamais l’appareil. Vous pouvez désactiver cette fonction dans les paramètres.

EarLoft ne demande pas l’accès à la position, au micro, à l’appareil photo, aux contacts, au téléphone ni au Bluetooth.

---

## 7. Open source

EarLoft est un logiciel open source. Vous pouvez consulter l’intégralité du code source dans le dépôt public :
https://github.com/TinyScripter/EarLoft

---

## 8. Contact et questions

Si vous avez des questions, des rapports de bugs ou des préoccupations concernant ces règles de confidentialité,
veuillez ouvrir un ticket dans le dépôt public :

* **GitHub Issues :** https://github.com/TinyScripter/EarLoft/issues
