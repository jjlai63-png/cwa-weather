package tw.local.seaweather

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate
import java.time.OffsetDateTime

class ApiError(message: String) : Exception(message)

data class Period(
    val start: String,
    val end: String,
    val wx: String,
    val pop: String,
    val temp: String,
    val feels: String,
    val humidity: String,
    val comfort: String,
    val windSpeed: String,
    val beaufort: String,
    val windDir: String,
    val wave: String = "",
    val waveDir: String = "",
    val wavePeriod: String = "",
    val currentSpeed: String = "",
    val currentDir: String = "",
)

data class TideEvent(val time: String, val tide: String, val heightCm: String)
data class TideDay(val date: String, val lunar: String, val range: String, val events: List<TideEvent>)

data class SeaSlot(
    val time: String,
    val windSpeed: String,
    val beaufort: String,
    val windDir: String,
    val wave: String,
    val waveDir: String,
    val wavePeriod: String,
    val currentSpeed: String,
    val currentDir: String,
)
data class CoastReport(val periods: List<Period>, val tide: List<TideDay>, val hasSea: Boolean, val notes: List<String>)

private const val SHAPE_ERROR = "資料格式與預期不符，請回報此資料集"

/**
 * CWA datasets mix camelCase and PascalCase between versions, and only the
 * request side is documented publicly, so lookups ignore case and search by
 * key rather than by a fixed path.
 */
private fun JSONObject.ci(name: String): Any? {
    val key = keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
    return opt(key).takeUnless { it == null || it == JSONObject.NULL }
}

private fun JSONObject.text(name: String): String = when (val v = ci(name)) {
    null, is JSONObject, is JSONArray -> ""
    else -> v.toString().trim()
}

private fun JSONObject.obj(name: String): JSONObject? = ci(name) as? JSONObject

private fun items(v: Any?): List<JSONObject> = when (v) {
    is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it) as? JSONObject }
    is JSONObject -> listOf(v)
    else -> emptyList()
}

/** Every object below [v] (inclusive) that has [key]; does not descend into a match. */
private fun objectsWith(v: Any?, key: String): List<JSONObject> = when (v) {
    is JSONObject ->
        if (v.ci(key) != null) listOf(v)
        else v.keys().asSequence().toList().flatMap { objectsWith(v.opt(it), key) }
    is JSONArray -> (0 until v.length()).flatMap { objectsWith(v.opt(it), key) }
    else -> emptyList()
}

private fun root(body: String): JSONObject {
    val parsed = try {
        JSONTokener(body).nextValue()
    } catch (e: Exception) {
        throw ApiError(SHAPE_ERROR)
    }
    val json = parsed as? JSONObject ?: throw ApiError(SHAPE_ERROR)
    if (json.text("success").equals("false", ignoreCase = true)) {
        throw ApiError(json.text("message").ifEmpty { "氣象署回傳失敗" })
    }
    return json
}

/** Blank, "None", "-" and the -99-style sentinels all mean the sensor had no value. */
private fun usable(v: String): Boolean {
    if (v.isEmpty() || v.equals("None", true) || v.equals("null", true)) return false
    if (v.all { it == '-' } || v.equals("X", true)) return false
    val n = v.toDoubleOrNull() ?: return true
    return n > -99
}

/**
 * Elements in F-D0047 use different cadences (hourly temperature, 3-hourly
 * weather, 6-hourly rain later on), so everything joins on the weather period's start.
 */
fun parseTownship(body: String): List<Period> {
    val location = objectsWith(root(body), "WeatherElement").firstOrNull()
        ?: throw ApiError("查無此鄉鎮的預報")
    val series = items(location.ci("WeatherElement")).associate { e ->
        e.text("ElementName") to items(e.ci("Time")).associateBy {
            it.text("StartTime").ifEmpty { it.text("DataTime") }
        }
    }
    val base = series["天氣現象"].orEmpty()
    if (base.isEmpty()) throw ApiError(SHAPE_ERROR)
    fun value(element: String, field: String, start: String) =
        items(series[element]?.get(start)?.ci("ElementValue")).firstOrNull()?.text(field).orEmpty()
            .takeIf(::usable).orEmpty()
    return base.map { (start, t) ->
        Period(
            start = start,
            end = t.text("EndTime"),
            wx = value("天氣現象", "Weather", start),
            pop = value("3小時降雨機率", "ProbabilityOfPrecipitation", start),
            temp = value("溫度", "Temperature", start),
            feels = value("體感溫度", "ApparentTemperature", start),
            humidity = value("相對濕度", "RelativeHumidity", start),
            comfort = value("舒適度指數", "ComfortIndexDescription", start),
            windSpeed = value("風速", "WindSpeed", start),
            beaufort = value("風速", "BeaufortScale", start),
            windDir = value("風向", "WindDirection", start),
        )
    }.sortedBy { it.start }
}

/** F-D0047-095 is one file holding every coastal point, keyed here by point name. */
fun parseCoast(body: String): Map<String, List<SeaSlot>> =
    objectsWith(root(body), "LocationName").associate { point ->
        val elements = items(point.ci("WeatherElement")).associate { e ->
            e.text("ElementName") to items(e.ci("Time")).associateBy { it.text("DataTime") }
        }
        fun value(element: String, field: String, time: String) =
            elements[element]?.get(time)?.obj("ElementValue")?.text(field).orEmpty().takeIf(::usable).orEmpty()
        point.text("LocationName") to elements["浪高"].orEmpty().keys.sorted().map { t ->
            SeaSlot(
                time = t,
                windSpeed = value("風速", "WindSpeed", t),
                beaufort = value("風速", "BeaufortScale", t),
                windDir = value("風向", "WindDirection", t),
                wave = value("浪高", "WaveHeight", t),
                waveDir = value("浪向", "WaveDirection", t),
                wavePeriod = value("浪週期", "WavePeriod", t),
                currentSpeed = value("流速", "OceanCurrentSpeed", t),
                currentDir = value("流向", "OceanCurrentDirection", t),
            )
        }
    }

/**
 * Sea wind replaces the township's land wind. A period the coastal file does not
 * reach goes blank rather than showing land wind in a row labelled as sea wind.
 */
fun withSea(periods: List<Period>, slots: List<SeaSlot>): List<Period> {
    if (slots.isEmpty()) return periods
    val byTime = slots.associateBy { it.time }
    return periods.map { p ->
        val slot = byTime[p.start] ?: return@map p.copy(windSpeed = "", beaufort = "", windDir = "")
        p.copy(
            windSpeed = slot.windSpeed,
            beaufort = slot.beaufort,
            windDir = slot.windDir,
            wave = slot.wave,
            waveDir = slot.waveDir,
            wavePeriod = slot.wavePeriod,
            currentSpeed = slot.currentSpeed,
            currentDir = slot.currentDir,
        )
    }
}

/**
 * Forecasts stop after a few days but tide runs for a month, so the time axis
 * keeps going in 3-hour slots, with no forecast values, until the tide data ends.
 */
fun withTideSlots(periods: List<Period>, tide: List<TideDay>): List<Period> {
    val lastTide = tide.flatMap { it.events }.maxOfOrNull { it.time } ?: return periods
    var start = OffsetDateTime.parse(periods.lastOrNull()?.end ?: return periods)
    val limit = OffsetDateTime.parse(lastTide)
    val slots = mutableListOf<Period>()
    while (!start.isAfter(limit)) {
        val end = start.plusHours(3)
        slots += Period(start.toString(), end.toString(), "", "", "", "", "", "", "", "", "")
        start = end
    }
    return periods + slots
}

/** "2026-11-01" to "11/01 週日"; built by hand so it does not depend on the phone's language. */
fun dayLabel(date: String): String {
    val weekday = "日一二三四五六"[LocalDate.parse(date).dayOfWeek.value % 7]
    return "${date.drop(5).replace('-', '/')} 週$weekday"
}

fun parseTide(body: String): List<TideDay> {
    val days = objectsWith(root(body), "Date")
    return days.map { d ->
        TideDay(
            date = d.text("Date"),
            lunar = d.text("LunarDate"),
            range = d.text("TideRange"),
            events = objectsWith(d.ci("Time"), "DateTime").map { t ->
                TideEvent(
                    time = t.text("DateTime"),
                    tide = t.text("Tide"),
                    heightCm = t.obj("TideHeights")?.text("AboveLocalMSL").orEmpty(),
                )
            },
        )
    }.sortedBy { it.date }
}
