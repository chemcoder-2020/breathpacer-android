package dev.breathwork.pacer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the ramp shapes without a phone: amplitude monotonicity, exact phase totals. */
class PatternsTest {

    private val longPhase = 5.5
    private val deepPhase = 8.0

    @Test fun inhaleRampsUp() {
        val w = Patterns.waveform(Patterns.Kind.INHALE, longPhase)
        assertTrue("ends exactly on the phase", w.totalMs == 5500L)
        assertTrue("segments capped", w.segments <= Patterns.MAX_SEGMENTS)
        for (i in 1 until w.segments) {
            assertTrue("amplitude never dips (i=$i: ${w.amplitudes[i - 1]} -> ${w.amplitudes[i]})",
                w.amplitudes[i] >= w.amplitudes[i - 1])
        }
        assertEquals(Patterns.MAX_AMP, w.amplitudes.last())
        assertTrue("starts clearly felt", w.amplitudes.first() >= Patterns.MIN_AMP)
        assertTrue("is a real ramp, not a step", w.amplitudes.last() - w.amplitudes.first() > 150)
    }

    @Test fun exhaleRampsDown() {
        val w = Patterns.waveform(Patterns.Kind.EXHALE, deepPhase)
        assertTrue("ends exactly on the phase", w.totalMs == 8000L)
        for (i in 1 until w.segments) {
            assertTrue("amplitude never rises (i=$i)", w.amplitudes[i] <= w.amplitudes[i - 1])
        }
        assertEquals(Patterns.MAX_AMP, w.amplitudes.first())
        assertTrue("fades to a whisper, not silence", w.amplitudes.last() >= Patterns.MIN_AMP)
    }

    @Test fun holdsAreSteady() {
        val w = Patterns.waveform(Patterns.Kind.HOLD, 7.0)
        assertEquals(7000L, w.totalMs)
        assertEquals(1, w.segments)
        assertTrue(w.amplitudes[0] < 120)   // a presence, not a buzz
    }

    @Test fun phaseTotalsMatchThePlan() {
        val expect = mapOf(
            "0800" to 240_000L,   // 15 x 16 s
            "0930" to 240_000L,   // 6 x 10 s + 18 x 10 s
            "1100" to 297_000L,   // 27 x 11 s
            "1300" to 720_000L,   // 60 x 10 s + 120 s
            "1600" to 240_000L,
            "1830" to 240_000L,   // 20 x 12 s
            "2030" to 720_000L,   // 8 x 19 s + 568 s
        )
        for ((id, ms) in expect) {
            val slot = Patterns.SLOTS.first { it.id == id }
            assertEquals("slot $id total", ms, Patterns.totalMillis(Patterns.phases(slot)))
        }
    }

    @Test fun halfSessionsHalveTheWork() {
        val slot = Patterns.SLOTS.first { it.id == "1100" }
        val full = Patterns.totalMillis(Patterns.phases(slot))
        val half = Patterns.totalMillis(Patterns.phases(slot, half = true))
        assertTrue("half is roughly half", half in (full / 2 - 20_000)..(full / 2 + 20_000))
    }

    @Test fun everyPlannedPhaseHasAFeltAmplitude() {
        for (slot in Patterns.SLOTS) {
            for (p in Patterns.phases(slot)) {
                val w = Patterns.waveform(p.kind, p.seconds)
                assertTrue("${slot.id} ${p.kind}: durations positive", w.timings.all { it > 0 })
                assertTrue("${slot.id} ${p.kind}: amplitude in range",
                    w.amplitudes.all { it in 1..255 })
            }
        }
    }
}
