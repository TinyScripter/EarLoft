# Política de privacidad de EarLoft

**Última actualización:** 20 de noviembre de 2026  
**ID de la aplicación:** de.kleinabersmart.earloft (teléfono y reloj Wear OS)

## Resumen

EarLoft es un reproductor de código abierto para tu propia colección: música de tu servidor Navidrome, Subsonic,
Jellyfin o Emby, podcasts de feeds RSS y Atom, audiolibros de tu servidor Audiobookshelf o de ARD Sounds, y archivos de
audio de tu dispositivo.
Se ha creado con la privacidad como principio fundamental. El desarrollador no te rastrea, no recopila datos personales
y no gestiona ningún servidor propio. No hay que crear ninguna cuenta.

---

## 1. Recopilación de datos y seguimiento

* **El desarrollador no recopila datos personales:** EarLoft no envía tu nombre, tu ubicación, tus contactos, los
  identificadores del dispositivo, tu biblioteca ni tu historial de escucha al desarrollador ni a nadie más.
* **Sin analíticas, anuncios ni rastreadores:** la app no contiene bibliotecas de analíticas, servicios de informes de
  fallos, SDK de seguimiento ni redes publicitarias.

---

## 2. Actividad de red

EarLoft solo se conecta a internet para las funciones que usas. Cada conexión va directamente de tu dispositivo (o
reloj) al servicio correspondiente:

* **Tus propios servidores:** los servidores Navidrome / Subsonic, Jellyfin, Emby y Audiobookshelf que añadas. La app
  sincroniza tu biblioteca, reproduce en streaming y descarga archivos, y envía de vuelta lo que haces en la app:
  favoritos, listas de reproducción, número de reproducciones y progreso de escucha. Si dejas activado el envío de
  reproducciones (**Ajustes → Música**), las canciones reproducidas se comunican a tu servidor de música, que puede
  reenviarlas a los servicios que hayas configurado allí (por ejemplo, Last.fm o ListenBrainz).
* **Feeds de podcasts:** los feeds a los que te suscribes y los sitios que alojan sus episodios e imágenes, gestionados
  por los respectivos editores.
* **Directorios de podcasts (opcionales, desactivados de forma predeterminada):** cuando activas un directorio (en
  **Ajustes → Podcasts** o con el engranaje de **Añadir podcast**), se le piden los podcasts populares en cuanto abres
  **Añadir podcast** (se envían tu país y tu idioma), y tus términos de búsqueda cuando buscas: Apple Podcasts (la API de
  búsqueda de Apple), Podcast Index (la API de podcastindex.org), fyyd (la API de fyyd.de; solo tus términos de
  búsqueda, no se le piden podcasts populares) y ARD Sounds (la interfaz pública de la ARD). Los programas de ARD Sounds
  sin feed propio también se actualizan a través de esa interfaz.
* **Audiolibros de ARD Sounds (opcionales, desactivados de forma predeterminada):** cuando los activas en
  **Ajustes → Libros**, los libros y radioteatros que exploras, buscas, reproduces o descargas proceden de la interfaz
  pública de la ARD. Los libros de tu biblioteca y los programas que sigues se comprueban allí con regularidad, también
  en segundo plano (aproximadamente cada 12 horas, con Wi-Fi), para buscar nuevas partes, nuevos libros y su fecha de
  caducidad. Tus términos de búsqueda y estos libros se envían a la ARD.
* **Imágenes de internet (opcionales, desactivadas de forma predeterminada):** fotos de artistas de Deezer (se envían
  los nombres de los artistas), portadas de álbumes que faltan de MusicBrainz y Cover Art Archive (se envían el título
  del álbum y el artista), e imágenes de autores que faltan de Audnexus (se envían los nombres de los autores).
* **Letras de LRCLIB (opcionales, desactivadas de forma predeterminada):** cuando activas
  **Ajustes → Música → Buscar letras en línea**, el título, el artista y la duración de la canción que se muestra se
  envían a LRCLIB (lrclib.net) para encontrar letras que faltan o letras sincronizadas.
* **Inicio de sesión con OpenID para Audiobookshelf (opcional):** la página de inicio de sesión del proveedor de
  identidad de tu servidor se abre en tu navegador. EarLoft solo recibe los tokens de tu servidor Audiobookshelf.
* **Chromecast (opcional):** cuando envías contenido, el dispositivo Cast de tu red recibe la dirección de la canción y
  la reproduce directamente desde tu servidor. Los títulos que solo están en tu teléfono (o cuyo servidor no es
  accesible para el dispositivo Cast) los carga en su lugar desde el teléfono: mientras envías contenido, EarLoft
  ejecuta un pequeño servidor web en tu red Wi-Fi que solo sirve los títulos y las portadas de esa sesión de envío,
  mediante HTTP sin cifrar, en direcciones con un secreto que cambia en cada sesión. Otras apps de envío de tu red pueden
  ver estas direcciones. Mientras el dispositivo Cast carga desde el teléfono, la app mantiene activos el teléfono y su
  Wi-Fi.

EarLoft no contacta con ningún otro servicio. Los enlaces que compartes apuntan a fuentes abiertas (MusicBrainz, el
sitio web y el feed del podcast, Open Library), nunca a tus propios servidores; la propia app no contacta con estos
sitios cuando compartes.

---

## 3. Almacenamiento y seguridad

* **Almacenamiento local:** tu biblioteca, tus suscripciones, tus descargas, tu progreso de escucha, tus estadísticas y
  tus ajustes se guardan en tu dispositivo (y en tu reloj, en el caso de lo que pases a él).
* **Credenciales:** las contraseñas de los servidores, las claves API y los tokens de inicio de sesión se guardan
  cifrados con el Android Keystore. En Jellyfin y Emby solo se conserva el token de inicio de sesión, no la contraseña.
* **Conexiones sin cifrar:** se puede acceder a servidores y feeds mediante HTTP sin cifrar (por ejemplo, en casa). La
  app te avisa antes de usar una conexión sin cifrar con un servidor.

---

## 4. Copias de seguridad

* **Copia de seguridad de Android:** si usas la copia de seguridad de Android en tu cuenta de Google, esta contiene tus
  ajustes y lo que ningún servidor conoce: tus servidores *sin* sus contraseñas, suscripciones, progreso de escucha,
  listas de reproducción locales, estadísticas de escucha y qué episodios se descargaron. Nunca contiene la base de
  datos de la biblioteca, los archivos descargados, las contraseñas, las claves API ni los tokens de inicio de sesión.
  Las direcciones de los feeds se conservan tal cual: si un editor incluye una clave personal en la dirección de un feed
  de pago, esa dirección forma parte de la copia de seguridad.
* **Archivo de copia de seguridad:** **Ajustes → General → Copia de seguridad** crea un archivo con el mismo contenido
  que guardas tú mismo, de nuevo sin contraseñas, claves API ni inicios de sesión, pero con las direcciones de los feeds
  (ver arriba). Dónde guardes o compartas este archivo depende de ti.

---

## 5. Wear OS, Android Auto y otras apps

* **Reloj:** el teléfono y el reloj intercambian datos a través de la capa de datos de Wear OS (servicios de Google
  Play): los ajustes de tus servidores, incluidas las credenciales que necesita el reloj, lo que eliges pasar al reloj,
  los comandos de reproducción y el progreso de escucha. Después, el reloj se conecta por sí mismo a tus servidores y a
  los feeds de podcasts, y carga los libros de ARD Sounds directamente desde la ARD.
* **Android Auto:** tu biblioteca se muestra en el coche a través de Android Auto en tu teléfono.
* **Controles multimedia:** como cualquier app multimedia, EarLoft comparte lo que se está reproduciendo (título,
  artista, portada) con el sistema para la notificación, la pantalla de bloqueo, el widget y los dispositivos
  conectados. Otras apps solo pueden controlar la reproducción; no pueden explorar tu biblioteca ni cambiar tu cola.

---

## 6. Permisos

Todos los permisos se solicitan solo cuando se necesitan, y la app funciona con lo que permitas:

* **Música y audio** (`READ_MEDIA_AUDIO`; en versiones antiguas de Android, acceso al almacenamiento): solo cuando
  activas la música, los podcasts o los libros de este dispositivo, para leer tus archivos de audio.
* **Notificaciones** (`POST_NOTIFICATIONS`): para contenido nuevo y el progreso de las descargas.
* **Dispositivos de tu red local** (`ACCESS_LOCAL_NETWORK`, Android 17 y versiones posteriores): para acceder a
  servidores de tu red doméstica.
* **Internet, estado de la red y servicios en primer plano** para la reproducción, las descargas y las transferencias
  al reloj.
* **Mantener activo el dispositivo** (`WAKE_LOCK`): para que la reproducción, las descargas y el envío de contenido
  continúen con la pantalla apagada.
* **Vibración** (`VIBRATE`): respuesta breve, p. ej., cuando se alarga el temporizador de apagado agitando el teléfono.
* En el reloj, además, **cambiar el estado de la red** (`CHANGE_NETWORK_STATE`): para solicitar Wi-Fi para las
  descargas.
* **Iniciar después de un reinicio** (`RECEIVE_BOOT_COMPLETED`): para que las sincronizaciones y descargas programadas
  continúen después de reiniciar el dispositivo.

El temporizador de apagado de libros y episodios se puede alargar agitando el teléfono; para ello, el sensor de
movimiento (acelerómetro) solo se lee durante el último minuto del temporizador y hasta dos minutos después de que haya
terminado. Sus valores nunca salen del dispositivo. Puedes desactivar esta función en los ajustes.

EarLoft no solicita acceso a la ubicación, el micrófono, la cámara, los contactos, el teléfono ni Bluetooth.

---

## 7. Código abierto

EarLoft es software de código abierto. Puedes revisar todo el código fuente en el repositorio público:
https://github.com/TinyScripter/EarLoft

---

## 8. Contacto y consultas

Si tienes preguntas, informes de errores o dudas sobre esta política de privacidad, abre una incidencia en el
repositorio público:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
