package com.alefinot.dashboardpp.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudGlyphLedger
import com.alefinot.dashboardpp.ui.components.HudGlyphSize
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudOverlayScrim
import com.alefinot.dashboardpp.ui.components.HudToggle
import com.alefinot.dashboardpp.ui.components.hudPressable
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeBody
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.hudGlass
import kotlinx.coroutines.launch

/**
 * The start tour: the page visible behind a dimmed scrim, and one glass card
 * floating over its lower half, a card per line of [TutorialScript.steps].
 *
 * It is an overlay in this composition rather than a window-level `Dialog`,
 * for [HudConfirmDialog]'s reason — it belongs to the screen it explains —
 * with one addition: the screen behind it staying visible *is* the point. The
 * bar, the pager and the garage sit dim under the scrim so "the middle of the
 * bar" can point at the middle of the bar.
 *
 * The rules, since none of them is visible:
 *  - the tour appears once per process ([TutorialHolder]) unless
 *    `tutorial_hidden` says the owner refused it; that flag is the whole
 *    persistence, and the card writes it the moment the switch moves —
 *    quitting mid-tour cannot lose the answer;
 *  - Back (and `SKIP`) put the tour away without touching the flag: refusing
 *    *this time* and refusing *for good* are different acts, and only one of
 *    them has a control;
 *  - the dots are the step list — any of them is pressable, which is why
 *    there is no BACK button. Two ways to walk back one card would be two
 *    grammars for one act.
 *
 * The footer's switches are the same store, and so the same live state, as
 * App Settings → GETTING STARTED: tick it here and the settings row has moved
 * before the owner walks over to look.
 */
@Composable
fun TutorialOverlay(context: Context) {
    val settings = remember { RecorderSettings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val hidden by settings.tutorialHidden.collectAsStateWithLifecycle(initialValue = false)
    var step by remember { mutableStateOf(0) }
    val steps = TutorialScript.steps

    BackHandler { TutorialHolder.close() }

    HudOverlayScrim(contentAlignment = Alignment.BottomCenter) {
        HudEnter(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            Column(Modifier.hudGlass().padding(20.dp)) {
                val card = steps[step]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Decorative: the title beside it is the name, exactly as a
                    // word button's glyph carries no description of its own.
                    Image(
                        HudGlyphLedger.mark(card.glyphId),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(HudColors.Cyan),
                        modifier = Modifier.width(HudGlyphSize.Card.dp).height(HudGlyphSize.Card.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        card.title,
                        color = HudColors.TextPrimary,
                        style = HudeHeading(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${step + 1} / ${steps.size}",
                        color = HudColors.TextDim,
                        style = HudePerfLabel(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(card.body, color = HudColors.TextMuted, style = HudeBody())
                Spacer(Modifier.height(16.dp))
                // The flag's one control on the tour, on every card (decision
                // 2026-11-22): reachable wherever the reader stopped reading.
                // It saves as it is touched, mid-tour or not.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DON\u2019T SHOW THIS AGAIN",
                        color = HudColors.Cyan,
                        style = HudeFormLabel(),
                        modifier = Modifier.weight(1f),
                    )
                    HudToggle(
                        on = hidden,
                        label = "don\u2019t show the start tour again",
                    ) { on -> scope.launch { settings.setTutorialHidden(on) } }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudButton("SKIP", style = HudButtonStyle.SECONDARY, glyph = HudIcons.Close) {
                        TutorialHolder.close()
                    }
                    // The walk, as the tour's own progress: press a dot, go there.
                    // The current dot is the full cyan pip, the rest dim rings —
                    // the same "one is this one" reading the bar gives its slots.
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        steps.forEachIndexed { i, cardStep ->
                            Box(
                                Modifier
                                    .padding(horizontal = 6.dp)
                                    .hudPressable(
                                        role = Role.Button,
                                        onClickLabel = "go to ${cardStep.title}",
                                        onClick = { step = i },
                                    )
                                    .background(
                                        if (i == step) HudColors.Cyan else HudColors.Hairline,
                                        CircleShape,
                                    )
                                    .width(10.dp)
                                    .height(10.dp),
                            )
                        }
                    }
                    if (step == steps.lastIndex) {
                        HudButton("DONE", glyph = HudIcons.Check) { TutorialHolder.close() }
                    } else {
                        HudButton("NEXT", glyph = HudIcons.Chevron) { step += 1 }
                    }
                }
            }
        }
    }
}
