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

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_kanaly -> { showKanaly(); true }
                R.id.tab_filmy -> { showFilmy(); true }
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
    }

    private fun info(text: String) {
        b.tvInfo.text = text
        b.tvInfo.visibility = View.VISIBLE
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
        startActivity(i)
    }
}
