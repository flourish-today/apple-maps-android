package org.getflourish.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.ContextMenu
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var offline: TextView
    private var location: Pair<String, GeolocationPermissions.Callback>? = null
    private val back = OnBackInvokedCallback { web.goBack() }
    private var backRegistered = false
    private var failed = false
    private val touch = PointF()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest) = filter(request)
        })

        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        web = WebView(this)
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            userAgentString = chromeUserAgent(userAgentString)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        registerForContextMenu(web)
        web.setOnTouchListener { _, event ->
            touch.set(event.x, event.y)
            false
        }
        web.setOnLongClickListener { view ->
            web.hitTestResult.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && view.showContextMenu(touch.x, touch.y)
        }
        web.setDownloadListener { _, _, _, _, _ -> }

        offline = TextView(this).apply {
            setText(R.string.offline)
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener {
                failed = false
                visibility = View.GONE
                web.reload()
            }
        }
        val root = FrameLayout(this)
        root.addView(web)
        root.addView(offline)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.tappableElement() or WindowInsets.Type.ime())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) return
        if (!cookiesCleared) {
            cookiesCleared = true
            CookieManager.getInstance().removeAllCookies { web.loadUrl(start(intent)) }
        } else {
            web.loadUrl(start(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) web.loadUrl(start(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun start(intent: Intent?): String = try {
        page(intent)
    } catch (_: RuntimeException) {
        HOME
    }

    private fun page(intent: Intent?): String {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return HOME
        if (isPage(data)) return data.toString()
        if (data.scheme != "geo") return HOME
        val part = data.schemeSpecificPart
        val coordinates = part.substringBefore('?')
        val query = part.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("q=") }?.let { Uri.decode(it.substring(2)) }
        val point = Regex("""-?\d{1,3}(\.\d+)?,-?\d{1,3}(\.\d+)?""").matchEntire(coordinates)?.value
            ?.takeUnless { it.split(',').all { n -> n.toDouble() == 0.0 } }
        return when {
            !query.isNullOrBlank() -> "$HOME?q=${Uri.encode(query)}" + (point?.let { "&sll=$it" } ?: "")
            point != null -> "$HOME?ll=$point&q=$point"
            else -> HOME
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, info: ContextMenu.ContextMenuInfo?) {
        val hit = web.hitTestResult
        val link = hit.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE } ?: return
        menu.add(R.string.copy_link).setOnMenuItemClickListener {
            val text = if (link.startsWith("tel:")) Uri.decode(link.removePrefix("tel:")) else link
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
            true
        }
    }

    private fun openOutside(url: Uri) {
        val intent = when (url.scheme) {
            "https", "http" -> Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
            "tel" -> Intent(Intent.ACTION_DIAL, url)
            else -> return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun updateBack() {
        val want = web.canGoBack()
        if (want == backRegistered) return
        backRegistered = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val allowed = isPage(request.url)
            val outside = !allowed && request.isForMainFrame && request.hasGesture()
            if (BuildConfig.TEST) Log.i(TAG, "${if (allowed) "NAV" else if (outside) "NAV-OUTSIDE" else "NAV-BLOCK"} " +
                "main=${request.isForMainFrame} redirect=${request.isRedirect} gesture=${request.hasGesture()} ${request.url}")
            if (outside) openOutside(request.url)
            return !allowed
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = filter(request)

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = updateBack()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            failed = true
            web.visibility = View.INVISIBLE
            offline.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!failed) web.visibility = View.VISIBLE
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!isPage(Uri.parse(origin))) return callback.invoke(origin, false, false)
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                return callback.invoke(origin, true, false)
            }
            location = origin to callback
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
            if (BuildConfig.TEST) Log.i(TAG, "WINDOW gesture=$gesture")
            if (!gesture) return false
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (BuildConfig.TEST) Log.i(TAG, "WINDOW-OPEN ${request.url}")
                    if (isPage(request.url)) web.loadUrl(request.url.toString()) else openOutside(request.url)
                    view.destroy()
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = filter(request)
            }
            (result.obj as WebView.WebViewTransport).webView = popup
            result.sendToTarget()
            return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val (origin, callback) = location ?: return
        location = null
        callback.invoke(origin, grantResults.any { it == PackageManager.PERMISSION_GRANTED }, false)
    }

    companion object {
        private const val TAG = "MapsTest"
        private const val HOME = "https://maps.apple.com/"
        private var cookiesCleared = false

        private val HOSTS = setOf("maps.apple.com")
        private val HOST_SUFFIXES = listOf(".apple-mapkit.com", ".ls.apple.com", ".mzstatic.com")

        fun isPage(uri: Uri) = uri.scheme == "https" && uri.host == "maps.apple.com" &&
            (uri.port == -1 || uri.port == 443) && uri.userInfo == null

        private fun isTracker(uri: Uri): Boolean {
            val path = uri.path.orEmpty()
            return (uri.host == "maps.apple.com" &&
                (path.startsWith("/data/analytics") || path.startsWith("/data/performanceAnalytics"))) ||
                path.contains("reportAnalytics")
        }

        private fun allowed(request: WebResourceRequest): Boolean {
            val uri = request.url
            if (request.isForMainFrame) return isPage(uri)
            if (request.requestHeaders["Accept"].orEmpty().startsWith("text/html")) return false
            val host = uri.host ?: return false
            return uri.scheme == "https" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null &&
                (host in HOSTS || HOST_SUFFIXES.any { host.endsWith(it) } ||
                    (host == "www.apple.com" && uri.path.orEmpty().startsWith("/wss/fonts/"))) && !isTracker(uri)
        }

        fun filter(request: WebResourceRequest): WebResourceResponse? {
            val ok = allowed(request)
            if (BuildConfig.TEST) Log.i(TAG, "${if (ok) "ALLOW" else "BLOCK"} main=${request.isForMainFrame} " +
                "${request.method} accept=${request.requestHeaders["Accept"]} ${request.url}")
            return if (ok) null else WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(),
                ByteArrayInputStream(ByteArray(0)))
        }

        fun chromeUserAgent(default: String): String {
            val major = Regex("""Chrome/(\d+)""").find(default)?.groupValues?.get(1) ?: return default
            return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Mobile Safari/537.36"
        }
    }
}
