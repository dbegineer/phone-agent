package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageTest {
    private val boss = WatchRule(senderContains = "priya")
    private val ci = WatchRule(app = "gmail", keywords = listOf("build", "pipeline"))
    private val rules = listOf(boss, ci)

    private fun msg(app: String, sender: String, text: String) = Incoming(app, sender, text)

    @Test fun unwatchedSendersAreIgnored() {
        assertEquals(Urgency.IGNORE, Triage.decide(msg("whatsapp", "Random", "hello"), rules, false))
    }

    @Test fun watchedGoesToDigestByDefault() {
        assertEquals(Urgency.DIGEST, Triage.decide(msg("whatsapp", "Priya", "lunch?"), rules, false))
    }

    @Test fun urgentWordsInterruptWatchedSenders() {
        assertEquals(Urgency.INTERRUPT, Triage.decide(msg("whatsapp", "Priya", "production is down"), rules, false))
        assertEquals(Urgency.INTERRUPT, Triage.decide(msg("gmail", "ci@x.com", "Build failed"), rules, false))
    }

    @Test fun urgentWordsFromUnwatchedAreStillIgnored() {
        assertEquals(Urgency.IGNORE, Triage.decide(msg("whatsapp", "Stranger", "URGENT call me"), rules, false))
    }

    @Test fun quietHoursDowngradeExceptCritical() {
        val urgent = msg("whatsapp", "Priya", "urgent: deadline moved")
        assertEquals(Urgency.DIGEST, Triage.decide(urgent, rules, true))
        val crit = listOf(WatchRule(senderContains = "priya", priority = RulePriority.CRITICAL))
        assertEquals(Urgency.INTERRUPT, Triage.decide(msg("whatsapp", "Priya", "hi"), crit, true))
    }

    @Test fun sensitiveMessagesAreAlwaysIgnored() {
        for (t in listOf("Your OTP is 482913", "Use verification code 123456", "password reset", "code 5521 expires"))
            assertEquals(t, Urgency.IGNORE, Triage.decide(msg("whatsapp", "Priya", t), rules, false))
        assertTrue(Triage.isSensitive("Your OTP is 482913"))
        assertFalse(Triage.isSensitive("see you at 5"))
    }

    @Test fun emptyRuleMatchesNothing() {
        assertFalse(Triage.matches(WatchRule(), msg("gmail", "a", "urgent")))
    }

    @Test fun wordBoundariesAvoidFalseUrgency() {
        assertEquals(Urgency.DIGEST, Triage.decide(msg("whatsapp", "Priya", "meet downtown"), rules, false))
    }

    @Test fun digestIsCappedAndDrains() {
        val d = Digest(max = 3)
        for (i in 1..5) d.add(msg("whatsapp", if (i % 2 == 0) "A" else "B", "m$i"))
        assertEquals(3, d.size())
        assertEquals(mapOf("B" to 2, "A" to 1), d.bySender())
        assertEquals(3, d.drain().size)
        assertEquals(0, d.size())
    }
}
