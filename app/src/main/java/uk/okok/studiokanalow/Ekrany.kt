package uk.okok.studiokanalow

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uk.okok.studiokanalow.model.Category
import uk.okok.studiokanalow.model.Channel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Filtry grup: ukryte, przypiete i kategorie dla doroslych. */
object Filtr {
    private val dorosle = Regex("(?i)xxx|adult|porn|18\\+|\\+18|dorosł|erotyk")
    fun ukryta(ctx: Context, rodzaj: String, c: Category): Boolean =
        c.id in Session.idSet(ctx, "ukryte_$rodzaj") ||
            (Session.optBool(ctx, "adult", true) && dorosle.containsMatchIn(c.name))

    /** Kategorie bez ukrytych, przypiete na poczatku. */
    fun kategorie(ctx: Context, rodzaj: String, cats: List<Category>): List<Category> {
        val przyp = Session.idSet(ctx, "przyp_$rodzaj")
        val ok = cats.filter { !ukryta(ctx, rodzaj, it) }
        return ok.filter { it.id in przyp } + ok.filter { it.id !in przyp }
    }
}

/** Pelnoekranowe ekrany ustawien w stylu MyTVOnline: tworzone w kodzie, bez dodatkowych layoutow. */
class Ekrany(
    private val a: AppCompatActivity,
    private val demo: Boolean,
    private val przeladuj: () -> Unit,
    private val otworzFilm: (Channel) -> Unit,
    private val otworzKanal: (Channel) -> Unit,
    private val otworzSerial: (Channel) -> Unit,
    private val zapiszKopie: () -> Unit,
    private val wczytajKopie: () -> Unit
) {
    private val czarny = 0xFF000000.toInt()
    private val karta = 0xFF262626.toInt()
    private val tekst = 0xFFEDEDED.toInt()
    private val szary = 0xFF9A9A9A.toInt()
    private val akcent = 0xFF4F7CFF.toInt()

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    // ---------- klocki ----------

    private fun ekran(tytul: String, tresc: (LinearLayout, Dialog) -> Unit, dol: ((LinearLayout, Dialog) -> Unit)? = null,
                      prawo: ((LinearLayout, Dialog) -> Unit)? = null): Dialog {
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.window?.setBackgroundDrawable(ColorDrawable(czarny))
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(czarny) }
        val gora = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(14), dp(10))
        }
        gora.addView(TextView(a).apply { text = tytul; setTextColor(Color.WHITE); textSize = 22f }, LinearLayout.LayoutParams(0, -2, 1f))
        prawo?.invoke(gora, d)
        root.addView(gora)
        val lista = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(20)) }
        val sv = ScrollView(a); sv.addView(lista)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        tresc(lista, d)
        if (dol != null) {
            val pas = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(8), dp(30), dp(24)) }
            dol(pas, d)
            root.addView(pas)
        }
        d.setContentView(root)
        d.show()
        return d
    }

    private fun zaokr(kolor: Int, r: Int = 14) = GradientDrawable().apply { setColor(kolor); cornerRadius = dp(r).toFloat() }

    private fun przycisk(t: String, jasny: Boolean = true, aktywny: Boolean = true, klik: () -> Unit): TextView =
        TextView(a).apply {
            text = t; gravity = Gravity.CENTER; textSize = 16f
            setTextColor(if (jasny) 0xFF111111.toInt() else Color.WHITE)
            background = zaokr(if (!aktywny) 0xFF555555.toInt() else if (jasny) 0xFFE6E6E6.toInt() else karta, 30)
            alpha = if (aktywny) 1f else 0.6f
            setPadding(0, dp(17), 0, dp(17))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) }
            setOnClickListener { if (aktywny) klik() }
        }

    private fun wiersz(kont: LinearLayout, tytul: String, podtytul: String? = null, strzalka: Boolean = true, klik: () -> Unit) {
        val w = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = zaokr(karta, 12)
            setPadding(dp(18), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(2) }
            setOnClickListener { klik() }
        }
        val kol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        kol.addView(TextView(a).apply { text = tytul; setTextColor(tekst); textSize = 16f })
        if (!podtytul.isNullOrEmpty()) kol.addView(TextView(a).apply { text = podtytul; setTextColor(szary); textSize = 13f })
        w.addView(kol, LinearLayout.LayoutParams(0, -2, 1f))
        if (strzalka) w.addView(TextView(a).apply { text = "›"; setTextColor(szary); textSize = 22f })
        kont.addView(w)
    }

    private fun przelacznik(kont: LinearLayout, tytul: String, wl: Boolean, zmiana: (Boolean) -> Unit) {
        val w = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = zaokr(karta, 12)
            setPadding(dp(18), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(2) }
        }
        w.addView(TextView(a).apply { text = tytul; setTextColor(tekst); textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
        w.addView(SwitchCompat(a).apply { isChecked = wl; setOnCheckedChangeListener { _, v -> zmiana(v) } })
        kont.addView(w)
    }

    private fun pole(kont: LinearLayout, podpowiedz: String, wartosc: String = "", haslo: Boolean = false): TextInputLayout {
        val til = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = podpowiedz
        val et = TextInputEditText(til.context)
        et.setText(wartosc)
        et.setSingleLine(true)
        et.setTextColor(Color.WHITE)
        if (haslo) et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        til.addView(et)
        til.layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        kont.addView(til)
        return til
    }

    private fun tx(t: TextInputLayout) = t.editText?.text?.toString()?.trim() ?: ""

    private fun arkusz(tytul: String, pozycje: List<Pair<String, () -> Unit>>) {
        val bs = BottomSheetDialog(a)
        val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = gora(karta); setPadding(0, dp(8), 0, dp(14)) }
        v.addView(TextView(a).apply { text = tytul; setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(24), dp(18), dp(24), dp(14)) })
        for ((n, f) in pozycje) v.addView(TextView(a).apply {
            text = n; setTextColor(tekst); textSize = 16f; setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener { bs.dismiss(); f() }
        })
        bs.setContentView(v)
        bs.show()
    }

    private fun gora(kolor: Int) = GradientDrawable().apply {
        setColor(kolor); val r = dp(24).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    private fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
    private fun teraz() = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())

    // ---------- Serwis Premium ----------

    fun premium() {
        ekran("", { l, _ ->
            l.gravity = Gravity.CENTER_VERTICAL
            l.addView(View(a), LinearLayout.LayoutParams(-1, dp(150)))
            val kolo = FrameLayout(a).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFD32F2F.toInt()) } }
            kolo.addView(ImageView(a).apply { setImageResource(R.drawable.ic_crown) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            l.addView(kolo, LinearLayout.LayoutParams(dp(54), dp(54)).also { it.leftMargin = dp(10) })
            l.addView(TextView(a).apply { text = "Konto premium\nAktywowany"; setTextColor(Color.WHITE); textSize = 30f; setPadding(dp(10), dp(18), 0, dp(14)) })
            val tab = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = zaokr(0xFF202020.toInt(), 16) }
            val w1 = tabWiersz(tab, "Status:", demo.let { if (it) "Demo" else "..." })
            val w2 = tabWiersz(tab, "Typ:", "...")
            val w3 = tabWiersz(tab, "Wygaśnięcie:", "...")
            l.addView(tab, LinearLayout.LayoutParams(-1, -2).also { it.leftMargin = dp(6); it.rightMargin = dp(6) })
            if (!demo) a.lifecycleScope.launch {
                val i = Api.accountInfo(Session.server(a), Session.user(a), Session.pass(a))
                if (i != null) { w1.text = i.first; w2.text = i.second; w3.text = i.third }
                else { w1.text = "Brak połączenia"; w2.text = "-"; w3.text = "-" }
            } else { w2.text = "Demo"; w3.text = "-" }
        })
    }

    private fun tabWiersz(tab: LinearLayout, k: String, v: String): TextView {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
        r.addView(TextView(a).apply { text = k; setTextColor(szary); textSize = 17f }, LinearLayout.LayoutParams(0, -2, 1f))
        val t = TextView(a).apply { text = v; setTextColor(Color.WHITE); textSize = 17f }
        r.addView(t)
        tab.addView(r)
        return t
    }

    // ---------- Zrodla tresci ----------

    private fun zrodla(): JSONArray {
        val arr = Session.sources(a)
        if (arr.length() == 0 && Session.isLogged(a) && !demo) {
            arr.put(JSONObject().put("id", "dom").put("name", "Konto").put("type", "XCAPI")
                .put("server", Session.server(a)).put("user", Session.user(a)).put("pass", Session.pass(a))
                .put("enabled", true).put("updated", teraz()))
            Session.saveSources(a, arr)
        }
        return arr
    }

    private fun aktywne(o: JSONObject) = o.optString("server") == Session.server(a) && o.optString("user") == Session.user(a) &&
        o.optString("user").isNotEmpty()

    fun zrodlaEkran() {
        var odswiez: () -> Unit = {}
        var lista: LinearLayout? = null
        ekran("Źródła treści", { l, _ -> lista = l }, { pas, _ ->
            pas.addView(przycisk("Odśwież") { przeladuj(); toast("Odświeżono"); odswiez() })
        }, { gora, _ ->
            gora.addView(TextView(a).apply { text = "+"; setTextColor(Color.WHITE); textSize = 32f; setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { menuDodaj { odswiez() } } })
        })
        odswiez = {
            val l = lista!!
            l.removeAllViews()
            val arr = zrodla()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val on = o.optBoolean("enabled", true)
                val akt = aktywne(o)
                val w = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = zaokr(karta, 12); setPadding(dp(18), dp(18), dp(14), dp(18))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
                    setOnClickListener { menuZrodla(i) { odswiez() } }
                }
                w.addView(TextView(a).apply {
                    text = if (!on) "⊗" else if (akt) "✓" else "?"
                    setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(40), -2))
                val kol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                kol.addView(TextView(a).apply { text = o.optString("name"); setTextColor(Color.WHITE); textSize = 21f })
                val typ = o.optString("type")
                kol.addView(TextView(a).apply { text = if (typ == "M3U") "M3U (odtwarzanie w następnej wersji)" else typ; setTextColor(szary); textSize = 15f })
                kol.addView(TextView(a).apply { text = "Ostatnia aktualizacja: " + o.optString("updated", "-"); setTextColor(szary); textSize = 15f })
                kol.addView(TextView(a).apply { text = "Wygasa: " + o.optString("expires", "-"); setTextColor(szary); textSize = 15f })
                w.addView(kol, LinearLayout.LayoutParams(0, -2, 1f))
                w.addView(TextView(a).apply { text = "›"; setTextColor(szary); textSize = 24f })
                l.addView(w)
                if (akt && !demo) a.lifecycleScope.launch {
                    val inf = Api.accountInfo(o.optString("server"), o.optString("user"), o.optString("pass"))
                    if (inf != null && inf.third != o.optString("expires")) {
                        o.put("expires", inf.third); Session.saveSources(a, arr); odswiez()
                    }
                }
            }
            if (arr.length() == 0) l.addView(TextView(a).apply { text = "Brak źródeł. Dotknij +, aby dodać."; setTextColor(szary); setPadding(dp(8), dp(30), dp(8), 0) })
        }
        odswiez()
    }

    private fun menuDodaj(po: () -> Unit) = arkusz("Dodaj źródła", listOf(
        "＋  Dodaj Portal (MAC & Xtream)" to { formularzPortal(null, po) },
        "＋  Dodaj listę odtwarzania m3u" to { formularzM3u(po) },
        "Importuj dane z urządzenia FORMULER" to { toast("Import z Formulera pojawi się z kodem parowania") },
        "Wypróbuj playlistę demo" to { a.startActivity(android.content.Intent(a, MainActivity::class.java).putExtra("demo", true)) }
    ))

    private fun menuZrodla(i: Int, po: () -> Unit) {
        val arr = zrodla()
        val o = arr.getJSONObject(i)
        val on = o.optBoolean("enabled", true)
        arkusz(o.optString("name"), listOf(
            "Użyj jako aktywne" to {
                if (o.optString("user").isNotEmpty()) {
                    Session.save(a, o.optString("server"), o.optString("user"), o.optString("pass")); przeladuj(); po()
                } else toast("To źródło nie ma loginu (MAC) — obsługa w następnej wersji")
            },
            "Odśwież" to { o.put("updated", teraz()); Session.saveSources(a, arr); if (aktywne(o)) przeladuj(); po() },
            "Zaktualizuj EPG" to { toast("Dane EPG pojawią się w następnej wersji") },
            "Wyczyść EPG" to { toast("Brak pobranych danych EPG") },
            (if (on) "Wyłącz" else "Włącz") to { o.put("enabled", !on); Session.saveSources(a, arr); po() },
            "Edytuj" to { formularzPortal(i, po) },
            "Usuń" to {
                AlertDialog.Builder(a).setTitle("Usunąć źródło „${o.optString("name")}”?")
                    .setPositiveButton("Usuń") { _, _ ->
                        val n = JSONArray(); for (k in 0 until arr.length()) if (k != i) n.put(arr.getJSONObject(k))
                        Session.saveSources(a, n); po()
                    }.setNegativeButton("Anuluj", null).show()
            },
            "Informacje" to {
                AlertDialog.Builder(a).setTitle(o.optString("name")).setMessage(
                    "Typ: ${o.optString("type")}\nSerwer: ${o.optString("server", o.optString("url", "-"))}\n" +
                        "Login: ${o.optString("user", "-")}\nPrzesunięcie EPG: ${o.optString("epg", "Brak")}\nWygasa: ${o.optString("expires", "-")}")
                    .setPositiveButton("OK", null).show()
            }
        ))
    }

    private val przesuniecia = (-12..12).map { if (it == 0) "Brak" else (if (it > 0) "+$it h" else "$it h") }

    private fun wyborEpg(kont: LinearLayout, start: String): () -> String {
        var w = start.ifEmpty { "Brak" }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setStroke(dp(1), 0xFF6A6A6A.toInt()); cornerRadius = dp(12).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10)) }
        box.addView(TextView(a).apply { text = "Przesunięcie EPG"; setTextColor(szary); textSize = 12f })
        val v = TextView(a).apply { text = w; setTextColor(Color.WHITE); textSize = 17f }
        box.addView(v)
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        box.setOnClickListener {
            AlertDialog.Builder(a).setTitle("Przesunięcie EPG").setItems(przesuniecia.toTypedArray()) { _, i -> w = przesuniecia[i]; v.text = w }.show()
        }
        kont.addView(box)
        return { w }
    }

    private fun skrot(s: String, n: Int) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(n)

    private fun formularzPortal(edytuj: Int?, po: () -> Unit) {
        val arr = zrodla()
        val stary = if (edytuj != null) arr.getJSONObject(edytuj) else null
        val zaaw = JSONObject(stary?.optJSONObject("zaaw")?.toString() ?: "{}")
        ekran(if (stary == null) "Dodaj Portal (MAC & Xtream)" else "Edytuj źródło", { l, _ ->
            val nazwa = pole(l, "Pseudonim portalu", stary?.optString("name") ?: "")
            val url = pole(l, "Adres URL portalu", stary?.optString("server") ?: "")
            var login = stary?.optString("user")?.isNotEmpty() ?: false
            val polaLogin = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; visibility = if (login) View.VISIBLE else View.GONE }
            val usr = pole(polaLogin, "Login", stary?.optString("user") ?: "")
            val pas = pole(polaLogin, "Hasło", stary?.optString("pass") ?: "", true)
            przelacznik(l, "Wymagany login?", login) { login = it; polaLogin.visibility = if (it) View.VISIBLE else View.GONE }
            l.addView(polaLogin)
            val epg = wyborEpg(l, stary?.optString("epg") ?: "Brak")
            val id = pole(l, "ID", Device.mac(a)); id.editText?.isEnabled = false
            wiersz(l, "Opcje zaawansowane") { zaawansowane(zaaw) }
            nastepny = {
                val n = nazwa.let { tx(it) }; val u = tx(url)
                if (n.isEmpty() || u.isEmpty()) toast("Podaj pseudonim i adres")
                else {
                    val o = stary ?: JSONObject().put("id", System.currentTimeMillis().toString())
                    o.put("name", n).put("server", u).put("epg", epg()).put("zaaw", zaaw).put("enabled", true)
                        .put("updated", teraz()).put("mac", Device.mac(a))
                    if (login) {
                        val lg = tx(usr); val ps = tx(pas)
                        if (lg.isEmpty() || ps.isEmpty()) toast("Podaj login i hasło")
                        else a.lifecycleScope.launch {
                            val ok = try { Api.login(u, lg, ps) } catch (e: Exception) { false }
                            if (ok) { o.put("type", "XCAPI").put("user", lg).put("pass", ps); zapisz(arr, o, stary == null); po()
                                nastepnyZamknij?.invoke() }
                            else toast("Nie udało się połączyć — sprawdź adres i dane")
                        }
                    } else {
                        o.put("type", "Portal").remove("user"); o.remove("pass")
                        zapisz(arr, o, stary == null); po(); nastepnyZamknij?.invoke()
                    }
                }
            }
        }, { pas, d ->
            nastepnyZamknij = { d.dismiss() }
            pas.addView(przycisk("Połącz") { nastepny?.invoke() })
        })
    }

    private var nastepny: (() -> Unit)? = null
    private var nastepnyZamknij: (() -> Unit)? = null

    private fun zapisz(arr: JSONArray, o: JSONObject, nowy: Boolean) {
        if (nowy) arr.put(o)
        Session.saveSources(a, arr)
        toast("Zapisano źródło")
        if (o.optString("user").isNotEmpty() && nowy) { Session.save(a, o.optString("server"), o.optString("user"), o.optString("pass")); przeladuj() }
    }

    private fun zaawansowane(z: JSONObject) {
        val mac = Device.mac(a)
        val dom = mapOf(
            "mac" to mac, "serial" to skrot(mac, 13).uppercase(), "id1" to skrot("a$mac", 40), "id2" to skrot("b$mac", 40),
            "uap" to "Domyślny", "uaa" to "Mozilla/5.0 (Linux; Android) OK-Player")
        ekran("Opcje zaawansowane", { l, d ->
            val m = pole(l, "MAC ID", z.optString("mac", dom["mac"]!!))
            val s = pole(l, "Numer seryjny", z.optString("serial", dom["serial"]!!))
            val i1 = pole(l, "Identyfikator urządzenia 1", z.optString("id1", dom["id1"]!!))
            val i2 = pole(l, "Identyfikator urządzenia 2", z.optString("id2", dom["id2"]!!))
            val up = pole(l, "Agent użytkownika w odtwarzaczu", z.optString("uap", dom["uap"]!!))
            val ua = pole(l, "Agent użytkownika API", z.optString("uaa", dom["uaa"]!!))
            nastepnyZaaw = {
                z.put("mac", tx(m)).put("serial", tx(s)).put("id1", tx(i1)).put("id2", tx(i2)).put("uap", tx(up)).put("uaa", tx(ua))
                d.dismiss()
            }
            resetZaaw = { for (k in dom.keys) z.remove(k); d.dismiss() }
        }, { pas, _ ->
            pas.addView(przycisk("Aktualizuj") { nastepnyZaaw?.invoke() })
            pas.addView(przycisk("Resetuj") { resetZaaw?.invoke() })
        })
    }

    private var nastepnyZaaw: (() -> Unit)? = null
    private var resetZaaw: (() -> Unit)? = null

    private fun formularzM3u(po: () -> Unit) {
        val arr = zrodla()
        ekran("Dodaj listę odtwarzania m3u", { l, d ->
            val nazwa = pole(l, "Pseudonim listy odtwarzania")
            val url = pole(l, "Adres URL listy odtwarzania")
            val epg = wyborEpg(l, "Brak")
            val vod = pole(l, "Adres URL VOD (opcjonalnie)")
            val x1 = pole(l, "Adres URL XMLTV EPG (opcjonalnie)")
            val dodatkowe = ArrayList<TextInputLayout>()
            val kont = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            l.addView(kont)
            wiersz(l, "＋  Dodaj adres URL EPG XMLTV", null, false) {
                val t = pole(kont, "Adres URL XMLTV EPG (opcjonalnie)"); dodatkowe.add(t)
            }
            nastepny = {
                val n = tx(nazwa); val u = tx(url)
                if (n.isEmpty() || u.isEmpty()) toast("Podaj pseudonim i adres listy")
                else {
                    val epgi = JSONArray(); for (t in listOf(x1) + dodatkowe) if (tx(t).isNotEmpty()) epgi.put(tx(t))
                    arr.put(JSONObject().put("id", System.currentTimeMillis().toString()).put("name", n).put("type", "M3U")
                        .put("url", u).put("vod", tx(vod)).put("epgUrls", epgi).put("epg", epg()).put("enabled", true).put("updated", teraz()))
                    Session.saveSources(a, arr); toast("Zapisano listę"); po(); d.dismiss()
                }
            }
        }, { pas, _ -> pas.addView(przycisk("Połącz") { nastepny?.invoke() }) })
    }

    // ---------- Zarzadzaj grupami ----------

    fun grupy() {
        var rodzaj = "live"
        var tresc: LinearLayout? = null
        var zakl: LinearLayout? = null
        lateinit var wczytaj: () -> Unit
        ekran("Zarządzaj grupami", { l, _ -> tresc = l; zakl = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            l.addView(zakl, 0) }, null)
        val nazwy = listOf("live" to "Telewizja na żywo", "vod" to "VOD", "ser" to "Seriale")
        fun rysujZakladki() {
            zakl!!.removeAllViews()
            for ((k, n) in nazwy) zakl!!.addView(TextView(a).apply {
                text = n; textSize = 17f; setPadding(dp(14), dp(10), dp(14), dp(12))
                setTextColor(if (k == rodzaj) Color.WHITE else szary)
                if (k == rodzaj) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { rodzaj = k; rysujZakladki(); wczytaj() }
            })
        }
        wczytaj = {
            val l = tresc!!
            for (i in l.childCount - 1 downTo 1) l.removeViewAt(i)
            val r = rodzaj
            a.lifecycleScope.launch {
                val cats = try {
                    if (demo) listOf(Category("1", "Demo — Ogólne"), Category("2", "Demo — Filmy"))
                    else when (r) {
                        "live" -> Api.liveCategories(Session.server(a), Session.user(a), Session.pass(a))
                        "vod" -> Api.vodCategories(Session.server(a), Session.user(a), Session.pass(a))
                        else -> Api.seriesCategories(Session.server(a), Session.user(a), Session.pass(a))
                    }
                } catch (e: Exception) { null }
                if (r != rodzaj) return@launch
                if (cats == null) { l.addView(TextView(a).apply { text = "Brak połączenia"; setTextColor(szary); setPadding(dp(8), dp(20), 0, 0) }); return@launch }
                val naglowek = TextView(a).apply { text = "⌃  Dodaj / usuń grupy"; setTextColor(Color.WHITE); textSize = 17f
                    background = zaokr(0xFF262626.toInt(), 6); setPadding(dp(16), dp(14), dp(16), dp(14)) }
                l.addView(naglowek)
                for (c in cats) {
                    val w = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(6), dp(14)) }
                    w.addView(TextView(a).apply { text = c.name; setTextColor(tekst); textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
                    fun ikona(res: Int, wl: Boolean, klik: (ImageView) -> Unit) = ImageView(a).also {
                        it.setImageResource(res); it.setColorFilter(if (wl) akcent else 0xFFD0D0D0.toInt()); it.setPadding(dp(12), dp(12), dp(12), dp(12))
                        it.setOnClickListener { _ -> klik(it) }
                        w.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)))
                    }
                    val przyp = Session.idSet(a, "przyp_$r"); val ukr = Session.idSet(a, "ukryte_$r")
                    ikona(R.drawable.ic_pin, c.id in przyp) { iv ->
                        val s = Session.idSet(a, "przyp_$r"); if (!s.add(c.id)) s.remove(c.id)
                        Session.setIdSet(a, "przyp_$r", s); iv.setColorFilter(if (c.id in s) akcent else 0xFFD0D0D0.toInt()); przeladuj()
                    }
                    ikona(if (c.id in ukr) R.drawable.ic_eye_off else R.drawable.ic_eye, false) { iv ->
                        val s = Session.idSet(a, "ukryte_$r"); if (!s.add(c.id)) s.remove(c.id)
                        Session.setIdSet(a, "ukryte_$r", s); iv.setImageResource(if (c.id in s) R.drawable.ic_eye_off else R.drawable.ic_eye); przeladuj()
                    }
                    l.addView(w)
                }
            }
        }
        rysujZakladki(); wczytaj()
    }

    // ---------- Opcje rodzicielskie ----------

    fun rodzicielskie(onPin: () -> Unit) {
        ekran("Opcje rodzicielskie", { l, _ ->
            przelacznik(l, "Zablokuj kategorie dla dorosłych", Session.optBool(a, "adult", true)) { Session.setOptBool(a, "adult", it); przeladuj() }
            przelacznik(l, "Wyczyść historię oglądania przy wyjściu", Session.optBool(a, "clear_exit", false)) { Session.setOptBool(a, "clear_exit", it) }
            przelacznik(l, "Blokada ustawień", Session.optBool(a, "lock_settings", true)) {
                Session.setOptBool(a, "lock_settings", it)
                if (it && Session.opt(a, "pin").isEmpty()) zmienPin(onPin)
            }
            wiersz(l, "Zmień PIN blokady", null, false) { zmienPin(onPin) }
        })
    }

    fun zmienPin(po: () -> Unit) {
        val et = EditText(a).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "Nowy PIN (4 cyfry)" }
        val b = AlertDialog.Builder(a).setTitle("PIN blokady").setView(et)
            .setPositiveButton("Ustaw") { _, _ ->
                val v = et.text.toString().trim()
                if (v.length == 4) { Session.setOpt(a, "pin", v); po(); toast("PIN ustawiony") } else toast("PIN musi mieć 4 cyfry")
            }.setNegativeButton("Anuluj", null)
        if (Session.opt(a, "pin").isNotEmpty()) b.setNeutralButton("Wyłącz PIN") { _, _ -> Session.setOpt(a, "pin", ""); po() }
        b.show()
    }

    // ---------- Backup ----------

    fun backup() {
        ekran("Backup & Przywróć", { l, _ ->
            l.gravity = Gravity.CENTER_HORIZONTAL
            l.addView(View(a), LinearLayout.LayoutParams(-1, dp(220)))
            l.addView(TextView(a).apply { text = "⇪"; setTextColor(Color.WHITE); textSize = 34f; gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF222222.toInt()) } },
                LinearLayout.LayoutParams(dp(78), dp(78)))
            l.addView(TextView(a).apply { text = "Backup & Przywróć"; setTextColor(Color.WHITE); textSize = 30f; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(10)) })
            l.addView(TextView(a).apply { text = "Wykonaj kopię zapasową swoich danych i przywracaj je łatwo, w dowolnym momencie.\n(Hasła nie trafiają do kopii.)"
                setTextColor(szary); textSize = 16f; gravity = Gravity.CENTER })
        }, { pas, d ->
            pas.addView(przycisk("Kopia zapasowa", false) { zapiszKopie() })
            pas.addView(przycisk("Przywróć", false) { wczytajKopie() })
        })
    }

    // ---------- Informacje ----------

    fun informacje(wersja: String) {
        val bs = BottomSheetDialog(a)
        val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = gora(karta); setPadding(dp(24), dp(24), dp(24), dp(16)) }
        v.addView(ImageView(a).apply { setImageResource(R.drawable.ic_logo_okragle) }, LinearLayout.LayoutParams(dp(110), dp(110)))
        v.addView(TextView(a).apply { text = "© Studio Kanałów. Wszelkie prawa zastrzeżone.\nOK Player jest znakiem Studia Kanałów. Aplikacja nie zawiera żadnych treści — treści dostarcza Twój serwis."
            setTextColor(tekst); textSize = 15f; setPadding(0, dp(16), 0, dp(14)) })
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(14)) }
        r.addView(TextView(a).apply { text = "Wersja aplikacji"; setTextColor(Color.WHITE); textSize = 16f }, LinearLayout.LayoutParams(dp(140), -2))
        r.addView(TextView(a).apply { text = wersja; setTextColor(Color.WHITE); textSize = 16f })
        v.addView(r)
        fun poz(t: String, tresc: String) = v.addView(TextView(a).apply { text = t; setTextColor(tekst); textSize = 16f; setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { AlertDialog.Builder(a).setTitle(t).setMessage(tresc).setPositiveButton("OK", null).show() } })
        poz("Warunki użytkowania (EULA)", "OK Player jest odtwarzaczem. Korzystasz z niego z własnym kontem otrzymanym od dostawcy usługi. Autor nie odpowiada za treści dostarczane przez serwis.")
        poz("Otwórz licencje", "Użyte biblioteki: AndroidX, Material Components, Media3 ExoPlayer, OkHttp, Coil (licencja Apache 2.0).")
        poz("Polityka prywatności", "Aplikacja zapisuje dane logowania wyłącznie na urządzeniu, w postaci zaszyfrowanej. Łączy się tylko z serwerem skonfigurowanym w źródłach. Kopia zapasowa nie zawiera haseł.")
        bs.setContentView(v)
        bs.show()
    }

    // ---------- Wyszukiwarka ----------

    private var liveC: List<Channel>? = null
    private var vodC: List<Channel>? = null
    private var serC: List<Channel>? = null

    fun szukaj() {
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.window?.setBackgroundDrawable(ColorDrawable(czarny))
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(czarny); setPadding(dp(14), dp(18), dp(14), 0) }
        val pole = EditText(a).apply {
            hint = "Szukaj kanałów, filmów i seriali"; setHintTextColor(szary); setTextColor(Color.WHITE); textSize = 18f
            background = zaokr(karta, 28); setPadding(dp(20), dp(14), dp(20), dp(14)); setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(pole, LinearLayout.LayoutParams(-1, -2))
        val lista = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(20)) }
        val sv = ScrollView(a); sv.addView(lista)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(root)

        fun pokaz(q: String) {
            lista.removeAllViews()
            if (q.length < 2) return
            val qq = q.lowercase()
            fun sekcja(t: String, l: List<Channel>?, klik: (Channel) -> Unit) {
                val f = (l ?: emptyList()).filter { it.name.lowercase().contains(qq) }.take(25)
                if (f.isEmpty()) return
                lista.addView(TextView(a).apply { text = t; setTextColor(szary); textSize = 14f; setPadding(dp(6), dp(16), 0, dp(6)) })
                for (c in f) lista.addView(TextView(a).apply {
                    text = c.name; setTextColor(tekst); textSize = 16f; background = zaokr(karta, 10)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(3) }
                    setOnClickListener { d.dismiss(); klik(c) }
                })
            }
            sekcja("KANAŁY", liveC, otworzKanal); sekcja("FILMY", vodC, otworzFilm); sekcja("SERIALE", serC, otworzSerial)
            if (lista.childCount == 0) lista.addView(TextView(a).apply { text = "Brak wyników"; setTextColor(szary); setPadding(dp(6), dp(20), 0, 0) })
        }
        pole.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { pokaz(s?.toString()?.trim() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        d.show()
        a.lifecycleScope.launch {
            try {
                if (demo) { liveC = Demo.channels; vodC = Demo.films; serC = Demo.seriale }
                else {
                    val s = Session.server(a); val u = Session.user(a); val p = Session.pass(a)
                    if (liveC == null) liveC = Api.liveStreams(s, u, p, null)
                    if (vodC == null) vodC = Api.vodStreams(s, u, p, null)
                    if (serC == null) serC = try { Api.seriesList(s, u, p) } catch (e: Exception) { emptyList() }
                }
                pokaz(pole.text.toString().trim())
            } catch (e: Exception) {
                lista.addView(TextView(a).apply { text = "Brak połączenia"; setTextColor(szary); setPadding(dp(6), dp(20), 0, 0) })
            }
        }
    }
}
