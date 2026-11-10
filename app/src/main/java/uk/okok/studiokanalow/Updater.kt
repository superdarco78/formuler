package uk.okok.studiokanalow

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Automatyczna aktualizacja: sprawdza wersje, pobiera plik i instaluje przez systemowy instalator.
 * Pierwszy raz system prosi o zgode ("Zezwol na instalowanie") i o potwierdzenie.
 * Na nowszych Androidach kolejne aktualizacje potrafia instalowac sie bez klikania.
 */
object Updater {

    private const val WERSJA_URL =
        "https://github.com/superdarco78/formuler/releases/download/apk-latest/wersja.json"
    private const val STRONA_URL =
        "https://github.com/superdarco78/formuler/releases/download/apk-latest/studio-kanalow-tv.apk"

    @Volatile private var sprawdzono = false
    @Volatile private var trwa = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun currentCode(act: Activity): Long {
        return try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    suspend fun check(act: Activity) {
        if (sprawdzono || trwa) return
        try {
            val json = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(WERSJA_URL).build()
                client.newCall(req).execute().use { it.body?.string() ?: "" }
            }
            if (json.isEmpty()) return
            val o = JSONObject(json)
            sprawdzono = true
            val remoteCode = o.optLong("versionCode", 0)
            val remoteName = o.optString("versionName", "")
            val url = o.optString("url", STRONA_URL)
            if (remoteCode <= currentCode(act)) return

            if (!act.packageManager.canRequestPackageInstalls()) {
                androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Nowa wersja $remoteName")
                    .setMessage("Aby aplikacja aktualizowała się sama, zezwól jej na instalowanie aktualizacji (jednorazowo), a potem wróć do aplikacji.")
                    .setPositiveButton("Zezwól") { _, _ ->
                        try {
                            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${act.packageName}")))
                        } catch (_: Exception) {}
                        sprawdzono = false
                    }
                    .setNegativeButton("Później", null)
                    .show()
                return
            }
            trwa = true
            Toast.makeText(act, "Aktualizuję do wersji $remoteName…", Toast.LENGTH_LONG).show()
            withContext(Dispatchers.IO) { instaluj(act, url) }
        } catch (_: Exception) {
        } finally {
            trwa = false
        }
    }

    private fun instaluj(act: Activity, url: String) {
        val installer = act.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                s.openWrite("update", 0, -1).use { out ->
                    resp.body!!.byteStream().copyTo(out)
                    s.fsync(out)
                }
            }
            val i = Intent(act, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(act, id, i, flags)
            s.commit(pending.intentSender)
        }
    }
}
