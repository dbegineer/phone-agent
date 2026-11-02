package com.phoneagent.app

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import com.phoneagent.core.Json
import com.phoneagent.core.SafetyGate
import com.phoneagent.core.ToolCall
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Runs a tool call that the [SafetyGate] already approved (and the user confirmed, if needed).
 * Most tools only hand off to a system screen where the user presses the final button.
 * Must be called on the main thread. Returns a JSON string for the model.
 */
class ToolExecutor(private val activity: Activity, private val gate: SafetyGate) {

    fun execute(call: ToolCall): String = try {
        when (call.name) {
            "open_app" -> openApp(call.str("app_name")!!)
            "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(call.str("url")!!)), "Opened link.")
            "web_search" -> webSearch(call.str("query")!!)
            "set_alarm" -> launch(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, call.int("hour")!!)
                    .putExtra(AlarmClock.EXTRA_MINUTES, call.int("minute")!!)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, call.str("label") ?: "")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
                "Alarm screen opened. The user must save it.",
            )
            "set_timer" -> launch(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, call.int("seconds")!!)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, call.str("label") ?: "")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
                "Timer screen opened.",
            )
            "open_camera" -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Camera opened. The user takes the photo.")
            "dial_number" -> launch(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(call.str("number")!!))),
                "Dialer opened. The user presses call.",
            )
            "compose_sms" -> launch(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(call.str("number")!!)))
                    .putExtra("sms_body", call.str("message") ?: ""),
                "SMS drafted. The user presses send.",
            )
            "compose_email" -> launch(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + call.str("to")!!))
                    .putExtra(Intent.EXTRA_SUBJECT, call.str("subject") ?: "")
                    .putExtra(Intent.EXTRA_TEXT, call.str("body") ?: ""),
                "Email drafted. The user presses send.",
            )
            "create_calendar_event" -> createEvent(call.str("title")!!, call.str("start")!!)
            "play_music" -> playMusic(call.str("query")!!)
            "navigate_to" -> launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(call.str("place")!!))),
                "Maps opened.",
            )
            "toggle_flashlight" -> flashlight(call.bool("on")!!)
            "get_time" -> Json.result(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            "get_battery" -> battery()
            else -> Json.error("Unknown tool.")
        }
    } catch (e: ActivityNotFoundException) {
        Json.error("No app on this phone can do that.")
    } catch (e: Exception) {
        Json.error("Failed: ${e.javaClass.simpleName}")
    }

    private fun launch(intent: Intent, okMessage: String): String {
        activity.startActivity(intent)
        return Json.result(okMessage)
    }

    private fun openApp(name: String): String {
        val pm = activity.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it)
        }
        val wanted = name.trim().lowercase()
        val match = apps.firstOrNull { it.first.lowercase() == wanted }
            ?: apps.firstOrNull { it.first.lowercase().contains(wanted) }
            ?: return Json.error("No installed app matches \"$name\".")
        // Second check on the real label and package name, not just what the model said.
        if (gate.isDeniedText(match.first + " " + match.second)) {
            return Json.error("Blocked: that app is off limits.")
        }
        val intent = pm.getLaunchIntentForPackage(match.second) ?: return Json.error("Cannot open that app.")
        return launch(intent, "Opened ${match.first}.")
    }

    private fun webSearch(query: String): String = try {
        launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query), "Search opened.")
    } catch (e: ActivityNotFoundException) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))), "Search opened.")
    }

    private fun playMusic(query: String): String = try {
        launch(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query),
            "Music requested.",
        )
    } catch (e: ActivityNotFoundException) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(query))), "Opened music search.")
    }

    private fun createEvent(title: String, start: String): String {
        val begin = LocalDateTime.parse(start, SafetyGate.START_FORMAT)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return launch(
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000L),
            "Calendar event drafted. The user presses save.",
        )
    }

    private fun flashlight(on: Boolean): String {
        val cm = activity.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Json.error("No flashlight on this phone.")
        cm.setTorchMode(id, on)
        return Json.result("Flashlight is ${if (on) "on" else "off"}.")
    }

    private fun battery(): String {
        val i = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Json.error("Battery info unavailable.")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return Json.error("Battery info unavailable.")
        return Json.result("${level * 100 / scale}% ${if (charging) "charging" else "not charging"}")
    }
}
