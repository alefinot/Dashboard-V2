package com.alefinot.dashboardpp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.trips.AccelRun
import com.alefinot.dashboardpp.ui.components.hudNum
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudeMetric
import com.alefinot.dashboardpp.ui.theme.HudeMetricUnit
import com.alefinot.dashboardpp.ui.theme.HudeSectionCaps
import com.alefinot.dashboardpp.ui.theme.hudCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One finished 0-50, in words: the number the unit timed, and the two facts that let the owner
 * check it means what it says.
 *
 * It is the answer both maps give. The live map floats it over the drive that is happening and
 * drops it after a few seconds, because the camera is about to move away from the ground it
 * belongs to; the trip detail page floats the same card over a route that is not going anywhere,
 * and lets the owner keep it until they tap something else. Neither one is a second design — the
 * number, the ink and the wording are this file's, and a run that read differently on the two
 * pages would be two measurements in the owner's eyes.
 *
 * The label is fixed wording, not the unit's `ACCEL_TARGET_SPEED` fetched over the link — for a
 * card whose whole job is to answer "which run was this?", a config read would be a round trip
 * that adds a way to fail and no information. The speed the run actually ended at is printed next
 * to it instead, so a target of 100 is self-evident on the card rather than contradicted by it.
 *
 * The clock is the run's first fix, not the drive's start and not the frame's receive time: it is
 * the one timestamp that lets the owner place the launch in a drive they are remembering — "that
 * was the pull-out at the light after the bridge".
 */
@Composable
fun RunResultCard(run: AccelRun) {
    Box(Modifier.hudCard()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text("0–50", style = HudeSectionCaps(), color = HudColors.Yellow)
            Row {
                Text(
                    hudNum(run.seconds, 2),
                    style = HudeMetric(),
                    color = HudColors.TextPrimary,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    " s",
                    style = HudeMetricUnit(),
                    color = HudColors.TextMuted,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                clock(run.startedAtMs) + " · " + hudNum(run.finishSpeedKmh, 0) + " km/h",
                style = HudeSectionCaps(),
                color = HudColors.TextMuted,
            )
        }
    }
}

/** A wall clock, second-precise, in the app's fixed US locale (see [hudNum]). */
private fun clock(ms: Long): String = CLOCK.format(Date(ms))

private val CLOCK = SimpleDateFormat("HH:mm:ss", Locale.US)
