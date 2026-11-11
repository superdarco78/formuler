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

    private var pokazane = false

    private suspend fun rap(act: Activity, m: String) {
        try { Api.raportuj(Device.id(act), "v" + (try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (e: Exception) { "?" }) + " OK Player aktualizacja " + m) } catch (_: Exception) {}
    }

    suspend fun check(act: Activity, reczne: Boolean = false) {
        try {
            val json = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(WERSJA_URL).build()
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            if (json.isEmpty()) { rap(act, "pusta odpowiedz"); return }
            val o = JSONObject(json)
            val remoteCode = o.optLong("versionCode", 0)
            val remoteName = o.optString("versionName", "")
            val url = o.optString("url", STRONA_URL)
            rap(act, "zdalna=" + remoteCode + " lokalna=" + currentCode(act))
            if (remoteCode > currentCode(act) && (reczne || !pokazane) && !act.isFinishing) {
                pokazane = true
                androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Nowa wersja $remoteName")
                    .setMessage("Dostępna jest nowa wersja aplikacji. Zainstalować teraz? Twoje dane i logowanie zostaną zachowane.")
                    .setPositiveButton("Zainstaluj") { _, _ -> pobierzIZainstaluj(act, url) }
                    .setNegativeButton("Później", null)
                    .setOnDismissListener { pokazane = false }
                    .show()
            } else if (reczne) {
                android.widget.Toast.makeText(act, "Masz najnowszą wersję (" + remoteName + ")", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            if (reczne) android.widget.Toast.makeText(act, "Nie udało się sprawdzić aktualizacji", android.widget.Toast.LENGTH_LONG).show()
            rap(act, "blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
        }
    }

    /** Otwiera plik APK w przegladarce/pobieraniu systemu - bez uprawnienia instalowania (uspokaja Play Protect). */
    private fun pobierzIZainstaluj(act: Activity, url: String) {
        android.widget.Toast.makeText(act, "Pobieranie aktualizacji…", android.widget.Toast.LENGTH_LONG).show()
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(act, "Nie udało się otworzyć pobierania", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
