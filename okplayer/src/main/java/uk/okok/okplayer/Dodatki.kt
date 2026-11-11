package uk.okok.okplayer

import okhttp3.Request
import org.json.JSONObject

/** Dodatkowe dane filmow z publicznych zrodel bez klucza: zdjecia aktorow (IMDb), zwiastun i role (Wikidata). */
object Dodatki {
    /** Jezyk i kraj urzadzenia: opisy, biografie i tytuly pobierane w jezyku telefonu / dekodera (zapas: angielski). */
    private val J: String get() = java.util.Locale.getDefault().language.ifEmpty { "en" }
    private val KRAJ: String get() = java.util.Locale.getDefault().country.ifEmpty { if (J == "en") "US" else J.uppercase() }
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
        val ob = sparql("SELECT ?aL (SAMPLE(?rl) AS ?rL) WHERE { $w ?f p:P161 ?st. ?st ps:P161 ?a. ?a rdfs:label ?aL. FILTER(lang(?aL)=\"en\") OPTIONAL { ?st pq:P453 ?r. ?r rdfs:label ?rl. FILTER(lang(?rl) IN (\"$J\",\"en\")) } OPTIONAL { ?st pq:P4633 ?rn. BIND(STR(?rn) AS ?rl) } } GROUP BY ?aL LIMIT 40")
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
    /** Okladki filmow z iTunes (po kluczu tytulu) - do siatki filmow aktora. */
    val okladki = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun okladka(t: String): String? = okladki[klucz(t)]

    /** Filmografia z dwoch zrodel naraz (dziala tez przy aktorach z setkami filmow):
     *  1) iTunes - polskie tytuly i lata, 2) Wikidata - lekkie zapytanie o same filmy + nazwy pobierane paczkami po 50. */
    fun filmografia(nazwa: String): List<Triple<String, String, Int>> {
        val n = nazwa.trim().replace("\"", "")
        if (n.isEmpty()) return emptyList()
        val out = ArrayList<Triple<String, String, Int>>()
        // 1) iTunes: filmy z tym aktorem (polski sklep -> polskie tytuly)
        try {
            for (kraj in listOf(KRAJ, "US").distinct()) {
                val arr = JSONObject(tekst("https://itunes.apple.com/search?term=" + java.net.URLEncoder.encode(n, "UTF-8") + "&media=movie&attribute=actorTerm&limit=200&country=$kraj")).optJSONArray("results")
                if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                    val t = o.optString("trackName"); if (t.isEmpty()) continue
                    o.optString("artworkUrl100").takeIf { it.isNotEmpty() }?.let { okladki.putIfAbsent(klucz(t), it.replace("100x100bb", "300x450bb")) }
                    out.add(if (kraj == KRAJ) Triple(t, "", o.optString("releaseDate").take(4).toIntOrNull() ?: 0) else Triple("", t, o.optString("releaseDate").take(4).toIntOrNull() ?: 0)) }
            }
        } catch (_: Exception) { }
        // 2) Wikidata: najpierw same numery filmow (szybko), potem nazwy po polsku i angielsku
        try {
            if (!imdbId.containsKey(nazwa)) zdjecieAktora(nazwa)
            val nm = imdbId[nazwa] ?: ""
            val ktos = if (nm.isNotEmpty()) "?a wdt:P345 \"$nm\"." else "?a rdfs:label \"$n\"@en; wdt:P31 wd:Q5."
            val a = sparql("SELECT ?f (MIN(YEAR(?d)) AS ?rok) WHERE { $ktos ?f wdt:P161 ?a. OPTIONAL { ?f wdt:P577 ?d. } } GROUP BY ?f LIMIT 600")
            val lata = HashMap<String, Int>()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); val q = o.optJSONObject("f")?.optString("value")?.substringAfterLast("/") ?: continue
                lata[q] = o.optJSONObject("rok")?.optString("value")?.toIntOrNull() ?: 0 }
            for (paczka in lata.keys.chunked(50)) {
                val j = JSONObject(tekst("https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&props=labels&languages=$J%7Cen&ids=" + paczka.joinToString("%7C")))
                val ent = j.optJSONObject("entities") ?: continue
                for (q in paczka) { val l = ent.optJSONObject(q)?.optJSONObject("labels") ?: continue
                    val p = l.optJSONObject(J)?.optString("value") ?: ""; val e = l.optJSONObject("en")?.optString("value") ?: ""
                    if (p.isNotEmpty() || e.isNotEmpty()) out.add(Triple(p, e, lata[q] ?: 0)) }
            }
        } catch (_: Exception) { }
        // Jedna pozycja na film (po tytule), najnowsze pierwsze
        val wynik = LinkedHashMap<String, Triple<String, String, Int>>()
        for (f in out) {
            val k = klucz(f.first.ifEmpty { f.second }) + "|" + (f.third / 2)
            val stary = wynik[k]
            wynik[k] = if (stary == null) f else Triple(stary.first.ifEmpty { f.first }, stary.second.ifEmpty { f.second }, maxOf(stary.third, f.third))
        }
        return wynik.values.sortedByDescending { it.third }
    }

    private val rePrzedrostek = Regex("^[a-z]{2,3}\\s*[-|:]\\s*")
    private val reRok = Regex("[(\\[]\\s*\\d{4}\\s*[)\\]]")
    private val reZnaki = Regex("[^\\p{L}\\p{Nd}]")
    /** Szybkie porownywanie tytulow (wyrazenia przygotowane raz, nie przy kazdym tytule). */
    fun klucz(t: String): String = t.lowercase().replace(rePrzedrostek, "").replace(reRok, "").replace(reZnaki, "")

    /** Zapas jak w starej aplikacji: opis, gatunek, rok i okladka z publicznej bazy iTunes (bez klucza). */
    /** Zwiastun z iTunes (krotki film .m4v), gdy nie ma go na serwerze ani w Wikidata. Zwraca adres filmu albo "". */
    fun zwiastunItunes(tytul: String): String = try {
        val czysty = tytul.replace(Regex("\\(\\d{4}\\)"), " ").replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "")
            .replace(Regex("\\b(napisy|lektor|dubbing|pl|4k|hd|fhd|uhd)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
        fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        var u = ""
        for (kraj in listOf(KRAJ, "US")) {
            val arr = JSONObject(tekst("https://itunes.apple.com/search?term=" + java.net.URLEncoder.encode(czysty, "UTF-8") + "&media=movie&entity=movie&country=$kraj&limit=8")).optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); val p = o.optString("previewUrl")
                if (p.startsWith("http") && (n(o.optString("trackName")).contains(n(czysty)) || n(czysty).contains(n(o.optString("trackName"))))) { u = p; break } }
            if (u.isNotEmpty()) break
        }
        u
    } catch (e: Exception) { "" }

    /** Pogoda bez klucza: miejsce z adresu IP (ipwho.is), temperatura i niebo z Open-Meteo. Zwraca (miasto, "12°", ikona) albo null. */
    fun pogoda(): Triple<String, String, String>? = try {
        val ip = JSONObject(tekst("https://ipwho.is/?lang=$J"))
        val lat = ip.optDouble("latitude"); val lon = ip.optDouble("longitude")
        val w = JSONObject(tekst("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day")).optJSONObject("current")
        if (w == null) null else {
            val k = w.optInt("weather_code"); val dzien = w.optInt("is_day", 1) == 1
            val ik = when (k) { 0 -> if (dzien) "☀️" else "🌙"; 1, 2 -> if (dzien) "🌤️" else "☁️"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "❄️"; in 95..99 -> "⛈️"; else -> "🌡️" }
            Triple(ip.optString("city"), Math.round(w.optDouble("temperature_2m")).toString() + "°", ik)
        }
    } catch (e: Exception) { null }

    fun itunes(tytul: String, serial: Boolean): JSONObject? = try {
        val rok = Regex("\\((\\d{4})\\)").find(tytul)?.groupValues?.get(1)?.toIntOrNull()
        val czysty = tytul.replace(Regex("\\(\\d{4}\\)"), " ").replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "")
            .replace(Regex("\\b(napisy|lektor|dubbing|pl|4k|hd|fhd|uhd)\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ").trim()
        fun n(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val media = if (serial) "&media=tvShow&entity=tvSeason" else "&media=movie&entity=movie"
        val arr = JSONObject(tekst("https://itunes.apple.com/search?term=" + java.net.URLEncoder.encode(czysty, "UTF-8") + media + "&country=$KRAJ&limit=6")).optJSONArray("results")
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

    /** Dane aktora: data i miejsce urodzenia, zawod, krotka biografia z Wikipedii (po polsku, a gdy brak - po angielsku). */
    fun bio(nazwa: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            if (!imdbId.containsKey(nazwa)) zdjecieAktora(nazwa)
            val nm = imdbId[nazwa] ?: ""
            val ktos = if (nm.isNotEmpty()) "?a wdt:P345 \"$nm\"." else "?a rdfs:label \"${nazwa.replace("\"", "")}\"@en; wdt:P31 wd:Q5."
            val a = sparql("SELECT ?ur ?mL ?smL ?plw ?enw WHERE { $ktos OPTIONAL { ?a wdt:P569 ?ur. } OPTIONAL { ?a wdt:P19 ?m. ?m rdfs:label ?mL. FILTER(lang(?mL) IN (\"$J\",\"en\")) } " +
                "OPTIONAL { ?a wdt:P570 ?sm. BIND(STR(?sm) AS ?smL) } OPTIONAL { ?plw schema:about ?a; schema:isPartOf <https://$J.wikipedia.org/>. } OPTIONAL { ?enw schema:about ?a; schema:isPartOf <https://en.wikipedia.org/>. } } LIMIT 1")
            if (a.length() > 0) {
                val o = a.getJSONObject(0)
                fun v(k: String) = o.optJSONObject(k)?.optString("value") ?: ""
                v("ur").take(10).takeIf { it.isNotEmpty() }?.let { d ->
                    val data = d.split("-").reversed().joinToString(".")
                    // Wiek (albo wiek w chwili smierci)
                    val wiek = try {
                        val ur = java.time.LocalDate.parse(d); val do_ = v("smL").take(10).takeIf { it.length == 10 }?.let { java.time.LocalDate.parse(it) } ?: java.time.LocalDate.now()
                        java.time.Period.between(ur, do_).years
                    } catch (_: Exception) { -1 }
                    val slowo = if (wiek % 10 in 2..4 && wiek % 100 !in 12..14) "lata" else "lat"
                    out["Urodzony"] = if (wiek > 0) "$data  ($wiek $slowo)" else data
                }
                v("mL").takeIf { it.isNotEmpty() }?.let { out["Miejsce urodzenia"] = it }
                v("smL").take(10).takeIf { it.isNotEmpty() }?.let { out["Zmarł"] = it.split("-").reversed().joinToString(".") }
                val wiki = v("plw").ifEmpty { v("enw") }
                if (wiki.isNotEmpty()) {
                    val host = wiki.substringBefore("/wiki/"); val tytul = wiki.substringAfter("/wiki/")
                    val j = JSONObject(tekst("$host/api/rest_v1/page/summary/$tytul"))
                    j.optString("extract").takeIf { it.isNotEmpty() }?.let { out["bio"] = it }
                    j.optString("description").takeIf { it.isNotEmpty() }?.let { out["Kim jest"] = it }
                    // Pelna biografia: caly artykul z Wikipedii (zwykly tekst, do ok. 8000 znakow)
                    try {
                        val q = JSONObject(tekst("$host/w/api.php?action=query&prop=extracts&explaintext=1&exsectionformat=plain&format=json&titles=$tytul"))
                        val strony = q.optJSONObject("query")?.optJSONObject("pages")
                        val tx = strony?.keys()?.asSequence()?.firstOrNull()?.let { strony.optJSONObject(it)?.optString("extract") } ?: ""
                        if (tx.length > (out["bio"]?.length ?: 0)) out["bio"] = tx.replace(Regex("\n{3,}"), "\n\n").take(8000)
                    } catch (_: Exception) { }
                }
            }
            // Dodatkowe dane: wzrost, malzonkowie, dzieci, nagrody
            val d = sparql("SELECT ?wz (GROUP_CONCAT(DISTINCT ?mzL; separator=\", \") AS ?mz) (COUNT(DISTINCT ?dz) AS ?ldz) (GROUP_CONCAT(DISTINCT ?ngL; separator=\", \") AS ?ng) WHERE { $ktos " +
                "OPTIONAL { ?a wdt:P2048 ?wz. } OPTIONAL { ?a wdt:P26 ?m. ?m rdfs:label ?mzL. FILTER(lang(?mzL)=\"en\") } OPTIONAL { ?a wdt:P40 ?dz. } " +
                "OPTIONAL { ?a wdt:P166 ?n. ?n rdfs:label ?ngL. FILTER(lang(?ngL) IN (\"$J\",\"en\")) } } GROUP BY ?wz LIMIT 1")
            if (d.length() > 0) {
                val o2 = d.getJSONObject(0)
                fun w(k: String) = o2.optJSONObject(k)?.optString("value") ?: ""
                w("wz").toDoubleOrNull()?.let { m -> out["Wzrost"] = if (m < 3) "${Math.round(m * 100)} cm" else "${Math.round(m)} cm" }
                w("mz").takeIf { it.isNotEmpty() }?.let { out["Małżonkowie"] = it }
                w("ldz").toIntOrNull()?.takeIf { it > 0 }?.let { out["Dzieci"] = it.toString() }
                w("ng").takeIf { it.isNotEmpty() }?.let { out["Nagrody"] = it.split(", ").take(8).joinToString(", ") }
            }
        } catch (_: Exception) { }
        return out
    }

    /** Uzupelnienie danych filmu/serialu z Wikidata i Wikipedii (po tytule i roku): rezyser, obsada z rolami, gatunek,
     *  kraj, czas, premiera, zwiastun, numer TMDb oraz opis z Wikipedii - gdy serwer ich nie ma. */
    fun filmWiki(tytulPelny: String, serial: Boolean): JSONObject? = try {
        val rok = Regex("\\((\\d{4})\\)").find(tytulPelny)?.groupValues?.get(1)
        val tytul = tytulPelny.replace(Regex("\\(\\d{4}\\)"), "").replace(Regex("^[A-Z]{2,3}\\s*[-|:]\\s*"), "")
            .replace(Regex("\\b(napisy|lektor|dubbing|4k|hd|fhd|uhd)\\b", RegexOption.IGNORE_CASE), "").replace("\"", "").replace(Regex("\\s+"), " ").trim()
        val typ = if (serial) "wd:Q5398426" else "wd:Q11424"
        val filtrRoku = if (rok != null && !serial) "OPTIONAL { ?f wdt:P577 ?d. } FILTER(!BOUND(?d) || ABS(YEAR(?d) - $rok) <= 1)" else ""
        val f = sparql("SELECT ?f WHERE { { ?f rdfs:label \"$tytul\"@pl } UNION { ?f rdfs:label \"$tytul\"@en } ?f wdt:P31/wdt:P279* $typ. $filtrRoku } LIMIT 1")
        if (f.length() == 0) null else {
            val q = f.getJSONObject(0).optJSONObject("f")?.optString("value")?.substringAfterLast("/") ?: ""
            val w = "wd:$q"
            fun lista(zap: String) = sparql(zap).let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optJSONObject("x")?.optString("value") }.distinct() }
            val rez = lista("SELECT ?x WHERE { $w wdt:P57 ?r. ?r rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 4")
            val gat = lista("SELECT ?x WHERE { $w wdt:P136 ?g. ?g rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 4")
            val kraj = lista("SELECT ?x WHERE { $w wdt:P495 ?k. ?k rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 3")
            val ob = sparql("SELECT ?aL (SAMPLE(?rl) AS ?rL) WHERE { $w p:P161 ?st. ?st ps:P161 ?a. ?a rdfs:label ?aL. FILTER(lang(?aL)=\"en\") OPTIONAL { ?st pq:P453 ?r. ?r rdfs:label ?rl. FILTER(lang(?rl) IN (\"$J\",\"en\")) } OPTIONAL { ?st pq:P4633 ?rn. BIND(STR(?rn) AS ?rl) } } GROUP BY ?aL LIMIT 80")
            val scen = lista("SELECT ?x WHERE { $w wdt:P58 ?r. ?r rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 4")
            val muz = lista("SELECT ?x WHERE { $w wdt:P86 ?r. ?r rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 3")
            val studio = lista("SELECT ?x WHERE { $w wdt:P272 ?r. ?r rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 3")
            val nagrody = lista("SELECT ?x WHERE { $w wdt:P166 ?r. ?r rdfs:label ?x. FILTER(lang(?x) IN (\"$J\",\"en\")) } LIMIT 6")
            val kasa = sparql("SELECT ?b ?bo ?ot WHERE { OPTIONAL { $w wdt:P2130 ?b. } OPTIONAL { $w wdt:P2142 ?bo. } OPTIONAL { $w wdt:P1476 ?ot. } } LIMIT 1")
            val inne = sparql("SELECT ?d ?czas ?yt ?tm ?plw WHERE { OPTIONAL { $w wdt:P577 ?d. } OPTIONAL { $w wdt:P2047 ?czas. } OPTIONAL { $w wdt:P1651 ?yt. } OPTIONAL { $w wdt:P4947 ?tm. } OPTIONAL { $w wdt:P4983 ?tm. } OPTIONAL { ?plw schema:about $w; schema:isPartOf <https://$J.wikipedia.org/>. } } LIMIT 1")
            val o = JSONObject()
            if (rez.isNotEmpty()) o.put("director", rez.joinToString(", "))
            if (gat.isNotEmpty()) o.put("genre", gat.joinToString(", "))
            if (scen.isNotEmpty()) o.put("writer", scen.distinct().joinToString(", "))
            if (muz.isNotEmpty()) o.put("composer", muz.distinct().joinToString(", "))
            if (kraj.isNotEmpty()) o.put("country", kraj.joinToString(", "))
            if (studio.isNotEmpty()) o.put("studio", studio.distinct().joinToString(", "))
            if (nagrody.isNotEmpty()) o.put("awards", nagrody.distinct().joinToString(", "))
            if (kasa.length() > 0) { val x = kasa.getJSONObject(0); fun v(k: String) = x.optJSONObject(k)?.optString("value") ?: ""
                fun mln(t: String) = t.toDoubleOrNull()?.let { d -> if (d >= 1e6) String.format(java.util.Locale.US, "%.1f mln $", d / 1e6) else String.format(java.util.Locale.US, "%,.0f $", d) } ?: ""
                mln(v("b")).takeIf { it.isNotEmpty() }?.let { o.put("budget", it) }
                mln(v("bo")).takeIf { it.isNotEmpty() }?.let { o.put("boxoffice", it) }
                v("ot").takeIf { it.isNotEmpty() }?.let { o.put("o_name", it) } }
            val nazwiska = ArrayList<String>(); val role = JSONObject()
            for (i in 0 until ob.length()) { val x = ob.getJSONObject(i); val a = x.optJSONObject("aL")?.optString("value") ?: continue; nazwiska.add(a); x.optJSONObject("rL")?.optString("value")?.takeIf { it.isNotEmpty() }?.let { role.put(a, it) } }
            if (nazwiska.isNotEmpty()) { o.put("cast", nazwiska.joinToString(", ")); o.put("_role", role) }
            if (inne.length() > 0) {
                val x = inne.getJSONObject(0); fun v(k: String) = x.optJSONObject(k)?.optString("value") ?: ""
                v("d").take(10).takeIf { it.isNotEmpty() }?.let { o.put("releasedate", it) }
                v("czas").toDoubleOrNull()?.let { o.put("duration", it.toInt().toString()) }
                v("yt").takeIf { it.isNotEmpty() }?.let { o.put("youtube_trailer", it) }
                v("tm").takeIf { it.isNotEmpty() }?.let { o.put("tmdb_id", it) }
                v("plw").takeIf { it.isNotEmpty() }?.let { u ->
                    try { val j = JSONObject(tekst(u.substringBefore("/wiki/") + "/api/rest_v1/page/summary/" + u.substringAfter("/wiki/"))); j.optString("extract").takeIf { it.isNotEmpty() }?.let { o.put("plot_wiki", it) } } catch (_: Exception) {}
                }
            }
            o
        }
    } catch (e: Exception) { null }
}

/** 2.26: wspolny odtwarzacz zajawek (jak glowny: VLC user-agent, przekierowania http<->https). */
object Zajawki {
    fun gracz(c: android.content.Context) = androidx.media3.exoplayer.ExoPlayer.Builder(c)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000).setReadTimeoutMs(12000))).build()
}
