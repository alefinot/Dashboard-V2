package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.hudGlass

/**
 * What a page says when the thing it shows needs a device that is not there.
 *
 * It is a **card on the page**, not a screen: the tab keeps its place in the swipe order,
 * the bar stays, and the message sits where the content would have been. That is the
 * whole of the replacement for the old offline screen, which was a route of its own and
 * said "NO DASHBOARD ON THE NETWORK" - a sentence with no subject now that there is no
 * network to be off [plan §4].
 *
 * This is the whole of the offline treatment for Live and the ESP slot: the page
 * stays, the bar stays, the swipe order stays, and the page answers instead of
 * disappearing. It borrows the confirmation box's furniture (`hudGlass`, the same
 * width cap, the same scrim-free centred column) because that is the one panel in
 * this app that already knows how to sit over a page and be read.
 *
 * It carries **no connect furniture**. Pairing, scanning, connect and disconnect live on
 * the vehicle page and nowhere else, because a device belongs to a vehicle and choosing
 * one is the vehicle page's job: a FIND DEVICE button here would be a second way to do
 * the same thing, and the second way is what makes a connection impossible to explain.
 * What the cover does instead is say which vehicle it is waiting for, quote the reason
 * the view model gave, and offer one action, [onOpenVehicles], which takes the user to
 * the page that can act.
 *
 * Two things it now says for itself, because they are the only reasons a dashboard can
 * be out of range that this page can tell apart on its own:
 *
 *  - **Android has not let the app look** ([permissionProblem]). This is not a dashboard
 *    problem and it must not be dressed as one: the radio is off-limits until the
 *    permission is granted, so the cover names it and the button goes to the page where
 *    granting it is asked for. Before Bluetooth there was a near-identical case, "Android
 *    is not letting us see any networks", and it had its own screen; it is the same
 *    sentence now as it is here.
 *  - **This phone has never met a dashboard** ([firstRun]). The old app's first run was a
 *    screen of instructions about the access point name and where the IP is printed. That
 *    guidance has no subject any more — there is nothing to look up — so the first run
 *    says one thing instead: pair from the vehicle page.
 *
 * It is deliberately not a screen: no route, no back handler, no state of its own.
 * It is drawn by the page, from the reason the view model supplied, and it goes away
 * by itself the moment a connection comes back.
 */
@Composable
fun NotLinkedCover(
    title: String,
    reason: String?,
    note: String,
    vehicleName: String?,
    onOpenVehicles: () -> Unit,
    permissionProblem: Boolean = false,
    firstRun: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Opaque on purpose. The ESP slot is a hole in the app rather than a page, so on
    // that tab the cover has to carry the background the WebView would have painted;
    // leaving it translucent would put the bar's glow through the middle of the
    // message. (Measured: a frame with this area at zero luminance is the one frame
    // the design rules out.)
    Box(
        modifier
            .fillMaxSize()
            .background(HudColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        HudEnter(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 24.dp),
        ) {
            Column(Modifier.hudGlass().padding(22.dp)) {
                Text(title, style = HudeHeading().copy(brush = HudBrushes.title))
                Spacer(Modifier.height(12.dp))
                if (!reason.isNullOrBlank()) {
                    Text(reason, color = HudColors.TextMuted, style = HudeBody())
                    Spacer(Modifier.height(10.dp))
                }
                // Which vehicle this page is waiting for. With a card selected the page
                // can name it, and that turns "no device" into "my car is not here" - a
                // sentence the driver already understands. With no card at all the app has
                // never met a dashboard, and the honest line is that nothing is chosen yet.
                Text(
                    when {
                        !vehicleName.isNullOrBlank() -> "WAITING FOR: $vehicleName"
                        firstRun -> "NO DASHBOARD PAIRED YET"
                        else -> "NO VEHICLE SELECTED"
                    },
                    color = HudColors.Cyan,
                    style = HudeCfgLive(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        permissionProblem ->
                            "Bluetooth permission not granted."
                        firstRun ->
                            "Switch the dashboard on, then pair it from the vehicle page."
                        else -> note
                    },
                    color = HudColors.TextDim,
                    style = HudeCfgNote(),
                )
                Spacer(Modifier.height(18.dp))
                HudButton(
                    "OPEN VEHICLE PAGE",
                    style = HudButtonStyle.PRIMARY,
                    // The bar's own wheel: the mark that means "vehicle" in the bar is the
                    // mark on the door to the vehicle page, so the cover points at the tab
                    // it is sending you to rather than only spelling it.
                    glyph = HudIcons.Wheel,
                    onClick = onOpenVehicles,
                )
            }
        }
    }
}
