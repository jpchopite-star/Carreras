package com.juandiaz.proximacarrera

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** App screen: live preview, style options, and a button to apply the wallpaper. */
class SettingsActivity : Activity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)

        // Accent colour
        val accents = findViewById<RadioGroup>(R.id.accents)
        WallpaperSettings.ACCENTS.forEachIndexed { i, (name, color) ->
            val rb = RadioButton(this).apply {
                id = 1000 + i
                text = name
                setTextColor(color)
                textSize = 15f
                setPadding(8, 0, 24, 0)
            }
            accents.addView(rb)
        }
        accents.check(1000 + WallpaperSettings.accentIndex(this))
        accents.setOnCheckedChangeListener { _, id ->
            WallpaperSettings.setAccentIndex(this, id - 1000); applied()
        }

        // Vertical position
        val pos = findViewById<RadioGroup>(R.id.position)
        val posIds = listOf(R.id.pos_top, R.id.pos_center, R.id.pos_bottom)
        pos.check(posIds[WallpaperSettings.position(this)])
        pos.setOnCheckedChangeListener { _, id ->
            WallpaperSettings.setPosition(this, posIds.indexOf(id).coerceAtLeast(0)); applied()
        }

        bindSwitch(R.id.sw_seconds, WallpaperSettings.showSeconds(this)) { WallpaperSettings.setShowSeconds(this, it) }
        bindSwitch(R.id.sw_track, WallpaperSettings.showTrack(this)) { WallpaperSettings.setShowTrack(this, it) }
        bindSwitch(R.id.sw_credit, WallpaperSettings.showCredit(this)) { WallpaperSettings.setShowCredit(this, it) }

        findViewById<Button>(R.id.btn_apply).setOnClickListener { applyWallpaper() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { refresh() }

        refresh()
    }

    private fun bindSwitch(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on -> save(on); applied() }
    }

    private fun applied() = preview.renderer.reloadSettings()

    private fun refresh() {
        status.setText(R.string.loading)
        preview.renderer.loading = true
        Thread {
            val ok = runCatching { RaceRepository.refresh(this) }.isSuccess
            runOnUiThread {
                preview.renderer.loading = false
                preview.renderer.reloadData()
                val race = RaceRepository.nextRace(RaceRepository.load(this), java.time.Instant.now())
                status.text = when {
                    race != null -> getString(R.string.next_is, race.name, race.round)
                    ok -> getString(R.string.no_races)
                    else -> getString(R.string.offline)
                }
            }
        }.start()
    }

    private fun applyWallpaper() {
        val component = ComponentName(this, RaceWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                Toast.makeText(this, R.string.pick_manually, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.pick_manually, Toast.LENGTH_LONG).show()
            }
        }
    }
}
