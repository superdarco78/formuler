package uk.okok.studiokanalow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import uk.okok.studiokanalow.model.Category
import uk.okok.studiokanalow.model.Channel
import java.util.concurrent.TimeUnit

/**
 * Klient Xtream API (player_api.php) do serwera klienta.
 * Zadne adresy ani dane nie sa wpisane na sztywno - przychodza z sesji (to, co wpisal klient).
 */
object Api {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Normalizuje adres serwera do pelnego http(s) bez koncowego ukosnika. */
    private fun baseUrl(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://$s"
        }
        return s
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StudioKanalow/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /** Logowanie: zwraca true jesli serwer potwierdzi konto. */
    suspend fun login(server: String, user: String, pass: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass"
            val body = get(url)
            val obj = JSONObject(body)
            val info = obj.optJSONObject("user_info")
            info != null && info.optString("auth", "0") == "1"
        }

    /** Konfiguracja globalna z panelu (konfig.php na serwerze). Null, gdy niedostepna. */
    suspend fun konfig(kod: String, mac: String): JSONObject? = withContext(Dispatchers.IO) {
        try { JSONObject(get("https://$HOME/konfig.php?kod=$kod&mac=$mac")) } catch (e: Exception) { null }
    }

    /** Dane konta: (status, typ, wygasniecie) - z odpowiedzi logowania. */
    suspend fun accountInfo(server: String, user: String, pass: String): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val obj = JSONObject(get("${baseUrl(server)}/player_api.php?username=$user&password=$pass"))
                val info = obj.optJSONObject("user_info") ?: return@withContext null
                val st = if (info.optString("auth", "0") == "1" && info.optString("status", "Active").equals("Active", true)) "Aktywowany" else info.optString("status", "Nieaktywny")
                val typ = if (info.optString("is_trial", "0") == "1") "Próbne" else "Abonament"
                val exp = info.optString("exp_date")
                val ex = exp.toLongOrNull()?.let {
                    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(it * 1000))
                } ?: "Bezterminowo"
                Triple(st, typ, ex)
            } catch (e: Exception) { null }
        }

    suspend fun liveCategories(server: String, user: String, pass: String): List<Category> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_live_categories"
            val arr = JSONArray(get(url))
            val out = ArrayList<Category>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Category(o.optString("category_id"), o.optString("category_name")))
            }
            out
        }

    suspend fun liveStreams(server: String, user: String, pass: String, categoryId: String?): List<Channel> =
        withContext(Dispatchers.IO) {
            var url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_live_streams"
            if (!categoryId.isNullOrEmpty()) url += "&category_id=$categoryId"
            val out = ArrayList<Channel>()
            strumienListy(url) { o ->
                out.add(Channel(o.s("stream_id"), o.s("name"), o.s("stream_icon").ifEmpty { null }, o.s("category_id"),
                    o.s("num").toIntOrNull() ?: (out.size + 1)))
            }
            out
        }

    /** Filmy trzymaja krotki adres "vod:ID.ext" (mniej pamieci) - tu zamiana na pelny adres. */
    fun pelnyUrl(u: String?, server: String, user: String, pass: String): String? =
        if (u != null && u.startsWith("vod:")) "${baseUrl(server)}/movie/$user/$pass/${u.substring(4)}" else u

    /** Buduje adres strumienia na zywo. */
    fun liveStreamUrl(server: String, user: String, pass: String, streamId: String): String {
        return "${baseUrl(server)}/live/$user/$pass/$streamId.m3u8"
    }

    /** Stały serwer aplikacji (wbudowany, klient go nie wpisuje). */
    const val HOME = "tv.ok-ok.uk"

    /** Raport bledu odtwarzania na serwer (diagnostyka, bez hasel i kluczy). */
    suspend fun raportuj(dev: String, msg: String) = withContext(Dispatchers.IO) {
        try { get("https://$HOME/app.php?a=blad&dev=$dev&msg=" + java.net.URLEncoder.encode(msg, "UTF-8")) } catch (e: Exception) { }
    }

    /** Parowanie kodem: serwer wydaje 6-cyfrowy kod (hello). */
    suspend fun appHello(dev: String, model: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                JSONObject(get("https://$HOME/app.php?a=hello&dev=$dev&model=" + java.net.URLEncoder.encode(model, "UTF-8")))
            } catch (e: Exception) { null }
        }

    /** Sprawdza, czy panel przypisal kod do klienta (poll). */
    suspend fun appPoll(dev: String, sec: String): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                JSONObject(get("https://$HOME/app.php?a=poll&dev=$dev&sec=" + java.net.URLEncoder.encode(sec, "UTF-8")))
            } catch (e: Exception) { null }
        }

    /**
     * Logowanie kodem/MAC. Pyta serwer, czy ten kod/MAC jest przypisany do konta.
     * Zwraca (serwer, login, haslo) gdy dowiazane, albo null gdy jeszcze nie.
     */
    suspend fun pair(code: String, mac: String): Triple<String, String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://$HOME/parowanie.php?kod=$code&mac=$mac"
                val o = JSONObject(get(url))
                if (o.optInt("ok", 0) == 1) {
                    Triple(
                        o.optString("server", HOME),
                        o.optString("user"),
                        o.optString("pass")
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Czyta duza liste JSON strumieniowo (obiekt po obiekcie), bez trzymania calego tekstu w pamieci.
     * Duze listy filmow nie zapychaja pamieci i aplikacja sie nie zawiesza.
     */
    private fun strumienListy(url: String, naObiekt: (Map<String, String>) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "StudioKanalow/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val r = android.util.JsonReader(resp.body!!.charStream())
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) { r.skipValue(); return }
            r.beginArray()
            val m = HashMap<String, String>()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                m.clear(); r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        android.util.JsonToken.STRING -> m[k] = r.nextString()
                        android.util.JsonToken.NUMBER -> m[k] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                naObiekt(m)
            }
        }
    }

    private fun Map<String, String>.s(k: String) = this[k]?.takeIf { it != "null" } ?: ""

    /** Lista filmow (VOD). Kazdy film ma gotowy adres do odtwarzania w directUrl. */
    suspend fun vodStreams(server: String, user: String, pass: String, categoryId: String?): List<Channel> =
        withContext(Dispatchers.IO) {
            var url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_vod_streams"
            if (!categoryId.isNullOrEmpty()) url += "&category_id=$categoryId"
            val out = ArrayList<Channel>()
            val baza = baseUrl(server)
            strumienListy(url) { o ->
                val id = o.s("stream_id")
                val ext = o.s("container_extension").ifEmpty { "mp4" }
                out.add(Channel(id, o.s("name"), o.s("stream_icon").ifEmpty { null }, o.s("category_id"),
                    o.s("num").toIntOrNull() ?: (out.size + 1), "vod:$id.$ext",
                    o.s("rating_5based").toDoubleOrNull() ?: o.s("rating").toDoubleOrNull() ?: 0.0))
            }
            out
        }

    private fun categories(server: String, user: String, pass: String, action: String): List<Category> {
        val arr = JSONArray(get("${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=$action"))
        val out = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Category(o.optString("category_id"), o.optString("category_name")))
        }
        return out
    }

    suspend fun vodCategories(server: String, user: String, pass: String): List<Category> =
        withContext(Dispatchers.IO) { categories(server, user, pass, "get_vod_categories") }

    suspend fun seriesCategories(server: String, user: String, pass: String): List<Category> =
        withContext(Dispatchers.IO) { categories(server, user, pass, "get_series_categories") }

    /** Lista seriali (bez odcinkow). */
    suspend fun seriesList(server: String, user: String, pass: String): List<Channel> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Channel>()
            strumienListy("${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series") { o ->
                out.add(Channel(o.s("series_id"), o.s("name"), o.s("cover").ifEmpty { null }, o.s("category_id"), out.size + 1, null,
                    o.s("rating_5based").toDoubleOrNull() ?: o.s("rating").toDoubleOrNull() ?: 0.0))
            }
            out
        }

    /** Odcinki serialu - kazdy ma gotowy adres w directUrl. */
    suspend fun seriesEpisodes(server: String, user: String, pass: String, seriesId: String): List<Channel> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId&okp=1"
            val eps = JSONObject(get(url)).optJSONObject("episodes")
            val out = ArrayList<Channel>()
            if (eps != null) {
                val sezony = eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
                for (sez in sezony) {
                    val arr = eps.optJSONArray(sez) ?: continue
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optString("id")
                        val ext = o.optString("container_extension").ifEmpty { "mp4" }
                        val nr = o.optString("episode_num")
                        val tytul = o.optString("title")
                        out.add(Channel(id, "S$sez E$nr — $tytul", null, null, out.size + 1,
                            "${baseUrl(server)}/series/$user/$pass/$id.$ext"))
                    }
                }
            }
            out
        }

    /** Szczegoly serialu: opis/obsada/ocena + odcinki pogrupowane w sezony. */
    suspend fun seriesDetails(server: String, user: String, pass: String, seriesId: String): Pair<VodInfo?, List<Pair<String, List<Channel>>>> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId&okp=1"
            val root = JSONObject(get(url))
            val inf = root.optJSONObject("info")
            fun t(k: String) = inf?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
            val vi = inf?.let {
                VodInfo(poster = t("cover"), plot = t("plot"), cast = t("cast"), genre = t("genre"),
                    year = t("releaseDate").take(4).ifEmpty { t("year") }, rating = t("rating"),
                    duration = t("episode_run_time").let { d -> if (d.isNotEmpty()) "$d min" else "" },
                    trailer = t("youtube_trailer"))
            }
            val out = ArrayList<Pair<String, List<Channel>>>()
            val eps = root.optJSONObject("episodes")
            if (eps != null) {
                for (sez in eps.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }) {
                    val arr = eps.optJSONArray(sez) ?: continue
                    val l = ArrayList<Channel>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optString("id")
                        val ext = o.optString("container_extension").ifEmpty { "mp4" }
                        val oi = o.optJSONObject("info")
                        fun ti(k: String) = oi?.optString(k)?.takeIf { x -> x.isNotEmpty() && x != "null" } ?: ""
                        l.add(Channel(id, "Odcinek ${o.optString("episode_num")} — ${o.optString("title")}", ti("movie_image").ifEmpty { null }, null, i + 1,
                            "${baseUrl(server)}/series/$user/$pass/$id.$ext", 0.0, ti("plot"), ti("duration")))
                    }
                    out.add("Sezon $sez" to l)
                }
            }
            vi to out
        }

    /** Normalizacja tytulu do porownan: male litery, bez roku, jakosci i znakow. */
    fun normTytul(t: String): String = t.lowercase()
        .replace(Regex("\\(\\d{4}\\).*$"), " ")
        .replace(Regex("\\b(4k|uhd|fhd|hd|lektor|napisy|dubbing|pl|multi|ai)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun rokTytulu(t: String): Int? = Regex("\\((\\d{4})\\)").find(t)?.groupValues?.get(1)?.toIntOrNull()

    /** Filmografia aktora z Wikidata (bez klucza): pary (tytul znormalizowany, rok). */
    suspend fun filmyAktora(nazwa: String): List<Pair<String, Int?>> = withContext(Dispatchers.IO) {
        val esc = nazwa.replace("\\", "").replace("\"", "")
        val q = """SELECT ?l ?y WHERE { ?a rdfs:label "$esc"@en. ?f wdt:P161 ?a. ?f rdfs:label ?l. FILTER(lang(?l) IN ("pl","en")) OPTIONAL { ?f wdt:P577 ?d. BIND(YEAR(?d) AS ?y) } } LIMIT 2000"""
        val url = "https://query.wikidata.org/sparql?format=json&query=" + java.net.URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "OKPlayer/3.6 (aplikacja IPTV; kontakt przez GitHub superdarco78)")
            .header("Accept", "application/sparql-results+json").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        val arr = JSONObject(body).optJSONObject("results")?.optJSONArray("bindings") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            normTytul(o.optJSONObject("l")?.optString("value") ?: "") to o.optJSONObject("y")?.optString("value")?.toIntOrNull()
        }.filter { it.first.length >= 2 }.distinct()
    }

    /** Dodatkowe dane filmu z Wikidata (bez klucza): zwiastun YouTube i obsada z rolami. */
    data class WikiFilm(val youtube: String, val obsada: List<Pair<String, String>>)

    private fun sparql(q: String): org.json.JSONArray {
        val url = "https://query.wikidata.org/sparql?format=json&query=" + java.net.URLEncoder.encode(q, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", "OKPlayer/3.6 (aplikacja IPTV; kontakt przez GitHub superdarco78)")
            .header("Accept", "application/sparql-results+json").build()
        val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
        return JSONObject(body).optJSONObject("results")?.optJSONArray("bindings") ?: org.json.JSONArray()
    }

    /** Szuka filmu po numerze TMDb (dokladnie), a gdy go brak - po tytule i roku. */
    suspend fun wikiFilm(tmdb: String, tytul: String): WikiFilm? = withContext(Dispatchers.IO) {
        try {
            val idTmdb = tmdb.filter { it.isDigit() }
            val czysty = tytul.replace(Regex("\\(\\d{4}\\).*$"), "").replace("\"", "").trim()
            val rok = rokTytulu(tytul)
            val wybor = if (idTmdb.isNotEmpty()) """?f wdt:P4947 "$idTmdb"."""
                else """?f rdfs:label "$czysty"@pl. ?f wdt:P31/wdt:P279* wd:Q11424. """ + (if (rok != null) "?f wdt:P577 ?d. FILTER(YEAR(?d) >= ${rok - 1} && YEAR(?d) <= ${rok + 1})" else "")
            val yt = sparql("SELECT ?yt WHERE { $wybor ?f wdt:P1651 ?yt. } LIMIT 3").let { a -> if (a.length() > 0) a.getJSONObject(0).optJSONObject("yt")?.optString("value") ?: "" else "" }
            val ob = sparql("""SELECT ?aL (SAMPLE(?rl) AS ?rL) WHERE { $wybor ?f p:P161 ?st. ?st ps:P161 ?a. ?a rdfs:label ?aL. FILTER(lang(?aL)="en")
                OPTIONAL { ?st pq:P453 ?r. ?r rdfs:label ?rl. FILTER(lang(?rl) IN ("pl","en")) }
                OPTIONAL { ?st pq:P4633 ?rn. BIND(STR(?rn) AS ?rl) } } GROUP BY ?aL LIMIT 40""")
            val obsada = (0 until ob.length()).map { i -> val o = ob.getJSONObject(i)
                (o.optJSONObject("aL")?.optString("value") ?: "") to (o.optJSONObject("rL")?.optString("value") ?: "") }.filter { it.first.isNotEmpty() }
            if (yt.isEmpty() && obsada.isEmpty()) null else WikiFilm(yt, obsada)
        } catch (e: Exception) { null }
    }

    private val zdjeciaAktorow = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Zdjecie aktora z publicznej wyszukiwarki IMDb (bez klucza). Pusty tekst = brak. */
    suspend fun zdjecieAktora(nazwa: String): String = withContext(Dispatchers.IO) {
        zdjeciaAktorow[nazwa]?.let { return@withContext it }
        val wynik = try {
            val q = nazwa.lowercase().trim().replace(Regex("\\s+"), "_")
            val url = "https://v2.sg.media-imdb.com/suggestion/" + q.first().let { c -> if (c.isLetter()) c else 'x' } + "/" + java.net.URLEncoder.encode(q, "UTF-8") + ".json"
            val arr = JSONObject(get(url)).optJSONArray("d")
            var u = ""
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optString("id").startsWith("nm")) continue
                u = o.optJSONObject("i")?.optString("imageUrl") ?: ""
                if (u.isNotEmpty()) { u = u.replace(Regex("\\._V1_.*\\.jpg$"), "._V1_UX400_.jpg"); break }
            }
            u
        } catch (e: Exception) { "" }
        zdjeciaAktorow[nazwa] = wynik
        wynik
    }

    /** Uzupelnienie brakujacych danych filmu z publicznej bazy (iTunes, bez klucza): okladka, opis, gatunek, rok. */
    suspend fun itunes(tytul: String): VodInfo? = withContext(Dispatchers.IO) {
        val rok = Regex("\\((\\d{4})\\)").find(tytul)?.groupValues?.get(1)?.toIntOrNull()
        val czysty = tytul.replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("\\b(napisy|lektor|dubbing|pl|4k|hd|fhd|uhd)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
        if (czysty.length < 2) return@withContext null
        fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val url = "https://itunes.apple.com/search?term=" + java.net.URLEncoder.encode(czysty, "UTF-8") + "&media=movie&entity=movie&country=PL&limit=5"
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nazwa = o.optString("trackName")
            val r = o.optString("releaseDate").take(4).toIntOrNull()
            if (n(nazwa) != n(czysty) && !n(nazwa).contains(n(czysty)) && !n(czysty).contains(n(nazwa))) continue
            if (rok != null && r != null && kotlin.math.abs(rok - r) > 1) continue
            val plot = o.optString("longDescription").ifEmpty { o.optString("shortDescription") }
            val okl = o.optString("artworkUrl100").replace("100x100bb", "600x900bb")
            return@withContext VodInfo(poster = okl, plot = plot, cast = "", genre = o.optString("primaryGenreName"),
                year = r?.toString() ?: "", rating = "", duration = "", trailer = "")
        }
        null
    }

    /** Szczegoly filmu (opis, obsada, ocena, zwiastun) - to, co wpisano w edytorze. */
    suspend fun vodInfo(server: String, user: String, pass: String, streamId: String): VodInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_vod_info&vod_id=$streamId&okp=1"
                val info = JSONObject(get(url)).optJSONObject("info") ?: return@withContext null
                fun t(k: String) = info.optString(k).takeIf { it.isNotEmpty() && it != "null" } ?: ""
                VodInfo(
                    poster = t("movie_image").ifEmpty { t("cover_big") },
                    plot = t("plot").ifEmpty { t("description") },
                    cast = t("cast").ifEmpty { t("actors") },
                    genre = t("genre"),
                    year = t("releasedate").take(4).ifEmpty { t("year") },
                    rating = t("rating"),
                    duration = t("duration"),
                    trailer = t("youtube_trailer"),
                    director = t("director"), country = t("country"), premiera = t("releasedate"),
                    backdrop = (info.optJSONArray("backdrop_path")?.optString(0) ?: t("backdrop_path")).let { x -> if (x == "null") "" else x },
                    wiek = t("age").ifEmpty { t("mpaa_rating") }.replace("+", ""),
                    tmdb = t("tmdb_id").ifEmpty { t("tmdb") },
                    galeria = info.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { i -> a.optString(i) }.filter { x -> x.startsWith("http") }.distinct() } ?: emptyList(),
                    media = listOfNotNull(
                        info.optJSONObject("video")?.let { v -> listOf(v.optString("codec_name"), v.optString("width") + "x" + v.optString("height")).filter { x -> x.isNotEmpty() && x != "x" }.joinToString(" ") }?.ifEmpty { null },
                        info.optJSONObject("audio")?.let { au -> listOf(au.optString("codec_name"), au.optString("channel_layout")).filter { x -> x.isNotEmpty() }.joinToString(" ") }?.ifEmpty { null }
                    ).joinToString("\n")
                )
            } catch (e: Exception) { null }
        }

    /** Wpis przewodnika programu: archiwum = mozna odtworzyc z cofniecia. */
    data class Wpis(val tytul: String, val start: Long, val koniec: Long, val startTxt: String, val archiwum: Boolean)

    suspend fun przewodnik(server: String, user: String, pass: String, streamId: String): List<Wpis> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$streamId&okp=1"
            val arr = JSONObject(get(url)).optJSONArray("epg_listings") ?: return@withContext emptyList()
            fun dek(x: String) = try { String(android.util.Base64.decode(x, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { x }
            val out = ArrayList<Wpis>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Wpis(dek(o.optString("title")), o.optString("start_timestamp").toLongOrNull() ?: 0L,
                    o.optString("stop_timestamp").toLongOrNull() ?: 0L, o.optString("start"), o.optString("has_archive") == "1"))
            }
            out.sortedBy { it.start }
        }

    /** Adres odtwarzania z archiwum (timeshift) dla wpisu przewodnika. */
    fun archiwumUrl(server: String, user: String, pass: String, streamId: String, w: Wpis): String {
        val min = (((w.koniec - w.start) / 60).toInt()).coerceAtLeast(1)
        val t = w.startTxt.replace(' ', ':').take(16).let { x -> if (x.length == 16) x.substring(0, 13) + "-" + x.substring(14) else x }
        return "${baseUrl(server)}/timeshift/$user/$pass/$min/$t/$streamId.ts"
    }

    /** Pozycja programu TV: tytul, poczatek i koniec (sekundy). */
    data class Audycja(val tytul: String, val start: Long, val koniec: Long)

    /** Program TV: teraz i nastepne (serwer pobiera od dostawcy, okp=1). */
    suspend fun programTv(server: String, user: String, pass: String, streamId: String, ile: Int = 6): List<Audycja> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_short_epg&stream_id=$streamId&limit=$ile&okp=1"
            val arr = JSONObject(get(url)).optJSONArray("epg_listings") ?: return@withContext emptyList()
            fun dek(x: String) = try { String(android.util.Base64.decode(x, android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) { x }
            val teraz = System.currentTimeMillis() / 1000
            val out = ArrayList<Audycja>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = o.optString("start_timestamp").toLongOrNull() ?: 0L
                val en = o.optString("stop_timestamp").toLongOrNull() ?: 0L
                if (en in 1..teraz) continue
                out.add(Audycja(dek(o.optString("title")), st, en))
            }
            out
        }
}

data class VodInfo(
    val poster: String, val plot: String, val cast: String, val genre: String,
    val year: String, val rating: String, val duration: String, val trailer: String,
    val director: String = "", val country: String = "", val premiera: String = "", val media: String = "",
    val backdrop: String = "", val wiek: String = "", val tmdb: String = "",
    val galeria: List<String> = emptyList()
)
