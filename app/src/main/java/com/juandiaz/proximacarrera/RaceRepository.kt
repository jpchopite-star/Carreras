package com.juandiaz.proximacarrera

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** One session of a race weekend (practice, qualifying, sprint, race). */
data class Session(val key: String, val label: String, val start: Instant)

data class Race(
    val season: Int,
    val round: Int,
    val name: String,
    val circuit: String,
    val locality: String,
    val country: String,
    /** Sorted by start time; the race itself is always last. */
    val sessions: List<Session>,
) {
    val race: Session get() = sessions.last()
}

/**
 * Season calendar from Jolpica (the community successor of the Ergast F1 API).
 * Fetched at most every few hours and cached, so the wallpaper works offline.
 */
object RaceRepository {

    private const val BASE = "https://api.jolpi.ca/ergast/f1/"

    /** A race counts as "next" until ~2 h after lights out, so it shows "EN CARRERA" meanwhile. */
    val RACE_LENGTH: Duration = Duration.ofHours(2)

    private val SESSION_KEYS = listOf(
        "FirstPractice" to "Práctica 1",
        "SecondPractice" to "Práctica 2",
        "ThirdPractice" to "Práctica 3",
        "SprintQualifying" to "Clasificación sprint",
        "SprintShootout" to "Clasificación sprint",
        "Sprint" to "Sprint",
        "Qualifying" to "Clasificación",
    )

    // ---- network ----

    /** Downloads this season (and next season if this one is over) and caches it. */
    fun refresh(ctx: Context) {
        val current = get(BASE + "current.json")
        val races = parse(current)
        var next = ""
        if (nextRace(races, Instant.now()) == null) {
            val year = (races.maxOfOrNull { it.season } ?: ZonedDateTime.now().year) + 1
            next = runCatching { get("$BASE$year.json") }.getOrDefault("")
        }
        prefs(ctx).edit()
            .putString("season", current)
            .putString("season_next", next)
            .putLong("fetched", System.currentTimeMillis())
            .apply()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "ProximaCarrera/1.0 (Android live wallpaper)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---- cache ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("calendar", Context.MODE_PRIVATE)

    fun lastFetch(ctx: Context): Long = prefs(ctx).getLong("fetched", 0)

    fun load(ctx: Context): List<Race> {
        val p = prefs(ctx)
        val a = p.getString("season", "") ?: ""
        val b = p.getString("season_next", "") ?: ""
        return (runCatching { parse(a) }.getOrDefault(emptyList()) +
                runCatching { parse(b) }.getOrDefault(emptyList()))
            .sortedBy { it.race.start }
    }

    fun nextRace(races: List<Race>, now: Instant): Race? =
        races.firstOrNull { it.race.start.plus(RACE_LENGTH).isAfter(now) }

    // ---- parsing ----

    fun parse(json: String): List<Race> {
        if (json.isBlank()) return emptyList()
        val arr = JSONObject(json).getJSONObject("MRData")
            .getJSONObject("RaceTable").getJSONArray("Races")
        val out = ArrayList<Race>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val sessions = ArrayList<Session>()
            for ((key, label) in SESSION_KEYS) {
                val s = r.optJSONObject(key) ?: continue
                instant(s.optString("date"), s.optString("time"))?.let { sessions += Session(key, label, it) }
            }
            val raceStart = instant(r.optString("date"), r.optString("time")) ?: continue
            sessions.sortBy { it.start }
            sessions += Session("Race", "Carrera", raceStart)

            val circuit = r.optJSONObject("Circuit")
            val loc = circuit?.optJSONObject("Location")
            out += Race(
                season = r.optString("season").toIntOrNull() ?: 0,
                round = r.optString("round").toIntOrNull() ?: 0,
                name = r.optString("raceName"),
                circuit = circuit?.optString("circuitName").orEmpty(),
                locality = loc?.optString("locality").orEmpty(),
                country = loc?.optString("country").orEmpty(),
                sessions = sessions.distinctBy { it.key to it.start },
            )
        }
        return out
    }

    /** "2026-10-04" + "07:00:00Z" → Instant. A missing time means the time isn't announced yet. */
    private fun instant(date: String, time: String): Instant? {
        if (date.isBlank()) return null
        return runCatching {
            if (time.isBlank()) LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()
            else Instant.parse("${date}T${if (time.endsWith("Z")) time else time + "Z"}")
        }.getOrNull()
    }
}

/** User choices from the settings screen. */
object WallpaperSettings {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val ACCENTS = listOf(
        "Rojo" to 0xFFFF3B30.toInt(),
        "Amarillo" to 0xFFFFD453.toInt(),
        "Cian" to 0xFF00E0C6.toInt(),
        "Blanco" to 0xFFF2F2F5.toInt(),
    )

    fun accentIndex(ctx: Context) = prefs(ctx).getInt("accent", 0).coerceIn(0, ACCENTS.lastIndex)
    fun setAccentIndex(ctx: Context, i: Int) = prefs(ctx).edit().putInt("accent", i).apply()

    /** 0 = arriba, 1 = centro, 2 = abajo */
    fun position(ctx: Context) = prefs(ctx).getInt("position", 0).coerceIn(0, 2)
    fun setPosition(ctx: Context, i: Int) = prefs(ctx).edit().putInt("position", i).apply()

    fun showSeconds(ctx: Context) = prefs(ctx).getBoolean("seconds", true)
    fun setShowSeconds(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("seconds", v).apply()

    fun showTrack(ctx: Context) = prefs(ctx).getBoolean("track", true)
    fun setShowTrack(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("track", v).apply()

    fun showCredit(ctx: Context) = prefs(ctx).getBoolean("credit", true)
    fun setShowCredit(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("credit", v).apply()
}
