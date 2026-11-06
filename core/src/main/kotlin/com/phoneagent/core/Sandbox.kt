package com.phoneagent.core

import java.net.URI

/**
 * Pure policy for the mini-app sandbox. The WebView layer only asks this object
 * "may this URL load, and from which bundle file?" so the rules are unit-tested on the JVM.
 */
object SandboxPolicy {
    const val HOST = "app.local"
    const val BASE = "https://$HOST/"

    /** Served on every response. No network of any kind, no frames, no forms, no plugins. */
    const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; " +
        "object-src 'none'; media-src 'none'; worker-src 'none'; form-action 'none'; " +
        "base-uri 'none'; frame-ancestors 'none'"

    /** Returns the bundle file path this URL maps to, or null if the load must be blocked. */
    fun resolve(url: String, entry: String, files: Set<String>): String? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host != HOST || u.port != -1 || u.userInfo != null) return null
        val raw = u.path ?: return null
        val path = if (raw == "/" || raw.isEmpty()) entry else raw.removePrefix("/")
        if (!BundleValidator.safePath(path)) return null
        if (path == "manifest.json") return null // host metadata, not served to the app
        return if (path in files) path else null
    }

    fun mime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        else -> "text/plain"
    }
}
