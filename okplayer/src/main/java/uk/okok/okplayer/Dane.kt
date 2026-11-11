package uk.okok.okplayer

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/*
 * OK Player (nowy, od zera) - warstwa danych.
 * Zasada: nic ciezkiego na glownym watku. Wszystko tutaj wolane jest z Dispatchers.IO.
 */

data class Kat(val id: String, val nazwa: String)
data class Kanal(val id: String, val nazwa: String, val logo: String?, val kat: String, val nr: Int)

/** Ustawienia - zwykle prywatne ustawienia aplikacji, otwierane raz (bez Keystore, ktory blokowal stara aplikacje). */
object Ust {
    @Volatile private var p: SharedPreferences? = null
    fun prefs(c: Context): SharedPreferences = p ?: synchronized(this) { p ?: c.applicationContext.getSharedPreferences("okplayer", Context.MODE_PRIVATE).also { p = it } }
    fun klucz(c: Context): String = prefs(c).getString("klucz", "") ?: ""
    fun zalogowany(c: Context) = klucz(c).isNotEmpty()
    fun zapiszKlucz(c: Context, k: String) = prefs(c).edit().putString("klucz", k).apply()
    fun wyloguj(c: Context) = prefs(c).edit().remove("klucz").apply()
    fun ostatni(c: Context): Kanal? {
        val id = prefs(c).getString("ost_id", null) ?: return null
        return Kanal(id, prefs(c).getString("ost_nazwa", "") ?: "", prefs(c).getString("ost_logo", null), "", 0)
    }
    fun zapiszOstatni(c: Context, k: Kanal) = prefs(c).edit().putString("ost_id", k.id).putString("ost_nazwa", k.nazwa).putString("ost_logo", k.logo).apply()
    fun grupa(c: Context): String = prefs(c).getString("grupa", "") ?: ""
    fun zapiszGrupe(c: Context, id: String) = prefs(c).edit().putString("grupa", id).apply()
}

object Serwer {
    const val HOME = "tv.ok-ok.uk"
    private const val BAZA = "https://$HOME"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build()

    private fun tekst(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", "OKPlayer/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }

    fun idUrzadzenia(c: Context): String {
        val a = try { android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ANDROID_ID) } catch (e: Exception) { null } ?: "0"
        return ("ok-$a").replace(Regex("[^A-Za-z0-9-]"), "")
    }

    /** Parowanie: serwer daje 6-cyfrowy kod do wpisania w edytorze. */
    fun hello(c: Context): JSONObject? = try {
        JSONObject(tekst("$BAZA/app.php?a=hello&dev=${idUrzadzenia(c)}&model=" + java.net.URLEncoder.encode(android.os.Build.MODEL ?: "Android", "UTF-8")))
    } catch (e: Exception) { null }

    fun poll(c: Context, sec: String): JSONObject? = try {
        JSONObject(tekst("$BAZA/app.php?a=poll&dev=${idUrzadzenia(c)}&sec=" + java.net.URLEncoder.encode(sec, "UTF-8")))
    } catch (e: Exception) { null }

    fun sprawdzKlucz(klucz: String): Boolean = try {
        JSONObject(tekst("$BAZA/player_api.php?username=app&password=$klucz")).optJSONObject("user_info")?.optInt("auth", 0) == 1
    } catch (e: Exception) { false }

    fun raport(c: Context, msg: String) {
        try { tekst("$BAZA/app.php?a=blad&dev=${idUrzadzenia(c)}&msg=" + java.net.URLEncoder.encode("OKP2 " + msg, "UTF-8")) } catch (e: Exception) { }
    }

    private fun api(c: Context, akcja: String) = "$BAZA/player_api.php?username=app&password=${Ust.klucz(c)}&action=$akcja"

    /** Czyta liste JSON obiekt po obiekcie (malo pamieci, bez zawieszania). */
    private fun strumien(url: String, naObiekt: (Map<String, String>) -> Unit) {
        http.newCall(Request.Builder().url(url).header("User-Agent", "OKPlayer/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
            val j = android.util.JsonReader(r.body!!.charStream())
            j.isLenient = true
            if (j.peek() != android.util.JsonToken.BEGIN_ARRAY) return
            j.beginArray()
            val m = HashMap<String, String>()
            while (j.hasNext()) {
                if (j.peek() != android.util.JsonToken.BEGIN_OBJECT) { j.skipValue(); continue }
                m.clear(); j.beginObject()
                while (j.hasNext()) {
                    val k = j.nextName()
                    when (j.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> m[k] = j.nextString()
                        else -> j.skipValue()
                    }
                }
                j.endObject(); naObiekt(m)
            }
            j.endArray()
        }
    }

    fun kategorieTv(c: Context): List<Kat> {
        val out = ArrayList<Kat>()
        strumien(api(c, "get_live_categories")) { o -> out.add(Kat(o["category_id"] ?: "", o["category_name"] ?: "")) }
        return out
    }

    fun kanaly(c: Context): List<Kanal> {
        val out = ArrayList<Kanal>()
        strumien(api(c, "get_live_streams")) { o ->
            out.add(Kanal(o["stream_id"] ?: "", o["name"] ?: "", o["stream_icon"]?.takeIf { it.startsWith("http") }, o["category_id"] ?: "", o["num"]?.toIntOrNull() ?: (out.size + 1)))
        }
        return out
    }

    fun adresKanalu(c: Context, id: String) = "$BAZA/live/app/${Ust.klucz(c)}/$id.m3u8"
}

/** Pamiec list na dysku: Start pokazuje sie od razu, swieze dane dociagaja sie w tle. */
object Pamiec {
    private fun plik(c: Context, n: String) = File(c.filesDir, "$n.json")

    fun zapiszKanaly(c: Context, l: List<Kanal>) = try {
        val t = File(c.filesDir, "kanaly.tmp")
        android.util.JsonWriter(t.bufferedWriter()).use { w ->
            w.beginArray(); for (k in l) w.beginArray().value(k.id).value(k.nazwa).value(k.logo ?: "").value(k.kat).value(k.nr.toLong()).endArray(); w.endArray()
        }
        t.renameTo(plik(c, "kanaly")); Unit
    } catch (e: Exception) { }

    fun kanaly(c: Context): List<Kanal>? = try {
        val f = plik(c, "kanaly"); if (!f.exists()) null else {
            val out = ArrayList<Kanal>()
            android.util.JsonReader(f.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) { r.beginArray(); val k = Kanal(r.nextString(), r.nextString(), r.nextString().ifEmpty { null }, r.nextString(), r.nextInt()); while (r.hasNext()) r.skipValue(); r.endArray(); out.add(k) }
                r.endArray()
            }
            out
        }
    } catch (e: Exception) { null }

    fun zapiszKat(c: Context, l: List<Kat>) = try {
        val t = File(c.filesDir, "kat.tmp")
        android.util.JsonWriter(t.bufferedWriter()).use { w -> w.beginArray(); for (k in l) w.beginArray().value(k.id).value(k.nazwa).endArray(); w.endArray() }
        t.renameTo(plik(c, "kat")); Unit
    } catch (e: Exception) { }

    fun kat(c: Context): List<Kat>? = try {
        val f = plik(c, "kat"); if (!f.exists()) null else {
            val out = ArrayList<Kat>()
            android.util.JsonReader(f.bufferedReader()).use { r -> r.beginArray(); while (r.hasNext()) { r.beginArray(); out.add(Kat(r.nextString(), r.nextString())); while (r.hasNext()) r.skipValue(); r.endArray() }; r.endArray() }
            out
        }
    } catch (e: Exception) { null }
}
