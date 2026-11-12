package com.alefinot.dashboardpp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.alefinot.dashboardpp.storage.AppRoot
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.ui.components.ClearFocusWhenKeyboardCloses
import com.alefinot.dashboardpp.ui.screens.DashboardRoot
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudTheme
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * POST_NOTIFICATIONS is asked for on Android 13+ because the foreground
     * service that keeps the trip log alive shows an ongoing notification. A
     * foreground service whose notification is blocked gets torn down on many
     * devices, which would silently punch holes into the recording. The grant is
     * not required to record — only to show why recording is running — so the
     * app behaves the same when the user refuses.
     *
     * WRITE_EXTERNAL_STORAGE rides along on Android 8.1 through 10, where it is what makes
     * `Documents/Dashboard++` writable at all: on 8.1/9 because scoped storage does not exist,
     * on Android 10 because this app opts out of it with `requestLegacyExternalStorage` (see
     * [AppRoot.isGranted] for the measurements, and for why the ask stops at API 29). Refusing it
     * changes nothing about whether a drive is recorded: the files land in the app-private folder
     * and the Storage card says so, which is the same degraded path a 11+ device without All
     * files access takes. From Android 11 that page, not this dialog, is where an owner goes -
     * `MANAGE_EXTERNAL_STORAGE` is a special access and no code can bring the dialog up for it.
     *
     * The result is not looked at: the recorder runs either way, and because [AppRoot] re-decides
     * which folder to write into on every lookup, an Allow that arrives after launch takes effect
     * on the next write rather than at the next install.
     */
    private val permissionRequests = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        warmPreferences()
        setContent {
            val vm = remember { ConnectionViewModel(application) }
            DisposableEffect(vm) {
                onDispose { vm.dispose() }
            }
            HudTheme {
                // One rule for every text field in the app: Back closes the keyboard, and
                // the field it was typing into lets go of focus at the same moment.
                ClearFocusWhenKeyboardCloses()
                DashboardRoot(this, vm)
            }
        }
    }

    /**
     * The app's switches live in DataStore, which never answers synchronously, and
     * the splash window is the only stretch of idle the app gets before its first
     * frame is on screen. Reading them there - off the main thread, mirrored onto
     * the theme on it - is what lets the first page entrance know the motion switch
     * as it composes, rather than starting to animate and stopping mid-fade. The
     * recording switch matters the same way to the service start in [ConnectedTabs],
     * whose cold path is a DataStore read on the boot critical path otherwise.
     */
    private fun warmPreferences() {
        lifecycleScope.launch(Dispatchers.Main) {
            val settings = RecorderSettings(application)
            withContext(Dispatchers.IO) { settings.warm() }
            HudMotion.setPrefEnabled(settings.motionEnabled.first())
        }
    }

    /**
     * The radio permissions are *not* asked for here. They are asked for by the shell
     * ([ConnectedTabs]) the moment it composes, which is the first moment there is a page
     * on screen to explain a refusal - and the same launcher is what a grant resumes
     * through, so the ask and the retry are one piece. Android 13 notifications, by
     * contrast, have nothing to do with reaching the dashboard, so they keep their own
     * quiet ask at launch.
     */
    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionRequests.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The two asks never overlap an API level: notifications are 33+ and this one is Android
        // 8.1 through 10 only, so the single launcher is never asked to hold two permissions at
        // once. From Android 11 neither fires and nothing is shown at launch - All files access
        // has no dialog to raise, and the Storage card is how an owner gets to its page.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !AppRoot.isGranted(this)) {
            permissionRequests.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
