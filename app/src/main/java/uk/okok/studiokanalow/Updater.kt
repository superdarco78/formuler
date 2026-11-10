package uk.okok.studiokanalow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                    .setMessage("Dostępna jest nowa wersja aplikacji. Zainstalować teraz? Twoje dane i logowanie zostaną zachowane.")
                    .setPositiveButton("Zainstaluj") { _, _ -> pobierzIZainstaluj(act, url) }
                    .setNegativeButton("Później", null)
                    .show()
            }
        } catch (_: Exception) {
        }
    }

    /** Pobiera APK do pamieci podrecznej i uruchamia systemowy instalator (aktualizacja na wierzch). */
    private fun pobierzIZainstaluj(act: Activity, url: String) {
        android.widget.Toast.makeText(act, "Pobieranie aktualizacji…", android.widget.Toast.LENGTH_LONG).show()
        (act as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
            try {
                val plik = withContext(Dispatchers.IO) {
                    val f = java.io.File(act.cacheDir, "aktualizacja.apk")
                    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
                        r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    }
                    f
                }
                if (android.os.Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
                    android.widget.Toast.makeText(act, "Zezwól tej aplikacji na instalowanie, potem wybierz aktualizację jeszcze raz", android.widget.Toast.LENGTH_LONG).show()
                    act.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                    return@launch
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(act, act.packageName + ".fileprovider", plik)
                act.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                android.widget.Toast.makeText(act, "Nie udało się pobrać aktualizacji", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
