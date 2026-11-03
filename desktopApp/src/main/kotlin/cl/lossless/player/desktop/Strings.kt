package cl.lossless.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** Idiomas de la app, los mismos cuatro que en Android. */
enum class Lang(val code: String, val nativeName: String) {
    ES("es", "Español"),
    EN("en", "English"),
    FR("fr", "Français"),
    PT("pt", "Português");

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code == code }
        fun system(): Lang = fromCode(Locale.getDefault().language) ?: ES
    }
}

/** Un texto en los cuatro idiomas. [format] reemplaza %1$s, %2$s… como en Android. */
class Str(private val es: String, private val en: String, private val fr: String, private val pt: String) {
    fun get(lang: Lang): String = when (lang) {
        Lang.ES -> es
        Lang.EN -> en
        Lang.FR -> fr
        Lang.PT -> pt
    }
}

val LocalLang = staticCompositionLocalOf { Lang.ES }

@Composable
fun t(s: Str): String = s.get(LocalLang.current)

@Composable
fun t(s: Str, vararg args: Any): String = String.format(s.get(LocalLang.current), *args)

/** Textos de la versión de escritorio. */
object S {
    val songs = Str("Canciones", "Songs", "Titres", "Músicas")
    val albums = Str("Álbumes", "Albums", "Albums", "Álbuns")
    val folders = Str("Carpetas", "Folders", "Dossiers", "Pastas")
    val settings = Str("Ajustes", "Settings", "Réglages", "Ajustes")
    val search = Str("Buscar", "Search", "Rechercher", "Pesquisar")
    val back = Str("Volver", "Back", "Retour", "Voltar")
    val playAll = Str("Reproducir todo", "Play all", "Tout lire", "Tocar tudo")

    val emptyTitle = Str(
        "Agrega tu carpeta de música",
        "Add your music folder",
        "Ajoutez votre dossier de musique",
        "Adicione sua pasta de música",
    )
    val emptyBody = Str(
        "ResuenApp lee FLAC, ALAC, WAV, MP3, AAC y OGG de las carpetas que elijas.",
        "ResuenApp plays FLAC, ALAC, WAV, MP3, AAC and OGG from the folders you choose.",
        "ResuenApp lit les FLAC, ALAC, WAV, MP3, AAC et OGG des dossiers choisis.",
        "O ResuenApp toca FLAC, ALAC, WAV, MP3, AAC e OGG das pastas que você escolher.",
    )
    val addFolder = Str("Agregar carpeta", "Add folder", "Ajouter un dossier", "Adicionar pasta")
    val removeFolder = Str("Quitar", "Remove", "Retirer", "Remover")
    val rescan = Str("Volver a escanear", "Rescan", "Réanalyser", "Escanear de novo")
    val scanning = Str("Escaneando… %1\$d archivos", "Scanning… %1\$d files", "Analyse… %1\$d fichiers", "Escaneando… %1\$d arquivos")
    val trackCount = Str("%1\$d canciones", "%1\$d songs", "%1\$d titres", "%1\$d músicas")
    val noResults = Str("Sin resultados", "No results", "Aucun résultat", "Nenhum resultado")
    val unknownArtist = Str("Artista desconocido", "Unknown artist", "Artiste inconnu", "Artista desconhecido")
    val unknownAlbum = Str("Álbum desconocido", "Unknown album", "Album inconnu", "Álbum desconhecido")
    val variousArtists = Str("Varios artistas", "Various artists", "Artistes divers", "Vários artistas")

    val libraryFolders = Str("Carpetas de música", "Music folders", "Dossiers de musique", "Pastas de música")
    val language = Str("Idioma", "Language", "Langue", "Idioma")
    val languageSystem = Str("Idioma del sistema", "System language", "Langue du système", "Idioma do sistema")
    val chooseFolder = Str("Elige una carpeta de música", "Choose a music folder", "Choisissez un dossier de musique", "Escolha uma pasta de música")

    val output = Str("Salida de audio", "Audio output", "Sortie audio", "Saída de áudio")
    val outputShared = if (IsMac) Str(
        "Core Audio de macOS (modo compartido)",
        "macOS Core Audio (shared mode)",
        "Core Audio de macOS (mode partagé)",
        "Core Audio do macOS (modo compartilhado)",
    ) else Str(
        "Mezclador de Windows (modo compartido)",
        "Windows mixer (shared mode)",
        "Mélangeur Windows (mode partagé)",
        "Mixer do Windows (modo compartilhado)",
    )
    val outputNote = if (IsMac) Str(
        "Esta versión decodifica sin pérdida, pero macOS puede remuestrear si la frecuencia del archivo no coincide con la de la salida (Configuración de Audio MIDI).",
        "This version decodes losslessly, but macOS may resample if the file's sample rate doesn't match the output's (Audio MIDI Setup).",
        "Cette version décode sans perte, mais macOS peut rééchantillonner si la fréquence du fichier ne correspond pas à celle de la sortie (Configuration audio et MIDI).",
        "Esta versão decodifica sem perdas, mas o macOS pode reamostrar se a frequência do arquivo não coincidir com a da saída (Configuração de Áudio e MIDI).",
    ) else Str(
        "Esta primera versión decodifica sin pérdida, pero Windows puede remuestrear. El modo bit-perfect (WASAPI exclusivo) llegará en una próxima versión.",
        "This first version decodes losslessly, but Windows may resample. Bit-perfect mode (WASAPI exclusive) is coming in a later version.",
        "Cette première version décode sans perte, mais Windows peut rééchantillonner. Le mode bit-perfect (WASAPI exclusif) arrivera dans une prochaine version.",
        "Esta primeira versão decodifica sem perdas, mas o Windows pode reamostrar. O modo bit-perfect (WASAPI exclusivo) virá numa próxima versão.",
    )
    val outputFormat = Str("Archivo %1\$s · salida %2\$s", "File %1\$s · output %2\$s", "Fichier %1\$s · sortie %2\$s", "Arquivo %1\$s · saída %2\$s")
    val volume = Str("Volumen", "Volume", "Volume", "Volume")
    val about = Str("Acerca de", "About", "À propos", "Sobre")
    val version = Str("Versión %1\$s", "Version %1\$s", "Version %1\$s", "Versão %1\$s")

    val play = Str("Reproducir", "Play", "Lecture", "Tocar")
    val pause = Str("Pausa", "Pause", "Pause", "Pausar")
    val next = Str("Siguiente", "Next", "Suivant", "Próxima")
    val previous = Str("Anterior", "Previous", "Précédent", "Anterior")
    val shuffleOff = Str("Aleatorio: no", "Shuffle: off", "Aléatoire : non", "Aleatório: não")
    val shuffleOn = Str("Aleatorio: sí", "Shuffle: on", "Aléatoire : oui", "Aleatório: sim")
    val repeatOff = Str("Repetir: no", "Repeat: off", "Répéter : non", "Repetir: não")
    val repeatAll = Str("Repetir todo", "Repeat all", "Tout répéter", "Repetir tudo")
    val repeatOne = Str("Repetir una", "Repeat one", "Répéter un titre", "Repetir uma")
    val nothingPlaying = Str("Nada sonando", "Nothing playing", "Rien en lecture", "Nada tocando")
    val playbackError = Str("No se pudo reproducir: %1\$s", "Couldn't play: %1\$s", "Lecture impossible : %1\$s", "Não foi possível tocar: %1\$s")

    val lyrics = Str("Letra", "Lyrics", "Paroles", "Letra")
    val lyricsLoading = Str("Buscando letra…", "Looking for lyrics…", "Recherche des paroles…", "Procurando letra…")
    val lyricsNone = Str("No se encontró letra", "No lyrics found", "Aucunes paroles trouvées", "Nenhuma letra encontrada")
    val lyricsInstrumental = Str("Instrumental", "Instrumental", "Instrumental", "Instrumental")
    val lyricsOffline = Str(
        "Sin conexión con LRCLIB",
        "Couldn't reach LRCLIB",
        "Impossible de joindre LRCLIB",
        "Sem conexão com o LRCLIB",
    )
    val lyricsServerError = Str(
        "LRCLIB no está respondiendo (saturado o caído, HTTP %1\$s). No es tu conexión; prueba más tarde.",
        "LRCLIB isn't responding (overloaded or down, HTTP %1\$s). It's not your connection; try again later.",
        "LRCLIB ne répond pas (surchargé ou en panne, HTTP %1\$s). Ce n'est pas votre connexion ; réessayez plus tard.",
        "O LRCLIB não está respondendo (sobrecarregado ou fora do ar, HTTP %1\$s). Não é a sua conexão; tente mais tarde.",
    )
    val lyricsRetry = Str("Reintentar", "Retry", "Réessayer", "Tentar de novo")
    val sourceLyricsOvh = Str("Lyrics.ovh", "Lyrics.ovh", "Lyrics.ovh", "Lyrics.ovh")
    val lyricsSource = Str("Fuente: %1\$s", "Source: %1\$s", "Source : %1\$s", "Fonte: %1\$s")
    val sourceSidecar = Str("archivo .lrc", ".lrc file", "fichier .lrc", "arquivo .lrc")
    val sourceEmbedded = Str("etiquetas del archivo", "file tags", "étiquettes du fichier", "tags do arquivo")
    val sourceLrclib = Str("LRCLIB", "LRCLIB", "LRCLIB", "LRCLIB")

    // Rediseño "Fusión Neón"
    val library = Str("Biblioteca", "Library", "Bibliothèque", "Biblioteca")
    val artists = Str("Artistas", "Artists", "Artistes", "Artistas")
    val allTracks = Str("Todas las canciones", "All songs", "Tous les titres", "Todas as músicas")
    val searchHint = Str(
        "Buscar canciones, álbumes, artistas, FLAC…",
        "Search songs, albums, artists, FLAC…",
        "Rechercher titres, albums, artistes, FLAC…",
        "Pesquisar músicas, álbuns, artistas, FLAC…",
    )
    val librarySummary = Str(
        "%1\$d archivos · %2\$s · %3\$d en alta resolución",
        "%1\$d files · %2\$s · %3\$d hi-res",
        "%1\$d fichiers · %2\$s · %3\$d haute résolution",
        "%1\$d arquivos · %2\$s · %3\$d em alta resolução",
    )
    val folderCount = Str("%1\$d carpetas", "%1\$d folders", "%1\$d dossiers", "%1\$d pastas")
    val albumCount = Str("%1\$d álbumes", "%1\$d albums", "%1\$d albums", "%1\$d álbuns")
    val colTitle = Str("Título", "Title", "Titre", "Título")
    val colArtist = Str("Artista", "Artist", "Artiste", "Artista")
    val colAlbum = Str("Álbum", "Album", "Album", "Álbum")
    val colFormat = Str("Formato", "Format", "Format", "Formato")
    val colDuration = Str("Duración", "Duration", "Durée", "Duração")
    val nowPlaying = Str("Reproduciendo", "Now playing", "En lecture", "Tocando agora")
    val queue = Str("Cola (%1\$d)", "Queue (%1\$d)", "File (%1\$d)", "Fila (%1\$d)")
    val upNext = Str("A continuación", "Up next", "À suivre", "A seguir")
    val lyricsSynced = Str("Letra sincronizada", "Synced lyrics", "Paroles synchronisées", "Letra sincronizada")
    val togglePanel = Str("Panel lateral", "Side panel", "Panneau latéral", "Painel lateral")
    val mute = Str("Silenciar", "Mute", "Couper le son", "Silenciar")
    val outputDevice = if (IsMac) Str("macOS · modo compartido", "macOS · shared mode", "macOS · mode partagé", "macOS · modo compartilhado")
        else Str("Windows · modo compartido", "Windows · shared mode", "Windows · mode partagé", "Windows · modo compartilhado")
    val outputIdle = Str("Salida en espera", "Output idle", "Sortie en attente", "Saída em espera")

    // Apariencia (los mismos nombres que en Android)
    val appearance = Str("Apariencia", "Appearance", "Apparence", "Aparência")
    val theme = Str("Tema", "Theme", "Thème", "Tema")
    val themeNight = Str("Noche", "Night", "Nuit", "Noite")
    val themeDark = Str("Oscuro", "Dark", "Sombre", "Escuro")
    val themeLight = Str("Claro", "Light", "Clair", "Claro")
    val accentColor = Str("Color de acento · %1\$s", "Accent color · %1\$s", "Couleur d'accent · %1\$s", "Cor de destaque · %1\$s")
    val accentNeonCyan = Str("Cian neón", "Neon cyan", "Cyan néon", "Ciano neon")
    val accentNeonTeal = Str("Turquesa neón", "Neon teal", "Turquoise néon", "Turquesa neon")
    val accentNeonAmber = Str("Ámbar neón", "Neon amber", "Ambre néon", "Âmbar neon")
    val accentNeonBlue = Str("Azul neón", "Neon blue", "Bleu néon", "Azul neon")
    val accentNeonPink = Str("Rosa neón", "Neon pink", "Rose néon", "Rosa neon")
    val accentBlue = Str("Azul", "Blue", "Bleu", "Azul")
    val accentTeal = Str("Turquesa", "Teal", "Turquoise", "Turquesa")
    val accentGreen = Str("Esmeralda", "Emerald", "Émeraude", "Esmeralda")
    val accentAmber = Str("Ámbar", "Amber", "Ambre", "Âmbar")
    val accentOrange = Str("Naranja", "Orange", "Orange", "Laranja")
    val accentRed = Str("Rojo", "Red", "Rouge", "Vermelho")
    val accentPink = Str("Rosa", "Pink", "Rose", "Rosa")
    val accentPurple = Str("Violeta", "Violet", "Violet", "Violeta")
    val accentSystem = Str("Color del sistema", "System color", "Couleur du système", "Cor do sistema")
    val font = Str("Tipo de letra · %1\$s", "Font · %1\$s", "Police · %1\$s", "Fonte · %1\$s")
    val fontSystem = Str("Sistema", "System", "Système", "Sistema")
    val textSize = Str("Tamaño del texto", "Text size", "Taille du texte", "Tamanho do texto")
    val textSmall = Str("Pequeño", "Small", "Petit", "Pequeno")
    val textNormal = Str("Normal", "Normal", "Normal", "Normal")
    val textLarge = Str("Grande", "Large", "Grand", "Grande")
    val textXLarge = Str("Muy grande", "Extra large", "Très grand", "Muito grande")

    // Etiquetas, carátulas y letras
    val editTags = Str("Editar etiquetas…", "Edit tags…", "Modifier les étiquettes…", "Editar tags…")
    val editAlbum = Str("Editar álbum", "Edit album", "Modifier l'album", "Editar álbum")
    val searchCover = Str("Buscar carátula…", "Find cover…", "Chercher une pochette…", "Buscar capa…")
    val searchLyrics = Str("Buscar letra…", "Find lyrics…", "Chercher les paroles…", "Buscar letra…")
    val fieldTitle = Str("Título", "Title", "Titre", "Título")
    val fieldArtist = Str("Artista", "Artist", "Artiste", "Artista")
    val fieldAlbum = Str("Álbum", "Album", "Album", "Álbum")
    val fieldAlbumArtist = Str("Artista del álbum", "Album artist", "Artiste de l'album", "Artista do álbum")
    val fieldYear = Str("Año", "Year", "Année", "Ano")
    val fieldGenre = Str("Género", "Genre", "Genre", "Gênero")
    val fieldTrack = Str("N.º de pista", "Track no.", "N° de piste", "N.º da faixa")
    val fieldDisc = Str("Disco", "Disc", "Disque", "Disco")
    val mixedValues = Str("Varios valores (se mantienen)", "Multiple values (kept)", "Valeurs multiples (conservées)", "Vários valores (mantidos)")
    val save = Str("Guardar", "Save", "Enregistrer", "Salvar")
    val cancel = Str("Cancelar", "Cancel", "Annuler", "Cancelar")
    val saving = Str("Guardando…", "Saving…", "Enregistrement…", "Salvando…")
    val saveError = Str("No se pudo guardar: %1\$s", "Couldn't save: %1\$s", "Enregistrement impossible : %1\$s", "Não foi possível salvar: %1\$s")
    val searching = Str("Buscando…", "Searching…", "Recherche…", "Procurando…")
    val offline = Str("Sin conexión a internet", "No internet connection", "Pas de connexion Internet", "Sem conexão com a internet")
    val coverFromFile = Str("Desde un archivo…", "From a file…", "Depuis un fichier…", "De um arquivo…")
    val chooseImage = Str("Elige una imagen", "Choose an image", "Choisissez une image", "Escolha uma imagem")
    val coverTarget = Str(
        "Se guardará dentro de %1\$d archivos",
        "It will be saved inside %1\$d files",
        "Elle sera enregistrée dans %1\$d fichiers",
        "Será salva dentro de %1\$d arquivos",
    )
    val mergeHint = Str(
        "Estas canciones están en %1\$d carpetas. Con el mismo álbum y artista (o artista del álbum) se verán como un solo álbum.",
        "These songs are in %1\$d folders. With the same album and artist (or album artist) they'll show up as one album.",
        "Ces titres sont dans %1\$d dossiers. Avec le même album et le même artiste (ou artiste de l'album), ils formeront un seul album.",
        "Estas músicas estão em %1\$d pastas. Com o mesmo álbum e artista (ou artista do álbum) elas aparecerão como um só álbum.",
    )

    // Selección de varias canciones
    val select = Str("Seleccionar", "Select", "Sélectionner", "Selecionar")
    val selectedCount = Str("%1\$d seleccionadas", "%1\$d selected", "%1\$d sélectionnés", "%1\$d selecionadas")
    val selectAll = Str("Seleccionar todo", "Select all", "Tout sélectionner", "Selecionar tudo")
    val clearSelection = Str("Quitar selección", "Clear selection", "Effacer la sélection", "Limpar seleção")
    val editSelected = Str("Asignar álbum y artista…", "Set album and artist…", "Définir l'album et l'artiste…", "Definir álbum e artista…")
    val editSelectedTags = Str(
        "Editar etiquetas de %1\$d canciones…",
        "Edit tags of %1\$d songs…",
        "Modifier les étiquettes de %1\$d titres…",
        "Editar tags de %1\$d músicas…",
    )
    val selectHint = Str(
        "%1\$s+clic o el cuadro junto al número para seleccionar varias canciones",
        "%1\$s+click or the box next to the number to select several songs",
        "%1\$s+clic ou la case à côté du numéro pour sélectionner plusieurs titres",
        "%1\$s+clique ou a caixa ao lado do número para selecionar várias músicas",
    )
    val lyricsPlain = Str("Solo texto", "Plain text", "Texte seul", "Só texto")
    val useThis = Str("Usar", "Use", "Utiliser", "Usar")

    // Panel de carátula y letra
    val panelSide = Str("Panel de reproducción", "Now playing panel", "Panneau de lecture", "Painel de reprodução")
    val sideRight = Str("A la derecha", "On the right", "À droite", "À direita")
    val sideLeft = Str("A la izquierda", "On the left", "À gauche", "À esquerda")
    val swapSide = Str("Cambiar de lado", "Switch side", "Changer de côté", "Trocar de lado")
    val collapseSidebar = Str("Contraer biblioteca", "Collapse library", "Réduire la bibliothèque", "Recolher biblioteca")
    val expandSidebar = Str("Expandir biblioteca", "Expand library", "Développer la bibliothèque", "Expandir biblioteca")

    // Listas de reproducción
    val playlists = Str("Listas", "Playlists", "Playlists", "Playlists")
    val playlistCount = Str("%1\$d listas", "%1\$d playlists", "%1\$d playlists", "%1\$d playlists")
    val newPlaylist = Str("Nueva lista", "New playlist", "Nouvelle playlist", "Nova playlist")
    val playlistName = Str("Nombre de la lista", "Playlist name", "Nom de la playlist", "Nome da playlist")
    val create = Str("Crear", "Create", "Créer", "Criar")
    val addToPlaylist = Str("Añadir a lista…", "Add to playlist…", "Ajouter à une playlist…", "Adicionar à playlist…")
    val addedToPlaylist = Str("Añadida a %1\$s", "Added to %1\$s", "Ajouté à %1\$s", "Adicionada a %1\$s")
    val renamePlaylist = Str("Renombrar", "Rename", "Renommer", "Renomear")
    val deletePlaylist = Str("Eliminar lista", "Delete playlist", "Supprimer la playlist", "Excluir playlist")
    val deletePlaylistConfirm = Str(
        "¿Eliminar \"%1\$s\"? Las canciones no se borran.",
        "Delete \"%1\$s\"? The songs themselves are kept.",
        "Supprimer « %1\$s » ? Les titres sont conservés.",
        "Excluir \"%1\$s\"? As músicas não são apagadas.",
    )
    val removeFromPlaylist = Str("Quitar de la lista", "Remove from playlist", "Retirer de la playlist", "Remover da playlist")
    val saveQueueAsPlaylist = Str("Guardar cola como lista", "Save queue as playlist", "Enregistrer la file comme playlist", "Salvar fila como playlist")
    val playlistEmpty = Str(
        "Esta lista está vacía. Añade canciones con clic derecho → Añadir a lista.",
        "This playlist is empty. Add songs with right-click → Add to playlist.",
        "Cette playlist est vide. Ajoutez des titres avec clic droit → Ajouter à une playlist.",
        "Esta playlist está vazia. Adicione músicas com clique direito → Adicionar à playlist.",
    )
    val noPlaylists = Str(
        "Aún no tienes listas. Crea una aquí o con clic derecho sobre una canción.",
        "No playlists yet. Create one here or by right-clicking a song.",
        "Aucune playlist pour l'instant. Créez-en une ici ou avec un clic droit sur un titre.",
        "Nenhuma playlist ainda. Crie uma aqui ou com clique direito numa música.",
    )
    val trackMissing = Str("No encontrada", "Not found", "Introuvable", "Não encontrada")
    val dragToReorder = Str("Arrastra para reordenar", "Drag to reorder", "Glisser pour réorganiser", "Arraste para reordenar")

    // Mini reproductor
    val miniPlayer = Str("Mini reproductor", "Mini player", "Mini lecteur", "Mini player")
    val fullView = Str("Vista completa", "Full view", "Vue complète", "Visualização completa")
    val alwaysOnTop = Str(
        "Siempre encima de las demás ventanas",
        "Always on top of other windows",
        "Toujours au-dessus des autres fenêtres",
        "Sempre acima das outras janelas",
    )

    // Ecualizador (mismos textos que en Android)
    val equalizer = Str("Ecualizador", "Equalizer", "Égaliseur", "Equalizador")
    val eqEnabled = Str("Activado", "On", "Activé", "Ativado")
    val eqBypass = Str("Desactivado (bypass)", "Off (bypass)", "Désactivé (bypass)", "Desativado (bypass)")
    val eqPreamp = Str("Preamp", "Preamp", "Préampli", "Pré-amp")
    val eqPreampHint = Str(
        "Bájalo si subes varias bandas, para evitar saturación",
        "Lower it if you boost several bands, to avoid clipping",
        "Baissez-le si vous montez plusieurs bandes, pour éviter la saturation",
        "Abaixe se aumentar várias bandas, para evitar saturação",
    )
    val eqReset = Str("Restablecer", "Reset", "Réinitialiser", "Redefinir")
    val eqBandsHint = Str(
        "dB por banda · doble clic en una banda para volverla a 0",
        "dB per band · double-click a band to reset it to 0",
        "dB par bande · double-cliquez sur une bande pour la remettre à 0",
        "dB por banda · clique duas vezes numa banda para voltar a 0",
    )
    val eqNoteBypass = Str(
        "Con el ecualizador apagado o en plano, el audio pasa sin ninguna modificación.",
        "With the equalizer off or flat, the audio passes through unmodified.",
        "Avec l'égaliseur désactivé ou à plat, l'audio passe sans aucune modification.",
        "Com o equalizador desligado ou plano, o áudio passa sem nenhuma modificação.",
    )
    val eqNoteProcessing = Str(
        "El ecualizador procesa el audio dentro de la app, antes de la salida. Desactívalo para escuchar el archivo sin alterar.",
        "The equalizer processes the audio inside the app, before the output. Turn it off to hear the file unaltered.",
        "L'égaliseur traite l'audio dans l'app, avant la sortie. Désactivez-le pour écouter le fichier sans altération.",
        "O equalizador processa o áudio dentro do app, antes da saída. Desative-o para ouvir o arquivo sem alterações.",
    )
    val eqPresetFlat = Str("Plano", "Flat", "Plat", "Plano")
    val eqPresetRock = Str("Rock", "Rock", "Rock", "Rock")
    val eqPresetPop = Str("Pop", "Pop", "Pop", "Pop")
    val eqPresetJazz = Str("Jazz", "Jazz", "Jazz", "Jazz")
    val eqPresetClassical = Str("Clásica", "Classical", "Classique", "Clássica")
    val eqPresetDance = Str("Electrónica", "Electronic", "Électronique", "Eletrônica")
    val eqPresetHiphop = Str("Hip-Hop", "Hip-Hop", "Hip-Hop", "Hip-Hop")
    val eqPresetAcoustic = Str("Acústico", "Acoustic", "Acoustique", "Acústico")
    val eqPresetBass = Str("Realce de graves", "Bass booster", "Graves renforcés", "Realce de graves")
    val eqPresetVocal = Str("Voz / Podcast", "Vocal / Podcast", "Voix / Podcast", "Voz / Podcast")
    val eqPresetCustom = Str("Personalizado", "Custom", "Personnalisé", "Personalizado")

    // Atajos de teclado
    val shortcuts = Str("Atajos de teclado", "Keyboard shortcuts", "Raccourcis clavier", "Atalhos de teclado")
    val shortcutPlayPause = Str("Reproducir / pausa", "Play / pause", "Lecture / pause", "Tocar / pausar")
    val shortcutStop = Str("Detener", "Stop", "Arrêter", "Parar")
    val shortcutNext = Str("Siguiente canción", "Next song", "Titre suivant", "Próxima música")
    val shortcutPrevious = Str("Canción anterior", "Previous song", "Titre précédent", "Música anterior")
    val shortcutForward = Str("Adelantar 10 s", "Forward 10 s", "Avancer de 10 s", "Avançar 10 s")
    val shortcutRewind = Str("Retroceder 10 s", "Rewind 10 s", "Reculer de 10 s", "Voltar 10 s")
    val shortcutNone = Str("Sin tecla", "No key", "Aucune touche", "Sem tecla")
    val shortcutPress = Str("Pulsa una tecla…", "Press a key…", "Appuyez sur une touche…", "Pressione uma tecla…")
    val shortcutSpace = Str("Espacio", "Space", "Espace", "Espaço")
    val shortcutHint = Str(
        "Haz clic en un atajo y pulsa la tecla nueva. Esc cancela; Supr lo deja sin tecla. Funcionan con la ventana de ResuenApp activa.",
        "Click a shortcut and press the new key. Esc cancels; Delete leaves it without a key. They work while the ResuenApp window is active.",
        "Cliquez sur un raccourci et appuyez sur la nouvelle touche. Échap annule ; Suppr le laisse sans touche. Ils fonctionnent quand la fenêtre de ResuenApp est active.",
        "Clique num atalho e pressione a nova tecla. Esc cancela; Delete deixa sem tecla. Funcionam com a janela do ResuenApp ativa.",
    )
    val shortcutReset = Str("Restablecer atajos", "Reset shortcuts", "Réinitialiser les raccourcis", "Redefinir atalhos")
    val mediaKeys = Str(
        "Teclas multimedia del teclado (▶︎⏸ ⏹ ⏭ ⏮), también con la app en segundo plano",
        "Keyboard media keys (▶︎⏸ ⏹ ⏭ ⏮), also with the app in the background",
        "Touches multimédia du clavier (▶︎⏸ ⏹ ⏭ ⏮), même avec l'app en arrière-plan",
        "Teclas multimídia do teclado (▶︎⏸ ⏹ ⏭ ⏮), também com o app em segundo plano",
    )
    val mediaKeysMac = Str(
        "macOS pedirá permiso de Accesibilidad para ResuenApp (Ajustes del Sistema → Privacidad y seguridad → Accesibilidad).",
        "macOS will ask for Accessibility permission for ResuenApp (System Settings → Privacy & Security → Accessibility).",
        "macOS demandera l'autorisation d'Accessibilité pour ResuenApp (Réglages Système → Confidentialité et sécurité → Accessibilité).",
        "O macOS vai pedir permissão de Acessibilidade para o ResuenApp (Ajustes do Sistema → Privacidade e Segurança → Acessibilidade).",
    )

    // Actualizaciones
    val updateCheck = Str("Buscar actualizaciones", "Check for updates", "Rechercher des mises à jour", "Procurar atualizações")
    val updateChecking = Str("Buscando versiones nuevas…", "Checking for new versions…", "Recherche de nouvelles versions…", "Procurando versões novas…")
    val updateUpToDate = Str("Tienes la versión más reciente", "You have the latest version", "Vous avez la dernière version", "Você tem a versão mais recente")
    val updateAvailable = Str("Versión %1\$s disponible", "Version %1\$s available", "Version %1\$s disponible", "Versão %1\$s disponível")
    val updatePill = Str("Actualizar a %1\$s", "Update to %1\$s", "Mettre à jour vers %1\$s", "Atualizar para %1\$s")
    val updateInstall = Str("Descargar e instalar", "Download and install", "Télécharger et installer", "Baixar e instalar")
    val updateDownloading = Str("Descargando… %1\$d %%", "Downloading… %1\$d %%", "Téléchargement… %1\$d %%", "Baixando… %1\$d %%")
    val updateFailed = Str("No se pudo actualizar: %1\$s", "Update failed: %1\$s", "Échec de la mise à jour : %1\$s", "Não foi possível atualizar: %1\$s")
}
