package uk.okok.okplayer

import android.content.Context

/** 2.03: skorki wygladu z grafik Canvy (bez neonu). a = grafit/srebro (domyslna), b = zloto, c = morski, d = blekit, r = roz. */
object Skorka {
    val lista = listOf("k" to "Kolor z plakatu (nowa, domyślna)", "a" to "Grafit i srebro", "b" to "Złoto", "c" to "Morski turkus", "d" to "Błękit", "r" to "Róż (stara)")
    fun wybrana(c: Context) = Ust.prefs(c).getString("skorka", "k") ?: "k"
    fun nazwa(c: Context) = lista.firstOrNull { it.first == wybrana(c) }?.second ?: lista[0].second
    private fun przyrostek(c: Context) = when (wybrana(c)) { "b" -> "__b"; "c" -> "__c"; "d" -> "__d"; "r" -> "__r"; "k" -> "__k"; else -> "" }
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
    fun akcent(c: Context): Int = when (wybrana(c)) { "k" -> Ust.prefs(c).getInt("akcent_k", 0xFFE8A23A.toInt()); "b" -> 0xFFD8A93B.toInt(); "c" -> 0xFF2FBFB0.toInt(); "d" -> 0xFF3B8FE0.toInt(); "r" -> 0xFFFF3D5A.toInt(); else -> 0xFFB8C2CE.toInt() }

    /** "Kolor z plakatu": akcent wyliczony z plakatu na banerze (najbardziej nasycony, jasny kolor). */
    fun zPlakatu(c: Context, b: android.graphics.Bitmap): Int {
        val m = android.graphics.Bitmap.createScaledBitmap(b, 24, 24, true)
        var best = 0xFFE8A23A.toInt(); var wynik = -1f; val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val px = m.getPixel(x, y); android.graphics.Color.colorToHSV(px, hsv)
            val w = hsv[1] * (if (hsv[2] < 0.25f) 0f else hsv[2])
            if (w > wynik) { wynik = w; best = px }
        }
        android.graphics.Color.colorToHSV(best, hsv); hsv[1] = hsv[1].coerceIn(0.45f, 0.85f); hsv[2] = hsv[2].coerceIn(0.75f, 0.95f)
        val k = android.graphics.Color.HSVToColor(hsv)
        Ust.prefs(c).edit().putInt("akcent_k", k).apply(); return k
    }
    /** Ciemna poswiata tla w kolorze plakatu (gora) przechodzaca w czern. */
    fun tloZAkcentem(k: Int): android.graphics.drawable.GradientDrawable {
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(k, hsv); hsv[2] = 0.32f; hsv[1] = (hsv[1] * 0.9f)
        return android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(android.graphics.Color.HSVToColor(hsv), 0xFF07080B.toInt(), 0xFF000000.toInt()))
    }
}
