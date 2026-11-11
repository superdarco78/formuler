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
data class Wpis(val tytul: String, val start: Long, val koniec: Long, val startTxt: String, val archiwum: Boolean, val opis: String = "")
/** Film albo serial (rodzaj "f" / "s"). ext = rozszerzenie pliku filmu. */
data class Vod(val id: String, val nazwa: String, val okladka: String?, val kat: String, val ocena: Double, val ext: String, val rodzaj: String)

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
    fun zapiszOstatni(c: Context, k: Kanal) {
        // Ostatnio ogladane: do 15 kanalow, najnowszy pierwszy.
        val l = ostatnie(c).filter { it.id != k.id }.toMutableList(); l.add(0, k)
        prefs(c).edit().putString("ost_id", k.id).putString("ost_nazwa", k.nazwa).putString("ost_logo", k.logo)
            .putString("ostatnie", l.take(15).joinToString("\n") { listOf(it.id, it.nazwa, it.logo ?: "", it.kat).joinToString("\u0001") }).apply()
    }
    fun ostatnie(c: Context): List<Kanal> = (prefs(c).getString("ostatnie", "") ?: "").split("\n").filter { it.isNotEmpty() }
        .map { it.split("\u0001") }.filter { it.size >= 4 }.map { Kanal(it[0], it[1], it[2].ifEmpty { null }, it[3], 0) }
    fun pozycja(c: Context, url: String): Long = prefs(c).getLong("poz_" + url.hashCode(), 0)
    fun zapiszPozycje(c: Context, url: String, ms: Long) = prefs(c).edit().putLong("poz_" + url.hashCode(), ms).apply()
    fun dlugosc(c: Context, url: String): Long = prefs(c).getLong("dl_" + url.hashCode(), 0)
    fun zapiszDlugosc(c: Context, url: String, ms: Long) = prefs(c).edit().putLong("dl_" + url.hashCode(), ms).apply()
    /** Ostatnio ogladany odcinek serialu: "sezon|indeks". */
    fun ostatniOdcinek(c: Context, serial: String): String = prefs(c).getString("odc_$serial", "") ?: ""
    fun zapiszOdcinek(c: Context, serial: String, v: String) = prefs(c).edit().putString("odc_$serial", v).apply()
    // Ulubione: lista "rodzaj|id|nazwa|obraz|ext|kat"
    fun ulubione(c: Context): List<List<String>> = (prefs(c).getString("ulubione", "") ?: "").split("\n").filter { it.isNotEmpty() }.map { it.split("|") }.filter { it.size >= 6 }
    fun czyUlubiony(c: Context, rodzaj: String, id: String) = ulubione(c).any { it[0] == rodzaj && it[1] == id }
    fun przelaczUlubiony(c: Context, rodzaj: String, id: String, nazwa: String, obraz: String?, ext: String, kat: String): Boolean {
        val l = ulubione(c).toMutableList()
        val jest = l.removeAll { it[0] == rodzaj && it[1] == id }
        if (!jest) l.add(0, listOf(rodzaj, id, nazwa.replace("|", " ").replace("\n", " "), obraz ?: "", ext, kat))
        prefs(c).edit().putString("ulubione", l.joinToString("\n") { it.joinToString("|") }).apply()
        return !jest
    }
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
    /** Filmy i odcinki ida przez serwer (okp=1), bo dostawca blokuje telefon. */
    fun adresFilmu(c: Context, id: String, ext: String) = "$BAZA/movie/app/${Ust.klucz(c)}/$id.$ext?okp=1"
    fun adresOdcinka(c: Context, id: String, ext: String) = "$BAZA/series/app/${Ust.klucz(c)}/$id.$ext?okp=1"

    /** Program TV kanalu (z archiwum, jesli dostawca je ma). */
    fun przewodnik(c: Context, id: String): List<Wpis> = try {
        val arr = JSONObject(tekst(api(c, "get_simple_data_table") + "&stream_id=$id&okp=1")).optJSONArray("epg_listings")
        fun dek(x: String) = try { String(android.util.Base64.decode(x, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { x }
        if (arr == null) emptyList() else (0 until arr.length()).map { i -> val o = arr.getJSONObject(i)
            Wpis(dek(o.optString("title")), o.optString("start_timestamp").toLongOrNull() ?: 0, o.optString("stop_timestamp").toLongOrNull() ?: 0, o.optString("start"), o.optString("has_archive") == "1", dek(o.optString("description")).trim()) }.sortedBy { it.start }
    } catch (e: Exception) { emptyList() }

    /** "Teraz leci" dla kanalu (EPG), z pamiecia na 10 min. */
    private val teraz = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    /** Czas trwania biezacej audycji (start, koniec w sekundach) - do paska postepu. */
    val okno = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
    fun postep(id: String): Int { val (a, b) = okno[id] ?: return -1; val t = System.currentTimeMillis() / 1000; return if (b > a && t in a..b) ((t - a) * 100 / (b - a)).toInt() else -1 }

    /** "13:35–14:10 • jeszcze 25 min" dla biezacej audycji. */
    fun godziny(id: String): String { val (a, b) = okno[id] ?: return ""; if (a <= 0 || b <= a) return ""
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl")); val z = ((b - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
        return f.format(java.util.Date(a * 1000)) + "–" + f.format(java.util.Date(b * 1000)) + "  •  jeszcze " + z + " min" }

    fun terazLeci(c: Context, id: String): String {
        val now = System.currentTimeMillis()
        // Udany wynik pamietany 10 min; pusty (blad / brak odpowiedzi) tylko 30 s - zeby "Teraz" szybko sie pojawilo.
        teraz[id]?.let { if (now - it.first < (if (it.second.isEmpty()) 30_000 else 600_000)) return it.second }
        fun pobierz(): String = try {
            val arr = JSONObject(tekst(api(c, "get_short_epg") + "&stream_id=$id&limit=4&okp=1")).optJSONArray("epg_listings")
            fun dek(x: String) = try { String(android.util.Base64.decode(x, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { x }
            val sek = now / 1000
            var out = ""
            if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val en = o.optString("stop_timestamp").toLongOrNull() ?: 0; if (en in 1..sek) continue; out = dek(o.optString("title")); okno[id] = (o.optString("start_timestamp").toLongOrNull() ?: 0L) to en; break }
            out
        } catch (e: Exception) { "" }
        val w = pobierz().ifEmpty { Thread.sleep(800); pobierz() }
        teraz[id] = now to w
        return w
    }

    fun adresArchiwum(c: Context, id: String, w: Wpis): String {
        val min = ((w.koniec - w.start) / 60).toInt().coerceAtLeast(1)
        val t = w.startTxt.replace(' ', ':').take(16).let { x -> if (x.length == 16) x.substring(0, 13) + "-" + x.substring(14) else x }
        return "$BAZA/timeshift/app/${Ust.klucz(c)}/$min/$t/$id.ts?okp=1"
    }

    /** Kilka sposobow pobrania archiwum - gdy pierwszy stoi (zawieszony obraz), aplikacja sama probuje nastepnego. */
    fun adresyArchiwum(c: Context, id: String, w: Wpis): List<String> {
        val ts = adresArchiwum(c, id, w)
        val min = ((w.koniec - w.start) / 60).toInt().coerceAtLeast(1)
        val t = ts.substringAfter("/$min/").substringBefore("/")
        return listOf(ts, ts.replace("/$id.ts?", "/$id.m3u8?"),
            "$BAZA/streaming/timeshift.php?username=app&password=${Ust.klucz(c)}&stream=$id&start=$t&duration=$min&okp=1")
    }

    fun kategorie(c: Context, akcja: String): List<Kat> {
        val out = ArrayList<Kat>()
        strumien(api(c, akcja)) { o -> out.add(Kat(o["category_id"] ?: "", o["category_name"] ?: "")) }
        return out
    }
    private fun ocena(o: Map<String, String>) = o["rating"]?.toDoubleOrNull() ?: ((o["rating_5based"]?.toDoubleOrNull() ?: 0.0) * 2)
    fun filmy(c: Context): List<Vod> {
        val out = ArrayList<Vod>()
        strumien(api(c, "get_vod_streams")) { o -> out.add(Vod(o["stream_id"] ?: "", o["name"] ?: "", o["stream_icon"]?.takeIf { it.startsWith("http") }, o["category_id"] ?: "", ocena(o), o["container_extension"]?.ifEmpty { null } ?: "mp4", "f")) }
        return out
    }
    fun seriale(c: Context): List<Vod> {
        val out = ArrayList<Vod>()
        strumien(api(c, "get_series")) { o -> out.add(Vod(o["series_id"] ?: "", o["name"] ?: "", o["cover"]?.takeIf { it.startsWith("http") }, o["category_id"] ?: "", ocena(o), "", "s")) }
        return out
    }
    /** Opis filmu/serialu: z pamieci telefonu od razu (zapamietany na 7 dni), z serwera w tle; 3 proby przy bledzie. */
    private fun plikInfo(c: Context, r: String, id: String) = File(File(c.filesDir, "info").apply { mkdirs() }, "${r}_$id.json")
    fun infoZPamieci(c: Context, serial: Boolean, id: String): JSONObject? = try {
        val f = plikInfo(c, if (serial) "s" else "f", id); if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) { null }
    /** Ostatni powod niepowodzenia (pokazywany na ekranie i wysylany do serwera). */
    @Volatile var bladInfo = ""
    private fun info(c: Context, serial: Boolean, id: String): JSONObject? {
        val baza = if (serial) api(c, "get_series_info") + "&series_id=$id" else api(c, "get_vod_info") + "&vod_id=$id"
        // 1) przez nasz serwer z dodatkami, 2) bez dodatkow (szybciej), 3) jeszcze raz z dodatkami
        val adresy = listOf("$baza&okp=1", baza, "$baza&okp=1")
        var zapas: JSONObject? = null
        for ((proba, u) in adresy.withIndex()) {
            try {
                szybki.newCall(okhttp3.Request.Builder().url(u).build()).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (!r.isSuccessful) { bladInfo = "HTTP ${r.code}"; return@use }
                    val o = JSONObject(t)
                    if ((o.optJSONObject("info")?.length() ?: 0) > 0) { try { plikInfo(c, if (serial) "s" else "f", id).writeText(t) } catch (_: Exception) {}; return o }
                    zapas = o; bladInfo = "serwer nie ma opisu tego tytułu"
                }
            } catch (e: Exception) { bladInfo = e.javaClass.simpleName.replace("Exception", "").ifEmpty { "błąd" } + (e.message?.let { " " + it.take(40) } ?: "") }
            Thread.sleep(600L * (proba + 1))
        }
        return zapas
    }
    /** Limit czasu dla opisow: 25 s na probe. */
    private val szybki by lazy { http.newBuilder().callTimeout(25, TimeUnit.SECONDS).build() }
    fun infoFilmu(c: Context, id: String): JSONObject? = info(c, false, id)
    fun infoSerialu(c: Context, id: String): JSONObject? = info(c, true, id)
    /** Wczesniejsze pobranie opisow (np. pierwszych plakatow na Starcie), zeby otwieraly sie od razu. */
    fun wczesniej(c: Context, l: List<Vod>) { for (v in l.take(8)) { Thread.sleep(1500); val f = plikInfo(c, if (v.rodzaj == "s") "s" else "f", v.id); if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) continue; info(c, v.rodzaj == "s", v.id) } }
}

/** Pamiec list na dysku: Start pokazuje sie od razu, swieze dane dociagaja sie w tle. */
object Pamiec {
    fun zapiszVod(c: Context, n: String, l: List<Vod>) = try {
        val t = File(c.filesDir, "$n.tmp")
        android.util.JsonWriter(t.bufferedWriter()).use { w ->
            w.beginArray(); for (v in l) w.beginArray().value(v.id).value(v.nazwa).value(v.okladka ?: "").value(v.kat).value(v.ocena).value(v.ext).value(v.rodzaj).endArray(); w.endArray()
        }
        t.renameTo(File(c.filesDir, "$n.json")); Unit
    } catch (e: Exception) { }

    fun vod(c: Context, n: String): List<Vod>? = try {
        val f = File(c.filesDir, "$n.json"); if (!f.exists()) null else {
            val out = ArrayList<Vod>()
            android.util.JsonReader(f.bufferedReader()).use { r ->
                r.beginArray()
                while (r.hasNext()) { r.beginArray(); val v = Vod(r.nextString(), r.nextString(), r.nextString().ifEmpty { null }, r.nextString(), r.nextDouble(), r.nextString(), r.nextString()); while (r.hasNext()) r.skipValue(); r.endArray(); out.add(v) }
                r.endArray()
            }
            out
        }
    } catch (e: Exception) { null }

    fun zapiszKatN(c: Context, n: String, l: List<Kat>) = try {
        val t = File(c.filesDir, "$n.tmp")
        android.util.JsonWriter(t.bufferedWriter()).use { w -> w.beginArray(); for (k in l) w.beginArray().value(k.id).value(k.nazwa).endArray(); w.endArray() }
        t.renameTo(File(c.filesDir, "$n.json")); Unit
    } catch (e: Exception) { }

    fun katN(c: Context, n: String): List<Kat>? = try {
        val f = File(c.filesDir, "$n.json"); if (!f.exists()) null else {
            val out = ArrayList<Kat>()
            android.util.JsonReader(f.bufferedReader()).use { r -> r.beginArray(); while (r.hasNext()) { r.beginArray(); out.add(Kat(r.nextString(), r.nextString())); while (r.hasNext()) r.skipValue(); r.endArray() }; r.endArray() }
            out
        }
    } catch (e: Exception) { null }

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
