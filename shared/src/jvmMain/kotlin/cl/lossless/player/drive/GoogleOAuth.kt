package cl.lossless.player.drive

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Tokens de una sesión de Google. [expiresAt] en milisegundos de System.currentTimeMillis(). */
data class OAuthTokens(val accessToken: String, val expiresAt: Long, val refreshToken: String?)

/**
 * Flujo OAuth 2.0 de Google para apps instaladas (escritorio): abre el navegador, Google redirige
 * a un puerto local con el código y se cambia por tokens. Usa PKCE, como pide Google para estas
 * apps. Las llamadas son bloqueantes y lanzan IOException si fallan.
 */
class GoogleOAuth(private val clientId: String, private val clientSecret: String) {

    /** Verificador PKCE aleatorio (43 a 128 caracteres seguros para URL). */
    fun newVerifier(): String = randomToken(64)

    fun newState(): String = randomToken(24)

    /** Página de Google donde el usuario elige la cuenta y da permiso de lectura a Drive. */
    fun authorizationUrl(redirectUri: String, verifier: String, state: String): String = AUTH_URL + "?" + form(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to "${DriveClient.SCOPE} openid email",
        "code_challenge" to challenge(verifier),
        "code_challenge_method" to "S256",
        "access_type" to "offline",
        // Pide siempre el consentimiento para que Google entregue un refresh token nuevo.
        "prompt" to "consent",
        "state" to state,
    )

    fun exchangeCode(code: String, verifier: String, redirectUri: String): OAuthTokens = tokenRequest(
        "code" to code,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "redirect_uri" to redirectUri,
        "grant_type" to "authorization_code",
        "code_verifier" to verifier,
    )

    /** Pide un token de acceso nuevo. [DriveAuthException] si Google ya no acepta [refreshToken]. */
    fun refresh(refreshToken: String): OAuthTokens = tokenRequest(
        "refresh_token" to refreshToken,
        "client_id" to clientId,
        "client_secret" to clientSecret,
        "grant_type" to "refresh_token",
    ).let { if (it.refreshToken == null) it.copy(refreshToken = refreshToken) else it }

    private fun tokenRequest(vararg params: Pair<String, String>): OAuthTokens {
        val (code, body) = post(TOKEN_URL, form(*params))
        if (code == 400 || code == 401) {
            // invalid_grant: se revocó el permiso o el refresh token venció.
            throw DriveAuthException("Google rechazó la sesión: ${body.take(200)}")
        }
        if (code != 200) throw IOException("Google respondió HTTP $code")
        return parseTokens(body)
    }

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

        internal fun parseTokens(body: String, now: Long = System.currentTimeMillis()): OAuthTokens {
            val json = JSONObject(body)
            return OAuthTokens(
                accessToken = json.getString("access_token"),
                // Un minuto de margen para no usar un token a punto de vencer.
                expiresAt = now + (json.optLong("expires_in", 3600) - 60).coerceAtLeast(0) * 1000,
                refreshToken = json.optString("refresh_token").ifEmpty { null },
            )
        }

        /** Desconecta la app de la cuenta (sirve con el token de acceso o el refresh token). */
        fun revoke(token: String) {
            runCatching { post(REVOKE_URL, form("token" to token)) }
        }

        internal fun challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun randomToken(bytes: Int): String {
            val data = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        }

        private fun form(vararg params: Pair<String, String>) =
            params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        private fun post(url: String, body: String): Pair<Int, String> {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                return code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } finally {
                connection.disconnect()
            }
        }
    }
}
