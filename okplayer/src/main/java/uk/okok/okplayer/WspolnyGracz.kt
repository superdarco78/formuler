package uk.okok.okplayer

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Jeden odtwarzacz TV dla calej aplikacji: duzy ekran i mini-okno korzystaja z tego samego,
 * wiec przejscie duzy <-> mini jest plynne (obraz gra dalej, bez ponownego ladowania).
 * wlasciciel: kto teraz pokazuje obraz ("duzy" albo "mini") - tylko on moze go zwolnic.
 */
object WspolnyGracz {
    var gracz: ExoPlayer? = null; private set
    var url: String? = null; private set
    var wlasciciel = ""

    fun pobierz(c: Context): ExoPlayer = gracz ?: run {
        // Serwer przepuszcza strumien przez siebie dla odtwarzaczy przedstawiajacych sie jak VLC (dostawca blokuje telefon).
        val http = DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(c.applicationContext).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().also { gracz = it }
    }

    /** Gra adres; jesli juz go gra - nic nie zmienia (plynnie). Zwraca true, gdy zaczal od nowa. */
    fun graj(c: Context, u: String, odNowa: Boolean = false): Boolean {
        val p = pobierz(c)
        if (!odNowa && u == url && p.playbackState != androidx.media3.common.Player.STATE_IDLE && p.playbackState != androidx.media3.common.Player.STATE_ENDED) { p.playWhenReady = true; return false }
        url = u
        p.setMediaItem(MediaItem.fromUri(u)); p.prepare(); p.playWhenReady = true
        return true
    }

    fun zwolnij(kto: String) {
        if (wlasciciel != kto) return
        gracz?.release(); gracz = null; url = null; wlasciciel = ""
    }
}
