package com.strmr.tv.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio diagnostics for "the picture froze with a full buffer" reports (a passthrough track that
 * opens but never plays): the selected audio format, the decoder (none = passthrough), the
 * AudioTrack that opened and where it is routed with what that output advertises, whether the
 * platform says this encoding can play direct there, when the audio clock first moves, and a
 * one-shot snapshot if the playhead stays still with plenty buffered. Logging only.
 */
@OptIn(UnstableApi::class)
class AudioHealthLog(context: Context, private val player: ExoPlayer) : AnalyticsListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var format: Format? = null
    private var decoder: String? = null
    private var track: AudioSink.AudioTrackConfig? = null
    private var trackOpenedAt = 0L
    private var advancing = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            var lastPosition = -1L
            var stillSince = 0L
            var reported = false
            while (isActive) {
                delay(1_000)
                val position = player.currentPosition
                val buffered = player.totalBufferedDuration
                val meantToPlay = player.playWhenReady && player.playbackSuppressionReason == 0
                if (!meantToPlay || position != lastPosition) {
                    lastPosition = position
                    stillSince = System.currentTimeMillis()
                    reported = false
                    continue
                }
                if (!reported && System.currentTimeMillis() - stillSince >= STILL_MS && buffered >= BUFFERED_MS) {
                    reported = true
                    AppLog.w(TAG, "playhead still ${(System.currentTimeMillis() - stillSince) / 1000}s at ${position / 1000}s " +
                        "with ${buffered}ms buffered; state=${player.playbackState} audioAdvancing=$advancing " +
                        "${describeTrack()} decoder=${decoder ?: "none (passthrough)"} ${counters()} route=${route()}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        this.format = format
        decoder = null
        AppLog.i(TAG, "input ${format.sampleMimeType} codecs=${format.codecs} ch=${format.channelCount} " +
            "rate=${format.sampleRate} lang=${format.language} label=${format.label} bitrate=${format.bitrate}")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        decoder = decoderName
        AppLog.i(TAG, "decoder $decoderName")
    }

    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        track = audioTrackConfig
        trackOpenedAt = System.currentTimeMillis()
        advancing = false
        AppLog.i(TAG, "track opened ${describeTrack()} decoder=${decoder ?: "none (passthrough)"} " +
            "route=${route()} direct=${direct(audioTrackConfig)}")
    }

    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        advancing = true
        AppLog.i(TAG, "audio clock advancing ${System.currentTimeMillis() - trackOpenedAt}ms after the track opened")
    }

    override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
        AppLog.i(TAG, "track released encoding=${audioTrackConfig.encoding} advanced=$advancing")
    }

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        AppLog.w(TAG, "underrun buffer=${bufferSizeMs}ms sinceLastFeed=${elapsedSinceLastFeedMs}ms")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        AppLog.w(TAG, "sink error ${audioSinkError.javaClass.simpleName}: ${audioSinkError.message}")
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        AppLog.w(TAG, "codec error ${audioCodecError.javaClass.simpleName}: ${audioCodecError.message}")
    }

    private fun describeTrack(): String {
        val t = track ?: return "track=none"
        val kind = if (Util.isEncodingLinearPcm(t.encoding)) "pcm" else "bitstream"
        return "track=$kind encoding=${t.encoding} rate=${t.sampleRate} chMask=0x${Integer.toHexString(t.channelConfig)} " +
            "offload=${t.offload} tunneling=${t.tunneling} buffer=${t.bufferSize}"
    }

    private fun counters(): String {
        val c = player.audioDecoderCounters ?: return ""
        c.ensureUpdated()
        return "audioIn=${c.queuedInputBufferCount} audioOut=${c.renderedOutputBufferCount}"
    }

    /** The output media audio goes to now and the encodings it advertises. */
    private fun route(): String {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(ATTRIBUTES)
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type == AudioDeviceInfo.TYPE_HDMI || it.type == AudioDeviceInfo.TYPE_HDMI_ARC || it.type == AudioDeviceInfo.TYPE_HDMI_EARC }
        }
        if (devices.isEmpty()) return "unknown"
        return devices.joinToString("|") { d ->
            "${typeName(d.type)}:${d.productName} enc=${d.encodings.joinToString(",")} ch=${d.channelCounts.maxOrNull() ?: "?"}"
        }
    }

    /** What the platform says about playing this exact encoding direct on the current route (Android 13+). */
    private fun direct(t: AudioSink.AudioTrackConfig): String {
        if (Util.isEncodingLinearPcm(t.encoding)) return "n/a"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "unknown"
        return runCatching {
            val f = AudioFormat.Builder().setEncoding(t.encoding).setSampleRate(t.sampleRate).setChannelMask(t.channelConfig).build()
            when (val s = AudioManager.getDirectPlaybackSupport(f, ATTRIBUTES)) {
                AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED -> "no"
                else -> buildList {
                    if (s and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0) add("bitstream")
                    if (s and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED != 0) add("offload")
                }.joinToString("+").ifEmpty { "flags=$s" }
            }
        }.getOrElse { "error:${it.message}" }
    }

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "earc"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        else -> "type$type"
    }

    companion object {
        private const val TAG = "Audio"
        private const val STILL_MS = 5_000L
        private const val BUFFERED_MS = 10_000L
        private val ATTRIBUTES = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    }
}
