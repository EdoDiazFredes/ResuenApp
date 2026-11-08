# Google Drive

ResuenApp puede reproducir música guardada en Google Drive (pestaña **Drive** en Android y
**Google Drive** en Windows y macOS). La app pide solo permiso de **lectura** de Drive. Cada
canción se descarga completa a una caché local antes de sonar: el archivo es idéntico al de Drive,
así que FLAC, ALAC y WAV se escuchan sin pérdida (y por USB, bit-perfect como cualquier archivo
local). Mientras suena la primera, se descargan las siguientes de la carpeta y se suman a la cola.

Para que funcione hay que registrar la app en Google Cloud una sola vez.

## 1. Proyecto y API

1. Entra a <https://console.cloud.google.com/> con tu cuenta de Google y crea un proyecto
   (por ejemplo "ResuenApp").
2. En **APIs y servicios → Biblioteca**, busca **Google Drive API** y pulsa **Habilitar**.

## 2. Pantalla de consentimiento

1. Ve a **Google Auth Platform** (o **APIs y servicios → Pantalla de consentimiento de OAuth**)
   y pulsa **Comenzar**.
2. Nombre de la app: `ResuenApp`; correo de asistencia: el tuyo. Público: **Externo**.
3. En **Acceso a los datos → Agregar o quitar permisos**, agrega
   `https://www.googleapis.com/auth/drive.readonly`.
4. En **Público → Usuarios de prueba**, agrega tu correo de Gmail.

Mientras la app esté en modo **Prueba**, Google cierra la sesión cada 7 días y hay que volver a
conectar Drive. Para evitarlo, en **Público** pulsa **Publicar app**: no hace falta la verificación
de Google para uso personal, pero al conectar aparece el aviso "Google no verificó esta app"
(**Configuración avanzada → Ir a ResuenApp**).

## 3. Cliente para Windows y macOS

1. En **Clientes → Crear cliente**, elige tipo **App de escritorio**, nombre `ResuenApp escritorio`.
2. Copia el **ID de cliente** y el **Secreto del cliente**.
3. En GitHub, en el repositorio ResuenApp: **Settings → Secrets and variables → Actions → New
   repository secret**, crea dos secretos:
   - `GOOGLE_DESKTOP_CLIENT_ID` con el ID de cliente
   - `GOOGLE_DESKTOP_CLIENT_SECRET` con el secreto

El CI los incluye al compilar el instalador. Sin ellos, la pantalla de Drive avisa que no está
configurado.

## 4. Cliente para Android

Android no usa un ID en el código: Google reconoce la app por el nombre del paquete y la huella
de la llave con que se firma.

1. Saca la huella SHA-1 de tu llave de depuración (la misma del secreto `DEBUG_KEYSTORE_B64`).
   En Windows, en PowerShell:

   ```
   keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```

   (`keytool` viene con Android Studio, en `C:\Program Files\Android\Android Studio\jbr\bin`.)
   Copia la línea **SHA1**.
2. En **Clientes → Crear cliente**, tipo **Android**:
   - Nombre del paquete: `cl.lossless.player.exp` (la app "Lossless (exp)")
   - Huella SHA-1: la del paso anterior
3. Para la app estable, crea otro cliente Android igual con el paquete `cl.lossless.player`.

No hay que copiar nada a GitHub para Android.

## Uso

- **Conectar con Google** abre la pantalla de Google para elegir la cuenta y dar permiso.
- **Mi unidad** y **Compartido conmigo** cambian la raíz del explorador.
- Al tocar una canción se descarga y suena; **Reproducir carpeta** empieza desde la primera.
- La caché ocupa hasta 4 GB; al pasarse se borran las canciones menos escuchadas. **Vaciar caché**
  la borra entera.
- **Desconectar** quita el permiso de la app en tu cuenta de Google.
