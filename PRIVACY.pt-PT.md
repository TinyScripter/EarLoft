# Política de privacidade da EarLoft

**Última atualização:** 20 de novembro de 2026  
**ID da aplicação:** de.kleinabersmart.earloft (telemóvel e relógio Wear OS)

## Visão geral

A EarLoft é um leitor de código aberto para a sua própria coleção: música do seu servidor Navidrome, Subsonic,
Jellyfin ou Emby, podcasts de feeds RSS e Atom, audiolivros do seu servidor Audiobookshelf ou do ARD Sounds, e
ficheiros de áudio no seu dispositivo.
Foi desenvolvida tendo a privacidade como princípio fundamental. O programador não o segue, não recolhe dados pessoais
e não opera nenhum servidor próprio. Não é necessário criar nenhuma conta.

---

## 1. Recolha de dados e rastreio

* **O programador não recolhe dados pessoais:** a EarLoft não envia o seu nome, localização, contactos,
  identificadores do dispositivo, biblioteca ou histórico de audição ao programador nem a mais ninguém.
* **Sem análises, anúncios ou rastreadores:** a app não contém bibliotecas de análise, serviços de relatórios de
  falhas, SDKs de rastreio nem redes de publicidade.

---

## 2. Atividade de rede

A EarLoft liga-se à Internet apenas para as funcionalidades que utiliza. Cada ligação vai diretamente do seu
dispositivo (ou relógio) para o serviço respetivo:

* **Os seus próprios servidores:** os servidores Navidrome / Subsonic, Jellyfin, Emby e Audiobookshelf que adicionar.
  A app sincroniza a sua biblioteca, faz streaming e transfere ficheiros, e devolve o que faz na app: favoritos,
  listas de reprodução, contagens de reproduções e progresso de audição. Se deixar a comunicação de reproduções ativada
  (**Definições → Música**), as músicas reproduzidas são comunicadas ao seu servidor de música, que as pode reencaminhar
  para serviços aí configurados (por exemplo, Last.fm ou ListenBrainz).
* **Feeds de podcasts:** os feeds que subscreve e os sites que alojam os respetivos episódios e imagens, operados pelos
  respetivos editores.
* **Diretórios de podcasts (opcional, desativado por predefinição):** quando ativa um diretório (em
  **Definições → Podcasts** ou com a roda dentada em **Adicionar podcast**), este é consultado sobre podcasts
  populares assim que abre **Adicionar podcast** (são enviados o seu país e o seu idioma) e sobre os seus termos de
  pesquisa quando pesquisa: Apple Podcasts (a API de pesquisa da Apple), Podcast Index (a API de podcastindex.org),
  fyyd (a API de fyyd.de; apenas os seus termos de pesquisa, não é consultado sobre podcasts populares) e ARD Sounds
  (a interface pública da ARD). Os programas do ARD Sounds sem feed próprio também são atualizados através dessa
  interface.
* **Audiolivros do ARD Sounds (opcional, desativado por predefinição):** quando os ativa em **Definições → Livros**,
  os livros e o teatro radiofónico que explora, pesquisa, reproduz ou transfere vêm da interface pública da ARD. Os
  livros da sua biblioteca e os programas que segue são verificados aí regularmente, também em segundo plano (cerca de
  12 em 12 horas, por Wi-Fi), para detetar novas partes, novos livros e a respetiva data de expiração. Os seus termos
  de pesquisa e estes livros são enviados para a ARD.
* **Imagens da Internet (opcional, desativado por predefinição):** fotos de artistas do Deezer (são enviados os nomes
  dos artistas), capas de álbuns em falta do MusicBrainz e do Cover Art Archive (são enviados o título do álbum e o
  artista), imagens de autores em falta do Audnexus (são enviados os nomes dos autores).
* **Letras do LRCLIB (opcional, desativado por predefinição):** quando ativa
  **Definições → Música → Procurar letras online**, o título, o artista e a duração da música apresentada são enviados
  para o LRCLIB (lrclib.net) para encontrar letras em falta ou sincronizadas.
* **Início de sessão OpenID no Audiobookshelf (opcional):** a página de início de sessão do fornecedor de identidade
  do seu servidor abre no seu navegador. A EarLoft recebe apenas os tokens do seu servidor Audiobookshelf.
* **Chromecast (opcional):** quando transmite, o dispositivo Cast na sua rede recebe o endereço da música e
  reproduz-a diretamente a partir do seu servidor. Os títulos que só estão no seu telemóvel (ou cujo servidor o
  dispositivo Cast não consegue alcançar) são carregados a partir do telemóvel: enquanto transmite, a EarLoft executa
  um pequeno servidor web na sua rede Wi-Fi que disponibiliza apenas os títulos e as capas desta sessão de transmissão,
  por HTTP simples, em endereços com um segredo que muda em cada sessão. Outras apps de transmissão na sua rede podem
  ver estes endereços. Enquanto o dispositivo Cast carrega a partir do telemóvel, a app mantém o telemóvel e o Wi-Fi
  ativos.

A EarLoft não contacta nenhum outro serviço. Os links que partilha apontam para fontes abertas (MusicBrainz, o site e
o feed do podcast, Open Library), nunca para os seus próprios servidores; a própria app não contacta estes sites
quando partilha.

---

## 3. Armazenamento e segurança

* **Armazenamento local:** a sua biblioteca, subscrições, transferências, progresso de audição, estatísticas e
  definições são guardados no seu dispositivo (e no seu relógio, no que diz respeito ao conteúdo que transfere para
  ele).
* **Credenciais:** as palavras-passe dos servidores, as chaves da API e os tokens de início de sessão são guardados de
  forma encriptada com o Android Keystore. No Jellyfin e no Emby, é guardado apenas o token de início de sessão, não a
  palavra-passe.
* **Ligações não encriptadas:** os servidores e os feeds podem ser acedidos por HTTP simples (por exemplo, em casa). A
  app avisa antes de utilizar uma ligação não encriptada a um servidor.

---

## 4. Cópias de segurança

* **Cópia de segurança do Android:** se utilizar a cópia de segurança do Android na sua Conta Google, esta contém as
  suas definições e o que nenhum servidor conhece: os seus servidores *sem* as respetivas palavras-passe, subscrições,
  progresso de audição, listas de reprodução locais, estatísticas de audição e quais os episódios transferidos. Nunca
  contém a base de dados da biblioteca, os ficheiros transferidos, as palavras-passe, as chaves da API nem os tokens de
  início de sessão. Os endereços dos feeds são mantidos tal como estão: se um editor incluir uma chave pessoal no
  endereço de um feed pago, esse endereço faz parte da cópia de segurança.
* **Ficheiro de cópia de segurança:** **Definições → Geral → Cópia de segurança** cria um ficheiro com o mesmo
  conteúdo, que guarda por conta própria, novamente sem palavras-passe, chaves da API nem inícios de sessão, mas com os
  endereços dos feeds (ver acima). Cabe-lhe a si decidir onde guarda ou partilha este ficheiro.

---

## 5. Wear OS, Android Auto e outras apps

* **Relógio:** o telemóvel e o relógio trocam dados através da camada de dados do Wear OS (Serviços do Google Play):
  as definições dos seus servidores, incluindo as credenciais de que o relógio precisa, o que escolhe transferir para
  o relógio, comandos de reprodução e o progresso de audição. O relógio liga-se então por si próprio aos seus
  servidores e feeds de podcasts, e transfere os livros do ARD Sounds diretamente da ARD.
* **Android Auto:** a sua biblioteca é apresentada no carro através do Android Auto no seu telemóvel.
* **Controlos multimédia:** como qualquer app multimédia, a EarLoft partilha o que está a ser reproduzido (título,
  artista, capa) com o sistema para a notificação, o ecrã de bloqueio, o widget e os dispositivos ligados. As outras
  apps só podem controlar a reprodução; não podem explorar a sua biblioteca nem alterar a sua fila.

---

## 6. Autorizações

Todas as autorizações são pedidas apenas quando necessário, e a app funciona com o que permitir:

* **Música e áudio** (`READ_MEDIA_AUDIO`; em versões mais antigas do Android, acesso ao armazenamento): apenas quando
  ativa música, podcasts ou livros deste dispositivo, para ler os seus ficheiros de áudio.
* **Notificações** (`POST_NOTIFICATIONS`): para conteúdos novos e o progresso das transferências.
* **Dispositivos na sua rede local** (`ACCESS_LOCAL_NETWORK`, Android 17 e posterior): para aceder a servidores na
  sua rede doméstica.
* **Internet, estado da rede, serviços em primeiro plano** para a reprodução, as transferências e o envio para o
  relógio.
* **Manter o dispositivo ativo** (`WAKE_LOCK`): para que a reprodução, as transferências e a transmissão continuem com
  o ecrã desligado.
* **Vibração** (`VIBRATE`): feedback breve, por exemplo, quando o temporizador de suspensão é prolongado ao abanar.
* No relógio, adicionalmente, **alterar o estado da rede** (`CHANGE_NETWORK_STATE`): para pedir Wi-Fi para as
  transferências.
* **Iniciar após um reinício** (`RECEIVE_BOOT_COMPLETED`): para que as sincronizações e transferências agendadas
  continuem depois de o dispositivo ser reiniciado.

O temporizador de suspensão dos livros e episódios pode ser prolongado abanando o telemóvel; para isso, o sensor de
movimento (acelerómetro) só é lido durante o último minuto do temporizador e até dois minutos depois de este terminar.
Os respetivos valores nunca saem do dispositivo. Pode desativar esta função nas definições.

A EarLoft não pede acesso à localização, ao microfone, à câmara, aos contactos, ao telefone nem ao Bluetooth.

---

## 7. Código aberto

A EarLoft é software de código aberto. Pode consultar o código-fonte completo no repositório público:
https://github.com/TinyScripter/EarLoft

---

## 8. Contacto e pedidos de informação

Se tiver perguntas, relatórios de erros ou preocupações relativamente a esta política de privacidade, abra um
problema (issue) no repositório público:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
