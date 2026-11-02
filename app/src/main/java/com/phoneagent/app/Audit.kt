package com.phoneagent.app

import android.content.Context
import java.io.File
import java.time.Instant

/** Append-only local log of everything the agent was asked and did. Never leaves the phone. */
class Audit(context: Context) {
    private val file = File(context.filesDir, "audit.log")

    @Synchronized
    fun write(line: String) {
        try {
            if (file.length() > MAX_BYTES) file.writeText("") // simple cap; Phase 2 adds rotation
            file.appendText("${Instant.now()}  ${line.replace('\n', ' ')}\n")
        } catch (_: Exception) {
            // Logging must never crash the agent.
        }
    }

    private companion object {
        const val MAX_BYTES = 512_000L
    }
}
