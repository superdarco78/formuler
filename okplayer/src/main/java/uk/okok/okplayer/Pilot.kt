package uk.okok.okplayer

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText

/**
 * Obsluga pilotem (dekoder Formuler, Android TV, telewizor): zaznaczony element dostaje biala ramke
 * i lekko sie powieksza, wiec zawsze widac, gdzie jestesmy. Na telefonie nic nie zmienia (dotyk nie daje zaznaczenia).
 */
object Pilot {
    fun czyTv(c: Context): Boolean {
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !c.packageManager.hasSystemFeature("android.hardware.touchscreen")
    }

    fun wlacz(a: Activity) {
        // 2.08: blokada zrzutow i nagrywania ekranu (jak w Netflixie) - mozna wylaczyc w Ustawieniach
        if (Ust.prefs(a).getBoolean("glob_blokada_zrzutow", false)) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val d = a.resources.displayMetrics.density
        val szer = a.resources.displayMetrics.widthPixels
        a.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { stary, nowy ->
            stary?.let { if (it !is EditText) { it.foreground = null; it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() } }
            if (nowy == null || nowy is EditText || nowy is androidx.recyclerview.widget.RecyclerView) return@addOnGlobalFocusChangeListener
            if (nowy.isInTouchMode) return@addOnGlobalFocusChangeListener
            nowy.foreground = GradientDrawable().apply { setColor(0x00000000); setStroke((3 * d).toInt(), 0xFFFFFFFF.toInt()); cornerRadius = 10 * d }
            if (nowy.width in 1 until (szer * 0.6).toInt()) nowy.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
        }
    }
}
