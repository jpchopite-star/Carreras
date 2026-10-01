package com.juandiaz.proximacarrera

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the whole wallpaper on a Canvas. Shared by the live wallpaper and the in-app preview.
 *
 * Layout (top → bottom inside the text block):
 *   eyebrow · GP name · circuit · big countdown · unit labels · session line · session dots · signature
 * Behind it: a faint telemetry grid and an abstract "circuit" (unique per round) with a glowing car.
 */
class RaceRenderer(private val ctx: Context) {

    private val ve = Locale.forLanguageTag("es-VE")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", ve)

    // ---- state ----
    private var races: List<Race> = emptyList()
    private var sessionIndex = -1           // -1 = race (default); else index into race.sessions
    private var lastTapMs = 0L
    var xOffset = 0.5f                      // home-screen page position, for parallax
    var loading = true

    private var accent = WallpaperSettings.ACCENTS[0].second
    private var position = 0
    private var showSeconds = true
    private var showTrack = true
    private var showCredit = true

    // ---- paints ----
    private val condensedBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val bg = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = Color.WHITE; alpha = 10; strokeWidth = 1f }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; alpha = 34; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }
    private val lightOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 26 }

    private var glowKey = ""

    // track cache
    private var trackKey = ""
    private var trackPath = Path()
    private var trackMeasure = PathMeasure()
    private val segment = Path()
    private val pos = FloatArray(2)

    // ---- public API ----

    fun reloadSettings() {
        accent = WallpaperSettings.ACCENTS[WallpaperSettings.accentIndex(ctx)].second
        position = WallpaperSettings.position(ctx)
        showSeconds = WallpaperSettings.showSeconds(ctx)
        showTrack = WallpaperSettings.showTrack(ctx)
        showCredit = WallpaperSettings.showCredit(ctx)
        trackKey = "" // colours changed → rebuild glow
    }

    fun reloadData() {
        races = RaceRepository.load(ctx)
        sessionIndex = -1
    }

    /** Tap on the wallpaper: show the next session of the weekend (returns to the race after 12 s). */
    fun cycleSession() {
        val race = RaceRepository.nextRace(races, Instant.now()) ?: return
        val n = race.sessions.size
        sessionIndex = if (sessionIndex < 0) 0 else sessionIndex + 1
        if (sessionIndex >= n - 1) sessionIndex = -1 // last one is the race itself
        lastTapMs = System.currentTimeMillis()
    }

    fun draw(c: Canvas, w: Int, h: Int, nowMs: Long) {
        if (w <= 0 || h <= 0) return
        val u = w / 1080f                       // design unit: 1080-wide canvas
        val now = Instant.ofEpochMilli(nowMs)
        if (sessionIndex >= 0 && nowMs - lastTapMs > 12_000) sessionIndex = -1

        val race = RaceRepository.nextRace(races, now)
        val parallax = (xOffset - 0.5f) * -80f * u

        drawBackground(c, w, h, u, parallax)
        if (race != null && showTrack) drawTrack(c, w, h, u, parallax, race, nowMs)

        val margin = 72f * u
        val top = when (position) { 0 -> 0.17f; 1 -> 0.33f; else -> 0.50f } * h
        if (race == null) {
            drawEmpty(c, w, top, u, margin)
            return
        }
        drawInfo(c, w, top, u, margin, race, now, nowMs)
    }

    // ---- layers ----

    private fun drawBackground(c: Canvas, w: Int, h: Int, u: Float, parallax: Float) {
        bg.color = 0xFF07080C.toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        // soft accent glow, bottom-right
        val gk = "$w-$h-$accent"
        if (gk != glowKey) {
            glow.shader = RadialGradient(
                w * 0.8f, h * 0.78f, w * 0.9f,
                (accent and 0x00FFFFFF) or (0x2A shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            glowKey = gk
        }
        c.save()
        c.translate(parallax, 0f)
        c.drawRect(-w.toFloat(), 0f, w * 2f, h.toFloat(), glow)
        c.restore()
        // telemetry grid
        val step = 90f * u
        var x = (parallax % step) - step
        while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), grid); x += step }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y, grid); y += step }
    }

    private fun drawTrack(c: Canvas, w: Int, h: Int, u: Float, parallax: Float, race: Race, nowMs: Long) {
        val key = "${race.season}-${race.round}-$w-$h-$accent"
        if (key != trackKey) {
            trackPath = buildTrack(race.season * 100 + race.round, w, h)
            trackMeasure = PathMeasure(trackPath, true)
            trailPaint.color = accent
            carPaint.color = accent
            carGlow.color = accent
            carGlow.alpha = 90
            carGlow.maskFilter = BlurMaskFilter(22f * u, BlurMaskFilter.Blur.NORMAL)
            trackKey = key
        }
        c.save()
        c.translate(parallax * 0.5f, 0f)

        trackPaint.strokeWidth = 14f * u
        c.drawPath(trackPath, trackPaint)

        val len = trackMeasure.length
        if (len <= 0f) { c.restore(); return }
        val lapMs = 14_000f
        val d = ((nowMs % lapMs.toLong()) / lapMs) * len
        val trail = len * 0.10f

        // trail, drawn as a few segments fading out behind the car
        trailPaint.strokeWidth = 6f * u
        val pieces = 8
        for (i in 0 until pieces) {
            val a = d - trail * (i + 1) / pieces
            val b = d - trail * i / pieces
            trailPaint.alpha = (220 * (1f - i / pieces.toFloat())).toInt()
            segmentOf(a, b, len)
            c.drawPath(segment, trailPaint)
        }

        // start/finish line
        trackMeasure.getPosTan(0f, pos, null)
        text.color = Color.WHITE; text.alpha = 140
        c.drawRect(pos[0] - 3f * u, pos[1] - 14f * u, pos[0] + 3f * u, pos[1] + 14f * u, text)

        // the car
        trackMeasure.getPosTan(d, pos, null)
        c.drawCircle(pos[0], pos[1], 26f * u, carGlow)
        c.drawCircle(pos[0], pos[1], 9f * u, carPaint)
        c.restore()
    }

    private fun segmentOf(a: Float, b: Float, len: Float) {
        segment.reset()
        var s = a; var e = b
        while (s < 0) { s += len; e += len }
        if (e <= len) trackMeasure.getSegment(s, e, segment, true)
        else {
            trackMeasure.getSegment(s, len, segment, true)
            trackMeasure.getSegment(0f, e - len, segment, true)
        }
    }

    private fun drawEmpty(c: Canvas, w: Int, top: Float, u: Float, margin: Float) {
        label(c, if (loading) "CARGANDO CALENDARIO…" else "SIN CALENDARIO", margin, top, 30f * u, accent, medium, 0.2f)
        label(c, "Conéctate a internet una vez", margin, top + 60f * u, 34f * u, 0xFFB8BCC8.toInt(), condensed, 0f)
    }

    private fun drawInfo(c: Canvas, w: Int, top: Float, u: Float, margin: Float, race: Race, now: Instant, nowMs: Long) {
        val session = if (sessionIndex in race.sessions.indices) race.sessions[sessionIndex] else race.race
        val isRace = session.key == "Race"
        val muted = 0xFFB8BCC8.toInt()
        val width = w - margin * 2
        var y = top

        // eyebrow
        val eyebrow = (if (isRace) "PRÓXIMA CARRERA" else "PRÓXIMA SESIÓN") + " · RONDA ${race.round} · ${race.season}"
        label(c, eyebrow, margin, y, 28f * u, accent, medium, 0.18f)
        y += 78f * u

        // GP name, shrunk to fit one line
        val name = race.name.uppercase(ve)
        val nameSize = fit(name, width, 74f * u, 40f * u, condensedBold, 0.02f)
        label(c, name, margin, y, nameSize, Color.WHITE, condensedBold, 0.02f)
        y += 50f * u

        val place = listOf(race.circuit, listOf(race.locality, race.country).filter { it.isNotBlank() }.joinToString(", "))
            .filter { it.isNotBlank() }.joinToString(" · ")
        val placeSize = fit(place, width, 32f * u, 22f * u, condensed, 0f)
        label(c, place, margin, y, placeSize, muted, condensed, 0f)
        y += 60f * u

        // countdown
        val remaining = Duration.between(now, session.start)
        val digitSize = 168f * u
        y += digitSize * 0.8f
        when {
            !remaining.isNegative -> {
                drawCountdown(c, margin, y, width, u, digitSize, remaining)
                y += 50f * u
            }
            isRace && now.isBefore(session.start.plus(RaceRepository.RACE_LENGTH)) -> {
                val pulse = 0.55f + 0.45f * abs(sin(nowMs / 600.0)).toFloat()
                val a = (255 * pulse).toInt()
                label(c, "EN CARRERA", margin, y, digitSize * 0.72f, (accent and 0x00FFFFFF) or (a shl 24), condensedBold, 0.02f)
                y += 50f * u
            }
            else -> {
                label(c, "FINALIZADA", margin, y, digitSize * 0.72f, 0x66FFFFFF, condensedBold, 0.02f)
                y += 50f * u
            }
        }

        // starting lights: last 5 minutes before the race, one light per minute
        val secs = remaining.seconds
        if (isRace && secs in 0..300) {
            val lit = min(5, 5 - (secs / 60).toInt())
            lightOn.color = 0xFFFF2A1F.toInt()
            val r = 26f * u
            val gap = 22f * u
            var cx = margin + r
            val cy = y + 24f * u
            for (i in 0 until 5) {
                c.drawCircle(cx, cy, r, if (i < lit) lightOn else lightOff)
                cx += r * 2 + gap
            }
            y += r * 2 + 30f * u
        }

        // session line
        y += 40f * u
        val local = session.start.atZone(ZoneId.systemDefault()).format(dayFmt)
            .replace(".", "").replace("a m", "a. m.").replace("p m", "p. m.")
        label(c, "${session.label.uppercase(ve)}  ·  $local", margin, y, 32f * u, Color.WHITE, medium, 0.04f)

        // session dots (which one is showing) + hint
        y += 46f * u
        val dotR = 6f * u
        var dx = margin + dotR
        val current = if (sessionIndex < 0) race.sessions.lastIndex else sessionIndex
        for (i in race.sessions.indices) {
            text.color = if (i == current) accent else Color.WHITE
            text.alpha = if (i == current) 255 else 60
            c.drawCircle(dx, y - dotR, dotR, text)
            dx += dotR * 2 + 14f * u
        }
        label(c, "toca el fondo para ver otras sesiones", dx + 10f * u, y, 22f * u, 0x55FFFFFF, condensed, 0f)

        if (showCredit) {
            y += 54f * u
            label(c, "By Juan Diaz", margin, y, 20f * u, 0x4DFFFFFF, condensed, 0.06f)
        }
    }

    private fun drawCountdown(c: Canvas, x0: Float, baseline: Float, width: Float, u: Float, size: Float, d: Duration) {
        val days = d.toDays()
        val hrs = d.toHours() % 24
        val mins = d.toMinutes() % 60
        val secs = d.seconds % 60
        val values = mutableListOf(days to "DÍAS", hrs to "HRS", mins to "MIN")
        if (showSeconds) values += secs to "SEG"

        // shrink digits if they don't fit (e.g. 3-digit days)
        val parts = values.map { (v, _) -> if (v >= 100) v.toString() else "%02d".format(v) }
        val sep = " : "
        text.typeface = condensedBold
        text.letterSpacing = 0f
        var sz = size
        text.textSize = sz
        while (text.measureText(parts.joinToString(sep)) > width && sz > 60f) { sz -= 4f; text.textSize = sz }

        var x = x0
        for ((i, p) in parts.withIndex()) {
            text.color = Color.WHITE
            text.typeface = condensedBold
            text.textSize = sz
            c.drawText(p, x, baseline, text)
            val pw = text.measureText(p)
            // unit label under each group
            text.color = 0x80FFFFFF.toInt()
            text.typeface = medium
            text.textSize = 22f * u
            text.letterSpacing = 0.2f
            c.drawText(values[i].second, x + 4f * u, baseline + 44f * u, text)
            text.letterSpacing = 0f
            x += pw
            if (i < parts.lastIndex) {
                text.color = accent
                text.typeface = condensedBold
                text.textSize = sz
                c.drawText(sep, x, baseline, text)
                x += text.measureText(sep)
            }
        }
    }

    // ---- helpers ----

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface, spacing: Float) {
        text.typeface = tf
        text.textSize = size
        text.letterSpacing = spacing
        text.color = color
        c.drawText(s, x, y, text)
        text.letterSpacing = 0f
    }

    private fun fit(s: String, width: Float, max: Float, min: Float, tf: Typeface, spacing: Float): Float {
        text.typeface = tf
        text.letterSpacing = spacing
        var size = max
        text.textSize = size
        while (text.measureText(s) > width && size > min) { size -= 2f; text.textSize = size }
        text.letterSpacing = 0f
        return size
    }

    /**
     * An abstract closed "circuit": jittered points around an ellipse, smoothed with
     * Catmull-Rom curves. Seeded by season+round, so every Grand Prix gets its own shape.
     */
    private fun buildTrack(seed: Int, w: Int, h: Int): Path {
        val rnd = Random(seed)
        val n = 11
        val pts = Array(n) { i ->
            val a = 2 * PI * i / n + rnd.nextDouble(-0.18, 0.18)
            val r = rnd.nextDouble(0.62, 1.0)
            floatArrayOf((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
        }
        val p = Path()
        fun pt(i: Int) = pts[(i + n) % n]
        p.moveTo(pt(0)[0], pt(0)[1])
        for (i in 0 until n) {
            val p0 = pt(i - 1); val p1 = pt(i); val p2 = pt(i + 1); val p3 = pt(i + 2)
            p.cubicTo(
                p1[0] + (p2[0] - p0[0]) / 6f, p1[1] + (p2[1] - p0[1]) / 6f,
                p2[0] - (p3[0] - p1[0]) / 6f, p2[1] - (p3[1] - p1[1]) / 6f,
                p2[0], p2[1],
            )
        }
        p.close()
        // place it in the lower part of the screen, behind the app icons
        val m = Matrix()
        val boxW = w * 0.46f
        val boxH = min(h * 0.17f, boxW * 0.85f)
        m.setScale(boxW, boxH)
        m.postTranslate(w * 0.5f, h * 0.76f)
        p.transform(m)
        return p
    }

    @Suppress("unused") private fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))
}
