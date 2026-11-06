package com.phoneagent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleValidatorTest {
    private fun manifest(extra: String = "", perms: String = "\"storage\"") =
        """{"id":"fit-track","name":"Fit","version":"1.0.0","permissions":[$perms],"entry":"index.html"$extra}"""

    private fun files(m: String = manifest()) = mapOf(
        "manifest.json" to m.toByteArray(),
        "index.html" to "<html></html>".toByteArray(),
    )

    private fun bad(r: BundleCheck): String = (r as BundleCheck.Bad).reason

    @Test fun validBundlePasses() {
        val r = BundleValidator.check(files())
        assertTrue(r is BundleCheck.Ok)
        assertEquals("fit-track", (r as BundleCheck.Ok).manifest.id)
    }

    @Test fun networkPermissionRejected() {
        assertTrue(bad(BundleValidator.check(files(manifest(perms = "\"storage\",\"network\""))))
            .contains("unknown permission"))
    }

    @Test fun pathTraversalRejected() {
        val f = files() + ("../evil.js" to ByteArray(1))
        assertTrue(bad(BundleValidator.check(f)).contains("unsafe"))
    }

    @Test fun absoluteAndSchemePathsRejected() {
        for (p in listOf("/etc/x.js", "http://x/a.js", "a\\b.js", ".hidden.js", "a//b.js"))
            assertTrue(p, bad(BundleValidator.check(files() + (p to ByteArray(1)))).contains("unsafe"))
    }

    @Test fun disallowedExtensionRejected() {
        assertTrue(bad(BundleValidator.check(files() + ("run.exe" to ByteArray(1)))).contains("unsafe"))
        assertTrue(bad(BundleValidator.check(files() + ("lib.so" to ByteArray(1)))).contains("unsafe"))
    }

    @Test fun missingManifestOrEntryRejected() {
        assertTrue(bad(BundleValidator.check(mapOf("index.html" to ByteArray(1)))).contains("manifest.json missing"))
        assertTrue(bad(BundleValidator.check(mapOf("manifest.json" to manifest().toByteArray()))).contains("entry file missing"))
    }

    @Test fun badIdAndVersionRejected() {
        val badId = manifest().replace("fit-track", "Bad ID")
        assertTrue(bad(BundleValidator.check(files(badId))).contains("app id"))
        val badVer = manifest().replace("1.0.0", "1.x")
        assertTrue(bad(BundleValidator.check(files(badVer))).contains("version"))
    }

    @Test fun hashIsDeterministicAndOrderIndependent() {
        val a = linkedMapOf("a.js" to byteArrayOf(1), "b.js" to byteArrayOf(2))
        val b = linkedMapOf("b.js" to byteArrayOf(2), "a.js" to byteArrayOf(1))
        assertEquals(BundleValidator.hash(a), BundleValidator.hash(b))
    }

    @Test fun anyByteChangeChangesHash() {
        val f = files()
        val h = BundleValidator.hash(f)
        val tampered = f + ("index.html" to "<html>x</html>".toByteArray())
        assertTrue(h != BundleValidator.hash(tampered))
        assertTrue(bad(BundleValidator.check(tampered, h)).contains("hash mismatch"))
        assertTrue(BundleValidator.check(f, h) is BundleCheck.Ok)
    }

    @Test fun oversizeRejected() {
        val big = files() + ("big.txt" to ByteArray(BundleValidator.MAX_TOTAL_BYTES + 1))
        assertTrue(bad(BundleValidator.check(big)).contains("too large"))
    }
}
