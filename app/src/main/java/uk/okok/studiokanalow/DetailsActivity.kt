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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val name = intent.getStringExtra("name") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        val logo = intent.getStringExtra("logo")
        val id = intent.getStringExtra("id") ?: ""
        val demo = intent.getBooleanExtra("demo", false)

        b.tvName.text = name
        if (!logo.isNullOrEmpty()) b.ivPoster.load(logo)
        b.btnWatch.setOnClickListener {
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

        if (!demo) {
            lifecycleScope.launch {
                info = Api.vodInfo(Session.server(this@DetailsActivity), Session.user(this@DetailsActivity),
                    Session.pass(this@DetailsActivity), id)
                info?.let { fill(it, logo) }
                render()
            }
        }
    }

    private fun fill(v: VodInfo, logo: String?) {
        if (v.poster.isNotEmpty() && logo.isNullOrEmpty()) b.ivPoster.load(v.poster)
        b.tvMeta.text = listOf(v.year, v.genre).filter { it.isNotEmpty() }.joinToString(" • ")
        b.tvRating.text = listOf(
            if (v.rating.isNotEmpty()) "★ ${v.rating}" else "", v.duration
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        b.tvPlot.text = v.plot
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
            } else none
            1 -> v?.cast?.takeIf { it.isNotEmpty() } ?: none
            2 -> (intent.getStringExtra("url") ?: "").substringAfterLast('.', "").uppercase().ifEmpty { none }
            else -> v?.let {
                listOf(it.year, it.genre, it.duration, it.rating).filter { x -> x.isNotEmpty() }.joinToString("\n")
            }?.ifEmpty { none } ?: none
        }
    }
}
