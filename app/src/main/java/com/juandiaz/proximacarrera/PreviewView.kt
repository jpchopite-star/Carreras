package com.juandiaz.proximacarrera

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Live, scaled-down preview of the wallpaper inside the settings screen. Tap it to cycle sessions. */
class PreviewView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    val renderer = RaceRenderer(ctx)
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (running) postDelayed(this, 33)
        }
    }

    init {
        renderer.reloadSettings()
        renderer.reloadData()
        setOnClickListener { renderer.cycleSession(); invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        post(tick)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** Keep the phone's aspect ratio (S26 Ultra ≈ 19.5:9). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 19.5f / 9f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, System.currentTimeMillis())
    }
}
