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
import kotlinx.coroutines.sync.withPermit

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
    private var filmy: List<Vod> = emptyList()
    private var seriale: List<Vod> = emptyList()
    private var katF: List<Kat> = emptyList()
    private var katS: List<Kat> = emptyList()
    private var bladVod = ""
    /** Najwyzej 4 zapytania o program naraz - zeby nie zasypac serwera. */
    private val limitEpg = kotlinx.coroutines.sync.Semaphore(4)
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
        if (savedInstanceState == null) animacjaStartu()
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
        glowny = root
        val gora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(10)) }
        gora.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        gora.addView(tekst("OK Player", 22f, gruby = true).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        gora.addView(tekst("🔍", 22f).apply { setPadding(dp(12), dp(4), dp(16), dp(4)); isFocusable = true; setOnClickListener { pokaz(5) } })
        gora.addView(tekst("⚙", 24f).apply { setPadding(dp(4), dp(4), dp(14), dp(4)); isFocusable = true; setOnClickListener { pokaz(4) } })
        gora.addView(tekst("v$wersja", 12f, SZARY))
        root.addView(gora)
        tresc = FrameLayout(this)
        root.addView(tresc, LinearLayout.LayoutParams(-1, 0, 1f))
        mini = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF1C1C1C.toInt()); visibility = View.GONE; isFocusable = true }
        miniWidok = androidx.media3.ui.PlayerView(this).apply { useController = false; setBackgroundColor(Color.BLACK) }
        mini.addView(miniWidok, LinearLayout.LayoutParams(dp(150), dp(84)))
        val mOpis = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(6), 0) }
        miniNazwa = tekst("", 16f, gruby = true).apply { maxLines = 1 }
        miniTeraz = tekst("", 13f, SZARY).apply { maxLines = 1 }
        mOpis.addView(miniNazwa); mOpis.addView(miniTeraz)
        mini.addView(mOpis, LinearLayout.LayoutParams(0, -2, 1f))
        mini.addView(tekst("⏸", 22f).apply { gravity = Gravity.CENTER; isFocusable = true
            setOnClickListener { miniGracz?.let { g -> g.playWhenReady = !g.playWhenReady; text = if (g.playWhenReady) "⏸" else "▶" } } }, LinearLayout.LayoutParams(dp(44), dp(84)))
        mini.addView(tekst("⛶", 22f).apply { gravity = Gravity.CENTER; isFocusable = true
            setOnClickListener { miniKanal?.let { val ch = it; zamknijMini(); graj(ch, pelny = true) } } }, LinearLayout.LayoutParams(dp(44), dp(84)))
        mini.addView(tekst("✕", 22f).apply { gravity = Gravity.CENTER; isFocusable = true; setOnClickListener { zamknijMini() } }, LinearLayout.LayoutParams(dp(48), dp(84)))
        mini.setOnClickListener { miniKanal?.let { val ch = it; zamknijMini(); graj(ch) } }
        root.addView(mini, LinearLayout.LayoutParams(-1, -2))
        dol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0D0D0D.toInt()) }
        listOf("Start", "TV", "Filmy", "Seriale", "Ustawienia").forEachIndexed { i, n ->
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
        when (i) { 0 -> start(); 1 -> listaKanalow(); 2 -> siatkaVod(filmy, katF, "f"); 3 -> siatkaVod(seriale, katS, "s"); 5 -> szukaj(); else -> ustawienia() }
    }

    /** Dane: najpierw z pamieci telefonu (od razu), potem swieze z serwera w tle. */
    private fun laduj() {
        ladujVod()
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

    /** Filmy i seriale - osobno, po kolei, w tle (nie blokuja kanalow). */
    private fun ladujVod() {
        lifecycleScope.launch {
            val c = this@StartActivity
            withContext(Dispatchers.IO) { Triple(Pamiec.vod(c, "filmy"), Pamiec.vod(c, "seriale"), Pamiec.katN(c, "katf") to Pamiec.katN(c, "kats")) }.let { (f, se, k) ->
                if (f != null) filmy = f; if (se != null) seriale = se; k.first?.let { katF = it }; k.second?.let { katS = it }
                if (f != null || se != null) odswiezEkran()
            }
            try {
                val kf = withContext(Dispatchers.IO) { Serwer.kategorie(c, "get_vod_categories") }
                val f = withContext(Dispatchers.IO) { Serwer.filmy(c) }
                if (f.isNotEmpty()) { filmy = f; katF = kf; odswiezEkran(); withContext(Dispatchers.IO) { Pamiec.zapiszVod(c, "filmy", f); Pamiec.zapiszKatN(c, "katf", kf) } }
                val ks = withContext(Dispatchers.IO) { Serwer.kategorie(c, "get_series_categories") }
                val se = withContext(Dispatchers.IO) { Serwer.seriale(c) }
                if (se.isNotEmpty()) { seriale = se; katS = ks; odswiezEkran(); withContext(Dispatchers.IO) { Pamiec.zapiszVod(c, "seriale", se); Pamiec.zapiszKatN(c, "kats", ks) } }
            } catch (e: Exception) {
                bladVod = e.javaClass.simpleName + " " + (e.message ?: "")
                odswiezEkran()
                withContext(Dispatchers.IO) { Serwer.raport(c, "vod blad " + e.javaClass.simpleName + " " + (e.message ?: "").take(80)) }
            }
        }
    }

    private fun odswiezEkran() { if (zakladka != 4) pokaz(zakladka) }

    // ---------------- Start (jak MyTvOnline: rzedy kafelkow) ----------------
    private fun nazwaKat(id: String, l: List<Kat>) = l.firstOrNull { it.id == id }?.nazwa?.replace(Regex("^[^|]*\\|\\s*"), "")?.trim() ?: ""

    private fun poziomy() = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(12), 0, dp(12), 0); clipToPadding = false }

    private fun naglowekTmdb(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(22), dp(16), dp(10))
        addView(tekst(t, 19f, gruby = true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(tekst("TMDB", 17f, 0xFF1ED98A.toInt(), gruby = true).apply { letterSpacing = 0.15f })
        addView(View(context).apply { background = tlo(0xFF01B4E4.toInt(), 8) }, LinearLayout.LayoutParams(dp(44), dp(14)).apply { marginStart = dp(6) })
    }

    /** Ranking 1, 2, 3... z duza cyfra przy plakacie (szeroko = karta pozioma dla seriali). */
    private fun rzadRanking(k: LinearLayout, tytul: String, l: List<Vod>, kat: List<Kat>, szeroko: Boolean) {
        if (l.isEmpty()) return
        k.addView(naglowekTmdb(tytul))
        val rv = poziomy()
        val sz = if (szeroko) dp(250) else dp(130); val wy = if (szeroko) dp(140) else dp(190)
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = l.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(FrameLayout(p.context).apply {
                isFocusable = true; layoutParams = RecyclerView.LayoutParams(sz + dp(40), -2).apply { marginEnd = dp(6) }
                val kol = LinearLayout(p.context).apply { orientation = LinearLayout.VERTICAL }
                kol.addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = tlo(KARTA, 8); clipToOutline = true }, LinearLayout.LayoutParams(sz, wy))
                kol.addView(tekst("", 14f, gruby = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(sz, -2))
                kol.addView(tekst("", 13f, SZARY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(sz, -2))
                addView(kol, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40) })
                addView(TextView(p.context).apply {
                    textSize = 58f; setTextColor(Color.BLACK); setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
                    setShadowLayer(3f, 0f, 0f, Color.WHITE); paint.style = android.graphics.Paint.Style.FILL_AND_STROKE; paint.strokeWidth = 1f
                }, FrameLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = h.itemView as FrameLayout; val v = l[i]
                val kol = f.getChildAt(0) as LinearLayout
                (kol.getChildAt(0) as ImageView).apply { setImageDrawable(null); v.okladka?.let { load(it) } }
                (kol.getChildAt(1) as TextView).text = v.nazwa.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
                (kol.getChildAt(2) as TextView).text = nazwaKat(v.kat, kat)
                (f.getChildAt(1) as TextView).apply {
                    text = "${i + 1}"
                    // obrys cyfry jak w MyTvOnline: biala kreska, czarny srodek
                    paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); setTextColor(Color.WHITE)
                }
                f.setOnClickListener { otworz(v) }
            }
        }
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    /** Plakaty z tytulem (rok) pod spodem i paskiem obejrzenia. */
    private fun rzadVod(k: LinearLayout, tytul: String, l: List<Vod>) {
        if (l.isEmpty()) return
        k.addView(naglowek(tytul))
        val rv = poziomy()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = l.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; layoutParams = RecyclerView.LayoutParams(dp(128), -2).apply { marginEnd = dp(10) }
                val fr = FrameLayout(p.context).apply { background = tlo(KARTA, 8); clipToOutline = true }
                fr.addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                fr.addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progressTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                }, FrameLayout.LayoutParams(-1, dp(4)).apply { gravity = Gravity.BOTTOM })
                addView(fr, LinearLayout.LayoutParams(-1, dp(185)))
                addView(tekst("", 14f).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) })
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val w = h.itemView as LinearLayout; val v = l[i]
                val fr = w.getChildAt(0) as FrameLayout
                (fr.getChildAt(0) as ImageView).apply { setImageDrawable(null); v.okladka?.let { load(it) } }
                val pb = fr.getChildAt(1) as android.widget.ProgressBar
                val url = if (v.rodzaj == "f") Serwer.adresFilmu(this@StartActivity, v.id, v.ext) else ""
                val poz = if (url.isEmpty()) 0 else Ust.pozycja(this@StartActivity, url); val dl = if (url.isEmpty()) 0 else Ust.dlugosc(this@StartActivity, url)
                if (poz > 0) { pb.visibility = View.VISIBLE; pb.progress = if (dl > 0) (poz * 100 / dl).toInt().coerceIn(3, 100) else 10 } else pb.visibility = View.GONE
                (w.getChildAt(1) as TextView).text = v.nazwa
                w.setOnClickListener { otworz(v) }
            }
        }
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun rzadKanalow(k: LinearLayout, tytul: String, l: List<Kanal>) {
        if (l.isEmpty()) return
        k.addView(naglowek(tytul))
        val rv = poziomy()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = l.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; layoutParams = RecyclerView.LayoutParams(dp(118), -2).apply { marginEnd = dp(10) }
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = tlo(0xFF222222.toInt(), 10); setPadding(dp(14), dp(10), dp(14), dp(10)) }, LinearLayout.LayoutParams(-1, dp(76)))
                addView(tekst("", 14f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) })
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val w = h.itemView as LinearLayout; val ch = l[i]
                (w.getChildAt(0) as ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) }
                (w.getChildAt(1) as TextView).text = ch.nazwa
                w.setOnClickListener { graj(ch) }
            }
        }
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun start() {
        val sv = ScrollView(this)
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        sv.addView(k); tresc.addView(sv)
        if (filmy.isEmpty() && kanaly.isEmpty()) { k.addView(tekst("Ładuję listę…", 15f, SZARY).apply { setPadding(dp(16), dp(16), 0, 0) }); return }
        val pop = { l: List<Vod> -> l.filter { it.ocena > 0 }.sortedByDescending { it.ocena }.take(20).ifEmpty { l.takeLast(20).reversed() } }
        // Kontynuuj ogladanie: ostatni kanal + filmy przerwane w polowie (z ulubionych i najnowszych)
        Ust.ostatni(this)?.let { o ->
            k.addView(naglowek("Kontynuuj oglądanie"))
            k.addView(kafelKanalu(o), LinearLayout.LayoutParams(-1, dp(72)).apply { setMargins(dp(16), 0, dp(16), 0) })
        }
        val ul = Ust.ulubione(this).filter { it[0] != "k" }.map { Vod(it[1], it[2], it[3].ifEmpty { null }, it[5], 0.0, it[4], it[0]) }
        rzadVod(k, "Ulubione", ul)
        rzadRanking(k, "Popularne filmy", pop(filmy), katF, false)
        rzadRanking(k, "Popularne seriale", pop(seriale), katS, true)
        rzadKanalow(k, "Najnowsze kanały telewizyjne", kanaly.takeLast(20).reversed())
        rzadVod(k, "Najnowsze VOD-y", filmy.takeLast(25).reversed())
        rzadVod(k, "Najnowsze seriale telewizyjne", seriale.takeLast(25).reversed())
    }

    private fun naglowek(t: String) = tekst(t, 19f, gruby = true).apply { setPadding(dp(16), dp(20), dp(16), dp(10)) }

    private fun rzad(k: LinearLayout, tytul: String, l: List<Vod>) {
        if (l.isEmpty()) return
        k.addView(naglowek(tytul))
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(12), 0, dp(12), 0); clipToPadding = false }
        rv.adapter = PlakatyAdapter(l, dp(112))
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------------- Szukaj: kanaly, filmy, seriale, aktor ----------------
    private var fraza = ""
    private fun bezOgonkow(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ł', 'l')

    private fun szukaj() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tresc.addView(k)
        val pole = android.widget.EditText(this).apply {
            hint = "Szukaj kanału, filmu, serialu, aktora…"; setHintTextColor(SZARY); setTextColor(Color.WHITE); textSize = 17f; setSingleLine()
            background = GradientDrawable().apply { setColor(0xFF222222.toInt()); cornerRadius = dp(24).toFloat() }; setPadding(dp(18), dp(12), dp(18), dp(12))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; setText(fraza); setSelection(fraza.length)
        }
        k.addView(pole, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(4), dp(14), dp(8)) })
        val wyniki = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        k.addView(ScrollView(this).apply { addView(wyniki) }, LinearLayout.LayoutParams(-1, 0, 1f))
        var zadanie: Job? = null
        fun licz() {
            zadanie?.cancel()
            val q = pole.text.toString().trim(); fraza = q
            zadanie = lifecycleScope.launch {
                delay(300)
                wyniki.removeAllViews()
                if (q.length < 2) { wyniki.addView(tekst("Wpisz co najmniej 2 litery.", 15f, SZARY).apply { setPadding(dp(18), dp(12), 0, 0) }); return@launch }
                val n = bezOgonkow(q)
                val (ch, f, se) = withContext(Dispatchers.Default) {
                    Triple(kanaly.filter { bezOgonkow(it.nazwa).contains(n) }.take(40),
                        filmy.filter { bezOgonkow(it.nazwa).contains(n) }.take(60),
                        seriale.filter { bezOgonkow(it.nazwa).contains(n) }.take(60))
                }
                if (q != fraza) return@launch
                if (ch.isNotEmpty()) {
                    wyniki.addView(naglowek("Kanały (${ch.size})"))
                    ch.forEach { c -> wyniki.addView(LinearLayout(this@StartActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); isFocusable = true
                        addView(ImageView(this@StartActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; c.logo?.let { load(it) } }, LinearLayout.LayoutParams(dp(56), dp(36)))
                        addView(tekst(c.nazwa, 16f).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                        setOnClickListener { graj(c) }
                    }) }
                }
                rzad(wyniki, "Filmy (${f.size})", f)
                rzad(wyniki, "Seriale (${se.size})", se)
                wyniki.addView(tekst("👤  Szukaj aktora: „$q”", 16f).apply {
                    setPadding(dp(18), dp(18), dp(18), dp(18)); isFocusable = true
                    setOnClickListener { startActivity(Intent(this@StartActivity, AktorActivity::class.java).putExtra("aktor", q.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } })) }
                })
                if (ch.isEmpty() && f.isEmpty() && se.isEmpty()) wyniki.addView(tekst("Nic nie znaleziono w kanałach, filmach i serialach.", 15f, SZARY).apply { setPadding(dp(18), 0, 0, 0) }, 0)
            }
        }
        pole.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { licz() }
        })
        licz()
        pole.requestFocus()
        pole.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(pole, 0) }, 200)
    }

    // ---------------- Filmy / Seriale: grupy + siatka plakatow ----------------
    private fun siatkaVod(lista: List<Vod>, kat: List<Kat>, rodzaj: String) {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tresc.addView(k)
        if (lista.isEmpty()) {
            if (bladVod.isNotEmpty()) {
                k.addView(tekst("Nie udało się pobrać listy.\n" + bladVod.take(120), 15f, SZARY).apply { setPadding(dp(16), dp(16), dp(16), dp(12)) })
                k.addView(tekst("⟳  Spróbuj ponownie", 17f, 0xFF111111.toInt()).apply { gravity = Gravity.CENTER; background = tlo(0xFFE6E6E6.toInt(), 26); setPadding(0, dp(14), 0, dp(14)); setOnClickListener { bladVod = ""; ladujVod(); pokaz(zakladka) } },
                    LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), 0) })
            } else k.addView(tekst("Ładuję " + (if (rodzaj == "f") "filmy" else "seriale") + "… (pierwszy raz może potrwać do minuty)", 15f, SZARY).apply { setPadding(dp(16), dp(16), dp(16), 0) })
            return
        }
        val ile = lista.groupingBy { it.kat }.eachCount()
        val grupy = listOf(Kat("", "Wszystkie")) + kat.filter { (ile[it.id] ?: 0) > 0 }
        val klucz = "grupa_$rodzaj"
        var wybrana = Ust.prefs(this).getString(klucz, "") ?: ""
        val siatka = RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@StartActivity, 3); setPadding(dp(8), 0, dp(8), dp(8)); clipToPadding = false }
        fun ustaw() { siatka.adapter = PlakatyAdapter(if (wybrana.isEmpty()) lista.asReversed() else lista.filter { it.kat == wybrana }, -1) }
        val pasek = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(10), dp(4), dp(10), dp(8)); clipToPadding = false }
        lateinit var ga: GrupyAdapter
        ga = GrupyAdapter(grupy, wybrana) { id -> wybrana = id; Ust.prefs(this).edit().putString(klucz, id).apply(); ga.wybrana = id; ga.notifyDataSetChanged(); ustaw() }
        pasek.adapter = ga
        k.addView(pasek, LinearLayout.LayoutParams(-1, -2))
        k.addView(siatka, LinearLayout.LayoutParams(-1, 0, 1f))
        ustaw()
    }

    private fun otworz(v: Vod) {
        startActivity(Intent(this, DetaleActivity::class.java).putExtra("id", v.id).putExtra("nazwa", v.nazwa)
            .putExtra("okladka", v.okladka).putExtra("ext", v.ext).putExtra("rodzaj", v.rodzaj).putExtra("ocena", v.ocena))
    }

    /** Plakaty filmow/seriali. szer = -1 -> do siatki (dopasuj szerokosc). */
    inner class PlakatyAdapter(val l: List<Vod>, val szer: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = l.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val w = LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true
                layoutParams = if (szer > 0) RecyclerView.LayoutParams(szer, -2).apply { marginEnd = dp(10) } else RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) }
                val wys = if (szer > 0) szer * 3 / 2 else (resources.displayMetrics.widthPixels / 3 - dp(14)) * 3 / 2
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = tlo(KARTA, 8); clipToOutline = true }, LinearLayout.LayoutParams(-1, wys))
                addView(tekst("", 13f).apply { maxLines = 2; setPadding(0, dp(6), 0, 0) })
            }
            return object : RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val w = h.itemView as LinearLayout; val v = l[i]
            (w.getChildAt(0) as ImageView).apply { setImageDrawable(null); if (v.okladka != null) load(v.okladka) }
            (w.getChildAt(1) as TextView).text = v.nazwa
            w.setOnClickListener { otworz(v) }
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

    // ---------------- Mini-odtwarzacz na dole ----------------
    private lateinit var mini: LinearLayout
    private lateinit var miniWidok: androidx.media3.ui.PlayerView
    private lateinit var miniNazwa: TextView
    private lateinit var miniTeraz: TextView
    private var miniGracz: androidx.media3.exoplayer.ExoPlayer? = null
    private var miniKanal: Kanal? = null

    private fun pokazMini() {
        if (!::mini.isInitialized) return
        val z = Ust.prefs(this).getString("mini", null)?.split("\u0001") ?: run { mini.visibility = View.GONE; return }
        if (z.size < 4) return
        val ch = Kanal(z[0], z[1], z[2].ifEmpty { null }, z[3], 0)
        miniKanal = ch; miniNazwa.text = ch.nazwa; miniTeraz.text = ""
        mini.visibility = View.VISIBLE
        if (miniGracz == null) {
            val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
            miniGracz = androidx.media3.exoplayer.ExoPlayer.Builder(this).setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http)).build()
            miniWidok.player = miniGracz
            miniGracz!!.setMediaItem(androidx.media3.common.MediaItem.fromUri(Serwer.adresKanalu(this, ch.id))); miniGracz!!.prepare(); miniGracz!!.playWhenReady = true
        }
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { Serwer.terazLeci(this@StartActivity, ch.id) }
            if (miniKanal?.id == ch.id) miniTeraz.text = if (t.isEmpty()) "Na żywo" else "Teraz: $t"
        }
    }

    private fun zatrzymajMini() { miniGracz?.release(); miniGracz = null; if (::miniWidok.isInitialized) miniWidok.player = null }

    private fun zamknijMini() {
        Ust.prefs(this).edit().remove("mini").apply()
        zatrzymajMini(); miniKanal = null
        if (::mini.isInitialized) mini.visibility = View.GONE
    }

    override fun onResume() { super.onResume(); pokazMini() }
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode) zatrzymajMini() }

    /** Mini-okno gra, a Ty wychodzisz do ekranu telefonu: TV zostaje w plywajacym okienku (jak wczesniej). */
    private var glowny: LinearLayout? = null
    private var miniRodzic: LinearLayout? = null
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (miniGracz?.isPlaying != true || !Ust.prefs(this).getBoolean("okienko", true)) return
        try { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) } catch (e: Exception) { }
    }

    override fun onPictureInPictureModeChanged(w: Boolean, c: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(w, c)
        if (w) {
            miniRodzic = miniWidok.parent as? LinearLayout
            miniRodzic?.removeView(miniWidok)
            setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(miniWidok, FrameLayout.LayoutParams(-1, -1)) })
        } else {
            (miniWidok.parent as? ViewGroup)?.removeView(miniWidok)
            miniRodzic?.addView(miniWidok, 0, LinearLayout.LayoutParams(dp(150), dp(84)))
            glowny?.let { (it.parent as? ViewGroup)?.removeView(it); setContentView(it) }
            // Powiekszenie okienka -> TV na caly ekran; zamkniecie (X) -> koniec grania.
            window.decorView.postDelayed({
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) miniKanal?.let { val ch = it; zamknijMini(); graj(ch, pelny = true) }
                else zamknijMini()
            }, 400)
        }
    }

    private fun graj(ch: Kanal, pelny: Boolean = false) {
        if (miniGracz != null || Ust.prefs(this).contains("mini")) zamknijMini()
        Ust.zapiszOstatni(this, ch)
        startActivity(Intent(this, OdtwarzaczActivity::class.java)
            .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat).putExtra("pelny", pelny))
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
        wiersz("Okienko TV (obraz w obrazie)", if (Ust.prefs(this).getBoolean("okienko", true)) "Włączone – po wyjściu do ekranu telefonu TV gra w okienku" else "Wyłączone") {
            Ust.prefs(this).edit().putBoolean("okienko", !Ust.prefs(this).getBoolean("okienko", true)).apply(); pokaz(4)
        }
        wiersz("Odśwież listę", "Pobiera świeżą listę kanałów, filmów i seriali z serwera") { laduj() }
        wiersz("Wyloguj", "Aplikacja pokaże nowy kod do edytora") { Ust.wyloguj(this); ekranLogowania() }
    }

    /** Animacja otwarcia: ikona wkreca sie i rosnie, napis wjezdza, potem ekran plynnie sie odslania (ok. 1,3 s, nie blokuje). */
    private fun animacjaStartu() {
        val dekor = window.decorView as ViewGroup
        val n = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A0A1E.toInt(), Color.BLACK)) }
        val sr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val ik = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        sr.addView(ik, LinearLayout.LayoutParams(dp(120), dp(120)))
        val na = tekst("OK Player", 30f, gruby = true).apply { setPadding(0, dp(16), 0, 0); letterSpacing = 0.08f }
        sr.addView(na)
        n.addView(sr, FrameLayout.LayoutParams(-1, -1))
        dekor.addView(n, ViewGroup.LayoutParams(-1, -1))
        ik.scaleX = 0.2f; ik.scaleY = 0.2f; ik.alpha = 0f; ik.rotation = -360f
        na.alpha = 0f; na.translationY = dp(24).toFloat()
        ik.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(650).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        na.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(400).start()
        n.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setStartDelay(1000).setDuration(350).withEndAction { dekor.removeView(n) }.start()
    }

    /** Okno wyjscia w stylu aplikacji; Wyjdz = animacja zamkniecia i calkowite zamkniecie (znika z ostatnich aplikacji). */
    private fun oknoWyjscia() {
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(20)); background = tlo(KARTA, 18) }
        val ik = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        box.addView(ik, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(14) })
        box.addView(tekst("Zamknąć OK Player?", 21f, gruby = true).apply { gravity = Gravity.CENTER })
        box.addView(tekst("Czy na pewno chcesz wyjść z aplikacji?", 16f, SZARY).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(22)) })
        fun przycisk(t: String, kolTla: Int, kolTekstu: Int, akcja: () -> Unit) = tekst(t, 17f, kolTekstu).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = tlo(kolTla, 28); isFocusable = true; setOnClickListener { akcja() }
        }
        box.addView(przycisk("Wyjdź", 0xFFE6E6E6.toInt(), 0xFF111111.toInt()) {
            ik.animate().rotation(360f).scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(450).start()
            box.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(250).setDuration(300).start()
            window.decorView.animate().alpha(0f).setStartDelay(250).setDuration(350).withEndAction {
                d.dismiss(); finishAndRemoveTask()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
            }.start()
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(przycisk("Anuluj", 0xFF2A2A2A.toInt(), Color.WHITE) { d.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        d.setContentView(box)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.86).toInt(), -2)
        box.alpha = 0f; box.scaleX = 0.8f; box.scaleY = 0.8f; ik.rotation = -90f
        d.show()
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        ik.animate().rotation(0f).setDuration(400).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::mini.isInitialized && mini.visibility == View.VISIBLE) { zamknijMini(); return }
        if (Ust.zalogowany(this) && zakladka != 0) { pokaz(0); return }
        oknoWyjscia()
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
                addView(LinearLayout(p.context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(tekst("", 17f).apply { maxLines = 1 })
                    addView(tekst("", 13f, SZARY).apply { maxLines = 1 })
                    addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; visibility = android.view.View.INVISIBLE; progressTintList = android.content.res.ColorStateList.valueOf(0xFFE0245E.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                    }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return object : RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val ch = l[i]; val w = h.itemView as LinearLayout
            (w.getChildAt(0) as TextView).text = "${i + 1}"
            (w.getChildAt(1) as ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) }
            val opis = w.getChildAt(2) as LinearLayout
            (opis.getChildAt(0) as TextView).text = ch.nazwa
            val epg = opis.getChildAt(1) as TextView
            val pas = opis.getChildAt(2) as android.widget.ProgressBar
            epg.text = ""; epg.tag = ch.id; pas.visibility = android.view.View.INVISIBLE
            lifecycleScope.launch {
                val t = limitEpg.withPermit { withContext(Dispatchers.IO) { Serwer.terazLeci(this@StartActivity, ch.id) } }
                if (epg.tag != ch.id) return@launch
                epg.text = if (t.isEmpty()) "" else "Teraz: $t"
                val pr = Serwer.postep(ch.id); if (pr >= 0) { pas.progress = pr; pas.visibility = android.view.View.VISIBLE }
            }
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
