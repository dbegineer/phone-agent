package com.phoneagent.studio

import android.Manifest as AndroidManifest
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.phoneagent.core.BundleCheck
import com.phoneagent.core.BundleValidator
import com.phoneagent.core.Permissions
import com.phoneagent.core.SandboxPolicy
import java.io.ByteArrayInputStream

class StudioActivity : Activity() {

    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = TextView(this).apply {
            setPadding(24, 24, 24, 24); textSize = 14f; setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        root.addView(header)
        setContentView(root)

        val dir = "miniapps/" + AssetBundle.list(assets).first()
        val files = AssetBundle.load(assets, dir)
        when (val check = BundleValidator.check(files)) {
            is BundleCheck.Bad -> {
                header.text = "Blocked: ${check.reason}"
                return
            }
            is BundleCheck.Ok -> {
                val m = check.manifest
                header.text = "${m.name} v${m.version}  |  permissions: " +
                    (m.permissions.joinToString().ifEmpty { "none" }) + "  |  sha256 ${check.hash.take(12)}"
                if (Permissions.NOTIFICATIONS in m.permissions && checkSelfPermission(AndroidManifest.permission.POST_NOTIFICATIONS) != 0)
                    requestPermissions(arrayOf(AndroidManifest.permission.POST_NOTIFICATIONS), 1)

                val names = files.keys
                val web = WebView(this)
                web.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                WebView.setWebContentsDebuggingEnabled(false)
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    saveFormData = false
                }
                web.addJavascriptInterface(Bridge(this, m), "Host")
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean =
                        SandboxPolicy.resolve(r.url.toString(), m.entry, names) == null // true = block

                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                        val path = SandboxPolicy.resolve(r.url.toString(), m.entry, names) ?: return blocked()
                        val headers = mapOf(
                            "Content-Security-Policy" to SandboxPolicy.CSP,
                            "X-Content-Type-Options" to "nosniff",
                            "Cache-Control" to "no-store",
                        )
                        return WebResourceResponse(
                            SandboxPolicy.mime(path), "utf-8", 200, "OK", headers,
                            ByteArrayInputStream(files.getValue(path)),
                        )
                    }
                }
                root.addView(web)
                web.loadUrl(SandboxPolicy.BASE)
            }
        }
    }
}
