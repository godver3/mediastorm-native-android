package com.strmr.tv.player

import kotlin.math.abs

/**
 * How evenly frames are released to the display. Each gap between two release times is compared
 * with the gap between the frames' presentation times: a matched display keeps the difference
 * near 0 ms; 24 fps at 60 Hz alternates about 8 ms either way (3:2 pulldown); more is uneven
 * release. Uneven presentation times point at the file instead. Fed from the playback thread,
 * read from the main thread.
 */
class FrameTiming {
    private val error = IntArray(ERROR_MS.size + 1)
    private var lastPtsUs = NONE
    private var lastReleaseNs = NONE
    private var gaps = 0
    private var resets = 0
    private var late = 0
    private var minPts = Long.MAX_VALUE
    private var maxPts = 0L
    private var minGap = Long.MAX_VALUE
    private var maxGap = 0L
    private var minLead = Long.MAX_VALUE

    @Synchronized fun frame(ptsUs: Long, releaseNs: Long, nowNs: Long, speed: Float) {
        val lead = (releaseNs - nowNs) / 1_000
        minLead = minOf(minLead, lead)
        if (lead < 0) late++
        val pts = ptsUs - lastPtsUs
        val gap = (releaseNs - lastReleaseNs) / 1_000
        val first = lastPtsUs == NONE
        lastPtsUs = ptsUs
        lastReleaseNs = releaseNs
        if (first) return
        // A seek, pause or stall: not the display's doing.
        if (pts <= 0 || pts > BREAK_US || gap <= 0 || gap > BREAK_US) { resets++; return }
        gaps++
        val off = abs(gap - (pts / speed).toLong())
        error[ERROR_MS.indexOfFirst { off <= it * 1_000L }.let { if (it < 0) ERROR_MS.size else it }]++
        minPts = minOf(minPts, pts); maxPts = maxOf(maxPts, pts)
        minGap = minOf(minGap, gap); maxGap = maxOf(maxGap, gap)
    }

    /** Since the last call; null when no frames were timed. */
    @Synchronized fun drain(): String? {
        if (gaps == 0) return null
        val buckets = ERROR_MS.mapIndexed { i, ms -> "<=${ms}ms:${error[i]}" } + ">${ERROR_MS.last()}ms:${error.last()}"
        val text = "gaps=$gaps error ${buckets.joinToString(" ")} release=${ms(minGap)}..${ms(maxGap)}ms " +
            "pts=${ms(minPts)}..${ms(maxPts)}ms minLead=${ms(minLead)}ms late=$late breaks=$resets"
        error.fill(0)
        gaps = 0; resets = 0; late = 0
        minPts = Long.MAX_VALUE; maxPts = 0; minGap = Long.MAX_VALUE; maxGap = 0; minLead = Long.MAX_VALUE
        return text
    }

    private fun ms(us: Long) = "%.1f".format(java.util.Locale.US, us / 1000f)

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val BREAK_US = 250_000L
        /** Upper bounds: steady, 3:2 pulldown, a 60 Hz refresh off, a 24 Hz refresh off. */
        val ERROR_MS = intArrayOf(2, 10, 20, 45)
    }
}
