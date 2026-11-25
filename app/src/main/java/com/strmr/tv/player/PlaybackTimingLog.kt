package com.strmr.tv.player

import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.FrameMetrics
import android.view.Window
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.strmr.tv.app.AppLog
import com.strmr.tv.ui.settings.PlaybackDebugFlag
import com.strmr.tv.ui.settings.PlaybackDebugFlags

/**
 * Stutter diagnostics the decoder's counters can't show: the display's mode at the start, at
 * every change and each minute; how evenly frames are released ([FrameTiming]); and how many
 * interface frames were drawn over the video (a hidden chrome should draw almost none). Wraps
 * [matcher], as the player takes a single frame listener.
 */
@OptIn(UnstableApi::class)
class PlaybackTimingLog(
    private val window: Window,
    private val display: Display?,
    private val player: ExoPlayer,
    private val matcher: VideoFrameMetadataListener?,
) : VideoFrameMetadataListener, DisplayManager.DisplayListener, Window.OnFrameMetricsAvailableListener {
    private val logsFrames = PlaybackDebugFlags.on(PlaybackDebugFlag.FRAME_TIMING_LOG)
    private val logsDisplay = PlaybackDebugFlags.on(PlaybackDebugFlag.DISPLAY_LOG)
    private val main = Handler(Looper.getMainLooper())
    private val timing = FrameTiming()
    private val displays = window.context.getSystemService(DisplayManager::class.java)
    @Volatile private var speed = 1f
    private var uiFrames = 0
    private var uiSlow = 0
    private var lastMode = ""
    private val speedListener = object : Player.Listener {
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed = playbackParameters.speed
        }
    }
    private val minute = object : Runnable {
        override fun run() {
            report("")
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        AppLog.i(TAG, "flags: ${PlaybackDebugFlags.summary()}")
        speed = player.playbackParameters.speed
        if (logsFrames) player.addListener(speedListener)
        when {
            logsFrames -> player.setVideoFrameMetadataListener(this)
            matcher != null -> player.setVideoFrameMetadataListener(matcher)
        }
        if (logsDisplay) {
            lastMode = mode()
            AppLog.i(TAG, "display $lastMode; modes " + display?.supportedModes.orEmpty().joinToString(" ") {
                "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${"%.3f".format(java.util.Locale.US, it.refreshRate)}"
            })
            displays?.registerDisplayListener(this, main)
            window.addOnFrameMetricsAvailableListener(this, main)
        }
        if (logsFrames || logsDisplay) main.postDelayed(minute, TICK_MS)
    }

    /** Before the player is released. */
    fun stop() {
        main.removeCallbacks(minute)
        player.removeListener(speedListener)
        if (logsFrames || logsDisplay) report("end: ")
        if (logsDisplay) {
            displays?.unregisterDisplayListener(this)
            runCatching { window.removeOnFrameMetricsAvailableListener(this) }
        }
    }

    private fun report(prefix: String) {
        val at = player.currentPosition / 1000
        if (logsFrames) timing.drain()?.let { AppLog.i(TAG, "${prefix}at ${at}s frames $it") }
        if (logsDisplay && (player.isPlaying || prefix.isNotEmpty())) {
            AppLog.i(TAG, "${prefix}at ${at}s display ${mode()} uiFrames=$uiFrames slow=$uiSlow")
        }
        uiFrames = 0
        uiSlow = 0
    }

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        matcher?.onVideoFrameAboutToBeRendered(presentationTimeUs, releaseTimeNs, format, mediaFormat)
        timing.frame(presentationTimeUs, releaseTimeNs, System.nanoTime(), speed)
    }

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != display?.displayId) return
        val now = mode()
        if (now == lastMode) return
        AppLog.i(TAG, "display $lastMode → $now at ${player.currentPosition / 1000}s")
        lastMode = now
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCountSinceLastInvocation: Int) {
        uiFrames += 1 + dropCountSinceLastInvocation
        if (metrics.getMetric(FrameMetrics.TOTAL_DURATION) > SLOW_NS) uiSlow++
    }

    private fun mode(): String = display?.let {
        val m = it.mode
        "${m.modeId}:${m.physicalWidth}x${m.physicalHeight}@${"%.3f".format(java.util.Locale.US, m.refreshRate)} " +
            "refresh=${"%.3f".format(java.util.Locale.US, it.refreshRate)} preferred=${window.attributes.preferredDisplayModeId}"
    } ?: "unknown"

    private companion object {
        const val TAG = "Timing"
        const val TICK_MS = 60_000L
        const val SLOW_NS = 33_000_000L
    }
}
