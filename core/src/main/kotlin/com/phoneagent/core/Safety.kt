package com.phoneagent.core

import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What the gate decided about one tool call requested by the model. */
sealed class Decision {
    /** Safe, read-only or harmless: run without asking. */
    object Allow : Decision()

    /** Needs the user's explicit tap before running. */
    data class Confirm(val summary: String) : Decision()

    /** Never runs. The model is told why. */
    data class Deny(val reason: String) : Decision()
}

/** A tool call as requested by the model. Arguments are untrusted input. */
data class ToolCall(val name: String, val args: Map<String, Any?>) {
    fun str(key: String): String? = when (val v = args[key]) {
        null -> null
        is String -> v.trim().ifEmpty { null }
        is Number, is Boolean -> v.toString()
        else -> null
    }

    fun int(key: String): Int? = when (val v = args[key]) {
        is Number -> v.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt()
        is String -> v.trim().toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = args[key]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true", "on", "yes" -> true
            "false", "off", "no" -> false
            else -> null
        }
        else -> null
    }
}

/**
 * The only path from model output to a real action. Pure Kotlin so it is unit-tested on the JVM.
 * Rule of thumb: when in doubt, Deny or Confirm. Never Allow by default.
 */
class SafetyGate(private val maxSteps: Int = 4) {

    /** True if [text] (an app name, package name or host) looks like money, credentials or system control. */
    fun isDeniedText(text: String): Boolean {
        val t = text.lowercase()
        if (DENY_SUBSTRINGS.any { t.contains(it) }) return true
        val tokens = t.split(NON_ALNUM).filter { it.isNotEmpty() }.toSet()
        return tokens.any { it in DENY_TOKENS }
    }

    /** [stepIndex] is how many tool calls this task has already attempted. */
    fun evaluate(call: ToolCall, stepIndex: Int): Decision {
        if (stepIndex >= maxSteps) return Decision.Deny("Step limit ($maxSteps) reached for this task.")
        val spec = Tools.byName[call.name] ?: return Decision.Deny("Unknown tool '${call.name}'.")
        for (key in spec.required) {
            if (call.args[key] == null) return Decision.Deny("Missing argument '$key'.")
        }
        validate(call)?.let { return Decision.Deny(it) }
        return if (spec.risk == Risk.SAFE) Decision.Allow else Decision.Confirm(describe(call))
    }

    /** Human-readable one-line summary, shown in the confirmation dialog and the audit log. */
    fun describe(call: ToolCall): String = when (call.name) {
        "open_app" -> "Open the app \"${call.str("app_name")}\""
        "open_url" -> "Open ${call.str("url")} in your browser"
        "web_search" -> "Search the web for \"${call.str("query")}\""
        "set_alarm" -> "Set an alarm for %02d:%02d".format(call.int("hour"), call.int("minute"))
        "set_timer" -> "Start a timer for ${call.int("seconds")} seconds"
        "open_camera" -> "Open the camera (you press the shutter)"
        "dial_number" -> "Open the dialer with ${call.str("number")} (you press call)"
        "compose_sms" -> "Draft an SMS to ${call.str("number")} (you press send)"
        "compose_email" -> "Draft an email to ${call.str("to")} (you press send)"
        "create_calendar_event" -> "Add \"${call.str("title")}\" at ${call.str("start")} to your calendar (you press save)"
        "play_music" -> "Play music matching \"${call.str("query")}\""
        "navigate_to" -> "Open maps for \"${call.str("place")}\""
        "toggle_flashlight" -> "Turn the flashlight ${if (call.bool("on") == true) "on" else "off"}"
        "get_time" -> "Read the current time"
        "get_battery" -> "Read the battery level"
        else -> call.name
    }

    /** Returns a reason string if the arguments are unacceptable, or null if they are fine. */
    private fun validate(call: ToolCall): String? {
        when (call.name) {
            "open_app" -> {
                val name = call.str("app_name") ?: return "Invalid app_name."
                if (name.length > 60) return "App name too long."
                if (isDeniedText(name)) return "Blocked: apps for payments, banking, passwords or system settings are off limits."
            }
            "open_url" -> {
                val url = call.str("url") ?: return "Invalid url."
                val uri = try { URI(url) } catch (e: Exception) { return "Invalid url." }
                if (uri.scheme?.lowercase() != "https") return "Only https links are allowed."
                val host = uri.host ?: return "Invalid url."
                if (isDeniedText(host)) return "Blocked: this site looks like banking, payments or credentials."
            }
            "web_search" -> {
                val q = call.str("query") ?: return "Invalid query."
                if (q.length > 200) return "Query too long."
            }
            "set_alarm" -> {
                val h = call.int("hour") ?: return "Invalid hour."
                val m = call.int("minute") ?: return "Invalid minute."
                if (h !in 0..23 || m !in 0..59) return "Alarm time out of range."
            }
            "set_timer" -> {
                val s = call.int("seconds") ?: return "Invalid seconds."
                if (s !in 1..86_400) return "Timer must be between 1 second and 24 hours."
            }
            "dial_number" -> if (!validPhone(call.str("number"))) return "Invalid phone number (codes starting with * or # are blocked)."
            "compose_sms" -> {
                if (!validPhone(call.str("number"))) return "Invalid phone number (codes starting with * or # are blocked)."
                if ((call.str("message") ?: "").length > 500) return "Message too long."
            }
            "compose_email" -> {
                val to = call.str("to") ?: return "Invalid recipient."
                if (!EMAIL.matches(to)) return "Invalid email address."
                if ((call.str("body") ?: "").length > 2000) return "Body too long."
            }
            "create_calendar_event" -> {
                val title = call.str("title") ?: return "Invalid title."
                if (title.length > 120) return "Title too long."
                val start = call.str("start") ?: return "Invalid start time."
                try { LocalDateTime.parse(start, START_FORMAT) } catch (e: Exception) { return "Start must look like 2026-11-05 18:30." }
            }
            "play_music" -> if ((call.str("query") ?: return "Invalid query.").length > 120) return "Query too long."
            "navigate_to" -> if ((call.str("place") ?: return "Invalid place.").length > 150) return "Place too long."
            "toggle_flashlight" -> if (call.bool("on") == null) return "Invalid value for 'on'."
        }
        return null
    }

    private fun validPhone(n: String?): Boolean {
        if (n == null || !PHONE.matches(n)) return false
        return n.count { it.isDigit() } in 3..15
    }

    companion object {
        val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val PHONE = Regex("^\\+?[0-9][0-9 \\-]{2,18}$")

        /** Matched anywhere inside the text. */
        private val DENY_SUBSTRINGS = listOf(
            "bank", "payment", "paypal", "paytm", "phonepe", "gpay", "google pay", "wallet",
            "credit", "debit", "loan", "invest", "broker", "zerodha", "groww", "trading",
            "crypto", "coinbase", "binance", "insurance", "mastercard",
            "password", "authenticator", "keepass", "bitwarden", "lastpass", "1password", "vault",
            "settings", "play store", "uninstall", "packageinstaller", "accessibility", "device admin",
            "find my device", "hdfc", "icici", "kotak", "barclays",
        )

        /** Matched only as a whole word, so "taxi" is fine but "tax" and "pay" are not. */
        private val DENY_TOKENS = setOf("upi", "otp", "atm", "sbi", "pnb", "axis", "tax", "visa", "citi", "amex", "pay", "bhim")
    }
}
