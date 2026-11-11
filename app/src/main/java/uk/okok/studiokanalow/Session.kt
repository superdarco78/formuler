package uk.okok.studiokanalow

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import uk.okok.studiokanalow.model.Channel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Bezpieczne przechowywanie danych logowania klienta.
 * Zapisywane zaszyfrowane (EncryptedSharedPreferences) - nie leza otwartym tekstem.
 * W samej aplikacji NIE ma zadnych danych na sztywno; wszystko wpisuje klient przy logowaniu.
 */
object Session {
    private const val FILE = "studio_sesja"
    private const val K_SERVER = "serwer"
    private const val K_USER = "login"
    private const val K_PASS = "haslo"

    // Zaszyfrowane ustawienia tworzone RAZ (wczesniej przy kazdym odczycie - wolny Keystore blokowal aplikacje).
    @Volatile private var cache: SharedPreferences? = null
    private fun prefs(ctx: Context): SharedPreferences = cache ?: synchronized(this) { cache ?: prefsNowe(ctx.applicationContext).also { cache = it } }

    private fun prefsNowe(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(ctx: Context, server: String, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(K_SERVER, server)
            .putString(K_USER, user)
            .putString(K_PASS, pass)
            .apply()
    }

    fun server(ctx: Context): String = prefs(ctx).getString(K_SERVER, "") ?: ""
    fun user(ctx: Context): String = prefs(ctx).getString(K_USER, "") ?: ""
    fun pass(ctx: Context): String = prefs(ctx).getString(K_PASS, "") ?: ""
    fun isLogged(ctx: Context): Boolean = server(ctx).isNotEmpty() && user(ctx).isNotEmpty()

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    // ----- pamiec list na dysku: Start pokazuje sie od razu, a swieze listy pobieraja sie w tle -----
    /** Zapis strumieniowy (JsonWriter) - nie buduje calej listy w pamieci, wiec nie zamraza telefonu. */
    fun zapiszKanaly(ctx: Context, klucz: String, l: List<uk.okok.studiokanalow.model.Channel>) {
        try {
            val tmp = java.io.File(ctx.filesDir, "lista_$klucz.tmp")
            android.util.JsonWriter(java.io.BufferedWriter(java.io.FileWriter(tmp), 65536)).use { w ->
                w.beginArray()
                for (c in l) {
                    w.beginArray().value(c.streamId).value(c.name).value(c.logo ?: "").value(c.categoryId ?: "").value(c.num.toLong())
                        .value(c.directUrl ?: "").value(c.rating).value(c.opis).value(c.czas).endArray()
                }
                w.endArray()
            }
            tmp.renameTo(java.io.File(ctx.filesDir, "lista_$klucz.json"))
        } catch (e: Exception) { }
    }
    fun wczytajKanaly(ctx: Context, klucz: String): List<uk.okok.studiokanalow.model.Channel>? = try {
        val f = java.io.File(ctx.filesDir, "lista_$klucz.json")
        if (!f.exists()) null else {
            val out = ArrayList<uk.okok.studiokanalow.model.Channel>()
            android.util.JsonReader(java.io.BufferedReader(java.io.FileReader(f), 65536)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val id = r.nextString(); val n = r.nextString(); val lg = r.nextString(); val cat = r.nextString()
                    val num = r.nextInt(); val u = r.nextString(); val rt = r.nextDouble(); val op = r.nextString(); val cz = r.nextString()
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    out.add(uk.okok.studiokanalow.model.Channel(id, n, lg.ifEmpty { null }, cat.ifEmpty { null }, num, u.ifEmpty { null }, rt, op, cz))
                }
                r.endArray()
            }
            out
        }
    } catch (e: Exception) { null }
    fun zapiszKategorie(ctx: Context, klucz: String, l: List<uk.okok.studiokanalow.model.Category>) {
        try {
            val a = org.json.JSONArray(); for (c in l) a.put(org.json.JSONArray().put(c.id).put(c.name))
            java.io.File(ctx.filesDir, "kat_$klucz.json").writeText(a.toString())
        } catch (e: Exception) { }
    }
    fun wczytajKategorie(ctx: Context, klucz: String): List<uk.okok.studiokanalow.model.Category>? = try {
        val f = java.io.File(ctx.filesDir, "kat_$klucz.json")
        if (!f.exists()) null else { val a = org.json.JSONArray(f.readText()); (0 until a.length()).map { i -> uk.okok.studiokanalow.model.Category(a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1)) } }
    } catch (e: Exception) { null }

    // ----- kontynuuj ogladanie filmow/odcinkow (pozycja jak w MyTvOnline) -----
    fun zapiszPozycje(ctx: Context, url: String, name: String, logo: String?, poz: Long, dl: Long) {
        val arr = try { org.json.JSONArray(prefs(ctx).getString("kontynuuj", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val nowa = org.json.JSONArray()
        val koniec = dl > 0 && poz > dl - 90_000
        if (!koniec && poz > 30_000) nowa.put(org.json.JSONObject().put("url", url).put("name", name).put("logo", logo ?: "").put("poz", poz).put("dl", dl))
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("url") != url && nowa.length() < 20) nowa.put(o) }
        prefs(ctx).edit().putString("kontynuuj", nowa.toString()).apply()
    }
    fun pozycja(ctx: Context, url: String): Long {
        val arr = try { org.json.JSONArray(prefs(ctx).getString("kontynuuj", "[]")) } catch (e: Exception) { return 0 }
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optString("url") == url) return o.optLong("poz") }
        return 0
    }
    /** Lista do rzedu "Kontynuuj ogladanie": (url, nazwa, logo, procent). */
    fun kontynuuj(ctx: Context): List<Array<Any>> {
        val arr = try { org.json.JSONArray(prefs(ctx).getString("kontynuuj", "[]")) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).map { i -> val o = arr.getJSONObject(i)
            arrayOf(o.optString("url"), o.optString("name"), o.optString("logo"), if (o.optLong("dl") > 0) (o.optLong("poz") * 100 / o.optLong("dl")).toInt() else 0) }
    }

    // ----- ostatni kanal i opcje startu -----
    fun saveLast(ctx: Context, url: String, name: String, logo: String? = null) {
        val e = prefs(ctx).edit().putString("last_url", url).putString("last_name", name)
        if (logo != null) e.putString("last_logo", logo) else if (name != lastName(ctx)) e.remove("last_logo")
        e.apply()
    }
    fun lastLogo(ctx: Context): String? = prefs(ctx).getString("last_logo", null)?.ifEmpty { null }
    fun lastUrl(ctx: Context): String? = prefs(ctx).getString("last_url", null)
    fun lastName(ctx: Context): String = prefs(ctx).getString("last_name", "") ?: ""

    private fun opts(ctx: Context) = ctx.getSharedPreferences("ustawienia", Context.MODE_PRIVATE)
    fun autostart(ctx: Context) = opts(ctx).getBoolean("autostart", true)
    fun setAutostart(ctx: Context, v: Boolean) = opts(ctx).edit().putBoolean("autostart", v).apply()
    fun resume(ctx: Context): Boolean {
        // 3.59: start zawsze na zakladce Start (jednorazowo wylacza wznawianie ustawione wczesniej domyslnie).
        val o = opts(ctx)
        if (!o.getBoolean("resume_migr_359", false)) o.edit().putBoolean("resume", false).putBoolean("resume_migr_359", true).apply()
        return o.getBoolean("resume", false)
    }
    fun setResume(ctx: Context, v: Boolean) = opts(ctx).edit().putBoolean("resume", v).apply()

    fun opt(ctx: Context, key: String, def: String = ""): String = opts(ctx).getString(key, def) ?: def
    fun setOpt(ctx: Context, key: String, v: String) = opts(ctx).edit().putString(key, v).apply()
    fun clearOpts(ctx: Context) {
        opts(ctx).edit().clear().apply()
        ctx.getSharedPreferences("wyglad", Context.MODE_PRIVATE).edit().clear().apply()
    }
    fun clearHistory(ctx: Context) {
        prefs(ctx).edit().remove("last_url").remove("last_name").apply()
    }

    // ----- ulubione (kind = "live" albo "vod") -----
    fun favs(ctx: Context, kind: String): List<Channel> {
        val out = ArrayList<Channel>()
        try {
            val arr = JSONArray(opts(ctx).getString("fav_$kind", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Channel(o.optString("id"), o.optString("name"),
                    o.optString("logo").ifEmpty { null }, null, i + 1,
                    o.optString("url").ifEmpty { null }))
            }
        } catch (e: Exception) { }
        return out
    }

    fun isFav(ctx: Context, kind: String, id: String) = favs(ctx, kind).any { it.streamId == id }

    /** Zwraca true, jesli pozycja zostala dodana, false jesli usunieta. */
    fun toggleFav(ctx: Context, kind: String, ch: Channel): Boolean {
        val l = favs(ctx, kind).toMutableList()
        val idx = l.indexOfFirst { it.streamId == ch.streamId }
        val dodano = idx < 0
        if (dodano) l.add(ch) else l.removeAt(idx)
        val arr = JSONArray()
        for (c in l) arr.put(JSONObject().put("id", c.streamId).put("name", c.name)
            .put("logo", c.logo ?: "").put("url", c.directUrl ?: ""))
        opts(ctx).edit().putString("fav_$kind", arr.toString()).apply()
        return dodano
    }

    // ----- dodatkowe opcje i zrodla (v3.0) -----
    fun optBool(ctx: Context, key: String, def: Boolean) = opts(ctx).getBoolean(key, def)
    fun setOptBool(ctx: Context, key: String, v: Boolean) = opts(ctx).edit().putBoolean(key, v).apply()
    fun idSet(ctx: Context, key: String): MutableSet<String> =
        opt(ctx, key).split(",").filter { it.isNotEmpty() }.toMutableSet()
    fun setIdSet(ctx: Context, key: String, v: Set<String>) = setOpt(ctx, key, v.joinToString(","))

    fun sources(ctx: Context): JSONArray = try { JSONArray(prefs(ctx).getString("zrodla", "[]")) } catch (e: Exception) { JSONArray() }
    fun saveSources(ctx: Context, a: JSONArray) = prefs(ctx).edit().putString("zrodla", a.toString()).apply()

    /** Kopia zapasowa BEZ hasel: opcje, ulubione, ukryte/przypiete grupy, lista zrodel (bez hasel). */
    fun exportBackup(ctx: Context): String {
        val o = JSONObject()
        val all = JSONObject()
        for ((k, v) in opts(ctx).all) if (k != "pin") all.put(k, v)
        o.put("opcje", all)
        val src = JSONArray()
        val cur = sources(ctx)
        for (i in 0 until cur.length()) {
            val c = JSONObject(cur.getJSONObject(i).toString()); c.remove("pass"); src.put(c)
        }
        o.put("zrodla", src)
        o.put("wersja", 1)
        return o.toString(2)
    }

    fun importBackup(ctx: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        val all = o.getJSONObject("opcje")
        val e = opts(ctx).edit()
        for (k in all.keys()) {
            if (k == "pin") continue
            when (val v = all.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is String -> e.putString(k, v)
            }
        }
        e.apply()
        true
    } catch (e: Exception) { false }
}
