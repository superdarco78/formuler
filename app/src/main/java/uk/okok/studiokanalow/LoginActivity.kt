package uk.okok.studiokanalow

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.okok.studiokanalow.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Jesli juz zalogowany - od razu do kanalow.
        if (Session.isLogged(this)) {
            goMain()
            return
        }

        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvCode.text = Device.code(this)
        b.tvMac.text = "MAC: " + Device.mac(this)

        b.btnLogin.setOnClickListener { doLogin() }

        b.btnDemo.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("demo", true)
            startActivity(i)
        }

        // Sprawdz, czy jest nowsza wersja aplikacji (auto-aktualizacja).
        lifecycleScope.launch { Updater.check(this@LoginActivity) }
    }

    private fun doLogin() {
        val server = Api.HOME
        val user = b.etUser.text.toString().trim()
        val pass = b.etPass.text.toString().trim()

        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            b.tvError.text = getString(R.string.err_fill)
            b.tvError.visibility = View.VISIBLE
            return
        }

        b.tvError.visibility = View.GONE
        setBusy(true)

        lifecycleScope.launch {
            try {
                val ok = Api.login(server, user, pass)
                if (ok) {
                    Session.save(this@LoginActivity, server, user, pass)
                    goMain(false)
                } else {
                    setBusy(false)
                    b.tvError.text = getString(R.string.err_login)
                    b.tvError.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                setBusy(false)
                b.tvError.text = getString(R.string.err_net)
                b.tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.btnLogin.isEnabled = !busy
        b.btnLogin.text = getString(if (busy) R.string.logging_in else R.string.btn_login)
    }

    private fun goMain(resume: Boolean = true) {
        startActivity(Intent(this, MainActivity::class.java))
        if (resume && Session.resume(this)) {
            val u = try { Session.lastUrl(this) } catch (e: Exception) { null }
            if (u != null) {
                val i = Intent(this, PlayerActivity::class.java)
                i.putExtra("url", u)
                i.putExtra("name", Session.lastName(this))
                startActivity(i)
            }
        }
        finish()
    }
}
