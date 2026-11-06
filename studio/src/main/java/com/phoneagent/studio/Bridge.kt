package com.phoneagent.studio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import com.phoneagent.core.KvBackend
import com.phoneagent.core.Manifest
import com.phoneagent.core.Permissions
import com.phoneagent.core.QuotaStore
import org.json.JSONArray

/**
 * The ONLY door out of the sandbox. Exposed to JS as `Host`.
 * Every method re-checks the manifest permission; storage is namespaced per app and quota-limited.
 */
class Bridge(private val ctx: Context, private val manifest: Manifest) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("miniapp_${manifest.id}", Context.MODE_PRIVATE)

    private val store = QuotaStore(object : KvBackend {
        override fun get(key: String) = prefs.getString(key, null)
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
        override fun keys(): Set<String> = prefs.all.keys.toSet()
    })

    private var notifTimes = ArrayDeque<Long>()

    private fun has(p: String) = p in manifest.permissions

    @JavascriptInterface fun storageGet(key: String): String? =
        if (has(Permissions.STORAGE)) store.get(key) else null

    @JavascriptInterface fun storageSet(key: String, value: String): Boolean =
        has(Permissions.STORAGE) && store.set(key, value)

    @JavascriptInterface fun storageRemove(key: String) {
        if (has(Permissions.STORAGE)) store.remove(key)
    }

    @JavascriptInterface fun storageKeys(): String =
        if (has(Permissions.STORAGE)) JSONArray(store.keys()).toString() else "[]"

    /** Max 3 per minute, short text only. Returns false if denied. */
    @JavascriptInterface fun notify(title: String, text: String): Boolean {
        if (!has(Permissions.NOTIFICATIONS)) return false
        val now = System.currentTimeMillis()
        while (notifTimes.isNotEmpty() && now - notifTimes.first() > 60_000) notifTimes.removeFirst()
        if (notifTimes.size >= 3) return false
        notifTimes.addLast(now)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("miniapps", "Mini-apps", NotificationManager.IMPORTANCE_DEFAULT))
        val n = android.app.Notification.Builder(ctx, "miniapps")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${manifest.name}: ${title.take(60)}")
            .setContentText(text.take(200))
            .build()
        nm.notify(manifest.id.hashCode(), n)
        return true
    }
}
