package uk.okok.okplayer

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Odtwarzacz kanalow na zywo: pelny ekran, strumien przez serwer tv.ok-ok.uk, gora/dol = nastepny/poprzedni kanal. */
class OdtwarzaczActivity : AppCompatActivity() {
    private var gracz: ExoPlayer? = null
    private lateinit var widok: PlayerView
    private lateinit var napis: TextView
    private var grupa: List<Kanal> = emptyList()
    private var biezacy: Kanal? = null
    private var proby = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        widok = PlayerView(this).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); controllerShowTimeoutMs = 3000 }
        f.addView(widok, FrameLayout.LayoutParams(-1, -1))
        napis = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(36, 24, 36, 24); setBackgroundColor(0x99000000.toInt())
        }
        f.addView(napis, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START; setMargins(24, 24, 24, 24) })
        val przyciski = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun prz(t: String, a: () -> Unit) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 18, 32, 18); setBackgroundColor(0x99000000.toInt()); isFocusable = true; setOnClickListener { a() } }
        przyciski.addView(prz("⚙ Opcje") { opcje() })
        if (!intent.getBooleanExtra("vod", false)) przyciski.addView(prz("☰ Program / archiwum") { program() }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginStart = 16 })
        widok.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> przyciski.visibility = v })
        f.addView(przyciski, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; setMargins(24, 24, 24, 24) })
        setContentView(f)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (intent.getBooleanExtra("vod", false)) { pokazNapis(intent.getStringExtra("nazwa") ?: ""); return }
        biezacy = Kanal(intent.getStringExtra("id") ?: "", intent.getStringExtra("nazwa") ?: "", intent.getStringExtra("logo"), intent.getStringExtra("kat") ?: "", 0)
        pokazNapis(biezacy!!.nazwa)
        lifecycleScope.launch {
            val kat = biezacy!!.kat
            grupa = withContext(Dispatchers.IO) { Pamiec.kanaly(this@OdtwarzaczActivity)?.filter { it.kat == kat } ?: emptyList() }
        }
    }

    private fun pokazNapis(t: String) {
        napis.text = t; napis.visibility = android.view.View.VISIBLE
        napis.removeCallbacks(ukryj); napis.postDelayed(ukryj, 4000)
    }
    private val ukryj = Runnable { napis.visibility = android.view.View.GONE }

    private fun graj(url: String) {
        val p = gracz ?: return
        p.setMediaItem(MediaItem.fromUri(url)); p.prepare(); p.playWhenReady = true
    }

    override fun onStart() {
        super.onStart()
        // Serwer przepuszcza strumien przez siebie dla odtwarzaczy przedstawiajacych sie jak VLC (dostawca blokuje telefon).
        val http = DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        widok.player = p; gracz = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lifecycleScope.launch(Dispatchers.IO) { Serwer.raport(this@OdtwarzaczActivity, "graj blad " + error.errorCodeName + " " + (biezacy?.nazwa ?: "")) }
                if (proby < 3) {
                    proby++; pokazNapis("Ponawiam… ($proby/3)")
                    lifecycleScope.launch { delay(1500); graj(intent.getStringExtra("url") ?: return@launch) }
                } else pokazNapis("Kanał nie działa (" + error.errorCodeName + ")")
            }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) proby = 0 }
        })
        val url = intent.getStringExtra("url") ?: return
        graj(url)
        // Filmy i odcinki: wznawianie od miejsca przerwania.
        if (intent.getBooleanExtra("vod", false)) {
            val poz = Ust.pozycja(this, url)
            if (poz > 30_000) { p.seekTo(poz); pokazNapis("Wznawiam od " + poz / 60000 + " min") }
        }
    }

    private fun zmien(o: Int) {
        val b = biezacy ?: return
        if (grupa.isEmpty()) return
        val i = grupa.indexOfFirst { it.id == b.id }
        val n = grupa[((if (i < 0) 0 else i) + o + grupa.size) % grupa.size]
        biezacy = n; proby = 0
        Ust.zapiszOstatni(this, n)
        val url = Serwer.adresKanalu(this, n.id)
        intent.putExtra("url", url)
        pokazNapis(n.nazwa); graj(url)
    }

    private var wylacznik: android.os.CountDownTimer? = null

    private fun wybor(t: String, o: List<String>, z: Int, a: (Int) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(t).setSingleChoiceItems(o.toTypedArray(), z) { d, i -> a(i); d.dismiss() }.setNegativeButton("Zamknij", null).show()
    }

    /** Opcje jak w MyTvOnline: dzwiek, napisy, format obrazu, predkosc (filmy), wylacznik czasowy. */
    private fun opcje() {
        val p = gracz ?: return
        val vod = intent.getBooleanExtra("vod", false)
        val l = mutableListOf("Ścieżka dźwiękowa", "Napisy", "Format obrazu")
        if (vod) l.add("Prędkość odtwarzania")
        l.add("Wyłącznik czasowy")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Opcje").setItems(l.toTypedArray()) { _, i ->
            when (l[i]) {
                "Ścieżka dźwiękowa" -> sciezki(p, androidx.media3.common.C.TRACK_TYPE_AUDIO)
                "Napisy" -> sciezki(p, androidx.media3.common.C.TRACK_TYPE_TEXT)
                "Format obrazu" -> {
                    val t = listOf("Dopasuj" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, "Powiększ (bez pasów)" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Rozciągnij" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)
                    wybor("Format obrazu", t.map { it.first }, t.indexOfFirst { it.second == widok.resizeMode }) { k -> widok.resizeMode = t[k].second }
                }
                "Prędkość odtwarzania" -> { val pr = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f); wybor("Prędkość", pr.map { if (it == 1f) "Normalna" else "${it}x" }, pr.indexOf(p.playbackParameters.speed)) { k -> p.setPlaybackSpeed(pr[k]) } }
                else -> {
                    val m = listOf(0, 15, 30, 60, 90, 120)
                    wybor("Wyłącznik czasowy", m.map { if (it == 0) "Wyłączony" else "$it min" }, -1) { k ->
                        wylacznik?.cancel(); wylacznik = null
                        if (m[k] > 0) wylacznik = object : android.os.CountDownTimer(m[k] * 60_000L, 60_000L) { override fun onTick(x: Long) {}; override fun onFinish() { finish() } }.start()
                    }
                }
            }
        }.show()
    }

    private fun sciezki(p: ExoPlayer, typ: Int) {
        val g = p.currentTracks.groups.filter { it.type == typ }
        val nazwy = ArrayList<String>(); val wyb = ArrayList<Pair<Int, Int>>(); var akt = -1
        if (typ == androidx.media3.common.C.TRACK_TYPE_TEXT) { nazwy.add("Wyłączone"); wyb.add(-1 to -1) }
        g.forEachIndexed { gi, gr -> for (t in 0 until gr.length) {
            val f = gr.getTrackFormat(t)
            val jez = f.language?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale("pl")) } ?: "Ścieżka ${nazwy.size + 1}"
            if (gr.isTrackSelected(t)) akt = nazwy.size
            nazwy.add(listOfNotNull(jez, f.label, if (f.channelCount > 0) "${f.channelCount} kan." else null).distinct().joinToString(" • ")); wyb.add(gi to t)
        } }
        if (nazwy.size <= (if (typ == androidx.media3.common.C.TRACK_TYPE_TEXT) 1 else 0)) { pokazNapis("Brak innych ścieżek"); return }
        wybor(if (typ == androidx.media3.common.C.TRACK_TYPE_AUDIO) "Ścieżka dźwiękowa" else "Napisy", nazwy, if (akt < 0) 0 else akt) { k ->
            val (gi, t) = wyb[k]
            val b = p.trackSelectionParameters.buildUpon()
            if (gi < 0) b.setTrackTypeDisabled(typ, true) else b.setTrackTypeDisabled(typ, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g[gi].mediaTrackGroup, t))
            p.trackSelectionParameters = b.build()
        }
    }

    /** Program kanalu; przeszle audycje z archiwum mozna obejrzec (cofanie). */
    private fun program() {
        val ch = biezacy ?: return
        pokazNapis("Ładuję program…")
        lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { Serwer.przewodnik(this@OdtwarzaczActivity, ch.id) }
            if (l.isEmpty()) { pokazNapis("Brak programu dla tego kanału"); return@launch }
            val teraz = System.currentTimeMillis() / 1000
            val wid = l.filter { it.koniec > teraz - 3 * 86400 }.takeLast(80)
            val fmt = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale("pl"))
            val nazwy = wid.map { w -> fmt.format(java.util.Date(w.start * 1000)) + "  " + w.tytul + (if (w.archiwum && w.koniec < teraz) "  ⏪" else if (w.start <= teraz && w.koniec > teraz) "  ● teraz" else "") }
            androidx.appcompat.app.AlertDialog.Builder(this@OdtwarzaczActivity).setTitle(ch.nazwa + " — program (⏪ = archiwum)").setItems(nazwy.toTypedArray()) { _, i ->
                val w = wid[i]
                if (w.archiwum && w.start < teraz) { val u = Serwer.adresArchiwum(this@OdtwarzaczActivity, ch.id, w); intent.putExtra("url", u); pokazNapis("Archiwum: " + w.tytul); graj(u) }
                else pokazNapis("Ta audycja nie ma archiwum")
            }.setNegativeButton("Zamknij", null).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (!intent.getBooleanExtra("vod", false)) { zmien(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (!intent.getBooleanExtra("vod", false)) { zmien(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { wylacznik?.cancel(); super.onDestroy() }

    override fun onStop() {
        super.onStop()
        gracz?.let { g ->
            if (intent.getBooleanExtra("vod", false) && g.duration > 0) {
                val koniec = g.currentPosition > g.duration - 90_000
                Ust.zapiszPozycje(this, intent.getStringExtra("url") ?: "", if (koniec) 0 else g.currentPosition)
            }
        }
        gracz?.release(); gracz = null
    }
}
