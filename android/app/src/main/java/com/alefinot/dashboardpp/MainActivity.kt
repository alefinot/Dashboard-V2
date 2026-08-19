package com.alefinot.dashboardpp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.alefinot.dashboardpp.ui.screens.DashboardRoot
import com.alefinot.dashboardpp.ui.theme.HudTheme
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.webview.FileChooser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm = remember { ConnectionViewModel(application) }
            DisposableEffect(vm) {
                onDispose { vm.dispose() }
            }
            HudTheme {
                DashboardRoot(this, vm)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FileChooser.REQUEST_CODE) {
            val callback = FileChooser.pendingCallback
            FileChooser.pendingCallback = null
            val uris: Array<Uri>? = if (resultCode == RESULT_OK) {
                data?.data?.let { arrayOf(it) }
            } else {
                null
            }
            callback?.onReceiveValue(uris)
        }
    }
}
