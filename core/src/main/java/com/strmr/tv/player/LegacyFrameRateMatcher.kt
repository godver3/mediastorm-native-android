package com.strmr.tv.player

import android.view.Display
import android.view.Window
import com.strmr.tv.app.AppLog
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The frame rate matching the app had before Match Frame Rate became a setting (Nov 18), kept
 * for A/B tests (Playback Debugging › Old Frame Rate Matcher): the container's rate only, the
 * loosest fitting mode (23.976 may land on 24 Hz), switched mid-playback without a pause.
 */
class LegacyFrameRateMatcher(private val window: Window, private val display: Display?) {
    private var applied = false

    fun match(frameRate: Float) {
        val display = display ?: return
        if (frameRate <= 0 || applied) return
        applied = true
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { mode -> multiple(mode.refreshRate, frameRate)?.let { mode to it } }
            .minWithOrNull(compareBy({ it.second }, { -it.first.refreshRate }))
            ?.first ?: return
        if (best.modeId == current.modeId) return
        AppLog.i("FrameRate", "legacy: content ${"%.3f".format(frameRate)} fps → mode ${best.modeId} ${"%.3f".format(best.refreshRate)} Hz")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    fun reset() {
        if (!applied) return
        applied = false
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    }

    private fun multiple(refresh: Float, fps: Float): Int? {
        val ratio = refresh / fps
        val whole = ratio.roundToInt()
        return whole.takeIf { it >= 1 && abs(ratio - whole) < 0.01f }
    }
}
