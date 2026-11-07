package com.phoneagent.core

enum class Urgency { INTERRUPT, DIGEST, IGNORE }
enum class RulePriority { NORMAL, CRITICAL }

/** A watch rule: at least one of sender, app or keywords must be set. */
data class WatchRule(
    val senderContains: String? = null,
    val app: String? = null,
    val keywords: List<String> = emptyList(),
    val priority: RulePriority = RulePriority.NORMAL,
) {
    val isValid get() = senderContains != null || app != null || keywords.isNotEmpty()
}

data class Incoming(val app: String, val sender: String, val text: String)

/**
 * Deterministic first-pass triage of notifications. The small on-device model only handles
 * what these rules leave unclear; it never overrides IGNORE for sensitive messages.
 */
object Triage {
    private val URGENT = Regex(
        "\\b(urgent|asap|immediately|deadline|outage|down|failed|failure|production|emergency|critical)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val SENSITIVE = Regex(
        "\\b(otp|one[- ]time (password|pin)|verification code|password|cvv|pin)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CODE = Regex("\\b\\d{4,8}\\b")

    /** OTPs, passwords and codes are never stored, summarized or forwarded. */
    fun isSensitive(text: String): Boolean =
        SENSITIVE.containsMatchIn(text) || (CODE.containsMatchIn(text) && text.contains("code", ignoreCase = true))

    fun matches(rule: WatchRule, msg: Incoming): Boolean {
        if (!rule.isValid) return false
        if (rule.senderContains != null && !msg.sender.contains(rule.senderContains, ignoreCase = true)) return false
        if (rule.app != null && !msg.app.equals(rule.app, ignoreCase = true)) return false
        if (rule.keywords.isNotEmpty() && rule.keywords.none { msg.text.contains(it, ignoreCase = true) }) return false
        return true
    }

    fun decide(msg: Incoming, rules: List<WatchRule>, quietHours: Boolean): Urgency {
        if (isSensitive(msg.text)) return Urgency.IGNORE
        val matched = rules.filter { matches(it, msg) }
        if (matched.isEmpty()) return Urgency.IGNORE
        val critical = matched.any { it.priority == RulePriority.CRITICAL }
        val urgent = critical || URGENT.containsMatchIn(msg.text)
        return if (urgent && (!quietHours || critical)) Urgency.INTERRUPT else Urgency.DIGEST
    }
}

/** Holds non-urgent items until the user asks "what did I miss?". Capped, in memory only. */
class Digest(private val max: Int = 200) {
    private val items = ArrayDeque<Incoming>()
    fun add(m: Incoming) { items.addLast(m); while (items.size > max) items.removeFirst() }
    fun size() = items.size
    fun bySender(): Map<String, Int> = items.groupingBy { it.sender }.eachCount()
    fun drain(): List<Incoming> = items.toList().also { items.clear() }
}
