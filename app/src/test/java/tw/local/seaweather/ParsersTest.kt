package tw.local.seaweather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/** township/tide/seazone.json are real API responses captured on 2026-11-01. */
class ParsersTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun liveTownshipForecastJoinsElementsByPeriod() {
        val periods = parseTownship(fixture("township.json"))
        assertEquals(32, periods.size)
        val first = periods.first()
        assertEquals("2026-11-01T12:00:00+08:00", first.start)
        assertEquals("晴", first.wx)
        assertEquals("31", first.temp)
        assertEquals("33", first.feels)
        assertEquals("10", first.pop)
        assertEquals("60", first.humidity)
        assertEquals("悶熱", first.comfort)
        assertEquals("2", first.windSpeed)
        assertEquals("2", first.beaufort)
        assertEquals("偏西風", first.windDir)
        assertEquals(periods.sortedBy { it.start }, periods)
    }

    @Test
    fun liveSeaZonesGiveDailyWaveByArea() {
        val zones = parseSeaZones(fixture("seazone.json"))
        assertEquals(setOf("彭佳嶼基隆海面", "花蓮沿海"), zones.keys)
        val days = zones.getValue("花蓮沿海")
        assertEquals(5, days.size)
        assertEquals("2026-11-01", days[0].date)
        assertTrue(days.all { it.wave.contains("公尺") && it.waveType.isNotEmpty() })
    }

    @Test
    fun tideCurvePassesThroughEventsAndIsSmoothBetween() {
        val marks = listOf(TideMark(0, 100, true, ""), TideMark(600, -100, false, ""))
        assertEquals(100.0, tideHeightAt(marks, 0)!!, 1e-9)
        assertEquals(0.0, tideHeightAt(marks, 300)!!, 1e-9)
        assertEquals(-100.0, tideHeightAt(marks, 600)!!, 1e-9)
        assertTrue(tideHeightAt(marks, 60)!! > 90)
        assertNull(tideHeightAt(marks, -1))
        assertNull(tideHeightAt(marks, 601))
    }

    @Test
    fun liveTideIsSortedWithHeights() {
        val days = parseTide(fixture("tide.json"))
        assertEquals(7, days.size)
        assertEquals(days.sortedBy { it.date }, days)
        val nov5 = days.first { it.date == "2026-11-05" }
        assertEquals("小", nov5.range)
        assertEquals("滿潮", nov5.events[0].tide)
        assertEquals("21", nov5.events[0].heightCm)
    }

    @Test
    fun historyMovesRepeatsToFrontAndStaysBounded() {
        assertEquals(listOf("B", "A", "C"), withVisit(listOf("A", "B", "C"), "B"))
        val full = (1..HISTORY_LIMIT).map { "P$it" }
        val next = withVisit(full, "new")
        assertEquals(HISTORY_LIMIT, next.size)
        assertEquals("new", next.first())
        assertEquals("P${HISTORY_LIMIT - 1}", next.last())
    }

    @Test
    fun timelineContinuesInThreeHourSlotsUntilTideEnds() {
        val forecast = Period("2026-11-01T12:00:00+08:00", "2026-11-01T15:00:00+08:00", "晴", "", "", "", "", "", "", "", "")
        val tide = listOf(
            TideDay("2026-11-02", "", "", listOf(TideEvent("2026-11-02T01:30:00+08:00", "滿潮", "10"))),
        )
        val timeline = withTideSlots(listOf(forecast), tide)
        assertEquals(forecast, timeline.first())
        assertEquals(5, timeline.size)
        assertEquals("", timeline.last().wx)
        assertTrue(OffsetDateTime.parse(timeline.last().end) > OffsetDateTime.parse("2026-11-02T01:30:00+08:00"))
        assertEquals(listOf(forecast), withTideSlots(listOf(forecast), emptyList()))
    }

    @Test
    fun dayLabelIncludesWeekday() {
        assertEquals("11/01 週日", dayLabel("2026-11-01"))
        assertEquals("11/07 週六", dayLabel("2026-11-07"))
    }

    @Test
    fun apiFailureAndGarbageBecomeApiError() {
        assertThrows(ApiError::class.java) { parseTownship("""{"success":"false","message":"no"}""") }
        assertThrows(ApiError::class.java) { parseTownship("<html>") }
    }
}
