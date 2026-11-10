package uk.okok.studiokanalow

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Automatyczna aktualizacja aplikacji.
 * Przy starcie sprawdza plik wersji na GitHub (Release). Jesli jest nowsza wersja,
 * proponuje klientowi pobranie i instalacje. Klient nie musi nic szukac ani wgrywac.
 */
object Updater {

    private const val WERSJA_URL =
        "https://github.com/superdarco78/formuler/releases/download/apk-latest/wersja.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun currentCode(act: Activity): Long {
        return try {
            val pi = act.packageManager.getPackageInfo(act.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    /** Sprawdza w tle i jesli jest nowsza wersja - pokazuje pytanie o aktualizacje. */
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
            val url = o.optString("url", "")
            if (remoteCode > currentCode(act) && url.isNotEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(act)
                    .setTitle("Nowa wersja $remoteName")
                    .setMessage("Dostępna jest nowa wersja aplikacji. Zaktualizować teraz?")
                    .setPositiveButton("Zaktualizuj") { _, _ -> download(act, url) }
                    .setNegativeButton("Później", null)
                    .show()
            }
        } catch (_: Exception) {
            // Brak sieci / brak pliku wersji - aplikacja dziala dalej normalnie.
        }
    }

    private fun download(act: Activity, url: String) {
        val busy = androidx.appcompat.app.AlertDialog.Builder(act)
            .setMessage("Pobieranie aktualizacji…")
            .setCancelable(false)
            .show()
        Thread {
            try {
                val req = Request.Builder().url(url).build()
                val bytes = client.newCall(req).execute().use { it.body?.bytes() }
                val apk = File(act.externalCacheDir, "studio-update.apk")
                if (bytes != null) apk.writeBytes(bytes)
                act.runOnUiThread {
                    busy.dismiss()
                    installApk(act, apk)
                }
            } catch (e: Exception) {
                act.runOnUiThread { busy.dismiss() }
            }
        }.start()
    }

    private fun installApk(act: Activity, apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", apk)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (_: Exception) {
        }
    }
}
