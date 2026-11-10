package uk.okok.studiokanalow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Uruchamia aplikacje po wlaczeniu urzadzenia (jesli wlaczone w Ustawieniach i klient jest zalogowany). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (!Session.isLogged(ctx) || !Session.autostart(ctx)) return
            val i = Intent(ctx, LoginActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { }
    }
}
