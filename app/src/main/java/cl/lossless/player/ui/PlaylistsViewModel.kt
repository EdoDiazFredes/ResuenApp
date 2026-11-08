package cl.lossless.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.lossless.player.library.Playlist
import cl.lossless.player.library.PlaylistStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PlaylistStore(app)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _playlists.value = store.load() }
    }

    /** Crea una playlist y devuelve su id. */
    fun create(name: String, initialUris: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        mutate { it + Playlist(id, name.trim(), initialUris) }
        return id
    }

    fun rename(id: String, name: String) =
        mutate { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }

    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id && !it.isFavorites } }

    fun add(id: String, uri: String) = mutate { list ->
        list.map { if (it.id == id && uri !in it.trackUris) it.copy(trackUris = it.trackUris + uri) else it }
    }

    /** Añade varias canciones al final, sin repetir las que ya estaban. */
    fun addAll(id: String, uris: List<String>) = mutate { list ->
        list.map { p ->
            if (p.id != id) p
            else p.copy(trackUris = p.trackUris + uris.filter { it !in p.trackUris }.distinct())
        }
    }

    fun remove(id: String, uri: String) = mutate { list ->
        list.map { if (it.id == id) it.copy(trackUris = it.trackUris - uri) else it }
    }

    fun toggleFavorite(uri: String) {
        val favorites = _playlists.value.firstOrNull { it.isFavorites } ?: return
        if (uri in favorites.trackUris) remove(favorites.id, uri) else add(favorites.id, uri)
    }

    private fun mutate(change: (List<Playlist>) -> List<Playlist>) {
        _playlists.update(change)
        val snapshot = _playlists.value
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }
}
