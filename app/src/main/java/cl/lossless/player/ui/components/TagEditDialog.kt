package cl.lossless.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cl.lossless.player.R
import cl.lossless.player.library.Track
import cl.lossless.player.tags.TagField
import cl.lossless.player.ui.TagEditState

/** Barra que aparece mientras hay canciones marcadas: cuántas, marcar todas, añadir a playlist, editar y salir. */
@Composable
fun SelectionBar(
    selection: TrackSelection,
    visible: List<Track>,
    onEdit: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = selection::clear) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.library_clear_selection))
            }
            Text(
                pluralStringResource(R.plurals.library_selected_count, selection.uris.size, selection.uris.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (visible.any { it !in selection }) {
                IconButton(onClick = { selection.selectAll(visible) }) {
                    Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.library_select_all))
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = stringResource(R.string.library_add_to_playlist))
            }
            FilledTonalButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.library_edit_tags), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Campos que se pueden cambiar a la vez en varias canciones (no título ni número de pista). */
private val batchFields = listOf(TagField.ARTIST, TagField.ALBUM, TagField.ALBUM_ARTIST, TagField.YEAR, TagField.GENRE)

private val TagField.label: Int
    get() = when (this) {
        TagField.ARTIST -> R.string.library_tag_artist
        TagField.ALBUM -> R.string.library_tag_album
        TagField.ALBUM_ARTIST -> R.string.library_tag_album_artist
        TagField.YEAR -> R.string.library_tag_year
        else -> R.string.library_tag_genre
    }

/**
 * Asignar álbum, artista, etc. a varias canciones. Un campo empieza con el valor que comparten;
 * si difieren (o la biblioteca no lo conoce) queda vacío y solo se escribe si se toca.
 * [onSave] recibe únicamente los campos tocados (vacío = borrar).
 */
@Composable
fun TagEditDialog(state: TagEditState, onDismiss: () -> Unit, onSave: (Map<TagField, String>) -> Unit) {
    val unknownArtist = stringResource(R.string.library_unknown_artist)
    val tracks = state.tracks
    val known = remember(tracks) {
        fun shared(value: (Track) -> String): String? =
            tracks.map { value(it).trim() }.distinct().singleOrNull()
        mapOf(
            TagField.ARTIST to shared { it.artist.takeIf { a -> a != unknownArtist }.orEmpty() },
            TagField.ALBUM to shared { it.album },
            TagField.ALBUM_ARTIST to shared { it.albumArtist },
        )
    }
    val values = remember(tracks) { mutableStateMapOf<TagField, String>().apply { known.forEach { (f, v) -> put(f, v.orEmpty()) } } }
    val edited = remember(tracks) { mutableStateMapOf<TagField, Boolean>() }
    val folders = remember(tracks) { tracks.map { it.folderKey }.distinct().size }

    AlertDialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        title = { Text(pluralStringResource(R.plurals.library_tags_title, tracks.size, tracks.size)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (folders > 1) {
                    Text(
                        stringResource(R.string.library_tags_merge_hint, folders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                batchFields.forEach { f ->
                    val mixed = f !in known || known[f] == null
                    OutlinedTextField(
                        value = values[f].orEmpty(),
                        onValueChange = { v ->
                            values[f] = if (f.numeric) v.filter { it.isDigit() } else v
                            edited[f] = true
                        },
                        label = { Text(stringResource(f.label)) },
                        placeholder = if (mixed && edited[f] != true) {
                            { Text(stringResource(if (f in known) R.string.library_tags_mixed else R.string.library_tags_unchanged)) }
                        } else null,
                        singleLine = true,
                        enabled = !state.saving,
                        keyboardOptions = if (f.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.error?.let {
                    Text(
                        stringResource(R.string.library_tags_error, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.saving) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.library_tags_saving), modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                TextButton(onClick = { onSave(batchFields.filter { edited[it] == true }.associateWith { values[it].orEmpty() }) }) {
                    Text(stringResource(R.string.library_save))
                }
            }
        },
        dismissButton = {
            if (!state.saving) TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
