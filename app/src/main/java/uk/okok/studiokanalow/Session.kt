package uk.okok.studiokanalow

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Bezpieczne przechowywanie danych logowania klienta.
 * Zapisywane zaszyfrowane (EncryptedSharedPreferences) - nie leza otwartym tekstem.
 * W samej aplikacji NIE ma zadnych danych na sztywno; wszystko wpisuje klient przy logowaniu.
 */
object Session {
    private const val FILE = "studio_sesja"
    private const val K_SERVER = "serwer"
    private const val K_USER = "login"
    private const val K_PASS = "haslo"

    private fun prefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(ctx: Context, server: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(K_SERVER, server)
            .putString(K_USER, user)
            .putString(K_PASS, pass)
            .apply()
    }

    fun server(ctx: Context): String = prefs(ctx).getString(K_SERVER, "") ?: ""
    fun user(ctx: Context): String = prefs(ctx).getString(K_USER, "") ?: ""
    fun pass(ctx: Context): String = prefs(ctx).getString(K_PASS, "") ?: ""
    fun isLogged(ctx: Context): Boolean = server(ctx).isNotEmpty() && user(ctx).isNotEmpty()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
