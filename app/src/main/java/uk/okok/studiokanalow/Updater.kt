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
 * Sprawdzanie aktualizacji. Jesli jest nowsza wersja, pokazuje okno "Nowa wersja";
 * po "Zainstaluj" aktualizacja pobiera sie i instaluje sama w tle.
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
                    .setMessage("Dostępna jest nowa wersja aplikacji. Po kliknięciu „Zainstaluj” zaktualizuje się sama w tle i uruchomi ponownie. Dane i logowanie zostaną zachowane.")
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

    /**
     * Cicha aktualizacja: pobiera APK w tle i instaluje przez PackageInstaller.
     * Na Androidzie 12+ aplikacja aktualizuje sama siebie bez dodatkowych okien
     * (jedyne potwierdzenie to "Zainstaluj" w naszym oknie). Na starszych systemach
     * system pokaze swoje okno instalacji.
     */
    private fun pobierzIZainstaluj(act: Activity, url: String) {
        val app = act.applicationContext
        (act as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    val pi = app.packageManager.packageInstaller
                    val params = android.content.pm.PackageInstaller.SessionParams(
                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(app.packageName)
                    if (android.os.Build.VERSION.SDK_INT >= 31)
                        params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    val id = pi.createSession(params)
                    val sesja = pi.openSession(id)
                    try {
                        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                            if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
                            val dl = r.body!!.contentLength()
                            r.body!!.byteStream().use { i ->
                                sesja.openWrite("okplayer.apk", 0, dl).use { o ->
                                    i.copyTo(o); sesja.fsync(o)
                                }
                            }
                        }
                        val flagi = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                        val pint = android.app.PendingIntent.getBroadcast(app, id,
                            Intent(app, InstalacjaReceiver::class.java), flagi)
                        sesja.commit(pint.intentSender)
                    } catch (e: Exception) { sesja.abandon(); throw e }
                    finally { sesja.close() }
                }
                rap(act, "cicha instalacja wyslana")
            } catch (e: Exception) {
                rap(act, "blad instalacji " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
                android.widget.Toast.makeText(act, "Nie udało się pobrać aktualizacji", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Odbiera wynik instalacji. Gdy system wymaga potwierdzenia (np. brak zgody na instalowanie), pokazuje jego okno. */
class InstalacjaReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent) {
        val st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val potw = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (potw != null) try { c.startActivity(potw.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            val m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            android.widget.Toast.makeText(c, "Aktualizacja nieudana: " + m.take(60), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

/** Po cichej aktualizacji uruchamia aplikacje ponownie. */
class AktualizacjaReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: android.content.Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            c.startActivity(Intent(c, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
