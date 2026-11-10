package cl.lossless.player.desktop

/** Sistema en el que corre la app: cambia rutas, atajos y algunos textos. */
val IsMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

val IsWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

/**
 * Ruta de ResuenApp.exe instalada (el lanzador de jpackage corre la app en su mismo proceso).
 * null si se ejecuta desde Gradle (ahí el proceso es java.exe).
 */
val InstalledExe: String? by lazy {
    ProcessHandle.current().info().command().orElse(null)
        ?.takeIf { java.io.File(it).name.equals("ResuenApp.exe", ignoreCase = true) }
}

/**
 * Abre Configuración > Aplicaciones predeterminadas en la página de ResuenApp (Windows 11; el
 * instalador la anota en RegisteredApplications). Windows 10 ignora el parámetro y muestra la
 * lista general.
 */
fun openWindowsDefaultApps() {
    runCatching { ProcessBuilder("explorer.exe", "ms-settings:defaultapps?registeredAppUser=ResuenApp").start() }
}
