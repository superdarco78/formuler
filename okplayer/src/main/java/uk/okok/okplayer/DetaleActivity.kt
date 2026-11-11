package uk.okok.okplayer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ekran filmu / serialu jak w MyTvOnline: plakat, rok, gatunek, TMDb %, czas, Ogladaj, opis z Wiecej, Obsada/Nosnik/Detale, sezony. */
class DetaleActivity : AppCompatActivity() {
    private val KARTA = 0xFF1E1E1E.toInt()
    private val SZARY = 0xFFA6A6A6.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tekst(t: CharSequence, r: Float, k: Int = Color.WHITE, g: Boolean = false) = TextView(this).apply { text = t; textSize = r; setTextColor(k); if (g) setTypeface(typeface, Typeface.BOLD) }
    private fun tlo(k: Int, r: Int) = GradientDrawable().apply { setColor(k); cornerRadius = dp(r).toFloat() }

    private lateinit var kol: LinearLayout
    private lateinit var tabTresc: LinearLayout
    private var info: JSONObject? = null
    private var odcinki: List<Pair<String, List<JSONObject>>> = emptyList()
    private var zwiastun = ""
    private var role: Map<String, String> = emptyMap()
    private var akt = 0
    private var serialF = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id") ?: ""
        val nazwa = intent.getStringExtra("nazwa") ?: ""
        val serial = intent.getStringExtra("rodzaj") == "s"
        val sv = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        kol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(kol)
        val plakat = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; intent.getStringExtra("okladka")?.let { load(it) } }
        kol.addView(plakat, LinearLayout.LayoutParams(-1, dp(420)))
        kol.addView(tekst(nazwa, 24f, g = true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(6)) })
        val meta = tekst("", 16f, 0xFFE6E6E6.toInt()).apply { gravity = Gravity.CENTER }
        kol.addView(meta)
        val ocena = tekst("", 16f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        kol.addView(ocena)
        val rzad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(18), dp(16), dp(10)) }
        val ogladaj = tekst("▶   " + (if (serial) "Odcinki" else "Oglądaj"), 18f, 0xFF111111.toInt()).apply {
            gravity = Gravity.CENTER; background = tlo(0xFFE6E6E6.toInt(), 30); setPadding(0, dp(15), 0, dp(15)); isFocusable = true
            setOnClickListener { if (serial) pokazOdcinki(nazwa) else grajFilm(id, nazwa) }
        }
        rzad.addView(ogladaj, LinearLayout.LayoutParams(0, -2, 1f))
        val rodz = if (serial) "s" else "f"
        val gw = tekst(if (Ust.czyUlubiony(this, rodz, id)) "★" else "☆", 24f, 0xFFBDBDBD.toInt()).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2A2A.toInt()) }; isFocusable = true
            setOnClickListener {
                val teraz = Ust.przelaczUlubiony(this@DetaleActivity, rodz, id, nazwa, intent.getStringExtra("okladka"), intent.getStringExtra("ext") ?: "", "")
                text = if (teraz) "★" else "☆"
                android.widget.Toast.makeText(this@DetaleActivity, if (teraz) "Dodano do ulubionych" else "Usunięto z ulubionych", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        rzad.addView(gw, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(10) })
        kol.addView(rzad)
        val opis = tekst("", 15f, SZARY).apply { setPadding(dp(16), dp(6), dp(16), dp(6)); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        var rozw = false
        opis.setOnClickListener { rozw = !rozw; opis.maxLines = if (rozw) Int.MAX_VALUE else 3 }
        kol.addView(opis)
        val zakl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(16), dp(8), dp(8)) }
        tabTresc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        serialF = serial
        val nazwyZ = listOf("Zwiastun", "Obsada", "Nośnik", "Detale")
        val tv = nazwyZ.map { n -> tekst(n, 16f, SZARY).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)); isFocusable = true } }
        fun wybierz(i: Int) { akt = i; tv.forEachIndexed { k, t -> t.setTextColor(if (k == i) Color.WHITE else SZARY); t.paint.isUnderlineText = k == i; t.invalidate() }; zakladka(i, serial) }
        tv.forEachIndexed { i, t -> t.setOnClickListener { wybierz(i) }; zakl.addView(t, LinearLayout.LayoutParams(0, -2, 1f)) }
        kol.addView(zakl); kol.addView(tabTresc)
        val f = FrameLayout(this)
        f.addView(sv)
        f.addView(tekst("‹", 26f).apply { setPadding(dp(22), dp(8), dp(22), dp(12)); background = tlo(0x99000000.toInt(), 24); setOnClickListener { finish() } },
            FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(14), 0, 0) })
        setContentView(f)
        wybierz(0)

        lifecycleScope.launch {
            val o = withContext(Dispatchers.IO) { if (serial) Serwer.infoSerialu(this@DetaleActivity, id) else Serwer.infoFilmu(this@DetaleActivity, id) }
            val i = o?.optJSONObject("info") ?: return@launch
            info = i
            if (serial) {
                val ep = o.optJSONObject("episodes")
                if (ep != null) odcinki = ep.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }.map { s ->
                    val a = ep.optJSONArray(s); s to (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) } }
            }
            fun t(k: String) = i.optString(k).takeIf { it.isNotEmpty() && it != "null" } ?: ""
            if (t("cover_big").startsWith("http") && intent.getStringExtra("okladka") == null) plakat.load(t("cover_big"))
            if (t("movie_image").startsWith("http") && intent.getStringExtra("okladka") == null) plakat.load(t("movie_image"))
            val rok = t("releasedate").take(4).ifEmpty { t("releaseDate").take(4) }.ifEmpty { t("year") }
            meta.text = listOf(rok, t("genre")).filter { it.isNotEmpty() }.joinToString("  •  ")
            val sb = SpannableStringBuilder()
            t("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { r ->
                val st = sb.length; sb.append(" TMDb "); sb.setSpan(android.text.style.BackgroundColorSpan(0xFF1ED98A.toInt()), st, sb.length, 0)
                sb.setSpan(ForegroundColorSpan(Color.BLACK), st, sb.length, 0); sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, 0)
                sb.append("  " + Math.round(if (r <= 10) r * 10 else r) + "%")
            }
            czas(t("duration").ifEmpty { t("episode_run_time") }).takeIf { it.isNotEmpty() }?.let { c -> if (sb.isNotEmpty()) sb.append("  •  "); sb.append(c) }
            ocena.text = sb
            opis.text = t("plot").ifEmpty { t("description") }
            zwiastun = t("youtube_trailer").let { y -> Regex("([A-Za-z0-9_-]{11})$").find(y.substringBefore("&"))?.groupValues?.get(1) ?: "" }
            wybierz(akt)
            // Zwiastun i role z Wikidata, a gdy brak - zwiastun z YouTube po tytule.
            val (yt, r) = withContext(Dispatchers.IO) { Dodatki.wikidata(t("tmdb_id").ifEmpty { t("tmdb") }) }
            role = r
            if (zwiastun.isEmpty()) zwiastun = yt
            if (zwiastun.isEmpty()) zwiastun = withContext(Dispatchers.IO) { Dodatki.szukajZwiastuna(nazwa.replace(Regex("\\(.*$"), "").trim() + " " + rok) }
            zakladka(akt, serialF)
        }
    }

    private fun czas(d: String): String {
        val m = Regex("^(\\d+):(\\d{1,2})").find(d.trim())?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            ?: Regex("(\\d+)").find(d)?.groupValues?.get(1)?.toIntOrNull() ?: return ""
        if (m <= 0) return ""
        return if (m >= 60) "${m / 60}godz. ${m % 60}m" else "${m}m"
    }

    private fun zakladka(i: Int, serial: Boolean) {
        tabTresc.removeAllViews()
        val inf = info
        if (inf == null) { tabTresc.addView(tekst("Ładuję…", 15f, SZARY)); return }
        fun t(k: String) = inf.optString(k).takeIf { it.isNotEmpty() && it != "null" } ?: ""
        when (i) {
            0 -> {
                val ramka = FrameLayout(this).apply { background = tlo(KARTA, 8); clipToOutline = true }
                val im = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                ramka.addView(im, FrameLayout.LayoutParams(-1, -1))
                val nap = tekst(if (zwiastun.isEmpty()) "Szukam zwiastuna…" else "▶", if (zwiastun.isEmpty()) 16f else 54f).apply { gravity = Gravity.CENTER; setShadowLayer(12f, 0f, 0f, Color.BLACK) }
                ramka.addView(nap, FrameLayout.LayoutParams(-1, -1))
                if (zwiastun.isNotEmpty()) {
                    im.load("https://img.youtube.com/vi/$zwiastun/hqdefault.jpg")
                    ramka.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$zwiastun"))) }
                }
                tabTresc.addView(ramka, LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16))
            }
            1 -> {
                val ob = t("cast").ifEmpty { t("actors") }.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (ob.isEmpty()) tabTresc.addView(tekst("Brak danych", 15f, SZARY))
                ob.chunked(2).forEach { para ->
                    val rz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(14)) }
                    para.forEach { a ->
                        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
                        val f = FrameLayout(this).apply { background = tlo(KARTA, 8); clipToOutline = true }
                        f.addView(tekst(a.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString(""), 36f, 0xFF777777.toInt(), true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                        val foto = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                        f.addView(foto, FrameLayout.LayoutParams(-1, -1))
                        lifecycleScope.launch { val u = withContext(Dispatchers.IO) { Dodatki.zdjecieAktora(a) }; if (u.isNotEmpty()) foto.load(u) }
                        k.addView(f, LinearLayout.LayoutParams(-1, dp(220)))
                        k.addView(tekst(a, 16f).apply { setPadding(0, dp(6), 0, 0) })
                        role[a]?.let { k.addView(tekst(it, 14f, SZARY)) }
                        rz.addView(k, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    if (para.size == 1) rz.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    tabTresc.addView(rz)
                }
            }
            2 -> {
                val obrazy = (inf.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()).filter { it.startsWith("http") }
                if (obrazy.isEmpty()) tabTresc.addView(tekst("Brak danych", 15f, SZARY))
                obrazy.forEach { u ->
                    tabTresc.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = tlo(KARTA, 8); clipToOutline = true; load(u) },
                        LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16).apply { bottomMargin = dp(10) })
                }
            }
            else -> {
                val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = tlo(KARTA, 10); setPadding(dp(18), dp(18), dp(18), dp(18)) }
                listOf("Premiera" to t("releasedate").ifEmpty { t("releaseDate") }, "Gatunek" to t("genre"), "Reżyseria" to t("director"),
                    "Czas" to czas(t("duration").ifEmpty { t("episode_run_time") }), "Ocena" to t("rating"), "Obsada" to t("cast"))
                    .filter { it.second.isNotEmpty() }.forEach { (a, b) -> k.addView(tekst("$a: $b", 15f, 0xFFDDDDDD.toInt()).apply { setPadding(0, dp(4), 0, dp(4)) }) }
                if (t("plot").isNotEmpty()) k.addView(tekst(t("plot"), 15f, SZARY).apply { setPadding(0, dp(12), 0, 0) })
                tabTresc.addView(k)
            }
        }
    }

    private fun grajFilm(id: String, nazwa: String) {
        val url = Serwer.adresFilmu(this, id, intent.getStringExtra("ext") ?: "mp4")
        startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("url", url).putExtra("nazwa", nazwa).putExtra("vod", true))
    }

    private fun pokazOdcinki(tytul: String) {
        if (odcinki.isEmpty()) { android.widget.Toast.makeText(this, "Ładuję odcinki…", android.widget.Toast.LENGTH_SHORT).show(); return }
        val sezony = odcinki.map { "Sezon " + it.first + " (" + it.second.size + ")" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tytul).setItems(sezony) { _, s ->
            val l = odcinki[s].second
            val nazwy = l.map { "E" + it.optString("episode_num") + "  " + it.optString("title") }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Sezon " + odcinki[s].first).setItems(nazwy) { _, e ->
                val o = l[e]
                val url = Serwer.adresOdcinka(this, o.optString("id"), o.optString("container_extension").ifEmpty { "mp4" })
                startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("url", url).putExtra("nazwa", "$tytul — " + nazwy[e]).putExtra("vod", true))
            }.show()
        }.show()
    }
}
