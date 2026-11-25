package com.strmr.tv.ui.settings

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.strmr.tv.app.App

/**
 * Settings › Playback Debugging: pieces of the player that can be turned off one at a time to
 * find which one a playback report (stutter) comes from. Each is read as a playback starts, so
 * a change applies to the next one. The defaults are the app's normal behaviour.
 */
enum class PlaybackDebugFlag(val title: String, val detail: String, val default: Boolean = true) {
    AUDIO_OFFSET("Lip-sync Wrapper", "Audio Offset has no effect while off"),
    PASSTHROUGH_GUARD("Passthrough Guard", "Keeps Dolby / DTS off outputs that can't take it"),
    LEGACY_MATCHER("Old Frame Rate Matcher", "Matches stated rates only, and ignores the Match Frame Rate setting", false),
    START_HOLD("Wait for Display Before Starting", "Match Frame Rate switches first, then playback starts"),
    SWITCH_PAUSE("Pause for Mid-playback Switch", "Pauses while the display changes refresh rate"),
    FRAME_LISTENER("Matcher Frame Listener", "Measures the rate of videos that don't state one"),
    SURFACE_FRAME_RATE("Surface Frame Rate Hint", "Tells Android the video's frame rate (Media3 default)"),
    HEALTH_LOG("Health Log", "Decoder, dropped frames, memory and heat"),
    AUDIO_LOG("Audio Log", "Audio format, output and clock"),
    STALL_LOG("Stall Log", "Rebuffering"),
    FRAME_TIMING_LOG("Frame Timing Log", "How evenly frames are released to the display"),
    DISPLAY_LOG("Display Log", "Refresh rate changes and interface frames drawn"),
}

object PlaybackDebugFlags {
    private val prefs by lazy { App.context.getSharedPreferences("strmr", Context.MODE_PRIVATE) }
    private val values by lazy {
        mutableStateMapOf<PlaybackDebugFlag, Boolean>().apply {
            PlaybackDebugFlag.entries.forEach { put(it, prefs.getBoolean(key(it), it.default)) }
        }
    }

    fun on(flag: PlaybackDebugFlag): Boolean = values[flag] ?: flag.default

    fun set(flag: PlaybackDebugFlag, value: Boolean) {
        values[flag] = value
        prefs.edit().apply { if (value == flag.default) remove(key(flag)) else putBoolean(key(flag), value) }.apply()
    }

    fun reset() = PlaybackDebugFlag.entries.forEach { set(it, it.default) }

    /** The flags away from their defaults. */
    val changed: List<PlaybackDebugFlag> get() = PlaybackDebugFlag.entries.filter { on(it) != it.default }

    /** For the log: what this playback runs with. */
    fun summary(): String = changed.joinToString(" ") { "${it.name}=${if (on(it)) "on" else "off"}" }.ifEmpty { "defaults" }

    private fun key(flag: PlaybackDebugFlag) = "playbackDebug.${flag.name}"
}
