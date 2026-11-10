package uk.okok.studiokanalow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sprawdzanie aktualizacji (lekka wersja, bez specjalnych uprawnien).
 * Jesli jest nowsza wersja, pokazuje okno z przyciskiem "Pobierz", ktory otwiera
 * strone z plikiem. Nie zada uprawnienia do instalowania (to uspokaja Play Protect).
 */
object Updater {

    private const val WERSJA_URL =
        "https://github.com/superdarco78/formuler/releases/download/apk-latest/wersja.json"
    private const val STRONA_URL =
        "https://github.com/superdarco78/formuler/releases/download/apk-latest/studio-kanalow-tv.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun currentCode(act: Activity): Long {
        return try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    suspend fun check(act: Activity) {
        try {
            val json = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(WERSJA_URL).build()
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            if (json.isEmpty()) return
            val o = JSONObject(json)
            val remoteCode = o.optLong("versionCode", 0)
            val remoteName = o.optString("versionName", "")
            val url = o.optString("url", STRONA_URL)
            if (remoteCode > currentCode(act)) {
                androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Nowa wersja $remoteName")
                    .setMessage("Dostępna jest nowa wersja aplikacji.")
                    .setPositiveButton("Pobierz") { _, _ ->
                        try {
                            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Później", null)
                    .show()
            }
        } catch (_: Exception) {
        }
    }
}
