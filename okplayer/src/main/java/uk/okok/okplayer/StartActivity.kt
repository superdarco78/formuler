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
import kotlinx.coroutines.async
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
    private val AKCENT get() = Skorka.akcent(this)

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
    /** 2.02: tlo z grafiki Canvy bez rozpychania widoku (panele 9-patch, zdjecia bez wlasnego rozmiaru), z zachowaniem odstepow. */
    private fun android.view.View.tloCanvy(r: Int) {
        val l = paddingLeft; val t = paddingTop; val p = paddingRight; val b = paddingBottom
        val d = context.getDrawable(r)
        background = if (d is android.graphics.drawable.NinePatchDrawable || d == null) d else object : android.graphics.drawable.DrawableWrapper(d) {
            override fun getIntrinsicWidth() = -1; override fun getIntrinsicHeight() = -1 }
        setPadding(l, t, p, b)
    }

    private fun tlo(kol: Int, r: Int) = GradientDrawable().apply { setColor(kol); cornerRadius = dp(r).toFloat() }
    /** 2.01: swiecaca ramka z Canvy na zaznaczonym plakacie (pilot / dotyk). */
    private fun ramkaCanvy() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), getDrawable(Skorka.id(this@StartActivity, R.drawable.ok_gr_ramka)))
        addState(intArrayOf(android.R.attr.state_pressed), getDrawable(Skorka.id(this@StartActivity, R.drawable.ok_gr_ramka)))
    }
    private val wersja by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pilot.wlacz(this)
        Jezyk.wlacz(this)
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_tlo_aplikacji)) }
        glowny = root
        val gora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(10)) }
        gora.addView(ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_logo)); scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }, LinearLayout.LayoutParams(dp(56), dp(40)))
        tytulZakladki = tekst("Strona główna", 22f, gruby = true).apply { setPadding(dp(12), 0, 0, 0); maxLines = 1 }
        gora.addView(tytulZakladki, LinearLayout.LayoutParams(0, -2, 1f))
        gora.addView(ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_ik_szukaj)); scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = true; setPadding(dp(2), dp(2), dp(2), dp(2)); setOnClickListener { pokaz(5) } }, LinearLayout.LayoutParams(dp(46), dp(46)))
        gora.addView(ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_ik_ustawienia)); scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = true; setPadding(dp(2), dp(2), dp(2), dp(2)); setOnClickListener { pokaz(4) } }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(6); marginEnd = dp(8) })
        gora.addView(tekst("v$wersja", 10f, SZARY))
        root.addView(gora)
        // Tresc zakladek + mini-okno TV plywajace nad nia (kwadrat z obrazem, bez opisu; dotknij = pelny ekran, X = zamknij).
        val obszar = FrameLayout(this)
        tresc = FrameLayout(this)
        obszar.addView(tresc, FrameLayout.LayoutParams(-1, -1))
        mini = FrameLayout(this).apply {
            visibility = View.GONE; isFocusable = true; elevation = dp(10).toFloat()
            background = tlo(Color.BLACK, 12); clipToOutline = true
        }
        miniWidok = layoutInflater.inflate(R.layout.mini_gracz, null) as androidx.media3.ui.PlayerView
        mini.addView(miniWidok, FrameLayout.LayoutParams(-1, -1))
        mini.addView(ImageView(this).apply {
            setImageResource(R.drawable.ik_zamknij); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(5), dp(5), dp(5), dp(5)); isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC000000.toInt()) }
            setOnClickListener { zamknijMini() }
        }, FrameLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(6), dp(6), 0) })
        mini.addView(ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_ramka)); scaleType = ImageView.ScaleType.FIT_XY; isClickable = false; isFocusable = false }, 1, FrameLayout.LayoutParams(-1, -1))
        miniNazwa = tekst("", 1f); miniTeraz = tekst("", 1f)
        // Dotkniecie = pelny ekran; przeciagniecie palcem = przesuwanie okienka.
        var x0 = 0f; var y0 = 0f; var ruch = false
        mini.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { x0 = e.rawX - v.translationX; y0 = e.rawY - v.translationY; ruch = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val nx = e.rawX - x0; val ny = e.rawY - y0
                    if (Math.abs(nx - v.translationX) > dp(4) || Math.abs(ny - v.translationY) > dp(4)) ruch = true
                    if (ruch) { v.translationX = nx; v.translationY = ny }; true
                }
                android.view.MotionEvent.ACTION_UP -> { if (!ruch) miniKanal?.let { miniNaDuzy(it, true) }; true }
                else -> false
            }
        }
        mini.setOnClickListener { miniKanal?.let { miniNaDuzy(it, true) } }  // OK na pilocie
        obszar.addView(mini, FrameLayout.LayoutParams(dp(208), dp(117)).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, dp(12), dp(12)) })
        gora2 = budujGornyGracz()
        root.addView(gora2, LinearLayout.LayoutParams(-1, -2))
        banerCanvy = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
        root.addView(banerCanvy, LinearLayout.LayoutParams(-1, -2))
        root.addView(obszar, LinearLayout.LayoutParams(-1, 0, 1f))
        dol = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(34).toFloat(); setColor(0xEE24272D.toInt()); setStroke(dp(1), 0x22FFFFFF) } }
        // 2.07: dolne menu jak w Netflixie - plywajaca pigulka, ikony z Canvy z podpisami, wybrana na jasnym tle
        listOf(Triple(R.drawable.ok_gr_menu_start, 0, "Start"), Triple(R.drawable.ok_gr_menu_tv, 1, "TV"), Triple(R.drawable.ok_gr_menu_filmy, 2, "Filmy"), Triple(R.drawable.ok_gr_menu_seriale, 3, "Seriale"), Triple(R.drawable.ok_gr_menu_radio, 7, "Radio"), Triple(R.drawable.ok_gr_menu_ulubione, 6, "Ulubione")).forEach { (ik, z, n) ->
            dol.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; tag = z; setPadding(0, dp(6), 0, dp(6))
                addView(ImageView(context).apply { setImageResource(Skorka.id(this@StartActivity, ik)); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(tekst(n, 11f, 0xFFB0B4BA.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
                setOnClickListener { pokaz(z) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(dol, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(4), dp(14), dp(10)) })
        root.background = Skorka.tloZAkcentem(Ust.prefs(this).getInt("kolor_plakatu", 0xFF2A6F7A.toInt()))
        setContentView(root)
        pokaz(0)
        laduj()
    }

    private fun pokazMenu(i: Int = zakladka) {
        for (k in 0 until dol.childCount) (dol.getChildAt(k) as? LinearLayout)?.let { el ->
            val wyb = el.tag == i
            el.background = if (wyb) GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x26FFFFFF) } else null
            (el.getChildAt(0) as? ImageView)?.apply { if (Skorka.bialeIkony(this@StartActivity)) setColorFilter(if (wyb) Color.WHITE else 0xFF9AA0A6.toInt()) else clearColorFilter(); alpha = if (wyb) 1f else 0.7f }
            (el.getChildAt(1) as? TextView)?.apply { setTextColor(if (wyb) Color.WHITE else 0xFF9AA0A6.toInt()); setTypeface(null, if (wyb) Typeface.BOLD else Typeface.NORMAL) }
        }
        if (::tytulZakladki.isInitialized) tytulZakladki.text = when (i) { 0 -> "Strona główna"; 1 -> "Telewizja"; 2 -> "Filmy"; 3 -> "Seriale"; 5 -> "Szukaj"; 6 -> "Ulubione"; 7 -> "Radio"; 8 -> "Historia"; else -> "Ustawienia" }
    }
    private lateinit var tytulZakladki: TextView

    private fun pokaz(i: Int) {
        if (i != 1 && i != 7 && ::gora2.isInitialized && gora2.visibility == View.VISIBLE) zwinGorny(true)
        zakladka = i
        pokazMenu(i)
        tresc.removeAllViews()
        // 2.09: wejscie w TV nie wlacza juz samo kanalu - kanal gra dopiero po kliknieciu.
        when (i) { 0 -> start(); 1 -> listaKanalow(); 2 -> siatkaVod(filmy, katF, "f"); 3 -> siatkaVod(seriale, katS, "s"); 5 -> szukaj(); 6 -> ulubioneEkran(); 7 -> listaKanalow(radio = true); 8 -> historiaEkran(); else -> ustawienia() }
        naglowekCanva(i)
        animujZakladke()
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
                kol.addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = tlo(KARTA, 8); clipToOutline = true; foreground = ramkaCanvy(); isDuplicateParentStateEnabled = true }, LinearLayout.LayoutParams(sz, wy))
                kol.isDuplicateParentStateEnabled = true
                kol.addView(tekst("", 14f, gruby = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }, LinearLayout.LayoutParams(sz, -2))
                kol.addView(tekst("", 13f, SZARY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(sz, -2))
                addView(kol, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40) })
                addView(TextView(p.context).apply {
                    textSize = 58f; setTextColor(Color.BLACK); setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
                    setShadowLayer(3f, 0f, 0f, Color.WHITE); paint.style = android.graphics.Paint.Style.FILL_AND_STROKE; paint.strokeWidth = 1f
                }, FrameLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; elevation = dp(4).toFloat() }, FrameLayout.LayoutParams(dp(58), dp(86)).apply { gravity = Gravity.TOP or Gravity.START; topMargin = (wy - dp(78)).coerceAtLeast(0) })
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val f = h.itemView as FrameLayout; val v = l[i]
                val kol = f.getChildAt(0) as LinearLayout
                (kol.getChildAt(0) as ImageView).apply { setImageDrawable(null); v.okladka?.let { load(it) } }
                (kol.getChildAt(1) as TextView).text = v.nazwa.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
                (kol.getChildAt(2) as TextView).text = nazwaKat(v.kat, kat)
                val cyfra = if (i < 10) canva("ok_gr_cyfra_" + (i + 1)) else 0
                (f.getChildAt(2) as ImageView).apply { if (cyfra != 0) { setImageResource(cyfra); visibility = View.VISIBLE } else visibility = View.GONE }
                (f.getChildAt(1) as TextView).apply {
                    visibility = if (cyfra != 0) View.GONE else View.VISIBLE
                    text = "${i + 1}"
                    // obrys cyfry jak w MyTvOnline: biala kreska, czarny srodek
                    paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat(); setTextColor(Color.WHITE)
                }
                f.setOnClickListener { otworz(v, kol.getChildAt(0)) }
            }
        }
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    /** Plakaty z tytulem (rok) pod spodem i paskiem obejrzenia. */
    private fun rzadVod(k: LinearLayout, tytul: String, l: List<Vod>, nowosc: Boolean = false) {
        if (l.isEmpty()) return
        k.addView(naglowek(tytul))
        val rv = poziomy()
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = l.size
            override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; layoutParams = RecyclerView.LayoutParams(dp(128), -2).apply { marginEnd = dp(10) }
                val fr = FrameLayout(p.context).apply { background = tlo(KARTA, 8); clipToOutline = true; foreground = ramkaCanvy(); isDuplicateParentStateEnabled = true }
                fr.addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                fr.addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Skorka.akcent(this@StartActivity)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                }, FrameLayout.LayoutParams(-1, dp(4)).apply { gravity = Gravity.BOTTOM })
                // plakietka jak w Netflixie: "Ostatnio dodany" na dole plakatu
                if (nowosc) fr.addView(tekst("Ostatnio dodany", 11f, Color.WHITE, gruby = true).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = GradientDrawable().apply { cornerRadii = floatArrayOf(dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), 0f, 0f, 0f, 0f); setColor(Skorka.akcent(this@StartActivity)) } },
                    FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
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
                w.setOnClickListener { otworz(v, fr) }
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
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); setPadding(dp(14), dp(10), dp(14), dp(10)); foreground = ramkaCanvy(); isDuplicateParentStateEnabled = true }, LinearLayout.LayoutParams(-1, dp(76)))
                addView(tekst("", 14f).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) })
            }) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                val w = h.itemView as LinearLayout; val ch = l[i]
                (w.getChildAt(0) as ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) { canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { error(it) } } else canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { setImageResource(it) } }
                (w.getChildAt(1) as TextView).text = ch.nazwa
                w.setOnClickListener { graj(ch) }
            }
        }
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    /** Rozgrywki rozpoznawane w programie: wzor, nazwa na plakietce, kolor plakietki. */
    private val ligi = listOf(
        Triple(Regex("(?i)ekstraklas"), "EKSTRAKLASA", 0xFF0B7A3B.toInt()),
        Triple(Regex("(?i)premier league|angielska"), "PREMIER LEAGUE", 0xFF3D195B.toInt()),
        Triple(Regex("(?i)la ?liga|hiszpańsk"), "LALIGA", 0xFFE0322B.toInt()),
        Triple(Regex("(?i)bundesliga|niemieck"), "BUNDESLIGA", 0xFFD20515.toInt()),
        Triple(Regex("(?i)serie a|włosk"), "SERIE A", 0xFF024494.toInt()),
        Triple(Regex("(?i)ligue 1|francusk"), "LIGUE 1", 0xFF1D3263.toInt()),
        Triple(Regex("(?i)liga mistrz|champions"), "LIGA MISTRZÓW", 0xFF0A1F5C.toInt()),
        Triple(Regex("(?i)liga europy|europa league"), "LIGA EUROPY", 0xFFF68E00.toInt()),
        Triple(Regex("(?i)liga konferenc|conference"), "LIGA KONFERENCJI", 0xFF00BE5A.toInt()),
        Triple(Regex("(?i)\\bf1\\b|formuła|grand prix"), "FORMUŁA 1", 0xFFE10600.toInt()),
        Triple(Regex("(?i)ksw|ufc|mma|boks"), "SPORTY WALKI", 0xFF8B0000.toInt()),
        Triple(Regex("(?i)siatk|plusliga|volley"), "SIATKÓWKA", 0xFFF5A300.toInt()),
        Triple(Regex("(?i)skoki|skocz"), "SKOKI", 0xFF1E88E5.toInt()),
        Triple(Regex("(?i)żużel|zuzel|speedway"), "ŻUŻEL", 0xFF5D4037.toInt()),
        Triple(Regex("(?i)tenis|atp|wta"), "TENIS", 0xFF2E7D32.toInt()),
        Triple(Regex("(?i)nba|koszyk"), "KOSZYKÓWKA", 0xFFC9082A.toInt()),
        Triple(Regex("(?i)hokej|nhl"), "HOKEJ", 0xFF000080.toInt())
    )

    /** Rzad "Mecze i sport na zywo": sprawdza program kanalow sportowych i pokazuje te, ktore nadaja teraz mecz lub transmisje. */
    private fun rzadSportu(k: LinearLayout) {
        val katSport = kategorie.filter { it.nazwa.lowercase().let { n -> n.contains("sport") || n.contains("piłk") || n.contains("mecz") } }.map { it.id }.toSet()
        val sportowe = kanaly.filter { it.kat in katSport }.take(40)
        if (sportowe.isEmpty()) return
        val miejsce = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        k.addView(miejsce)
        val mecz = Regex("(?i)( - | – | vs\\.? | v |mecz|liga|ekstraklasa|puchar|cup|grand prix|gp |f1|mma|ufc|ksw|boks|tenis|siatk|skoki|żużel|zuzel|na żywo|live)")
        lifecycleScope.launch {
            val trwa = ArrayList<Pair<Kanal, String>>(); val inne = ArrayList<Pair<Kanal, String>>()
            kotlinx.coroutines.coroutineScope {
                sportowe.map { ch -> async { ch to limitEpg.withPermit { withContext(Dispatchers.IO) { Serwer.terazLeci(this@StartActivity, ch.id) } } } }.forEach { d ->
                    val (ch, t) = d.await(); if (t.isNotEmpty()) { if (mecz.containsMatchIn(t)) trwa.add(ch to t) else inne.add(ch to t) }
                }
            }
            // Najpierw mecze, potem pozostale transmisje sportowe - rzad zawsze widoczny na gorze.
            trwa.addAll(inne)
            if (trwa.isEmpty() || !miejsce.isAttachedToWindow) return@launch
            miejsce.addView(naglowek("⚽ Mecze i sport na żywo"))
            val rv = poziomy()
            rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = trwa.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = object : RecyclerView.ViewHolder(LinearLayout(p.context).apply {
                    orientation = LinearLayout.VERTICAL; isFocusable = true; setPadding(dp(14), dp(12), dp(14), dp(12)); tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); foreground = ramkaCanvy()
                    layoutParams = RecyclerView.LayoutParams(dp(230), -2).apply { marginEnd = dp(10) }
                    addView(LinearLayout(p.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(54), dp(34)))
                        addView(tekst("● NA ŻYWO", 11f, Color.WHITE, gruby = true)) })
                    addView(tekst("", 15f, gruby = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) })
                    addView(tekst("", 12f, SZARY).apply { maxLines = 1 })
                    addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100
                        progressTintList = android.content.res.ColorStateList.valueOf(AKCENT); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt()) },
                        LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(6) })
                }) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
                    val w = h.itemView as LinearLayout; val (ch, t) = trwa[i]
                    ((w.getChildAt(0) as LinearLayout).getChildAt(0) as ImageView).apply { setImageDrawable(null); ch.logo?.let { load(it) } }
                    val liga = ligi.firstOrNull { (wz, _, _) -> wz.containsMatchIn(t) || wz.containsMatchIn(ch.nazwa) }
                    ((w.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).apply {
                        text = liga?.second ?: ""; setTextColor(Color.WHITE)
                        // bez rozpoznanej ligi: plakietka NA ZYWO z Canvy
                        if (liga == null) { tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_plakietka_zywo)); minWidth = dp(84); minHeight = dp(24); setPadding(0, 0, 0, 0) }
                        else { background = tlo(liga.third, 8); minWidth = 0; minHeight = 0; setPadding(dp(8), dp(3), dp(8), dp(3)) }
                        (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(10)
                    }
                    // Druzyny "A - B" w dwoch linijkach, jak w aplikacjach sportowych
                    val dr = Regex("^(.{2,40}?)\\s+(?:-|–|vs\\.?|v)\\s+(.{2,40})$").find(t.substringAfter(": ").trim())
                    (w.getChildAt(1) as TextView).text = if (dr != null) dr.groupValues[1].trim() + "\n" + dr.groupValues[2].trim() else t
                    (w.getChildAt(2) as TextView).text = ch.nazwa + "   " + Serwer.godziny(ch.id).substringBefore("  •")
                    (w.getChildAt(3) as android.widget.ProgressBar).progress = Serwer.postep(ch.id).coerceAtLeast(0)
                    w.setOnClickListener { graj(ch) }
                }
            }
            miejsce.addView(rv, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Plynne wejscie zakladki: tresc wjezdza lekko od dolu i sie rozjasnia, rzedy/listy pojawiaja sie po kolei. */
    private fun animujZakladke() {
        tresc.alpha = 0f; tresc.translationY = dp(18).toFloat()
        tresc.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        tresc.post { animujListy(tresc) }
    }
    private fun animujListy(v: View) {
        if (v is RecyclerView) {
            v.layoutAnimation = android.view.animation.LayoutAnimationController(
                android.view.animation.AnimationSet(true).apply {
                    addAnimation(android.view.animation.AlphaAnimation(0f, 1f)); addAnimation(android.view.animation.TranslateAnimation(0f, 0f, dp(24).toFloat(), 0f))
                    duration = 280; interpolator = android.view.animation.DecelerateInterpolator() }, 0.08f)
            v.scheduleLayoutAnimation()
        } else if (v is ViewGroup) for (i in 0 until v.childCount) animujListy(v.getChildAt(i))
    }

    private var wczesniejPobrane = false
    private var pogodaPamiec: Triple<String, String, String>? = null
    private var waznoscPamiec: String? = null
    private fun kartaZegara(): View {
        val karta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)) }
        val lewa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lewa.addView(android.widget.TextClock(this).apply { format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 34f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        lewa.addView(android.widget.TextClock(this).apply { format24Hour = "EEEE, d MMMM yyyy"; format12Hour = "EEEE, d MMMM yyyy"; textSize = 14f; setTextColor(0xFFBDBDBD.toInt()) })
        val konto = tekst(waznoscPamiec?.let { if (it.isEmpty()) "" else "Konto ważne: $it" } ?: "", 12f, Skorka.akcent(this@StartActivity)).apply { setPadding(0, dp(4), 0, 0) }
        lewa.addView(konto)
        karta.addView(lewa, LinearLayout.LayoutParams(0, -2, 1f))
        val prawa = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        val ik = tekst(pogodaPamiec?.third ?: "", 30f); val temp = tekst(pogodaPamiec?.second ?: "", 22f, gruby = true); val miasto = tekst(pogodaPamiec?.first ?: "", 13f, 0xFFBDBDBD.toInt())
        val rz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(ik); addView(temp.apply { setPadding(dp(6), 0, 0, 0) }) }
        prawa.addView(rz); prawa.addView(miasto.apply { gravity = Gravity.END })
        karta.addView(prawa)
        if (pogodaPamiec == null || waznoscPamiec == null) lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { pogodaPamiec ?: Dodatki.pogoda() }
            val w = withContext(Dispatchers.IO) { waznoscPamiec ?: Serwer.waznosc(this@StartActivity) }
            pogodaPamiec = p; waznoscPamiec = w
            p?.let { ik.text = it.third; temp.text = it.second; miasto.text = it.first }
            if (w.isNotEmpty()) konto.text = "Konto ważne: $w"
        }
        return karta
    }

    /** Duze okragle przyciski jak w IBO Player: najwazniejsze miejsca aplikacji jednym dotknieciem. */
    private fun kafleMenu(): View {
        val siatka = android.widget.GridLayout(this).apply { columnCount = 4 }
        val poz = listOf(Triple("★", "Ulubione") { pokaz(6) }, Triple("📺", "Telewizja") { pokaz(1) }, Triple("🎬", "Filmy") { pokaz(2) }, Triple("🎞️", "Seriale") { pokaz(3) },
            Triple("📻", "Radio") { pokaz(7) }, Triple("🕘", "Historia") { pokaz(8) }, Triple("👤", "Konto") { ekranKonta() }, Triple("⚙️", "Ustawienia") { pokaz(4) })
        poz.forEachIndexed { i, (ik, nazwa, akcja) ->
            val el = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, dp(6)); isFocusable = true; isClickable = true }
            val ikC = mapOf("Telewizja" to "ok_gr_ik_tv", "Filmy" to "ok_gr_ik_filmy", "Seriale" to "ok_gr_ik_seriale", "Ulubione" to "ok_gr_ik_ulubione", "Radio" to "ok_gr_ik_radio", "Historia" to "ok_gr_ik_historia", "Konto" to "ok_gr_ik_konto", "Ustawienia" to "ok_gr_ik_ustawienia")[nazwa]?.let { canva(it) } ?: 0
            val kolo: View = if (ikC != 0) ImageView(this).apply { setImageResource(ikC) } else tekst(ik, 24f).apply { gravity = Gravity.CENTER
                background = if (i == 0) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Skorka.akcent(this@StartActivity), Skorka.akcent(this@StartActivity))).apply { shape = GradientDrawable.OVAL }
                    else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF2A2A35.toInt()); setStroke(dp(1), 0xFF3A3A48.toInt()) } }
            el.addView(kolo, LinearLayout.LayoutParams(dp(64), dp(64)))
            el.addView(tekst(nazwa, 12f, if (i == 0) Skorka.akcent(this@StartActivity) else 0xFFDDDDDD.toInt()).apply { setPadding(0, dp(4), 0, 0); gravity = Gravity.CENTER })
            el.setOnClickListener { kolo.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction { kolo.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); akcja() }.start() }
            siatka.addView(el, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(i / 4, 1f), android.widget.GridLayout.spec(i % 4, 1f)).apply { width = 0 })
        }
        return siatka
    }

    /** Konto jak w IBO / Smarters: status, waznosc, polaczenia, urzadzenie, serwer. */
    private fun ekranKonta() {
        lifecycleScope.launch {
            val j = withContext(Dispatchers.IO) { Serwer.konto(this@StartActivity) }
            val u = j?.optJSONObject("user_info"); val sv = j?.optJSONObject("server_info")
            val f = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
            fun data(x: String?) = x?.toLongOrNull()?.takeIf { it > 0 }?.let { f.format(java.util.Date(it * 1000)) }
            val tekstKonta = if (u == null) "Nie udało się pobrać danych konta. Sprawdź internet." else listOf(
                "Status: " + (if (u.optString("auth") == "1" && u.optString("status", "Active").equals("Active", true)) "✅ aktywne" else "⛔ " + u.optString("status")),
                "Ważne do: " + (data(u.optString("exp_date")) ?: "bez limitu"),
                "Założone: " + (data(u.optString("created_at")) ?: "—"),
                "Połączenia: " + u.optString("active_cons", "0") + " z " + u.optString("max_connections", "1"),
                "Typ: " + (if (u.optString("is_trial") == "1") "próbne" else "abonament"),
                "Kod urządzenia: " + Ust.klucz(this@StartActivity).take(4) + "••••",
                "Serwer: " + (sv?.optString("url")?.ifEmpty { null } ?: "tv.ok-ok.uk") + (sv?.optString("timezone")?.takeIf { it.isNotEmpty() }?.let { "  •  $it" } ?: ""),
                "Wersja aplikacji: " + wersja).joinToString("\n")
            androidx.appcompat.app.AlertDialog.Builder(this@StartActivity).setTitle("👤 Moje konto").setMessage(tekstKonta).setPositiveButton("OK", null).show().also { Jezyk.okno(it) }
        }
    }

    /** Duzy baner jak w Netflix / Apple TV: plakat na cala szerokosc, sam sie zmienia co 5 s, Ogladaj / Moja lista / Info. */
    private fun baner(k: LinearLayout, l0: List<Vod>) {
        // 2.07: baner jak w Netflixie - duza karta z plakatem, tytul i gatunki na srodku, pod spodem szeroki bialy "Odtworz" i szary "Moja lista";
        // tlo calej aplikacji bierze kolor z plakatu.
        // 2.11: na banerze nr 1 zawsze pierwszy, reszta losowo przy kazdym wejsciu
        val l = l0.filter { it.okladka?.startsWith("http") == true }.take(12).let { if (it.size > 1) listOf(it[0]) + it.drop(1).shuffled() else it }
        if (l.isEmpty()) return
        val w = resources.displayMetrics.widthPixels - dp(32)
        val karta = FrameLayout(this).apply { clipToOutline = true; isFocusable = true
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF111317.toInt()) }
            foreground = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1), 0x55FFFFFF) } }
        val obrazy = List(2) { ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
        obrazy.forEach { karta.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        banerObrazy = obrazy
        // 2.11: zywy baner - po 3 s na plakacie rusza zwiastun bez dzwieku
        val zwiastunWidok = (layoutInflater.inflate(R.layout.mini_gracz, null) as androidx.media3.ui.PlayerView).apply {
            useController = false; resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; alpha = 0f; setShutterBackgroundColor(Color.TRANSPARENT) }
        karta.addView(zwiastunWidok, FrameLayout.LayoutParams(-1, -1))
        var zwiastun: androidx.media3.exoplayer.ExoPlayer? = null
        fun stopZwiastun() { zwiastunWidok.animate().cancel(); zwiastunWidok.alpha = 0f; zwiastunWidok.player = null; zwiastun?.release(); zwiastun = null }
        stopBaner = { stopZwiastun() }
        karta.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { stopZwiastun() } })
        karta.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x00000000, 0x66000000, 0xEE000000.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val dol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(18), 0, dp(18), dp(18)) }
        val tytul = tekst("", 30f, gruby = true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(14f, 0f, 2f, Color.BLACK) }
        val meta = tekst("", 14f, 0xFFDADADA.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(16)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        dol.addView(tytul, LinearLayout.LayoutParams(-1, -2)); dol.addView(meta, LinearLayout.LayoutParams(-1, -2))
        fun prz(t: String, bialy: Boolean) = tekst(t, 17f, if (bialy) Color.BLACK else Color.WHITE, gruby = true).apply {
            gravity = Gravity.CENTER; isFocusable = true
            background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(if (bialy) Color.WHITE else 0x59808080) } }
        val ogladaj = prz("▶   Odtwórz", true); val lista = prz("＋   Moja lista", false)
        dol.addView(ogladaj, LinearLayout.LayoutParams(-1, dp(50)))
        dol.addView(lista, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        karta.addView(dol, FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM })
        k.addView(karta, LinearLayout.LayoutParams(-1, (w * 1.38).toInt().coerceAtMost((resources.displayMetrics.heightPixels * 0.68).toInt())).apply { setMargins(dp(16), dp(14), dp(16), dp(6)) })
        var i = 0; var widoczny = 0
        fun pokazNr(n: Int, anim: Boolean) {
            stopZwiastun()
            val v = l[n]; val nowy = obrazy[1 - widoczny]; val stary = obrazy[widoczny]
            // 2.12: zajawka - po 3 s na banerze leci wyciety losowy fragment z samego filmu (bez dzwieku, ok. 20 s)
            if (v.rodzaj == "f") karta.postDelayed({
                if (i != n || !karta.isAttachedToWindow) return@postDelayed
                var przewiniete = false
                zwiastun = androidx.media3.exoplayer.ExoPlayer.Builder(this@StartActivity).build().apply {
                    volume = 0f
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(Serwer.adresFilmu(this@StartActivity, v.id, v.ext))); prepare(); playWhenReady = false
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(st: Int) {
                            if (st == androidx.media3.common.Player.STATE_READY && !przewiniete) {
                                przewiniete = true
                                val d = duration
                                if (d > 120_000) seekTo((d * (0.15 + Math.random() * 0.55)).toLong())
                                playWhenReady = true
                                karta.postDelayed({ if (i == n) zwiastunWidok.animate().alpha(0f).setDuration(900).start() }, 21_000)
                            }
                        }
                        override fun onIsPlayingChanged(gra: Boolean) { if (gra && przewiniete && zwiastunWidok.alpha == 0f) zwiastunWidok.animate().alpha(1f).setStartDelay(300).setDuration(900).start() }
                        override fun onPlayerError(e: androidx.media3.common.PlaybackException) { stopZwiastun() } })
                }
                zwiastunWidok.player = zwiastun
            }, 3000)
            nowy.alpha = 0f; nowy.scaleX = 1f; nowy.scaleY = 1f; nowy.translationX = 0f
            nowy.load(v.okladka) { allowHardware(false); listener(onSuccess = { _, r ->
                (r.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let { bm ->
                    val kol = Skorka.zPlakatu(this@StartActivity, bm)
                    Ust.prefs(this@StartActivity).edit().putInt("kolor_plakatu", kol).apply()
                    glowny?.background = Skorka.tloZAkcentem(kol)
                } }) }
            nowy.animate().alpha(1f).setDuration(if (anim) 700 else 0).start()
            // 2.11: plakat banera powoli sie przybliza i przesuwa (efekt filmowy)
            android.animation.AnimatorSet().apply {
                playTogether(android.animation.ObjectAnimator.ofFloat(nowy, "scaleX", 1.0f, 1.18f), android.animation.ObjectAnimator.ofFloat(nowy, "scaleY", 1.0f, 1.18f),
                    android.animation.ObjectAnimator.ofFloat(nowy, View.TRANSLATION_X, 0f, (if (n % 2 == 0) -1 else 1) * dp(18).toFloat()))
                duration = 12000; interpolator = android.view.animation.LinearInterpolator(); start() }
            stary.animate().alpha(0f).setDuration(if (anim) 700 else 0).start()
            widoczny = 1 - widoczny
            tytul.text = v.nazwa.replace(Regex("\\s*\\(\\d{4}\\).*$"), "")
            val rok = Regex("\\((\\d{4})\\)").find(v.nazwa)?.groupValues?.get(1)
            meta.text = listOfNotNull(if (v.rodzaj == "s") "Serial" else "Film", rok, nazwaKat(v.kat, if (v.rodzaj == "s") katS else katF).ifEmpty { null }?.take(28), v.ocena.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }).joinToString("  •  ")
            ogladaj.text = if (v.rodzaj == "s") "▶   Odtwórz serial" else "▶   Odtwórz film"
            ogladaj.setOnClickListener { otworz(v, karta) }
            lista.setOnClickListener { Ust.przelaczUlubiony(this, v.rodzaj, v.id, v.nazwa, v.okladka, v.ext, ""); android.widget.Toast.makeText(this, Jezyk.t("Dodano do Mojej listy"), android.widget.Toast.LENGTH_SHORT).show() }
        }
        pokazNr(0, false)
        val zmiana = object : Runnable { override fun run() { if (!karta.isAttachedToWindow) return; i = (i + 1) % l.size; pokazNr(i, true); karta.postDelayed(this, if (zwiastun != null) 26000 else 9000) } }
        karta.postDelayed(zmiana, 7000)
        karta.setOnClickListener { otworz(l[i], karta) }
    }

    /** Filtry pod banerem: szybkie przejscie do TV, filmow, seriali, radia, ulubionych. */
    private fun filtry(k: LinearLayout) {
        val rz = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(2)) }
        listOf("Seriale" to 3, "Filmy" to 2, "TV na żywo" to 1, "Radio" to 7, "Ulubione" to 6, "Historia" to 8).forEachIndexed { j, (t, z) ->
            rz.addView(tekst(t, 15f, 0xFFE8E8E8.toInt(), gruby = true).apply {
                setPadding(dp(18), dp(10), dp(18), dp(10)); isFocusable = true
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x14FFFFFF); setStroke(dp(1), 0x55FFFFFF) }
                setOnClickListener { if (z != 0) pokaz(z) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        k.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(rz) })
    }

    /** Historia ogladania jak u konkurencji: kanaly i filmy z godzina, pasek postepu filmu, dotknij = ogladaj dalej. */
    private fun historiaEkran() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        tresc.addView(ScrollView(this).apply { addView(k) })
        val l = Ust.historia(this)
        val gora = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(10)) }
        gora.addView(tekst("🕘 Historia oglądania", 20f, gruby = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (l.isNotEmpty()) gora.addView(tekst("Wyczyść", 14f, Skorka.akcent(this@StartActivity)).apply { setPadding(dp(10), dp(6), dp(10), dp(6)); isFocusable = true; setOnClickListener { Ust.wyczyscHistorie(this@StartActivity); pokaz(8) } })
        k.addView(gora)
        if (l.isEmpty()) { k.addView(tekst("Tu pojawi się wszystko, co oglądasz: kanały, filmy i odcinki.", 15f, SZARY)); return }
        val teraz = System.currentTimeMillis()
        fun kiedy(c: Long): String { val m = (teraz - c) / 60000; return when { m < 1 -> "przed chwilą"; m < 60 -> "$m min temu"; m < 1440 -> "${m / 60} godz. temu"; else -> java.text.SimpleDateFormat("d.MM, HH:mm", Jezyk.loc).format(java.util.Date(c)) } }
        for (o in l) {
            val tv = o.optString("t") == "tv"; val u = o.optString("u")
            val w = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); foreground = ramkaCanvy(); setPadding(dp(10), dp(10), dp(12), dp(10)); isFocusable = true }
            w.addView(ImageView(this).apply { scaleType = if (tv) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP; background = tlo(0xFF2A2A30.toInt(), 8); clipToOutline = true
                o.optString("o").takeIf { it.startsWith("http") }?.let { load(it) } }, LinearLayout.LayoutParams(if (tv) dp(84) else dp(56), if (tv) dp(52) else dp(80)))
            val p = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            p.addView(tekst(o.optString("n"), 16f, gruby = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            p.addView(tekst((if (tv) "📺 Kanał" else "🎬 Film / odcinek") + "  ·  " + kiedy(o.optLong("c")), 13f, SZARY))
            if (!tv) { val poz = Ust.pozycja(this, u); val dl = Ust.dlugosc(this, u)
                if (poz > 0 && dl > 0) p.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = (poz * 100 / dl).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(AKCENT) }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(6) }) }
            w.addView(p, LinearLayout.LayoutParams(0, -2, 1f))
            w.addView(tekst("▶", 20f, AKCENT))
            w.setOnClickListener {
                if (tv) graj(kanaly.firstOrNull { it.id == o.optString("id") } ?: Kanal(o.optString("id"), o.optString("n"), o.optString("o").ifEmpty { null }, o.optString("k"), 0))
                else startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("url", u).putExtra("nazwa", o.optString("n")).putExtra("okladka", o.optString("o").ifEmpty { null }).putExtra("vod", true))
            }
            k.addView(w, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    /** Grafika z Canvy (wbudowana przy budowie) albo 0, gdy jej brak. */
    private fun canva(n: String) = Skorka.nazwaId(this, n)
    private lateinit var banerCanvy: ImageView
    /** Baner z Canvy nad zakladka: Filmy = "Nowosci filmowe", TV = "Kanaly na zywo", Seriale/Ulubione = "Witaj w OK Player". */
    private fun naglowekCanva(i: Int) {
        if (!::banerCanvy.isInitialized) return
        val n = when (i) { 1 -> if (::gora2.isInitialized && gora2.visibility == View.VISIBLE) null else "baner_kanaly"; 2 -> "baner_filmy"; 3, 6 -> "baner_witaj"; else -> null }
        val id = n?.let { canva(it) } ?: 0
        if (id == 0) { banerCanvy.visibility = View.GONE; return }
        banerCanvy.setImageResource(id); banerCanvy.visibility = View.VISIBLE
        banerCanvy.alpha = 0f; banerCanvy.translationY = -dp(16).toFloat(); banerCanvy.animate().alpha(1f).translationY(0f).setDuration(420).start()
    }

    private var banerObrazy: List<ImageView>? = null
    private var stopBaner: (() -> Unit)? = null
    override fun onPause() { stopBaner?.invoke(); super.onPause() }
    private fun start() {
        val sv = ScrollView(this)
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        sv.addView(k); tresc.addView(sv)
        // 2.11: paralaksa - plakat w banerze przesuwa sie wolniej niz reszta przy przewijaniu
        banerObrazy = null
        sv.setOnScrollChangeListener { _, _, y, _, _ -> banerObrazy?.forEach { it.translationY = y * 0.35f } }
        if (filmy.isEmpty() && kanaly.isEmpty()) { k.addView(tekst("Ładuję listę…", 15f, SZARY).apply { setPadding(dp(16), dp(16), 0, 0) }); return }
        val pop = { l: List<Vod> -> l.filter { it.ocena > 0 }.sortedByDescending { it.ocena }.take(20).ifEmpty { l.takeLast(20).reversed() } }
        // Uklad Startu (Ustawienia -> Uklad Startu): kinowy (domyslny), ikony (jak IBO) albo klasyczny.
        val uklad = Ust.prefs(this).getString("uklad", "kinowy")
        if (uklad == "kinowy") {
            filtry(k)
            baner(k, (pop(filmy).take(6) + pop(seriale).take(4)).shuffled(java.util.Random(System.currentTimeMillis() / 3_600_000)))
        }
        if (uklad == "ikony") {
            k.addView(kartaZegara(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })
            k.addView(kafleMenu(), LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(10), dp(8), dp(6)) })
        }
        // Mecze i sport, ktore leca teraz
        rzadSportu(k)
        if (uklad == "kinowy") { rzadRanking(k, "Top filmów dziś", pop(filmy), katF, false); rzadRanking(k, "Top seriali dziś", pop(seriale), katS, true) }
        rzadKanalow(k, "Ostatnio oglądane kanały", Ust.ostatnie(this).map { o -> kanaly.firstOrNull { it.id == o.id } ?: o })
        // Radio: stacje z grup z "radio" w nazwie
        val katRadio = kategorie.filter { it.nazwa.lowercase().contains("radio") }.map { it.id }.toSet()
        rzadKanalow(k, "📻 Radio", kanaly.filter { it.kat in katRadio }.take(40))
        val ul = Ust.ulubione(this).filter { it[0] != "k" }.map { Vod(it[1], it[2], it[3].ifEmpty { null }, it[5], 0.0, it[4], it[0]) }
        rzadVod(k, "Ulubione", ul)
        if (uklad != "kinowy") { rzadRanking(k, "Popularne filmy", pop(filmy), katF, false); rzadRanking(k, "Popularne seriale", pop(seriale), katS, true) }
        rzadKanalow(k, "Najnowsze kanały telewizyjne", kanaly.takeLast(20).reversed())
        rzadVod(k, "Najnowsze filmy", filmy.takeLast(25).reversed(), true)
        rzadVod(k, "Najnowsze seriale", seriale.takeLast(25).reversed(), true)
        // Opisy pierwszych plakatow pobierane w tle - otwieraja sie od razu.
        if (!wczesniejPobrane && filmy.isNotEmpty()) { wczesniejPobrane = true
            val l = pop(filmy).take(10) + filmy.takeLast(10).reversed() + pop(seriale).take(6) + Ust.ulubione(this).filter { it[0] != "k" }.map { Vod(it[1], it[2], it[3].ifEmpty { null }, it[5], 0.0, it[4], it[0]) }
            lifecycleScope.launch(Dispatchers.IO) { Serwer.wczesniej(this@StartActivity, l) } }
    }

    private fun naglowek(t: String) = tekst(t, 19f, gruby = true).apply { setPadding(dp(16), dp(20), dp(16), dp(10)) }

    private fun rzad(k: LinearLayout, tytul: String, l: List<Vod>) {
        if (l.isEmpty()) return
        k.addView(naglowek(tytul))
        val rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(12), 0, dp(12), 0); clipToPadding = false }
        rv.adapter = PlakatyAdapter(l, dp(112))
        k.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------------- Ulubione: kanaly, filmy, seriale ----------------
    private fun ulubioneEkran() {
        val sv = ScrollView(this); val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        sv.addView(k); tresc.addView(sv)
        val ul = Ust.ulubione(this)
        val kan = ul.filter { it[0] == "k" }.mapNotNull { u -> kanaly.firstOrNull { it.id == u[1] } ?: Kanal(u[1], u[2], u[3].ifEmpty { null }, u[5], 0) }
        val vod = ul.filter { it[0] != "k" }.map { Vod(it[1], it[2], it[3].ifEmpty { null }, it[5], 0.0, it[4], it[0]) }
        if (kan.isEmpty() && vod.isEmpty()) {
            k.addView(tekst("Nie masz jeszcze ulubionych.\n\nKanał: przytrzymaj go palcem na liście TV.\nFilm lub serial: gwiazdka ☆ w opisie.", 16f, SZARY).apply { setPadding(dp(18), dp(24), dp(18), 0) }); return
        }
        rzadKanalow(k, "Ulubione kanały", kan)
        rzadVod(k, "Ulubione filmy", vod.filter { it.rodzaj == "f" })
        rzadVod(k, "Ulubione seriale", vod.filter { it.rodzaj == "s" })
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
                k.addView(tekst("⟳  Spróbuj ponownie", 17f, Color.WHITE, gruby = true).apply { gravity = Gravity.CENTER; background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Skorka.akcent(this@StartActivity), Skorka.akcent(this@StartActivity))).apply { cornerRadius = dp(26).toFloat() }; setPadding(0, dp(14), 0, dp(14)); setOnClickListener { bladVod = ""; ladujVod(); pokaz(zakladka) } },
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

    private fun otworz(v: Vod, zrodlo: View? = null) {
        val i = Intent(this, DetaleActivity::class.java).putExtra("id", v.id).putExtra("nazwa", v.nazwa)
            .putExtra("okladka", v.okladka).putExtra("ext", v.ext).putExtra("rodzaj", v.rodzaj).putExtra("ocena", v.ocena)
        // 2.11: plakat plynnie powieksza sie z miejsca, w ktorym go kliknieto, na caly ekran (jak w Apple TV)
        if (zrodlo != null && zrodlo.width > 0) {
            if (Pilot.czyTv(this).not()) zrodlo.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(i, android.app.ActivityOptions.makeScaleUpAnimation(zrodlo, 0, 0, zrodlo.width, zrodlo.height).toBundle())
        } else { startActivity(i); overridePendingTransition(R.anim.wejscie_zoom, android.R.anim.fade_out) }
    }

    /** Plakaty filmow/seriali. szer = -1 -> do siatki (dopasuj szerokosc). */
    inner class PlakatyAdapter(val l: List<Vod>, val szer: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = l.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val w = LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true
                layoutParams = if (szer > 0) RecyclerView.LayoutParams(szer, -2).apply { marginEnd = dp(10) } else RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) }
                val wys = if (szer > 0) szer * 3 / 2 else (resources.displayMetrics.widthPixels / 3 - dp(14)) * 3 / 2
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = tlo(KARTA, 8); clipToOutline = true; foreground = ramkaCanvy(); isDuplicateParentStateEnabled = true }, LinearLayout.LayoutParams(-1, wys))
                addView(tekst("", 13f).apply { maxLines = 2; setPadding(0, dp(6), 0, 0) })
            }
            return object : RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val w = h.itemView as LinearLayout; val v = l[i]
            (w.getChildAt(0) as ImageView).apply { setImageDrawable(null); if (v.okladka != null) load(v.okladka) { canva("ok_gr_brak_plakatu").takeIf { it != 0 }?.let { error(it) } } else canva("ok_gr_brak_plakatu").takeIf { it != 0 }?.let { setImageResource(it) } }
            (w.getChildAt(1) as TextView).text = v.nazwa
            w.setOnClickListener { otworz(v, w.getChildAt(0)) }
        }
    }

    // ---------------- Kanaly ----------------
    private fun czyRadio(k: Kat) = k.nazwa.lowercase().contains("radio")
    /** Lista kanalow TV albo (radio = true) stacji radiowych - z grupami u gory. */
    private fun listaKanalow(radio: Boolean = false) {
        val kategorie = this.kategorie.filter { czyRadio(it) == radio }.ifEmpty { if (radio) emptyList() else this.kategorie }
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tresc.addView(k)
        if (kategorie.isEmpty()) { k.addView(tekst(if (radio && this.kategorie.isNotEmpty()) "Brak stacji radiowych na liście." else "Ładuję listę…", 15f, SZARY).apply { setPadding(dp(16), dp(16), 0, 0) }); return }
        var wybrana = (if (radio) Ust.prefs(this).getString("grupa_radio", null) else Ust.grupa(this)).takeIf { id -> kategorie.any { it.id == id } } ?: kategorie.first().id
        if (czyZablokowana(wybrana)) wybrana = kategorie.firstOrNull { !czyZablokowana(it.id) }?.id ?: wybrana
        val lista = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity) }
        val adapter = KanalyAdapter { ch -> graj(ch) }
        lista.adapter = adapter
        val grupy = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@StartActivity, LinearLayoutManager.HORIZONTAL, false); setPadding(dp(10), dp(4), dp(10), dp(8)); clipToPadding = false }
        lateinit var ga: GrupyAdapter
        ga = GrupyAdapter(kategorie, wybrana) { id -> if (czyZablokowana(id)) pytajPin { ga.klik(id) } else { wybrana = id; if (radio) Ust.prefs(this).edit().putString("grupa_radio", id).apply() else Ust.zapiszGrupe(this, id); ga.wybrana = id; ga.notifyDataSetChanged(); adapter.ustaw(kanaly.filter { it.kat == id }) } }
        grupy.adapter = ga
        k.addView(grupy, LinearLayout.LayoutParams(-1, -2))
        k.addView(lista, LinearLayout.LayoutParams(-1, 0, 1f))
        adapter.ustaw(kanaly.filter { it.kat == wybrana })
        val poz = kategorie.indexOfFirst { it.id == wybrana }; if (poz > 0) grupy.scrollToPosition(poz)
    }

    private fun kafelKanalu(ch: Kanal) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); foreground = ramkaCanvy(); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusable = true
        addView(ImageView(this@StartActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; if (ch.logo != null) load(ch.logo) { canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { error(it) } } else canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { setImageResource(it) } }, LinearLayout.LayoutParams(dp(80), dp(52)))
        addView(tekst(ch.nazwa, 17f).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@StartActivity).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_play)) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        setOnClickListener { graj(ch) }
    }

    // ---------------- Mini-odtwarzacz na dole ----------------
    private lateinit var mini: FrameLayout
    private lateinit var miniWidok: androidx.media3.ui.PlayerView
    private lateinit var miniNazwa: TextView
    private lateinit var miniTeraz: TextView
    private var miniGracz: androidx.media3.exoplayer.ExoPlayer? = null
    private var miniKanal: Kanal? = null
    private var miniFilm: String? = null

    private fun pokazMini() {
        if (!::mini.isInitialized) return
        val z = Ust.prefs(this).getString("mini", null)?.split("\u0001") ?: run { mini.visibility = View.GONE; return }
        if (z.size < 4) return
        val ch = Kanal(z[0], z[1], z[2].ifEmpty { null }, z[3], 0)
        miniKanal = ch; miniNazwa.text = ch.nazwa; miniTeraz.text = ""
        miniFilm = if (ch.id == "vod") z.getOrNull(4) else null
        if (mini.visibility != View.VISIBLE) { mini.translationX = 0f; mini.translationY = 0f }
        mini.visibility = View.VISIBLE; mini.bringToFront()
        WspolnyGracz.wlasciciel = "mini"
        miniGracz = WspolnyGracz.pobierz(this)
        miniWidok.player = miniGracz
        if (miniFilm != null) {
            // Film w mini-oknie: gra dalej od tego samego miejsca
            if (WspolnyGracz.graj(this, miniFilm!!)) Ust.pozycja(this, miniFilm!!).takeIf { it > 0 }?.let { miniGracz?.seekTo(it) }
            return
        }
        WspolnyGracz.graj(this, z.getOrNull(5)?.takeIf { it.isNotEmpty() } ?: Serwer.adresKanalu(this, ch.id))
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { Serwer.terazLeci(this@StartActivity, ch.id) }
            if (miniKanal?.id == ch.id) miniTeraz.text = if (t.isEmpty()) "Na żywo" else "Teraz: $t"
        }
    }

    private fun zatrzymajMini() { if (::miniWidok.isInitialized) miniWidok.player = null; miniGracz = null; WspolnyGracz.zwolnij("mini") }

    /** Z mini na duzy ekran plynnie: obraz nie jest zatrzymywany, duzy ekran przejmuje ten sam odtwarzacz. */
    private fun miniNaDuzy(ch: Kanal, pelny: Boolean) {
        miniFilm?.let { u ->
            Ust.prefs(this).edit().remove("mini").apply()
            miniWidok.player = null; miniGracz = null; miniKanal = null; miniFilm = null; mini.visibility = View.GONE
            WspolnyGracz.wlasciciel = "duzy"
            startActivity(Intent(this, OdtwarzaczActivity::class.java).putExtra("url", u).putExtra("nazwa", ch.nazwa).putExtra("vod", true))
            overridePendingTransition(android.R.anim.fade_in, 0); return
        }
        Ust.prefs(this).edit().remove("mini").apply()
        if (::miniWidok.isInitialized) miniWidok.player = null
        miniGracz = null; miniKanal = null; mini.visibility = View.GONE
        WspolnyGracz.wlasciciel = "duzy"
        Ust.zapiszOstatni(this, ch)
        startActivity(Intent(this, OdtwarzaczActivity::class.java)
            .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat).putExtra("pelny", pelny))
        overridePendingTransition(android.R.anim.fade_in, 0)
    }

    private fun zamknijMini() {
        miniFilm?.let { u -> miniGracz?.let { g -> if (g.duration > 0) { Ust.zapiszPozycje(this, u, g.currentPosition); Ust.zapiszDlugosc(this, u, g.duration) } } }
        miniFilm = null
        Ust.prefs(this).edit().remove("mini").apply()
        zatrzymajMini(); miniKanal = null
        if (::mini.isInitialized) mini.visibility = View.GONE
    }

    override fun onResume() { super.onResume(); pokazMini(); lifecycleScope.launch { Aktualizacja.sprawdz(this@StartActivity) } }
    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        zatrzymajMini()
        if (::gora2.isInitialized && gora2.visibility == View.VISIBLE) { gornyWidok.player = null; gora2.visibility = View.GONE; gornyKanal = null; WspolnyGracz.zwolnij("gora") }
    }

    /** Mini-okno gra, a Ty wychodzisz do ekranu telefonu: TV zostaje w plywajacym okienku (jak wczesniej). */
    private var glowny: LinearLayout? = null
    private var miniRodzic: ViewGroup? = null
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::gora2.isInitialized && gora2.visibility == View.VISIBLE) zwinGorny(true)
        if (miniGracz?.isPlaying != true || !Ust.prefs(this).getBoolean("okienko", true)) return
        try { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) } catch (e: Exception) { }
    }

    override fun onPictureInPictureModeChanged(w: Boolean, c: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(w, c)
        if (w) {
            miniRodzic = miniWidok.parent as? ViewGroup
            miniRodzic?.removeView(miniWidok)
            setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(miniWidok, FrameLayout.LayoutParams(-1, -1)) })
        } else {
            (miniWidok.parent as? ViewGroup)?.removeView(miniWidok)
            miniRodzic?.addView(miniWidok, 0, FrameLayout.LayoutParams(-1, -1))
            glowny?.let { (it.parent as? ViewGroup)?.removeView(it); setContentView(it) }
            // Powiekszenie okienka -> TV na caly ekran; zamkniecie (X) -> koniec grania.
            window.decorView.postDelayed({
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) miniKanal?.let { miniNaDuzy(it, true) }
                else zamknijMini()
            }, 400)
        }
    }

    // ---------------- Kontrola rodzicielska (PIN) ----------------
    private var odblokowane = false
    private fun pin() = Ust.prefs(this).getString("pin", "") ?: ""
    private fun zablokowaneGrupy(): Set<String> = Ust.prefs(this).getStringSet("zablokowane", emptySet()) ?: emptySet()
    private fun czyZablokowana(kat: String) = pin().isNotEmpty() && !odblokowane && kat in zablokowaneGrupy()
    private val DOROSLI = Regex("(?i)(xxx|adult|18\\+|\\+18|dla doros|erot|porn)")
    private fun polePin() = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4)); gravity = Gravity.CENTER; textSize = 26f; letterSpacing = 0.5f; hint = "••••"
    }
    /** Pyta o PIN; po poprawnym odblokowuje do zamkniecia aplikacji. */
    private fun pytajPin(ok: () -> Unit) {
        val e = polePin()
        val d = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("🔒 Podaj PIN").setView(e)
            .setPositiveButton("OK", null).setNegativeButton("Anuluj", null).create()
        d.setOnShowListener { d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (e.text.toString() == pin()) { odblokowane = true; d.dismiss(); ok() } else { e.text.clear(); e.error = "Zły PIN" } } }
        d.show(); Jezyk.okno(d)
    }
    private fun ustawPin(potem: () -> Unit) {
        val e1 = polePin(); val e2 = polePin()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(tekst("Nowy PIN (4 cyfry)", 14f, SZARY)); addView(e1); addView(tekst("Powtórz PIN", 14f, SZARY).apply { setPadding(0, dp(10), 0, 0) }); addView(e2) }
        val d = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("🔒 Ustaw PIN").setView(box).setPositiveButton("Zapisz", null).setNegativeButton("Anuluj", null).create()
        d.setOnShowListener { d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = e1.text.toString(); val b = e2.text.toString()
            when { a.length != 4 -> e1.error = "4 cyfry"; a != b -> e2.error = "PIN-y się różnią"
                else -> {
                    val ed = Ust.prefs(this).edit().putString("pin", a)
                    // Za pierwszym razem od razu blokujemy grupy dla doroslych.
                    if (!Ust.prefs(this).contains("zablokowane")) ed.putStringSet("zablokowane", kategorie.filter { DOROSLI.containsMatchIn(it.nazwa) }.map { it.id }.toSet())
                    ed.apply(); odblokowane = false; d.dismiss(); android.widget.Toast.makeText(this, Jezyk.t("PIN zapisany"), android.widget.Toast.LENGTH_SHORT).show(); potem()
                } } } }
        d.show(); Jezyk.okno(d)
    }
    private fun wyborGrup() {
        val l = kategorie; val zb = zablokowaneGrupy().toMutableSet()
        val zazn = BooleanArray(l.size) { l[it].id in zb }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Zablokowane grupy (wymagają PIN)")
            .setMultiChoiceItems(l.map { nazwaKat(it.id, l) }.toTypedArray(), zazn) { _, i, c -> if (c) zb.add(l[i].id) else zb.remove(l[i].id) }
            .setPositiveButton("Zapisz") { _, _ -> Ust.prefs(this).edit().putStringSet("zablokowane", zb).apply(); pokaz(4) }
            .setNegativeButton("Anuluj", null).show().also { Jezyk.okno(it) }
    }
    private fun kontrolaRodzicielska() {
        if (pin().isEmpty()) { ustawPin { pokaz(4) }; return }
        pytajPin {
            val o = arrayOf("Wybierz zablokowane grupy (" + zablokowaneGrupy().size + ")", "Zmień PIN", "Wyłącz kontrolę rodzicielską")
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("🔒 Kontrola rodzicielska").setItems(o) { _, i ->
                when (i) { 0 -> wyborGrup(); 1 -> ustawPin { pokaz(4) }; else -> { Ust.prefs(this).edit().remove("pin").apply(); android.widget.Toast.makeText(this, Jezyk.t("Kontrola rodzicielska wyłączona"), android.widget.Toast.LENGTH_SHORT).show(); pokaz(4) } }
            }.setNegativeButton("Zamknij", null).show().also { Jezyk.okno(it) }
        }
    }

    private fun graj(ch: Kanal, pelny: Boolean = false) {
        if (czyZablokowana(ch.kat)) { pytajPin { graj(ch, pelny) }; return }
        if (Ust.prefs(this).contains("mini")) { Ust.prefs(this).edit().remove("mini").apply(); if (::miniWidok.isInitialized) miniWidok.player = null; miniGracz = null; miniKanal = null; mini.visibility = View.GONE }
        Ust.zapiszOstatni(this, ch)
        if (pelny) {
            WspolnyGracz.wlasciciel = "duzy"
            startActivity(Intent(this, OdtwarzaczActivity::class.java)
                .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat).putExtra("pelny", true))
            return
        }
        // TV jak w starej aplikacji: obraz na gorze zakladki TV, pod nim ta sama lista kanalow (bez drugiej listy).
        val bylaTv = (zakladka == 1 || zakladka == 7) && gornyKanal != null
        gornyKanal = ch
        val jestRadio = kategorie.firstOrNull { it.id == ch.kat }?.let { czyRadio(it) } == true
        if (ch.kat.isNotEmpty() && !bylaTv) { if (jestRadio) Ust.prefs(this).edit().putString("grupa_radio", ch.kat).apply() else Ust.zapiszGrupe(this, ch.kat) }
        WspolnyGracz.wlasciciel = "gora"
        val g = WspolnyGracz.pobierz(this)
        gornyWidok.player = g
        WspolnyGracz.graj(this, Serwer.adresKanalu(this, ch.id))
        gora2.visibility = View.VISIBLE
        ustawGornyPanel(ch)
        if (zakladka != 1 && zakladka != 7) pokaz(if (kategorie.firstOrNull { it.id == ch.kat }?.let { czyRadio(it) } == true) 7 else 1) else odswiezZaznaczenie()
    }

    // ---------------- Obraz TV na gorze zakladki TV ----------------
    private lateinit var gora2: LinearLayout
    private lateinit var gornyWidok: androidx.media3.ui.PlayerView
    private lateinit var gLogo: ImageView
    private lateinit var gNazwa: TextView
    private lateinit var gTeraz: TextView
    private lateinit var gPasek: android.widget.ProgressBar
    private lateinit var gGodz: TextView
    private var gornyKanal: Kanal? = null

    private fun budujGornyGracz(): LinearLayout {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        gornyWidok = androidx.media3.ui.PlayerView(this).apply {
            useController = true; controllerShowTimeoutMs = 2500; setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowPreviousButton(false); setShowNextButton(false); setShowRewindButton(false); setShowFastForwardButton(false)
            setFullscreenButtonClickListener { gornyKanal?.let { ch -> doPelnego(ch) } }
        }
        // Na telewizorze / w poziomie obraz zajmuje najwyzej ~45% wysokosci, zeby lista byla widoczna.
        val dm = resources.displayMetrics
        k.addView(gornyWidok, LinearLayout.LayoutParams(-1, minOf(dm.widthPixels * 9 / 16, (dm.heightPixels * 0.45).toInt())))
        gornyWidok.post { for (n in listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration, androidx.media3.ui.R.id.exo_time)) gornyWidok.findViewById<View>(n)?.visibility = View.GONE }
        val p = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(0xFF111111.toInt()) }
        gLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        p.addView(gLogo, LinearLayout.LayoutParams(dp(56), dp(38)))
        val o = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        gNazwa = tekst("", 17f, gruby = true).apply { maxLines = 1 }
        gTeraz = tekst("", 13f, 0xFFDDDDDD.toInt()).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        gPasek = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(AKCENT); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt()) }
        gGodz = tekst("", 12f, SZARY).apply { maxLines = 1 }
        o.addView(gNazwa); o.addView(gTeraz); o.addView(gPasek, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(4); bottomMargin = dp(2) }); o.addView(gGodz)
        p.addView(o, LinearLayout.LayoutParams(0, -2, 1f))
        p.addView(tekst("☰ Program", 14f, Color.WHITE, gruby = true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)); background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Skorka.akcent(this@StartActivity), Skorka.akcent(this@StartActivity))).apply { cornerRadius = dp(20).toFloat() }; isFocusable = true
            setOnClickListener { gornyKanal?.let { ch -> doProgramu(ch) } } })
        k.addView(p)
        return k
    }

    private fun ustawGornyPanel(ch: Kanal) {
        gNazwa.text = ch.nazwa; gLogo.setImageDrawable(null); ch.logo?.let { gLogo.load(it) }
        gTeraz.text = ""; gGodz.text = ""; gPasek.visibility = View.GONE
        lifecycleScope.launch {
            val t = withContext(Dispatchers.IO) { Serwer.terazLeci(this@StartActivity, ch.id) }
            if (gornyKanal?.id != ch.id) return@launch
            gTeraz.text = if (t.isEmpty()) "Na żywo" else "Teraz: $t"
            Serwer.postep(ch.id).takeIf { it >= 0 }?.let { gPasek.progress = it; gPasek.visibility = View.VISIBLE }
            gGodz.text = Serwer.godziny(ch.id)
        }
    }

    /** Obraz z gory na pelny ekran (plynnie, ten sam odtwarzacz). */
    private fun doPelnego(ch: Kanal) {
        zwinGorny(false)
        WspolnyGracz.wlasciciel = "duzy"
        startActivity(Intent(this, OdtwarzaczActivity::class.java)
            .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat).putExtra("pelny", true))
        overridePendingTransition(android.R.anim.fade_in, 0)
    }

    /** Program (EPG) z archiwum: odtwarzacz z otwartym przewodnikiem. */
    private fun doProgramu(ch: Kanal) {
        zwinGorny(false)
        WspolnyGracz.wlasciciel = "duzy"
        startActivity(Intent(this, OdtwarzaczActivity::class.java)
            .putExtra("url", Serwer.adresKanalu(this, ch.id)).putExtra("nazwa", ch.nazwa).putExtra("logo", ch.logo).putExtra("id", ch.id).putExtra("kat", ch.kat).putExtra("przewodnik", true))
    }

    /** Chowa obraz z gory; doMini = true -> TV gra dalej w malym kwadracie. */
    private fun zwinGorny(doMini: Boolean) {
        if (!::gora2.isInitialized || gora2.visibility != View.VISIBLE) return
        val ch = gornyKanal
        gornyWidok.player = null; gora2.visibility = View.GONE; gornyKanal = null
        if (doMini && ch != null) {
            Ust.prefs(this).edit().putString("mini", listOf(ch.id, ch.nazwa, ch.logo ?: "", ch.kat).joinToString("\u0001")).apply()
            WspolnyGracz.wlasciciel = "mini"; pokazMini()
        }
    }

    private fun odswiezZaznaczenie() { (tresc.getChildAt(0) as? LinearLayout)?.let { k -> for (i in 0 until k.childCount) ((k.getChildAt(i) as? RecyclerView)?.adapter as? KanalyAdapter)?.notifyDataSetChanged() } }

    // ---------------- Ustawienia ----------------
    private fun ustawienia() {
        val k = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        tresc.addView(ScrollView(this).apply { addView(k) })
        fun wiersz(t: String, opis: String, akcja: () -> Unit) = k.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); foreground = ramkaCanvy(); setPadding(dp(16), dp(16), dp(16), dp(16)); isFocusable = true
            addView(tekst(t, 17f)); addView(tekst(opis, 14f, SZARY))
            setOnClickListener { akcja() }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        wiersz("Informacje", "OK Player, nowa wersja $wersja") {}
        wiersz("Sprawdź aktualizację", "Pobiera i instaluje nową wersję w tle") { lifecycleScope.launch { Aktualizacja.sprawdz(this@StartActivity, true) } }
        wiersz("Okienko TV (obraz w obrazie)", if (Ust.prefs(this).getBoolean("okienko", true)) "Włączone – po wyjściu do ekranu telefonu TV gra w okienku" else "Wyłączone") {
            Ust.prefs(this).edit().putBoolean("okienko", !Ust.prefs(this).getBoolean("okienko", true)).apply(); pokaz(4)
        }
        wiersz("Układ Startu", when (Ust.prefs(this).getString("uklad", "kinowy")) { "ikony" -> "Ikony (jak IBO Player)"; "klasyczny" -> "Klasyczny (jak w 1.89)"; else -> "Kinowy (duży baner, Top 10)" }) {
            val o = listOf("kinowy" to "Kinowy – duży baner, Top 10 (nowy)", "ikony" to "Ikony – zegar, pogoda, okrągłe menu (jak IBO)", "klasyczny" to "Klasyczny – jak w wersji 1.89")
            val ter = o.indexOfFirst { it.first == Ust.prefs(this).getString("uklad", "kinowy") }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Układ Startu").setSingleChoiceItems(o.map { it.second }.toTypedArray(), ter) { d, i -> Ust.prefs(this).edit().putString("uklad", o[i].first).apply(); d.dismiss(); pokaz(4) }
                .setNegativeButton("Zamknij", null).show().also { Jezyk.okno(it) }
        }
        wiersz("Blokada zrzutów ekranu", if (Ust.prefs(this).getBoolean("blokada_zrzutow", true)) "Włączona – nie da się zrobić zrzutu ani nagrać ekranu aplikacji (jak w Netflixie)" else "Wyłączona – zrzuty ekranu działają") {
            Ust.prefs(this).edit().putBoolean("blokada_zrzutow", !Ust.prefs(this).getBoolean("blokada_zrzutow", true)).apply(); Pilot.wlacz(this); pokaz(4)
        }
        wiersz("Kontrola rodzicielska", if (pin().isEmpty()) "Wyłączona – ustaw PIN, żeby blokować grupy (np. dla dorosłych)" else "Włączona – zablokowanych grup: " + zablokowaneGrupy().size) { kontrolaRodzicielska() }
        wiersz("Odśwież listę", "Pobiera świeżą listę kanałów, filmów i seriali z serwera") { laduj() }
        wiersz("Wyloguj", "Aplikacja pokaże nowy kod do edytora") { Ust.wyloguj(this); ekranLogowania() }
    }

    private fun animacjaStartu() {
        // 2.01: animacja startu w calosci z grafik Canvy (ok. 4 s): kino z plakatami wjezdza z bokow, logo OK PLAYER wylania sie i swieci,
        // neonowy pasek z Canvy laduje sie do konca, napis "Ladowanie kanalow...", na koniec plynne wejscie do aplikacji. Nie blokuje ladowania.
        val dekor = window.decorView as ViewGroup
        val n = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val tloS = ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_start_tlo)); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; scaleX = 1.25f; scaleY = 1.25f }
        n.addView(tloS, FrameLayout.LayoutParams(-1, -1))
        val szer = resources.displayMetrics.widthPixels; val wys = resources.displayMetrics.heightPixels
        val bok = (minOf(szer, wys * 16 / 9) * 0.30f).toInt()
        val lewa = ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_start_lewa)); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; translationX = -bok.toFloat() }
        val prawa = ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_start_prawa)); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f; translationX = bok.toFloat() }
        n.addView(lewa, FrameLayout.LayoutParams(bok, -1).apply { gravity = Gravity.START })
        n.addView(prawa, FrameLayout.LayoutParams(bok, -1).apply { gravity = Gravity.END })
        // przyciemnienie srodka, zeby logo swiecilo
        n.addView(View(this).apply { background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = minOf(szer, wys) * 0.7f; colors = intArrayOf(0xEE000000.toInt(), 0x66000000, 0x00000000) } }, FrameLayout.LayoutParams(-1, -1))
        val sr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val poswiata = View(this).apply { background = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(150).toFloat(); colors = intArrayOf(0x88FF2D9A.toInt(), 0x33B52AD6, 0x00000000) }; alpha = 0f }
        val logoF = FrameLayout(this)
        logoF.addView(poswiata, FrameLayout.LayoutParams(dp(320), dp(260)).apply { gravity = Gravity.CENTER })
        val logo = ImageView(this).apply { setImageResource(Skorka.id(this@StartActivity, R.drawable.ok_gr_logo)); scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f; scaleX = 0.4f; scaleY = 0.4f }
        logoF.addView(logo, FrameLayout.LayoutParams(dp(230), dp(170)).apply { gravity = Gravity.CENTER })
        sr.addView(logoF, LinearLayout.LayoutParams(dp(320), dp(230)))
        val tor = FrameLayout(this).apply { tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_pasek_tor)); alpha = 0f }
        val pelny = android.graphics.drawable.ClipDrawable(getDrawable(Skorka.id(this@StartActivity, R.drawable.ok_gr_pasek_pelny)), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL).apply { level = 0 }
        tor.addView(View(this).apply { background = pelny }, FrameLayout.LayoutParams(-1, -1))
        sr.addView(tor, LinearLayout.LayoutParams(dp(260), dp(14)).apply { topMargin = dp(18) })
        val napis = tekst("Ładowanie kanałów…", 15f, 0xFFE6E6F0.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0); alpha = 0f; letterSpacing = 0.05f }
        sr.addView(napis)
        n.addView(sr, FrameLayout.LayoutParams(-1, -1))
        dekor.addView(n, ViewGroup.LayoutParams(-1, -1))
        val wolno = android.view.animation.DecelerateInterpolator(1.8f)
        // 1. kino: tlo sie rozjasnia i powoli przybliza, sciany z plakatami wjezdzaja z bokow
        tloS.animate().alpha(1f).setDuration(700).start()
        tloS.animate().scaleX(1f).scaleY(1f).setDuration(4200).setInterpolator(wolno).start()
        lewa.animate().alpha(1f).translationX(0f).setStartDelay(200).setDuration(1100).setInterpolator(wolno).start()
        prawa.animate().alpha(1f).translationX(0f).setStartDelay(200).setDuration(1100).setInterpolator(wolno).start()
        // 2. logo wylania sie z poswiaty z lekkim odbiciem, potem pulsuje
        poswiata.animate().alpha(1f).setStartDelay(600).setDuration(800).start()
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(700).setDuration(900).setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).withEndAction {
            android.animation.ObjectAnimator.ofFloat(poswiata, "alpha", 1f, 0.45f, 1f).apply { duration = 1400; repeatCount = 1 }.start()
            logo.animate().scaleX(1.06f).scaleY(1.06f).setDuration(700).withEndAction { logo.animate().scaleX(1f).scaleY(1f).setDuration(700).start() }.start()
        }.start()
        // 3. neonowy pasek z Canvy laduje sie do konca + napis
        tor.animate().alpha(1f).setStartDelay(1300).setDuration(400).start()
        napis.animate().alpha(1f).setStartDelay(1500).setDuration(500).start()
        android.animation.ValueAnimator.ofInt(0, 10000).apply { startDelay = 1500; duration = 2100; interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { pelny.level = it.animatedValue as Int } }.start()
        napis.postDelayed({ napis.text = "Gotowe ✓" }, 3600)
        // 4. wyjscie: kamera wlatuje w logo, wszystko gasnie
        n.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(3800).setDuration(500).withEndAction { dekor.removeView(n) }.start()
    }

    private fun oknoWyjscia() {
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(24), dp(24), dp(20)); tloCanvy(Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)) }
        val ik = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        box.addView(ik, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(14) })
        box.addView(tekst("Zamknąć OK Player?", 21f, gruby = true).apply { gravity = Gravity.CENTER })
        box.addView(tekst("Czy na pewno chcesz wyjść z aplikacji?", 16f, SZARY).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(22)) })
        fun przycisk(t: String, kolTla: Int, kolTekstu: Int, akcja: () -> Unit) = tekst(t, 17f, kolTekstu, gruby = kolTla == 0).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14)); background = if (kolTla == 0) android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Skorka.akcent(this@StartActivity), Skorka.akcent(this@StartActivity))).apply { cornerRadius = dp(28).toFloat() } else tlo(kolTla, 28).apply { setStroke(dp(1), 0xFF555555.toInt()) }; isFocusable = true; setOnClickListener { akcja() }
        }
        box.addView(przycisk("Wyjdź", 0, Color.WHITE) {
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
        d.show(); Jezyk.okno(d)
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        ik.animate().rotation(0f).setDuration(400).start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::gora2.isInitialized && gora2.visibility == View.VISIBLE) { zwinGorny(true); return }
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
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(3), dp(8), dp(3)) }
                addView(tekst("", 14f, SZARY).apply { minWidth = dp(36) })
                addView(ImageView(p.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(64), dp(40)))
                addView(LinearLayout(p.context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                    addView(tekst("", 17f).apply { maxLines = 1 })
                    addView(tekst("", 13f, SZARY).apply { maxLines = 1 })
                    addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; visibility = android.view.View.INVISIBLE; progressTintList = android.content.res.ColorStateList.valueOf(Skorka.akcent(this@StartActivity)); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                    }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return object : RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val ch = l[i]; val w = h.itemView as LinearLayout
            (w.getChildAt(0) as TextView).text = "${i + 1}"
            (w.getChildAt(1) as ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) { canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { error(it) } } else canva("ok_gr_brak_logo").takeIf { it != 0 }?.let { setImageResource(it) } }
            val opis = w.getChildAt(2) as LinearLayout
            (opis.getChildAt(0) as TextView).text = ch.nazwa
            // 2.01: wiersz kanalu na szklanym panelu z Canvy, grajacy kanal na rozowej pigulce z Canvy
            w.tloCanvy(if (gornyKanal?.id == ch.id) Skorka.id(this@StartActivity, R.drawable.ok_gr_przycisk_roz) else Skorka.id(this@StartActivity, R.drawable.ok_gr_okno)); w.foreground = ramkaCanvy()
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
            w.setOnLongClickListener {
                val teraz = Ust.przelaczUlubiony(this@StartActivity, "k", ch.id, ch.nazwa, ch.logo, "", ch.kat)
                android.widget.Toast.makeText(this@StartActivity, Jezyk.t(if (teraz) "★ ${ch.nazwa} dodany do ulubionych" else "${ch.nazwa} usunięty z ulubionych").toString(), android.widget.Toast.LENGTH_SHORT).show(); true
            }
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
            t.tloCanvy(if (wyb) Skorka.id(this@StartActivity, R.drawable.ok_gr_przycisk_roz) else Skorka.id(this@StartActivity, R.drawable.ok_gr_przycisk)); t.setTextColor(Color.WHITE); t.setPadding(dp(20), dp(12), dp(20), dp(12))
            t.setOnClickListener { klik(k.id) }
        }
    }
}
