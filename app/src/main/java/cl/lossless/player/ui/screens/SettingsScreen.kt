package cl.lossless.player.ui.screens

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cl.lossless.player.ui.components.FormatBadge
import cl.lossless.player.ui.components.ResuenLogo
import androidx.compose.ui.platform.LocalContext
import cl.lossless.player.ui.theme.AccentColor
import cl.lossless.player.ui.theme.AppFont
import cl.lossless.player.ui.theme.TextSize
import cl.lossless.player.sync.NetworkMode
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.FilterChip
import cl.lossless.player.ui.theme.isDark
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cl.lossless.player.library.FolderExclusions
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cl.lossless.player.R
import cl.lossless.player.ui.LibraryUiState
import cl.lossless.player.ui.components.ScreenHeader
import cl.lossless.player.ui.theme.ThemeMode
import cl.lossless.player.update.AppUpdater
import cl.lossless.player.update.UpdateState
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    state: LibraryUiState,
    audioSource: String,
    audioOutput: String,
    onPickFolder: () -> Unit,
    onShowWholeLibrary: () -> Unit,
    onRescan: () -> Unit,
    onFetchCovers: () -> Unit,
    onCancelFetch: () -> Unit,
    onSaveCoversInFolder: (Boolean) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAccent: (AccentColor) -> Unit,
    onTintedText: (Boolean) -> Unit,
    equalizerSummary: String,
    onOpenEqualizer: () -> Unit,
    onOpenExclusions: () -> Unit,
    lyricsOnline: Boolean,
    onLyricsOnline: (Boolean) -> Unit,
    lyricsAutoSync: Boolean,
    onLyricsAutoSync: (Boolean) -> Unit,
    alignServerUrl: String,
    onOpenAlignServer: () -> Unit,
    onClearLyricsCache: () -> Unit,
    onFont: (AppFont) -> Unit,
    onTextSize: (TextSize) -> Unit,
    onNetworkMode: (NetworkMode) -> Unit,
    onClearSyncQueue: () -> Unit,
    onSwipeToSkip: (Boolean) -> Unit,
    onMediaKeysSkip: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        BrandHeader()

        SettingsSection(stringResource(R.string.settings_section_library), Icons.Rounded.Folder) {
            SettingRow(
                title = stringResource(R.string.settings_location),
                subtitle = state.location?.displayPath ?: stringResource(R.string.settings_location_all_device),
                monospace = true,
            )
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilledTonalButton(onClick = onPickFolder) { Text(stringResource(R.string.settings_pick_folder)) }
                if (state.location != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onShowWholeLibrary) { Text(stringResource(R.string.settings_use_all_music)) }
                }
            }
            val suggestions = remember(state.allTracks, state.excludedFolders) {
                FolderExclusions.suggestions(state.allTracks, state.excludedFolders)
            }
            SettingRow(
                title = stringResource(R.string.settings_excluded_folders),
                subtitle = buildString {
                    append(
                        if (state.excludedFolders.isEmpty()) stringResource(R.string.settings_excluded_none)
                        else pluralStringResource(
                            R.plurals.settings_excluded_count, state.excludedFolders.size, state.excludedFolders.size,
                        )
                    )
                    if (suggestions.isNotEmpty()) {
                        append(" · ")
                        append(pluralStringResource(R.plurals.settings_excluded_suggestions, suggestions.size, suggestions.size))
                    }
                },
                trailing = { Icon(Icons.Rounded.FolderOff, null) },
                onClick = onOpenExclusions,
            )
            SettingRow(
                title = stringResource(R.string.settings_rescan),
                subtitle = pluralStringResource(R.plurals.settings_tracks_count, state.tracks.size, state.tracks.size) +
                    " · " + pluralStringResource(R.plurals.settings_albums_count, state.albums.size, state.albums.size),
                trailing = { Icon(Icons.Rounded.Refresh, null) },
                onClick = onRescan,
            )
        }

        SettingsSection(stringResource(R.string.settings_section_covers), Icons.Rounded.Image) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (state.coverTaskRunning) {
                    OutlinedButton(onClick = onCancelFetch) { Text(stringResource(R.string.settings_covers_stop)) }
                } else {
                    OutlinedButton(onClick = onFetchCovers, enabled = state.albums.isNotEmpty()) {
                        Text(stringResource(R.string.settings_covers_fetch_missing))
                    }
                }
                state.coverTaskStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.coverTaskRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            SettingRow(
                title = stringResource(R.string.settings_covers_save_in_folder),
                subtitle = stringResource(
                    if (state.location == null) R.string.settings_covers_requires_folder else R.string.settings_covers_also_cache
                ),
                trailing = {
                    Switch(
                        checked = state.saveCoversInFolder,
                        onCheckedChange = onSaveCoversInFolder,
                        enabled = state.location != null,
                    )
                },
            )
        }

        LanguageSection()

        SettingsSection(stringResource(R.string.settings_section_appearance), Icons.Rounded.Palette) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        icon = {},
                    ) { Text(stringResource(mode.labelRes), maxLines = 1, softWrap = false) }
                }
            }
            Text(
                stringResource(R.string.settings_text_size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextSize.entries.forEachIndexed { i, size ->
                    SegmentedButton(
                        selected = state.textSize == size,
                        onClick = { onTextSize(size) },
                        shape = SegmentedButtonDefaults.itemShape(i, TextSize.entries.size),
                        icon = {},
                    ) { Text(stringResource(size.labelRes), maxLines = 1, softWrap = false) }
                }
            }
            Text(
                stringResource(R.string.settings_font_with_value, state.font.label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            FontPicker(selected = state.font, onSelect = onFont)
            Text(
                stringResource(
                    R.string.settings_accent_with_value,
                    if (state.dynamicColor) "Material You" else stringResource(state.accent.labelRes),
                ),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            AccentPicker(
                selected = state.accent,
                dark = state.themeMode.isDark(),
                enabled = true,
                showSelection = !state.dynamicColor,
                onSelect = onAccent,
            )
            if (state.dynamicColor) {
                Text(
                    stringResource(R.string.settings_material_you_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            SettingRow(
                title = stringResource(R.string.settings_tinted_text),
                subtitle = stringResource(
                    if (state.dynamicColor) R.string.settings_tinted_text_wallpaper else R.string.settings_tinted_text_accent
                ),
                trailing = {
                    Switch(checked = state.tintedText, onCheckedChange = onTintedText)
                },
            )
            AccentPreview(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            SettingRow(
                title = stringResource(R.string.settings_dynamic_colors),
                subtitle = stringResource(R.string.settings_dynamic_colors_desc),
                trailing = { Switch(checked = state.dynamicColor, onCheckedChange = onDynamicColor) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_lyrics), Icons.Rounded.Lyrics) {
            SettingRow(
                title = stringResource(R.string.settings_lyrics_online),
                subtitle = stringResource(R.string.settings_lyrics_online_desc),
                trailing = { Switch(checked = lyricsOnline, onCheckedChange = onLyricsOnline) },
            )
            SettingRow(
                title = stringResource(R.string.settings_lyrics_auto_sync),
                subtitle = stringResource(R.string.settings_lyrics_auto_sync_desc),
                trailing = { Switch(checked = lyricsAutoSync, onCheckedChange = onLyricsAutoSync) },
            )
            SettingRow(
                title = stringResource(R.string.settings_align_server),
                subtitle = alignServerUrl.ifBlank { stringResource(R.string.settings_align_server_not_set) },
                monospace = alignServerUrl.isNotBlank(),
                trailing = { Icon(Icons.Rounded.Dns, null) },
                onClick = onOpenAlignServer,
            )
            SettingRow(
                title = stringResource(R.string.settings_clear_lyrics),
                subtitle = stringResource(R.string.settings_clear_lyrics_desc),
                trailing = { Icon(Icons.Rounded.DeleteSweep, null) },
                onClick = onClearLyricsCache,
            )
        }

        SettingsSection(stringResource(R.string.settings_section_sync), Icons.Rounded.CloudSync) {
            Text(
                stringResource(R.string.settings_sync_network_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                NetworkMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.networkMode == mode,
                        onClick = { onNetworkMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, NetworkMode.entries.size),
                        icon = {},
                    ) { Text(stringResource(mode.labelRes), maxLines = 1, softWrap = false) }
                }
            }
            Text(
                stringResource(
                    when (state.networkMode) {
                        NetworkMode.WIFI -> R.string.settings_network_wifi_desc
                        NetworkMode.MOBILE -> R.string.settings_network_mobile_desc
                        NetworkMode.ANY -> R.string.settings_network_any_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            val queue = state.syncQueue
            val coversText = pluralStringResource(R.plurals.settings_sync_queue_covers, queue.covers, queue.covers)
            val lyricsText = pluralStringResource(R.plurals.settings_sync_queue_lyrics, queue.lyrics, queue.lyrics)
            val queueItems = when {
                queue.covers > 0 && queue.lyrics > 0 -> stringResource(R.string.settings_sync_queue_and, coversText, lyricsText)
                queue.covers > 0 -> coversText
                else -> lyricsText
            }
            val pendingText = pluralStringResource(
                R.plurals.settings_sync_queue_pending, queue.covers + queue.lyrics, queueItems,
            )
            val waitingText = stringResource(R.string.settings_sync_queue_waiting_network)
            SettingRow(
                title = stringResource(R.string.settings_sync_queue),
                subtitle = if (queue.isEmpty) stringResource(R.string.settings_sync_queue_empty) else buildString {
                    append(pendingText)
                    queue.current?.let { append(" · ").append(it) }
                    if (queue.waitingNetwork) append(" · ").append(waitingText)
                },
                trailing = if (queue.isEmpty) null else {
                    { TextButton(onClick = onClearSyncQueue) { Text(stringResource(R.string.settings_sync_queue_clear)) } }
                },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_gestures), Icons.Rounded.Swipe) {
            SettingRow(
                title = stringResource(R.string.settings_swipe_to_skip),
                subtitle = stringResource(R.string.settings_swipe_to_skip_desc),
                trailing = { Switch(checked = state.swipeToSkip, onCheckedChange = onSwipeToSkip) },
            )
            SettingRow(
                title = stringResource(R.string.settings_media_keys_skip),
                subtitle = stringResource(R.string.settings_media_keys_skip_desc),
                trailing = { Switch(checked = state.mediaKeysSkip, onCheckedChange = onMediaKeysSkip) },
            )
        }

        SettingsSection(stringResource(R.string.settings_section_audio), Icons.Rounded.GraphicEq) {
            SettingRow(
                title = stringResource(R.string.settings_equalizer),
                subtitle = stringResource(R.string.settings_equalizer_desc, equalizerSummary),
                trailing = { Icon(Icons.Rounded.Equalizer, null) },
                onClick = onOpenEqualizer,
            )
            SettingRow(title = stringResource(R.string.settings_audio_source), subtitle = audioSource, monospace = true)
            SettingRow(
                title = stringResource(R.string.settings_audio_output),
                subtitle = audioOutput,
                monospace = true,
                trailing = if (audioOutput.startsWith("BIT-PERFECT")) {
                    { Icon(Icons.Rounded.Usb, null, tint = MaterialTheme.colorScheme.primary) }
                } else null,
            )
        }

        UpdateSection()
        Spacer(Modifier.height(24.dp))
    }
}

/** Buscar e instalar versiones nuevas (ver AppUpdater). */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = AppUpdater.state
    SettingsSection(stringResource(R.string.settings_section_updates), Icons.Rounded.SystemUpdate) {
        val subtitle = when (state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> stringResource(R.string.update_checking)
            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
            is UpdateState.Available -> stringResource(R.string.update_available, state.update.version)
            is UpdateState.Downloading -> stringResource(R.string.update_downloading, (state.progress * 100).toInt())
            is UpdateState.NeedsPermission -> stringResource(R.string.update_permission)
            is UpdateState.Failed ->
                if (state.update != null) stringResource(R.string.update_download_failed, state.message)
                else stringResource(R.string.update_failed, state.message)
        }
        val update = when (state) {
            is UpdateState.Available -> state.update
            is UpdateState.NeedsPermission -> state.update
            is UpdateState.Downloading -> state.update
            is UpdateState.Failed -> state.update
            else -> null
        }
        SettingRow(
            title = if (update != null) stringResource(R.string.update_available, update.version) else stringResource(R.string.update_check),
            subtitle = subtitle.takeIf { update == null || state !is UpdateState.Available },
            trailing = {
                when {
                    state is UpdateState.Downloading ->
                        TextButton(onClick = { AppUpdater.cancel() }) { Text(stringResource(R.string.update_cancel)) }
                    update != null ->
                        FilledTonalButton(onClick = { AppUpdater.install(context, update) }) {
                            Text(stringResource(if (state is UpdateState.Failed) R.string.update_resume else R.string.update_install))
                        }
                    else -> Icon(Icons.Rounded.Refresh, null)
                }
            },
            onClick = if (update == null && state !is UpdateState.Checking) {
                { scope.launch { AppUpdater.check(context.applicationContext) } }
            } else null,
        )
        if (state is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Cabecera de marca: isotipo, nombre y versión. */
@Composable
private fun BrandHeader() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResuenLogo(Modifier.size(76.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text("ResuenApp", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Lossless Player" + (version?.let { " · v$it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let {
            { Text(it, fontFamily = if (monospace) FontFamily.Monospace else null) }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** Idiomas de la app: etiqueta BCP 47 ("" = el del sistema) y su nombre en ese idioma. */
private val appLanguages = listOf("es" to "Español", "en" to "English", "fr" to "Français", "pt" to "Português")

/**
 * Idioma de la app, independiente del sistema (LocaleManager, Android 13+). Al cambiarlo,
 * Android recrea la actividad con los textos nuevos; queda en Ajustes → Apps → Idioma también.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val localeManager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    var selected by remember {
        mutableStateOf(localeManager?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty())
    }
    SettingsSection(stringResource(R.string.settings_section_language), Icons.Rounded.Language) {
        Text(
            stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf("" to stringResource(R.string.settings_language_system)) + appLanguages).forEach { (tag, name) ->
                FilterChip(
                    selected = selected == tag,
                    onClick = {
                        if (selected != tag) {
                            selected = tag
                            localeManager?.applicationLocales =
                                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
                        }
                    },
                    label = { Text(name) },
                )
            }
        }
    }
}

/** Tipos de letra, cada uno escrito con su propia fuente. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FontPicker(selected: AppFont, onSelect: (AppFont) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFont.entries.forEach { font ->
            FilterChip(
                selected = font == selected,
                onClick = { onSelect(font) },
                label = { Text(font.label, fontFamily = font.family ?: FontFamily.Default) },
            )
        }
    }
}

/** Muestras circulares de los colores de acento. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentPicker(
    selected: AccentColor,
    dark: Boolean,
    enabled: Boolean,
    showSelection: Boolean,
    onSelect: (AccentColor) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentColor.entries.forEach { accent ->
            val isSelected = showSelection && accent == selected
            val accentName = stringResource(accent.labelRes)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.swatch(dark).copy(alpha = if (enabled) 1f else 0.35f))
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(accent) }
                    .semantics { contentDescription = accentName },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (dark) Color.Black else Color.White,
                    )
                }
            }
        }
    }
}

/** Vista previa en vivo: pestaña, carpeta y pista seleccionada con el tema actual. */
@Composable
private fun AccentPreview(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(CircleShape).background(colors.primaryContainer).padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text(stringResource(R.string.settings_preview_tracks), color = colors.secondary, style = MaterialTheme.typography.labelLarge) }
            Text(stringResource(R.string.settings_preview_albums), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Folder, null, tint = colors.primary)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.primaryContainer,
            border = BorderStroke(1.dp, colors.primary),
        ) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLowest),
                    contentAlignment = Alignment.Center,
                ) { Text("01", color = colors.primary, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(stringResource(R.string.settings_preview_selected_track), color = colors.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_preview_artist_album),
                        color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FormatBadge("FLAC 24-bit")
            }
        }
        Text(stringResource(R.string.settings_preview_app_text), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
