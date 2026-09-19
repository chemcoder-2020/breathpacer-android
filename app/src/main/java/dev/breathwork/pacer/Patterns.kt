package dev.breathwork.pacer

/**
 * The breathing plan and the amplitude-envelope waveforms.
 *
 * Kept free of Android imports so the ramp maths is unit-testable on the JVM (CI runs
 * these tests) - the phone is only needed to feel the result, not to verify the shapes.
 */
object Patterns {

    const val MIN_AMP = 40      // still clearly felt; 0 would drop the cue mid-breath
    const val MAX_AMP = 255     // hardware maximum
    const val HOLD_AMP = 80     // steady, low - a hold should be a presence, not a buzz
    const val FREE_AMP = 90
    const val MAX_SEGMENTS = 60 // keep each waveform well inside HAL segment limits

    sealed class Block {
        data class Cycle(val inS: Double, val hold: Double, val outS: Double, val hold2: Double, val count: Int) : Block()
        data class Sighs(val count: Int) : Block()
        data class Free(val seconds: Double) : Block()
    }

    data class Slot(val id: String, val time: String, val name: String, val blocks: List<Block>)

    val SLOTS = listOf(
        Slot("0800", "08:00", "Box 4-4-4-4",        listOf(Block.Cycle(4.0, 4.0, 4.0, 4.0, 15))),
        Slot("0930", "09:30", "Sighs + extended",   listOf(Block.Sighs(6), Block.Cycle(4.0, 0.0, 6.0, 0.0, 18))),
        Slot("1100", "11:00", "Resonance 5.5/5.5",  listOf(Block.Cycle(5.5, 0.0, 5.5, 0.0, 27))),
        Slot("1300", "13:00", "Coherent (12 min)",  listOf(Block.Cycle(4.0, 0.0, 6.0, 0.0, 60), Block.Free(120.0))),
        Slot("1600", "16:00", "Box 4-4-4-4",        listOf(Block.Cycle(4.0, 4.0, 4.0, 4.0, 15))),
        Slot("1830", "18:30", "Extended exhale",    listOf(Block.Cycle(4.0, 0.0, 8.0, 0.0, 20))),
        Slot("2030", "20:30", "4-7-8 wind-down",    listOf(Block.Cycle(4.0, 7.0, 8.0, 0.0, 8), Block.Free(568.0))),
    )

    enum class Kind { INHALE, INHALE2, HOLD, HOLD2, EXHALE, FREE }

    data class Phase(val kind: Kind, val seconds: Double, val cycle: Int = 0, val cycles: Int = 0)

    private fun halve(n: Int) = maxOf(1, Math.round(n / 2.0).toInt())

    fun phases(slot: Slot, half: Boolean = false): List<Phase> {
        val out = ArrayList<Phase>()
        for (b in slot.blocks) {
            when (b) {
                is Block.Cycle -> {
                    val n = if (half) halve(b.count) else b.count
                    for (k in 1..n) {
                        out += Phase(Kind.INHALE, b.inS, k, n)
                        if (b.hold > 0) out += Phase(Kind.HOLD, b.hold, k, n)
                        out += Phase(Kind.EXHALE, b.outS, k, n)
                        if (b.hold2 > 0) out += Phase(Kind.HOLD2, b.hold2, k, n)
                    }
                }
                is Block.Sighs -> {
                    val n = if (half) halve(b.count) else b.count
                    for (k in 1..n) {
                        out += Phase(Kind.INHALE, 3.0, k, n)
                        out += Phase(Kind.INHALE2, 1.0, k, n)
                        out += Phase(Kind.EXHALE, 6.0, k, n)
                    }
                }
                is Block.Free -> out += Phase(Kind.FREE, if (half) b.seconds / 2 else b.seconds)
            }
        }
        return out
    }

    fun phaseMillis(phases: List<Phase>): LongArray {
        val cum = LongArray(phases.size)
        var acc = 0L
        phases.forEachIndexed { i, p -> cum[i] = acc; acc += Math.round(p.seconds * 1000.0) }
        return cum
    }

    fun totalMillis(phases: List<Phase>): Long =
        phases.sumOf { Math.round(it.seconds * 1000.0) }

    /** timings[i] paired with amplitudes[i]; amplitude 0 means the motor is off. */
    data class Waveform(val timings: LongArray, val amplitudes: IntArray) {
        val totalMs: Long get() = timings.sum()
        val segments: Int get() = timings.size
    }

    /**
     * Real amplitude envelope for one phase.
     *
     * The inhale runs MIN_AMP -> MAX_AMP on a slightly convex curve (perceived intensity
     * lags amplitude, so easing keeps the swell smooth rather than back-loaded); the exhale
     * mirrors it. Segment length adapts to the phase (25-133 ms) so a slow 8 s exhale gets
     * a genuinely gradual ramp instead of a stepped buzz.
     */
    fun waveform(kind: Kind, seconds: Double): Waveform {
        val ms = Math.round(seconds * 1000.0)
        when (kind) {
            Kind.FREE -> return Waveform(longArrayOf(200L), intArrayOf(FREE_AMP))
            Kind.INHALE2 -> return Waveform(longArrayOf(120L), intArrayOf(180))
            Kind.HOLD, Kind.HOLD2 -> return Waveform(longArrayOf(ms), intArrayOf(HOLD_AMP))
            else -> {}
        }
        val stepTarget = (ms / MAX_SEGMENTS.toDouble()).coerceIn(25.0, 133.0)
        val n = maxOf(2, Math.ceil(ms / stepTarget).toInt()).coerceAtMost(MAX_SEGMENTS)
        val base = ms / n
        val timings = LongArray(n)
        val amps = IntArray(n)
        var acc = 0L
        for (i in 0 until n) {
            val t = if (kind == Kind.INHALE) (i + 1).toDouble() / n else 1.0 - i.toDouble() / n
            val eased = Math.pow(t, 0.8)
            amps[i] = (MIN_AMP + (MAX_AMP - MIN_AMP) * eased).toInt().coerceIn(1, 255)
            timings[i] = base
            acc += base
        }
        timings[n - 1] += ms - acc   // absorb rounding so the waveform ends exactly on the phase
        return Waveform(timings, amps)
    }
}
