package uk.okok.studiokanalow

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityMainBinding
import uk.okok.studiokanalow.model.Channel

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var tileAdapter: TileAdapter
    private var demo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        demo = intent.getBooleanExtra("demo", false)

        channelAdapter = ChannelAdapter { ch -> openPlayer(ch) }
        tileAdapter = TileAdapter { ch ->
            if (b.bottomNav.selectedItemId == R.id.tab_filmy) openDetails(ch) else openPlayer(ch)
        }

        zastosujWyglad()

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_kanaly -> { showKanaly(); true }
                R.id.tab_filmy -> { showFilmy(); true }
                R.id.tab_seriale -> { showSeriale(); true }
                R.id.tab_radio -> { showRadio(); true }
                R.id.tab_ustawienia -> { showUstawienia(); true }
                else -> false
            }
        }
        b.bottomNav.selectedItemId = R.id.tab_kanaly
    }

    // ----- zakladki -----

    private fun showKanaly() {
        b.tvTitle.text = getString(R.string.tab_kanaly)
        b.settingsPanel.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = channelAdapter
        if (demo) {
            channelAdapter.submit(Demo.channels)
            b.tvCount.text = "${Demo.channels.size}"
            hideBusy()
        } else {
            load { Api.liveStreams(Session.server(this), Session.user(this), Session.pass(this), null) }
                .also { /* handled in load */ }
        }
    }

    private fun showFilmy() {
        b.tvTitle.text = getString(R.string.tab_filmy)
        b.settingsPanel.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
        b.recycler.layoutManager = GridLayoutManager(this, 3)
        b.recycler.adapter = tileAdapter
        if (demo) {
            tileAdapter.submit(Demo.films)
            b.tvCount.text = "${Demo.films.size}"
            hideBusy()
        } else {
            loadTiles { Api.vodStreams(Session.server(this), Session.user(this), Session.pass(this), null) }
        }
    }

    private fun showSeriale() {
        b.tvTitle.text = getString(R.string.tab_seriale)
        b.settingsPanel.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
        b.recycler.layoutManager = GridLayoutManager(this, 3)
        b.recycler.adapter = tileAdapter
        if (demo) {
            tileAdapter.submit(Demo.seriale); b.tvCount.text = "${Demo.seriale.size}"; hideBusy()
        } else {
            tileAdapter.submit(emptyList()); b.tvCount.text = ""; info(getString(R.string.empty_demo))
        }
    }

    private fun showRadio() {
        b.tvTitle.text = getString(R.string.tab_radio)
        b.settingsPanel.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = channelAdapter
        if (demo) {
            channelAdapter.submit(Demo.radio); b.tvCount.text = "${Demo.radio.size}"; hideBusy()
        } else {
            channelAdapter.submit(emptyList()); b.tvCount.text = ""; info(getString(R.string.empty_demo))
        }
    }

    private var pinOk = false

    private fun showUstawienia() {
        b.tvTitle.text = getString(R.string.tab_ustawienia)
        b.recycler.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.tvInfo.visibility = View.GONE
        b.tvCount.text = ""
        val pin = Session.opt(this, "pin")
        if (pin.isNotEmpty() && !pinOk) {
            b.settingsPanel.visibility = View.GONE
            pytajOPin(pin) { pinOk = true; showUstawienia() }
            return
        }
        b.settingsPanel.visibility = View.VISIBLE
        budujUstawienia()
    }

    private fun tak(v: Boolean) = getString(if (v) R.string.yes else R.string.no)

    private fun budujUstawienia() {
        val l = b.settingsList
        l.removeAllViews()
        fun sekcja(t: String) = l.addView(TextView(this).apply {
            text = t; setTextColor(getColor(R.color.muted)); textSize = 12f
            setPadding(8, 28, 8, 8)
        })
        fun wiersz(t: String, sub: String? = null, akcja: () -> Unit) = l.addView(TextView(this).apply {
            text = if (sub.isNullOrEmpty()) t else "$t
$sub"
            setTextColor(getColor(R.color.tx)); textSize = 16f
            setBackgroundResource(R.drawable.bg_input)
            setPadding(36, 30, 36, 30)
            val lp = android.widget.LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 8
            layoutParams = lp
            setOnClickListener { akcja() }
        })
        val vn = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }

        sekcja("KONTO I TREŚCI")
        wiersz("Konto i serwis", if (demo) "Tryb testowy (demo)" else Api.HOME) { dialog("Konto i serwis",
            if (demo) "Tryb testowy — bez konta." else "Serwer: ${Api.HOME}
Login: ${Session.user(this)}

Konto i listy przypisujesz w panelu (edytor).") }
        wiersz("Źródła treści", "Zarządzane w panelu") { dialog("Źródła treści", "Źródła (listy, dostawcy) ustawiasz w panelu edytora. Aplikacja pobiera je sama.") }
        wiersz("Zarządzaj grupami", "Zarządzane w panelu") { dialog("Zarządzaj grupami", "Kolejność i nazwy grup ustawiasz w edytorze — aplikacja pokazuje je 1 do 1.") }
        wiersz("Dane EPG", "Wkrótce") { dialog("Dane EPG", "Program telewizyjny pojawi się w jednej z następnych wersji (źródło wybierasz w edytorze).") }
        wiersz("Wyczyść historię", "Ostatnio oglądany kanał") { Session.clearHistory(this); dialog("Historia", "Wyczyszczono.") }

        sekcja("APLIKACJA")
        wiersz(getString(R.string.lang_button), null) { pokazWyborJezyka() }
        wiersz(getString(R.string.skin_button), null) { pokazWyborWygladu() }
        wiersz("Dźwięk i napisy", "Język: " + Session.opt(this, "audio_lang").ifEmpty { "auto" } +
            ", napisy: " + tak(Session.opt(this, "subs") == "1")) { pokazDzwiek() }
        wiersz("Opcje odtwarzacza", "Start: " + tak(Session.autostart(this)) + ", wznawianie: " + tak(Session.resume(this)) +
            ", powiększenie: " + tak(Session.opt(this, "zoom") == "1")) { pokazOpcjeOdtwarzacza() }
        wiersz("Opcje rodzicielskie", if (Session.opt(this, "pin").isEmpty()) "PIN wyłączony" else "PIN włączony") { pokazPin() }

        sekcja("DANE")
        wiersz("Backup i przywracanie", "Wkrótce — ustawienia na serwerze") { dialog("Backup", "Kopia ustawień na serwerze pojawi się po dowiązaniu konta kodem.") }
        wiersz("Zresetuj preferencje użytkownika", null) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Zresetować preferencje?")
                .setPositiveButton("Resetuj") { _, _ -> Session.clearOpts(this); pinOk = false; recreate() }
                .setNegativeButton("Anuluj", null).show()
        }

        sekcja("INFORMACJE")
        wiersz("Informacje", "Wersja $vn") { dialog("OK Player", "Wersja: $vn
Kod: ${Device.code(this)}
MAC: ${Device.mac(this)}") }
        wiersz(getString(R.string.btn_logout), null) {
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
                else { b.bottomNav.selectedItemId = R.id.tab_kanaly }
            }
            .setNegativeButton("Anuluj") { _, _ -> b.bottomNav.selectedItemId = R.id.tab_kanaly }
            .show()
    }

    // ----- ladowanie -----

    private fun load(block: suspend () -> List<Channel>) {
        showBusy()
        lifecycleScope.launch {
            try {
                val list = block()
                hideBusy()
                channelAdapter.submit(list)
                b.tvCount.text = "${list.size}"
                if (list.isEmpty()) info("Pusto")
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
    }

    private fun loadTiles(block: suspend () -> List<Channel>) {
        showBusy()
        lifecycleScope.launch {
            try {
                val list = block()
                hideBusy()
                tileAdapter.submit(list)
                b.tvCount.text = "${list.size}"
                if (list.isEmpty()) info("Pusto")
            } catch (e: Exception) {
                hideBusy(); info(getString(R.string.err_net))
            }
        }
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
        val i = getSharedPreferences("wyglad", MODE_PRIVATE).getInt("skin", 0).coerceIn(0, skiny.size - 1)
        val (bg, panel, akcent) = Triple(skiny[i][1] as Int, skiny[i][2] as Int, skiny[i][3] as Int)
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
        i.putExtra("url", ch.directUrl ?: "")
        i.putExtra("demo", demo)
        startActivity(i)
    }

    private fun openPlayer(ch: Channel) {
        val url = ch.directUrl ?: Api.liveStreamUrl(
            Session.server(this), Session.user(this), Session.pass(this), ch.streamId
        )
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("url", url)
        i.putExtra("name", ch.name)
        i.putExtra("demo", demo)
        startActivity(i)
    }
}
