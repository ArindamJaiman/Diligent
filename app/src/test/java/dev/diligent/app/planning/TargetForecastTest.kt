package dev.diligent.app.planning

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TargetForecastTest {

    private val deadline = LocalDate.of(2026, 12, 31)

    @Test
    fun aheadAndFinishingEarly() {
        val pace = Pace(total = 120f, target = 200f, neededPerDay = 1.5f, expectedByNow = 116.5f, ahead = 3.5f,
            projectedFinish = LocalDate.of(2026, 12, 14), daysLeft = 55)
        assertEquals("▲ 3.5 hrs ahead · done by 14 Dec", TargetPace.forecastLine(pace, "hrs", deadline))
    }

    @Test
    fun behindShowsWhatEachDayNeeds() {
        val pace = Pace(total = 80f, target = 200f, neededPerDay = 2.43f, expectedByNow = 92f, ahead = -12f,
            projectedFinish = LocalDate.of(2027, 2, 1), daysLeft = 49)
        assertEquals("▼ 12 hrs behind · need 2.4/day", TargetPace.forecastLine(pace, "hrs", deadline))
    }

    @Test
    fun noRecentProgressFallsBackToNeededPerDay() {
        val pace = Pace(total = 10f, target = 60f, neededPerDay = 1f, expectedByNow = 10f, ahead = 0f, projectedFinish = null, daysLeft = 49)
        assertEquals("▲ 0 qs ahead · need 1/day", TargetPace.forecastLine(pace, "qs", deadline))
    }

    @Test
    fun reached() {
        val pace = Pace(total = 210f, target = 200f, neededPerDay = 0f, expectedByNow = 150f, ahead = 60f, projectedFinish = LocalDate.of(2026, 11, 6), daysLeft = 55)
        assertEquals("🎯 target reached · 210/200 hrs", TargetPace.forecastLine(pace, "hrs", deadline))
    }

    @Test
    fun computedPaceFeedsTheLine() {
        // 100 of 200 h at the halfway point of a 100-day target, 2 h a day lately
        val pace = TargetPace.compute(total = 100f, target = 200f, start = LocalDate.of(2026, 9, 22), deadline = deadline,
            today = LocalDate.of(2026, 11, 6), recentPerDay = 2f)
        assertEquals("▲ 10 hrs ahead · done by 26 Dec", TargetPace.forecastLine(pace, "hrs", deadline))
    }
}
