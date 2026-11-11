package uk.okok.studiokanalow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityDetailsBinding

/** Ekran szczegolow filmu: okladka, opis, obsada, zwiastun, detale (dane z edytora/serwera). */
class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding
    private var info: VodInfo? = null
    private var tab = 0
    private lateinit var tabs: List<TextView>
    private var sezony: List<Pair<String, List<uk.okok.studiokanalow.model.Channel>>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val name = intent.getStringExtra("name") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        val logo = intent.getStringExtra("logo")
        val id = intent.getStringExtra("id") ?: ""
        val demo = intent.getBooleanExtra("demo", false)
        val serial = intent.getBooleanExtra("serial", false)

        b.tvName.text = name
        stylMyTvOnline()
        if (!logo.isNullOrEmpty()) b.ivPoster.load(logo)
        b.btnWatch.setOnClickListener {
            if (serial) { pokazSezony(name, demo); return@setOnClickListener }
            val i = Intent(this, PlayerActivity::class.java)
            i.putExtra("url", url); i.putExtra("name", name); i.putExtra("demo", demo)
            startActivity(i)
        }
        b.btnBack.setOnClickListener { finish() }
        val fch = uk.okok.studiokanalow.model.Channel(id, name, logo, null, 0, url)
        fun odswiezGwiazdke() { b.btnFav.text = if (Session.isFav(this, "vod", id)) "★" else "☆" }
        odswiezGwiazdke()
        b.btnFav.setOnClickListener { Session.toggleFav(this, "vod", fch); odswiezGwiazdke() }

        tabs = listOf(b.tabTrailer, b.tabCast, b.tabMedia, b.tabDetails)
        tabs.forEachIndexed { i, t -> t.setOnClickListener { tab = i; render() } }
        render()

        if (!demo && serial) {
            lifecycleScope.launch {
                try {
                    val (vi, sz) = Api.seriesDetails(Session.server(this@DetailsActivity), Session.user(this@DetailsActivity),
                        Session.pass(this@DetailsActivity), id)
                    info = vi; sezony = sz
                    vi?.let { fill(it, logo) }
                    rap("serial id=" + id + " sezony=" + sz.size + " odc=" + sz.sumOf { it.second.size } + " opis=" + (vi?.plot?.length ?: -1))
                } catch (e: Exception) { rap("serial blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(100)) }
                render()
            }
        } else if (!demo) {
            lifecycleScope.launch {
                var vi = Api.vodInfo(Session.server(this@DetailsActivity), Session.user(this@DetailsActivity),
                    Session.pass(this@DetailsActivity), id)
                if (vi == null || vi.plot.isEmpty() || (vi.poster.isEmpty() && logo.isNullOrEmpty())) {
                    val it = try { Api.itunes(name) } catch (e: Exception) { null }
                    if (it != null) {
                        vi = if (vi == null) it else vi.copy(plot = vi.plot.ifEmpty { it.plot }, poster = vi.poster.ifEmpty { it.poster },
                            genre = vi.genre.ifEmpty { it.genre }, year = vi.year.ifEmpty { it.year })
                        rap("film id=" + id + " uzupelniono z bazy filmow")
                    }
                }
                info = vi
                info?.let { fill(it, logo) }
                rap("film id=" + id + " opis=" + (info?.plot?.length ?: -1) + " obsada=" + (info?.cast?.length ?: -1) + " zwiastun=" + (info?.trailer?.length ?: -1))
                render()
            }
        }
    }

    private fun rap(m: String) {
        lifecycleScope.launch { try { Api.raportuj(Device.id(this@DetailsActivity), "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }) + " OK Player " + m) } catch (_: Exception) {} }
    }

    private fun pokazSezony(tytul: String, demo: Boolean) {
        if (sezony.isEmpty()) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tytul).setMessage("Brak odcinków.").setPositiveButton("OK", null).show(); return
        }
        fun dpx(v: Int) = (v * resources.displayMetrics.density).toInt()
        val bs = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0F1A.toInt()); setPadding(dpx(14), dpx(16), dpx(14), dpx(10)) }
        root.addView(TextView(this).apply { text = tytul; setTextColor(0xFFFFFFFF.toInt()); textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 0, 0, dpx(10)) })
        val chips = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val lista = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val sv = android.widget.ScrollView(this).apply { addView(lista) }
        if (sezony.size > 1) root.addView(hs)
        root.addView(sv, android.widget.LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.6).toInt()))
        fun rysuj(i: Int) {
            chips.removeAllViews()
            sezony.forEachIndexed { k, s ->
                chips.addView(TextView(this).apply {
                    text = s.first; textSize = 14f; setPadding(dpx(16), dpx(8), dpx(16), dpx(8))
                    setTextColor(if (k == i) 0xFFFFFFFF.toInt() else 0xFFA0A8C0.toInt())
                    setBackgroundColor(if (k == i) 0xFF4F7BFF.toInt() else 0xFF1B2236.toInt())
                    val lp = android.widget.LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, dpx(8), dpx(10)); layoutParams = lp
                    setOnClickListener { rysuj(k) }
                })
            }
            lista.removeAllViews()
            for (e in sezony[i].second) {
                val karta = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, dpx(8), 0, dpx(8))
                    setOnClickListener {
                        bs.dismiss()
                        startActivity(Intent(this@DetailsActivity, PlayerActivity::class.java)
                            .putExtra("url", e.directUrl ?: "").putExtra("name", "$tytul — ${e.name}").putExtra("demo", demo))
                    }
                }
                val img = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1B2236.toInt()); if (!e.logo.isNullOrEmpty()) load(e.logo) else setImageResource(R.drawable.ic_movie) }
                karta.addView(img, android.widget.LinearLayout.LayoutParams(dpx(128), dpx(72)))
                val txt = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dpx(12), 0, 0, 0) }
                txt.addView(TextView(this).apply { text = e.name.removePrefix("Odcinek ").let { x -> "E$x" }; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (e.czas.isNotEmpty()) txt.addView(TextView(this).apply { text = e.czas; setTextColor(0xFFF5B945.toInt()); textSize = 12f })
                if (e.opis.isNotEmpty()) txt.addView(TextView(this).apply { text = e.opis; setTextColor(0xFFA0A8C0.toInt()); textSize = 12f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                karta.addView(txt, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                lista.addView(karta)
            }
        }
        rysuj(0)
        bs.setContentView(root); bs.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Wyglad 1:1 jak MyTvOnline: czarne tlo, jasny przycisk Ogladaj, szare kolko z gwiazdka. */
    private fun stylMyTvOnline() {
        b.root.setBackgroundColor(0xFF000000.toInt())
        b.tvName.setTextColor(0xFFFFFFFF.toInt())
        b.tvMeta.setTextColor(0xFFE6E6E6.toInt()); b.tvMeta.textSize = 16f
        b.tvRating.setTextColor(0xFFFFFFFF.toInt()); b.tvRating.textSize = 16f
        b.tvPlot.setTextColor(0xFFA6A6A6.toInt())
        b.btnWatch.text = "▶   Oglądaj"
        b.btnWatch.isAllCaps = false
        b.btnWatch.textSize = 18f
        b.btnWatch.setTextColor(0xFF111111.toInt())
        (b.btnWatch as? com.google.android.material.button.MaterialButton)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE6E6E6.toInt())
            cornerRadius = dp(30); insetTop = 0; insetBottom = 0; elevation = 0f; stateListAnimator = null
            rippleColor = android.content.res.ColorStateList.valueOf(0x33000000)
        }
        (b.btnFav as? com.google.android.material.button.MaterialButton)?.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2A2A2A.toInt())
            cornerRadius = dp(30); insetTop = 0; insetBottom = 0; elevation = 0f; stateListAnimator = null
        }
        b.btnBack.backgroundTintList = android.content.res.ColorStateList.valueOf(0x99000000.toInt())
        b.btnFav.setTextColor(0xFFBDBDBD.toInt())
    }

    /** "01:44:59" / "105" / "105 min" -> "1godz. 45m" */
    private fun czasLadny(d: String): String {
        val cz = d.trim()
        val min = Regex("^(\\d+):(\\d{1,2})(?::(\\d{1,2}))?$").find(cz)?.let { m ->
            val a = m.groupValues[1].toInt(); val bb = m.groupValues[2].toInt(); val c = m.groupValues[3].toIntOrNull()
            if (c != null) a * 60 + bb + (if (c >= 30) 1 else 0) else a * 60 + bb
        } ?: Regex("(\\d+)").find(cz)?.groupValues?.get(1)?.toIntOrNull()
        if (min == null || min <= 0) return cz
        return if (min >= 60) "${min / 60}godz. ${min % 60}m" else "${min}m"
    }

    private fun procent(r: String): String {
        val x = r.replace(',', '.').toDoubleOrNull() ?: return ""
        if (x <= 0) return ""
        return "${Math.round(if (x <= 10) x * 10 else x)}%"
    }

    /** Linia: [18] • [TMDb] 73% • 1godz. 45m */
    private fun liniaOceny(v: VodInfo): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        fun kropka() { if (sb.isNotEmpty()) sb.append("  •  ") }
        fun plakietka(t: String, tlo: Int, kol: Int) {
            val st = sb.length; sb.append(t)
            sb.setSpan(Plakietka(tlo, kol, dp(6).toFloat(), dp(7).toFloat()), st, sb.length, 0)
        }
        if (v.wiek.isNotEmpty() && v.wiek != "0") { plakietka(v.wiek, 0xFF3A3A3A.toInt(), 0xFFFFFFFF.toInt()) }
        val pr = procent(v.rating)
        if (pr.isNotEmpty()) {
            kropka(); plakietka("TMDb", 0xFF1ED98A.toInt(), 0xFF000000.toInt())
            val st = sb.length; sb.append(" $pr")
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
        }
        if (v.duration.isNotEmpty()) { kropka(); sb.append(czasLadny(v.duration)) }
        return sb
    }

    private fun fill(v: VodInfo, logo: String?) {
        if (v.poster.isNotEmpty() && logo.isNullOrEmpty()) b.ivPoster.load(v.poster)
        b.tvMeta.text = listOf(v.year, v.genre).filter { it.isNotEmpty() }.joinToString("  •  ")
        b.tvRating.text = liniaOceny(v)
        ustawOpis(v.plot)
    }

    private var opisRozwiniety = false

    /** Opis jak w MyTvOnline: 3 linie z "… Więcej", po kliknieciu calosc z "Mniej". */
    private fun ustawOpis(pelny: String) {
        val tv = b.tvPlot
        tv.maxLines = Int.MAX_VALUE
        if (pelny.isEmpty()) { tv.text = ""; return }
        fun zDopiskiem(t: String, dop: String): CharSequence {
            val sb = android.text.SpannableStringBuilder(t).append("  ")
            val st = sb.length
            sb.append(dop)
            sb.setSpan(android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()), st, sb.length, 0)
            sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, sb.length, 0)
            return sb
        }
        tv.setOnClickListener { opisRozwiniety = !opisRozwiniety; ustawOpis(pelny) }
        if (opisRozwiniety) { tv.text = zDopiskiem(pelny, "Mniej"); return }
        tv.text = pelny
        tv.post {
            val l = tv.layout ?: return@post
            if (l.lineCount <= 3) { tv.setOnClickListener(null); return@post }
            var koniec = l.getLineEnd(2)
            var krotki: CharSequence
            do {
                krotki = zDopiskiem(pelny.substring(0, koniec).trimEnd() + "…", "Więcej")
                val sl = android.text.StaticLayout(krotki, tv.paint, l.width,
                    android.text.Layout.Alignment.ALIGN_NORMAL, tv.lineSpacingMultiplier, tv.lineSpacingExtra, tv.includeFontPadding)
                if (sl.lineCount <= 3) break
                koniec -= 2
            } while (koniec > 10)
            tv.text = krotki
        }
    }

    private var zwiastunId: String? = null
    private var zwiastunSzukany = false

    /** Id filmu YouTube ze zwiastunem (szukane raz). */
    private suspend fun idZwiastuna(): String? {
        if (zwiastunSzukany) return zwiastunId
        val nazwa = (intent.getStringExtra("name") ?: "").replace(Regex("\\(\\d{4}\\)"), "").trim()
        val rok = info?.year ?: ""
        val q = Uri.encode("$nazwa $rok zwiastun")
        zwiastunId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val req = okhttp3.Request.Builder().url("https://www.youtube.com/results?search_query=$q")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .header("Accept-Language", "pl-PL,pl;q=0.9").header("Cookie", "CONSENT=YES+1; SOCS=CAI").build()
                val html = okhttp3.OkHttpClient().newCall(req).execute().use { it.body?.string() ?: "" }
                Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
            } catch (e: Exception) { null }
        }
        zwiastunSzukany = true
        return zwiastunId
    }

    /** Szuka zwiastuna na YouTube (telefon laczy sie z YouTube bezposrednio) i otwiera pierwszy wynik. */
    private fun odtworzZwiastun() {
        val nazwa = (intent.getStringExtra("name") ?: "").replace(Regex("\\(\\d{4}\\)"), "").trim()
        val q = Uri.encode(nazwa + " zwiastun")
        val szukaj = Uri.parse("https://www.youtube.com/results?search_query=$q")
        android.widget.Toast.makeText(this, "Szukam zwiastuna…", android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val req = okhttp3.Request.Builder().url(szukaj.toString())
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        .header("Accept-Language", "pl-PL,pl;q=0.9")
                        .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                        .build()
                    val html = okhttp3.OkHttpClient().newCall(req).execute().use { it.body?.string() ?: "" }
                    Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
                } catch (e: Exception) { null }
            }
            try { rap("zwiastun " + (id ?: "brak")) } catch (_: Exception) {}
            val cel = if (id != null) Uri.parse("https://www.youtube.com/watch?v=$id") else szukaj
            startActivity(Intent(Intent.ACTION_VIEW, cel))
        }
    }

    /** Kontener pod zakladkami (obrazy, siatka obsady, karta detali). */
    private val kontener by lazy {
        android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val rodzic = b.tvTabContent.parent as android.view.ViewGroup
            rodzic.addView(this, rodzic.indexOfChild(b.tvTabContent) + 1,
                android.widget.LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun tekst(t: CharSequence, rozm: Float, kol: Int, gruby: Boolean = false) = TextView(this).apply {
        text = t; textSize = rozm; setTextColor(kol); if (gruby) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun render() {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) 0xFFFFFFFF.toInt() else 0xFF9E9E9E.toInt())
            t.paint.isUnderlineText = i == tab
            t.invalidate()
        }
        val none = getString(R.string.det_none)
        val v = info
        val serial = intent.getBooleanExtra("serial", false)
        kontener.removeAllViews()
        b.tvTabContent.visibility = android.view.View.VISIBLE
        b.tvTabContent.gravity = android.view.Gravity.START
        b.tvTabContent.setOnClickListener(null)
        when (tab) {
            0 -> {
                b.tvTabContent.visibility = android.view.View.GONE
                val ramka = android.widget.FrameLayout(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(8).toFloat() }
                    clipToOutline = true
                }
                val mini = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                ramka.addView(mini, android.widget.FrameLayout.LayoutParams(-1, -1))
                val napis = tekst("Szukam zwiastuna…", 16f, 0xFFBDBDBD.toInt()).apply { gravity = android.view.Gravity.CENTER }
                ramka.addView(napis, android.widget.FrameLayout.LayoutParams(-1, -1))
                kontener.addView(ramka, android.widget.LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16))
                lifecycleScope.launch {
                    val yt = v?.trailer?.takeIf { it.isNotEmpty() }?.let { t -> Regex("([A-Za-z0-9_-]{11})$").find(t.substringBefore("&"))?.groupValues?.get(1) } ?: idZwiastuna()
                    if (tab != 0) return@launch
                    if (yt == null) {
                        napis.text = "▶  Zwiastun na YouTube"; napis.setTextColor(0xFFFFFFFF.toInt())
                        val nazwa = (intent.getStringExtra("name") ?: "").replace(Regex("\\(\\d{4}\\)"), "").trim()
                        ramka.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode("$nazwa ${v?.year ?: ""} zwiastun")))) }
                        return@launch
                    }
                    mini.load("https://img.youtube.com/vi/$yt/hqdefault.jpg")
                    napis.text = "▶"; napis.textSize = 54f; napis.setTextColor(0xFFFFFFFF.toInt())
                    napis.setShadowLayer(12f, 0f, 0f, 0xFF000000.toInt())
                    ramka.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$yt"))) }
                }
            }
            1 -> {
                val lista = v?.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                if (lista.isEmpty()) { b.tvTabContent.gravity = android.view.Gravity.CENTER; b.tvTabContent.text = "Brak danych" }
                else {
                    b.tvTabContent.visibility = android.view.View.GONE
                    lista.chunked(2).forEach { para ->
                        val rz = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(14)) }
                        para.forEach { aktor ->
                            val k = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
                            val inic = aktor.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("")
                            val ramka = android.widget.FrameLayout(this).apply {
                                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(8).toFloat() }
                                clipToOutline = true
                            }
                            ramka.addView(tekst(inic, 40f, 0xFF8A8A8A.toInt(), true).apply { gravity = android.view.Gravity.CENTER }, android.widget.FrameLayout.LayoutParams(-1, -1))
                            val foto = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP }
                            ramka.addView(foto, android.widget.FrameLayout.LayoutParams(-1, -1))
                            lifecycleScope.launch { val u = Api.zdjecieAktora(aktor); if (u.isNotEmpty()) foto.load(u) }
                            k.addView(ramka, android.widget.LinearLayout.LayoutParams(-1, dp(220)))
                            k.addView(tekst(aktor, 17f, 0xFFFFFFFF.toInt()).apply { setPadding(0, dp(6), 0, 0) })
                            rz.addView(k, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                        }
                        if (para.size == 1) rz.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                        kontener.addView(rz)
                    }
                }
            }
            2 -> {
                val txt = if (serial) (if (sezony.isEmpty()) "" else "Sezonów: ${sezony.size}\nOdcinków: ${sezony.sumOf { it.second.size }}")
                    else listOf((intent.getStringExtra("url") ?: "").substringAfterLast('.', "").uppercase(), v?.media ?: "", Session.opt(this, "nosnik_" + (intent.getStringExtra("id") ?: ""))).filter { x -> x.isNotEmpty() }.joinToString("\n")
                val obraz = v?.backdrop?.ifEmpty { null } ?: v?.poster?.ifEmpty { null }
                if (obraz != null) {
                    kontener.addView(android.widget.ImageView(this).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; load(obraz)
                        clipToOutline = true
                        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(8).toFloat() }
                    }, android.widget.LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 9 / 16)))
                }
                b.tvTabContent.text = txt.ifEmpty { if (obraz == null) none else "" }
                if (txt.isEmpty() && obraz != null) b.tvTabContent.visibility = android.view.View.GONE
                // tekst pod obrazem
                if (obraz != null && txt.isNotEmpty()) { b.tvTabContent.visibility = android.view.View.GONE; kontener.addView(tekst(txt, 15f, 0xFFE6E6E6.toInt()).apply { setPadding(dp(16), dp(12), dp(16), 0) }) }
            }
            else -> {
                if (v == null) { b.tvTabContent.text = none; return }
                b.tvTabContent.visibility = android.view.View.GONE
                val karta = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(10).toFloat() }
                }
                val ocena = v.rating.replace(',', '.').toDoubleOrNull()?.let { r -> " (" + (if (r > 10) r / 10 else r).let { x -> String.format(java.util.Locale.US, "%.1f", x) } + ")" } ?: ""
                val tytul = (intent.getStringExtra("name") ?: "").let { n -> if (v.year.isNotEmpty() && !n.contains(v.year)) "$n (${v.year})" else n } + ocena
                karta.addView(tekst(tytul, 24f, 0xFFFFFFFF.toInt(), true))
                val meta = listOf(v.year, v.genre).filter { it.isNotEmpty() }.joinToString("  •  ")
                if (meta.isNotEmpty()) karta.addView(tekst(meta, 16f, 0xFFE6E6E6.toInt()).apply { setPadding(0, dp(10), 0, 0) })
                karta.addView(tekst(liniaOceny(v), 16f, 0xFFFFFFFF.toInt()).apply { setPadding(0, dp(8), 0, 0) })
                listOfNotNull(
                    v.premiera.takeIf { it.length >= 8 }?.let { "Premiera: $it" },
                    v.director.takeIf { it.isNotEmpty() }?.let { "Reżyseria: $it" }
                ).forEach { karta.addView(tekst(it, 15f, 0xFFBDBDBD.toInt()).apply { setPadding(0, dp(6), 0, 0) }) }
                if (v.plot.isNotEmpty()) karta.addView(tekst(v.plot, 16f, 0xFFA6A6A6.toInt()).apply { setPadding(0, dp(14), 0, 0) })
                val obsada = v.cast.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (obsada.isNotEmpty()) {
                    karta.addView(android.view.View(this).apply { setBackgroundColor(0xFF3A3A3A.toInt()) },
                        android.widget.LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(16) })
                    karta.addView(tekst("Obsada:", 16f, 0xFFA6A6A6.toInt()).apply { setPadding(0, 0, 0, dp(10)) })
                    obsada.forEach { karta.addView(tekst(it, 17f, 0xFFFFFFFF.toInt()).apply { setPadding(0, dp(3), 0, dp(3)) }) }
                }
                kontener.addView(karta, android.widget.LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(24)) })
            }
        }
    }
}

/** Zaokraglona plakietka w tekscie (jak "TMDb" i "18" w MyTvOnline). */
class Plakietka(private val tlo: Int, private val kol: Int, private val promien: Float, private val pad: Float) : android.text.style.ReplacementSpan() {
    override fun getSize(p: android.graphics.Paint, t: CharSequence, s: Int, e: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val pp = android.graphics.Paint(p).apply { isFakeBoldText = true }
        return (pp.measureText(t, s, e) + pad * 2).toInt()
    }
    override fun draw(c: android.graphics.Canvas, t: CharSequence, s: Int, e: Int, x: Float, top: Int, y: Int, bottom: Int, p: android.graphics.Paint) {
        val pp = android.graphics.Paint(p).apply { isFakeBoldText = true }
        val w = pp.measureText(t, s, e) + pad * 2
        val fm = pp.fontMetrics
        val r = android.graphics.RectF(x, y + fm.ascent - pad / 3, x + w, y + fm.descent + pad / 3)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tlo }
        c.drawRoundRect(r, promien, promien, bg)
        pp.color = kol
        c.drawText(t, s, e, x + pad, y.toFloat(), pp)
    }
}
