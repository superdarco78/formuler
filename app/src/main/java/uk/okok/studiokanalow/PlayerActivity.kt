package uk.okok.studiokanalow

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var proby = 0
    private val probowane = HashSet<String>()
    private var szukam = false

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Gdy kanal przestaje dzialac: najpierw ponawia, potem szuka tego samego kanalu w innych zrodlach (Duza -> Srednia -> Mala). */
    private var ostatniBlad = ""
    private fun awaria() {
        val p = player ?: return
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
        b.tvName.text = intent.getStringExtra("name") ?: ""
        uloz(resources.configuration.orientation)
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
            try { Session.saveLast(this, url, intent.getStringExtra("name") ?: "") } catch (e: Exception) { }
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
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { ostatniBlad = error.errorCodeName + " " + (error.cause?.javaClass?.simpleName ?: "") + " " + (error.cause?.message ?: "").take(80); awaria() }
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY) proby = 0 }
        })
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
