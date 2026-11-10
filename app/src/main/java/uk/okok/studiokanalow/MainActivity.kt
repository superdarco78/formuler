package uk.okok.studiokanalow

import android.content.Intent
import android.os.Bundle
import android.view.View
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
        tileAdapter = TileAdapter { ch -> openPlayer(ch) }

        b.btnLogout.setOnClickListener {
            Session.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        b.btnLanguage.setOnClickListener { pokazWyborJezyka() }
        b.btnSkin.setOnClickListener { pokazWyborWygladu() }
        odswiezOpcjeStartu()
        b.btnAutostart.setOnClickListener { Session.setAutostart(this, !Session.autostart(this)); odswiezOpcjeStartu() }
        b.btnResume.setOnClickListener { Session.setResume(this, !Session.resume(this)); odswiezOpcjeStartu() }
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

    private fun odswiezOpcjeStartu() {
        val t = getString(R.string.yes); val n = getString(R.string.no)
        b.btnAutostart.text = getString(R.string.opt_autostart) + ": " + (if (Session.autostart(this)) t else n)
        b.btnResume.text = getString(R.string.opt_resume) + ": " + (if (Session.resume(this)) t else n)
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

    private fun showUstawienia() {
        b.tvTitle.text = getString(R.string.tab_ustawienia)
        b.recycler.visibility = View.GONE
        b.progress.visibility = View.GONE
        b.tvInfo.visibility = View.GONE
        b.tvCount.text = ""
        b.settingsPanel.visibility = View.VISIBLE
        val vn = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }
        b.tvVersion.text = "${getString(R.string.sett_version)}: $vn" +
                if (demo) "\n\nTryb testowy (demo)" else ""
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
