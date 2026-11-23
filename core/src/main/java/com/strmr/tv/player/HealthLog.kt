package com.strmr.tv.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback health for "frames drop after ~20 minutes" reports: the video decoder in use (hardware
 * or software), each dropped-frame burst, and once a minute the drop count, the decoder's
 * rendered/skipped frames, memory (Java heap, native heap, system free) and thermal state. A
 * resource leak shows as memory climbing minute over minute; heat as thermal rising before drops.
 */
@OptIn(UnstableApi::class)
class HealthLog(context: Context, private val player: ExoPlayer) : AnalyticsListener {
    private val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var dropped = 0
    private var droppedAtLastTick = 0
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    fun stop() {
        job?.cancel()
        AppLog.i("Health", "end: dropped=$dropped ${frames()} ${resources()}")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        val format = player.videoFormat
        AppLog.i(
            "Health",
            "video decoder $decoderName (${kind(decoderName)}) for ${format?.sampleMimeType} " +
                "${format?.codecs} ${format?.width}x${format?.height}@${format?.frameRate}",
        )
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
        AppLog.w("Health", "dropped $droppedFrames frames in ${elapsedMs}ms at ${player.currentPosition / 1000}s (total $dropped) ${resources()}")
    }

    private fun tick() {
        if (!player.isPlaying) return
        val delta = dropped - droppedAtLastTick
        droppedAtLastTick = dropped
        AppLog.i("Health", "at ${player.currentPosition / 1000}s dropped+$delta=$dropped ${frames()} ${resources()}")
    }

    private fun frames(): String {
        val c: DecoderCounters = player.videoDecoderCounters ?: return ""
        c.ensureUpdated()
        return "rendered=${c.renderedOutputBufferCount} skipped=${c.skippedOutputBufferCount} " +
            "maxConsecDropped=${c.maxConsecutiveDroppedBufferCount} lateUs=${c.totalVideoFrameProcessingOffsetUs / maxOf(1, c.videoFrameProcessingOffsetCount)}"
    }

    private fun resources(): String {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val nativeMb = Debug.getNativeHeapAllocatedSize() / MB
        val sys = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        val thermal = if (Build.VERSION.SDK_INT >= 29) " thermal=${power.currentThermalStatus}" else ""
        return "heap=${heapMb}/${rt.maxMemory() / MB}MB native=${nativeMb}MB sysFree=${sys.availMem / MB}MB" +
            (if (sys.lowMemory) " LOW" else "") + thermal +
            " buffered=${player.totalBufferedDuration}ms"
    }

    private fun kind(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("c2.android.") || n.startsWith("omx.google.") || n.contains("ffmpeg") ||
                n.contains("dav1d") || n.contains("gav1") || n.contains("libvpx") -> "software"
            else -> "hardware"
        }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val MB = 1024 * 1024
    }
}
