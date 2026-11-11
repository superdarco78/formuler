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
        setContentView(f)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        graj(intent.getStringExtra("url") ?: return)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zmien(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zmien(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        gracz?.release(); gracz = null
    }
}
