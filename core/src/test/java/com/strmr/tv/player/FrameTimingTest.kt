package com.strmr.tv.player

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimingTest {
    private fun run(releaseMs: List<Long>, ptsMs: List<Long>, speed: Float = 1f): FrameTiming {
        val t = FrameTiming()
        releaseMs.zip(ptsMs).forEach { (r, p) -> t.frame(p * 1000, r * 1_000_000, r * 1_000_000 - 20_000_000, speed) }
        return t
    }

    @Test fun evenFramesAreSteady() {
        val text = run(List(11) { 1000 + it * 42L }, List(11) { it * 42L }).drain()!!
        assertTrue(text, text.contains("gaps=10 error <=2ms:10 "))
        assertTrue(text, text.contains("minLead=20.0ms late=0 breaks=0"))
    }

    @Test fun pulldownAndHitchesLandInTheirBuckets() {
        // 33 / 50 ms alternating, then one frame 42 ms late.
        val text = run(listOf(0, 33, 83, 167), listOf(0, 42, 84, 126)).drain()!!
        assertTrue(text, text.contains("<=10ms:2 <=20ms:0 <=45ms:1 >45ms:0"))
    }

    @Test fun seeksAndPausesAreBreaks() {
        val text = run(listOf(0, 42, 5000, 5042), listOf(0, 42, 84, 126)).drain()!!
        assertTrue(text, text.contains("gaps=2 "))
        assertTrue(text, text.contains("breaks=1"))
    }

    @Test fun speedScalesTheExpectedGap() {
        val text = run(listOf(0, 21, 42), listOf(0, 42, 84), speed = 2f).drain()!!
        assertTrue(text, text.contains("<=2ms:2"))
    }

    @Test fun drainStartsOver() {
        val t = run(listOf(0, 42), listOf(0, 42))
        t.drain()
        assertNull(t.drain())
    }
}
