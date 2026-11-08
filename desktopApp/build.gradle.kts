import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// Versión de escritorio (Windows y macOS). Reutiliza la lógica del módulo :shared (letras, LRCLIB,
// detección de texto) y trae su propio motor de audio: FFmpeg para decodificar y Java Sound
// para la salida (modo compartido; el bit-perfect con WASAPI exclusivo viene después).
kotlin { jvmToolchain(17) }

val javacpp = "1.5.11"

// Versión 1.2.N con N = cantidad de commits (igual que la app Android): cada instalador que
// publica el CI es más nuevo que el anterior, y el actualizador de la app lo compara así.
val buildNumber: Int = providers.environmentVariable("RESUENAPP_BUILD").orNull?.toIntOrNull()
    ?: runCatching {
        providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(0)
val appVersion = "1.2.$buildNumber"

// La app lee su versión de este recurso (APP_VERSION en Main.kt).
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    val version = appVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast { out.get().file("resuenapp-version.txt").asFile.writeText(version) }
}
sourceSets { main { resources.srcDir(generateVersion) } }

// Cada instalador lleva solo las librerías nativas de FFmpeg de la máquina que lo compila
// (jpackage no puede empaquetar para otro sistema): windows-x86_64, macosx-arm64 o macosx-x86_64.
val hostOs = System.getProperty("os.name").lowercase()
val isMac = hostOs.contains("mac")
val nativePlatform = when {
    isMac && System.getProperty("os.arch") in listOf("aarch64", "arm64") -> "macosx-arm64"
    isMac -> "macosx-x86_64"
    hostOs.contains("linux") -> "linux-x86_64"
    else -> "windows-x86_64"
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // En Android org.json viene con el sistema; aquí hay que traerlo (lo usa LrcLibClient).
    implementation("org.json:json:20240303")

    // Etiquetas, duración, formato y carátulas incrustadas (FLAC, MP3, M4A/ALAC, WAV, OGG).
    implementation("net.jthink:jaudiotagger:3.0.1")

    // Decodificación con FFmpeg. Solo el lector de javacv y las librerías de FFmpeg del sistema
    // de destino (sin OpenCV ni los demás módulos que javacv arrastra).
    implementation("org.bytedeco:javacv:$javacpp") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacpp")
    implementation("org.bytedeco:ffmpeg:7.1-$javacpp")
    runtimeOnly("org.bytedeco:javacpp:$javacpp:$nativePlatform")
    runtimeOnly("org.bytedeco:ffmpeg:7.1-$javacpp:$nativePlatform")

    // Teclas multimedia del teclado con la app en segundo plano (trae sus librerías nativas
    // de Windows y macOS dentro del jar).
    implementation("com.github.kwhat:jnativehook:2.2.2")

    // Colores de la barra de título de Windows (DwmSetWindowAttribute de dwmapi.dll).
    implementation("net.java.dev.jna:jna:5.15.0")

    testImplementation("junit:junit:4.13.2")
}

// Formatos que la app ofrece abrir ("Abrir con" en Windows, Finder en macOS). Un archivo abierto
// así con la app ya en marcha se pasa a la ventana abierta (ver SingleInstance.kt).
val audioFileTypes = listOf(
    "flac" to "audio/flac",
    "m4a" to "audio/mp4",
    "alac" to "audio/mp4",
    "wav" to "audio/wav",
    "aif" to "audio/aiff",
    "aiff" to "audio/aiff",
    "mp3" to "audio/mpeg",
    "aac" to "audio/aac",
    "ogg" to "audio/ogg",
    "oga" to "audio/ogg",
    "opus" to "audio/opus",
    "dsf" to "audio/x-dsf",
)

compose.desktop {
    application {
        mainClass = "cl.lossless.player.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            // En CI la .dmg final la arma installer/macos/make-dmg.sh (fondo e ícono del volumen).
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "ResuenApp"
            packageVersion = appVersion
            description = "Reproductor de música sin pérdida"
            vendor = "EdoGris"
            includeAllModules = true

            windows {
                menu = true
                menuGroup = "ResuenApp"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Mismo ícono que la app Android (icons/resuenapp.svg es la fuente).
                iconFile.set(project.file("icons/resuenapp.ico"))
                // Fijo: permite que una versión nueva del .msi reemplace a la anterior.
                upgradeUuid = "6f1c2a8e-3b4d-4c5e-9f60-7a8b9c0d1e2f"
                audioFileTypes.forEach { (ext, mime) -> fileAssociation(mime, ext, "Audio ${ext.uppercase()}") }
            }

            // .dmg sin firma de Apple: al abrirla por primera vez hay que autorizarla en
            // Ajustes del Sistema → Privacidad y seguridad (ver docs/macos.md).
            macOS {
                bundleID = "cl.lossless.resuenapp"
                dockName = "ResuenApp"
                appCategory = "public.app-category.music"
                minimumSystemVersion = "11.0"
                // Generado desde icons/resuenapp.svg con el margen de la plantilla de íconos de macOS.
                iconFile.set(project.file("icons/resuenapp.icns"))
                audioFileTypes.forEach { (ext, mime) -> fileAssociation(mime, ext, "Audio ${ext.uppercase()}") }
            }
        }
    }
}
