package cl.lossless.player.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.lossless.player.desktop.AppSettings
import cl.lossless.player.desktop.S
import cl.lossless.player.desktop.Str
import cl.lossless.player.desktop.SystemAccent
import javax.imageio.ImageIO

/** Tema de la app: Noche es el de la maqueta; Oscuro y Claro son los mismos de Android. */
enum class ThemeMode(val label: Str) {
    NIGHT(S.themeNight),
    DARK(S.themeDark),
    LIGHT(S.themeLight),
}

/** Colores de acento: los mismos de la app Android, incluida la paleta neón con sus valores exactos. */
enum class Accent(val label: Str, val hue: Float, val saturation: Float, private val exact: Long? = null) {
    NEON_CYAN(S.accentNeonCyan, 182.6f, 0.82f, 0xFF14C4CC),
    NEON_TEAL(S.accentNeonTeal, 169.1f, 0.71f, 0xFF22C7A9),
    NEON_AMBER(S.accentNeonAmber, 43.3f, 1.00f, 0xFFFFB800),
    NEON_BLUE(S.accentNeonBlue, 206.6f, 0.90f, 0xFF2196F3),
    NEON_PINK(S.accentNeonPink, 339.6f, 0.82f, 0xFFE91E63),
    BLUE(S.accentBlue, 220f, 0.95f),
    TEAL(S.accentTeal, 178f, 0.85f),
    GREEN(S.accentGreen, 145f, 0.75f),
    AMBER(S.accentAmber, 38f, 1.00f),
    ORANGE(S.accentOrange, 18f, 0.95f),
    RED(S.accentRed, 355f, 0.85f),
    PINK(S.accentPink, 330f, 0.85f),
    PURPLE(S.accentPurple, 268f, 0.80f),
    /** El color de énfasis de Windows o macOS, con el mismo ajuste de contraste que los demás. */
    SYSTEM(S.accentSystem, 0f, 0f),
    ;

    fun color(dark: Boolean): Color {
        val (h, s) = if (this == SYSTEM) SystemAccent.color.hueSaturation() else hue to saturation
        return when {
            dark -> exact?.let { Color(it) } ?: vivid(h, s, 0.60f, minLum = 0.28f)
            else -> vivid(h, s, 0.45f, maxLum = 0.12f)
        }
    }
}

/** Tono (0..360) y saturación HSL (0..1) de un color. */
private fun Color.hueSaturation(): Pair<Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    if (d < 1e-4f) return 0f to 0f
    val l = (max + min) / 2
    val s = d / (1 - kotlin.math.abs(2 * l - 1))
    val h = when (max) {
        red -> 60f * (((green - blue) / d) % 6f)
        green -> 60f * ((blue - red) / d + 2f)
        else -> 60f * ((red - green) / d + 4f)
    }
    return (if (h < 0) h + 360f else h) to s.coerceIn(0f, 1f)
}

/** Tipos de letra: los mismos que se pueden elegir en Android (incluidos en la app, licencia OFL). */
enum class AppFont(val brandName: String?, private val file: String?) {
    INTER("Inter", "inter"),
    SYSTEM(null, null),
    ROBOTO("Roboto", "roboto"),
    OPEN_SANS("Open Sans", "open_sans"),
    NUNITO("Nunito", "nunito"),
    MONTSERRAT("Montserrat", "montserrat"),
    POPPINS("Poppins", "poppins"),
    BRICOLAGE("Bricolage Grotesque", "bricolage_grotesque"),
    ;

    val family: FontFamily by lazy {
        when {
            file == null -> FontFamily.Default
            else -> FontFamily(
                listOf("regular" to FontWeight.Normal, "medium" to FontWeight.Medium, "semibold" to FontWeight.SemiBold, "bold" to FontWeight.Bold)
                    .map { (name, weight) -> Font("$file-$name", resourceBytes("font/${file}_$name.ttf"), weight) },
            )
        }
    }
}

enum class TextSize(val label: Str, val scale: Float) {
    SMALL(S.textSmall, 0.9f),
    NORMAL(S.textNormal, 1f),
    LARGE(S.textLarge, 1.15f),
    XLARGE(S.textXLarge, 1.3f),
}

private fun hsl(h: Float, s: Float, l: Float) = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** Como en Android: corrige la luminosidad lo justo para mantener el contraste del acento. */
private fun vivid(h: Float, s: Float, l: Float, minLum: Float = 0f, maxLum: Float = 1f): Color {
    val base = hsl(h, s, l)
    val lum = base.luminance()
    if (lum in minLum..maxLum) return base
    var lo = if (lum < minLum) l else 0f
    var hi = if (lum < minLum) 1f else l
    repeat(24) {
        val mid = (lo + hi) / 2
        val tooDark = hsl(h, s, mid).luminance() < minLum
        val tooLight = hsl(h, s, mid).luminance() > maxLum
        if (lum < minLum) { if (tooDark) lo = mid else hi = mid } else { if (tooLight) hi = mid else lo = mid }
    }
    return hsl(h, s, if (lum < minLum) hi else lo)
}

/** Colores efectivos según el tema y el acento elegidos. */
class Palette(
    val dark: Boolean,
    val night: Color,
    val surface: Color,
    val card: Color,
    val border: Color,
    val hover: Color,
    val accent: Color,
    val teal: Color,
    val amber: Color,
    val text: Color,
    val textStrong: Color,
    val muted: Color,
    val faint: Color,
) {
    val onAccent: Color = if (accent.luminance() > 0.3f) Color(0xFF0A0D14) else Color.White

    companion object {
        fun of(theme: ThemeMode, accent: Accent): Palette = when (theme) {
            ThemeMode.NIGHT -> Palette(
                dark = true,
                night = Color(0xFF0A0D14), surface = Color(0xFF121722), card = Color(0xFF1A202C),
                border = Color(0xFF1F283B), hover = Color(0xFF2A354D),
                accent = accent.color(dark = true), teal = Color(0xFF22C7A9), amber = Color(0xFFFFB800),
                text = Color(0xFFE2E8F0), textStrong = Color.White, muted = Color(0xFF94A3B8), faint = Color(0xFF64748B),
            )
            ThemeMode.DARK -> Palette(
                dark = true,
                night = Color(0xFF161616), surface = Color(0xFF1C1C1C), card = Color(0xFF212121),
                border = Color(0xFF333333), hover = Color(0xFF303030),
                accent = accent.color(dark = true), teal = Color(0xFF22C7A9), amber = Color(0xFFFFB800),
                text = Color(0xFFE8E8E8), textStrong = Color.White, muted = Color(0xFFA8A8A8), faint = Color(0xFF787878),
            )
            ThemeMode.LIGHT -> Palette(
                dark = false,
                night = Color(0xFFF6F7FA), surface = Color(0xFFFFFFFF), card = Color(0xFFFFFFFF),
                border = Color(0xFFDADDE3), hover = Color(0xFFE9ECF2),
                accent = accent.color(dark = false), teal = Color(0xFF0F8A76), amber = Color(0xFFA86F00),
                text = Color(0xFF1A1C1E), textStrong = Color(0xFF0B0C0E), muted = Color(0xFF5A5F68), faint = Color(0xFF80868F),
            )
        }
    }
}

/** Apariencia elegida en Ajustes; se guarda entre sesiones y la UI se redibuja al cambiarla. */
object Appearance {
    var theme by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == AppSettings.theme } ?: ThemeMode.NIGHT)
        private set
    var accent by mutableStateOf(Accent.entries.firstOrNull { it.name == AppSettings.accent } ?: Accent.NEON_CYAN)
        private set
    // Bricolage Grotesque ocupa el lugar de la antigua opción "SERIF".
    var font by mutableStateOf(AppFont.entries.firstOrNull { it.name == AppSettings.font?.replace("SERIF", "BRICOLAGE") } ?: AppFont.INTER)
        private set
    var textSize by mutableStateOf(TextSize.entries.firstOrNull { it.name == AppSettings.textSize } ?: TextSize.NORMAL)
        private set

    /** Panel de carátula y letra: a la izquierda de la lista en vez de a la derecha, y su ancho. */
    var panelLeft by mutableStateOf(AppSettings.panelLeft)
        private set
    var panelWidth by mutableStateOf(AppSettings.panelWidth.coerceIn(PANEL_MIN, PANEL_MAX))
        private set

    /** Menú lateral reducido a un riel de íconos. */
    var sidebarCollapsed by mutableStateOf(AppSettings.sidebarCollapsed)
        private set
    fun toggleSidebar() { sidebarCollapsed = !sidebarCollapsed; AppSettings.sidebarCollapsed = sidebarCollapsed }

    val palette: Palette by derivedStateOf { Palette.of(theme, accent) }

    fun placePanel(left: Boolean) { panelLeft = left; AppSettings.panelLeft = left }
    /** Mientras se arrastra el borde; [savePanelWidth] lo guarda al soltar. */
    fun resizePanel(width: Float) { panelWidth = width.coerceIn(PANEL_MIN, PANEL_MAX) }
    fun savePanelWidth() { AppSettings.panelWidth = panelWidth }

    const val PANEL_MIN = 280f
    const val PANEL_MAX = 760f

    fun set(theme: ThemeMode) { this.theme = theme; AppSettings.theme = theme.name }
    fun set(accent: Accent) { this.accent = accent; AppSettings.accent = accent.name }
    fun set(font: AppFont) { this.font = font; AppSettings.font = font.name }
    fun set(size: TextSize) { textSize = size; AppSettings.textSize = size.name }
}

/** Colores de la interfaz. "Accent" es el color elegido (cian neón por defecto, como en la maqueta). */
object Neon {
    private val p get() = Appearance.palette
    val Night get() = p.night
    val Surface get() = p.surface
    val Card get() = p.card
    val Border get() = p.border
    val Hover get() = p.hover
    val Accent get() = p.accent
    val OnAccent get() = p.onAccent
    val Teal get() = p.teal
    val Amber get() = p.amber
    val Text get() = p.text
    val TextStrong get() = p.textStrong
    val Muted get() = p.muted
    val Faint get() = p.faint
}

private fun resourceBytes(path: String): ByteArray =
    checkNotNull(Neon::class.java.classLoader.getResourceAsStream(path)) { path }.use { it.readBytes() }

/** Para todo dato técnico: rutas, duraciones, formatos, números. */
val Mono = FontFamily.Monospace

/** Ícono de la app (el mismo de Android), para la ventana y la barra de título. */
val AppIcon: ImageBitmap by lazy {
    ImageIO.read(resourceBytes("resuenapp.png").inputStream()).toComposeImageBitmap()
}

private fun typography(family: FontFamily) = Typography().let { base ->
    fun TextStyle.withFont() = copy(fontFamily = family)
    Typography(
        displayLarge = base.displayLarge.withFont(),
        displayMedium = base.displayMedium.withFont(),
        displaySmall = base.displaySmall.withFont(),
        headlineLarge = base.headlineLarge.withFont(),
        headlineMedium = base.headlineMedium.withFont(),
        headlineSmall = base.headlineSmall.withFont(),
        titleLarge = base.titleLarge.withFont(),
        titleMedium = base.titleMedium.withFont(),
        titleSmall = base.titleSmall.withFont(),
        bodyLarge = base.bodyLarge.withFont(),
        bodyMedium = base.bodyMedium.withFont(),
        bodySmall = base.bodySmall.withFont(),
        labelLarge = base.labelLarge.withFont(),
        labelMedium = base.labelMedium.withFont(),
        labelSmall = base.labelSmall.withFont(),
    )
}

@Composable
fun NeonTheme(content: @Composable () -> Unit) {
    val p = Appearance.palette
    val scheme = remember(p) {
        if (p.dark) {
            darkColorScheme(
                primary = p.accent, onPrimary = p.onAccent,
                primaryContainer = p.accent.copy(alpha = 0.18f), onPrimaryContainer = p.accent,
                secondary = p.teal, tertiary = p.amber,
                background = p.night, onBackground = p.text,
                surface = p.surface, onSurface = p.text,
                surfaceVariant = p.card, onSurfaceVariant = p.muted,
                surfaceContainerLowest = p.night, surfaceContainerLow = p.surface, surfaceContainer = p.card,
                surfaceContainerHigh = p.hover, surfaceContainerHighest = p.hover,
                outline = p.hover, outlineVariant = p.border,
                error = Color(0xFFFF6B81),
            )
        } else {
            lightColorScheme(
                primary = p.accent, onPrimary = p.onAccent,
                primaryContainer = p.accent.copy(alpha = 0.14f), onPrimaryContainer = p.accent,
                secondary = p.teal, tertiary = p.amber,
                background = p.night, onBackground = p.text,
                surface = p.surface, onSurface = p.text,
                surfaceVariant = p.hover, onSurfaceVariant = p.muted,
                surfaceContainerLowest = p.surface, surfaceContainerLow = p.night, surfaceContainer = p.card,
                surfaceContainerHigh = p.hover, surfaceContainerHighest = p.hover,
                outline = Color(0xFFB8BDC7), outlineVariant = p.border,
            )
        }
    }
    val font = Appearance.font
    val typo = remember(font) { typography(font.family) }
    val density = LocalDensity.current
    val size = Appearance.textSize
    val scaled = remember(density, size) { Density(density.density, density.fontScale * size.scale) }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}

/** Panel translúcido con borde, como .glass-panel / .glass-card de la maqueta. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(12.dp), color: Color = Neon.Surface.copy(alpha = 0.75f)): Modifier =
    clip(shape).background(color, shape).border(1.dp, Neon.Border, shape)

/** Resplandor neón alrededor de un elemento (más suave en el tema claro). */
fun Modifier.glow(color: Color = Neon.Accent, radius: Dp = 14.dp, shape: Shape = RoundedCornerShape(50)): Modifier =
    shadow(if (Appearance.palette.dark) radius else radius / 2, shape, clip = false, ambientColor = color, spotColor = color)

/** Rótulo de sección en mayúsculas y monoespaciado ("BIBLIOTECA"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Neon.Muted) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = Mono,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
    )
}

/** Insignia monoespaciada: formato, bits/kHz, estado de la salida. */
@Composable
fun NeonBadge(text: String, color: Color = Neon.Muted, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        modifier = modifier
            .clip(shape)
            .background(if (emphasized) color.copy(alpha = 0.10f) else Neon.Card, shape)
            .border(1.dp, if (emphasized) color.copy(alpha = 0.35f) else Neon.Border, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = color,
        fontFamily = Mono,
        fontSize = 10.sp,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}
