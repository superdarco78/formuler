package uk.okok.okplayer

import android.content.Context

/** 2.03: skorki wygladu z grafik Canvy (bez neonu). a = grafit/srebro (domyslna), b = zloto, c = morski, d = blekit, r = roz. */
object Skorka {
    val lista = listOf("a" to "Grafit i srebro (uniwersalna)", "b" to "Złoto", "c" to "Morski turkus", "d" to "Błękit", "r" to "Róż (stara)")
    fun wybrana(c: Context) = Ust.prefs(c).getString("skorka", "a") ?: "a"
    fun nazwa(c: Context) = lista.firstOrNull { it.first == wybrana(c) }?.second ?: lista[0].second
    private fun przyrostek(c: Context) = when (wybrana(c)) { "b" -> "__b"; "c" -> "__c"; "d" -> "__d"; "r" -> "__r"; else -> "" }
    /** Grafika w wybranej skorce (gdy jej brak - domyslna). */
    fun id(c: Context, id: Int): Int {
        val s = przyrostek(c); if (s.isEmpty()) return id
        val n = c.resources.getResourceEntryName(id) + s
        val r = c.resources.getIdentifier(n, "drawable", c.packageName)
        return if (r != 0) r else id
    }
    fun nazwaId(c: Context, n: String): Int {
        val s = przyrostek(c)
        val r = if (s.isEmpty()) 0 else c.resources.getIdentifier(n + s, "drawable", c.packageName)
        return if (r != 0) r else c.resources.getIdentifier(n, "drawable", c.packageName)
    }
    /** Kolor akcentu skorki (paski postepu, wyroznienia). */
    fun akcent(c: Context): Int = when (wybrana(c)) { "b" -> 0xFFD8A93B.toInt(); "c" -> 0xFF2FBFB0.toInt(); "d" -> 0xFF3B8FE0.toInt(); "r" -> 0xFFFF3D5A.toInt(); else -> 0xFFB8C2CE.toInt() }
}
