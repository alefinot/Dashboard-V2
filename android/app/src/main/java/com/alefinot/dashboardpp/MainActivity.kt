package com.alefinot.dashboardpp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.alefinot.dashboardpp.ui.screens.DashboardRoot
import com.alefinot.dashboardpp.ui.theme.HudTheme
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

class MainActivity : ComponentActivity() {
    // The runtime permissions the companion needs: 12+ BLE (scan + GATT),
    // 13+ POST_NOTIFICATIONS (the NotificationListenerService's gate),
    // 14+ NEARBY_WIFI_DEVICES (the NSD device discovery). Without them the
    // app is dead on 12+ (startScan throws / is swallowed, the listener
    // binding is refused). Requested at launch - before the BLE startScan
    // and the listener can run; the launcher skips already-granted /
    // denied permissions automatically.
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    /** The runtime permissions still needed on this API level (empty = none). */
    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 12+: the BLE central (startScan + the GATT link).
            needed += "android.permission.BLUETOOTH_SCAN"
            needed += "android.permission.BLUETOOTH_CONNECT"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 13+: the NotificationListenerService's post gate.
            needed += "android.permission.POST_NOTIFICATIONS"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 14+: the Wi-Fi (NSD) device discovery.
            needed += "android.permission.NEARBY_WIFI_DEVICES"
        }
        if (needed.isNotEmpty()) {
            permissionLauncher?.launch(needed.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }
        requestRuntimePermissions()
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
}
