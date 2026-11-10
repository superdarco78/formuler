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
            val arr = JSONArray(get(url))
            val out = ArrayList<Channel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val logo = o.optString("stream_icon").takeIf { it.isNotEmpty() && it != "null" }
                out.add(
                    Channel(
                        streamId = o.optString("stream_id"),
                        name = o.optString("name"),
                        logo = logo,
                        categoryId = o.optString("category_id"),
                        num = o.optInt("num", i + 1)
                    )
                )
            }
            out
        }

    /** Buduje adres strumienia na zywo. */
    fun liveStreamUrl(server: String, user: String, pass: String, streamId: String): String {
        return "${baseUrl(server)}/live/$user/$pass/$streamId.m3u8"
    }

    /** Stały serwer aplikacji (wbudowany, klient go nie wpisuje). */
    const val HOME = "tv.ok-ok.uk"

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

    /** Lista filmow (VOD). Kazdy film ma gotowy adres do odtwarzania w directUrl. */
    suspend fun vodStreams(server: String, user: String, pass: String, categoryId: String?): List<Channel> =
        withContext(Dispatchers.IO) {
            var url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_vod_streams"
            if (!categoryId.isNullOrEmpty()) url += "&category_id=$categoryId"
            val arr = JSONArray(get(url))
            val out = ArrayList<Channel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("stream_id")
                val ext = o.optString("container_extension").ifEmpty { "mp4" }
                val poster = o.optString("stream_icon").takeIf { it.isNotEmpty() && it != "null" }
                val movieUrl = "${baseUrl(server)}/movie/$user/$pass/$id.$ext"
                out.add(
                    Channel(
                        streamId = id,
                        name = o.optString("name"),
                        logo = poster,
                        categoryId = o.optString("category_id"),
                        num = o.optInt("num", i + 1),
                        directUrl = movieUrl,
                        rating = o.optString("rating_5based").toDoubleOrNull() ?: o.optString("rating").toDoubleOrNull() ?: 0.0
                    )
                )
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
            val arr = JSONArray(get("${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series"))
            val out = ArrayList<Channel>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cover = o.optString("cover").takeIf { it.isNotEmpty() && it != "null" }
                out.add(Channel(o.optString("series_id"), o.optString("name"), cover,
                    o.optString("category_id"), i + 1, null,
                    o.optString("rating_5based").toDoubleOrNull() ?: o.optString("rating").toDoubleOrNull() ?: 0.0))
            }
            out
        }

    /** Odcinki serialu - kazdy ma gotowy adres w directUrl. */
    suspend fun seriesEpisodes(server: String, user: String, pass: String, seriesId: String): List<Channel> =
        withContext(Dispatchers.IO) {
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"
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
            val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$seriesId"
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
                        l.add(Channel(id, "Odcinek ${o.optString("episode_num")} — ${o.optString("title")}", null, null, i + 1,
                            "${baseUrl(server)}/series/$user/$pass/$id.$ext"))
                    }
                    out.add("Sezon $sez" to l)
                }
            }
            vi to out
        }

    /** Szczegoly filmu (opis, obsada, ocena, zwiastun) - to, co wpisano w edytorze. */
    suspend fun vodInfo(server: String, user: String, pass: String, streamId: String): VodInfo? =
        withContext(Dispatchers.IO) {
            try {
                val url = "${baseUrl(server)}/player_api.php?username=$user&password=$pass&action=get_vod_info&vod_id=$streamId"
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
                    trailer = t("youtube_trailer")
                )
            } catch (e: Exception) { null }
        }
}

data class VodInfo(
    val poster: String, val plot: String, val cast: String, val genre: String,
    val year: String, val rating: String, val duration: String, val trailer: String
)
