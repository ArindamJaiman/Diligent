package dev.diligent.app.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Parsers and unit maths for the auto-log sources, free of Android so they can be unit tested. */
object AutoLogSources {

    private val json = Json { ignoreUnknownKeys = true }

    /** Amount in an activity's unit for [millis] of time, or null when the unit isn't time. */
    fun timeAmount(unit: String, millis: Long): Float? = when (unit.trim().lowercase()) {
        "hrs", "hr", "hours", "hour", "h" -> millis / 3_600_000f
        "mins", "min", "minutes" -> millis / 60_000f
        else -> null
    }

    /**
     * WakaTime GET /api/v1/users/current/summaries?start=…&end=… → total coded seconds.
     * One day in, one entry out; summed in case the range spans more.
     */
    fun wakaSeconds(body: String): Double {
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return 0.0
        return data.sumOf { day ->
            (day as? JsonObject)?.get("grand_total")?.jsonObject?.get("total_seconds")?.jsonPrimitive?.doubleOrNull ?: 0.0
        }
    }

    /**
     * Codeforces user.status → distinct problems with an accepted verdict submitted in
     * [from, to) (epoch seconds).
     */
    fun codeforcesSolved(body: String, from: Long, to: Long): Set<String> {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull != "OK") return emptySet()
        return root["result"]?.jsonArray.orEmpty().mapNotNull { element ->
            val sub = element as? JsonObject ?: return@mapNotNull null
            val time = sub["creationTimeSeconds"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (time < from || time >= to) return@mapNotNull null
            if (sub["verdict"]?.jsonPrimitive?.contentOrNull != "OK") return@mapNotNull null
            val problem = sub["problem"]?.jsonObject ?: return@mapNotNull null
            val contest = problem["contestId"]?.jsonPrimitive?.contentOrNull ?: problem["problemsetName"]?.jsonPrimitive?.contentOrNull
            val index = problem["index"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            "cf:$contest$index"
        }.toSet()
    }

    /** AtCoder Problems submissions API → distinct AC problems in [from, to) (epoch seconds). */
    fun atcoderSolved(body: String, from: Long, to: Long): Set<String> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val sub = element as? JsonObject ?: return@mapNotNull null
            val time = sub["epoch_second"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (time < from || time >= to) return@mapNotNull null
            if (sub["result"]?.jsonPrimitive?.contentOrNull != "AC") return@mapNotNull null
            sub["problem_id"]?.jsonPrimitive?.contentOrNull?.let { "ac:$it" }
        }.toSet()

    /** Milliseconds per activity from per-app usage and the apps linked to each activity. */
    fun usagePerActivity(usage: Map<String, Long>, links: Map<Long, Set<String>>): Map<Long, Long> =
        links.mapValues { (_, packages) -> packages.sumOf { usage[it] ?: 0L } }.filterValues { it > 0 }
}
