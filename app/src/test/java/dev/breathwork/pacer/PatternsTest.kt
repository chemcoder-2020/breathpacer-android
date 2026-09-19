package dev.breathwork.pacer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the ramp + pulse-train shapes without a phone. */
class PatternsTest {

    private val opt = Patterns.Options()

    private fun peaks(w: Patterns.Waveform) = w.pulsePeaks

    @Test fun everyPhaseHasRealSilence() {
        for (slot in Patterns.SLOTS) {
            for (p in Patterns.phases(slot)) {
                if (p.kind == Patterns.Kind.FREE || p.kind == Patterns.Kind.INHALE2) continue
                val w = Patterns.waveform(p.kind, p.seconds, opt)
                val silentMs = w.timings.filterIndexed { i, _ -> w.amplitudes[i] == 0 }.sum()
                assertTrue("${slot.id} ${p.kind}: pulsed, not continuous (silent ${silentMs}ms of ${w.totalMs})",
                    silentMs >= w.totalMs / 5)
            }
        }
    }

    @Test fun holdsAreAFirmDoubleTapThenSilence() {
        for (secs in listOf(4.0, 7.0)) {
            val w = Patterns.waveform(Patterns.Kind.HOLD, secs, opt)
            assertEquals("fills the phase", Math.round(secs * 1000.0), w.totalMs)
            val pk = peaks(w)
            assertEquals("exactly two taps", 2, pk.size)
            assertEquals("both taps the same strength", 1, pk.distinct().size)
            assertTrue("firm, not a whisper (got ${pk.first()})", pk.first() >= 40)
            assertTrue("taps are short", w.timings[0] <= 150L && w.timings[2] <= 150L)
            val silence = w.timings.last()
            assertTrue("the rest of the hold is quiet (${silence}ms)", silence > w.totalMs * 6 / 10)
        }
    }

    @Test fun inhaleRampsUpAcrossPulses() {
        val w = Patterns.waveform(Patterns.Kind.INHALE, 5.5, opt)
        assertEquals("ends exactly on the phase", 5500L, w.totalMs)
        assertTrue("segments capped", w.segments <= Patterns.MAX_SEGMENTS)
        val pk = peaks(w)
        assertTrue("several discrete pulses (got ${pk.size})", pk.size >= 5)
        for (i in 1 until pk.size) {
            assertTrue("pulse peaks never dip ($i: ${pk[i - 1]} -> ${pk[i]})", pk[i] >= pk[i - 1])
        }
        assertTrue("is a real ramp", pk.last() - pk.first() > 20)
    }

    @Test fun exhaleRampsDownAcrossPulses() {
        val w = Patterns.waveform(Patterns.Kind.EXHALE, 8.0, opt)
        assertEquals(8000L, w.totalMs)
        val pk = peaks(w)
        assertTrue("several discrete pulses", pk.size >= 5)
        for (i in 1 until pk.size) {
            assertTrue("pulse peaks never rise ($i)", pk[i] <= pk[i - 1])
        }
        assertTrue("stays felt at the end", pk.last() >= 10)
    }

    @Test fun holdsAreLevelPulses() {
        val w = Patterns.waveform(Patterns.Kind.HOLD, 7.0, opt)
        assertEquals(7000L, w.totalMs)
        val pk = peaks(w)
        assertEquals("every pulse the same height", 1, pk.distinct().size)
        assertTrue("holds are pulsed, not continuous", w.amplitudes.any { it == 0 })
    }

    @Test fun pulseRateAndDutyAreHonoured() {
        // measured on a ramped phase: holds are a fixed double tap, not a train
        val slow = peaks(Patterns.waveform(Patterns.Kind.EXHALE, 6.0, opt.copy(pulseHz = 2.0))).size
        val mid = peaks(Patterns.waveform(Patterns.Kind.EXHALE, 6.0, opt.copy(pulseHz = 2.5))).size
        val fast = peaks(Patterns.waveform(Patterns.Kind.EXHALE, 6.0, opt.copy(pulseHz = 3.0))).size
        assertTrue("3 Hz beats 2.5 Hz beats 2 Hz (got $slow/$mid/$fast)", fast > mid && mid > slow)

        val w = Patterns.waveform(Patterns.Kind.EXHALE, 6.0, opt)   // 2.5 Hz: 400 ms period, 30% duty
        assertEquals("first tap = 120 ms duty + the 72 ms boundary extension", 192L, w.timings[0])
        assertEquals("gaps complete the period", 280L, w.timings[1])
        assertEquals("later taps are the bare duty cycle", 120L, w.timings[2])
    }

    @Test fun holdCueIsIndependentOfPulseRate() {
        for (hz in listOf(2.0, 2.5, 3.0)) {
            val w = Patterns.waveform(Patterns.Kind.HOLD, 4.0, opt.copy(pulseHz = hz))
            assertEquals("still a double tap at $hz Hz", 2, peaks(w).size)
        }
    }

    @Test fun strengthScalesEveryAmplitude() {
        val soft = Patterns.waveform(Patterns.Kind.INHALE, 4.0, opt.copy(scale = 0.35))
        val loud = Patterns.waveform(Patterns.Kind.INHALE, 4.0, opt.copy(scale = 1.0))
        assertTrue("softer is quieter", peaks(soft).max() < peaks(loud).max())
        assertTrue("default ceiling is far below hardware max",
            peaks(Patterns.waveform(Patterns.Kind.INHALE, 4.0, Patterns.Options())).max() <= 90)
    }

    @Test fun boundaryTapMarksEachPhaseChange() {
        val w = Patterns.waveform(Patterns.Kind.INHALE, 5.5, opt)
        val onTimes = w.timings.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue("first tap is distinctly longer", onTimes.first() > onTimes[1] * 1.3)
    }

    @Test fun singlePulseKindsAreShortAndFillTheirPhase() {
        val sip = Patterns.waveform(Patterns.Kind.INHALE2, 1.0, opt)
        assertEquals("sip fills its phase", 1000L, sip.totalMs)
        assertEquals("sip is one pulse", 1, sip.pulsePeaks.size)
        assertTrue("free pulse stays short",
            Patterns.waveform(Patterns.Kind.FREE, 0.2, opt).totalMs <= 250L)
        assertEquals("free fills its long phase", 120_000L, Patterns.waveform(Patterns.Kind.FREE, 120.0, opt).totalMs)
    }

    @Test fun phaseTotalsMatchThePlan() {
        val expect = mapOf(
            "0800" to 240_000L, "0930" to 240_000L, "1100" to 297_000L,
            "1300" to 720_000L, "1600" to 240_000L, "1830" to 240_000L, "2030" to 720_000L,
        )
        for ((id, ms) in expect) {
            val slot = Patterns.SLOTS.first { it.id == id }
            assertEquals("slot $id total", ms, Patterns.totalMillis(Patterns.phases(slot)))
        }
    }

    @Test fun waveformsAlwaysFillTheirPhaseExactly() {
        for (slot in Patterns.SLOTS) {
            for (p in Patterns.phases(slot)) {
                if (p.kind == Patterns.Kind.FREE) continue   // fired as an 11-second pulse instead
                val w = Patterns.waveform(p.kind, p.seconds, opt)
                assertEquals("${slot.id} ${p.kind}: waveform length",
                    Math.round(p.seconds * 1000.0), w.totalMs)
                assertTrue("${slot.id} ${p.kind}: durations positive", w.timings.all { it > 0 })
                assertTrue("${slot.id} ${p.kind}: amplitudes in range", w.amplitudes.all { it in 0..255 })
                assertTrue("${slot.id} ${p.kind}: segment cap", w.segments <= Patterns.MAX_SEGMENTS)
            }
        }
    }
}
