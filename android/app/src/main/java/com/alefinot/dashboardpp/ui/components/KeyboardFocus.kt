package com.alefinot.dashboardpp.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * Back closes the keyboard but leaves the field focused: the caret keeps blinking and
 * the cyan ring still says "you are editing this" after the user has stopped editing.
 * This clears that focus once, when the keyboard goes away, for every text field in the
 * app — the rule lives at the window, not in a screen, so a field added tomorrow gets it
 * without having to remember.
 *
 * The platform reports the keyboard differently across API levels: Compose exposes the
 * IME as an inset on API 30+, while Android 10 keeps the window full height and shrinks
 * only the area left visible above the keyboard. The two are folded into one height with
 * `max`; taking the larger one means a signal that does not apply on a given device reads
 * zero instead of cancelling the one that does. `WindowInsets.isImeVisible` is
 * deliberately not used: on this Android 10 device it reports `true` from the first frame
 * and never changes.
 *
 * Clearing on the open → closed edge, never on the closed state itself, is what keeps
 * the first composition - and a screen with no keyboard at all - from stealing focus
 * from a field the user just tapped.
 *
 * It is deliberately a side effect with no layout: [MainActivity] calls it above the
 * whole shell, so no screen can forget it and no screen has to repeat it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClearFocusWhenKeyboardCloses() {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val density = LocalDensity.current
    val threshold = with(density) { 100.dp.toPx() }

    // Edge-to-edge path: the IME inset is the whole story (and zero below API 30).
    val imeHeight = WindowInsets.ime.getBottom(density)

    // adjustResize path: the window or the visible frame shrinks while the keys are up.
    // A bar changing height (status bar, three-button navigation) stays under the 100 dp
    // threshold; a keyboard never does.
    var windowHeightAboveKeyboard by remember { mutableIntStateOf(0) }
    var keyboardShown by remember { mutableStateOf(false) }

    LaunchedEffect(imeHeight, windowHeightAboveKeyboard) {
        val open = maxOf(imeHeight, windowHeightAboveKeyboard) > threshold
        if (open) {
            keyboardShown = true
        } else if (keyboardShown) {
            keyboardShown = false
            focusManager.clearFocus()
        }
    }

    DisposableEffect(view) {
        val decor = view.rootView
        var tallest = decor.height
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val height = decor.height
            if (height > tallest) tallest = height
            // The decor frame can stay full-screen while MIUI reports the keyboard as a
            // content inset, so measure what the user can actually see, not just the view.
            val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
            val resized = (tallest - height).coerceAtLeast(0)
            val covered = (height - frame.bottom).coerceAtLeast(0)
            val hidden = maxOf(resized, covered)
            windowHeightAboveKeyboard = if (hidden > threshold) hidden else 0
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
}

/**
 * A field's rectangle in root coordinates, as a [MutableState] so a scrolling list
 * moves it without touching anyone else's, and as a list member so it registers and
 * unregisters by identity. One per live [HudInput]; see [TextRectRegistry].
 */
internal typealias TextRect = MutableState<ComposeRect?>

/**
 * The rectangles of every [HudInput] currently composed. The tap rule below must know
 * whether a touch hit *any* text field, not merely whether it hit the focused one:
 * clearing focus for a tap on a *different* field routes focus through "nothing" —
 * MIUI then hides the keyboard and does not re-show it when the tapped field takes
 * focus back in the same dispatch, so the second field opens without keys and a
 * further tap cannot bring them (bench, 2026-11-12: the focused field's own ring
 * stayed, its keyboard did not). So every field publishes its bounds and the rule
 * stays its hands off any tap that lands on one.
 */
@Stable
internal class TextRectRegistry {
    private val rects = mutableStateListOf<TextRect>()

    fun register(rect: TextRect) {
        rects += rect
    }

    fun unregister(rect: TextRect) {
        rects -= rect
    }

    /** True when [offset] (root coordinates) lands inside any live text field. */
    fun under(offset: Offset): Boolean = rects.any { it.value?.contains(offset) == true }
}

internal val LocalTextRectRegistry = staticCompositionLocalOf { TextRectRegistry() }

/**
 * The twin of [ClearFocusWhenKeyboardCloses]: Back closes the keyboard from behind,
 * this closes it from the front — a tap anywhere outside the field being typed into.
 * Without it the keyboard only ever lets go through Back, the IME action, or a tap on
 * another field, because a Compose tap on plain content moves nothing: the rows and
 * gaps a settings page is made of are not focusable, so focus — and with it the
 * keyboard — stays latched to the value box it was in.
 *
 * [content] is wrapped in a [Box] that watches every pointer down on the **Initial**
 * pass — the one moment before any child has touched the tap — and clears focus only
 * when the down lands on no field at all per [TextRectRegistry]. A down on the
 * focused field is left completely alone (caret moves, keyboard never blinks), and a
 * down on another field is left alone so Compose moves focus there directly, keyboard
 * included; only a down on rows, buttons or empty space releases focus, the cyan ring
 * and the keyboard with it.
 *
 * Like the rule above, it lives at the window and not in a screen: every field in
 * the app is [HudInput], and screens must not be able to forget this. Dialogs and
 * dropdown menus live in their own window, so a tap inside one is none of this
 * rule's business.
 */
@Composable
fun ClearFocusOnTapOutside(content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val registry = remember { TextRectRegistry() }
    CompositionLocalProvider(LocalTextRectRegistry provides registry) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            pass = PointerEventPass.Initial,
                            requireUnconsumed = false,
                        )
                        if (!registry.under(down.position)) focusManager.clearFocus()
                    }
                },
        ) { content() }
    }
}
