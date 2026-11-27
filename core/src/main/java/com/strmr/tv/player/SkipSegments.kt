package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.SkipSegment
import com.strmr.tv.api.VideoChapter
import com.strmr.tv.api.skipSegments
import com.strmr.tv.api.videoChapters
import com.strmr.tv.app.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Intro / recap / credits ranges for the playing item from `POST /api/video/segments` (for a
 * recording, its commercial breaks instead), which
 * merges IntroDB, SkipDB and chapter names server-side. Posted once the duration is known.
 * ExoPlayer doesn't surface container chapters, so when an episode comes back without credits
 * the file's chapters are read from the server's probe ([videoChapters]) and posted again, as
 * the Expo player posts the chapters mpv reports. On-device credits detection adds an outro
 * through [detectedCredits] when neither source has one.
 */
class SkipSegments(private val request: PlaybackRequest, private val streamPath: String?) {
    private var segments: List<SkipSegment> = emptyList()
    private var detected: SkipSegment? = null
    private var postedDuration = 0L
    /** A recording's breaks: asked at this duration (s; -1: not yet, or the request failed). */
    private var recordingAskedAt = -1L
    private var recordingHasBreaks = false
    private var job: Job? = null

    /** Whether the server's segments include credits (on-device detection then stays off). */
    val hasOutro: Boolean get() = segments.any { it.type == "outro" }

    fun refresh(scope: CoroutineScope, durationMs: Long) {
        if (durationMs <= 0 || durationMs / 1000 == postedDuration) return
        postedDuration = durationMs / 1000
        val duration = durationMs / 1000.0
        request.recordingId?.let { id ->
            // A recording: its commercial breaks, which comskip marks once it is done. A growing
            // recording's duration changes with every playlist reload, so while it has none they are
            // asked again only every 15 minutes it grew; a failed request is tried twice more.
            val seconds = durationMs / 1000
            if (recordingHasBreaks || job?.isActive == true || (recordingAskedAt >= 0 && seconds < recordingAskedAt + 900)) return
            recordingAskedAt = seconds
            job = scope.launch {
                val body = buildJsonObject { put("recordingId", id); put("duration", duration) }
                repeat(3) { attempt ->
                    if (attempt > 0) delay(10_000)
                    val found = try {
                        ApiClient.skipSegments(body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@repeat
                    }
                    segments = found.filter { it.type == "commercial" && it.end > it.start }.sortedBy { it.start }
                    recordingHasBreaks = segments.isNotEmpty()
                    if (recordingHasBreaks) AppLog.i(TAG, "recording $id: ${segments.size} commercial breaks")
                    return@launch
                }
                recordingAskedAt = -1 // all three failed: ask again on the next duration change
            }
            return
        }
        job?.cancel()
        job = scope.launch {
            runCatching { ApiClient.skipSegments(body(duration, emptyList())) }.onSuccess { segments = it }
            val path = streamPath?.takeIf { request.isEpisode && !hasOutro } ?: return@launch
            val chapters = runCatching { ApiClient.videoChapters(path) }.getOrNull().orEmpty()
            if (chapters.isEmpty()) return@launch
            runCatching { ApiClient.skipSegments(body(duration, chapters)) }.onSuccess {
                AppLog.i(TAG, "segments with ${chapters.size} chapters: ${it.joinToString { s -> s.type }}")
                segments = it
            }
        }
    }

    /** Credits found on screen at [startSec]: an outro to the end, unless the server has one. */
    fun detectedCredits(startSec: Double, durationSec: Double) {
        if (hasOutro || durationSec <= startSec) return
        detected = SkipSegment("outro", startSec, durationSec)
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * The segment playback is inside, when it merits a button. Movie end credits are left
     * alone (skipping them only ends the film, and may skip a post-credits scene).
     */
    fun active(positionMs: Long): SkipSegment? {
        val t = positionMs / 1000.0
        val all = if (hasOutro) segments else segments + listOfNotNull(detected)
        return all.firstOrNull { t >= it.start && t < it.end - 1 && (it.type != "outro" || request.isEpisode) }
    }

    private fun body(duration: Double, chapters: List<VideoChapter>) = buildJsonObject {
        put("duration", duration)
        putJsonArray("chapters") {
            chapters.forEach { c ->
                add(buildJsonObject {
                    put("title", c.title)
                    put("start", c.start)
                    if (c.end > c.start) put("end", c.end)
                })
            }
        }
        request.title.imdbId?.let { put("imdbId", it) }
        request.episode?.let {
            put("season", it.season)
            put("episode", it.episode)
        }
    }

    private companion object {
        const val TAG = "SkipSegments"
    }
}

/** The contextual pill over the transport: a seek past a segment, or the next episode at the credits. */
sealed interface SkipAction {
    val label: String

    data class Skip(val segment: SkipSegment) : SkipAction {
        override val label = when (segment.type) {
            "intro" -> "Skip Intro"
            "recap" -> "Skip Recap"
            "outro" -> "Skip Credits"
            "commercial" -> "Skip Commercial"
            else -> "Skip Preview"
        }
    }

    /** `countdown` is the seconds left when it will play by itself (credits auto-skip on). */
    data class NextEpisode(val next: PlaybackRequest, val countdown: Int?) : SkipAction {
        override val label = countdown?.let { "Next Episode in $it" } ?: "Next Episode"
    }
}
