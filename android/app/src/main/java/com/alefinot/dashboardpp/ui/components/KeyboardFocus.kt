package com.alefinot.dashboardpp.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
