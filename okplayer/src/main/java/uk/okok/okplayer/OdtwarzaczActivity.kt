package uk.okok.okplayer

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Odtwarzacz kanalow na zywo: pelny ekran, strumien przez serwer tv.ok-ok.uk, gora/dol = nastepny/poprzedni kanal. */
class OdtwarzaczActivity : AppCompatActivity() {
    private var gracz: ExoPlayer? = null
    private var sluchacz: Player.Listener? = null
    private lateinit var widok: PlayerView
    private lateinit var napis: TextView
    private var grupa: List<Kanal> = emptyList()
    private var biezacy: Kanal? = null
    private val limitEpg = kotlinx.coroutines.sync.Semaphore(4)
    private var proby = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pilot.wlacz(this)
        Jezyk.wlacz(this)
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        widok = PlayerView(this).apply { useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS); controllerShowTimeoutMs = 3000 }
        f.addView(widok, FrameLayout.LayoutParams(-1, -1))
        napis = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(36, 24, 36, 24); setBackgroundColor(0x99000000.toInt()); visibility = android.view.View.GONE
        }
        f.addView(napis, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START; setMargins(24, 24, 24, 24) })
        val przyciski = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun prz(t: String, a: () -> Unit) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 15f; setPadding(32, 18, 32, 18); setBackgroundColor(0x99000000.toInt()); isFocusable = true; setOnClickListener { a() } }
        if (!intent.getBooleanExtra("vod", false)) przyciski.addView(prz("⏮ Od początku") { odPoczatku() }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 16 })
        przyciski.addView(prz("⚙ Opcje") { opcje() })
        if (!intent.getBooleanExtra("vod", false)) przyciski.addView(prz("☰ Program / archiwum") { program() }.also { progNaObrazie = it }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginStart = 16 })
        widok.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> przyciski.visibility = v })
        f.addView(przyciski, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END; setMargins(24, 24, 24, 24) })
        if (intent.getBooleanExtra("vod", false)) {
            setContentView(f)
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            pokazNapis(intent.getStringExtra("nazwa") ?: ""); return
        }
        // TV jak w starej aplikacji: w pionie obraz u gory, pod nim kanal z programem i lista kanalow grupy; w poziomie pelny ekran.
        ramka = f
        korzen = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        korzen.addView(f, android.widget.LinearLayout.LayoutParams(-1, resources.displayMetrics.widthPixels * 9 / 16))
        val pn = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(32, 24, 32, 24); setBackgroundColor(0xFF111111.toInt()) }
        logoTv = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
        pn.addView(logoTv, android.widget.LinearLayout.LayoutParams(160, 100))
        val opisy = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(28, 0, 0, 0) }
        nazwaTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        terazTv = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 14f }
        potemTv = TextView(this).apply { setTextColor(0xFF9A9A9A.toInt()); textSize = 13f }
        pasekTeraz = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = android.view.View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(0xFFE0245E.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
        }
        godzinyTv = TextView(this).apply { setTextColor(0xFF9A9A9A.toInt()); textSize = 12f; visibility = android.view.View.GONE }
        opisy.addView(nazwaTv); opisy.addView(terazTv); opisy.addView(pasekTeraz, android.widget.LinearLayout.LayoutParams(-1, 10).apply { topMargin = 8; bottomMargin = 4 }); opisy.addView(godzinyTv); opisy.addView(potemTv)
        opisy.setOnClickListener { if (archiwumGra) biezacy?.let { przelacz(it) } }
        pn.addView(opisy, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        przyciskProgram = TextView(this).apply {
            text = "☰ Program"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(30, 18, 30, 18); isFocusable = true
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFF3D5A.toInt(), 0xFFB52AD6.toInt())).apply { cornerRadius = 40f }
            setOnClickListener { if (przewodnikOtwarty) zamknijPrzewodnik() else otworzPrzewodnik() }
        }
        // Z przycisku Program na podgladzie: tylko archiwum tego kanalu (bez listy i bez przelacznika). Zwykle: lista kanalow + Program.
        if (intent.getBooleanExtra("przewodnik", false)) przyciskProgram.visibility = android.view.View.GONE
        pn.addView(przyciskProgram)
        korzen.addView(pn); panel = pn
        przewodnik = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        korzen.addView(przewodnik, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        listaTv = androidx.recyclerview.widget.RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@OdtwarzaczActivity) }
        korzen.addView(listaTv, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(korzen)
        widok.setFullscreenButtonClickListener { pelny ->
            requestedOrientation = if (pelny) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        biezacy = Kanal(intent.getStringExtra("id") ?: "", intent.getStringExtra("nazwa") ?: "", intent.getStringExtra("logo"), intent.getStringExtra("kat") ?: "", 0)
        trybNaZywo(true)
        if (intent.getBooleanExtra("pelny", false)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Pod obrazem zawsze program z archiwum TYLKO tego kanalu (w pionie). Na pelnym ekranie sam obraz.
        if (intent.getBooleanExtra("przewodnik", false)) window.decorView.post { otworzPrzewodnik() }
        ustawPanel()
        uloz(resources.configuration.orientation)
        lifecycleScope.launch {
            val kat = biezacy!!.kat
            grupa = withContext(Dispatchers.IO) { Pamiec.kanaly(this@OdtwarzaczActivity)?.filter { it.kat == kat } ?: emptyList() }
            listaTv?.adapter = ListaAdapter()
            val i = grupa.indexOfFirst { it.id == biezacy?.id }; if (i > 2) listaTv?.scrollToPosition(i - 2)
        }
    }

    private var ramka: FrameLayout? = null
    private var progNaObrazie: TextView? = null
    private var archiwumGra = false
    private var terazWpis: Wpis? = null

    /** Po wybraniu audycji z programu: program sie chowa, obraz od razu na pelny ekran. */
    private fun poWyborze() {
        // Pelny ekran: program chowa sie, obraz na calosc. Podglad w pionie: archiwum gra w obrazie na gorze, program zostaje pod spodem.
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            if (przewodnikOtwarty) zamknijPrzewodnik()
            uloz(android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        } else if (przewodnikOtwarty) rysujPrzewodnik()
    }

    // ---- Archiwum ze straznikiem: gdy obraz stoi albo jest blad, sam probuje kolejnego sposobu ----
    private var warianty: List<String> = emptyList()
    private var wariant = 0
    private var ostPoz = -1L
    private var stoi = 0
    private val straznik = object : Runnable {
        override fun run() {
            val g = gracz ?: return
            if (!archiwumGra) return
            val poz = g.currentPosition
            if (g.playWhenReady && poz == ostPoz) stoi++ else stoi = 0
            ostPoz = poz
            if (stoi >= 4) { stoi = 0; nastepnyWariant() }   // ok. 12 s bez ruchu
            widok.postDelayed(this, 3000)
        }
    }
    private fun grajArchiwum(id: String, w: Wpis) {
        warianty = Serwer.adresyArchiwum(this, id, w); wariant = 0; ostPoz = -1; stoi = 0
        proby = 0; trybNaZywo(false)
        intent.putExtra("url", warianty[0]); graj(warianty[0])
        widok.removeCallbacks(straznik); widok.postDelayed(straznik, 3000)
    }
    private fun nastepnyWariant(): Boolean {
        if (!archiwumGra || wariant + 1 >= warianty.size) return false
        wariant++; ostPoz = -1
        pokazNapis("Archiwum: inny sposób " + (wariant + 1) + "/" + warianty.size + "…")
        intent.putExtra("url", warianty[wariant]); graj(warianty[wariant]); return true
    }

    /** Od poczatku: biezaca audycja z archiwum od jej pierwszej minuty (czego nie ma MyTvOnline). */
    private fun odPoczatku() {
        val w = terazWpis; val ch = biezacy
        if (w == null || ch == null) { pokazNapis("Brak programu dla tego kanału"); return }
        if (!w.archiwum) { pokazNapis("Ten kanał nie ma archiwum"); return }
        grajArchiwum(ch.id, w.copy(koniec = System.currentTimeMillis() / 1000)); naArchiwum(w)
        pokazNapis("⏮ Od początku: " + w.tytul)
    }

    /** Przesuniecie palcem po obrazie w gore / w dol = nastepny / poprzedni kanal (z nazwa kanalu na ekranie). */
    private val gest by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || intent.getBooleanExtra("vod", false)) return false
                val dy = e2.y - e1.y; val dx = e2.x - e1.x
                if (Math.abs(dy) > 150 && Math.abs(dy) > Math.abs(dx) * 1.5f && Math.abs(vy) > 600) { zmien(if (dy < 0) 1 else -1); biezacy?.let { pokazNapis(it.nazwa) }; return true }
                return false
            }
        })
    }
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val r = android.graphics.Rect(); widok.getGlobalVisibleRect(r)
        // Przewijanie programu (lista na obrazie) to nie zmiana kanalu - gest liczy sie tylko na samym obrazie.
        val pr = android.graphics.Rect()
        val naLiscie = przewodnik?.let { it.visibility == android.view.View.VISIBLE && it.getGlobalVisibleRect(pr) && pr.contains(ev.rawX.toInt(), ev.rawY.toInt()) } == true
        if (!naLiscie && r.contains(ev.rawX.toInt(), ev.rawY.toInt()) && gest.onTouchEvent(ev) && ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return true
        return super.dispatchTouchEvent(ev)
    }

    /** W panelu pod obrazem widac, co leci z archiwum, i jak wrocic na zywo. */
    private fun naArchiwum(w: Wpis) {
        if (panel == null) return
        val fmt = java.text.SimpleDateFormat("EEE d.MM HH:mm", Jezyk.loc)
        terazTv.text = "⏪ Archiwum: " + fmt.format(java.util.Date(w.start * 1000)) + "  " + w.tytul
        potemTv.text = "▶ Dotknij tutaj, aby wrócić na żywo"; pasekTeraz?.visibility = android.view.View.GONE; godzinyTv?.visibility = android.view.View.GONE
    }

    /** Na zywo: bez paska czasu i przewijania (jak w innych aplikacjach IPTV); w archiwum pasek i przewijanie wracaja. */
    private fun trybNaZywo(naZywo: Boolean) {
        archiwumGra = !naZywo
        widok.setShowPreviousButton(false); widok.setShowNextButton(false)
        widok.setShowRewindButton(!naZywo); widok.setShowFastForwardButton(!naZywo)
        for (n in listOf(androidx.media3.ui.R.id.exo_progress, androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration, androidx.media3.ui.R.id.exo_time))
            widok.findViewById<android.view.View>(n)?.visibility = if (naZywo) android.view.View.GONE else android.view.View.VISIBLE
    }
    private var przewodnik: android.widget.LinearLayout? = null
    private lateinit var przyciskProgram: TextView
    private var przewodnikOtwarty = false
    private var wpisy: List<Wpis> = emptyList()
    private var dzien = ""

    /** Przewodnik jak w starej aplikacji: dni u gory, audycje pod spodem; przeszle z archiwum (⏪) mozna obejrzec. */
    private fun otworzPrzewodnik() {
        val ch = biezacy ?: return
        val pr = przewodnik ?: return
        przewodnikOtwarty = true; przyciskProgram.text = "✕ Kanały"
        listaTv?.visibility = android.view.View.GONE; pr.visibility = android.view.View.VISIBLE
        uloz(resources.configuration.orientation)
        pr.removeAllViews()
        pr.addView(TextView(this).apply { text = "Ładuję program…"; setTextColor(0xFF9A9A9A.toInt()); setPadding(32, 24, 32, 24) })
        lifecycleScope.launch {
            wpisy = withContext(Dispatchers.IO) { Serwer.przewodnik(this@OdtwarzaczActivity, ch.id) }
            if (!przewodnikOtwarty || biezacy?.id != ch.id) return@launch
            val dz = java.text.SimpleDateFormat("yyyy-MM-dd", Jezyk.loc)
            dzien = dz.format(java.util.Date())
            rysujPrzewodnik()
        }
    }

    private fun zamknijPrzewodnik() {
        przewodnikOtwarty = false; przyciskProgram.text = "☰ Program"
        przewodnik?.visibility = android.view.View.GONE
        listaTv?.visibility = if (intent.getBooleanExtra("przewodnik", false) || resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun rysujPrzewodnik() {
        val pr = przewodnik ?: return
        pr.removeAllViews()
        if (wpisy.isEmpty()) { pr.addView(TextView(this).apply { text = "Brak programu dla tego kanału"; setTextColor(0xFF9A9A9A.toInt()); setPadding(32, 24, 32, 24) }); return }
        val dz = java.text.SimpleDateFormat("yyyy-MM-dd", Jezyk.loc)
        val nazwaDnia = java.text.SimpleDateFormat("EEE d.MM", Jezyk.loc)
        val dzis = dz.format(java.util.Date())
        // Tylko dni z archiwum tego kanalu (+ dzis); bez przyszlych dni i bez dni, ktorych nie da sie obejrzec.
        val dni = wpisy.filter { it.archiwum || dz.format(java.util.Date(it.start * 1000)) == dzis }
            .map { dz.format(java.util.Date(it.start * 1000)) }.distinct().filter { it <= dzis }.ifEmpty { listOf(dzis) }
        val pasek = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(20, 12, 20, 12) }
        for (d in dni) {
            pasek.addView(TextView(this).apply {
                text = if (d == dzis) "Dziś" else nazwaDnia.format(dz.parse(d)!!)
                textSize = 15f; setPadding(30, 14, 30, 14); isFocusable = true
                val wyb = d == dzien
                setTextColor(if (wyb) Color.BLACK else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(if (wyb) Color.WHITE else 0xFF2A2A2A.toInt()); cornerRadius = 40f }
                setOnClickListener { dzien = d; rysujPrzewodnik() }
            }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 14 })
        }
        pr.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pasek) })
        val lista = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val sv = android.widget.ScrollView(this).apply { addView(lista) }
        pr.addView(sv, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val t = System.currentTimeMillis() / 1000
        val godz = java.text.SimpleDateFormat("HH:mm", Jezyk.loc)
        var terazView: android.view.View? = null
        // Tylko archiwum tego kanalu: audycje, ktore mozna obejrzec, i ta, ktora leci teraz (bez przyszlych).
        for (w in wpisy.filter { dz.format(java.util.Date(it.start * 1000)) == dzien && it.start <= t && (it.archiwum || it.koniec > t) }) {
            val teraz = w.start <= t && w.koniec > t
            val archiwum = w.archiwum && w.koniec <= t
            val szary = 0xFF9A9A9A.toInt()
            val minelo = w.koniec <= t
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(32, 22, 32, 22); isFocusable = true
                setBackgroundColor(if (teraz) 0xFF2A2A2A.toInt() else Color.TRANSPARENT)
                addView(TextView(this@OdtwarzaczActivity).apply {
                    text = godz.format(java.util.Date(w.start * 1000)) + "\n" + godz.format(java.util.Date(w.koniec * 1000))
                    setTextColor(if (teraz) Color.WHITE else szary); textSize = 14f; minWidth = 150
                })
                val prawa = android.widget.LinearLayout(this@OdtwarzaczActivity).apply { orientation = android.widget.LinearLayout.VERTICAL }
                prawa.addView(TextView(this@OdtwarzaczActivity).apply {
                    text = w.tytul; textSize = 16f; setTypeface(typeface, if (teraz) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (minelo && !archiwum) 0xFF777777.toInt() else Color.WHITE)
                })
                val znak = when { teraz -> "● NA ŻYWO  •  jeszcze " + ((w.koniec - t) / 60) + " min"; archiwum -> "⏪ Archiwum – dotknij, aby obejrzeć"; minelo -> "Bez archiwum"; else -> "Wkrótce  •  " + ((w.koniec - w.start) / 60) + " min" }
                prawa.addView(TextView(this@OdtwarzaczActivity).apply {
                    text = znak; textSize = 13f
                    setTextColor(when { teraz -> 0xFFE0245E.toInt(); archiwum -> 0xFF4FC3F7.toInt(); else -> szary })
                }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = 4 })
                if (teraz) prawa.addView(android.widget.ProgressBar(this@OdtwarzaczActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = ((t - w.start) * 100 / (w.koniec - w.start).coerceAtLeast(1)).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(0xFFE0245E.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                }, android.widget.LinearLayout.LayoutParams(-1, 10).apply { topMargin = 8 })
                if (w.opis.isNotEmpty()) prawa.addView(TextView(this@OdtwarzaczActivity).apply {
                    text = w.opis; textSize = 13f; setTextColor(0xFFBDBDBD.toInt()); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
                addView(prawa, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
                setOnLongClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(this@OdtwarzaczActivity).setTitle(w.tytul)
                        .setMessage(godz.format(java.util.Date(w.start * 1000)) + "–" + godz.format(java.util.Date(w.koniec * 1000)) + "\n\n" + w.opis.ifEmpty { "Brak opisu" })
                        .setPositiveButton(if (archiwum) "⏪ Obejrzyj" else if (teraz) "▶ Na żywo" else "OK") { _, _ -> if (archiwum || teraz) performClick() }
                        .setNegativeButton("Zamknij", null).show().also { Jezyk.okno(it) }; true
                }
                setOnClickListener {
                    when {
                        archiwum -> { pokazNapis("Archiwum: " + w.tytul); grajArchiwum(biezacy!!.id, w); naArchiwum(w); poWyborze() }
                        teraz -> { biezacy?.let { przelacz(it) }; poWyborze() }
                        else -> pokazNapis(if (w.start > t) "Audycja jeszcze się nie zaczęła" else "Ta audycja nie ma archiwum")
                    }
                }
            }
            lista.addView(r)
            if (teraz) terazView = r
        }
        terazView?.let { v -> sv.post { sv.scrollTo(0, (v.top - 200).coerceAtLeast(0)) } }
    }
    private lateinit var korzen: android.widget.LinearLayout
    private var panel: android.widget.LinearLayout? = null
    private var listaTv: androidx.recyclerview.widget.RecyclerView? = null
    private lateinit var logoTv: android.widget.ImageView
    private lateinit var nazwaTv: TextView
    private lateinit var terazTv: TextView
    private lateinit var potemTv: TextView
    private var pasekTeraz: android.widget.ProgressBar? = null
    private var godzinyTv: TextView? = null

    private fun uloz(o: Int) {
        val f = ramka ?: return
        val poziomo = o == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        f.layoutParams = android.widget.LinearLayout.LayoutParams(-1, if (poziomo) -1 else resources.displayMetrics.widthPixels * 9 / 16)
        panel?.visibility = if (poziomo) android.view.View.GONE else android.view.View.VISIBLE
        progNaObrazie?.visibility = if (poziomo) android.view.View.VISIBLE else android.view.View.GONE
        listaTv?.visibility = if (poziomo || przewodnikOtwarty || intent.getBooleanExtra("przewodnik", false)) android.view.View.GONE else android.view.View.VISIBLE
        przewodnik?.let { pr ->
            val rodzic = pr.parent as? android.view.ViewGroup
            if (poziomo && rodzic !== f) {
                rodzic?.removeView(pr)
                pr.setBackgroundColor(0xFF101010.toInt()); pr.isClickable = true
                f.addView(pr, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.5).toInt(), -1).apply { gravity = Gravity.END })
            } else if (!poziomo && rodzic === f) {
                f.removeView(pr); pr.setBackgroundColor(Color.TRANSPARENT)
                korzen.addView(pr, korzen.indexOfChild(listaTv), android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            }
            pr.visibility = if (przewodnikOtwarty) android.view.View.VISIBLE else android.view.View.GONE
            if (poziomo && przewodnikOtwarty) pr.bringToFront()
        }
        // Gdy program jest otwarty, przyciski na obrazie znikaja (nie nachodza na liste).
        if (poziomo && przewodnikOtwarty) progNaObrazie?.visibility = android.view.View.GONE
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (poziomo) c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onConfigurationChanged(c: android.content.res.Configuration) { super.onConfigurationChanged(c); uloz(c.orientation) }

    /** Logo, nazwa i program (teraz / potem) biezacego kanalu. */
    private fun ustawPanel() {
        val ch = biezacy ?: return
        if (panel == null) return
        nazwaTv.text = ch.nazwa; logoTv.setImageDrawable(null); if (ch.logo != null) coilLoad(logoTv, ch.logo)
        terazTv.text = ""; potemTv.text = ""; pasekTeraz?.visibility = android.view.View.GONE; godzinyTv?.visibility = android.view.View.GONE
        lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { Serwer.przewodnik(this@OdtwarzaczActivity, ch.id) }
            val t = System.currentTimeMillis() / 1000
            val fmt = java.text.SimpleDateFormat("HH:mm", Jezyk.loc)
            val i = l.indexOfFirst { it.start <= t && it.koniec > t }
            terazWpis = l.getOrNull(i)
            if (biezacy?.id != ch.id) return@launch
            if (i >= 0) {
                terazTv.text = "Teraz: " + l[i].tytul
                val w = l[i]; val pr = ((t - w.start) * 100 / (w.koniec - w.start).coerceAtLeast(1)).toInt()
                pasekTeraz?.progress = pr; pasekTeraz?.visibility = android.view.View.VISIBLE
                godzinyTv?.text = fmt.format(java.util.Date(w.start * 1000)) + "–" + fmt.format(java.util.Date(w.koniec * 1000)) + "  •  jeszcze " + ((w.koniec - t) / 60) + " min"; godzinyTv?.visibility = android.view.View.VISIBLE
                l.getOrNull(i + 1)?.let { potemTv.text = "Potem: " + fmt.format(java.util.Date(it.start * 1000)) + "  " + it.tytul }
            } else {
                val tl = withContext(Dispatchers.IO) { Serwer.terazLeci(this@OdtwarzaczActivity, ch.id) }
                if (biezacy?.id == ch.id) { terazTv.text = if (tl.isEmpty()) "" else "Teraz: $tl"
                    val pr = Serwer.postep(ch.id); if (pr >= 0) { pasekTeraz?.progress = pr; pasekTeraz?.visibility = android.view.View.VISIBLE }
                    Serwer.godziny(ch.id).takeIf { it.isNotEmpty() }?.let { godzinyTv?.text = it; godzinyTv?.visibility = android.view.View.VISIBLE } }
            }
        }
    }

    private fun coilLoad(v: android.widget.ImageView, u: String) { v.load(u) }

    inner class ListaAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        override fun getItemCount() = grupa.size
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val w = android.widget.LinearLayout(p.context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(32, 20, 32, 20); isFocusable = true
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2)
                addView(android.widget.ImageView(p.context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }, android.widget.LinearLayout.LayoutParams(130, 80))
                addView(android.widget.LinearLayout(p.context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(28, 0, 0, 0)
                    addView(TextView(p.context).apply { setTextColor(Color.WHITE); textSize = 16f; maxLines = 1 })
                    addView(TextView(p.context).apply { setTextColor(0xFF9A9A9A.toInt()); textSize = 13f; maxLines = 1 })
                    addView(android.widget.ProgressBar(p.context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progressTintList = android.content.res.ColorStateList.valueOf(0xFFE0245E.toInt()); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                    }, android.widget.LinearLayout.LayoutParams(-1, 10).apply { topMargin = 8 })
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            }
            return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(w) {}
        }
        override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, i: Int) {
            val w = h.itemView as android.widget.LinearLayout; val ch = grupa[i]
            (w.getChildAt(0) as android.widget.ImageView).apply { setImageDrawable(null); if (ch.logo != null) load(ch.logo) }
            val opis = w.getChildAt(1) as android.widget.LinearLayout
            (opis.getChildAt(0) as TextView).text = ch.nazwa
            val tt = opis.getChildAt(1) as TextView; val pb = opis.getChildAt(2) as android.widget.ProgressBar
            tt.text = ""; pb.visibility = android.view.View.INVISIBLE; tt.tag = ch.id
            lifecycleScope.launch {
                val t = limitEpg.withPermit { withContext(Dispatchers.IO) { Serwer.terazLeci(this@OdtwarzaczActivity, ch.id) } }
                if (tt.tag != ch.id) return@launch
                tt.text = if (t.isEmpty()) "" else "Teraz: $t"
                val pr = Serwer.postep(ch.id); if (pr >= 0) { pb.progress = pr; pb.visibility = android.view.View.VISIBLE }
            }
            w.setBackgroundColor(if (ch.id == biezacy?.id) 0xFF2A2A2A.toInt() else Color.TRANSPARENT)
            w.setOnClickListener { przelacz(ch) }
        }
    }

    private fun przelacz(n: Kanal) {
        // Nowy kanal = zawsze na zywo: koniec archiwum i jego straznika.
        widok.removeCallbacks(straznik); warianty = emptyList(); wariant = 0
        biezacy = n; proby = 0
        Ust.zapiszOstatni(this, n)
        val url = Serwer.adresKanalu(this, n.id)
        intent.putExtra("url", url)
        pokazNapis(n.nazwa); trybNaZywo(true); graj(url); ustawPanel()
        if (przewodnikOtwarty) otworzPrzewodnik()
        listaTv?.adapter?.notifyDataSetChanged()
    }

    private fun pokazNapis(t: String) {
        napis.text = t; napis.visibility = android.view.View.VISIBLE
        napis.removeCallbacks(ukryj); napis.postDelayed(ukryj, 4000)
    }
    private val ukryj = Runnable { napis.visibility = android.view.View.GONE }

    private fun graj(url: String) {
        // Zawsze ten sam, zywy odtwarzacz na ekranie (inaczej po zmianie kanalu obraz byl czarny albo zostawalo stare archiwum).
        WspolnyGracz.wlasciciel = "duzy"
        val p = WspolnyGracz.pobierz(this)
        if (gracz !== p) {
            sluchacz?.let { s -> gracz?.removeListener(s); p.addListener(s) }
            gracz = p; widok.player = p
        }
        WspolnyGracz.graj(this, url, odNowa = true)
    }

    override fun onStart() {
        super.onStart()
        // Serwer przepuszcza strumien przez siebie dla odtwarzaczy przedstawiajacych sie jak VLC (dostawca blokuje telefon).
        val vodTryb = intent.getBooleanExtra("vod", false)
        // Film: inny niz grajacy - zaczynamy od nowa; ten sam (wraca z mini-okna) - gra dalej bez przerwy.
        if (vodTryb && WspolnyGracz.url != intent.getStringExtra("url")) WspolnyGracz.zwolnij(WspolnyGracz.wlasciciel)
        WspolnyGracz.wlasciciel = "duzy"
        val p = WspolnyGracz.pobierz(this)
        widok.player = p; gracz = p
        sluchacz?.let { p.removeListener(it) }
        sluchacz = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lifecycleScope.launch(Dispatchers.IO) { Serwer.raport(this@OdtwarzaczActivity, "graj blad " + error.errorCodeName + " " + (biezacy?.nazwa ?: "")) }
                if (archiwumGra && nastepnyWariant()) return
                if (proby < 3) {
                    proby++; pokazNapis("Ponawiam… ($proby/3)")
                    lifecycleScope.launch { delay(1500); graj(intent.getStringExtra("url") ?: return@launch) }
                } else pokazNapis("Kanał nie działa (" + error.errorCodeName + ")")
            }
            override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) proby = 0 }
        }
        p.addListener(sluchacz!!)
        val url = intent.getStringExtra("url") ?: return
        val nowy = WspolnyGracz.graj(this, url)
        // Filmy i odcinki: wznawianie od miejsca przerwania (gdy film zaczyna sie od nowa).
        if (nowy && intent.getBooleanExtra("vod", false)) {
            val poz = Ust.pozycja(this, url)
            if (poz > 30_000) { p.seekTo(poz); pokazNapis("Wznawiam od " + poz / 60000 + " min") }
        }
    }

    private fun zmien(o: Int) {
        val b = biezacy ?: return
        if (grupa.isEmpty()) return
        val i = grupa.indexOfFirst { it.id == b.id }
        przelacz(grupa[((if (i < 0) 0 else i) + o + grupa.size) % grupa.size])
    }

    private var wylacznik: android.os.CountDownTimer? = null

    private fun wybor(t: String, o: List<String>, z: Int, a: (Int) -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(t).setSingleChoiceItems(o.toTypedArray(), z) { d, i -> a(i); d.dismiss() }.setNegativeButton("Zamknij", null).show().also { Jezyk.okno(it) }
    }

    /** Opcje jak w MyTvOnline: dzwiek, napisy, format obrazu, predkosc (filmy), wylacznik czasowy. */
    private fun opcje() {
        val p = gracz ?: return
        val vod = intent.getBooleanExtra("vod", false)
        val l = mutableListOf("Ścieżka dźwiękowa", "Napisy", "Format obrazu")
        if (vod) l.add("Prędkość odtwarzania")
        l.add("Wyłącznik czasowy")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Opcje").setItems(l.toTypedArray()) { _, i ->
            when (l[i]) {
                "Ścieżka dźwiękowa" -> sciezki(p, androidx.media3.common.C.TRACK_TYPE_AUDIO)
                "Napisy" -> sciezki(p, androidx.media3.common.C.TRACK_TYPE_TEXT)
                "Format obrazu" -> {
                    val t = listOf("Dopasuj" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, "Powiększ (bez pasów)" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Rozciągnij" to androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL)
                    wybor("Format obrazu", t.map { it.first }, t.indexOfFirst { it.second == widok.resizeMode }) { k -> widok.resizeMode = t[k].second }
                }
                "Prędkość odtwarzania" -> { val pr = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f); wybor("Prędkość", pr.map { if (it == 1f) "Normalna" else "${it}x" }, pr.indexOf(p.playbackParameters.speed)) { k -> p.setPlaybackSpeed(pr[k]) } }
                else -> {
                    val m = listOf(0, 15, 30, 60, 90, 120)
                    wybor("Wyłącznik czasowy", m.map { if (it == 0) "Wyłączony" else "$it min" }, -1) { k ->
                        wylacznik?.cancel(); wylacznik = null
                        if (m[k] > 0) wylacznik = object : android.os.CountDownTimer(m[k] * 60_000L, 60_000L) { override fun onTick(x: Long) {}; override fun onFinish() { finish() } }.start()
                    }
                }
            }
        }.show()
    }

    private fun sciezki(p: ExoPlayer, typ: Int) {
        val g = p.currentTracks.groups.filter { it.type == typ }
        val nazwy = ArrayList<String>(); val wyb = ArrayList<Pair<Int, Int>>(); var akt = -1
        if (typ == androidx.media3.common.C.TRACK_TYPE_TEXT) { nazwy.add("Wyłączone"); wyb.add(-1 to -1) }
        g.forEachIndexed { gi, gr -> for (t in 0 until gr.length) {
            val f = gr.getTrackFormat(t)
            val jez = f.language?.let { java.util.Locale(it).getDisplayLanguage(Jezyk.loc) } ?: "Ścieżka ${nazwy.size + 1}"
            if (gr.isTrackSelected(t)) akt = nazwy.size
            nazwy.add(listOfNotNull(jez, f.label, if (f.channelCount > 0) "${f.channelCount} kan." else null).distinct().joinToString(" • ")); wyb.add(gi to t)
        } }
        if (nazwy.size <= (if (typ == androidx.media3.common.C.TRACK_TYPE_TEXT) 1 else 0)) { pokazNapis("Brak innych ścieżek"); return }
        wybor(if (typ == androidx.media3.common.C.TRACK_TYPE_AUDIO) "Ścieżka dźwiękowa" else "Napisy", nazwy, if (akt < 0) 0 else akt) { k ->
            val (gi, t) = wyb[k]
            val b = p.trackSelectionParameters.buildUpon()
            if (gi < 0) b.setTrackTypeDisabled(typ, true) else b.setTrackTypeDisabled(typ, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g[gi].mediaTrackGroup, t))
            p.trackSelectionParameters = b.build()
        }
    }

    /** Program kanalu; przeszle audycje z archiwum mozna obejrzec (cofanie). */
    private fun program() {
        // TV: ten sam pelny przewodnik co w pionie (dni, opisy, NA ZYWO, archiwum) - w poziomie jako panel na obrazie.
        if (przewodnik != null) { if (przewodnikOtwarty) zamknijPrzewodnik() else otworzPrzewodnik(); uloz(resources.configuration.orientation); return }
        val ch = biezacy ?: return
        pokazNapis("Ładuję program…")
        lifecycleScope.launch {
            val l = withContext(Dispatchers.IO) { Serwer.przewodnik(this@OdtwarzaczActivity, ch.id) }
            if (l.isEmpty()) { pokazNapis("Brak programu dla tego kanału"); return@launch }
            val teraz = System.currentTimeMillis() / 1000
            val wid = l.filter { it.koniec > teraz - 3 * 86400 }.takeLast(80)
            val fmt = java.text.SimpleDateFormat("EEE HH:mm", Jezyk.loc)
            val nazwy = wid.map { w -> fmt.format(java.util.Date(w.start * 1000)) + "  " + w.tytul + (if (w.archiwum && w.koniec < teraz) "  ⏪" else if (w.start <= teraz && w.koniec > teraz) "  ● teraz" else "") }
            androidx.appcompat.app.AlertDialog.Builder(this@OdtwarzaczActivity).setTitle(ch.nazwa + " — program (⏪ = archiwum)").setItems(nazwy.toTypedArray()) { _, i ->
                val w = wid[i]
                if (w.archiwum && w.start < teraz) { pokazNapis("Archiwum: " + w.tytul); grajArchiwum(ch.id, w); naArchiwum(w) }
                else pokazNapis("Ta audycja nie ma archiwum")
            }.setNegativeButton("Zamknij", null).show().also { Jezyk.okno(it) }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> if (!intent.getBooleanExtra("vod", false)) { zmien(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> if (!intent.getBooleanExtra("vod", false)) { zmien(1); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Okienko (obraz w obrazie): po wyjsciu do ekranu glownego telefonu TV gra dalej w malym okienku. */
    private fun wejdzWOkienko(reczne: Boolean = false): Boolean {
        if (!reczne && gracz?.isPlaying != true) return false
        if (!reczne && !Ust.prefs(this).getBoolean("okienko", true)) return false
        try {
            val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9))
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(true).setSeamlessResizeEnabled(true)
            return enterPictureInPictureMode(b.build())
        } catch (e: Exception) { return false }
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); wejdzWOkienko() }

    /** Mini-odtwarzacz: TV gra dalej w malym pasku na dole aplikacji (nad zakladkami). */
    private fun doMini() {
        if (intent.getBooleanExtra("vod", false)) {
            // Film / odcinek do mini-okna: zapamietujemy adres i miejsce, film gra dalej w kwadracie.
            val u = intent.getStringExtra("url") ?: ""
            gracz?.let { g -> if (g.duration > 0) { Ust.zapiszPozycje(this, u, g.currentPosition); Ust.zapiszDlugosc(this, u, g.duration) } }
            Ust.prefs(this).edit().putString("mini", listOf("vod", intent.getStringExtra("nazwa") ?: "", "", "", u).joinToString("\u0001")).apply()
        } else
        biezacy?.let { Ust.prefs(this).edit().putString("mini", listOf(it.id, it.nazwa, it.logo ?: "", it.kat, "", WspolnyGracz.url ?: "").joinToString("\u0001")).apply() }
        WspolnyGracz.wlasciciel = "mini"
        // Mini-okno zyje na ekranie glownym aplikacji - film z opisu tez tam trafia.
        startActivity(Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        finish(); overridePendingTransition(0, android.R.anim.fade_out)
    }

    /** Nowy kanal / film z listy, gdy odtwarzacz juz gra (np. w okienku) - zaczynamy od nowa z nowymi danymi. */
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); recreate() }

    /** Wstecz przy TV: obraz zmniejsza sie do okienka, a pod nim wraca aplikacja (lista kanalow), jak mini-odtwarzacz. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Jedno Wstecz z pelnego ekranu (gdziekolwiek): od razu male okno na wierzchu aplikacji, obraz gra dalej.
        val koniecFilmu = intent.getBooleanExtra("vod", false) && gracz?.let { it.duration > 0 && it.currentPosition >= it.duration - 90_000 } == true
        if (!koniecFilmu) { doMini(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onPictureInPictureModeChanged(w: Boolean, c: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(w, c)
        widok.useController = !w
        if (w) {
            ramka?.layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
            panel?.visibility = android.view.View.GONE; listaTv?.visibility = android.view.View.GONE; przewodnik?.visibility = android.view.View.GONE
            napis.visibility = android.view.View.GONE
        } else uloz(resources.configuration.orientation)
    }

    override fun onDestroy() { wylacznik?.cancel(); if (isFinishing) WspolnyGracz.zwolnij("duzy"); super.onDestroy() }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) return
        gracz?.let { g ->
            if (intent.getBooleanExtra("vod", false) && g.duration > 0) {
                val koniec = g.currentPosition > g.duration - 90_000
                Ust.zapiszPozycje(this, intent.getStringExtra("url") ?: "", if (koniec) 0 else g.currentPosition)
                Ust.zapiszDlugosc(this, intent.getStringExtra("url") ?: "", g.duration)
            }
        }
        sluchacz?.let { gracz?.removeListener(it) }; sluchacz = null
        widok.player = null; gracz = null
        // Do mini-okna obraz gra dalej (wlasciciel = "mini"); w innym przypadku zatrzymujemy.
        WspolnyGracz.zwolnij("duzy")
    }
}
