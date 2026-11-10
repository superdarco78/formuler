package uk.okok.studiokanalow

import android.content.Context
import org.json.JSONObject

/**
 * Konfiguracja globalna z panelu (edytora). Aplikacja pobiera ja z serwera przy starcie,
 * zapamietuje ostatnia wersje i dziala dalej, gdy serwer jest niedostepny.
 * Brak konfiguracji = wszystko wlaczone (zachowanie domyslne). Haseł tu nie ma.
 */
object Konfig {
    private fun p(ctx: Context) = ctx.getSharedPreferences("konfig", Context.MODE_PRIVATE)

    suspend fun odswiez(ctx: Context) {
        val j = Api.konfig(Device.code(ctx), Device.mac(ctx)) ?: return
        p(ctx).edit().putString("json", j.toString()).apply()
    }

    private fun obj(ctx: Context): JSONObject =
        try { JSONObject(p(ctx).getString("json", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Przelacznik funkcji: klucz w sekcji "wl" (wlaczone). Domyslnie wlaczone. */
    fun wl(ctx: Context, klucz: String, domyslnie: Boolean = true): Boolean =
        obj(ctx).optJSONObject("wl")?.optBoolean(klucz, domyslnie) ?: domyslnie

    fun tekst(ctx: Context, klucz: String): String = obj(ctx).optString(klucz, "")

    /** Wartosc wymuszona z panelu, albo lokalny wybor klienta. */
    fun styl(ctx: Context): String {
        val w = tekst(ctx, "styl")
        return if (w.isNotEmpty() && wl(ctx, "wymus_styl", false)) w else Session.opt(ctx, "styl", "mol")
    }

    fun skin(ctx: Context, lokalny: Int): Int {
        val w = obj(ctx).optInt("skin", -1)
        return if (w >= 0 && wl(ctx, "wymus_wyglad", false)) w else lokalny
    }

    /** Kolor z panelu (#RRGGBB) albo null. */
    fun kolor(ctx: Context, klucz: String): Int? {
        val t = tekst(ctx, klucz)
        return if (Regex("^#[0-9A-Fa-f]{6}$").matches(t)) (0xFF000000.toInt() or t.substring(1).toLong(16).toInt()) else null
    }

    /** Adres obrazu z panelu - tylko https. */
    fun obraz(ctx: Context, klucz: String): String? = tekst(ctx, klucz).takeIf { it.startsWith("https://") }
}
