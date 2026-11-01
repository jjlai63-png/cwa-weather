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
    fun liveCoastGivesThreeHourlySeaStateByPoint() {
        val points = parseCoast(fixture("coast.json"))
        assertEquals(setOf("中正區沿海", "綠島鄉_北方沿海"), points.keys)
        val slots = points.getValue("綠島鄉_北方沿海")
        assertEquals(4, slots.size)
        assertEquals(slots.sortedBy { it.time }, slots)
        assertEquals("2026-11-01T18:00:00+08:00", slots[0].time)
        assertTrue(slots.all { it.wave.toFloat() >= 0 && it.windDir.endsWith("風") && it.waveDir.isNotEmpty() })
    }

    @Test
    fun seaValuesReplaceLandWindAndUnmatchedPeriodsGoBlank() {
        val land = Period("2026-11-01T18:00:00+08:00", "2026-11-01T21:00:00+08:00", "晴", "", "", "", "", "", "9", "9", "偏北風")
        val later = land.copy(start = "2026-11-01T21:00:00+08:00", end = "2026-11-02T00:00:00+08:00")
        val slot = SeaSlot(land.start, "3", "2", "偏南風", "1.1", "偏東", "9.3", "0.3", "東南")
        val merged = withSea(listOf(land, later), listOf(slot))
        assertEquals("3", merged[0].windSpeed)
        assertEquals("1.1", merged[0].wave)
        assertEquals("", merged[1].windSpeed)
        assertEquals("晴", merged[1].wx)
        assertEquals(listOf(land), withSea(listOf(land), emptyList()))
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
