package com.alefinot.dashboardpp.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable

/**
 * A live figure that glides to its value instead of teleporting onto it.
 *
 * The unit streams one snapshot a second. Formatted raw, every figure on the Live page
 * therefore jumps once a second - the page reads broken even when every jump is the truth,
 * because a number that moves like a slot machine is read as a number that was frozen for a
 * second. This turns each step into a travel across one sample period: the moment a new
 * figure lands, the displayed value starts gliding from where it stood to the new one and
 * arrives just as the next frame would.
 *
 * Linear, and one period long, on purpose. An eased glide finishes early and then sits
 * still until the next sample arrives, which is a step with extra frames; a wall of numbers
 * doing it in unison reads as a pulse. At constant speed the only thing this display owes
 * the rider is that the change is continuous - and a constant-speed glide never over- or
 * under-shoots either sample, so the figure is always a value the unit actually had, at
 * most a second late. That latency is the whole price of smooth, and for a dash it is the
 * right thing to pay.
 *
 * Discrete readings - satellite count, fix state, the fuel sender's health word - must not
 * come through here. They genuinely change in steps, and gliding a count from 9 to 10
 * through 9.4 is the one thing worse than a step: it is a fiction.
 *
 * And it is called in the leaf that paints the number, never in a card's body. A [flow] in
 * a card's body is a card that recomposes at frame rate: its trace, its strip and the live
 * map beside it all redraw for a caption's motion. That is why `FlowBar`, `FlowFigureText`,
 * `SideNum` and `TripFlowLine` exist, and why the card captions on those pages stay raw.
 */
@Composable
fun flow(target: Double): Double = animateFloatAsState(
    targetValue = target.toFloat(),
    animationSpec = flowSpec(),
    label = "flow",
).value.toDouble()

/**
 * The glide's timing - and no timing at all when the page holding it is off the pager's
 * screen.
 *
 * That second half is not a nicety. The pager keeps its neighbours composed, so a page
 * nobody can look at still receives the unit's 1 Hz stream, and every one of those frames
 * starts another second of animation on it: measured on the bench phone, a build with the
 * glide and no gate sat at sixty frames a second on the App Settings page, where there is
 * nothing to animate. Off screen the value simply arrives ([snap]), because an animation
 * nobody watches is only battery and heat.
 *
 * Bench 2026-11-15, on the Mi 9 Lite with a drive recording: this is also the one place a
 * cheaper glide was tried and rejected. Stepping the figures by hand at 30 Hz instead of
 * letting Compose take every refresh aimed to halve the page's recompositions, and the
 * Live page's missed deadlines did not move outside their noise (26-64 janky frames per six
 * seconds for Compose's own animation, 14-74 for the stepper, alternating runs). The
 * numbers cannot be read at thirty hertz any better than at sixty, so the simpler driver is
 * the one that stays.
 */
@Composable
fun flowSpec(): FiniteAnimationSpec<Float> =
    if (LocalEnterActive.current) tween(FLOW_MS, easing = LinearEasing) else snap()

/**
 * One telemetry period, and so the length of every glide.
 *
 * The unit answers at 1 Hz (trip plan: one frame a second), and both the figures
 * ([flow], [flowSpec]) and the live map's dot and camera travel one period per fix. Shared
 * as a token so the page cannot disagree with itself about how long a second of its own
 * data takes to arrive.
 */
const val FLOW_MS = 1000
