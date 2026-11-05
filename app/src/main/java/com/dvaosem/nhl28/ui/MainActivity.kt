package com.dvaosem.nhl28.ui

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dvaosem.nhl28.R

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
    }

    private lateinit var web: WebView
    private val ui = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var updateDialogShown = false
    private var failedLoad = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)

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
        }

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

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(START_URL)
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

    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (updateDialogShown || now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        Thread {
            val rel = UpdateManager.latest() ?: return@Thread
            if (rel.build > UpdateManager.currentBuild(this)) ui.post { offerUpdate(rel) }
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
