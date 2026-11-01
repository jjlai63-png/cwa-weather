package tw.local.seaweather

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TIDE_DAYS = 30L

class CwaClient(private val apiKey: String) {
    private val zone = ZoneId.of("Asia/Taipei")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun now() = LocalDateTime.now(zone)

    /** The dataset keeps periods that already ended; both sides are Taipei time, so text order works. */
    fun township(dataset: String, town: String): List<Period> {
        val current = now().format(stamp)
        return parseTownship(get(dataset, mapOf("LocationName" to town))).filter { it.end > current }
    }

    fun tide(locationId: String): List<TideDay> {
        val today = now().toLocalDate().atStartOfDay()
        return parseTide(
            get(
                "F-A0021-001",
                mapOf(
                    "LocationId" to locationId,
                    "timeFrom" to today.format(stamp),
                    "timeTo" to today.plusDays(TIDE_DAYS).format(stamp),
                ),
            ),
        )
    }

    /** About 6 MB for all of Taiwan, so callers keep the body instead of refetching per town. */
    fun coast(): String {
        val key = URLEncoder.encode(apiKey, "UTF-8")
        return read(URL("https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/F-D0047-095?Authorization=$key&downloadType=WEB&format=JSON"))
    }

    private fun get(dataset: String, params: Map<String, String>): String {
        val query = (params + ("Authorization" to apiKey) + ("format" to "JSON")).entries
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        // Exception text reaches the UI, so it must never include this URL: it carries the key.
        return read(URL("https://opendata.cwa.gov.tw/api/v1/rest/datastore/$dataset?$query"))
    }

    private fun read(url: URL): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        try {
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                401, 403 -> throw ApiError("授權碼不正確，請按右上角「授權碼」重新輸入")
                429 -> throw ApiError("查詢次數已達上限，請稍後再試")
                else -> throw ApiError("氣象署伺服器回應異常（$code）")
            }
        } catch (e: IOException) {
            throw ApiError("無法連線，請確認網路")
        } finally {
            conn.disconnect()
        }
    }
}
