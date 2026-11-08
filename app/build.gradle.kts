plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Número de compilación = cantidad de commits de la rama (el CI hace un checkout completo).
// Sube solo en cada commit, así cada APK que publica el CI se instala encima del anterior y el
// actualizador de la app sabe cuál es más nuevo. RESUENAPP_BUILD lo fija a mano si hace falta.
val buildNumber: Int = providers.environmentVariable("RESUENAPP_BUILD").orNull?.toIntOrNull()
    ?: runCatching {
        providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(3)

android {
    namespace = "cl.lossless.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.lossless.player"
        minSdk = 34          // Android 14: necesario para AudioMixerAttributes (bit-perfect USB)
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.2.$buildNumber"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        // Instalable junto a la app normal: otro paquete y otro nombre (src/experimental); el
        // ícono es el mismo de la app de Windows. Datos y ajustes quedan separados de la estable.
        create("experimental") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".exp"
            versionNameSuffix = "-exp"
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Idiomas: español (por defecto, res/values), inglés, francés y portugués. Genera el
    // locale_config para que Android 13+ ofrezca elegir el idioma de la app en Ajustes del sistema.
    androidResources { generateLocaleConfig = true }
}

kotlin { jvmToolchain(17) }

val media3 = "1.8.0"   // revisa la última versión estable de Media3

dependencies {
    // Lógica compartida con la versión de escritorio (letras, carátulas, ecualizador)
    implementation(project(":shared"))

    // Reproducción y sesión multimedia (Google / AndroidX)
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    // Decodificador FFmpeg: respaldo para ALAC (y FLAC/MP3) si el dispositivo no tiene
    // decodificador nativo. Google no publica este módulo en Maven; esta es la
    // compilación que mantiene Jellyfin. Su versión debe coincidir con la de Media3.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1")

    // UI
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")   // FileProvider para el actualizador
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")

    // Carga de imágenes (carátulas locales y vistas previas de internet)
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Cola de sincronización (carátulas y letras): sigue aunque se cierre la app y respeta
    // la red elegida (solo Wi-Fi, solo datos o ambas).
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Google Drive: inicio de sesión con Google Play Services (AuthorizationClient) para pedir el
    // permiso de solo lectura de Drive. La API de Drive se usa con el cliente de :shared.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    testImplementation("junit:junit:4.13.2")
    // org.json real: el de android.jar en los tests de JVM son solo firmas vacías.
    testImplementation("org.json:json:20240303")
}
