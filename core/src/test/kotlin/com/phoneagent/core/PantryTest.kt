package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PantryTest {
    private val eps = 1e-9

    @Test fun priorsForThreePeople() {
        val p = Pantry(3)
        p.record("milk", 1.0, 0); p.record("banana", 12.0, 0); p.record("curd", 1.0, 0)
        p.record("cereal", 1.0, 0); p.record("atta", 10.0, 0)
        assertEquals(2.0, p.runOutDay("milk")!!, eps)
        assertEquals(3.5, p.runOutDay("banana")!!, eps)
        assertEquals(2.0, p.runOutDay("curd")!!, eps)
        assertEquals(17.5, p.runOutDay("cereal")!!, eps)
        assertEquals(37.5, p.runOutDay("atta")!!, eps)
    }

    @Test fun scalesWithHouseholdAndQuantity() {
        val six = Pantry(6); six.record("milk", 1.0, 0)
        assertEquals(1.0, six.runOutDay("milk")!!, eps)
        val p = Pantry(3); p.record("atta", 5.0, 0)
        assertEquals(18.75, p.runOutDay("atta")!!, eps)
    }

    @Test fun aliasesAreNormalised() {
        val p = Pantry(3)
        p.record("Bananas", 12.0, 0); p.record("Dahi", 1.0, 0)
        assertEquals(3.5, p.runOutDay("banana")!!, eps)
        assertEquals(2.0, p.runOutDay("curd")!!, eps)
    }

    @Test fun learnsFromRepeatPurchases() {
        val p = Pantry(3)
        p.record("milk", 1.0, 0)
        p.record("milk", 1.0, 1) // re-bought after 1 day: faster than the 2-day prior
        assertEquals(1.6, p.daysPerUnit("milk")!!, eps) // 0.6*2 + 0.4*1
        assertEquals(2.6, p.runOutDay("milk")!!, eps)
    }

    @Test fun sameDayPurchasesMerge() {
        val p = Pantry(3)
        p.record("milk", 1.0, 0); p.record("milk", 1.0, 0)
        assertEquals(4.0, p.runOutDay("milk")!!, eps)
    }

    @Test fun suggestsOneDayAheadWithLeadTime() {
        val p = Pantry(3)
        p.record("milk", 1.0, 0); p.record("atta", 10.0, 0)
        assertEquals(listOf("milk"), p.due(today = 0).map { it.item })
        assertTrue(p.due(today = 10).none { it.item == "atta" })
        assertFalse(p.due(today = 35).any { it.item == "atta" })
        assertTrue(p.due(today = 36).any { it.item == "atta" })
    }

    @Test fun awayDaysPushRunOutLater() {
        val p = Pantry(3)
        p.record("milk", 1.0, 0)
        p.addAway(1, 3)
        assertTrue(p.runOutDay("milk")!! > 2.0)
    }

    @Test fun ranOutEarlyLearnsFaster() {
        val p = Pantry(3)
        p.record("cereal", 1.0, 0)
        p.ranOutEarly("cereal", 10)
        assertEquals(13.75, p.runOutDay("cereal")!!, eps) // 0.5*17.5 + 0.5*10
    }

    @Test fun unknownItemWithoutHistoryIsNotPredicted() {
        val p = Pantry(3)
        p.record("saffron", 1.0, 0)
        assertNull(p.runOutDay("saffron"))
        assertNull(p.runOutDay("milk"))
        assertTrue(p.due(today = 100).isEmpty())
    }

    @Test fun ignoresNonPositiveQuantities() {
        val p = Pantry(3)
        p.record("milk", 0.0, 0); p.record("milk", -1.0, 0)
        assertNull(p.runOutDay("milk"))
    }
}
