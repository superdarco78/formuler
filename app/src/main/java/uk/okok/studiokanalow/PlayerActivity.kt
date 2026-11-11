package uk.okok.studiokanalow

import android.content.Intent
import android.os.Bundle
import coil.load
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityPlayerBinding

/** Lista kanalow, z ktorej otwarto odtwarzacz (do przelaczania kanalow bez wychodzenia). */
object Kontekst { var kanaly: List<uk.okok.studiokanalow.model.Channel> = emptyList() }

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var proby = 0
    private val probowane = HashSet<String>()
    private var szukam = false

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Gdy kanal przestaje dzialac: najpierw ponawia, potem szuka tego samego kanalu w innych zrodlach (Duza -> Srednia -> Mala). */
    /**
     * Serwer wstrzykuje komunikaty do strumienia, gdy zobaczy nazwe VLC (to dla MyTVOnline+),
     * a dostawca wymaga nazwy VLC. Dlatego przekierowanie odczytujemy pod neutralna nazwa,
     * a odtwarzacz laczy sie z dostawca juz jako VLC.
     */
    private suspend fun rozwiaz(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (!url.contains(Api.HOME)) return@withContext url
            if (url.contains("/movie/") || url.contains("/series/") || url.contains("/timeshift/")) return@withContext url + (if (url.contains("?")) "&" else "?") + "okp=1" /* film przez serwer */
            if (url.contains("/live/")) return@withContext url /* kanaly: strumien idzie przez serwer (dostawca blokuje adres telefonu), jak u dekodera */
            val c = okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            c.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "OKPlayer/resolver").build()).execute().use { r ->
                val loc = r.header("Location")
                if (r.code in 300..399 && !loc.isNullOrEmpty()) r.request.url.resolve(loc)?.toString() ?: url else url
            }
        } catch (e: Exception) { url }
    }

    private var ostatniBlad = ""
    private fun czyLiveUrl() = (intent.getStringExtra("url") ?: "").contains("/live/")
    private var statusTv: android.widget.TextView? = null
    private fun status(t: String) { statusTv?.let { it.text = t; it.visibility = if (t.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE } }
    private fun raport(t: String) {
        try {
            val n = try { title?.toString() ?: "" } catch (e: Exception) { "" }
            lifecycleScope.launch { Api.raportuj(Device.id(this@PlayerActivity), "v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }) + " " + n + " " + t) }
        } catch (e: Exception) { }
    }
    private var czuwak: kotlinx.coroutines.Job? = null
    private var rozwiazany = ""
    /** Diagnostyka: co naprawde odpowiada dostawca (bez adresow z haslami - tylko status, typ, hosty). */
    private suspend fun sonda(u: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val c = okhttp3.OkHttpClient.Builder().followRedirects(true)
                .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            c.newCall(okhttp3.Request.Builder().url(u).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build()).execute().use { r ->
                val body = r.body?.source()?.let { src -> try { src.request(4000); src.buffer.snapshot().utf8() } catch (e: Exception) { "" } } ?: ""
                val hosty = Regex("https?://([^/\\s]+)").findAll(body).map { it.groupValues[1] }.toSet().take(3).joinToString(",")
                val sciezek = body.lines().count { it.isNotBlank() && !it.startsWith("#") }
                "sonda " + r.code + " " + (r.header("Content-Type") ?: "?") + " " + (System.currentTimeMillis() - t0) + "ms pocz=" + body.take(7).replace("\n", " ") + " linii=" + sciezek + " hosty=" + hosty + " finalny=" + r.request.url.host + " tresc=" + body.take(160).replace(Regex("[^\\p{L}\\p{N} .:,;#-]"), "_")
            }
        } catch (e: Exception) { "sonda blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(80) }
    }
    /** Zapamietuje rozdzielczosc, kodek i dzwiek filmu (pokazywane potem w zakladce Nosnik). */
    private fun zapiszNosnik(t: androidx.media3.common.Tracks) {
        if (czyLiveUrl()) return
        val id = Regex("/movie/[^/]+/[^/]+/(\\d+)").find(intent.getStringExtra("url") ?: "")?.groupValues?.get(1) ?: return
        fun kodek(m: String?) = when {
            m == null -> ""
            m.contains("avc") -> "H.264"; m.contains("hevc") -> "H.265"; m.contains("av01") -> "AV1"; m.contains("vp9") -> "VP9"
            m.contains("mp4a") -> "AAC"; m.contains("eac3") -> "E-AC-3"; m.contains("ac3") -> "AC-3"; m.contains("dts") -> "DTS"
            m.contains("opus") -> "Opus"; m.contains("mpeg") -> "MP3"; else -> m.substringAfter('/')
        }
        val v = ArrayList<String>(); val au = ArrayList<String>()
        for (g in t.groups) {
            if (!g.isSelected) continue
            val f = g.mediaTrackGroup.getFormat(0)
            if (g.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) v.add((if (f.width > 0) f.width.toString() + "×" + f.height else "") + " " + kodek(f.sampleMimeType))
            else if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) au.add("Dźwięk " + kodek(f.sampleMimeType) + (if (f.channelCount > 0) " " + f.channelCount + " kan." else ""))
        }
        val s = (v + au).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (s.isNotEmpty()) Session.setOpt(this, "nosnik_" + id, s)
    }

    /** Jesli po 20 s nie ma obrazu - zglos stan i przelacz zrodlo. */
    private fun czuwajNad(p: androidx.media3.exoplayer.ExoPlayer) {
        czuwak?.cancel()
        czuwak = lifecycleScope.launch {
            kotlinx.coroutines.delay(12000)
            if (p.playbackState != androidx.media3.common.Player.STATE_READY) {
                raport("brak obrazu po 12 s, stan=" + p.playbackState)
                if (rozwiazany.isNotEmpty()) { val sd = sonda(rozwiazany); raport(sd); if (!czyLiveUrl()) status("Serwer: " + sd.take(70)) }
                if (czyLiveUrl()) awaria() else status("Film wczytuje się dłużej niż zwykle…")
            }
        }
    }
    private fun awaria() {
        val p = player ?: return
        if (!czyLiveUrl()) return
        if (intent.getBooleanExtra("demo", false) || !Session.optBool(this, "failover", true)) return
        if (proby < 2) { proby++; p.prepare(); p.playWhenReady = true; return }
        if (szukam) return
        szukam = true
        val nazwa = norm(intent.getStringExtra("name") ?: "")
        lifecycleScope.launch {
            var znaleziono = false
            val arr = Session.sources(this@PlayerActivity)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") != "XCAPI" || !o.optBoolean("enabled", true)) continue
                val srv = o.optString("server"); val u = o.optString("user"); val ps = o.optString("pass")
                val klucz = "$srv|$u"
                if (klucz in probowane) continue
                probowane.add(klucz)
                try {
                    val lista = Api.liveStreams(srv, u, ps, null)
                    val ch = lista.firstOrNull { norm(it.name) == nazwa } ?: continue
                    val url = Api.liveStreamUrl(srv, u, ps, ch.streamId)
                    if (url == intent.getStringExtra("url")) continue
                    android.widget.Toast.makeText(this@PlayerActivity, "Przełączono na źródło: " + o.optString("name"), android.widget.Toast.LENGTH_LONG).show()
                    proby = 0
                    player?.setMediaItem(MediaItem.fromUri(url)); player?.prepare(); player?.playWhenReady = true
                    znaleziono = true
                    break
                } catch (e: Exception) { }
            }
            szukam = false
            if (!znaleziono) android.widget.Toast.makeText(this@PlayerActivity, "Kanał nie działa w żadnym źródle (" + ostatniBlad + ")", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (intent.getBooleanExtra("archiwum", false)) {
            b.tvName.text = intent.getStringExtra("name") ?: ""
            b.tvEpg.text = "Archiwum • " + (intent.getStringExtra("kanal") ?: "")
            val lg0 = intent.getStringExtra("logo")
            b.ivLogo.visibility = android.view.View.VISIBLE
            if (!lg0.isNullOrEmpty()) b.ivLogo.load(lg0) else b.ivLogo.setImageResource(R.drawable.ic_movie)
            b.pbEpg.visibility = android.view.View.GONE; b.btnPrzewodnik.visibility = android.view.View.GONE
        } else if (!intent.getBooleanExtra("demo", false)) ladujNaglowek(intent.getStringExtra("url") ?: "", intent.getStringExtra("name") ?: "")
        else b.tvName.text = intent.getStringExtra("name") ?: ""
        run {
            val u0 = intent.getStringExtra("url") ?: ""
            val m = Regex("/live/[^/]+/[^/]+/(\\d+)").find(u0)
            if (m != null && !intent.getBooleanExtra("demo", false)) {
                val bt = android.widget.TextView(this).apply {
                    text = "☰  Program"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
                    setPadding(36, 20, 36, 20); setBackgroundColor(0x99000000.toInt())
                    setOnClickListener { pokazPrzewodnik(m.groupValues[1]) }
                }
                val lp = android.widget.FrameLayout.LayoutParams(-2, -2)
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END; lp.setMargins(0, 80, 24, 0)
                b.playerView.overlayFrameLayout?.addView(bt, lp)
            }
        }
        val czyLive = (intent.getStringExtra("url") ?: "").contains("/live/")
        if (!czyLive) {
            // Film / serial / archiwum: sam tytul, bez listy kanalow i logo.
            if (!intent.getBooleanExtra("archiwum", false)) b.ivLogo.visibility = android.view.View.GONE
            b.rvKanaly.visibility = android.view.View.GONE
            b.btnPrzewodnik.visibility = android.view.View.GONE
            if (!intent.getBooleanExtra("archiwum", false)) { b.tvEpg.text = ""; b.tvEpg.visibility = android.view.View.GONE }
        }
        if (czyLive && !intent.getBooleanExtra("demo", false) && !intent.getBooleanExtra("archiwum", false)) {
            if (Kontekst.kanaly.isNotEmpty()) ustawListeKanalow()
            else {
                b.rvKanaly.visibility = android.view.View.GONE
                lifecycleScope.launch {
                    val l = try { Api.liveStreams(Session.server(this@PlayerActivity), Session.user(this@PlayerActivity), Session.pass(this@PlayerActivity), null) } catch (e: Exception) { emptyList() }
                    raport("lista kanalow " + l.size)
                    if (l.isNotEmpty()) {
                        Kontekst.kanaly = l
                        ustawListeKanalow()
                        val lg = l.firstOrNull { it.streamId == biezacyId }?.logo
                        if (!lg.isNullOrEmpty()) { biezaceLogo = lg; b.ivLogo.load(lg) }
                    }
                }
            }
        } else b.rvKanaly.visibility = android.view.View.GONE
        b.playerView.setFullscreenButtonClickListener { pelny ->
            requestedOrientation = if (pelny) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        // Odtwarzacz otwiera sie w pionie (obraz u gory, lista kanalow pod spodem); pelny ekran dopiero po przycisku.
        if (!czyLiveUrl()) {
            statusTv = android.widget.TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; setPadding(24, 14, 24, 14); setBackgroundColor(0x99000000.toInt()); visibility = android.view.View.GONE
            }
            val lp = android.widget.FrameLayout.LayoutParams(-2, -2); lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START; lp.setMargins(24, 0, 24, 140)
            b.playerView.overlayFrameLayout?.addView(statusTv, lp)
            status("Ładowanie…")
        }
        val fp = (intent.getStringExtra("url") ?: "")
        // Filmy i seriale od razu na pelnym ekranie (poziomo); kanaly na zywo w pionie z lista kanalow.
        requestedOrientation = if (fp.contains("/movie/") || fp.contains("/series/")) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        uloz(if (fp.contains("/movie/") || fp.contains("/series/")) android.content.res.Configuration.ORIENTATION_LANDSCAPE else resources.configuration.orientation)
    }

    private fun ustawListeKanalow() {
        val ad = ChannelAdapter({ ch -> zmienKanal(ch) }, null, true)
        b.rvKanaly.visibility = android.view.View.VISIBLE
        b.rvKanaly.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        b.rvKanaly.adapter = ad
        ad.submit(Kontekst.kanaly)
        val nr = Kontekst.kanaly.indexOfFirst { c -> c.streamId == biezacyId }
        if (nr > 0) b.rvKanaly.scrollToPosition(nr)
    }

    /** Przelacza kanal w tym samym odtwarzaczu (jak w MyTVOnline+). */
    private fun zmienKanal(ch: uk.okok.studiokanalow.model.Channel) {
        val p = player ?: return
        val url = ch.directUrl ?: Api.liveStreamUrl(Session.server(this), Session.user(this), Session.pass(this), ch.streamId)
        intent.putExtra("url", url); intent.putExtra("name", ch.name)
        ladujNaglowek(url, ch.name, ch.streamId, ch.logo)
        zamknijPrzewodnik()
        try { Session.saveLast(this, url, ch.name, ch.logo) } catch (e: Exception) { }
        proby = 0; probowane.clear()
        try { probowane.add(Session.server(this) + "|" + Session.user(this)) } catch (e: Exception) { }
        lifecycleScope.launch {
            val u2 = rozwiaz(url)
            rozwiazany = u2
            p.setMediaItem(MediaItem.fromUri(u2)); p.prepare(); czuwajNad(p); p.playWhenReady = true
        }
    }

    private fun pokazPrzewodnik(id: String) {
        lifecycleScope.launch {
            val srv = Session.server(this@PlayerActivity); val usr = Session.user(this@PlayerActivity); val pw = Session.pass(this@PlayerActivity)
            val lista = try { Api.przewodnik(srv, usr, pw, id) } catch (e: Exception) { raport("przewodnik blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(60)); emptyList() }
            if (lista.isEmpty()) { android.widget.Toast.makeText(this@PlayerActivity, "Brak programu dla tego kanału", android.widget.Toast.LENGTH_LONG).show(); return@launch }
            raport("przewodnik " + lista.size + " pozycji, archiwum " + lista.count { it.archiwum })
            val teraz = System.currentTimeMillis() / 1000
            val fmt = java.text.SimpleDateFormat("EEE dd.MM HH:mm", java.util.Locale("pl"))
            val bs = com.google.android.material.bottomsheet.BottomSheetDialog(this@PlayerActivity)
            val sv = android.widget.ScrollView(this@PlayerActivity)
            val col = android.widget.LinearLayout(this@PlayerActivity).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(0xFF1C1C1C.toInt()); setPadding(40, 32, 40, 32) }
            col.addView(android.widget.TextView(this@PlayerActivity).apply { text = "Program i archiwum"; setTextColor(0xFFFFFFFF.toInt()); textSize = 20f; setPadding(0, 0, 0, 20) })
            var teraz1 = -1
            lista.forEachIndexed { i, w ->
                val trwa = w.start <= teraz && (w.koniec == 0L || w.koniec > teraz)
                val przeszla = w.koniec in 1..teraz
                if (trwa) teraz1 = i
                col.addView(android.widget.TextView(this@PlayerActivity).apply {
                    text = (if (trwa) "● TERAZ  " else fmt.format(java.util.Date(w.start * 1000)) + "  ") + w.tytul + (if (przeszla && w.archiwum) "   ▶" else "")
                    textSize = 16f; setPadding(0, 18, 0, 18)
                    setTextColor(if (trwa) 0xFF4F7BFF.toInt() else if (przeszla && !w.archiwum) 0xFF777777.toInt() else 0xFFE0E0E0.toInt())
                    if (przeszla && w.archiwum) setOnClickListener {
                        bs.dismiss()
                        val i2 = Intent(this@PlayerActivity, PlayerActivity::class.java)
                        i2.putExtra("url", Api.archiwumUrl(srv, usr, pw, id, w)); i2.putExtra("name", w.tytul); i2.putExtra("demo", false)
                        startActivity(i2)
                    }
                })
            }
            sv.addView(col); bs.setContentView(sv); bs.show()
        }
    }

    private var biezacyId = ""
    private var biezacaNazwa = ""
    private var biezaceLogo: String? = null
    private var wpisy: List<Api.Wpis> = emptyList()
    private var wybranyDzien = ""
    private var przewodnikOtwarty = false

    /** Naglowek jak w MOL: logo, nazwa kanalu, audycja, godziny i pasek postepu. */
    private fun ladujNaglowek(url: String, nazwa: String, idPodane: String = "", logo: String? = null) {
        b.tvName.text = nazwa
        val id = if (idPodane.isNotEmpty()) idPodane else (Regex("/live/[^/]+/[^/]+/(\\d+)").find(url)?.groupValues?.get(1) ?: "")
        biezacyId = id; wpisy = emptyList(); biezacaNazwa = nazwa
        val lg = logo ?: Kontekst.kanaly.firstOrNull { it.streamId == id }?.logo
        biezaceLogo = lg
        if (!lg.isNullOrEmpty()) b.ivLogo.load(lg) else b.ivLogo.setImageResource(R.drawable.ic_movie)
        b.pbEpg.visibility = android.view.View.GONE
        if (id.isEmpty() || intent.getBooleanExtra("demo", false)) { b.tvEpg.text = ""; b.btnPrzewodnik.visibility = android.view.View.GONE; return }
        b.btnPrzewodnik.visibility = android.view.View.VISIBLE
        b.btnPrzewodnik.setOnClickListener { if (przewodnikOtwarty) zamknijPrzewodnik() else otworzPrzewodnik() }
        b.tvEpg.text = "Program TV: ładowanie…"
        lifecycleScope.launch {
            val l = try { Api.programTv(Session.server(this@PlayerActivity), Session.user(this@PlayerActivity), Session.pass(this@PlayerActivity), id, 3) } catch (e: Exception) { raport("epg blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(60)); emptyList() }
            if (id != biezacyId) return@launch
            if (l.isEmpty()) { b.tvEpg.text = "Program TV: brak danych dla tego kanału"; return@launch }
            pokazProgram(l)
            raport("epg " + l.size + " pozycji")
        }
    }

    private fun pokazProgram(l: List<Api.Audycja>) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val teraz = System.currentTimeMillis() / 1000
        val akt = l.firstOrNull { it.start <= teraz } ?: l.first()
        val nast = l.firstOrNull { it.start > teraz }
        val sb = android.text.SpannableStringBuilder()
        sb.append(akt.tytul); sb.setSpan(android.text.style.ForegroundColorSpan(0xFFFFFFFF.toInt()), 0, sb.length, 0)
        if (akt.koniec > 0) sb.append("   " + fmt.format(java.util.Date(akt.start * 1000)) + " – " + fmt.format(java.util.Date(akt.koniec * 1000)))
        if (nast != null) sb.append("\nDalej: " + fmt.format(java.util.Date(nast.start * 1000)) + "  " + nast.tytul)
        b.tvEpg.text = sb
        if (akt.koniec > akt.start) {
            b.pbEpg.progress = (((teraz - akt.start) * 100) / (akt.koniec - akt.start)).toInt().coerceIn(0, 100)
            b.pbEpg.visibility = android.view.View.VISIBLE
        }
    }

    private fun zamknijPrzewodnik() {
        przewodnikOtwarty = false
        b.guideScroll.visibility = android.view.View.GONE; b.dniScroll.visibility = android.view.View.GONE
        b.rvKanaly.visibility = if (Kontekst.kanaly.isNotEmpty() && !intent.getBooleanExtra("demo", false)) android.view.View.VISIBLE else android.view.View.GONE
        b.btnPrzewodnik.text = "☰ Program"
    }

    /** Przewodnik w panelu (jak w MOL): dni u gory, lista audycji, archiwum po dotknieciu. */
    private fun otworzPrzewodnik() {
        val id = biezacyId; if (id.isEmpty()) return
        przewodnikOtwarty = true
        b.rvKanaly.visibility = android.view.View.GONE
        b.guideScroll.visibility = android.view.View.VISIBLE; b.dniScroll.visibility = android.view.View.VISIBLE
        b.btnPrzewodnik.text = "▤ Kanały"
        lifecycleScope.launch {
            if (wpisy.isEmpty()) {
                b.guideList.removeAllViews()
                b.guideList.addView(android.widget.TextView(this@PlayerActivity).apply { text = "Ładowanie programu…"; setTextColor(0xFFB0B0B0.toInt()); setPadding(0, 24, 0, 0) })
                wpisy = try { Api.przewodnik(Session.server(this@PlayerActivity), Session.user(this@PlayerActivity), Session.pass(this@PlayerActivity), id) } catch (e: Exception) { raport("przewodnik blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(60)); emptyList() }
                if (id != biezacyId) return@launch
                raport("przewodnik " + wpisy.size + " pozycji, archiwum " + wpisy.count { it.archiwum })
            }
            if (wpisy.isEmpty()) {
                b.guideList.removeAllViews()
                b.guideList.addView(android.widget.TextView(this@PlayerActivity).apply { text = "Brak programu dla tego kanału"; setTextColor(0xFFB0B0B0.toInt()); setPadding(0, 24, 0, 0) })
                return@launch
            }
            val dz = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val dni = wpisy.map { dz.format(java.util.Date(it.start * 1000)) }.distinct().sorted()
            val dzis = dz.format(java.util.Date())
            if (wybranyDzien !in dni) wybranyDzien = if (dzis in dni) dzis else dni.last()
            rysujDni(dni, dzis, dz)
            rysujAudycje(dz)
        }
    }

    private fun rysujDni(dni: List<String>, dzis: String, dz: java.text.SimpleDateFormat) {
        b.dniRow.removeAllViews()
        val nazwa = java.text.SimpleDateFormat("EEE dd.MM", java.util.Locale("pl"))
        for (d in dni) {
            val wybrany = d == wybranyDzien
            b.dniRow.addView(android.widget.TextView(this).apply {
                text = if (d == dzis) "Dziś" else nazwa.format(dz.parse(d)!!)
                textSize = 14f; setPadding(32, 14, 32, 14)
                setTextColor(if (wybrany) 0xFFFFFFFF.toInt() else 0xFFB0B0B0.toInt())
                setBackgroundColor(if (wybrany) 0xFF4F7BFF.toInt() else 0xFF2A2A2A.toInt())
                val lp = android.widget.LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, 12, 0); layoutParams = lp
                setOnClickListener { wybranyDzien = d; rysujDni(dni, dzis, dz); rysujAudycje(dz) }
            })
        }
    }

    private fun rysujAudycje(dz: java.text.SimpleDateFormat) {
        val teraz = System.currentTimeMillis() / 1000
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val srv = Session.server(this); val usr = Session.user(this); val pw = Session.pass(this)
        b.guideList.removeAllViews()
        var scrollDo: android.view.View? = null
        for (w in wpisy.filter { dz.format(java.util.Date(it.start * 1000)) == wybranyDzien }) {
            val trwa = w.start <= teraz && (w.koniec == 0L || w.koniec > teraz)
            val przeszla = w.koniec in 1..teraz
            val wiersz = android.widget.TextView(this).apply {
                text = fmt.format(java.util.Date(w.start * 1000)) + "   " + w.tytul + (if (przeszla && w.archiwum) "   ▶ archiwum" else "")
                textSize = 16f; setPadding(0, 20, 0, 20)
                setTextColor(if (trwa) 0xFF4F7BFF.toInt() else if (przeszla && !w.archiwum) 0xFF707070.toInt() else 0xFFE0E0E0.toInt())
                if (trwa) setTypeface(typeface, android.graphics.Typeface.BOLD)
                if (przeszla && w.archiwum) setOnClickListener {
                    val i2 = Intent(this@PlayerActivity, PlayerActivity::class.java)
                    i2.putExtra("url", Api.archiwumUrl(srv, usr, pw, biezacyId, w)); i2.putExtra("name", w.tytul); i2.putExtra("demo", false); i2.putExtra("archiwum", true); i2.putExtra("kanal", biezacaNazwa); i2.putExtra("logo", biezaceLogo)
                    startActivity(i2)
                }
            }
            if (trwa) scrollDo = wiersz
            b.guideList.addView(wiersz)
        }
        scrollDo?.let { v -> b.guideScroll.post { b.guideScroll.scrollTo(0, (v.top - 40).coerceAtLeast(0)) } }
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        uloz(c.orientation)
    }

    /** Pionowo: podglad 16:9 u gory i panel kanalu pod spodem; poziomo: pelny ekran. */
    private fun uloz(o: Int) {
        val poziomo = o == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        b.panel.visibility = if (poziomo) android.view.View.GONE else android.view.View.VISIBLE
        val lp = b.playerView.layoutParams
        if (poziomo) {
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
        }
        b.playerView.layoutParams = lp
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !poziomo)
        val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (poziomo) c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        val url = intent.getStringExtra("url") ?: return
        if (!intent.getBooleanExtra("demo", false)) {
            try { Session.saveLast(this, url, intent.getStringExtra("name") ?: "", intent.getStringExtra("logo")) } catch (e: Exception) { }
        }
        // Nazwa jak w VLC - dostawca czesto podaje prawdziwy strumien tylko takim odtwarzaczom.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
            .build()
        val lang = Session.opt(this, "audio_lang")
        if (lang.isNotEmpty()) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(lang)
                .setPreferredTextLanguage(if (Session.opt(this, "subs") == "1") lang else null)
                .build()
        }
        b.playerView.resizeMode = if (Session.opt(this, "zoom") == "1")
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        b.playerView.player = p
        probowane.clear(); proby = 0
        try { probowane.add(Session.server(this) + "|" + Session.user(this)) } catch (e: Exception) { }
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (!czyLiveUrl()) status("Błąd: " + error.errorCodeName + " " + (error.cause?.message ?: "").take(60)); if (rozwiazany.isNotEmpty() && proby == 0) lifecycleScope.launch { raport(sonda(rozwiazany)) }; raport("blad " + error.errorCodeName + " " + (error.cause?.message ?: "").take(120)); ostatniBlad = error.errorCodeName + " " + (error.cause?.javaClass?.simpleName ?: "") + " " + (error.cause?.message ?: "").take(80); awaria() }
            override fun onPlaybackStateChanged(state: Int) { if (!czyLiveUrl()) { if (state == androidx.media3.common.Player.STATE_BUFFERING) status("Ładowanie…") else if (state == androidx.media3.common.Player.STATE_READY) status("") }; raport("stan=" + state + " gra=" + p.isPlaying); if (state == androidx.media3.common.Player.STATE_READY) { proby = 0; czuwak?.cancel() } }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { raport("obraz " + v.width + "x" + v.height) }
            override fun onTracksChanged(t: androidx.media3.common.Tracks) {
                val o = t.groups.joinToString(",") { g -> g.mediaTrackGroup.getFormat(0).sampleMimeType.toString() + (if (g.isSelected) "+" else "-") + (if (g.isSupported) "ok" else "NIEOBSLUG") }
                raport("sciezki " + o)
                zapiszNosnik(t)
            }
        })
        player = p
        lifecycleScope.launch {
            val u2 = rozwiaz(url)
            if (u2 != url) raport("przekierowanie na " + (try { android.net.Uri.parse(u2).host } catch (e: Exception) { "?" }))
            rozwiazany = u2
            p.setMediaItem(MediaItem.fromUri(u2))
            p.prepare()
            czuwajNad(p)
            p.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
