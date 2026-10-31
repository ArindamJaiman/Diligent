package dev.diligent.app.insights

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One day of everything Insights looks at. Null = not measured that day. */
data class DayFacts(
    val date: LocalDate,
    /** Share of due goals met, 0..1 (null when nothing was due). */
    val completion: Double?,
    val hours: Double,
    val sleepHours: Double? = null,
    val steps: Int? = null,
    val mood: Int? = null,
    val energy: Int? = null,
    val distractingMinutes: Int? = null,
    val mindfulMinutes: Int? = null,
    val focusSessions: Int = 0,
    /** Activities done that day (ids). */
    val done: Set<Long> = emptySet()
)

/** "On days you sleep 7 h+, you hit 82% of goals vs 54%" (Exist, Daylio). */
data class Correlation(
    val factor: String,
    val highLabel: String,
    val lowLabel: String,
    /** Mean completion on high-factor days and low-factor days, 0..1. */
    val high: Double,
    val low: Double,
    val days: Int,
    /** Pearson r between the factor and completion. */
    val r: Double
) {
    val difference: Double get() = high - low
    fun sentence(): String =
        "On days with $highLabel you hit ${(high * 100).roundToInt()}% of goals, vs ${(low * 100).roundToInt()}% with $lowLabel."
}

object Correlations {

    const val MIN_DAYS = 10

    fun pearson(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size != ys.size || xs.size < 3) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        xs.indices.forEach { i ->
            num += (xs[i] - mx) * (ys[i] - my)
            dx += (xs[i] - mx) * (xs[i] - mx)
            dy += (ys[i] - my) * (ys[i] - my)
        }
        return if (dx == 0.0 || dy == 0.0) 0.0 else num / sqrt(dx * dy)
    }

    /**
     * Splits days at [threshold] of a factor and compares goal completion. Null when fewer
     * than [MIN_DAYS] days have both, or either side has fewer than 3.
     */
    fun split(
        days: List<DayFacts>,
        factor: String,
        value: (DayFacts) -> Double?,
        threshold: Double,
        highLabel: String,
        lowLabel: String
    ): Correlation? {
        val pairs = days.mapNotNull { d -> val v = value(d); val c = d.completion; if (v == null || c == null) null else v to c }
        if (pairs.size < MIN_DAYS) return null
        val high = pairs.filter { it.first >= threshold }.map { it.second }
        val low = pairs.filter { it.first < threshold }.map { it.second }
        if (high.size < 3 || low.size < 3) return null
        return Correlation(factor, highLabel, lowLabel, high.average(), low.average(), pairs.size,
            pearson(pairs.map { it.first }, pairs.map { it.second }))
    }

    /** Every factor we can test, strongest first; weak ones (under 8 points) are dropped. */
    fun all(days: List<DayFacts>): List<Correlation> = listOfNotNull(
        split(days, "sleep", { it.sleepHours }, 7.0, "7 h+ of sleep", "less sleep"),
        split(days, "steps", { it.steps?.toDouble() }, 6000.0, "6,000+ steps", "fewer steps"),
        split(days, "mood", { it.mood?.toDouble() }, 4.0, "a good mood (4–5)", "a lower mood"),
        split(days, "energy", { it.energy?.toDouble() }, 4.0, "high energy", "lower energy"),
        split(days, "scroll", { it.distractingMinutes?.let { m -> -m.toDouble() } }, -60.0, "under 1 h of scrolling", "1 h+ of scrolling"),
        split(days, "mindful", { it.mindfulMinutes?.toDouble() }, 10.0, "10+ mindful minutes", "less"),
        split(days, "focus", { it.focusSessions.toDouble() }, 2.0, "2+ focus sessions", "fewer sessions")
    ).filter { abs(it.difference) >= 0.08 }.sortedByDescending { abs(it.difference) }

    /**
     * "On Gym days you also do Quantum Computing 78% of the time, vs 41%": how doing one
     * activity goes with doing another. Pairs need [MIN_DAYS] days and a 20-point gap.
     */
    fun companions(days: List<DayFacts>, names: Map<Long, String>): List<String> {
        if (days.size < MIN_DAYS) return emptyList()
        val out = mutableListOf<Pair<Double, String>>()
        names.keys.forEach { a ->
            names.keys.filter { it != a }.forEach { b ->
                val withA = days.filter { a in it.done }
                val withoutA = days.filter { a !in it.done }
                if (withA.size < 4 || withoutA.size < 4) return@forEach
                val p1 = withA.count { b in it.done } / withA.size.toDouble()
                val p0 = withoutA.count { b in it.done } / withoutA.size.toDouble()
                if (p1 - p0 >= 0.2) {
                    out += (p1 - p0) to "On ${names[a]} days you also do ${names[b]} ${(p1 * 100).roundToInt()}% of the time, vs ${(p0 * 100).roundToInt()}% otherwise."
                }
            }
        }
        return out.sortedByDescending { it.first }.take(4).map { it.second }
    }
}

/** Today's readiness, 0–100, after Whoop recovery / Oura readiness, plus a burnout check. */
data class Readiness(
    val score: Int,
    /** Hours logged over the last 7 days vs the 4-week weekly average (1.0 = usual). */
    val load: Double,
    val sleepDebtHours: Double?,
    val burnoutRisk: Boolean,
    val reasons: List<String>
)

object ReadinessMath {

    /** [days] newest last; today is the last element (may be partly logged). */
    fun compute(days: List<DayFacts>, sleepDebtMinutes: Int?): Readiness {
        if (days.isEmpty()) return Readiness(50, 1.0, null, false, emptyList())
        val today = days.last()
        val yesterday = days.getOrNull(days.size - 2)
        val reasons = mutableListOf<String>()
        var score = 0.0
        var weight = 0.0
        fun add(part: Double, w: Double) {
            score += part.coerceIn(0.0, 1.0) * w
            weight += w
        }

        today.sleepHours?.let { h ->
            add(h / 8.0, 30.0)
            if (h < 6) reasons += "short sleep (%.1f h)".format(h)
        }
        sleepDebtMinutes?.let { debt ->
            add(1.0 - debt / (10 * 60.0), 15.0)
            if (debt > 5 * 60) reasons += "sleep debt ${debt / 60} h"
        }
        yesterday?.completion?.let { add(it, 20.0) }
        val mood = listOfNotNull(today.mood ?: yesterday?.mood, today.energy ?: yesterday?.energy)
        if (mood.isNotEmpty()) add((mood.average() - 1) / 4.0, 15.0)
        yesterday?.distractingMinutes?.let { add(1.0 - it / 240.0, 5.0) }

        val last7 = days.takeLast(7).sumOf { it.hours }
        val previous = days.dropLast(7).takeLast(28)
        val baseline = if (previous.size >= 14) previous.sumOf { it.hours } / previous.size * 7 else 0.0
        val load = if (baseline > 0) last7 / baseline else 1.0
        // Load well above your usual costs readiness; a little above is fine.
        add(if (load <= 1.15) 1.0 else 1.0 - (load - 1.15) * 2, 15.0)
        if (load > 1.3) reasons += "load ${(load * 100).roundToInt()}% of usual"

        val final = if (weight == 0.0) 50 else (score / weight * 100).roundToInt()
        val lowMood = days.takeLast(3).mapNotNull { it.mood }.let { it.size >= 2 && it.average() <= 2.0 }
        val burnout = (load > 1.3 && (sleepDebtMinutes ?: 0) > 5 * 60) || (lowMood && load > 1.1)
        if (lowMood) reasons += "mood low for days"
        return Readiness(final.coerceIn(0, 100), load, sleepDebtMinutes?.let { it / 60.0 }, burnout, reasons)
    }

    fun label(score: Int): String = when {
        score >= 80 -> "Primed · go deep"
        score >= 60 -> "Steady · normal day"
        score >= 40 -> "Low · lighter goals"
        else -> "Recover · rest and reset"
    }
}

/** A badge: earned or with progress towards it. */
data class Achievement(val id: String, val title: String, val detail: String, val progress: Float, val earned: Boolean)

/** Inputs for achievements and levels. */
data class Lifetime(
    val bestStreak: Int,
    /** Lifetime hours per time activity, by name. */
    val hoursBySkill: Map<String, Double>,
    val sessions: Int,
    val intactInARow: Int,
    val perfectWeeks: Int,
    val earlySessions: Int,
    val lateSessions: Int,
    val completions: Int,
    val daysTracked: Int
)

object Achievements {

    val MASTERY = listOf(10, 100, 1_000, 10_000)

    fun list(life: Lifetime): List<Achievement> = buildList {
        listOf(7, 30, 100, 365).forEach { n ->
            add(Achievement("streak_$n", "$n-day streak", "Keep any activity going $n days", (life.bestStreak / n.toFloat()).coerceAtMost(1f), life.bestStreak >= n))
        }
        listOf(10, 100, 500).forEach { n ->
            add(Achievement("sessions_$n", "$n focus sessions", "Finish $n timed sessions", (life.sessions / n.toFloat()).coerceAtMost(1f), life.sessions >= n))
        }
        add(Achievement("stake_10", "Unbroken ×10", "10 focus sessions in a row without breaking the stake", (life.intactInARow / 10f).coerceAtMost(1f), life.intactInARow >= 10))
        add(Achievement("perfect_week", "Perfect week", "Every due goal met, Monday to Sunday", if (life.perfectWeeks > 0) 1f else 0f, life.perfectWeeks > 0))
        add(Achievement("early", "Early bird", "5 sessions started before 8 AM", (life.earlySessions / 5f).coerceAtMost(1f), life.earlySessions >= 5))
        add(Achievement("late", "Night owl", "5 sessions started after 10 PM", (life.lateSessions / 5f).coerceAtMost(1f), life.lateSessions >= 5))
        life.hoursBySkill.forEach { (skill, hours) ->
            val next = MASTERY.firstOrNull { hours < it } ?: MASTERY.last()
            val reached = MASTERY.lastOrNull { hours >= it }
            add(
                Achievement(
                    "mastery_$skill", "$skill · ${reached?.let { "${"%,d".format(it)} h" } ?: "apprentice"}",
                    if (hours >= MASTERY.last()) "Mastered" else "${hours.roundToInt()} of ${"%,d".format(next)} h",
                    (hours / next).toFloat().coerceAtMost(1f), reached != null
                )
            )
        }
    }

    /** XP: 10 per hour, 5 per completed day, 2 per session. */
    fun xp(life: Lifetime): Int = (life.hoursBySkill.values.sum() * 10 + life.completions * 5 + life.sessions * 2).roundToInt()

    /** Level n needs 50·n² XP (Habitica-style curve). Returns (level, progress to next 0..1). */
    fun level(xp: Int): Pair<Int, Float> {
        var level = 1
        while (50 * (level + 1) * (level + 1) <= xp) level++
        val floor = 50 * level * level
        val ceiling = 50 * (level + 1) * (level + 1)
        return level to ((xp - floor).toFloat() / (ceiling - floor)).coerceIn(0f, 1f)
    }
}

/** This period vs the one before (Strava, Garmin), per activity. */
data class Comparison(val name: String, val unit: String, val current: Double, val previous: Double) {
    val change: Double? get() = if (previous == 0.0) null else (current - previous) / previous
}

object PeriodCompare {
    fun compare(current: Map<Long, Double>, previous: Map<Long, Double>, names: Map<Long, Pair<String, String>>): List<Comparison> =
        names.map { (id, nameUnit) -> Comparison(nameUnit.first, nameUnit.second, current[id] ?: 0.0, previous[id] ?: 0.0) }
            .filter { it.current > 0 || it.previous > 0 }
            .sortedByDescending { it.current }
}
