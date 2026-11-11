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
                okno(a, o)
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
    /** Ladne okno aktualizacji: tytul, a pod nim tylko zmiany tej wersji - kazda: nazwa, bylo, teraz. */
    private fun okno(a: Activity, o: JSONObject) {
        val dp = { v: Int -> (v * a.resources.displayMetrics.density).toInt() }
        val d = android.app.Dialog(a)
        val kol = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1B1B1B.toInt()); cornerRadius = dp(18).toFloat() } }
        fun t(x: String, r: Float, k: Int, g: Boolean = false) = android.widget.TextView(a).apply { text = x; textSize = r; setTextColor(k); if (g) setTypeface(typeface, android.graphics.Typeface.BOLD) }
        kol.addView(t("Nowa wersja " + o.optString("versionName"), 22f, android.graphics.Color.WHITE, true))
        kol.addView(t("Co się zmieniło", 14f, 0xFFE0245E.toInt(), true).apply { setPadding(0, dp(4), 0, dp(10)) })
        val lista = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for (w in o.optString("zmiany").lines().map { it.trim().trimStart('•', ' ') }.filter { it.isNotEmpty() }) {
            val cz = w.split("|").map { it.trim() }
            val box = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF262626.toInt()); cornerRadius = dp(12).toFloat() } }
            if (cz.size >= 3) {
                box.addView(t(cz[0], 15f, android.graphics.Color.WHITE, true))
                box.addView(t("Było: " + cz[1], 13f, 0xFF9A9A9A.toInt()).apply { setPadding(0, dp(4), 0, 0) })
                box.addView(t("Teraz: " + cz[2], 14f, 0xFFE6E6E6.toInt()).apply { setPadding(0, dp(2), 0, 0) })
            } else box.addView(t(w, 14f, 0xFFE6E6E6.toInt()))
            lista.addView(box, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        kol.addView(android.widget.ScrollView(a).apply { addView(lista) }, android.widget.LinearLayout.LayoutParams(-1, if (o.optString("zmiany").lines().size > 4) dp(380) else -2))
        // Instrukcja dla uzytkownika: co kliknac, gdy Android pokaze okno Google Play Protect.
        val instr = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF14233A.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2F5A94.toInt()) } }
        instr.addView(t("🛡  Jak zainstalować", 15f, 0xFF8FB8FF.toInt(), true))
        instr.addView(t("Po dotknięciu „Zainstaluj” Android może pokazać okno Google Play Protect:\n" +
            "1.  „Zalecane skanowanie” → dotknij „Skanuj aplikację”.\n" +
            "2.  „Skanuję…” → poczekaj kilka sekund, nic nie dotykaj.\n" +
            "3.  Na końcu dotknij „Zainstaluj” albo „Zainstaluj mimo to”.\n" +
            "Nie dotykaj „Nie instaluj” ani „Anuluj”.", 13f, 0xFFD6E4FF.toInt()).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) })
        kol.addView(instr, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val przyciski = android.widget.LinearLayout(a).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, dp(12), 0, 0) }
        przyciski.addView(t("Później", 15f, 0xFFBDBDBD.toInt()).apply { setPadding(dp(18), dp(12), dp(18), dp(12)); setOnClickListener { d.dismiss() } })
        przyciski.addView(t("Zainstaluj", 15f, android.graphics.Color.WHITE, true).apply { setPadding(dp(22), dp(12), dp(22), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE0245E.toInt()); cornerRadius = dp(22).toFloat() }
            setOnClickListener { d.dismiss(); instaluj(a, o.optString("url")) } })
        kol.addView(przyciski)
        d.setContentView(kol)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        d.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.9).toInt(), -2)
        d.show()
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
