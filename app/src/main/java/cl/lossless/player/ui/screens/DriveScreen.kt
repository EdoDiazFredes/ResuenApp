package cl.lossless.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.lossless.player.R
import cl.lossless.player.drive.DriveClient
import cl.lossless.player.drive.DriveItem
import cl.lossless.player.ui.DriveUiState
import cl.lossless.player.ui.components.EmptyState
import cl.lossless.player.ui.components.ScreenHeader

/**
 * Explorador de Google Drive: Mi unidad o Compartido conmigo, carpetas y canciones. Al tocar una
 * canción se descarga a la caché y suena; las siguientes de la carpeta se suman a la cola.
 */
@Composable
fun DriveScreen(
    state: DriveUiState,
    currentMediaId: String?,
    uriFor: (DriveItem) -> String,
    onShown: () -> Unit,
    onConnect: () -> Unit,
    onSignOut: () -> Unit,
    onRoot: (String) -> Unit,
    onOpen: (DriveItem) -> Unit,
    onBack: () -> Boolean,
    onReload: () -> Unit,
    onPlay: (DriveItem) -> Unit,
    onClearCache: () -> Unit,
) {
    LaunchedEffect(state.account) { onShown() }
    val account = state.account
    if (account == null) {
        EmptyState(
            icon = if (state.error != null) Icons.Rounded.CloudOff else Icons.Rounded.Cloud,
            title = stringResource(R.string.drive_connect_title),
            message = state.error ?: stringResource(R.string.drive_connect_body),
            actionLabel = stringResource(if (state.connecting) R.string.drive_connecting else R.string.drive_connect),
            onAction = { if (!state.connecting) onConnect() },
        )
        return
    }

    BackHandler(enabled = state.stack.size > 1) { onBack() }
    val folder = state.folder
    val files = state.files

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = folder?.name ?: stringResource(R.string.drive_my_drive),
            trailingText = account.email,
            onBack = if (state.stack.size > 1) ({ onBack() }) else null,
            actions = {
                IconButton(onClick = onReload) { Icon(Icons.Rounded.Refresh, contentDescription = null) }
                IconButton(onClick = onSignOut) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = stringResource(R.string.drive_sign_out))
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val root = state.stack.firstOrNull()?.id
            FilterChip(
                selected = root == DriveClient.ROOT,
                onClick = { onRoot(DriveClient.ROOT) },
                label = { Text(stringResource(R.string.drive_my_drive)) },
            )
            FilterChip(
                selected = root == DriveClient.SHARED,
                onClick = { onRoot(DriveClient.SHARED) },
                label = { Text(stringResource(R.string.drive_shared)) },
            )
            FilterChip(
                selected = false,
                onClick = onClearCache,
                label = { Text(stringResource(R.string.drive_cache, formatBytes(state.cacheBytes))) },
                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.drive_clear_cache)) },
            )
        }

        state.download?.let { d ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.drive_downloading, d.index, d.total, d.name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.items.isEmpty() && state.error == null -> EmptyState(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.drive_empty),
                message = "",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (files.isNotEmpty()) item(key = "actions") {
                    FilledTonalButton(onClick = { onPlay(files.first()) }) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_folders_play))
                    }
                }
                items(state.items, key = { it.id }) { item ->
                    if (item.isFolder) {
                        FolderCard(name = item.name, path = null, detail = "") { onOpen(item) }
                    } else {
                        DriveFileCard(item, current = uriFor(item) == currentMediaId) { onPlay(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveFileCard(item: DriveItem, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (current) colors.primaryContainer else colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (current) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                null,
                tint = colors.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
            )
            Text(
                "${item.extension.uppercase()} · ${formatBytes(item.sizeBytes)}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "${bytes / 1024} KB"
    else -> "0 MB"
}
