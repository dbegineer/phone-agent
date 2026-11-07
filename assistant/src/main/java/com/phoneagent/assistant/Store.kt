package com.phoneagent.assistant

import android.content.Context
import com.phoneagent.core.Incoming
import com.phoneagent.core.Pantry
import com.phoneagent.core.RulePriority
import com.phoneagent.core.WatchRule
import org.json.JSONArray
import org.json.JSONObject

/** All state lives in app-private storage. Nothing here is ever sent anywhere. */
class Store(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_DIGEST = 200
        private val LOCK = Any()
    }

    var quietStart: Int
        get() = prefs.getInt("quiet_start", 22)
        set(v) { prefs.edit().putInt("quiet_start", v.coerceIn(0, 23)).apply() }

    var quietEnd: Int
        get() = prefs.getInt("quiet_end", 7)
        set(v) { prefs.edit().putInt("quiet_end", v.coerceIn(0, 23)).apply() }

    // ---- watch rules ----
    fun rules(): List<WatchRule> = synchronized(LOCK) {
        val arr = JSONArray(prefs.getString("rules", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val k = o.optJSONArray("k") ?: JSONArray()
            WatchRule(
                senderContains = o.optString("s").ifBlank { null },
                app = o.optString("a").ifBlank { null },
                keywords = (0 until k.length()).map { i -> k.getString(i) },
                priority = if (o.optBoolean("c")) RulePriority.CRITICAL else RulePriority.NORMAL,
            )
        }
    }

    fun saveRules(rules: List<WatchRule>) = synchronized(LOCK) {
        val arr = JSONArray()
        rules.forEach {
            arr.put(JSONObject().put("s", it.senderContains ?: "").put("a", it.app ?: "")
                .put("k", JSONArray(it.keywords)).put("c", it.priority == RulePriority.CRITICAL))
        }
        prefs.edit().putString("rules", arr.toString()).apply()
    }

    // ---- digest ----
    fun addDigest(m: Incoming) = synchronized(LOCK) {
        val arr = JSONArray(prefs.getString("digest", "[]"))
        arr.put(JSONObject().put("a", m.app).put("s", m.sender).put("t", m.text))
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_DIGEST) until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString("digest", trimmed.toString()).apply()
    }

    fun drainDigest(): List<Incoming> = synchronized(LOCK) {
        val arr = JSONArray(prefs.getString("digest", "[]"))
        val out = (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Incoming(o.getString("a"), o.getString("s"), o.getString("t"))
        }
        prefs.edit().putString("digest", "[]").apply()
        out
    }

    // ---- pantry (stored as an event log and replayed) ----
    private fun events(): JSONArray = JSONArray(prefs.getString("pantry", "[]"))
    private fun addEvent(o: JSONObject) = synchronized(LOCK) {
        prefs.edit().putString("pantry", events().put(o).toString()).apply()
    }

    fun bought(item: String, qty: Double, day: Int) =
        addEvent(JSONObject().put("t", "buy").put("i", item).put("q", qty).put("d", day))

    fun ranOutEarly(item: String, day: Int) =
        addEvent(JSONObject().put("t", "early").put("i", item).put("d", day))

    fun away(from: Int, to: Int) =
        addEvent(JSONObject().put("t", "away").put("f", from).put("e", to))

    fun pantry(): Pantry = synchronized(LOCK) {
        val p = Pantry(3)
        val ev = events()
        for (i in 0 until ev.length()) {
            val o = ev.getJSONObject(i)
            when (o.getString("t")) {
                "buy" -> p.record(o.getString("i"), o.getDouble("q"), o.getInt("d"))
                "early" -> p.ranOutEarly(o.getString("i"), o.getInt("d"))
                "away" -> p.addAway(o.getInt("f"), o.getInt("e"))
            }
        }
        p
    }
}
