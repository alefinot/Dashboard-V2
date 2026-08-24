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
    const val REQUEST_CODE = 42
    var pendingCallback: ValueCallback<Array<Uri>>? = null
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
    activity: Activity,
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
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "application/json"
                activity.startActivity(intent)
            } catch (e: Exception) {
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
