package com.phoneagent.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Validates every bundled mini-app and runs a basic security lint over its source. */
class SampleBundleTest {
    private fun bundles(): List<File> {
        val root = listOf(File("../studio/src/main/assets/miniapps"), File("studio/src/main/assets/miniapps"))
            .first { it.isDirectory }
        return root.listFiles()!!.filter { it.isDirectory }
    }

    private fun load(dir: File): Map<String, ByteArray> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path.replace('\\', '/') to it.readBytes() }

    @Test fun allBundledAppsValidate() {
        val all = bundles()
        assertTrue(all.isNotEmpty())
        for (d in all) {
            val r = BundleValidator.check(load(d))
            assertTrue("${d.name}: $r", r is BundleCheck.Ok)
        }
    }

    @Test fun lintForbidsNetworkAndDynamicCode() {
        val banned = listOf(
            "fetch(", "XMLHttpRequest", "WebSocket", "EventSource", "sendBeacon", "importScripts",
            "eval(", "new Function", "document.write", "http://", "https://", "window.open", "<iframe", "<form",
        )
        for (d in bundles()) for ((path, bytes) in load(d)) {
            if (path == "manifest.json") continue
            val text = String(bytes)
            for (b in banned) assertTrue("${d.name}/$path contains $b", !text.contains(b))
        }
    }

    @Test fun noInlineScriptsOrHandlers() {
        for (d in bundles()) for ((path, bytes) in load(d)) {
            if (!path.endsWith(".html")) continue
            val text = String(bytes)
            assertTrue("$path inline script", !Regex("<script(?![^>]*\\bsrc=)[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertTrue("$path inline handler", !Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(text))
        }
    }
}
