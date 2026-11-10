package uk.okok.studiokanalow

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityMainBinding
import uk.okok.studiokanalow.model.Channel

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = ChannelAdapter { ch -> openPlayer(ch) }
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = adapter

        b.btnLogout.setOnClickListener {
            Session.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        loadChannels()
    }

    private fun loadChannels() {
        b.progress.visibility = View.VISIBLE
        b.tvInfo.visibility = View.GONE
        val server = Session.server(this)
        val user = Session.user(this)
        val pass = Session.pass(this)

        lifecycleScope.launch {
            try {
                val list = Api.liveStreams(server, user, pass, null)
                b.progress.visibility = View.GONE
                if (list.isEmpty()) {
                    b.tvInfo.text = "Brak kanałów na liście"
                    b.tvInfo.visibility = View.VISIBLE
                } else {
                    adapter.submit(list)
                    b.tvCount.text = "Kanały: ${list.size}"
                }
            } catch (e: Exception) {
                b.progress.visibility = View.GONE
                b.tvInfo.text = getString(R.string.err_net)
                b.tvInfo.visibility = View.VISIBLE
            }
        }
    }

    private fun openPlayer(ch: Channel) {
        val url = Api.liveStreamUrl(
            Session.server(this), Session.user(this), Session.pass(this), ch.streamId
        )
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("url", url)
        i.putExtra("name", ch.name)
        startActivity(i)
    }
}
