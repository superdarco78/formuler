package uk.okok.okplayer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OK Player - jedna aktywnosc: logowanie kodem, Start, Kanaly, Ustawienia.
 * Caly wyglad budowany w kodzie (czarny, jak MyTvOnline). Dane zawsze ladowane w tle.
 */
class StartActivity : AppCompatActivity() {

    private val CZARNY = Color.BLACK
    private val KARTA = 0xFF1E1E1E.toInt()
    private val SZARY = 0xFFA6A6A6.toInt()
    private val AKCENT = 0xFFE0245E.toInt()

    private lateinit var tresc: FrameLayout
    private lateinit var dol: LinearLayout
    private var kategorie: List<Kat> = emptyList()
    private var kanaly: List<Kanal> = emptyList()
    private var zakladka = 0
    private var parowanie: Job? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tekst(t: CharSequence, rozm: Float, kol: Int = Color.WHITE, gruby: Boolean = false) = TextView(this).apply {
        text = t; textSize = rozm; setTextColor(kol); if (gruby) setTypeface(typeface, Typeface.BOLD)
    }
    private fun tlo(kol: Int, r: Int) = GradientDrawable().apply { setColor(kol); cornerRadius = dp(r).toFloat() }
    private val wersja by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Ust.zalogowany(this)) ekranGlowny() else ekranLogowania()
        lifecycleScope.launch { Aktualizacja.sprawdz(this@StartActivity) }
    }

    // ---------------- logowanie kodem ----------------
    private fun ekranLogowania() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(CZARNY); setPadding(dp(24), dp(24), dp(24), dp(24)) }
        k.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(110), dp(110)))
        k.addView(tekst("OK Player", 30f, gruby = true).apply { setPadding(0, dp(14), 0, 0) })
        k.addView(tekst("Wersja $wersja", 13f, SZARY))
        k.addView(tekst("Wpisz ten kod w edytorze:\nklient → Ustawienia → „Dodaj urządzenie”", 16f, SZARY).apply { gravity = Gravity.CENTER; setPadding(0, dp(30), 0, dp(10)) })
        val kod = tekst("······", 46f, Color.WHITE, true).apply { letterSpacing = 0.15f }
        k.addView(kod)
        val stan = tekst("Łączę z serwerem…", 14f, SZARY).apply { setPadding(0, dp(14), 0, 0) }
        k.addView(stan)
        setContentView(k)
        parowanie?.cancel()
        parowanie = lifecycleScope.launch {
            var sec = ""
            while (true) {
                if (sec.isEmpty()) {
                    val h = withContext(Dispatchers.IO) { Serwer.hello(this@StartActivity) }
                    if (h != null && h.optBoolean("ok") && h.optString("code").isNotEmpty()) {
                        sec = h.optString("sec"); kod.text = h.optString("code"); stan.text = "Czekam, aż dodasz kod w edytorze…"
                    } else { stan.text = "Brak połączenia z serwerem, ponawiam…"; delay(5000); continue }
                }
                delay(3000)
                val p = withContext(Dispatchers.IO) { Serwer.poll(this@StartActivity, sec) } ?: continue
                val klucz = p.optString("key")
                if (p.optBoolean("paired") && klucz.isNotEmpty()) {
                    stan.text = "Kod przyjęty, loguję…"
                    if (withContext(Dispatchers.IO) { Serwer.sprawdzKlucz(klucz) }) {
                        Ust.zapiszKlucz(this@StartActivity, klucz)
                        ekranGlowny(); return@launch
                    }
                    sec = ""
                } else if (p.optInt("wait", 0) != 1) sec = ""
            }
        }
    }

    // ---------------- ekran glowny ----------------
    private fun ekranGlowny() {
        parowanie?.cancel()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(CZARNY) }
        val gora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(10)) }
        gora.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        gora.addView(tekst("OK Player", 22f, gruby = true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        gora.addView(tekst("v$wersja", 12f, SZARY))
        root.addView(gora)
        tresc = FrameLayout(this)
        root.addView(tresc, LinearLayout.LayoutParams(-1, 0, 1f))
        dol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0D0D0D.toInt()) }
        listOf("Start", "Kanały", "Ustawienia").forEachIndexed { i, n ->
            dol.addView(tekst(n, 15f, SZARY).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(16)); isFocusable = true
                setOnClickListener { pokaz(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(dol)
        setContentView(root)
        pokaz(0)
        laduj()
    }

    private fun pokaz(i: Int) {
        zakladka = i
        for (k in 0 until dol.childCount) (dol.getChildAt(k) as TextView).setTextColor(if (k == i) Color.WHITE else SZARY)
        tresc.removeAllViews()
        when (i) { 0 -> start(); 1 -> listaKanalow(); else -> ustawienia() }
    }

    /** Dane: najpierw z pamieci telefonu (od razu), potem swieze z serwera w tle. */
    private fun laduj() {
        lifecycleScope.launch {
            val zPamieci = withContext(Dispatchers.IO) { Pamiec.kat(this@StartActivity) to Pamiec.kanaly(this@StartActivity) }
            if (zPamieci.first != null && zPamieci.second != null) { kategorie = zPamieci.first!!; kanaly = zPamieci.second!!; odswiezEkran() }
            try {
                val k = withContext(Dispatchers.IO) { Serwer.kategorieTv(this@StartActivity) }
                val l = withContext(Dispatchers.IO) { Serwer.kanaly(this@StartActivity) }
                if (l.isNotEmpty()) {
                    kategorie = k; kanaly = l; odswiezEkran()
                    withContext(Dispatchers.IO) { Pamiec.zapiszKat(this@StartActivity, k); Pamiec.zapiszKanaly(this@StartActivity, l) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { Serwer.raport(this@StartActivity, "lista blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(80)) }
                if (kanaly.isEmpty()) { tresc.removeAllViews(); tresc.addView(tekst("Nie udało się pobrać listy kanałów.\nSprawdź internet i otwórz aplikację ponownie.", 16f, SZARY).apply { gravity = Gravity.CENTER }) }
            }
        }
    }

    private fun odswiezEkran() { if (zakladka != 2) pokaz(zakladka) }

    // ---------------- Start ----------------
    private fun start() {
        val sv = ScrollView(this)
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        sv.addView(k); tresc.addView(sv)
        Ust.ostatni(this)?.let { o ->
            k.addView(tekst("Kontynuuj oglądanie", 19f).apply { setPadding(0, dp(6), 0, dp(10)) })
            k.addView(kafelKanalu(o), LinearLayout.LayoutParams(-1, dp(72)))
        }
        k.addView(tekst("Grupy kanałów", 19f).apply { setPadding(0, dp(22), 0, dp(10)) })
        if (kategorie.isEmpty()) { k.addView(tekst("Ładuję listę…", 15f, SZARY)); return }
        val ile = kanaly.groupingBy { it.kat }.eachCount()
        for (g in kategorie) {
            val n = ile[g.id] ?: 0
            if (n == 0) continue
            k.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = tlo(KARTA, 10); setPadding(dp(16), dp(16), dp(16), dp(16)); isFocusable = true
                addView(tekst(g.nazwa, 17f), LinearLayout.LayoutParams(0, -2, 1f))
                addView(tekst("$n", 14f, SZARY))
                setOnClickListener { Ust.zapiszGrupe(this@StartActivity, g.id); pokaz(1) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    // ---------------- Kanaly ----------------
    private fun listaKanalow() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tresc.addView(k)
        if (kategorie.isEmpty()) { k.addView(tekst("Ładuję listę…", 15f, SZARY).apply { setPadding(dp(16), dp(16), 0, 0) }); return }
        var wybrana = Ust.grupa(this).takeIf { id -> kategorie.any { it.id == id } } ?: kategorie.first().id
        val lista = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity) }
        val adapter = KanalyAdapter { ch -> graj(ch) }
        lista.adapter = adapter
        val grupy = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(10), dp(4), dp(10), dp(8)); clipToPadding = false }
        lateinit var ga: GrupyAdapter
        ga = GrupyAdapter(kategorie, wybrana) { id -> wybrana = id; Ust.zapiszGrupe(this, id); ga.wybrana = id; ga.notifyDataSetChanged(); adapter.ustaw(kanaly.filter { it.kat == id }) }
        grupy.adapter = ga
        k.addView(grupy, LinearLayout.LayoutParams(-1, -2))
        k.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter.ustaw(kanaly.filter { it.kat == wybrana })
        val poz = kategorie.indexOfFirst { it.id == wybrana }; if (poz > 0) grupy.scrollToPosition(poz)
    }

    private fun kafelKanalu(ch: Kanal) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = tlo(KARTA, 10); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusable = true
        addView(ImageView(this@StartActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; if (ch.logo != null) load(ch.logo) }, LinearLayout.LayoutParams(dp(80), dp(52)))
        addView(tekst(ch.nazwa, 17f).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(tekst("▶", 20f, AKCENT))
        setOnClickListener { graj(ch) }
    }

    private fun graj(ch: Kanal) {
        Ust.zapiszOstatni(this, ch)
        startActivity(Intent(this, OdtwarzaczActivity::class.java)
            .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat))
    }

    // ---------------- Ustawienia ----------------
    private fun ustawienia() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        tresc.addView(ScrollView(this).apply { addView(k) })
        fun wiersz(t: String, opis: String, akcja: () -> Unit) = k.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = tlo(KARTA, 10); setPadding(dp(16), dp(16), dp(16), dp(16)); isFocusable = true
            addView(tekst(t, 17f)); addView(tekst(opis, 14f, SZARY))
            setOnClickListener { akcja() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        wiersz("Informacje", "OK Player, nowa wersja $wersja") {}
        wiersz("Sprawdź aktualizację", "Pobiera i instaluje nową wersję w tle") { lifecycleScope.launch { Aktualizacja.sprawdz(this@StartActivity, true) } }
        wiersz("Odśwież listę", "Pobiera świeżą listę kanałów z serwera") { laduj() }
        wiersz("Wyloguj", "Aplikacja pokaże nowy kod do edytora") { Ust.wyloguj(this); ekranLogowania() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Ust.zalogowany(this) && zakladka != 0) { pokaz(0); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Zamknąć OK Player?")
            .setPositiveButton("Wyjdź") { _, _ -> finishAndRemoveTask() }
            .setNegativeButton("Anuluj", null).show()
    }

    // ---------------- adaptery ----------------
    inner class KanalyAdapter(val klik: (Kanal) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var l: List<Kanal> = emptyList()
        fun ustaw(n: List<Kanal>) { l = n; notifyDataSetChanged() }
        override fun getItemCount() = l.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val w = LinearLayout(p.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)); isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                addView(tekst("", 14f, SZARY).apply { minWidth = dp(36) })
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(64), dp(40)))
                addView(tekst("", 17f).apply { setPadding(dp(14), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return object : RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val ch = l[i]; val w = h.itemView as LinearLayout
            (w.getChildAt(0) as TextView).text = "${i + 1}"
            (w.getChildAt(1) as ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) }
            (w.getChildAt(2) as TextView).text = ch.nazwa
            w.setOnClickListener { klik(ch) }
        }
    }

    inner class GrupyAdapter(val g: List<Kat>, var wybrana: String, val klik: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = g.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(tekst("", 15f).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10)); isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            }) {}
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val t = h.itemView as TextView; val k = g[i]
            t.text = k.nazwa
            val wyb = k.id == wybrana
            t.background = tlo(if (wyb) Color.WHITE else KARTA, 20); t.setTextColor(if (wyb) Color.BLACK else Color.WHITE)
            t.setOnClickListener { klik(k.id) }
        }
    }
}
