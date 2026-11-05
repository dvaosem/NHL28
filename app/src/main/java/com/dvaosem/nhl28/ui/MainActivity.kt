package com.dvaosem.nhl28.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.dvaosem.nhl28.R
import com.dvaosem.nhl28.widget.BoardWidget
import com.dvaosem.nhl28.widget.ChatWidget
import com.dvaosem.nhl28.widget.ResultsWidget
import com.dvaosem.nhl28.widget.TipsWidget
import com.dvaosem.nhl28.widget.WidgetStore
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

/**
 * Thin WebView shell around https://dvaosem.com/nhl/.
 * The web app is updated through the hosting; the APK changes only when this shell does.
 * On start/resume it checks GitHub Releases for a newer APK and offers to install it.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val START_URL = "https://dvaosem.com/nhl/"
        private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private val HOSTS = setOf("dvaosem.com", "www.dvaosem.com")

        // WebView quirk: fixed bars with backdrop-filter can vanish while scrolling, so drop the blur in the app.
        private const val FIX_JS = "(function(){if(document.getElementById('nhl28-app-fix'))return;" +
            "var s=document.createElement('style');s.id='nhl28-app-fix';" +
            "s.textContent='nav.tabbar,header{-webkit-backdrop-filter:none!important;backdrop-filter:none!important}" +
            "nav.tabbar{transform:translateZ(0)}';document.head.appendChild(s);})();"
    }

    /** Called from the web app (window.NHL28App). */
    inner class AppBridge {
        @JavascriptInterface fun versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        @JavascriptInterface fun versionCode(): Int = UpdateManager.currentBuild(this@MainActivity)
        @JavascriptInterface fun checkUpdate() { ui.post { checkForUpdate(true) } }
        /** The page's own update bar calls this; the GitHub check handles the actual download. */
        @JavascriptInterface fun update(url: String) { ui.post { checkForUpdate(true) } }

        // --- tipping widget: the page pushes games, the widget queues tips, the page sends them ---
        @JavascriptInterface fun saveGames(json: String) {
            try { WidgetStore.saveData(applicationContext, json); TipsWidget.refreshAll(applicationContext) } catch (_: Exception) {}
        }
        /** Generic widget data: kind = "results" | "board" | "chat". */
        @JavascriptInterface fun saveWidgetData(kind: String, json: String) {
            try {
                WidgetStore.putKind(applicationContext, kind, json)
                when (kind) {
                    "results" -> ResultsWidget.refreshAll(applicationContext)
                    "chat" -> ChatWidget.refreshAll(applicationContext)
                    "board" -> BoardWidget.refreshAll(applicationContext)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface fun getPendingTips(): String = WidgetStore.pendingJson(applicationContext)
        @JavascriptInterface fun clearPendingTips(idsJson: String) {
            try { WidgetStore.clearPending(applicationContext, idsJson); TipsWidget.refreshAll(applicationContext) } catch (_: Exception) {}
        }

        /** Keeps the status/navigation bars in the page's theme color. */
        @JavascriptInterface fun setBarColor(hex: String) {
            ui.post {
                try {
                    val c = Color.parseColor(hex)
                    window.statusBarColor = c
                    window.navigationBarColor = c
                    web.setBackgroundColor(c)
                } catch (_: Exception) {}
            }
        }

        // --- native push (the page uses these instead of web push) ---
        @JavascriptInterface fun pushAvailable(): Boolean =
            try { FirebaseApp.getApps(this@MainActivity).isNotEmpty() } catch (_: Exception) { false }

        @JavascriptInterface fun notifPermission(): String = permState()

        /** Result comes back through window.__nhlPermResult('granted' | 'denied'). */
        @JavascriptInterface fun requestNotifPermission() {
            ui.post {
                when (permState()) {
                    "granted" -> sendPerm("granted")
                    "denied" -> {
                        // Android won't show the dialog again: send the user to the app's notification settings
                        try {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        } catch (_: Exception) {}
                        sendPerm("denied")
                    }
                    else -> if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else sendPerm(permState())
                }
            }
        }

        /** Result comes back through window.__nhlToken(token), empty string on failure. */
        @JavascriptInterface fun requestFcmToken() {
            ui.post {
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                        sendToken(if (t.isSuccessful) (t.result ?: "") else "")
                    }
                } catch (_: Exception) { sendToken("") }
            }
        }
    }

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var updateDialogShown = false
    private var failedLoad = false
    private val prefs by lazy { getSharedPreferences("nhl28", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.edit().putBoolean("asked", true).apply()
        sendPerm(if (granted) "granted" else "denied")
    }

    private fun sendPerm(v: String) {
        web.evaluateJavascript("window.__nhlPermResult&&window.__nhlPermResult('$v')", null)
    }

    private fun sendToken(v: String) {
        web.evaluateJavascript("window.__nhlToken&&window.__nhlToken(${JSONObject.quote(v)})", null)
    }

    private fun permState(): String {
        if (Build.VERSION.SDK_INT < 33) {
            return if (NotificationManagerCompat.from(this).areNotificationsEnabled()) "granted" else "denied"
        }
        val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return if (ok) "granted" else if (prefs.getBoolean("asked", false)) "denied" else "default"
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nhl28_reminders", "Pripomienky a chat", NotificationManager.IMPORTANCE_HIGH))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        createChannel()

        web.setBackgroundColor(0xFF1D1B19.toInt())
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " NHL28App"
            // ignore the system font scale: the page has its own text-size setting
            textZoom = 100
        }
        web.addJavascriptInterface(AppBridge(), "NHL28App")

        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host in HOSTS) return false
                // external links (mailto:, tel:, other sites) open outside the app
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Cannot open link", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failedLoad = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith(START_URL.substringBeforeLast("/nhl/"))) view.evaluateJavascript(FIX_JS, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    failedLoad = true
                    view.loadDataWithBaseURL(null, offlineHtml(), "text/html", "utf-8", null)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!failedLoad && web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(if (intent?.getStringExtra("open") == "chat") START_URL + "#chat" else START_URL)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("open") == "chat") {
            web.evaluateJavascript("(function(){var t=document.getElementById('tab-chat');if(t&&!t.hidden)t.click();})()", null)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // offline page is showing and the network may be back: retry
        if (failedLoad) { failedLoad = false; web.loadUrl(START_URL) }
        checkForUpdate()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun offlineHtml(): String =
        "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
        "<body style='background:#1d1b19;color:#e8e2da;font-family:sans-serif;text-align:center;padding-top:30vh'>" +
        "<h2>No connection</h2><p style='color:#a39b91'>NHL28 needs internet.</p>" +
        "<p><a href='$START_URL' style='color:#d97757'>Try again</a></p></body></html>"

    private fun checkForUpdate(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (updateDialogShown) return
        if (!manual && now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        Thread {
            val rel = UpdateManager.latest()
            ui.post {
                when {
                    rel == null -> if (manual) Toast.makeText(this, "Cannot reach GitHub", Toast.LENGTH_SHORT).show()
                    rel.build > UpdateManager.currentBuild(this) -> offerUpdate(rel)
                    manual -> Toast.makeText(this, "You have the latest version", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun offerUpdate(rel: UpdateManager.Release) {
        if (isFinishing || isDestroyed) return
        updateDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("NHL28 build ${rel.build} is ready." +
                (if (rel.notes.isNotBlank()) "\n\n${rel.notes}" else ""))
            .setPositiveButton("Update") { _, _ -> startUpdate(rel) }
            .setNegativeButton("Later") { _, _ -> updateDialogShown = false }
            .setOnCancelListener { updateDialogShown = false }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun startUpdate(rel: UpdateManager.Release) {
        if (!UpdateManager.canInstall(this)) {
            Toast.makeText(this, "Allow installs from NHL28, then tap Update again", Toast.LENGTH_LONG).show()
            UpdateManager.openInstallPermissionSettings(this)
            updateDialogShown = false
            lastCheck = 0L
            return
        }
        val progress = ProgressDialog(this).apply {
            setMessage("Downloading…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        Thread {
            val apk = UpdateManager.download(this, rel.apkUrl) { p -> ui.post { progress.progress = p } }
            ui.post {
                progress.dismiss()
                updateDialogShown = false
                if (apk == null) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_LONG).show()
                } else {
                    UpdateManager.installSession(this, apk)
                }
            }
        }.start()
    }
}
