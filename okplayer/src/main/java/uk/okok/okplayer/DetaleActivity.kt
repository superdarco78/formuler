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
import kotlinx.coroutines.withTimeoutOrNull
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
    private lateinit var ogladaj: TextView
    private lateinit var sekcjaOdc: LinearLayout
    private var sezon = 0

    /** 2.02: tlo z grafiki Canvy bez rozpychania widoku (panele 9-patch, zdjecia bez wlasnego rozmiaru), z zachowaniem odstepow. */
    private fun android.view.View.tloCanvy(r: Int) {
        val l = paddingLeft; val t = paddingTop; val p = paddingRight; val b = paddingBottom
        val d = context.getDrawable(r)
        background = if (d is android.graphics.drawable.NinePatchDrawable || d == null) d else object : android.graphics.drawable.DrawableWrapper(d) {
            override fun getIntrinsicWidth() = -1; override fun getIntrinsicHeight() = -1 }
        setPadding(l, t, p, b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pilot.wlacz(this)
        Jezyk.wlacz(this)
        val id = intent.getStringExtra("id") ?: ""
        val nazwa = intent.getStringExtra("nazwa") ?: ""
        val serial = intent.getStringExtra("rodzaj") == "s"
        val sv = ScrollView(this).apply { tloCanvy(Skorka.id(this@DetaleActivity, R.drawable.ok_gr_tlo_aplikacji)) }
        kol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(kol)
        val plakat = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        kol.addView(plakat, LinearLayout.LayoutParams(-1, dp(420)))
        kol.addView(tekst(nazwa, 24f, g = true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(14), dp(16), dp(6)) })
        val meta = tekst("", 16f, 0xFFE6E6E6.toInt()).apply { gravity = Gravity.CENTER }
        kol.addView(meta)
        val ocena = tekst("", 16f).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
        kol.addView(ocena)
        val rzad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(18), dp(16), dp(10)) }
        ogladaj = tekst("▶   " + (if (serial) "Odcinki" else "Oglądaj"), 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Skorka.akcent(this@DetaleActivity), Skorka.akcent(this@DetaleActivity))).apply { cornerRadius = dp(30).toFloat() }; setPadding(0, dp(15), 0, dp(15)); isFocusable = true
            setOnClickListener { if (serial) grajKolejny(id, nazwa) else grajFilm(id, nazwa) }
        }
        rzad.addView(ogladaj, LinearLayout.LayoutParams(0, -2, 1f))
        val rodz = if (serial) "s" else "f"
        val gw = tekst(if (Ust.czyUlubiony(this, rodz, id)) "★" else "☆", 24f, 0xFFBDBDBD.toInt()).apply {
            gravity = Gravity.CENTER; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2A2A.toInt()) }; isFocusable = true
            setOnClickListener {
                val teraz = Ust.przelaczUlubiony(this@DetaleActivity, rodz, id, nazwa, intent.getStringExtra("okladka"), intent.getStringExtra("ext") ?: "", "")
                text = if (teraz) "★" else "☆"
                android.widget.Toast.makeText(this@DetaleActivity, Jezyk.t(if (teraz) "Dodano do ulubionych" else "Usunięto z ulubionych").toString(), android.widget.Toast.LENGTH_SHORT).show()
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
        sekcjaOdc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        kol.addView(sekcjaOdc)
        kol.addView(zakl); kol.addView(tabTresc)
        val f = FrameLayout(this)
        // 2.11: rozmyty plakat w tle i kolor z plakatu (tlo, przycisk Ogladaj) - jak w Apple TV
        val rozmyte = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.45f
            if (android.os.Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(80f, 80f, android.graphics.Shader.TileMode.CLAMP)) }
        f.addView(rozmyte, FrameLayout.LayoutParams(-1, -1))
        val nakladka = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33000000, 0xCC000000.toInt(), Color.BLACK)) }
        f.addView(nakladka, FrameLayout.LayoutParams(-1, -1))
        sv.background = null
        intent.getStringExtra("okladka")?.let { u ->
            plakat.load(u) { allowHardware(false); listener(onSuccess = { _, r ->
                (r.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bm ->
                    rozmyte.setImageBitmap(bm)
                    val kol = Skorka.kolor(bm)
                    nakladka.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Skorka.przyciemnij(kol, 0.45f, 0x66), 0xDD000000.toInt(), Color.BLACK))
                    (ogladaj.background as? GradientDrawable)?.colors = intArrayOf(kol, kol)
                    ogladaj.setTextColor(if (Skorka.jasny(kol)) Color.BLACK else Color.WHITE)
                } }) }
        }
        f.addView(sv)
        f.addView(tekst("‹", 26f).apply { setPadding(dp(22), dp(8), dp(22), dp(12)); background = tlo(0x99000000.toInt(), 24); setOnClickListener { finish() } },
            FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(14), dp(14), 0, 0) })
        setContentView(f)
        wybierz(0)
        // Wejscie: plakat delikatnie sie przybliza, tytul, przyciski i opis wjezdzaja po kolei.
        plakat.scaleX = 1.12f; plakat.scaleY = 1.12f; plakat.alpha = 0.4f
        plakat.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        for (n in 1 until kol.childCount) kol.getChildAt(n).apply { alpha = 0f; translationY = dp(20).toFloat()
            animate().alpha(1f).translationY(0f).setStartDelay(120L + n * 60L).setDuration(320).start() }

        lifecycleScope.launch {
            // Najpierw z pamieci telefonu (od razu), potem swieze z serwera.
            val zPam = withContext(Dispatchers.IO) { Serwer.infoZPamieci(this@DetaleActivity, serial, id) }
            if (zPam != null) pokazInfo(zPam, serial, id, nazwa, plakat, meta, ocena, opis)
            // Zapas po 3 s: gdy serwer jeszcze nie odpowiedzial, od razu opis z iTunes (jak w starej aplikacji), a serwer dociaga w tle.
            var zapasPokazany = false
            val zapas = if (zPam == null) launch {
                kotlinx.coroutines.delay(3000)
                if (info == null) withContext(Dispatchers.IO) { Dodatki.itunes(nazwa, serial) }?.let { pokazInfo(JSONObject().put("info", it.put("rating", intent.getDoubleExtra("ocena", 0.0).takeIf { r -> r > 0 }?.toString() ?: "")), serial, id, nazwa, plakat, meta, ocena, opis); zapasPokazany = true }
            } else null
            val o = withContext(Dispatchers.IO) { if (serial) Serwer.infoSerialu(this@DetaleActivity, id) else Serwer.infoFilmu(this@DetaleActivity, id) }
            if (o == null || (o.optJSONObject("info")?.length() ?: 0) == 0) { zapas?.join(); if (zapasPokazany) return@launch } else zapas?.cancel()
            if (o == null && zPam == null) {
                val it2 = withContext(Dispatchers.IO) { Dodatki.itunes(nazwa, serial) }
                if (it2 != null) { pokazInfo(JSONObject().put("info", it2), serial, id, nazwa, plakat, meta, ocena, opis); return@launch }
            }
            if (o == null) {
                if (zPam == null) { tabTresc.removeAllViews(); tabTresc.addView(tekst("Nie udało się pobrać opisu z serwera.", 15f, SZARY))
                    tabTresc.addView(tekst("(" + Serwer.bladInfo + ")", 12f, 0xFF777777.toInt()))
                    lifecycleScope.launch(Dispatchers.IO) { Serwer.raport(this@DetaleActivity, "opis blad $id ${Serwer.bladInfo}") }
                    tabTresc.addView(tekst("⟳  Spróbuj ponownie", 16f).apply { setPadding(0, dp(14), 0, dp(14)); setOnClickListener { recreate() } }) }
                return@launch
            }
            pokazInfo(o, serial, id, nazwa, plakat, meta, ocena, opis)
        }
    }

    private var dodatkiPobrane = false
    private var uzupelniono = false
    private fun pokazInfo(o: JSONObject, serial: Boolean, id: String, nazwa: String, plakat: ImageView, meta: TextView, ocena: TextView, opis: TextView) {
        lifecycleScope.launch {
            // Gdy serwer nie ma opisu - pokazujemy to, co wiemy z listy (tytul, ocena), a zwiastun szukamy po tytule.
            // Gdy serwer nie ma opisu - jak w starej aplikacji: opis, gatunek, rok z iTunes; ocena z listy; zwiastun po tytule.
            val i = o.optJSONObject("info")?.takeIf { it.length() > 0 }
                ?: (withContext(Dispatchers.IO) { Dodatki.itunes(nazwa, serial) } ?: JSONObject().put("name", nazwa).put("plot", "Serwer nie ma jeszcze opisu tego tytułu."))
                    .put("rating", intent.getDoubleExtra("ocena", 0.0).takeIf { it > 0 }?.toString() ?: "")
            info = i
            if (serial) {
                val ep = o.optJSONObject("episodes")
                if (ep != null) odcinki = ep.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }.map { s ->
                    val a = ep.optJSONArray(s); s to (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) } }
                val ost = Ust.ostatniOdcinek(this@DetaleActivity, id).split("|")
                sezon = odcinki.indexOfFirst { it.first == ost.getOrNull(0) }.coerceAtLeast(0)
                pokazSezon(id, nazwa); ustawPrzycisk(id)
            }
            fun t(k: String) = i.optString(k).takeIf { it.isNotEmpty() && it != "null" } ?: ""
            // Uzupelnienie z Wikidata/Wikipedii (raz): rezyser, obsada z rolami, gatunek, kraj, czas, zwiastun, opis - gdy czegos brakuje.
            // Zawsze raz: pelna obsada (wszyscy aktorzy), rezyser, scenariusz, muzyka - takze gdy serwer ma tylko czesc danych.
            if (!uzupelniono) {
                uzupelniono = true
                launch {
                    val w = withContext(Dispatchers.IO) { Dodatki.filmWiki(nazwa, serial) } ?: return@launch
                    var zm = false
                    for (k in listOf("director", "genre", "country", "cast", "releasedate", "duration", "youtube_trailer", "tmdb_id", "writer", "composer", "studio", "awards", "budget", "boxoffice", "o_name")) if (w.optString(k).isNotEmpty() && t(k).isEmpty()) { i.put(k, w.optString(k)); zm = true }
                    // Obsada: najpierw ta z serwera, potem wszyscy pozostali aktorzy z Wikidata.
                    if (t("cast").isNotEmpty() && w.optString("cast").isNotEmpty()) {
                        val jest = t("cast").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        val nowi = w.optString("cast").split(",").map { it.trim() }.filter { n -> n.isNotEmpty() && jest.none { it.equals(n, true) } }
                        if (nowi.isNotEmpty()) { i.put("cast", (jest + nowi).joinToString(", ")); zm = true }
                    }
                    val pl = w.optString("plot_wiki")
                    if (pl.isNotEmpty() && (t("plot").isEmpty() || t("plot").startsWith("Serwer nie ma"))) { i.put("plot", pl); zm = true }
                    w.optJSONObject("_role")?.let { r -> role = role + r.keys().asSequence().associateWith { r.optString(it) } }
                    if (zm) { o.put("info", i); pokazInfo(o, serial, id, nazwa, plakat, meta, ocena, opis) }
                }
            }
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
            t("youtube_trailer").let { y -> Regex("([A-Za-z0-9_-]{11})$").find(y.substringBefore("&"))?.groupValues?.get(1) ?: "" }.takeIf { it.isNotEmpty() }?.let { zwiastun = it }
            zakladka(akt, serial)
            // Zwiastun i role z Wikidata, a gdy brak - zwiastun z YouTube po tytule.
            if (dodatkiPobrane) return@launch
            dodatkiPobrane = true
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
                } else {
                    // Brak na serwerze i w Wikidata: zwiastun z iTunes; gdy i tam brak - wyszukanie na YouTube (nigdy nie wisi "Szukam").
                    intent.getStringExtra("okladka")?.let { im.load(it) }
                    val yt = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode((intent.getStringExtra("nazwa") ?: "") + " zwiastun trailer", "UTF-8")
                    lifecycleScope.launch {
                        val u = withTimeoutOrNull(8000) { withContext(Dispatchers.IO) { Dodatki.zwiastunItunes(intent.getStringExtra("nazwa") ?: "") } } ?: ""
                        if (u.isNotEmpty()) {
                            nap.text = "▶"; nap.textSize = 54f
                            ramka.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(u), "video/*")) }
                        } else {
                            nap.text = "▶  Zwiastun na YouTube"; nap.textSize = 18f
                            ramka.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(yt))) }
                        }
                    }
                }
                tabTresc.addView(ramka, LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16))
            }
            1 -> {
                val rezyserzy = t("director").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val ob0 = t("cast").ifEmpty { t("actors") }.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                // Najpierw rezyser (ze zdjeciem, dotknij = jego strona), potem wszyscy aktorzy.
                rezyserzy.forEach { r -> if (role[r] == null) role = role + (r to "Reżyser") }
                val ob = rezyserzy + ob0.filter { a -> rezyserzy.none { it.equals(a, true) } }
                if (ob.isEmpty()) tabTresc.addView(tekst("Brak danych", 15f, SZARY))
                else tabTresc.addView(tekst("Obsada: " + ob0.size + (if (rezyserzy.isNotEmpty()) "  •  reżyseria: " + rezyserzy.joinToString(", ") else ""), 14f, SZARY).apply { setPadding(dp(4), 0, 0, dp(10)) })
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
                        k.isFocusable = true; k.isClickable = true
                        k.setOnClickListener { startActivity(Intent(this, AktorActivity::class.java).putExtra("aktor", a).putExtra("rola", role[a] ?: "")); overridePendingTransition(R.anim.wejscie_zoom, android.R.anim.fade_out) }
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
                listOf("Premiera" to t("releasedate").ifEmpty { t("releaseDate") }, "Gatunek" to t("genre"), "Reżyseria" to t("director"), "Scenariusz" to t("writer"), "Muzyka" to t("composer"), "Tytuł oryginalny" to t("o_name"), "Wytwórnia" to t("studio"), "Budżet" to t("budget"), "Przychód (box office)" to t("boxoffice"), "Nagrody" to t("awards"), "Kraj" to t("country"),
                    "Czas" to czas(t("duration").ifEmpty { t("episode_run_time") }), "Ocena" to t("rating"), "Obsada" to t("cast"))
                    .filter { it.second.isNotEmpty() }.forEach { (a, b) -> k.addView(tekst("$a: $b", 15f, 0xFFDDDDDD.toInt()).apply { setPadding(0, dp(4), 0, dp(4)) }) }
                if (t("plot").isNotEmpty()) k.addView(tekst(t("plot"), 15f, SZARY).apply { setPadding(0, dp(12), 0, 0) })
                tabTresc.addView(k)
            }
        }
    }

    private fun grajFilm(id: String, nazwa: String) {
        val url = Serwer.adresFilmu(this, id, intent.getStringExtra("ext") ?: "mp4")
        startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("okladka", intent.getStringExtra("okladka")).putExtra("url", url).putExtra("nazwa", nazwa).putExtra("vod", true))
    }

    /** Krotki tytul odcinka: bez "Chicago Fire - S01E01 - ". */
    private fun krotki(t: String): String = t.replace(Regex("^.*?[Ss]\\d{1,2}\\s*[Ee]\\d{1,3}\\s*[-–:.]?\\s*"), "").trim().ifEmpty { t }

    private fun adresOdc(o: JSONObject) = Serwer.adresOdcinka(this, o.optString("id"), o.optString("container_extension").ifEmpty { "mp4" })

    private fun grajOdcinek(serialId: String, tytul: String, s: Int, e: Int) {
        val o = odcinki.getOrNull(s)?.second?.getOrNull(e) ?: return
        Ust.zapiszOdcinek(this, serialId, odcinki[s].first + "|" + e)
        startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("okladka", intent.getStringExtra("okladka")).putExtra("url", adresOdc(o))
            .putExtra("nazwa", "$tytul — S${odcinki[s].first} E${o.optString("episode_num")} " + krotki(o.optString("title"))).putExtra("vod", true))
    }

    /** Ogladaj: ostatni odcinek (jesli nie skonczony) albo nastepny; na poczatku S1 E1. */
    private fun ktoryOdcinek(serialId: String): Pair<Int, Int> {
        val ost = Ust.ostatniOdcinek(this, serialId).split("|")
        val s = odcinki.indexOfFirst { it.first == ost.getOrNull(0) }
        val e = ost.getOrNull(1)?.toIntOrNull() ?: -1
        if (s < 0 || e < 0) return 0 to 0
        val o = odcinki[s].second.getOrNull(e) ?: return 0 to 0
        if (Ust.pozycja(this, adresOdc(o)) > 0) return s to e
        return if (e + 1 < odcinki[s].second.size) s to e + 1 else if (s + 1 < odcinki.size) s + 1 to 0 else s to e
    }

    private fun ustawPrzycisk(serialId: String) {
        if (odcinki.isEmpty()) return
        val (s, e) = ktoryOdcinek(serialId)
        val o = odcinki[s].second.getOrNull(e) ?: return
        val wznow = Ust.pozycja(this, adresOdc(o)) > 0
        ogladaj.text = (if (wznow) "▶   Wznów  " else "▶   Oglądaj  ") + "S${odcinki[s].first} E${o.optString("episode_num")}"
    }

    private fun grajKolejny(serialId: String, tytul: String) {
        if (odcinki.isEmpty()) { android.widget.Toast.makeText(this, Jezyk.t("Ładuję odcinki…").toString(), android.widget.Toast.LENGTH_SHORT).show(); return }
        val (s, e) = ktoryOdcinek(serialId); grajOdcinek(serialId, tytul, s, e)
    }

    override fun onResume() { super.onResume(); if (serialF && odcinki.isNotEmpty()) { pokazSezon(intent.getStringExtra("id") ?: "", intent.getStringExtra("nazwa") ?: ""); ustawPrzycisk(intent.getStringExtra("id") ?: "") } }

    /** Odcinki jak w MyTvOnline: sezony w rzedzie, pod spodem karty z obrazkiem, numerem, tytulem, czasem, opisem i paskiem obejrzenia. */
    private fun pokazSezon(serialId: String, tytul: String) {
        sekcjaOdc.removeAllViews()
        if (odcinki.isEmpty()) return
        sekcjaOdc.visibility = android.view.View.VISIBLE
        sekcjaOdc.addView(tekst("Odcinki", 20f, g = true).apply { setPadding(dp(16), dp(16), dp(16), dp(8)) })
        val chipy = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        odcinki.forEachIndexed { i, (nr, l) ->
            chipy.addView(tekst("Sezon $nr", 15f, Color.WHITE).apply {
                background = if (i == sezon) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Skorka.akcent(this@DetaleActivity), Skorka.akcent(this@DetaleActivity))).apply { cornerRadius = dp(20).toFloat() } else tlo(0xFF2A2A2A.toInt(), 20); setPadding(dp(16), dp(9), dp(16), dp(9)); isFocusable = true
                setOnClickListener { sezon = i; pokazSezon(serialId, tytul) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        sekcjaOdc.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipy) })
        val okladka = intent.getStringExtra("okladka")
        odcinki[sezon].second.forEachIndexed { e, o ->
            val inf = o.optJSONObject("info")
            fun t(k: String) = inf?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
            val wiersz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(10), dp(16), dp(10)); isFocusable = true; isClickable = true
                setOnClickListener { grajOdcinek(serialId, tytul, sezon, e) } }
            val ram = FrameLayout(this).apply { background = tlo(KARTA, 8); clipToOutline = true }
            val im = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            ram.addView(im, FrameLayout.LayoutParams(-1, -1))
            (t("movie_image").takeIf { it.startsWith("http") } ?: okladka)?.let { im.load(it) }
            ram.addView(tekst("▶", 22f).apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 0f, Color.BLACK) }, FrameLayout.LayoutParams(-1, -1))
            val url = adresOdc(o); val poz = Ust.pozycja(this, url); val dl = Ust.dlugosc(this, url)
            if (poz > 0) ram.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = if (dl > 0) (poz * 100 / dl).toInt().coerceIn(3, 100) else 10
                progressTintList = android.content.res.ColorStateList.valueOf(Skorka.akcent(this@DetaleActivity)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66000000)
            }, FrameLayout.LayoutParams(-1, dp(4)).apply { gravity = Gravity.BOTTOM })
            wiersz.addView(ram, LinearLayout.LayoutParams(dp(150), dp(85)))
            val op = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            op.addView(tekst(o.optString("episode_num") + ". " + krotki(o.optString("title")), 15f, g = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            val m = listOf(czas(t("duration").ifEmpty { t("duration_secs").toIntOrNull()?.let { (it / 60).toString() } ?: "" }), t("releasedate").ifEmpty { t("air_date") }.take(10)).filter { it.isNotEmpty() }.joinToString("  •  ")
            if (m.isNotEmpty()) op.addView(tekst(m, 13f, SZARY).apply { setPadding(0, dp(3), 0, 0) })
            t("plot").takeIf { it.isNotEmpty() }?.let { op.addView(tekst(it, 13f, 0xFFBDBDBD.toInt()).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) }) }
            wiersz.addView(op, LinearLayout.LayoutParams(0, -2, 1f))
            sekcjaOdc.addView(wiersz)
        }
    }
}
