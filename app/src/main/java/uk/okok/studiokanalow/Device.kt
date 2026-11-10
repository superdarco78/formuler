package uk.okok.studiokanalow

import android.content.Context
import android.provider.Settings
import kotlin.math.abs

/**
 * Stale dane urzadzenia do logowania kodem/MAC - klient nic nie wpisuje.
 * Kod i MAC sa wyliczane z identyfikatora urzadzenia, wiec sa stale dla danego sprzetu.
 */
object Device {

    private fun androidId(ctx: Context): String =
        try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "0"
        } catch (e: Exception) { "0" }

    /** 6-cyfrowy kod parowania. */
    fun code(ctx: Context): String {
        val h = abs(androidId(ctx).hashCode().toLong())
        return String.format("%06d", h % 1000000L)
    }

    /** Wirtualny MAC (stały dla urządzenia), format 00:1A:79:xx:xx:xx. */
    fun mac(ctx: Context): String {
        val h = androidId(ctx).hashCode().toLong() and 0xFFFFFFL
        val p = String.format("%06X", h)
        return "00:1A:79:${p.substring(0, 2)}:${p.substring(2, 4)}:${p.substring(4, 6)}"
    }
}
