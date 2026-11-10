package uk.okok.studiokanalow

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import uk.okok.studiokanalow.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null

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
