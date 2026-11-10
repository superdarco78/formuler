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
                        directUrl = movieUrl
                    )
                )
            }
            out
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
