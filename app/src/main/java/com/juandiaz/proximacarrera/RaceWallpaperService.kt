package com.juandiaz.proximacarrera

import android.app.WallpaperManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Live wallpaper: animates only while visible, so it costs no battery with the screen off or an app open. */
class RaceWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = RaceEngine()

    inner class RaceEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = RaceRenderer(this@RaceWallpaperService)
        private val fetching = AtomicBoolean(false)
        private var visible = false
        private var w = 0
        private var h = 0

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, FRAME_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            renderer.reloadSettings()
            renderer.reloadData()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame)
            if (v) {
                renderer.reloadSettings() // pick up changes made in the settings screen
                maybeRefresh()
                handler.post(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            super.onDestroy()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int
        ) {
            renderer.xOffset = xOffset
        }

        /** The launcher sends a "tap" command when you tap empty wallpaper. */
        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                renderer.cycleSession()
                drawFrame()
            }
            return null
        }

        /** Downloads the calendar if it's old, missing, or the cached race is over. */
        private fun maybeRefresh() {
            val ctx = this@RaceWallpaperService
            val age = System.currentTimeMillis() - RaceRepository.lastFetch(ctx)
            val races = RaceRepository.load(ctx)
            val stale = races.isEmpty() || age > 6 * 3600_000L ||
                RaceRepository.nextRace(races, Instant.now()) == null
            if (!stale || !fetching.compareAndSet(false, true)) return
            renderer.loading = true
            Thread {
                runCatching { RaceRepository.refresh(ctx) }
                handler.post {
                    renderer.loading = false
                    renderer.reloadData()
                    fetching.set(false)
                }
            }.start()
        }

        private fun drawFrame() {
            if (w == 0 || h == 0) return
            val holder = surfaceHolder
            val c = runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                renderer.draw(c, w, h, System.currentTimeMillis())
            } finally {
                runCatching { holder.unlockCanvasAndPost(c) }
            }
        }
    }

    companion object {
        private const val FRAME_MS = 33L // ~30 fps while visible
    }
}
