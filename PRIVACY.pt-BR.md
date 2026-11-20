# Política de Privacidade do EarLoft

**Última atualização:** 20 de novembro de 2026  
**ID do aplicativo:** de.kleinabersmart.earloft (smartphone e relógio Wear OS)

## Visão geral

O EarLoft é um player de código aberto para a sua própria coleção: músicas do seu servidor Navidrome, Subsonic,
Jellyfin ou Emby, podcasts de feeds RSS e Atom, audiolivros do seu servidor Audiobookshelf ou do ARD Sounds e arquivos
de áudio do seu dispositivo.
Ele foi criado tendo a privacidade como princípio fundamental. O desenvolvedor não rastreia você, não coleta dados
pessoais e não opera nenhum servidor próprio. Não é preciso criar conta.

---

## 1. Coleta de dados e rastreamento

* **Nenhum dado pessoal coletado pelo desenvolvedor:** o EarLoft não envia seu nome, localização, contatos,
  identificadores do dispositivo, biblioteca ou histórico de audição ao desenvolvedor nem a qualquer outra pessoa.
* **Sem análises, anúncios ou rastreadores:** o app não contém bibliotecas de análise, serviços de relatório de falhas,
  SDKs de rastreamento nem redes de publicidade.

---

## 2. Atividade de rede

O EarLoft se conecta à internet apenas para os recursos que você usa. Cada conexão vai diretamente do seu dispositivo
(ou relógio) ao respectivo serviço:

* **Seus próprios servidores:** os servidores Navidrome / Subsonic, Jellyfin, Emby e Audiobookshelf que você adicionar.
  O app sincroniza sua biblioteca, faz streaming e download de arquivos e envia de volta o que você faz no app:
  favoritos, playlists, contagens de reprodução e progresso de audição. Se você mantiver o scrobbling ativado
  (**Configurações → Música**), as músicas tocadas são informadas ao seu servidor de música, que pode repassá-las a
  serviços configurados nele (por exemplo, Last.fm ou ListenBrainz).
* **Feeds de podcast:** os feeds em que você se inscreve e os sites que hospedam seus episódios e imagens, operados
  pelos respectivos editores.
* **Diretórios de podcasts (opcionais, desativados por padrão):** quando você ativa um diretório (em
  **Configurações → Podcasts** ou pela engrenagem em **Adicionar podcast**), ele é consultado sobre podcasts populares
  assim que você abre **Adicionar podcast** (seu país e idioma são enviados) e recebe seus termos de pesquisa quando
  você pesquisa: Apple Podcasts (API de pesquisa da Apple), Podcast Index (API do podcastindex.org), fyyd (API do
  fyyd.de; apenas seus termos de pesquisa, ele não é consultado sobre podcasts populares) e ARD Sounds (interface
  pública da ARD). Programas do ARD Sounds sem feed próprio também são atualizados por essa interface.
* **Audiolivros do ARD Sounds (opcionais, desativados por padrão):** quando você os ativa em
  **Configurações → Livros**, os livros e radioteatros que você navega, pesquisa, toca ou baixa vêm da interface
  pública da ARD. Os livros da sua biblioteca e os programas que você segue são verificados lá regularmente, também em
  segundo plano (cerca de a cada 12 horas, no Wi-Fi), em busca de novas partes, novos livros e da data de expiração.
  Seus termos de pesquisa e esses livros são enviados à ARD.
* **Imagens da internet (opcionais, desativadas por padrão):** fotos de artistas do Deezer (os nomes dos artistas são
  enviados), capas de álbuns ausentes do MusicBrainz e do Cover Art Archive (o título do álbum e o artista são
  enviados), imagens de autores ausentes do Audnexus (os nomes dos autores são enviados).
* **Letras do LRCLIB (opcionais, desativadas por padrão):** quando você ativa
  **Configurações → Música → Buscar letras on-line**, o título, o artista e a duração da música exibida são enviados
  ao LRCLIB (lrclib.net) para encontrar letras ausentes ou sincronizadas.
* **Login com OpenID no Audiobookshelf (opcional):** a página de login do provedor de identidade do seu servidor abre
  no seu navegador. O EarLoft recebe apenas os tokens do seu servidor Audiobookshelf.
* **Chromecast (opcional):** quando você transmite, o dispositivo de transmissão na sua rede recebe o endereço da
  música e a toca diretamente do seu servidor. Faixas que estão apenas no seu smartphone (ou cujo servidor o
  dispositivo de transmissão não consegue acessar) são carregadas a partir do smartphone: durante a transmissão, o
  EarLoft executa um pequeno servidor web no seu Wi-Fi que fornece apenas as faixas e capas desta sessão de
  transmissão, por HTTP simples, em endereços com um segredo que muda a cada sessão. Outros apps de transmissão na sua
  rede podem ver esses endereços. Enquanto o dispositivo de transmissão carrega a partir do smartphone, o app mantém o
  smartphone e o Wi-Fi dele ativos.

O EarLoft não entra em contato com nenhum outro serviço. Os links que você compartilha apontam para fontes abertas
(MusicBrainz, o site e o feed do podcast, Open Library), nunca para seus próprios servidores; o próprio app não acessa
esses sites quando você compartilha.

---

## 3. Armazenamento e segurança

* **Armazenamento local:** sua biblioteca, inscrições, downloads, progresso de audição, estatísticas e configurações
  são armazenados no seu dispositivo (e no seu relógio, para o que você carregar nele).
* **Credenciais:** senhas de servidores, chaves de API e tokens de login são armazenados com criptografia pelo Android
  Keystore. No Jellyfin e no Emby, apenas o token de login é mantido, não a senha.
* **Conexões não criptografadas:** servidores e feeds podem ser acessados por HTTP simples (por exemplo, em casa). O
  app avisa você antes de usar uma conexão não criptografada com um servidor.

---

## 4. Backups

* **Backup do Android:** se você usa o backup do Android na sua Conta do Google, ele contém suas configurações e o que
  nenhum servidor sabe: seus servidores *sem* as senhas, inscrições, progresso de audição, playlists locais,
  estatísticas de audição e quais episódios foram baixados. Ele nunca contém o banco de dados da biblioteca, arquivos
  baixados, senhas, chaves de API nem tokens de login. Os endereços dos feeds são mantidos como estão: se um editor
  colocar uma chave pessoal no endereço de um feed pago, esse endereço faz parte do backup.
* **Arquivo de backup:** **Configurações → Geral → Backup** cria um arquivo com o mesmo conteúdo, que você guarda por
  conta própria, também sem senhas, chaves de API ou logins, mas com os endereços dos feeds (veja acima). Onde você
  armazena ou compartilha esse arquivo é decisão sua.

---

## 5. Wear OS, Android Auto e outros apps

* **Relógio:** o smartphone e o relógio trocam dados pela camada de dados do Wear OS (Google Play Services): as
  configurações dos seus servidores, incluindo as credenciais de que o relógio precisa, o que você escolhe carregar no
  relógio, comandos de reprodução e progresso de audição. Depois, o próprio relógio se conecta aos seus servidores e
  feeds de podcast e carrega livros do ARD Sounds diretamente da ARD.
* **Android Auto:** sua biblioteca é mostrada no carro pelo Android Auto no seu smartphone.
* **Controles de mídia:** como todo app de mídia, o EarLoft compartilha o que está tocando (título, artista, capa) com
  o sistema para a notificação, a tela de bloqueio, o widget e os dispositivos conectados. Outros apps podem apenas
  controlar a reprodução; eles não podem navegar pela sua biblioteca nem alterar sua fila.

---

## 6. Permissões

Todas as permissões são solicitadas apenas quando necessário, e o app funciona com o que você permitir:

* **Música e áudio** (`READ_MEDIA_AUDIO`, em versões mais antigas do Android, acesso ao armazenamento): apenas quando
  você ativa músicas, podcasts ou livros deste dispositivo, para ler seus arquivos de áudio.
* **Notificações** (`POST_NOTIFICATIONS`): para novos conteúdos e o progresso dos downloads.
* **Dispositivos na sua rede local** (`ACCESS_LOCAL_NETWORK`, Android 17 e mais recentes): para acessar servidores na
  sua rede doméstica.
* **Internet, estado da rede, serviços em primeiro plano** para reprodução, downloads e transferências para o relógio.
* **Manter o dispositivo ativo** (`WAKE_LOCK`): para que a reprodução, os downloads e a transmissão continuem com a
  tela desligada.
* **Vibração** (`VIBRATE`): retorno curto, por exemplo, quando o timer de suspensão é estendido ao agitar.
* No relógio, também **alterar o estado da rede** (`CHANGE_NETWORK_STATE`): para solicitar Wi-Fi para downloads.
* **Iniciar após uma reinicialização** (`RECEIVE_BOOT_COMPLETED`): para que sincronizações e downloads programados
  continuem depois que o dispositivo for reiniciado.

O timer de suspensão de livros e episódios pode ser estendido agitando o smartphone; para isso, o sensor de movimento
(acelerômetro) é lido apenas durante o último minuto do timer e até dois minutos depois que ele terminar. Os valores
nunca saem do dispositivo. Você pode desativar isso nas configurações.

O EarLoft não solicita acesso à localização, ao microfone, à câmera, aos contatos, ao telefone nem ao Bluetooth.

---

## 7. Código aberto

O EarLoft é um software de código aberto. Você pode analisar o código-fonte completo no repositório público:
https://github.com/TinyScripter/EarLoft

---

## 8. Contato e dúvidas

Se você tiver dúvidas, relatórios de bugs ou preocupações relacionadas a esta política de privacidade, abra uma issue
no repositório público:

* **GitHub Issues:** https://github.com/TinyScripter/EarLoft/issues
