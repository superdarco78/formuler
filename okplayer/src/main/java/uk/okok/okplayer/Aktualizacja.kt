package uk.okok.okplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Aktualizacje nowej aplikacji: okno "Nowa wersja" -> po "Zainstaluj" pobiera i instaluje sie sama w tle. */
object Aktualizacja {
    private const val WERSJA = "https://github.com/superdarco78/formuler/releases/download/okplayer-latest/wersja.json"
    private var pokazane = false

    suspend fun sprawdz(a: Activity, reczne: Boolean = false) {
        try {
            val o = withContext(Dispatchers.IO) {
                Serwer.http.newCall(Request.Builder().url(WERSJA + "?t=" + System.currentTimeMillis()).header("Cache-Control", "no-cache").build()).execute().use { JSONObject(it.body?.string() ?: "{}") }
            }
            val zdalna = o.optLong("versionCode", 0)
            val moja = withContext(Dispatchers.IO) { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(a.packageManager.getPackageInfo(a.packageName, 0)) }
            if (zdalna > moja && (reczne || !pokazane) && !a.isFinishing) {
                pokazane = true
                AlertDialog.Builder(a).setTitle("Nowa wersja " + o.optString("versionName"))
                    .setMessage(o.optString("zmiany").let { z -> if (z.isEmpty()) "" else "Co było → co jest teraz:\n\n$z\n\n" } + "Po kliknięciu „Zainstaluj” aplikacja zaktualizuje się sama w tle.")
                    .setPositiveButton("Zainstaluj") { _, _ -> instaluj(a, o.optString("url")) }
                    .setNegativeButton("Później", null).show()
            } else if (reczne) android.widget.Toast.makeText(a, "Masz najnowszą wersję", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (reczne) android.widget.Toast.makeText(a, "Nie udało się sprawdzić aktualizacji", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun instaluj(a: Activity, url: String) {
        val app = a.applicationContext
        android.widget.Toast.makeText(a, "Pobieram aktualizację…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val pi = app.packageManager.packageInstaller
                val par = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                par.setAppPackageName(app.packageName)
                if (android.os.Build.VERSION.SDK_INT >= 31) par.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(par)
                pi.openSession(id).use { s ->
                    Serwer.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                        r.body!!.byteStream().use { i -> s.openWrite("okplayer.apk", 0, r.body!!.contentLength()).use { o -> i.copyTo(o); s.fsync(o) } }
                    }
                    val fl = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(android.app.PendingIntent.getBroadcast(app, id, Intent(app, WynikInstalacji::class.java), fl).intentSender)
                }
            } catch (e: Exception) { Serwer.raport(app, "aktualizacja blad " + (e.message ?: "")) }
        }.start()
    }
}

class WynikInstalacji : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val p = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (p != null) try { c.startActivity(p.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
