package cl.lossless.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.DesktopDrive
import cl.lossless.player.desktop.LocalLang
import cl.lossless.player.desktop.Player
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.t
import cl.lossless.player.drive.DriveAuthException
import cl.lossless.player.drive.DriveClient
import cl.lossless.player.drive.DriveItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una carpeta abierta en el explorador de Drive. */
private data class DriveFolder(val id: String, val name: String)

/**
 * Explorador de Google Drive: Mi unidad o Compartido conmigo, carpetas y canciones. Al tocar una
 * canción se descarga a la caché y suena; las siguientes de la carpeta se suman a la cola.
 */
@Composable
fun DriveScreen(player: Player, currentPath: String?, playing: Boolean) {
    val account = DesktopDrive.account
    when {
        !DesktopDrive.configured -> DriveMessage(t(S.driveNotConfigured))
        account == null -> DriveConnect()
        else -> DriveBrowser(player, currentPath, playing, account.email.ifBlank { account.name })
    }
}

@Composable
private fun DriveConnect() {
    val scope = rememberCoroutineScope()
    val lang = LocalLang.current
    var error by remember { mutableStateOf<String?>(null) }
    val errorFormat = t(S.driveError, "%s")
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DriveBadge()
        Spacer(Modifier.height(20.dp))
        Text(t(S.driveConnectTitle), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Neon.TextStrong, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(t(S.driveConnectBody), color = Neon.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        Spacer(Modifier.height(24.dp))
        if (DesktopDrive.signingIn) {
            Text(t(S.driveWaiting), color = Neon.Accent, fontSize = 13.sp)
        } else {
            PrimaryButton(Icons.Rounded.Cloud, t(S.driveConnect)) {
                error = null
                scope.launch {
                    try {
                        DesktopDrive.signIn(lang)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = errorFormat.format(e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        error?.let { Text(it, color = Neon.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun DriveBrowser(player: Player, currentPath: String?, playing: Boolean, who: String) {
    val scope = rememberCoroutineScope()
    val myDrive = t(S.driveMyDrive)
    val shared = t(S.driveShared)
    var stack by remember { mutableStateOf(listOf(DriveFolder(DriveClient.ROOT, myDrive))) }
    var items by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    val expired = t(S.driveSessionExpired)
    val errorFormat = t(S.driveError, "%s")
    fun describe(e: Throwable) = if (e is DriveAuthException) expired else errorFormat.format(e.message ?: e.javaClass.simpleName)

    val folder = stack.last()
    LaunchedEffect(folder.id, reload) {
        loading = true
        error = null
        try {
            items = withContext(Dispatchers.IO) { DesktopDrive.client.list(folder.id, DesktopDrive.audioExtensions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            items = emptyList()
            error = describe(e)
        }
        loading = false
    }
    val download = DesktopDrive.download
    LaunchedEffect(download == null) { cacheBytes = withContext(Dispatchers.IO) { DesktopDrive.cache.sizeBytes() } }

    val files = remember(items) { items.filterNot { it.isFolder } }
    fun play(file: DriveItem) {
        error = null
        DesktopDrive.play(scope, files, files.indexOf(file).coerceAtLeast(0), folder.name, player) { e -> error = describe(e) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            ViewHeader(
                title = folder.name,
                eyebrow = "${t(S.drive)} · $who",
                eyebrowIcon = Icons.Rounded.Cloud,
                summary = if (loading) t(S.driveLoading) else t(S.trackCount, files.size),
                onBack = if (stack.size > 1) ({ stack = stack.dropLast(1) }) else null,
                action = { if (files.isNotEmpty()) PrimaryButton(Icons.Rounded.PlayArrow, t(S.playAll)) { play(files.first()) } },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(myDrive, selected = stack.first().id == DriveClient.ROOT) { stack = listOf(DriveFolder(DriveClient.ROOT, myDrive)) }
                Pill(shared, selected = stack.first().id == DriveClient.SHARED) { stack = listOf(DriveFolder(DriveClient.SHARED, shared)) }
                Spacer(Modifier.weight(1f))
                Text(t(S.driveCache, formatBytes(cacheBytes)), color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
                GhostButton(Icons.Rounded.Delete, t(S.driveClearCache)) {
                    scope.launch(Dispatchers.IO) {
                        DesktopDrive.cancelDownloads()
                        DesktopDrive.cache.clear()
                        cacheBytes = DesktopDrive.cache.sizeBytes()
                    }
                }
                GhostButton(Icons.AutoMirrored.Rounded.Logout, t(S.driveSignOut)) { scope.launch { DesktopDrive.signOut() } }
            }
        }
        if (download != null) item {
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    t(S.driveDownloading, download.index, download.total, download.name),
                    fontFamily = Mono, fontSize = 11.sp, color = Neon.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { download.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp),
                    color = Neon.Accent,
                    trackColor = Neon.Border,
                )
            }
        }
        error?.let { message -> item { Text(message, color = Neon.Amber, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp)) } }
        if (!loading && error == null && items.isEmpty()) item {
            Text(t(S.driveEmpty), color = Neon.Muted, modifier = Modifier.padding(24.dp))
        }
        itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
            if (item.isFolder) {
                DriveRow(Icons.Rounded.Folder, item.name, detail = null, highlighted = false) {
                    stack = stack + DriveFolder(item.id, item.name)
                }
            } else {
                val current = currentPath == DesktopDrive.cache.fileFor(item).path
                DriveRow(Icons.Rounded.MusicNote, item.name, detail = formatBytes(item.sizeBytes), highlighted = current, playing = current && playing) {
                    play(item)
                }
            }
        }
    }
}

@Composable
private fun DriveRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String?,
    highlighted: Boolean,
    playing: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlighted -> Neon.Accent.copy(alpha = 0.10f)
                    hovered -> Neon.Hover.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (highlighted) PlayingBars(playing) else Icon(icon, null, tint = Neon.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            name, Modifier.weight(1f),
            color = if (highlighted) Neon.Accent else if (hovered) Neon.TextStrong else Neon.Text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) Text(detail, color = Neon.Muted, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable
private fun DriveBadge() {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(Neon.Accent.copy(alpha = 0.10f))
            .border(1.dp, Neon.Accent.copy(alpha = 0.3f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Cloud, null, tint = Neon.Accent, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun DriveMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DriveBadge()
        Spacer(Modifier.height(20.dp))
        Text(text, color = Neon.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 MB"
}
