package cl.lossless.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Pausa tras la última tecla antes de filtrar, para no recalcular con cada letra. */
private const val SEARCH_DEBOUNCE_MS = 120L

/**
 * Resultado de una búsqueda calculado fuera del hilo principal.
 *
 * Filtrar miles de pistas en la composición bloqueaba el hilo de la interfaz y el texto
 * tecleado aparecía con segundos de retraso. Aquí el campo se actualiza al instante y la
 * lista se recalcula en segundo plano un momento después de dejar de escribir; mientras
 * tanto se sigue mostrando el resultado anterior.
 */
@Composable
fun <T> rememberSearchResult(
    query: String,
    vararg keys: Any?,
    compute: (query: String) -> T,
): State<T> {
    // El valor inicial (sin filtro) se calcula una sola vez, no en cada recomposición.
    val result = remember { mutableStateOf(compute("")) }
    LaunchedEffect(query, *keys) {
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        result.value = withContext(Dispatchers.Default) { compute(query) }
    }
    return result
}
