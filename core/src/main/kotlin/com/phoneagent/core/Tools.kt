package com.phoneagent.core

enum class Risk { SAFE, CONFIRM }

data class Param(val type: String, val description: String)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: Map<String, Param>,
    val required: List<String>,
    val risk: Risk,
    /**
     * True if the tool opens another app's screen. Android blocks background launches,
     * so the task ends after such a tool runs.
     */
    val terminal: Boolean,
) {
    /** OpenAPI-style JSON description handed to the on-device model. */
    fun toJsonString(): String {
        val props = params.entries.joinToString(",") { (k, p) ->
            "${Json.str(k)}:{\"type\":${Json.str(p.type)},\"description\":${Json.str(p.description)}}"
        }
        val req = required.joinToString(",") { Json.str(it) }
        return "{\"name\":${Json.str(name)},\"description\":${Json.str(description)}," +
            "\"parameters\":{\"type\":\"object\",\"properties\":{$props},\"required\":[$req]}}"
    }
}

object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun result(msg: String) = "{\"result\":${str(msg)}}"
    fun error(msg: String) = "{\"error\":${str(msg)}}"
}

/** The complete allowlist. If a tool is not here, the agent cannot do it. */
object Tools {
    private fun s(d: String) = Param("string", d)
    private fun i(d: String) = Param("integer", d)
    private fun b(d: String) = Param("boolean", d)

    val all: List<ToolSpec> = listOf(
        ToolSpec("open_app", "Open an installed app by name, for example Camera, Maps or YouTube.",
            mapOf("app_name" to s("App name as shown on the home screen")), listOf("app_name"), Risk.CONFIRM, true),
        ToolSpec("open_url", "Open a website. Only https links.",
            mapOf("url" to s("Full https URL")), listOf("url"), Risk.CONFIRM, true),
        ToolSpec("web_search", "Search the web for a query.",
            mapOf("query" to s("Search text")), listOf("query"), Risk.SAFE, true),
        ToolSpec("set_alarm", "Set an alarm at a clock time (24-hour).",
            mapOf("hour" to i("Hour 0-23"), "minute" to i("Minute 0-59"), "label" to s("Optional alarm label")),
            listOf("hour", "minute"), Risk.CONFIRM, true),
        ToolSpec("set_timer", "Start a countdown timer.",
            mapOf("seconds" to i("Length in seconds"), "label" to s("Optional timer label")),
            listOf("seconds"), Risk.SAFE, true),
        ToolSpec("open_camera", "Open the camera app so the user can take a photo. The user presses the shutter.",
            emptyMap(), emptyList(), Risk.CONFIRM, true),
        ToolSpec("dial_number", "Open the phone dialer with a number. The user presses call.",
            mapOf("number" to s("Phone number, digits with optional leading +")), listOf("number"), Risk.CONFIRM, true),
        ToolSpec("compose_sms", "Draft a text message. The user reviews and presses send.",
            mapOf("number" to s("Phone number"), "message" to s("Message text")), listOf("number", "message"), Risk.CONFIRM, true),
        ToolSpec("compose_email", "Draft an email. The user reviews and presses send.",
            mapOf("to" to s("Recipient email address"), "subject" to s("Subject line"), "body" to s("Email body")),
            listOf("to"), Risk.CONFIRM, true),
        ToolSpec("create_calendar_event", "Add an event to the calendar. The user reviews and saves it.",
            mapOf("title" to s("Event title"), "start" to s("Local start time as yyyy-MM-dd HH:mm, 24-hour")),
            listOf("title", "start"), Risk.CONFIRM, true),
        ToolSpec("play_music", "Play a song, artist or genre.",
            mapOf("query" to s("Song, artist or genre")), listOf("query"), Risk.CONFIRM, true),
        ToolSpec("navigate_to", "Show a place in the maps app.",
            mapOf("place" to s("Place name or address")), listOf("place"), Risk.CONFIRM, true),
        ToolSpec("toggle_flashlight", "Turn the flashlight on or off.",
            mapOf("on" to b("true for on, false for off")), listOf("on"), Risk.SAFE, false),
        ToolSpec("get_time", "Get the current local date and time.", emptyMap(), emptyList(), Risk.SAFE, false),
        ToolSpec("get_battery", "Get the battery percentage and charging state.", emptyMap(), emptyList(), Risk.SAFE, false),
    )

    val byName: Map<String, ToolSpec> = all.associateBy { it.name }
}
