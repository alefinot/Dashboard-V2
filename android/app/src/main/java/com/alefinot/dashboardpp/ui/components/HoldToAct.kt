package com.alefinot.dashboardpp.ui.components

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The numbers behind "press to act, hold to confirm", kept apart from the gesture so they can
 * be tested without a screen.
 *
 * Three seconds to stop is deliberate: stopping a drive is the one press in the recorder that
 * cannot be taken back by pressing again (the next START opens a *new* file), and the cost of a
 * mis-tap that lands on a status pill is a drive split in two. [TAP_MS] is the other half of
 * that: a press that lasted longer than a tap but shorter than the hold is a person who changed
 * their mind, so it does **nothing** - pausing a recording because a thumb rested on the pill
 * would be the worst of both readings.
 */
object HoldRules {
    /** How long the finger must stay down for the hold to fire. */
    const val HOLD_MS = 3000L

    /** Anything released under this is a tap. */
    const val TAP_MS = 450L

    /** How fast the fill drains once the press ends without a hold. */
    const val DRAIN_MS = 160

    enum class Result {
        /** A press short enough to mean "do the one thing this control does". */
        TAP,

        /** Long enough not to be a tap, too short to mean stop: let it go. */
        NOTHING,

        /** The full window: the hold fired (or is firing). */
        STOP,
    }

    fun result(pressedMs: Long, cancelled: Boolean): Result = when {
        cancelled -> Result.NOTHING
        pressedMs >= HOLD_MS -> Result.STOP
        pressedMs < TAP_MS -> Result.TAP
        else -> Result.NOTHING
    }
}

/**
 * A press that means one thing and a longer press that means another, with the wait made
 * visible: holding fills the control red from its left edge, and the fill *is* the countdown.
 *
 * The fill is exposed as a lambda rather than a value so the caller draws it inside its draw
 * lambda ([progress] is read there), which invalidates one layer per frame instead of
 * recomposing the pill or the card that owns it - the same discipline as `HudStateChip`'s
 * breath. Nothing here allocates per frame.
 *
 * [onHold] being null is a real state, not a disabled one: a control with nothing to stop gets
 * a plain tap and no fill, because a red sweep that concludes "nothing to do" would be worse
 * than no sweep at all.
 */
class HoldToAct internal constructor(
    /** Apply to the element that takes the touches; it is also the a11y node. */
    val modifier: Modifier,
    private val fill: Animatable<Float, AnimationVector1D>,
) {
    /** 0..1 of the hold window consumed. Read inside a draw lambda. */
    fun progress(): Float = fill.value
}

/**
 * Build the gesture for one control.
 *
 * @param onTap what a short press does.
 * @param onHold what the full window does, or null when this state has nothing to hold for.
 * @param enabled false when the control cannot act at all; the node then reports itself
 *   disabled and swallows presses instead of pretending to be a button.
 */
@Composable
fun rememberHoldToAct(
    onTap: () -> Unit,
    onHold: (() -> Unit)?,
    enabled: Boolean,
    tapLabel: String,
    holdLabel: String,
    holdMs: Long = HoldRules.HOLD_MS,
): HoldToAct {
    val fill = remember { Animatable(0f) }
    val view = LocalView.current
    // The pages rebuild their action bundles every recomposition; the gesture must not be
    // restarted mid-press because of it, so the lambdas are read through State and the
    // pointerInput is keyed on nothing that a state change can flip.
    val tap = rememberUpdatedState(onTap)
    val hold = rememberUpdatedState(onHold)
    val holdable = onHold != null
    val armed = rememberUpdatedState(enabled)
    val canHold = rememberUpdatedState(holdable)

    // A control that can no longer be held must not keep a half-swept fill on screen. The
    // offer is withdrawn *while* the press is still down - the stop itself is what withdraws
    // it - and the first version of this left the stopped card wearing a red wash until the
    // next touch, because the state change cancelled the coroutine that would have drained it.
    LaunchedEffect(enabled, holdable) {
        if (!enabled || !holdable) fill.snapTo(0f)
    }

    val semantics = remember(enabled, holdable, tapLabel, holdLabel) {
        Modifier.semantics {
            role = Role.Button
            if (enabled) {
                onClick(label = tapLabel) {
                    tap.value()
                    true
                }
                if (holdable) {
                    // TalkBack has no finger to hold down for three seconds: its long-click
                    // acts at once, and says so in its own label.
                    onLongClick(label = holdLabel) {
                        hold.value?.invoke()
                        true
                    }
                }
            } else {
                disabled()
            }
        }
    }
    val gesture = remember(holdMs) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = press@{
                    if (!armed.value) {
                        tryAwaitRelease()
                        return@press
                    }
                    if (!canHold.value) {
                        // Nothing to stop, so the press is a plain button: any duration counts
                        // as the tap, the way a button has always behaved.
                        if (tryAwaitRelease()) tap.value()
                        return@press
                    }
                    coroutineScope {
                        val startedAt = SystemClock.uptimeMillis()
                        var fired = false
                        val sweep = launch {
                            fill.animateTo(
                                1f,
                                animationSpec = tween(
                                    holdMs.toInt(),
                                    // Linear: this is a deadline the owner is watching, and an
                                    // eased bar either looks finished early or stalls at the end.
                                    easing = LinearEasing,
                                ),
                            )
                            // Reaching this line means the finger is still down: a release
                            // cancels the job first. So the hold fires on the deadline itself,
                            // not on the release that follows it.
                            fired = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            // Clear the fill before the state changes: what follows is a
                            // stopped drive, and a stopped drive wearing the colour of the
                            // gesture that stopped it is a frame of the old state left behind.
                            fill.snapTo(0f)
                            hold.value?.invoke()
                        }
                        val released = tryAwaitRelease()
                        sweep.cancel()
                        if (!fired && released) {
                            val held = SystemClock.uptimeMillis() - startedAt
                            if (HoldRules.result(held, cancelled = false) == HoldRules.Result.TAP) {
                                tap.value()
                            }
                        }
                        // Drain rather than snap: the red is feedback about a gesture that
                        // just ended, and a hard cut reads as a glitch.
                        fill.animateTo(0f, tween(HoldRules.DRAIN_MS, easing = LinearEasing))
                    }
                },
            )
        }
    }

    return HoldToAct(semantics.then(gesture), fill)
}
