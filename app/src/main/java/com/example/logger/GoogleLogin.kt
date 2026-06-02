package com.example.logger

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sign in with Google folosind Credential Manager (modern API).
 * Nu necesita SHA-1 register in Google Cloud — doar Web Client ID.
 *
 * Flow:
 *  1. CredentialManager.getCredential -> bottom sheet cu conturile Google
 *  2. User selecteaza un cont -> primim Google ID Token
 *  3. POST id_token la /auth/google/token -> primim JWT BioEcho
 *  4. Salvam JWT in SharedPreferences
 */
object GoogleLogin {

    private val BACKEND_URL = BuildConfig.SERVER_URL + "/auth/google/token"
    private const val PREFS = "bioecho_prefs"

    /**
     * @param onSuccess: invocat pe main thread cu (email, name) la login reusit
     * @param onError: invocat pe main thread cu mesaj eroare
     */
    fun signIn(
        activity: AppCompatActivity,
        onSuccess: (email: String, name: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val credentialManager = CredentialManager.create(activity)
        // GetSignInWithGoogleOption = fluxul explicit "Sign in with Google" (buton):
        // arata mereu selectorul de conturi, mai robust decat GetGoogleIdOption la
        // "No credentials available". Necesita clientul OAuth Android (package + SHA-1)
        // inregistrat in proiectul Google Cloud, altfel tot da NoCredentialException.
        val signInOption = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        activity.lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(activity, request)
                val cred = result.credential
                if (cred is CustomCredential
                    && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleCred = try {
                        GoogleIdTokenCredential.createFrom(cred.data)
                    } catch (e: GoogleIdTokenParsingException) {
                        onError("Parsare token: ${e.message}")
                        return@launch
                    }
                    val idToken = googleCred.idToken
                    val email = googleCred.id
                    val displayName = googleCred.displayName ?: ""

                    // POST la backend pentru a primi JWT BioEcho
                    val exchangeResult = withContext(Dispatchers.IO) { exchangeToken(idToken) }
                    if (exchangeResult.first != null) {
                        // Salveaza JWT
                        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        prefs.edit().putString("jwt_token", exchangeResult.first).apply()
                        onSuccess(email, displayName)
                    } else {
                        onError("Schimb token: ${exchangeResult.second}")
                    }
                } else {
                    onError("Credential tip neașteptat")
                }
            } catch (e: NoCredentialException) {
                onError("Niciun cont Google disponibil. Verifica: (1) cont Google adaugat pe " +
                        "telefon, (2) Google Play Services actualizat, (3) app inregistrata in " +
                        "Google Cloud (client OAuth Android cu package com.example.logger + SHA-1).")
            } catch (e: GetCredentialException) {
                onError(e.message ?: e.type)
            } catch (e: Exception) {
                onError(e.message ?: "necunoscut")
            }
        }
    }

    /** Returneaza (jwt, null) la success sau (null, errorMsg) la eroare. */
    private fun exchangeToken(googleIdToken: String): Pair<String?, String?> {
        return try {
            val url = URL(BACKEND_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            val body = JSONObject().put("id_token", googleIdToken).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                conn.disconnect()
                Pair(null, "HTTP $code: ${err?.take(120)}")
            } else {
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val jwt = JSONObject(resp).optString("token")
                if (jwt.isBlank()) Pair(null, "token gol")
                else Pair(jwt, null)
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "rețea")
        }
    }
}
