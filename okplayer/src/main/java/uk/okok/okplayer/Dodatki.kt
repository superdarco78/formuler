package uk.okok.okplayer

import okhttp3.Request
import org.json.JSONObject

/** Dodatkowe dane filmow z publicznych zrodel bez klucza: zdjecia aktorow (IMDb), zwiastun i role (Wikidata). */
object Dodatki {
    private val zdjecia = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val imdbId = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun tekst(url: String, ua: String = "OKPlayer/1.0 (aplikacja IPTV)", acc: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", ua)
        if (acc != null) b.header("Accept", acc)
        return Serwer.http.newCall(b.build()).execute().use { it.body?.string() ?: "" }
    }

    /** Zdjecie aktora (wolac w tle). */
    fun zdjecieAktora(nazwa: String): String {
        zdjecia[nazwa]?.let { return it }
        val w = try {
            val q = nazwa.lowercase().trim().replace(Regex("\\s+"), "_")
            val arr = JSONObject(tekst("https://v2.sg.media-imdb.com/suggestion/" + (q.firstOrNull()?.takeIf { it.isLetter() } ?: 'x') + "/" + java.net.URLEncoder.encode(q, "UTF-8") + ".json")).optJSONArray("d")
            var u = ""
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optString("id").startsWith("nm")) continue
                imdbId.putIfAbsent(nazwa, o.optString("id"))
                u = o.optJSONObject("i")?.optString("imageUrl") ?: ""
                if (u.isNotEmpty()) { u = u.replace(Regex("\\._V1_.*\\.jpg$"), "._V1_UX400_.jpg"); break }
            }
            u
        } catch (e: Exception) { "" }
        zdjecia[nazwa] = w
        return w
    }

    private fun sparql(q: String) = try {
        JSONObject(tekst("https://query.wikidata.org/sparql?format=json&query=" + java.net.URLEncoder.encode(q, "UTF-8"), acc = "application/sparql-results+json"))
            .optJSONObject("results")?.optJSONArray("bindings") ?: org.json.JSONArray()
    } catch (e: Exception) { org.json.JSONArray() }

    /** Zwiastun YouTube (id) i role aktorow wg numeru TMDb filmu. */
    fun wikidata(tmdb: String): Pair<String, Map<String, String>> {
        val id = tmdb.filter { it.isDigit() }
        if (id.isEmpty()) return "" to emptyMap()
        val w = "?f wdt:P4947 \"$id\"."
        val yt = sparql("SELECT ?yt WHERE { $w ?f wdt:P1651 ?yt. } LIMIT 1").let { a -> if (a.length() > 0) a.getJSONObject(0).optJSONObject("yt")?.optString("value") ?: "" else "" }
        val ob = sparql("SELECT ?aL (SAMPLE(?rl) AS ?rL) WHERE { $w ?f p:P161 ?st. ?st ps:P161 ?a. ?a rdfs:label ?aL. FILTER(lang(?aL)=\"en\") OPTIONAL { ?st pq:P453 ?r. ?r rdfs:label ?rl. FILTER(lang(?rl) IN (\"pl\",\"en\")) } OPTIONAL { ?st pq:P4633 ?rn. BIND(STR(?rn) AS ?rl) } } GROUP BY ?aL LIMIT 40")
        val role = HashMap<String, String>()
        for (i in 0 until ob.length()) { val o = ob.getJSONObject(i); val a = o.optJSONObject("aL")?.optString("value") ?: continue; val r = o.optJSONObject("rL")?.optString("value") ?: ""; if (r.isNotEmpty()) role[a] = r }
        return yt to role
    }

    /** Zwiastun z YouTube po tytule (gdy Wikidata nie ma). */
    fun szukajZwiastuna(tytul: String): String = try {
        val html = tekst("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("$tytul zwiastun", "UTF-8"),
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1) ?: ""
    } catch (e: Exception) { "" }
    /** Filmografia aktora z Wikidata: (tytul pl, tytul en, rok), najnowsze pierwsze. */
    fun filmografia(nazwa: String): List<Triple<String, String, Int>> {
        val n = nazwa.trim().replace("\"", "")
        if (n.isEmpty()) return emptyList()
        // Najpierw po numerze IMDb aktora (pewne i szybkie), a gdy brak - po nazwisku.
        if (!imdbId.containsKey(nazwa)) zdjecieAktora(nazwa)
        val nm = imdbId[nazwa] ?: ""
        val ktos = if (nm.isNotEmpty()) "?a wdt:P345 \"$nm\"." else "?a rdfs:label \"$n\"@en; wdt:P31 wd:Q5."
        val a = sparql("SELECT ?f (SAMPLE(?pl) AS ?p) (SAMPLE(?en) AS ?e) (MIN(?r) AS ?rok) WHERE { $ktos ?f wdt:P161 ?a. OPTIONAL { ?f rdfs:label ?pl. FILTER(lang(?pl)=\"pl\") } OPTIONAL { ?f rdfs:label ?en. FILTER(lang(?en)=\"en\") } OPTIONAL { ?f wdt:P577 ?d. BIND(YEAR(?d) AS ?r) } } GROUP BY ?f LIMIT 400")
        val out = ArrayList<Triple<String, String, Int>>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val p = o.optJSONObject("p")?.optString("value") ?: ""
            val e = o.optJSONObject("e")?.optString("value") ?: ""
            if (p.isEmpty() && e.isEmpty()) continue
            out.add(Triple(p, e, o.optJSONObject("rok")?.optString("value")?.toIntOrNull() ?: 0))
        }
        return out.distinctBy { it.first + "|" + it.second }.sortedByDescending { it.third }
    }

    /** Tytul do porownan: male litery, bez roku w nawiasie, bez przedrostkow typu "PL - ", tylko litery i cyfry. */
    fun klucz(t: String): String = t.lowercase()
        .replace(Regex("^[a-z]{2,3}\\s*[-|:]\\s*"), "")
        .replace(Regex("[(\\[]\\s*\\d{4}\\s*[)\\]]"), "")
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    /** Zapas jak w starej aplikacji: opis, gatunek, rok i okladka z publicznej bazy iTunes (bez klucza). */
    fun itunes(tytul: String, serial: Boolean): JSONObject? = try {
        val rok = Regex("\\((\\d{4})\\)").find(tytul)?.groupValues?.get(1)?.toIntOrNull()
        val czysty = tytul.replace(Regex("\\(\\d{4}\\)"), " ").replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "")
            .replace(Regex("\\b(napisy|lektor|dubbing|pl|4k|hd|fhd|uhd)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
        fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val media = if (serial) "&media=tvShow&entity=tvSeason" else "&media=movie&entity=movie"
        val arr = JSONObject(tekst("https://itunes.apple.com/search?term=" + java.net.URLEncoder.encode(czysty, "UTF-8") + media + "&country=PL&limit=6")).optJSONArray("results")
        var wynik: JSONObject? = null
        if (arr != null && czysty.length >= 2) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val nazwa = if (serial) o.optString("artistName").ifEmpty { o.optString("collectionName") } else o.optString("trackName")
            val r = o.optString("releaseDate").take(4).toIntOrNull()
            if (n(nazwa) != n(czysty) && !n(nazwa).contains(n(czysty)) && !n(czysty).contains(n(nazwa))) continue
            if (!serial && rok != null && r != null && kotlin.math.abs(rok - r) > 1) continue
            wynik = JSONObject().put("name", tytul)
                .put("plot", o.optString("longDescription").ifEmpty { o.optString("description") }.ifEmpty { o.optString("shortDescription") })
                .put("genre", o.optString("primaryGenreName")).put("releasedate", o.optString("releaseDate").take(10))
                .put("cover_big", o.optString("artworkUrl100").replace("100x100bb", "600x900bb"))
            break
        }
        wynik
    } catch (e: Exception) { null }
}
