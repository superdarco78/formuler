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

    private fun fill(v: VodInfo, logo: String?) {
        if (v.poster.isNotEmpty() && logo.isNullOrEmpty()) b.ivPoster.load(v.poster)
        b.tvMeta.text = listOf(v.year, v.genre).filter { it.isNotEmpty() }.joinToString(" • ")
        b.tvRating.text = listOf(
            if (v.rating.isNotEmpty()) "★ ${v.rating}" else "", v.duration
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        b.tvPlot.text = v.plot
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

    private fun render() {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(getColor(if (i == tab) R.color.tx else R.color.muted))
            t.paint.isUnderlineText = i == tab
        }
        val none = getString(R.string.det_none)
        val v = info
        b.tvTabContent.setOnClickListener(null)
        b.tvTabContent.text = when (tab) {
            0 -> if (v != null && v.trailer.isNotEmpty()) {
                b.tvTabContent.setOnClickListener {
                    val id = v.trailer
                    val u = if (id.startsWith("http")) id else "https://www.youtube.com/watch?v=$id"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
                getString(R.string.det_open_trailer)
            } else {
                b.tvTabContent.setOnClickListener { odtworzZwiastun() }
                "▶  Odtwórz zwiastun"
            }
            1 -> v?.cast?.takeIf { it.isNotEmpty() } ?: none
            2 -> if (intent.getBooleanExtra("serial", false))
                    (if (sezony.isEmpty()) none else "Sezonów: ${sezony.size}\nOdcinków: ${sezony.sumOf { it.second.size }}")
                else listOf((intent.getStringExtra("url") ?: "").substringAfterLast('.', "").uppercase(), v?.media ?: "", Session.opt(this, "nosnik_" + (intent.getStringExtra("id") ?: ""))).filter { x -> x.isNotEmpty() }.joinToString("\n").ifEmpty { none }
            else -> v?.let {
                listOfNotNull(
                    it.premiera.ifEmpty { it.year }.takeIf { x -> x.isNotEmpty() }?.let { x -> "Premiera: $x" },
                    it.genre.takeIf { x -> x.isNotEmpty() }?.let { x -> "Gatunek: $x" },
                    it.duration.takeIf { x -> x.isNotEmpty() }?.let { x -> "Czas: $x" },
                    it.rating.takeIf { x -> x.isNotEmpty() }?.let { x -> "Ocena: $x" },
                    it.director.takeIf { x -> x.isNotEmpty() }?.let { x -> "Reżyseria: $x" },
                    it.country.takeIf { x -> x.isNotEmpty() }?.let { x -> "Kraj: $x" }
                ).joinToString("\n")
            }?.ifEmpty { none } ?: none
        }
    }
}
