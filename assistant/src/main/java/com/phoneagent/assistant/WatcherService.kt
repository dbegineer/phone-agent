package com.phoneagent.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.phoneagent.core.Incoming
import com.phoneagent.core.Quiet
import com.phoneagent.core.Triage
import com.phoneagent.core.Urgency
import java.time.LocalTime

/**
 * Reads WhatsApp and Gmail notifications that are already on screen. No logins, no network.
 * Sensitive messages (OTPs, passwords, codes) are dropped before anything is stored.
 */
class WatcherService : NotificationListenerService() {

    private val gmail = "com.google.android.gm"
    private val whatsapp = setOf("com.whatsapp", "com.whatsapp.w4b")

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != gmail && pkg !in whatsapp) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(80) ?: return
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (body.isBlank()) return

        val msg = Incoming(if (pkg == gmail) "gmail" else "whatsapp", title, body.take(300))
        val store = Store(this)
        val quiet = Quiet.isQuiet(LocalTime.now().hour, store.quietStart, store.quietEnd)
        when (Triage.decide(msg, store.rules(), quiet)) {
            Urgency.INTERRUPT -> alert(msg, sbn.key.hashCode())
            Urgency.DIGEST -> store.addDigest(msg)
            Urgency.IGNORE -> Unit
        }
    }

    private fun alert(msg: Incoming, id: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("important", "Important messages", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(this, "important")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(msg.sender)
            .setContentText(msg.text)
            .setStyle(Notification.BigTextStyle().bigText(msg.text))
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
