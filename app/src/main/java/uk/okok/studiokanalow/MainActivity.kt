package uk.okok.studiokanalow

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import coil.load
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import uk.okok.studiokanalow.databinding.ActivityMainBinding
import uk.okok.studiokanalow.model.Category
import uk.okok.studiokanalow.model.Channel

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var channelAdapter: ChannelAdapter
    private var demo = false
    private var zPamieci = false
    private var pamiecWczytana = false
    private var vodCache: List<Channel>? = null
    private var vodCats: List<Category>? = null
    private var serCache: List<Channel>? = null
    private var serCats: List<Category>? = null
    private var liveCats: List<Category>? = null
    private var liveGroup = 0
    private var liveCache: List<Channel>? = null
    private var liveFiltr: List<Category> = emptyList()
    private var favTab = 0
    private var wersjaEkranu = 0

    private val zapiszLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { it.write(Session.exportBackup(this).toByteArray()) }
            dialog("Kopia zapasowa", "Zapisano plik kopii (bez haseł).")
        } catch (e: Exception) { dialog("Kopia zapasowa", "Nie udało się zapisać pliku.") }
    }
    private val wczytajLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val t = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
            if (Session.importBackup(this, t)) recreate() else dialog("Przywracanie", "To nie jest plik kopii OK Player.")
        } catch (e: Exception) { dialog("Przywracanie", "Nie udało się odczytać pliku.") }
    }

    private fun zastosujKonfig() {
        val m = b.bottomNav.menu
        m.findItem(R.id.tab_kanaly)?.isVisible = Konfig.wl(this, "kanaly")
        m.findItem(R.id.tab_filmy)?.isVisible = Konfig.wl(this, "filmy")
        m.findItem(R.id.tab_seriale)?.isVisible = Konfig.wl(this, "seriale")
        m.findItem(R.id.tab_ulubione)?.isVisible = Konfig.wl(this, "ulubione")
        b.btnSearch.visibility = if (Konfig.wl(this, "szukaj")) View.VISIBLE else View.GONE
    }

    private fun dodajKomunikat() {
        val t = Konfig.tekst(this, "wiadomosc")
        if (t.isEmpty()) return
        b.rowsList.addView(TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 15f
            val tlo = android.graphics.drawable.GradientDrawable(); tlo.setColor(0xFF262626.toInt()); tlo.cornerRadius = dp(12).toFloat()
            background = tlo; setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.setMargins(dp(14), dp(10), dp(14), dp(4)) }
        })
    }

    private fun przeladujWszystko() {
        vodCache = null; vodCats = null; serCache = null; serCats = null; liveCats = null; liveCache = null; liveGroup = 0
        pokaz(b.bottomNav.selectedItemId)
    }

    private val ekrany by lazy {
        Ekrany(this, demo, { przeladujWszystko() }, { ch -> openDetails(ch) }, { ch -> openPlayer(ch) }, { ch -> openSeries(ch) },
            { zapiszLauncher.launch("ok-player-kopia.json") }, { wczytajLauncher.launch(arrayOf("*/*")) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun s() = Session.server(this)
    private fun u() = Session.user(this)
    private fun p() = Session.pass(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (savedInstanceState == null) { animacjaStartu(); raportZawieszenia() }

        demo = intent.getBooleanExtra("demo", false)

        channelAdapter = ChannelAdapter({ ch -> openPlayer(ch) }, { ch -> przelaczUlubiony("live", ch) }, !demo)
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = channelAdapter

        zastosujWyglad()
        b.btnGear.setOnClickListener { showUstawienia() }
        b.btnSearch.setOnClickListener { ekrany.szukaj() }
        b.bottomNav.setOnItemSelectedListener { item -> pokaz(item.itemId); true }
        b.bottomNav.setOnItemReselectedListener { item -> pokaz(item.itemId) }
        b.bottomNav.selectedItemId = R.id.tab_start

        // Wstecz jak w innych aplikacjach: z zakladki wraca na Start, na Starcie pyta o wyjscie.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.bottomNav.selectedItemId != R.id.tab_start) { b.bottomNav.selectedItemId = R.id.tab_start; return }
                if (!Session.optBool(this@MainActivity, "pytaj_wyjscie", true)) {
                    finishAndRemoveTask()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
                    return
                }
                oknoWyjscia()
            }
        })

        // Automatyczna aktualizacja (sprawdza raz na uruchomienie).
        zastosujKonfig()
        if (!demo) lifecycleScope.launch {
            Konfig.odswiez(this@MainActivity); zastosujKonfig(); zastosujWyglad()
            Updater.check(this@MainActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!demo) lifecycleScope.launch { Updater.check(this@MainActivity) }
    }

    private fun pokaz(id: Int) {
        if (id != R.id.tab_start) koniecAnimacjiStartu()
        wersjaEkranu++
        zastosujWyglad()
        when (id) {
            R.id.tab_start -> showStart()
            R.id.tab_kanaly -> showKanaly()
            R.id.tab_filmy -> showFilmy()
            R.id.tab_seriale -> showSeriale()
            R.id.tab_ulubione -> showUlubione()
        }
    }

    // ----- uklad ekranow -----

    private fun tryb(tytul: String, rzedy: Boolean, lista: Boolean) {
        b.tvTitle.text = tytul
        b.tvCount.text = ""
        b.settingsPanel.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.tvInfo.visibility = View.GONE
        b.rowsScroll.visibility = if (rzedy) View.VISIBLE else View.GONE
        b.tvPane.visibility = if (lista) View.VISIBLE else View.GONE
        b.groupBar.visibility = View.GONE
        b.rowsList.removeAllViews()
    }

    private fun dodajRzad(tytul: String, lista: List<Channel>, onClick: (Channel) -> Unit, szer: Int = 112, rank: Boolean = false) {
        if (lista.isEmpty()) return
        b.rowsList.addView(TextView(this).apply {
            text = tytul
            setTextColor(getColor(R.color.tx)); textSize = 19f
            setPadding(dp(18), dp(18), dp(18), dp(8))
        })
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rv.setPadding(dp(12), 0, dp(12), 0)
        rv.clipToPadding = false
        val ad = TileAdapter(szer, rank, onClick)
        ad.submit(lista)
        rv.adapter = ad
        b.rowsList.addView(rv, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dodajBaner(tekst: String) {
        val img = Konfig.obraz(this, "baner_url")
        if (img != null) {
            val fl = android.widget.FrameLayout(this)
            val iv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; load(img) }
            fl.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1))
            fl.addView(TextView(this).apply {
                text = tekst; setTextColor(Color.WHITE); textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.BOTTOM; setPadding(dp(18), dp(18), dp(18), dp(18))
                setBackgroundResource(R.drawable.bg_baner)
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            fl.clipToOutline = true
            fl.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            }
            val lp = LinearLayout.LayoutParams(-1, dp(130)); lp.setMargins(dp(14), dp(10), dp(14), dp(4))
            b.rowsList.addView(fl, lp)
            return
        }
        b.rowsList.addView(TextView(this).apply {
            text = tekst
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_baner)
            gravity = android.view.Gravity.BOTTOM
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val lp = LinearLayout.LayoutParams(-1, dp(130))
            lp.setMargins(dp(14), dp(10), dp(14), dp(4))
            layoutParams = lp
        })
    }

    private fun grupuj(streams: List<Channel>, cats0: List<Category>?, max: Int, rodzaj: String = "vod"): List<Pair<String, List<Channel>>> {
        val cats = cats0?.let { Filtr.kategorie(this, rodzaj, it) }
        val nazwy = LinkedHashMap<String, String>()
        cats?.forEach { nazwy[it.id] = it.name }
        val mapa = LinkedHashMap<String, MutableList<Channel>>()
        for (c in streams.filter { st -> cats == null || cats.isEmpty() || cats.any { it.id == st.categoryId } }) mapa.getOrPut(c.categoryId ?: "") { ArrayList() }.add(c)
        val out = ArrayList<Pair<String, List<Channel>>>()
        val kolejnosc = if (nazwy.isNotEmpty()) nazwy.keys.toList() + mapa.keys.filter { it !in nazwy } else mapa.keys.toList()
        for (k in kolejnosc) {
            val l = mapa[k] ?: continue
            out.add((nazwy[k] ?: "Pozostałe") to l.take(max))
            if (out.size >= 40) break
        }
        return out
    }

    // ----- zakladki -----

    private fun kafel(tekst: String, ikona: Int, k1: Int, k2: Int, wys: Int, akcja: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(k1, k2))
                .also { it.cornerRadius = dp(10).toFloat() }
            if (ikona != 0) addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(ikona); setColorFilter(Color.WHITE) },
                LinearLayout.LayoutParams(dp(if (wys > 100) 52 else 22), dp(if (wys > 100) 52 else 22)))
            addView(TextView(this@MainActivity).apply { text = tekst; setTextColor(Color.WHITE); textSize = if (wys > 100) 14f else 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), 0) })
            val lp = LinearLayout.LayoutParams(0, dp(wys), 1f); lp.setMargins(dp(5), dp(5), dp(5), dp(5)); layoutParams = lp
            setOnClickListener { akcja() }
        }

    private fun showStartSmarters() {
        tryb(getString(R.string.app_name), true, false)
        val teraz = java.text.SimpleDateFormat("HH:mm   dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date())
        dodajKomunikat()
        b.rowsList.addView(TextView(this).apply { text = teraz; setTextColor(0xFFCFD8FF.toInt()); textSize = 15f; setPadding(dp(18), dp(14), dp(18), dp(6)) })
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(9), dp(4), dp(9), 0) }
        r1.addView(kafel("TV NA ŻYWO", R.drawable.nav_tv, 0xFF1DB98A.toInt(), 0xFF3A47D5.toInt(), 190) { b.bottomNav.selectedItemId = R.id.tab_kanaly })
        r1.addView(kafel("FILMY", R.drawable.nav_filmy, 0xFFE8394D.toInt(), 0xFFF29A3C.toInt(), 190) { b.bottomNav.selectedItemId = R.id.tab_filmy })
        r1.addView(kafel("SERIALE", R.drawable.nav_seriale, 0xFF8E44C9.toInt(), 0xFF4E6FE0.toInt(), 190) { b.bottomNav.selectedItemId = R.id.tab_seriale })
        b.rowsList.addView(r1)
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(9), 0, dp(9), 0) }
        val zielony = 0xFF4FA36A.toInt()
        r2.addView(kafel("PROGRAM EPG", R.drawable.ic_series, zielony, zielony, 56) { ekrany.epg() })
        r2.addView(kafel("ULUBIONE", R.drawable.nav_ulubione, zielony, zielony, 56) { b.bottomNav.selectedItemId = R.id.tab_ulubione })
        r2.addView(kafel("SZUKAJ", R.drawable.ic_search, zielony, zielony, 56) { ekrany.szukaj() })
        b.rowsList.addView(r2)
        val stopka = TextView(this).apply { setTextColor(0xFFCFD8FF.toInt()); textSize = 13f; setPadding(dp(18), dp(18), dp(18), dp(10))
            text = if (demo) "Tryb demo" else "Zalogowano jako: ${u()}" }
        b.rowsList.addView(stopka)
        if (!demo) lifecycleScope.launch {
            val i = Api.accountInfo(s(), u(), p())
            if (i != null) stopka.text = "Wygasa: ${i.third}\nZalogowano jako: ${u()}"
        }
    }

    private fun showStart() {
        if (Konfig.styl(this) == "smarters") { showStartSmarters(); koniecAnimacjiStartu(); return }
        tryb(getString(R.string.app_name), true, false)
        dodajKomunikat()
        dodajBaner("Witaj w OK Player")
        val last = try { Session.lastUrl(this) } catch (e: Exception) { null }
        if (!demo) {
            val wiersze = ArrayList<Channel>()
            if (last != null) wiersze.add(Channel("last", Session.lastName(this).ifEmpty { "Ostatni kanał" },
                Session.lastLogo(this) ?: Session.lastName(this).let { ln -> liveCache?.firstOrNull { it.name == ln }?.logo }, null, 0, last))
            Session.kontynuuj(this).forEach { a ->
                val u = a[0] as String
                if (u != last) wiersze.add(Channel("k" + wiersze.size, (a[1] as String) + "  •  " + a[3] + "%", (a[2] as String).ifEmpty { null }, null, 0, u))
            }
            if (wiersze.isNotEmpty()) dodajRzad("Kontynuuj oglądanie", wiersze, { ch -> openPlayer(ch.copy(name = ch.name.substringBefore("  •  "))) })
        }
        if (demo) {
            dodajRzad("Popularne filmy", Demo.films, { ch -> openDetails(ch) }, 112, true)
            dodajRzad("Popularne seriale", Demo.seriale, { ch -> openPlayer(ch) }, 112, true)
            dodajRzad("Najnowsze kanały telewizyjne", Demo.channels, { ch -> openPlayer(ch) })
            koniecAnimacjiStartu()
            return
        }
        val wersja = wersjaEkranu
        showBusy()
        lifecycleScope.launch {
            try {
                // Listy z poprzedniego uruchomienia (czytane w tle, nie blokuja ekranu) - Start od razu gotowy.
                if (vodCache == null && !pamiecWczytana) {
                    pamiecWczytana = true
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val ctx = this@MainActivity
                        val v0 = Session.wczytajKanaly(ctx, "vod")
                        if (v0 != null) {
                            vodCache = v0; serCache = Session.wczytajKanaly(ctx, "ser"); liveCache = Session.wczytajKanaly(ctx, "live")
                            vodCats = Session.wczytajKategorie(ctx, "vod"); serCats = Session.wczytajKategorie(ctx, "ser"); zPamieci = true
                        }
                    }
                }
                // Po kolei (nie naraz): przy duzej liscie naraz zapychalo pamiec i aplikacja "nie odpowiadala".
                // Szybkosc daje pamiec list na dysku - za drugim razem Start jest od razu.
                val v = vodCache ?: Api.vodStreams(s(), u(), p(), null).also { vodCache = it }
                val vc = vodCats ?: try { Api.vodCategories(s(), u(), p()).also { vodCats = it } } catch (e: Exception) { emptyList() }
                val sr = serCache ?: try { Api.seriesList(s(), u(), p()).also { serCache = it } } catch (e: Exception) { emptyList() }
                val sc = serCats ?: try { Api.seriesCategories(s(), u(), p()).also { serCats = it } } catch (e: Exception) { emptyList() }
                val kan = liveCache ?: try { Api.liveStreams(s(), u(), p(), null).also { liveCache = it } } catch (e: Exception) { emptyList() }
                if (!zPamieci) launch(kotlinx.coroutines.Dispatchers.IO) { zapiszListy() } else { zPamieci = false; odswiezWTle() }
                hideBusy()
                koniecAnimacjiStartu()
                if (wersja != wersjaEkranu) return@launch
                if (Session.lastLogo(this@MainActivity) == null) Session.lastName(this@MainActivity).let { ln -> kan.firstOrNull { it.name == ln } }?.logo?.let { lg ->
                    Session.lastUrl(this@MainActivity)?.let { lu -> Session.saveLast(this@MainActivity, lu, Session.lastName(this@MainActivity), lg) }
                }
                // Liczenie rzedow w tle (duze listy) - ekran nie zamarza.
                val rzedy = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    fun ok(l: List<Channel>, c: List<Category>, r: String): List<Channel> {
                        val dozw = Filtr.kategorie(this@MainActivity, r, c).map { it.id }.toSet()
                        return if (c.isEmpty()) l else l.filter { it.categoryId in dozw }
                    }
                    val vf = ok(v, vc, "vod"); val sf = ok(sr, sc, "ser")
                    val out = ArrayList<Triple<String, List<Channel>, Int>>()  // 0 film, 1 serial, 2 kanal
                    out.add(Triple("Popularne filmy", if (vf.any { it.rating > 0 }) vf.sortedByDescending { it.rating }.take(10) else vf.takeLast(10).reversed(), 10))
                    out.add(Triple("Popularne seriale", if (sf.any { it.rating > 0 }) sf.sortedByDescending { it.rating }.take(10) else sf.takeLast(10).reversed(), 11))
                    val serwisy = listOf(
                        "Netflix" to Regex("netflix", RegexOption.IGNORE_CASE),
                        "HBO Max" to Regex("\\bhbo|\\bmax\\b", RegexOption.IGNORE_CASE),
                        "Disney+" to Regex("disney", RegexOption.IGNORE_CASE),
                        "Amazon Prime Video" to Regex("amazon|prime video|\\bprime\\b", RegexOption.IGNORE_CASE),
                        "Apple TV+" to Regex("apple", RegexOption.IGNORE_CASE),
                        "SkyShowtime" to Regex("skyshowtime|sky showtime", RegexOption.IGNORE_CASE),
                        "Canal+" to Regex("canal", RegexOption.IGNORE_CASE),
                        "Player" to Regex("\\bplayer\\b", RegexOption.IGNORE_CASE),
                        "Polsat Box Go" to Regex("polsat ?box|polsat go", RegexOption.IGNORE_CASE),
                        "Paramount+" to Regex("paramount", RegexOption.IGNORE_CASE)
                    )
                    for ((nazwa, wz) in serwisy) {
                        val idF = vc.filter { wz.containsMatchIn(it.name) }.map { it.id }.toSet()
                        val idS = sc.filter { wz.containsMatchIn(it.name) }.map { it.id }.toSet()
                        if (idF.isNotEmpty()) vf.filter { it.categoryId in idF }.takeLast(30).reversed().takeIf { it.isNotEmpty() }?.let { out.add(Triple("$nazwa — filmy", it, 0)) }
                        if (idS.isNotEmpty()) sf.filter { it.categoryId in idS }.takeLast(30).reversed().takeIf { it.isNotEmpty() }?.let { out.add(Triple("$nazwa — seriale", it, 1)) }
                    }
                    out.add(Triple("Najnowsze kanały telewizyjne", kan.takeLast(30).reversed(), 2))
                    out.add(Triple("Nowo dodane filmy", vf.takeLast(30).reversed(), 0))
                    out
                }
                if (wersja != wersjaEkranu) return@launch
                for ((tytul, lista, typ) in rzedy) {
                    if (lista.isEmpty()) continue
                    when (typ) {
                        10 -> dodajRzad(tytul, lista, { ch -> openDetails(ch) }, 130, true)
                        11 -> dodajRzad(tytul, lista, { ch -> openSeries(ch) }, 130, true)
                        0 -> dodajRzad(tytul, lista, { ch -> openDetails(ch) })
                        1 -> dodajRzad(tytul, lista, { ch -> openSeries(ch) })
                        else -> dodajRzad(tytul, lista, { ch -> openPlayer(ch) })
                    }
                }
            } catch (e: Exception) {
                hideBusy(); koniecAnimacjiStartu(); info(getString(R.string.err_net))
            }
        }
    }

    private fun showKanaly() {
        tryb(getString(R.string.tab_kanaly), false, true)
        b.recycler.adapter = channelAdapter
        if (demo) {
            channelAdapter.submit(Demo.channels)
            b.tvCount.text = "${Demo.channels.size}"
            return
        }
        val wersja = wersjaEkranu
        showBusy()
        lifecycleScope.launch {
            try {
                val cats = liveCats ?: Api.liveCategories(s(), u(), p()).also { liveCats = it }
                if (wersja != wersjaEkranu) return@launch
                liveFiltr = Filtr.kategorie(this@MainActivity, "live", cats)
                if (liveGroup >= liveFiltr.size) liveGroup = 0
                budujGrupy(liveFiltr)
                wczytajGrupe()
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
    }

    private fun budujGrupy(cats: List<Category>) {
        val bar = b.groupBarList
        bar.removeAllViews()
        if (cats.isEmpty()) { b.groupBar.visibility = View.GONE; return }
        b.groupBar.visibility = View.VISIBLE
        val akt = cats.getOrNull(liveGroup)?.name ?: ""
        val w = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            val tlo = android.graphics.drawable.GradientDrawable(); tlo.setColor(0xFF262626.toInt()); tlo.cornerRadius = dp(10).toFloat()
            background = tlo
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity).setTitle("Grupa")
                    .setItems(cats.map { it.name }.toTypedArray()) { _, k -> liveGroup = k; budujGrupy(cats); wczytajGrupe() }.show()
            }
        }
        w.addView(TextView(this).apply { text = "‹"; setTextColor(Color.WHITE); textSize = 26f; setPadding(0, 0, dp(14), 0) })
        w.addView(TextView(this).apply { text = akt; setTextColor(Color.WHITE); textSize = 18f })
        w.addView(TextView(this).apply { id = View.generateViewId(); tag = "licznik"; text = ""; setTextColor(Color.WHITE); textSize = 13f
            val t = android.graphics.drawable.GradientDrawable(); t.setColor(0xFF444444.toInt()); t.cornerRadius = dp(12).toFloat()
            background = t; setPadding(dp(10), dp(2), dp(10), dp(2)) },
            LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(12) })
        bar.addView(w, LinearLayout.LayoutParams(dp(340), -2).also { it.marginEnd = dp(6) })
        licznikKanalow = w.findViewWithTag("licznik")
    }

    private var licznikKanalow: TextView? = null

    private fun wczytajGrupe() {
        val id = liveFiltr.getOrNull(liveGroup)?.id
        val wersja = wersjaEkranu
        showBusy()
        lifecycleScope.launch {
            try {
                val list = Api.liveStreams(s(), u(), p(), id)
                hideBusy()
                if (wersja != wersjaEkranu) return@launch
                channelAdapter.submit(list)
                b.tvCount.text = ""
                licznikKanalow?.text = "${list.size}"
                if (list.isEmpty()) info("Pusto")
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
    }

    private fun showFilmy() {
        tryb(getString(R.string.tab_filmy), true, false)
        if (demo) { dodajRzad("Filmy", Demo.films, { ch -> openDetails(ch) }); return }
        val wersja = wersjaEkranu
        showBusy()
        lifecycleScope.launch {
            try {
                val v = vodCache ?: Api.vodStreams(s(), u(), p(), null).also { vodCache = it }
                val c = vodCats ?: try { Api.vodCategories(s(), u(), p()).also { vodCats = it } } catch (e: Exception) { emptyList() }
                hideBusy()
                if (wersja != wersjaEkranu) return@launch
                b.tvCount.text = "${v.size}"
                for ((n, l) in grupuj(v, c, 30)) dodajRzad(n, l, { ch -> openDetails(ch) })
                if (v.isEmpty()) info("Pusto")
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
    }

    private fun showSeriale() {
        tryb(getString(R.string.tab_seriale), true, false)
        if (demo) { dodajRzad("Seriale", Demo.seriale, { ch -> openPlayer(ch) }); return }
        val wersja = wersjaEkranu
        showBusy()
        lifecycleScope.launch {
            try {
                val v = serCache ?: Api.seriesList(s(), u(), p()).also { serCache = it }
                val c = serCats ?: try { Api.seriesCategories(s(), u(), p()).also { serCats = it } } catch (e: Exception) { emptyList() }
                hideBusy()
                if (wersja != wersjaEkranu) return@launch
                b.tvCount.text = "${v.size}"
                for ((n, l) in grupuj(v, c, 30, "ser")) dodajRzad(n, l, { ch -> openSeries(ch) })
                if (v.isEmpty()) info("Pusto")
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
    }

    private fun showUlubione() {
        tryb(getString(R.string.tab_ulubione), false, true)
        b.groupBar.visibility = View.VISIBLE
        val bar = b.groupBarList
        bar.removeAllViews()
        listOf("Telewizja na żywo", "Filmy").forEachIndexed { i, t ->
            bar.addView(Button(this).apply {
                text = t; isAllCaps = false; setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == favTab) 0xFF4F7CFF.toInt() else 0xFF1F1F23.toInt())
                setOnClickListener { favTab = i; showUlubione() }
            }, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(6) })
        }
        val kind = if (favTab == 0) "live" else "vod"
        val list = Session.favs(this, kind)
        if (favTab == 0) {
            b.recycler.adapter = channelAdapter
            channelAdapter.submit(list)
        } else {
            val ad = ChannelAdapter({ ch -> openDetails(ch) }, { ch -> przelaczUlubiony("vod", ch); showUlubione() })
            b.recycler.adapter = ad
            ad.submit(list)
        }
        b.tvCount.text = "${list.size}"
        if (list.isEmpty()) info("Brak pozycji.\nPrzytrzymaj kanał lub dotknij gwiazdki przy filmie.")
    }

    private fun przelaczUlubiony(kind: String, ch: Channel) {
        val dodany = Session.toggleFav(this, kind, ch)
        android.widget.Toast.makeText(this,
            if (dodany) "Dodano do ulubionych" else "Usunięto z ulubionych", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openSeries(ch: Channel) {
        val i = Intent(this, DetailsActivity::class.java)
        i.putExtra("id", ch.streamId); i.putExtra("name", ch.name); i.putExtra("logo", ch.logo)
        i.putExtra("url", ""); i.putExtra("demo", demo); i.putExtra("serial", true)
        startActivity(i)
    }

    private var pinOk = false

    private fun showUstawienia() {
        b.tvTitle.text = getString(R.string.tab_ustawienia)
        b.tvPane.visibility = View.GONE
        b.rowsScroll.visibility = View.GONE
        b.groupBar.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.tvInfo.visibility = View.GONE
        b.tvCount.text = ""
        val pin = Session.opt(this, "pin")
        if (pin.isNotEmpty() && Session.optBool(this, "lock_settings", true) && !pinOk) {
            b.settingsPanel.visibility = View.GONE
            pytajOPin(pin) { pinOk = true; showUstawienia() }
            return
        }
        b.settingsPanel.visibility = View.VISIBLE
        ustawieniaWyglad()
        budujUstawienia()
    }

    private fun ustawieniaWyglad() {
        b.root.setBackgroundColor(0xFF000000.toInt())
        b.header.setBackgroundColor(0xFF000000.toInt())
        b.bottomNav.setBackgroundColor(0xFF000000.toInt())
        b.tvTitle.setTextColor(Color.WHITE)
    }

    private fun zapiszListy() {
        vodCache?.let { Session.zapiszKanaly(this, "vod", it) }; serCache?.let { Session.zapiszKanaly(this, "ser", it) }
        liveCache?.let { Session.zapiszKanaly(this, "live", it) }
        vodCats?.let { Session.zapiszKategorie(this, "vod", it) }; serCats?.let { Session.zapiszKategorie(this, "ser", it) }
    }

    /** Pobiera swieze listy w tle (Start juz pokazany z pamieci) i zapisuje je na nastepny raz. */
    private fun odswiezWTle() {
        lifecycleScope.launch {
            try {
                Api.vodStreams(s(), u(), p(), null).takeIf { it.isNotEmpty() }?.let { vodCache = it }
                try { Api.vodCategories(s(), u(), p()) } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() }?.let { vodCats = it }
                try { Api.seriesList(s(), u(), p()) } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() }?.let { serCache = it }
                try { Api.seriesCategories(s(), u(), p()) } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() }?.let { serCats = it }
                try { Api.liveStreams(s(), u(), p(), null) } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() }?.let { liveCache = it }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { zapiszListy() }
            } catch (e: Exception) { }
        }
    }

    /**
     * Diagnostyka: jesli poprzednio Android pokazal "nie odpowiada", pokazuje miejsce w kodzie,
     * gdzie aplikacja stala (z raportu systemu) - do zrzutu ekranu dla Claude.
     */
    private fun raportZawieszenia() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val wyj = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            val anr = wyj.firstOrNull { it.reason == android.app.ApplicationExitInfo.REASON_ANR } ?: return
            val pref = getSharedPreferences("diag", MODE_PRIVATE)
            if (pref.getLong("anr_ts", 0) == anr.timestamp) return
            pref.edit().putLong("anr_ts", anr.timestamp).apply()
            val slad = anr.traceInputStream?.bufferedReader()?.use { it.readText() } ?: anr.description ?: "brak"
            val i = slad.indexOf("\"main\"")
            val main = (if (i >= 0) slad.substring(i) else slad).lines().take(40)
                .filter { it.contains("at ") || it.contains("main") || it.contains("uk.okok") }
                .joinToString("\n").take(2500)
            lifecycleScope.launch { try { Api.raportuj(Device.id(this@MainActivity), "ANR " + main.take(1500)) } catch (_: Exception) {} }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Raport zawieszenia (zrób zrzut ekranu dla Claude)")
                .setMessage(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("pl")).format(java.util.Date(anr.timestamp)) + "\n" + main)
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) { }
    }

    private var nakladkaStartu: android.view.View? = null
    private var startAnimMs = 0L

    /**
     * Animacja startu: ikona wkreca sie i rosnie, napis wjezdza, ikona "oddycha" i swieci
     * pasek ladowania - trwa tyle, ile laduje sie Start (min. 1,2 s, max 8 s), potem plynnie znika.
     */
    private fun animacjaStartu() {
        val dekor = window.decorView as android.view.ViewGroup
        val nakladka = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B1230.toInt(), 0xFF000000.toInt())); isClickable = true
        }
        val srodek = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER }
        val poswiata = android.widget.FrameLayout(this)
        val blask = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL
                gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(110).toFloat()
                colors = intArrayOf(0x664F7BFF, 0x004F7BFF) }
            alpha = 0f
        }
        poswiata.addView(blask, android.widget.FrameLayout.LayoutParams(dp(220), dp(220)).also { it.gravity = android.view.Gravity.CENTER })
        val ikona = android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_round) }
        poswiata.addView(ikona, android.widget.FrameLayout.LayoutParams(dp(112), dp(112)).also { it.gravity = android.view.Gravity.CENTER })
        srodek.addView(poswiata, LinearLayout.LayoutParams(dp(220), dp(220)))
        val napis = TextView(this).apply { text = "OK Player"; setTextColor(Color.WHITE); textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER; letterSpacing = 0.08f }
        srodek.addView(napis)
        val pasekTlo = android.widget.FrameLayout(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(2).toFloat() } }
        val pasek = android.view.View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF4F7BFF.toInt()); cornerRadius = dp(2).toFloat() } }
        pasekTlo.addView(pasek, android.widget.FrameLayout.LayoutParams(dp(60), -1))
        srodek.addView(pasekTlo, LinearLayout.LayoutParams(dp(160), dp(4)).also { it.topMargin = dp(26) })
        nakladka.addView(srodek, android.widget.FrameLayout.LayoutParams(-1, -1))
        dekor.addView(nakladka, android.view.ViewGroup.LayoutParams(-1, -1))
        nakladkaStartu = nakladka; startAnimMs = System.currentTimeMillis()

        ikona.scaleX = 0.2f; ikona.scaleY = 0.2f; ikona.alpha = 0f; ikona.rotation = -360f
        napis.alpha = 0f; napis.translationY = dp(24).toFloat(); pasekTlo.alpha = 0f
        ikona.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(700)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).withEndAction {
                // "oddychanie" ikony i poswiaty, dopoki Start sie laduje
                android.animation.ObjectAnimator.ofPropertyValuesHolder(ikona,
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)).apply {
                    duration = 700; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE; start() }
            }.start()
        blask.animate().alpha(1f).setStartDelay(300).setDuration(600).start()
        napis.animate().alpha(1f).translationY(0f).setStartDelay(400).setDuration(450).start()
        pasekTlo.animate().alpha(1f).setStartDelay(600).setDuration(300).start()
        android.animation.ObjectAnimator.ofFloat(pasek, "translationX", -dp(60).toFloat(), dp(160).toFloat()).apply {
            duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start() }
        // zabezpieczenie: najdluzej 8 s
        nakladka.postDelayed({ koniecAnimacjiStartu() }, 5000)
    }

    /** Wywolywane, gdy Start jest gotowy: animacja znika plynnie (nie wczesniej niz po 1,2 s). */
    private fun koniecAnimacjiStartu() {
        val n = nakladkaStartu ?: return
        nakladkaStartu = null
        val zostalo = (1200 - (System.currentTimeMillis() - startAnimMs)).coerceAtLeast(0)
        n.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(zostalo).setDuration(380).withEndAction {
            (n.parent as? android.view.ViewGroup)?.removeView(n)
        }.start()
    }

    /** Okno wyjscia w stylu aplikacji (czarne tlo, jasny przycisk jak "Ogladaj"). */
    private fun oknoWyjscia() {
        val d = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF1E1E1E.toInt()); cornerRadius = dp(16).toFloat() }
        }
        box.gravity = android.view.Gravity.CENTER_HORIZONTAL
        val ikona = android.widget.ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_round) }
        box.addView(ikona, LinearLayout.LayoutParams(dp(72), dp(72)).also { it.bottomMargin = dp(14) })
        box.addView(TextView(this).apply { text = "Zamknąć OK Player?"; setTextColor(Color.WHITE); textSize = 21f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER })
        box.addView(TextView(this).apply { text = "Czy na pewno chcesz wyjść z aplikacji?"; setTextColor(0xFFA6A6A6.toInt()); textSize = 16f; setPadding(0, dp(10), 0, dp(22)); gravity = android.view.Gravity.CENTER })
        fun przycisk(t: String, tlo: Int, kol: Int, akcja: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(kol); textSize = 17f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(tlo); cornerRadius = dp(28).toFloat() }
            isFocusable = true; setOnClickListener { akcja() }
        }
        box.addView(przycisk("Wyjdź", 0xFFE6E6E6.toInt(), 0xFF111111.toInt()) {
            // Animacja zamykania: ikona sie obraca i znika, okno i ekran gasna.
            ikona.animate().rotation(360f).scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(450).start()
            box.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setStartDelay(250).setDuration(300).start()
            b.root.animate().alpha(0f).setStartDelay(250).setDuration(350).withEndAction {
                d.dismiss()
                // Pelne zamkniecie: znika tez z listy ostatnich aplikacji i nie zostaje w pamieci.
                finishAndRemoveTask(); overridePendingTransition(0, android.R.anim.fade_out)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
            }.start()
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(przycisk("Anuluj", 0xFF2A2A2A.toInt(), Color.WHITE) { d.dismiss() }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        d.setContentView(box)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.86).toInt(), -2)
        d.window?.setDimAmount(0.7f)
        // Animacja otwarcia: okno wyskakuje z lekkim powiekszeniem, ikona wiruje.
        box.alpha = 0f; box.scaleX = 0.8f; box.scaleY = 0.8f
        ikona.rotation = -90f
        d.show()
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        ikona.animate().rotation(0f).setDuration(400).start()
    }

    private fun tak(v: Boolean) = getString(if (v) R.string.yes else R.string.no)

    private fun budujUstawienia() {
        val l = b.settingsList
        l.removeAllViews()
        fun odstep() = l.addView(View(this), LinearLayout.LayoutParams(-1, dp(14)))
        fun wiersz(t: String, sub: String? = null, ikona: Int = 0, akcja: () -> Unit) = l.addView(TextView(this).apply {
            text = if (sub.isNullOrEmpty()) t else "$t\n$sub"
            setTextColor(0xFFEDEDED.toInt()); textSize = 16f
            setBackgroundColor(0xFF262626.toInt())
            if (ikona != 0) {
                val d = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, ikona)?.mutate()
                d?.setBounds(0, 0, dp(24), dp(24)); d?.setTint(0xFFD0D0D0.toInt())
                setCompoundDrawables(d, null, null, null); compoundDrawablePadding = dp(18)
            }
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(2)
            layoutParams = lp
            setOnClickListener { akcja() }
        })
        val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }

        if (Konfig.wl(this, "premium")) wiersz("Serwis Premium", null, R.drawable.ic_crown) { ekrany.premium() }
        odstep()
        if (Konfig.wl(this, "zrodla")) wiersz("Źródła treści", null, R.drawable.ic_tv) { ekrany.zrodlaEkran() }
        if (Konfig.wl(this, "grupy")) wiersz("Zarządzaj grupami", null, R.drawable.ic_movie) { ekrany.grupy() }
        if (Konfig.wl(this, "epg")) wiersz("Dane EPG", null, R.drawable.ic_series) { ekrany.epg() }
        wiersz("Wyczyść historię", null, R.drawable.ic_star) { Session.clearHistory(this); dialog("Historia", "Wyczyszczono.") }
        odstep()
        if (Konfig.wl(this, "jezyk")) wiersz(getString(R.string.lang_button), null, R.drawable.ic_home) { ekrany.jezyk { k -> AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(k)) } }
        if (Konfig.wl(this, "styl_wybor")) wiersz("Styl interfejsu", if (Session.opt(this, "styl", "mol") == "smarters") "IPTV Smarters Pro" else "MyTVOnline+", R.drawable.ic_movie) { ekrany.styl { budujUstawienia() } }
        if (Konfig.wl(this, "wyglad")) wiersz(getString(R.string.skin_button), null, R.drawable.ic_settings) {
            ekrany.wyglad(skiny.map { it[0] as String }, getSharedPreferences("wyglad", MODE_PRIVATE).getInt("skin", 0)) { i ->
                getSharedPreferences("wyglad", MODE_PRIVATE).edit().putInt("skin", i).apply(); zastosujWyglad(); ustawieniaWyglad()
            }
        }
        wiersz("Dźwięk i napisy", "Język: " + Session.opt(this, "audio_lang").ifEmpty { "auto" } +
            ", napisy: " + tak(Session.opt(this, "subs") == "1"), R.drawable.ic_radio) { ekrany.dzwiek { budujUstawienia() } }
        wiersz("Opcje odtwarzacza", "Start: " + tak(Session.autostart(this)) + ", wznawianie: " + tak(Session.resume(this)) +
            ", powiększenie: " + tak(Session.opt(this, "zoom") == "1"), R.drawable.ic_tv) { ekrany.odtwarzacz { budujUstawienia() } }
        wiersz("Pytaj przed wyjściem", tak(Session.optBool(this, "pytaj_wyjscie", true)), R.drawable.ic_tv) {
            Session.setOptBool(this, "pytaj_wyjscie", !Session.optBool(this, "pytaj_wyjscie", true)); budujUstawienia()
        }
        if (Konfig.wl(this, "rodzic")) wiersz("Opcje rodzicielskie", null, R.drawable.ic_pin) { ekrany.rodzicielskie { pinOk = true } }
        odstep()
        if (Konfig.wl(this, "backup")) wiersz("Backup & Przywróć", null, R.drawable.ic_movie) { ekrany.backup() }
        wiersz("Zresetuj preferencje użytkownika", null, R.drawable.ic_settings) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Zresetować preferencje?")
                .setPositiveButton("Resetuj") { _, _ -> Session.clearOpts(this); pinOk = false; recreate() }
                .setNegativeButton("Anuluj", null).show()
        }
        odstep()
        wiersz("Informacje", "Wersja $vn", R.drawable.ic_home) { ekrany.informacje(vn ?: "") }
        wiersz(getString(R.string.btn_logout), null, R.drawable.ic_eye_off) {
            Session.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun dialog(t: String, m: String) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(t).setMessage(m)
            .setPositiveButton("OK", null).show()
    }

    private fun pokazDzwiek() {
        val nazwy = arrayOf("Automatycznie", "Polski", "English", "Deutsch")
        val kody = arrayOf("", "pl", "en", "de")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Język dźwięku")
            .setItems(nazwy) { _, i ->
                Session.setOpt(this, "audio_lang", kody[i])
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Napisy")
                    .setItems(arrayOf("Wyłączone", "Włączone (w tym samym języku)")) { _, j ->
                        Session.setOpt(this, "subs", if (j == 1) "1" else "0"); budujUstawienia()
                    }.show()
            }.show()
    }

    private fun pokazOpcjeOdtwarzacza() {
        val etykiety = arrayOf(
            getString(R.string.opt_autostart) + ": " + tak(Session.autostart(this)),
            getString(R.string.opt_resume) + ": " + tak(Session.resume(this)),
            "Powiększenie obrazu (bez pasów): " + tak(Session.opt(this, "zoom") == "1"))
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Opcje odtwarzacza")
            .setItems(etykiety) { _, i ->
                when (i) {
                    0 -> Session.setAutostart(this, !Session.autostart(this))
                    1 -> Session.setResume(this, !Session.resume(this))
                    else -> Session.setOpt(this, "zoom", if (Session.opt(this, "zoom") == "1") "0" else "1")
                }
                budujUstawienia()
            }.show()
    }

    private fun pokazPin() {
        val ma = Session.opt(this, "pin").isNotEmpty()
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN (4 cyfry)"
        }
        val bld = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("PIN do Ustawień").setView(et)
            .setPositiveButton("Ustaw") { _, _ ->
                val v = et.text.toString().trim()
                if (v.length == 4) { Session.setOpt(this, "pin", v); pinOk = true; budujUstawienia() }
            }
            .setNegativeButton("Anuluj", null)
        if (ma) bld.setNeutralButton("Wyłącz PIN") { _, _ -> Session.setOpt(this, "pin", ""); budujUstawienia() }
        bld.show()
    }

    private fun pytajOPin(pin: String, ok: () -> Unit) {
        val et = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Podaj PIN").setView(et)
            .setPositiveButton("OK") { _, _ ->
                if (et.text.toString().trim() == pin) ok()
                else { b.bottomNav.selectedItemId = R.id.tab_start }
            }
            .setNegativeButton("Anuluj") { _, _ -> b.bottomNav.selectedItemId = R.id.tab_start }
            .show()
    }

    private fun showBusy() {
        b.progress.visibility = View.VISIBLE
        b.tvInfo.visibility = View.GONE
    }

    private fun hideBusy() {
        b.progress.visibility = View.GONE
        b.tvInfo.visibility = View.GONE
    }

    private fun info(text: String) {
        b.tvInfo.text = text
        b.tvInfo.visibility = View.VISIBLE
    }

    // ----- wyglad (skorki demo) -----

    private val skiny = listOf(
        // nazwa, tlo, panel, akcent
        arrayOf("Granat (domyślny)", 0xFF070B16.toInt(), 0xFF0F1830.toInt(), 0xFF4F7CFF.toInt()),
        arrayOf("Czarny OLED", 0xFF000000.toInt(), 0xFF111111.toInt(), 0xFF38E0D6.toInt()),
        arrayOf("Zieleń nocna", 0xFF07130F.toInt(), 0xFF0E2219.toInt(), 0xFF35E08A.toInt()),
        arrayOf("Fiolet", 0xFF0E0A18.toInt(), 0xFF1B1430.toInt(), 0xFFB07CFF.toInt()),
        arrayOf("Bursztyn", 0xFF140E06.toInt(), 0xFF241A0C.toInt(), 0xFFFFC24D.toInt())
    )

    private fun zastosujWyglad() {
        val i = Konfig.skin(this, getSharedPreferences("wyglad", MODE_PRIVATE).getInt("skin", 0)).coerceIn(0, skiny.size - 1)
        var (bg, panel, akcent) = Triple(skiny[i][1] as Int, skiny[i][2] as Int, skiny[i][3] as Int)
        Konfig.kolor(this, "kolor_tlo")?.let { bg = it; panel = androidx.core.graphics.ColorUtils.blendARGB(it, Color.WHITE, 0.08f) }
        Konfig.kolor(this, "kolor_akcent")?.let { akcent = it }
        b.root.setBackgroundColor(bg)
        b.header.setBackgroundColor(panel)
        b.bottomNav.setBackgroundColor(panel)
        val stany = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val kol = android.content.res.ColorStateList(stany, intArrayOf(akcent, 0xFF7F90B8.toInt()))
        b.bottomNav.itemIconTintList = kol
        b.bottomNav.itemTextColor = kol
        b.bottomNav.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf((akcent and 0x00FFFFFF) or 0x33000000)
        b.tvTitle.setTextColor(akcent)
    }

    private fun pokazWyborWygladu() {
        val nazwy = skiny.map { it[0] as String }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.skin_title)
            .setItems(nazwy) { _, i ->
                getSharedPreferences("wyglad", MODE_PRIVATE).edit().putInt("skin", i).apply()
                zastosujWyglad()
            }
            .show()
    }

    private fun pokazWyborJezyka() {
        val nazwy = arrayOf("Polski", "English", "Deutsch", "Українська")
        val kody = arrayOf("pl", "en", "de", "uk")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.lang_title)
            .setItems(nazwy) { _, i ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(kody[i]))
            }
            .show()
    }

    private fun openDetails(ch: Channel) {
        val i = Intent(this, DetailsActivity::class.java)
        i.putExtra("id", ch.streamId)
        i.putExtra("name", ch.name)
        i.putExtra("logo", ch.logo)
        i.putExtra("url", Api.pelnyUrl(ch.directUrl, Session.server(this), Session.user(this), Session.pass(this)) ?: "")
        i.putExtra("demo", demo)
        startActivity(i)
    }

    private fun openPlayer(ch: Channel) {
        val url = Api.pelnyUrl(ch.directUrl, Session.server(this), Session.user(this), Session.pass(this)) ?: Api.liveStreamUrl(
            Session.server(this), Session.user(this), Session.pass(this), ch.streamId
        )
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("url", url)
        i.putExtra("name", ch.name)
        i.putExtra("demo", demo)
        Kontekst.kanaly = if (ch.directUrl.isNullOrEmpty() || ch.directUrl!!.contains("/live/")) (try { channelAdapter.lista() } catch (e: Exception) { emptyList() }) else emptyList()
        startActivity(i)
    }

    override fun onDestroy() {
        if (isFinishing && Session.optBool(this, "clear_exit", false)) Session.clearHistory(this)
        super.onDestroy()
    }
}
