package dev.breathwork.pacer

/**
 * Pure schedule maths: slot wall-clock times + next daily trigger.
 *
 * No android.* imports so CI unit tests can verify the firing logic on the JVM.
 */
object Schedules {

    /** Slot id -> daily fire time (24h hour, minute). Mirrors the 7 breathing slots. */
    val TIMES: Map<String, Pair<Int, Int>> = mapOf(
        "0800" to Pair(8, 0),
        "0930" to Pair(9, 30),
        "1100" to Pair(11, 0),
        "1300" to Pair(13, 0),
        "1600" to Pair(16, 0),
        "1830" to Pair(18, 30),
        "2030" to Pair(20, 30),
    )

    fun timeFor(slotId: String): Pair<Int, Int> = TIMES[slotId] ?: Pair(8, 0)

    /**
     * Next wall-clock trigger strictly after [nowMillis] for hour:minute
     * in the device default timezone.
     */
    fun nextTriggerMillis(nowMillis: Long, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= nowMillis) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun nextTriggerForSlot(nowMillis: Long, slotId: String): Long {
        val (h, m) = timeFor(slotId)
        return nextTriggerMillis(nowMillis, h, m)
    }
}
