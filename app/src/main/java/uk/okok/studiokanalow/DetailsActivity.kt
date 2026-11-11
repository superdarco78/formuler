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
                info = Api.vodInfo(Session.server(this@DetailsActivity), Session.user(this@DetailsActivity),
                    Session.pass(this@DetailsActivity), id)
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
        fun odcinki(i: Int) {
            val eps = sezony[i].second
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("$tytul — ${sezony[i].first}")
                .setItems(eps.map { it.name }.toTypedArray()) { _, k ->
                    startActivity(Intent(this, PlayerActivity::class.java)
                        .putExtra("url", eps[k].directUrl ?: "").putExtra("name", "$tytul — ${eps[k].name}").putExtra("demo", demo))
                }.show()
        }
        if (sezony.size == 1) odcinki(0)
        else androidx.appcompat.app.AlertDialog.Builder(this).setTitle(tytul)
            .setItems(sezony.map { "${it.first} (${it.second.size})" }.toTypedArray()) { _, i -> odcinki(i) }.show()
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
                else listOf((intent.getStringExtra("url") ?: "").substringAfterLast('.', "").uppercase(), v?.media ?: "").filter { x -> x.isNotEmpty() }.joinToString("\n").ifEmpty { none }
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
