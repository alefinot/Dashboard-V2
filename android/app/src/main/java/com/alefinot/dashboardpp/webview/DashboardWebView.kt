package com.alefinot.dashboardpp.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

object FileChooser {
    // The launcher is registered by the composition that hosts the WebView
    // (DashboardWebScreen); onShowFileChooser stashes the callback and
    // launches it. The result is delivered via deliverFilePick.
    var launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
    var pendingCallback: ValueCallback<Array<Uri>>? = null
}

/**
 * Resolve the pending file-chooser callback (exactly once). A null uri is
 * a cancel / failure; the WebUI input gets a definitive answer instead of
 * hanging.
 */
fun deliverFilePick(uri: Uri?) {
    val cb = FileChooser.pendingCallback
    FileChooser.pendingCallback = null
    cb?.onReceiveValue(if (uri == null) null else arrayOf(uri))
}

/**
 * Creates the WebView that hosts the ESP32's Web UI, with a load timeout
 * (device asleep / powered off) and the file picker used by the
 * backup-import input on the ESP UI. Pull-to-refresh is handled by the
 * WebUI itself (floating arrow gesture); the WebView must not intercept
 * touches or it interrupts the arrow / double-reloads.
 */
@SuppressLint("SetJavaScriptEnabled")
fun createDashboardWebView(
    context: Context,
    ip: String,
    vm: ConnectionViewModel,
): WebView {
    val webView = WebView(context)
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // The WebUI never loads local files; file:// access is disabled.
    settings.allowFileAccess = false
    webView.setBackgroundColor(android.graphics.Color.parseColor("#05080D"))

    val handler = Handler(Looper.getMainLooper())
    var loaded = false
    val timeoutRunnable = Runnable {
        if (!loaded) vm.onWebLost(ip)
    }
    handler.postDelayed(timeoutRunnable, 8000)

    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            loaded = true
            handler.removeCallbacks(timeoutRunnable)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            val host = request?.url?.host ?: return
            if (host == ip) {
                loaded = true
                handler.removeCallbacks(timeoutRunnable)
                vm.onWebLost(ip)
            }
        }
    }

    webView.setWebChromeClient(object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            FileChooser.pendingCallback = filePathCallback
            // The picker is launched through the composition-registered
            // launcher; a missing launcher or a failed launch resolves the
            // callback with null (the WebUI input gets a definitive answer).
            val launcher = FileChooser.launcher
            if (launcher == null) {
                deliverFilePick(null)
                return true
            }
            try {
                launcher.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply { type = "application/json" },
                )
            } catch (e: Exception) {
                deliverFilePick(null)
            }
            return true
        }
    })

    // Pull-to-refresh lives in the WebUI itself (floating arrow + reload);
    // keep the WebView's touch handling untouched so the arrow gesture
    // isn't interrupted or double-reloaded.

    webView.loadUrl("http://$ip/")
    return webView
}
