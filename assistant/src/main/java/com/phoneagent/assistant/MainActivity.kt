package com.phoneagent.assistant

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phoneagent.core.RulePriority
import com.phoneagent.core.WatchRule
import java.time.LocalDate

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var rulesBox: LinearLayout
    private lateinit var output: TextView

    private fun today() = LocalDate.now().toEpochDay().toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 18f; setPadding(0, dp(18), 0, dp(4)) }
    private fun edit(h: String, number: Boolean = false) = EditText(this).apply {
        hint = h
        if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    }
    private fun btn(t: String, onClick: () -> Unit) =
        Button(this).apply { text = t; setOnClickListener { onClick() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })

        // Status + permission
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)
        col.addView(btn("Grant notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        // Watch rules
        col.addView(label("Watch list"))
        val sender = edit("Sender contains (e.g. priya)")
        val app = edit("App: whatsapp or gmail (optional)")
        val kw = edit("Keywords, comma separated (optional)")
        val critical = CheckBox(this).apply { text = "Always interrupt me (critical)" }
        col.addView(sender); col.addView(app); col.addView(kw); col.addView(critical)
        col.addView(btn("Add rule") {
            val rule = WatchRule(
                senderContains = sender.text.toString().trim().ifBlank { null },
                app = app.text.toString().trim().lowercase().ifBlank { null },
                keywords = kw.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                priority = if (critical.isChecked) RulePriority.CRITICAL else RulePriority.NORMAL,
            )
            if (!rule.isValid) { output.text = "Give at least a sender, an app or a keyword."; return@btn }
            store.saveRules(store.rules() + rule)
            sender.text.clear(); app.text.clear(); kw.text.clear(); critical.isChecked = false
            refreshRules()
        })
        rulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rulesBox)

        // Quiet hours
        col.addView(label("Quiet hours (24h clock)"))
        val qs = edit("Start hour, e.g. 22", true).apply { setText(store.quietStart.toString()) }
        val qe = edit("End hour, e.g. 7", true).apply { setText(store.quietEnd.toString()) }
        col.addView(qs); col.addView(qe)
        col.addView(btn("Save quiet hours") {
            store.quietStart = qs.text.toString().toDoubleOrNull()?.toInt() ?: 22
            store.quietEnd = qe.text.toString().toDoubleOrNull()?.toInt() ?: 7
            output.text = "Quiet hours ${store.quietStart}:00 to ${store.quietEnd}:00. Only critical rules interrupt then."
        })

        // Digest
        col.addView(label("Digest"))
        col.addView(btn("What did I miss?") { showDigest() })

        // Pantry
        col.addView(label("Pantry (family of 3)"))
        val item = edit("Item, e.g. milk")
        val qty = edit("Quantity (1 L milk = 1, a dozen bananas = 12)", true)
        col.addView(item); col.addView(qty)
        col.addView(btn("I bought this today") {
            val name = item.text.toString().trim()
            if (name.isEmpty()) { output.text = "Type an item first."; return@btn }
            val q = qty.text.toString().toDoubleOrNull() ?: 1.0
            store.bought(name, q, today())
            output.text = "Noted: $name x$q."
            item.text.clear(); qty.text.clear()
        })
        col.addView(btn("It ran out earlier than predicted") {
            val name = item.text.toString().trim()
            if (name.isEmpty()) { output.text = "Type the item first."; return@btn }
            store.ranOutEarly(name, today())
            output.text = "Got it. I'll expect $name to go faster."
        })
        val awayDays = edit("Away for how many days from today?", true)
        col.addView(awayDays)
        col.addView(btn("Save away days") {
            val n = awayDays.text.toString().toDoubleOrNull()?.toInt() ?: 0
            if (n > 0) { store.away(today(), today() + n); output.text = "Paused predictions for $n days." }
        })
        col.addView(btn("What do I need?") { showDue() })

        output = TextView(this).apply { textSize = 15f; setPadding(0, dp(20), 0, 0) }
        col.addView(output)
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = if (enabled) "Watcher is ON. This app has no internet permission."
        else "Watcher is OFF. Tap below and allow \"Assistant\"."
        refreshRules()
    }

    private fun refreshRules() {
        rulesBox.removeAllViews()
        val rules = store.rules()
        if (rules.isEmpty()) rulesBox.addView(TextView(this).apply { text = "No rules yet. Nothing will notify you." })
        rules.forEachIndexed { i, r ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val desc = buildString {
                append(r.senderContains?.let { "from \"$it\" " } ?: "")
                append(r.app?.let { "in $it " } ?: "")
                if (r.keywords.isNotEmpty()) append("about ${r.keywords.joinToString("/")} ")
                if (r.priority == RulePriority.CRITICAL) append("[critical]")
            }.trim()
            row.addView(TextView(this).apply { text = desc }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(btn("Remove") { store.saveRules(store.rules().filterIndexed { j, _ -> j != i }); refreshRules() })
            rulesBox.addView(row)
        }
    }

    private fun showDigest() {
        val items = store.drainDigest()
        output.text = if (items.isEmpty()) "Nothing waiting." else buildString {
            append("${items.size} waiting:\n")
            items.groupingBy { it.sender }.eachCount().forEach { (s, n) -> append("- $s: $n\n") }
            append("\nLatest:\n")
            items.takeLast(8).forEach { append("${it.sender}: ${it.text.take(100)}\n") }
        }
    }

    private fun showDue() {
        val due = store.pantry().due(today())
        output.text = if (due.isEmpty()) "Nothing needed in the next day." else buildString {
            append("You will probably need:\n")
            due.forEach {
                val left = it.runOutDay - today()
                append("- ${it.item}: ").append(if (left <= 0) "out now" else "about ${"%.1f".format(left)} days left").append("\n")
            }
        }
    }
}
