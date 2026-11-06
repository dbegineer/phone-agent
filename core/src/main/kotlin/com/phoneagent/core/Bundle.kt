package com.phoneagent.core

import org.json.JSONObject
import java.security.MessageDigest

/** Permissions a mini-app may request. Anything else is rejected. */
object Permissions {
    const val STORAGE = "storage"
    const val NOTIFICATIONS = "notifications"
    val ALL = setOf(STORAGE, NOTIFICATIONS)
}

data class Manifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val permissions: Set<String>,
    val entry: String,
)

sealed class BundleCheck {
    data class Ok(val manifest: Manifest, val hash: String) : BundleCheck()
    data class Bad(val reason: String) : BundleCheck()
}

/**
 * Validates a mini-app bundle (manifest + files) before it may run.
 * Pure and deterministic: the model never gets to decide what is allowed.
 */
object BundleValidator {
    const val MAX_TOTAL_BYTES = 2_000_000
    const val MAX_FILES = 64
    val ALLOWED_EXT = setOf("html", "css", "js", "json", "svg", "png", "txt")
    private val ID_RE = Regex("^[a-z][a-z0-9-]{2,31}$")
    private val VERSION_RE = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")

    fun parseManifest(json: String): Result<Manifest> = runCatching {
        val o = JSONObject(json)
        val perms = mutableSetOf<String>()
        val arr = o.optJSONArray("permissions")
        if (arr != null) for (i in 0 until arr.length()) perms += arr.getString(i)
        Manifest(
            id = o.getString("id"),
            name = o.getString("name"),
            version = o.getString("version"),
            description = o.optString("description", ""),
            permissions = perms,
            entry = o.optString("entry", "index.html"),
        )
    }

    fun safePath(p: String): Boolean =
        p.isNotEmpty() && p.length <= 120 && !p.startsWith("/") && !p.contains("\\") &&
            !p.contains("..") && !p.contains(":") && p.split("/").none { it.isEmpty() || it.startsWith(".") } &&
            p.substringAfterLast('.', "").lowercase() in ALLOWED_EXT

    /** Deterministic SHA-256 over sorted (path, length, bytes). */
    fun hash(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (path in files.keys.sorted()) {
            val bytes = files.getValue(path)
            md.update(path.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(bytes.size.toString().toByteArray())
            md.update(0)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun check(files: Map<String, ByteArray>, expectedHash: String? = null): BundleCheck {
        if (files.size > MAX_FILES) return BundleCheck.Bad("too many files")
        if (files.values.sumOf { it.size.toLong() } > MAX_TOTAL_BYTES) return BundleCheck.Bad("bundle too large")
        for (p in files.keys) if (!safePath(p)) return BundleCheck.Bad("unsafe file path: $p")
        val raw = files["manifest.json"] ?: return BundleCheck.Bad("manifest.json missing")
        val m = parseManifest(String(raw, Charsets.UTF_8)).getOrElse { return BundleCheck.Bad("manifest invalid") }
        if (!ID_RE.matches(m.id)) return BundleCheck.Bad("bad app id")
        if (!VERSION_RE.matches(m.version)) return BundleCheck.Bad("bad version")
        if (m.name.isBlank() || m.name.length > 40) return BundleCheck.Bad("bad name")
        val unknown = m.permissions - Permissions.ALL
        if (unknown.isNotEmpty()) return BundleCheck.Bad("unknown permission: ${unknown.first()}")
        if (!safePath(m.entry) || !m.entry.endsWith(".html")) return BundleCheck.Bad("bad entry")
        if (m.entry !in files) return BundleCheck.Bad("entry file missing")
        val h = hash(files)
        if (expectedHash != null && !h.equals(expectedHash, ignoreCase = true)) return BundleCheck.Bad("hash mismatch")
        return BundleCheck.Ok(m, h)
    }
}
