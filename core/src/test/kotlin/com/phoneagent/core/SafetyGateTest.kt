package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGateTest {
    private val gate = SafetyGate(maxSteps = 4)

    private fun call(name: String, vararg args: Pair<String, Any?>) = ToolCall(name, mapOf(*args))
    private fun deny(c: ToolCall, step: Int = 0) = gate.evaluate(c, step) is Decision.Deny
    private fun confirm(c: ToolCall) = gate.evaluate(c, 0) is Decision.Confirm

    @Test fun unknownToolIsDenied() = assertTrue(deny(call("format_phone")))

    @Test fun missingRequiredArgIsDenied() = assertTrue(deny(call("open_app")))

    @Test fun stepLimitIsEnforced() {
        val c = call("get_time")
        assertEquals(Decision.Allow, gate.evaluate(c, 3))
        assertTrue(deny(c, 4))
    }

    @Test fun readOnlyToolsAreAllowedWithoutPrompt() {
        assertEquals(Decision.Allow, gate.evaluate(call("get_time"), 0))
        assertEquals(Decision.Allow, gate.evaluate(call("get_battery"), 0))
        assertEquals(Decision.Allow, gate.evaluate(call("toggle_flashlight", "on" to true), 0))
    }

    @Test fun bankingAndPaymentAppsAreDenied() {
        for (name in listOf("HDFC Bank", "Google Pay", "PhonePe", "Paytm", "Settings", "Bitwarden", "Play Store", "Zerodha Kite", "UPI", "SBI YONO")) {
            assertTrue("should deny $name", deny(call("open_app", "app_name" to name)))
        }
    }

    @Test fun ordinaryAppsNeedConfirmation() {
        for (name in listOf("Camera", "Maps", "YouTube", "Uber Taxi", "Spotify", "WhatsApp")) {
            assertTrue("should confirm $name", confirm(call("open_app", "app_name" to name)))
        }
    }

    @Test fun onlyHttpsUrlsOnAllowedHosts() {
        assertTrue(confirm(call("open_url", "url" to "https://en.wikipedia.org/wiki/Bank")))
        assertTrue(deny(call("open_url", "url" to "http://example.com")))
        assertTrue(deny(call("open_url", "url" to "javascript:alert(1)")))
        assertTrue(deny(call("open_url", "url" to "file:///sdcard/secret")))
        assertTrue(deny(call("open_url", "url" to "intent://scan/#Intent;end")))
        assertTrue(deny(call("open_url", "url" to "https://netbanking.hdfcbank.com/login")))
        assertTrue(deny(call("open_url", "url" to "https://www.paypal.com")))
        assertTrue(deny(call("open_url", "url" to "not a url")))
    }

    @Test fun ussdAndMalformedNumbersAreDenied() {
        assertTrue(deny(call("dial_number", "number" to "*123#")))
        assertTrue(deny(call("dial_number", "number" to "#06#")))
        assertTrue(deny(call("dial_number", "number" to "12")))
        assertTrue(deny(call("compose_sms", "number" to "tel:123", "message" to "hi")))
        assertTrue(confirm(call("dial_number", "number" to "+91 98765 43210")))
        assertTrue(confirm(call("compose_sms", "number" to "9876543210", "message" to "hi")))
    }

    @Test fun alarmAndTimerRanges() {
        assertTrue(confirm(call("set_alarm", "hour" to 7, "minute" to 30)))
        assertTrue(confirm(call("set_alarm", "hour" to 7.0, "minute" to "30")))
        assertTrue(deny(call("set_alarm", "hour" to 24, "minute" to 0)))
        assertTrue(deny(call("set_alarm", "hour" to 7, "minute" to 60)))
        assertTrue(deny(call("set_alarm", "hour" to 7.5, "minute" to 0)))
        assertEquals(Decision.Allow, gate.evaluate(call("set_timer", "seconds" to 300), 0))
        assertTrue(deny(call("set_timer", "seconds" to 0)))
        assertTrue(deny(call("set_timer", "seconds" to 100_000)))
    }

    @Test fun emailAndCalendarValidation() {
        assertTrue(confirm(call("compose_email", "to" to "a@b.com", "subject" to "s", "body" to "b")))
        assertTrue(deny(call("compose_email", "to" to "not-an-email")))
        assertTrue(confirm(call("create_calendar_event", "title" to "Dentist", "start" to "2026-11-05 18:30")))
        assertTrue(deny(call("create_calendar_event", "title" to "Dentist", "start" to "tomorrow evening")))
    }

    @Test fun confirmSummaryNamesTheAction() {
        val d = gate.evaluate(call("set_alarm", "hour" to 7, "minute" to 5), 0)
        assertEquals(Decision.Confirm("Set an alarm for 07:05"), d)
    }

    @Test fun everyToolHasAValidJsonSpecAndGateCoverage() {
        assertEquals(15, Tools.all.size)
        for (t in Tools.all) {
            val j = t.toJsonString()
            assertTrue(j.startsWith("{\"name\":\"${t.name}\""))
            assertTrue(t.required.all { it in t.params.keys })
        }
    }

    @Test fun jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.str("a\"b\\c\n"))
    }
}
