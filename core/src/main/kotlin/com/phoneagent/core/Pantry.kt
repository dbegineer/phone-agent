package com.phoneagent.core

/** Typical usage for a 3-person household: one `qty` of the item lasts `days` days. */
data class Prior(val qty: Double, val days: Double)

data class Due(val item: String, val runOutDay: Double, val orderByDay: Double)

/**
 * Pantry predictor. Pure maths, no model and no network.
 * Days are plain integers (epoch days) so the logic is trivial to test.
 */
class Pantry(private val household: Int = 3) {
    private class Purchase(val day: Int, var qty: Double)

    private val history = mutableMapOf<String, MutableList<Purchase>>()
    private val learned = mutableMapOf<String, Double>() // days per unit
    private val away = mutableListOf<IntRange>()

    companion object {
        private val ALIASES = mapOf(
            "bananas" to "banana", "dahi" to "curd", "yogurt" to "curd", "yoghurt" to "curd",
            "flour" to "atta", "wheat flour" to "atta", "cereals" to "cereal", "egg" to "eggs",
            "bread loaf" to "bread", "tomatoes" to "tomato", "onions" to "onion", "potatoes" to "potato",
        )
        val PRIORS = mapOf(
            "milk" to Prior(1.0, 2.0),      // 1 L
            "banana" to Prior(12.0, 3.5),   // a dozen
            "bread" to Prior(1.0, 3.0),     // 1 loaf
            "curd" to Prior(1.0, 2.0),      // 1 pack
            "cereal" to Prior(1.0, 17.5),   // 1 box
            "atta" to Prior(10.0, 37.5),    // 10 kg
            "eggs" to Prior(12.0, 6.0),     // a dozen
            "rice" to Prior(5.0, 30.0),     // 5 kg
            "onion" to Prior(2.0, 12.0),    // 2 kg
            "potato" to Prior(2.0, 12.0),   // 2 kg
            "tomato" to Prior(1.0, 5.0),    // 1 kg
        )
    }

    fun canonical(name: String): String {
        val n = name.trim().lowercase()
        return ALIASES[n] ?: n
    }

    private fun priorDpu(item: String): Double? =
        PRIORS[item]?.let { it.days / it.qty * 3.0 / household }

    /** Days one unit of this item lasts for this household, or null if unknown. */
    fun daysPerUnit(item: String): Double? = learned[canonical(item)] ?: priorDpu(canonical(item))

    fun record(name: String, qty: Double, day: Int) {
        if (qty <= 0) return
        val item = canonical(name)
        val list = history.getOrPut(item) { mutableListOf() }
        val last = list.lastOrNull()
        if (last != null && last.day == day) { last.qty += qty; return }
        if (last != null && day > last.day) {
            val observed = (day - last.day) / last.qty
            val base = daysPerUnit(item) ?: observed
            learned[item] = 0.6 * base + 0.4 * observed
        }
        list += Purchase(day, qty)
    }

    /** User says "ran out earlier than you thought": learn faster from this one. */
    fun ranOutEarly(name: String, today: Int) {
        val item = canonical(name)
        val last = history[item]?.lastOrNull() ?: return
        if (today <= last.day) return
        val observed = (today - last.day) / last.qty
        val base = daysPerUnit(item) ?: observed
        learned[item] = 0.5 * base + 0.5 * observed
    }

    fun addAway(from: Int, to: Int) { if (to >= from) away += from..to }

    fun runOutDay(name: String): Double? {
        val item = canonical(name)
        val last = history[item]?.lastOrNull() ?: return null
        val dpu = daysPerUnit(item) ?: return null
        var runOut = last.day + dpu * last.qty
        var extra = 0.0
        for (r in away) {
            val overlap = minOf(r.last.toDouble(), runOut) - maxOf(r.first.toDouble(), last.day.toDouble())
            if (overlap > 0) extra += overlap
        }
        runOut += extra
        return runOut
    }

    /** Items whose order-by day falls on or before today + horizon. */
    fun due(today: Int, leadDays: Double = 1.0, horizon: Int = 1): List<Due> =
        history.keys.mapNotNull { item ->
            val out = runOutDay(item) ?: return@mapNotNull null
            val by = out - leadDays
            if (by <= today + horizon) Due(item, out, by) else null
        }.sortedBy { it.runOutDay }
}
