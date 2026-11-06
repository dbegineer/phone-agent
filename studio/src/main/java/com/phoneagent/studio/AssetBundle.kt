package com.phoneagent.studio

import android.content.res.AssetManager

/** Reads a mini-app bundle (directory of files) from APK assets into memory. */
object AssetBundle {
    fun load(am: AssetManager, dir: String): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        fun walk(path: String, rel: String) {
            val kids = am.list(path) ?: emptyArray()
            if (kids.isEmpty()) {
                out[rel] = am.open(path).use { it.readBytes() }
            } else {
                for (k in kids) walk("$path/$k", if (rel.isEmpty()) k else "$rel/$k")
            }
        }
        for (k in am.list(dir) ?: emptyArray()) walk("$dir/$k", k)
        return out
    }

    fun list(am: AssetManager, root: String = "miniapps"): List<String> =
        (am.list(root) ?: emptyArray()).sorted()
}
