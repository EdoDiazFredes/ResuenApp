# Android Auto (y CarPlay a futuro)

Diseño de referencia: [design/auto-y-carplay.html](design/auto-y-carplay.html) (abrirlo en el navegador;
tiene dos pestañas, Android Auto y CarPlay).

## Cómo funciona en Android Auto

En Android Auto una app de música **no dibuja su propia pantalla**: el auto arma la interfaz con
lo que la app le entrega (árbol de la biblioteca, carátula, textos y botones). Por eso el diseño
se aplica así:

| En el diseño | En ResuenApp |
| --- | --- |
| Pantalla dividida con el mapa | La pone Android Auto solo (Maps/Waze a un lado, el reproductor al otro). |
| Carátula grande | Se entrega la carátula del álbum (`CoverProvider`, `content://`). |
| Insignia "FLAC 24-bit / 96 kHz" | Va en la descripción de cada pista (`Track.qualityDetails`) y en las listas ("Artista · FLAC 24-bit"). Android Auto decide si la muestra en la pantalla de reproducción. |
| Anterior / reproducir / siguiente, barra de avance | Los dibuja Android Auto con los comandos de la sesión de Media3. |
| Botón de micrófono (Asistente) | "Reproduce X en ResuenApp" busca por título, artista o álbum (`onSetMediaItems` con `searchQuery`). |
| Colores y tipografía propios | No se pueden cambiar; Android Auto usa su propio tema. |

Navegación: pestañas Playlists (Mezclar todo, Todas las pistas, Favoritos, playlists), Álbumes
(cuadrícula), Artistas y Carpetas. Código en `app/.../playback/auto/` y `PlaybackService`.

### Probar

La APK no viene de Play Store: en la app Android Auto del teléfono, tocar 10 veces "Versión",
luego menú ⋮ › Configuración para desarrolladores › **Fuentes desconocidas**. Sin auto se puede
usar el Desktop Head Unit (DHU) del SDK de Android.

## CarPlay (reservado para la versión iOS)

La vista "Apple CarPlay (Focus Drive)" del diseño queda reservada para cuando exista ResuenApp para
iOS (Kotlin Multiplatform + Compose Multiplatform). Notas para entonces:

- CarPlay también usa plantillas del sistema (`CPNowPlayingTemplate`, `CPListTemplate`,
  `CPGridTemplate`); la app no dibuja la pantalla. La insignia de calidad iría como texto/botón de la
  plantilla y la carátula como `MPMediaItemArtwork`.
- Requiere el permiso (entitlement) `com.apple.developer.carplay-audio`, que se pide a Apple con la
  cuenta de Apple Developer.
- El árbol de navegación de `AutoMediaIds` (Playlists / Álbumes / Artistas / Carpetas) se puede
  reutilizar moviendo esa lógica a `shared`.
