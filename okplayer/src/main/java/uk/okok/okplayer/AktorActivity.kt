package uk.okok.okplayer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ekran aktora: duze zdjecie, nazwisko, pod spodem jego filmy i seriale z listy (do klikniecia), nizej reszta filmografii. */
class AktorActivity : AppCompatActivity() {
    private val KARTA = 0xFF1E1E1E.toInt()
    private val SZARY = 0xFFA6A6A6.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tekst(t: CharSequence, r: Float, k: Int = Color.WHITE, g: Boolean = false) = TextView(this).apply { text = t; textSize = r; setTextColor(k); if (g) setTypeface(typeface, Typeface.BOLD) }
    private fun tlo(k: Int, r: Int) = GradientDrawable().apply { setColor(k); cornerRadius = dp(r).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nazwa = intent.getStringExtra("aktor") ?: ""
        val rola = intent.getStringExtra("rola") ?: ""
        val kol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(kol) }
        setContentView(sv)

        val wstecz = tekst("‹", 30f).apply { gravity = Gravity.CENTER; background = tlo(0xAA222222.toInt(), 26); isFocusable = true; setOnClickListener { finish() } }
        kol.addView(wstecz, LinearLayout.LayoutParams(dp(52), dp(52)))

        val gora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(18)) }
        val ramka = FrameLayout(this).apply { background = tlo(KARTA, 10); clipToOutline = true }
        ramka.addView(tekst(nazwa.split(" ").mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString(""), 40f, 0xFF777777.toInt(), true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        val foto = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        ramka.addView(foto, FrameLayout.LayoutParams(-1, -1))
        gora.addView(ramka, LinearLayout.LayoutParams(dp(150), dp(210)))
        val opis = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0) }
        opis.addView(tekst(nazwa, 24f, g = true))
        if (rola.isNotEmpty()) opis.addView(tekst("jako $rola", 15f, SZARY).apply { setPadding(0, dp(6), 0, 0) })
        val licznik = tekst("Szukam filmów…", 14f, SZARY).apply { setPadding(0, dp(10), 0, 0) }
        opis.addView(licznik)
        gora.addView(opis, LinearLayout.LayoutParams(0, -2, 1f))
        kol.addView(gora)

        val naLiscie = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inne = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        kol.addView(naLiscie); kol.addView(inne)

        lifecycleScope.launch {
            val u = withContext(Dispatchers.IO) { Dodatki.zdjecieAktora(nazwa) }
            if (u.isNotEmpty()) foto.load(u)
        }
        lifecycleScope.launch {
            val wynik = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(25_000) {
                val fg = Dodatki.filmografia(nazwa)
                val klucze = HashMap<String, Int>()
                fg.forEachIndexed { i, f -> listOf(f.first, f.second).map { Dodatki.klucz(it) }.filter { it.length >= 2 }.forEach { k -> klucze.putIfAbsent(k, i) } }
                val trafione = HashSet<Int>()
                val moje = ArrayList<Vod>()
                for (n in listOf("filmy", "seriale")) {
                    for (v in Pamiec.vod(this@AktorActivity, n) ?: emptyList()) {
                        val i = klucze[Dodatki.klucz(v.nazwa)] ?: continue
                        if (moje.none { it.nazwa == v.nazwa && it.rodzaj == v.rodzaj }) { moje.add(v); trafione.add(i) }
                    }
                }
                moje to fg.filterIndexed { i, _ -> i !in trafione }
            } }
            if (wynik == null) { licznik.text = "Nie udało się pobrać filmów (brak odpowiedzi). Wróć i spróbuj ponownie."; return@launch }
            val (moje, reszta) = wynik
            licznik.text = when {
                moje.isEmpty() && reszta.isEmpty() -> "Brak danych o filmach"
                else -> "Na Twojej liście: ${moje.size}   •   Wszystkich: ${moje.size + reszta.size}"
            }
            if (moje.isNotEmpty()) {
                naLiscie.addView(tekst("Na Twojej liście", 19f, g = true).apply { setPadding(0, dp(4), 0, dp(10)) })
                moje.chunked(3).forEach { rzad ->
                    val rz = LinearLayout(this@AktorActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(12)) }
                    rzad.forEach { v -> rz.addView(plakat(v), LinearLayout.LayoutParams(0, -2, 1f)) }
                    repeat(3 - rzad.size) { rz.addView(android.view.View(this@AktorActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
                    naLiscie.addView(rz)
                }
            }
            if (reszta.isNotEmpty()) {
                inne.addView(tekst("Pozostałe filmy (brak na liście)", 19f, g = true).apply { setPadding(0, dp(14), 0, dp(8)) })
                reszta.take(150).forEach { f ->
                    val t = f.first.ifEmpty { f.second }
                    val rz = LinearLayout(this@AktorActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
                    rz.addView(tekst(if (f.third > 0) f.third.toString() else "—", 14f, SZARY), LinearLayout.LayoutParams(dp(56), -2))
                    rz.addView(tekst(t, 15f, 0xFFCCCCCC.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                    inne.addView(rz)
                }
            }
        }
    }

    private fun plakat(v: Vod): LinearLayout {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0); isFocusable = true; isClickable = true }
        val f = FrameLayout(this).apply { background = tlo(KARTA, 8); clipToOutline = true }
        f.addView(tekst(v.nazwa, 12f, SZARY).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0) }, FrameLayout.LayoutParams(-1, -1))
        val im = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(im, FrameLayout.LayoutParams(-1, -1))
        if (!v.okladka.isNullOrEmpty()) im.load(v.okladka)
        k.addView(f, LinearLayout.LayoutParams(-1, dp(165)))
        k.addView(tekst(v.nazwa, 13f).apply { maxLines = 2; setPadding(0, dp(5), 0, 0) })
        if (v.rodzaj == "s") k.addView(tekst("Serial", 12f, SZARY))
        k.setOnClickListener {
            startActivity(Intent(this, DetaleActivity::class.java).putExtra("id", v.id).putExtra("nazwa", v.nazwa)
                .putExtra("okladka", v.okladka).putExtra("ext", v.ext).putExtra("rodzaj", v.rodzaj).putExtra("ocena", v.ocena))
        }
        return k
    }
}
