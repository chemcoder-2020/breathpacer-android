package dev.breathwork.pacer

/**
 * The breathing plan and the amplitude-envelope waveforms.
 *
 * Kept free of Android imports so the ramp maths is unit-testable on the JVM (CI runs
 * these tests) - the phone is only needed to feel the result, not to verify the shapes.
 */
object Patterns {

    // Amplitudes are a 0-255 hardware scale. These are the reference levels at 100%
    // strength; the app scales them down from here (default 55%), because full-scale
    // 255 played continuously was reported as far too strong on a Pixel.
    const val MIN_AMP = 20       // bottom of the ramp - still felt, never silent
    const val MAX_AMP = 140      // top of the ramp (was 255)
    const val HOLD_AMP = 55      // a hold should be a presence, not a challenge
    const val FREE_AMP = 60
    const val SIGH_AMP = 110
    const val MAX_SEGMENTS = 60  // keep each waveform inside HAL limits

    /** Pulse train + intensity. Adjustable on the device; persisted there. */
    data class Options(
        val pulseHz: Double = 2.5,   // 2-3 Hz reads as discrete taps rather than a buzz
        val duty: Double = 0.30,     // 30% on-time: the gaps are what make it feel pulsed
        val scale: Double = 0.55,    // fraction of the reference amplitudes
        val boundaryTap: Boolean = true,  // first tap of a phase is longer, marking the change
    )

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

        /** Peak amplitude of each pulse - the envelope the hand reads as the "path". */
        val pulsePeaks: List<Int>
            get() {
                val out = ArrayList<Int>()
                var i = 0
                while (i < amplitudes.size) {
                    if (amplitudes[i] > 0) {
                        var peak = amplitudes[i]
                        while (i < amplitudes.size && amplitudes[i] > 0) {
                            peak = maxOf(peak, amplitudes[i]); i++
                        }
                        out += peak
                    } else i++
                }
                return out
            }
    }

    private fun scaled(amp: Double, scale: Double): Int =
        (amp * scale).toInt().coerceIn(10, 255)

    /**
     * A PULSED amplitude ramp for one phase.
     *
     * The envelope rides on the peak of each pulse rather than on a continuous vibration:
     * an inhale climbs pulse by pulse, an exhale falls, a hold stays level. Period is
     * `1000/pulseHz` with `duty` on-time, and the train is padded at the end so the
     * durations always sum to the exact phase length - the next phase starts on the beat.
     */
    fun waveform(kind: Kind, seconds: Double, opt: Options = Options()): Waveform {
        val ms = Math.round(seconds * 1000.0)
        val single = { amp: Int -> if (ms <= 250) Waveform(longArrayOf(ms), intArrayOf(scaled(amp.toDouble(), opt.scale)))
                                    else Waveform(longArrayOf(200L, ms - 200), intArrayOf(scaled(amp.toDouble(), opt.scale), 0)) }
        when (kind) {
            Kind.FREE -> return single(FREE_AMP)          // one deliberate pulse per ~11 s breath
            Kind.INHALE2 -> return Waveform(longArrayOf(120L, (ms - 120).coerceAtLeast(0)),
                intArrayOf(scaled(SIGH_AMP.toDouble(), opt.scale), 0))
            else -> {}
        }

        val period = Math.round(1000.0 / opt.pulseHz).coerceAtLeast(120)
        val on = Math.max(40, Math.round(period * opt.duty))
        val off = (period - on).coerceAtLeast(40)
        val extra = if (opt.boundaryTap) Math.round(on * 0.6) else 0   // longer first tap = phase change

        if (ms <= period + extra) {                    // too short to pulse: one level tap
            return Waveform(longArrayOf(ms), intArrayOf(scaled((MIN_AMP + MAX_AMP) / 2.0, opt.scale)))
        }

        val pulses = maxOf(1, ((ms - extra) / period).toInt())
        val timings = ArrayList<Long>(pulses * 2)
        val amps = ArrayList<Int>(pulses * 2)
        for (i in 0 until pulses) {
            val t = if (kind == Kind.INHALE) (i + 0.5) / pulses else 1.0 - (i + 0.5) / pulses
            val a: Double = when (kind) {
                Kind.HOLD, Kind.HOLD2 -> HOLD_AMP.toDouble()
                else -> MIN_AMP + (MAX_AMP - MIN_AMP) * Math.pow(t, 0.8)
            }
            timings += (on + if (i == 0) extra else 0).toLong()
            amps += scaled(a, opt.scale)
            timings += off.toLong()
            amps += 0
        }
        val pad = ms - (pulses.toLong() * period + extra)   // trailing silence: ends exactly on the phase
        if (pad > 0) timings[timings.size - 1] += pad
        return Waveform(timings.toLongArray(), amps.toIntArray())
    }
}
