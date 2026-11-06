package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxTest {
    private val files = setOf("manifest.json", "index.html", "app.js", "css/style.css")
    private fun r(url: String) = SandboxPolicy.resolve(url, "index.html", files)

    @Test fun servesBundleFilesOnVirtualOrigin() {
        assertEquals("index.html", r("https://app.local/"))
        assertEquals("app.js", r("https://app.local/app.js"))
        assertEquals("css/style.css", r("https://app.local/css/style.css?v=1#x"))
    }

    @Test fun blocksEveryOtherOrigin() {
        for (u in listOf(
            "https://example.com/app.js", "http://app.local/app.js", "https://app.local:8443/app.js",
            "https://evil@app.local/app.js", "https://app.local.evil.com/app.js",
            "file:///data/data/com.phoneagent.studio/files/x", "content://sms/inbox",
            "javascript:alert(1)", "data:text/html,<script>1</script>", "intent://x#Intent;end",
            "ftp://app.local/app.js", "not a url",
        )) assertNull(u, r(u))
    }

    @Test fun blocksTraversalAndMissingFiles() {
        for (u in listOf(
            "https://app.local/../secret.js", "https://app.local/%2e%2e/secret.js",
            "https://app.local/css/../../x.js", "https://app.local/missing.js", "https://app.local//app.js",
        )) assertNull(u, r(u))
    }

    @Test fun manifestIsNotServedToTheApp() {
        assertNull(r("https://app.local/manifest.json"))
    }

    @Test fun cspForbidsNetworkFramesAndForms() {
        val csp = SandboxPolicy.CSP
        assertTrue(csp.contains("default-src 'none'"))
        assertTrue(csp.contains("connect-src 'none'"))
        assertTrue(csp.contains("frame-src 'none'"))
        assertTrue(csp.contains("form-action 'none'"))
        assertFalse(csp.contains("unsafe-eval"))
        assertFalse(csp.contains("http:"))
        assertFalse(Regex("script-src[^;]*unsafe-inline").containsMatchIn(csp))
    }
}
