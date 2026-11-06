package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaStoreTest {
    private class Mem : KvBackend {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys() = m.keys.toSet()
    }

    @Test fun roundTripAndRemove() {
        val s = QuotaStore(Mem())
        assertTrue(s.set("workouts", "[1,2]"))
        assertEquals("[1,2]", s.get("workouts"))
        s.remove("workouts")
        assertNull(s.get("workouts"))
    }

    @Test fun rejectsBadKeys() {
        val b = Mem(); val s = QuotaStore(b)
        for (k in listOf("", "a/b", "../x", "a b", "x".repeat(65), "k\u0000")) {
            assertFalse(k, s.set(k, "v")); assertNull(s.get(k))
        }
        assertTrue(b.m.isEmpty())
    }

    @Test fun enforcesValueKeyAndTotalQuotas() {
        val s = QuotaStore(Mem(), maxKeys = 2, maxValueBytes = 10, maxTotalBytes = 30)
        assertFalse(s.set("a", "x".repeat(11)))
        assertTrue(s.set("a", "1234567890"))
        assertTrue(s.set("b", "1234567890"))
        assertFalse(s.set("c", "1"))                  // key count
        assertTrue(s.set("a", "1"))                   // overwrite stays within quota
        val t = QuotaStore(Mem(), maxKeys = 10, maxValueBytes = 100, maxTotalBytes = 30)
        assertTrue(t.set("a", "x".repeat(14)))        // 15 bytes
        assertFalse(t.set("b", "x".repeat(20)))       // would exceed total
    }
}
