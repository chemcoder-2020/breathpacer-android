package dev.breathwork.pacer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Verifies the daily reminder schedule maths without a phone. */
class SchedulesTest {

    private fun millis(y: Int, mo: Int, d: Int, h: Int, min: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo, d, h, min, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test fun everySlotHasADailyTime() {
        assertEquals("all 7 slots scheduled", Patterns.SLOTS.size, Schedules.TIMES.size)
        for (slot in Patterns.SLOTS) {
            assertTrue("slot ${slot.id} has a time", Schedules.TIMES.containsKey(slot.id))
            val (h, m) = Schedules.timeFor(slot.id)
            assertTrue("hour sane for ${slot.id}", h in 0..23)
            assertTrue("minute sane for ${slot.id}", m in 0..59)
        }
    }

    @Test fun slotTimeMatchesItsLabel() {
        // The 08:00 slot fires at 08:00, the 20:30 slot at 20:30, etc.
        for (slot in Patterns.SLOTS) {
            val (h, m) = Schedules.timeFor(slot.id)
            assertEquals("${slot.id} label matches", slot.time, "%02d:%02d".format(h, m))
        }
    }

    @Test fun morningTriggerFiresLaterTheSameDay() {
        val now = millis(2026, Calendar.SEPTEMBER, 19, 7, 0)
        val at = Schedules.nextTriggerMillis(now, 8, 0)
        val c = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(19, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun missedTriggerRollsToTomorrow() {
        val now = millis(2026, Calendar.SEPTEMBER, 19, 9, 0)
        val at = Schedules.nextTriggerMillis(now, 8, 0)
        val c = Calendar.getInstance().apply { timeInMillis = at }
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun exactMinuteDoesNotRefireImmediately() {
        // At exactly 08:00:00 the trigger must be tomorrow, never "now".
        val now = millis(2026, Calendar.SEPTEMBER, 19, 8, 0)
        val at = Schedules.nextTriggerMillis(now, 8, 0)
        assertTrue("strictly in the future", at > now)
    }
}
