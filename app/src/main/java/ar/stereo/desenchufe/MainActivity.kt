package ar.stereo.desenchufe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    lateinit var web: WebView
    var pendingShare: JSONObject? = null
    var pendingIntercept: String? = null
    var pendingGo: String? = null

    companion object {
        @Volatile var current: MainActivity? = null
        const val HOST = "appassets.androidplatform.net"
        const val REQ_PERMS = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#0F1517")
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val nights = File(filesDir, "nights").apply { mkdirs() }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/nights/", WebViewAssetLoader.InternalStoragePathHandler(this, nights))
            .build()

        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == HOST) return false
                openExternal(u)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(this), "Android")
        setContentView(web)

        handleIntent(intent)
        web.loadUrl("https://$HOST/assets/www/index.html")
    }

    fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        send("pending", "")
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && (i.type ?: "").startsWith("text/")) {
            pendingShare = JSONObject()
                .put("text", i.getStringExtra(Intent.EXTRA_TEXT) ?: "")
                .put("subject", i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
        }
        i.getStringExtra("intercept")?.let { pendingIntercept = it }
        i.getStringExtra("go")?.let { pendingGo = it }
        i.removeExtra("intercept")
        i.removeExtra("go")
    }

    fun send(type: String, data: String) {
        runOnUiThread {
            if (::web.isInitialized) {
                web.evaluateJavascript(
                    "window.onNative&&window.onNative(${JSONObject.quote(type)},${JSONObject.quote(data)})",
                    null
                )
            }
        }
    }

    fun askPermissions() {
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            send("perm", "ok")
        } else {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            send("perm", if (mic) "ok" else "no")
        }
    }

    override fun onResume() {
        super.onResume()
        current = this
        send("resume", "")
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.onBack&&window.onBack())?'1':'0'") { r ->
            if (r != "\"1\"") moveTaskToBack(true)
        }
    }
}
