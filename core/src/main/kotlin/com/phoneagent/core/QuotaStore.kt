package com.phoneagent.core

interface KvBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/** Per-mini-app key/value storage with hard quotas. The app never touches files directly. */
class QuotaStore(
    private val backend: KvBackend,
    private val maxKeys: Int = 100,
    private val maxValueBytes: Int = 64 * 1024,
    private val maxTotalBytes: Int = 512 * 1024,
) {
    private val keyRe = Regex("^[A-Za-z0-9_.-]{1,64}$")
    private fun size(k: String, v: String) = k.toByteArray().size + v.toByteArray().size

    fun get(key: String): String? = if (keyRe.matches(key)) backend.get(key) else null

    fun set(key: String, value: String): Boolean {
        if (!keyRe.matches(key)) return false
        if (value.toByteArray().size > maxValueBytes) return false
        val keys = backend.keys()
        if (key !in keys && keys.size >= maxKeys) return false
        var total = 0
        for (k in keys) if (k != key) total += size(k, backend.get(k) ?: "")
        if (total + size(key, value) > maxTotalBytes) return false
        backend.put(key, value)
        return true
    }

    fun remove(key: String) { if (keyRe.matches(key)) backend.remove(key) }

    fun keys(): List<String> = backend.keys().sorted()
}
