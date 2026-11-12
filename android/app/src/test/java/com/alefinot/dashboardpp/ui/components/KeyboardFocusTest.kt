package com.alefinot.dashboardpp.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyboard dismissal must let go of the field (bench, 2026-11-12): Back closes the
 * keyboard and used to leave the caret and the cyan focus ring in a field nobody was
 * typing into, on every page. The rule is pinned at the window because that is where
 * the platform reports the keyboard, and that is what makes it cover every text field
 * without each screen opting in.
 *
 * What each guard protects:
 *   1. both working signals stay observed — the IME inset (API 30+) and the visible
 *      frame / window shrink (Android 10 and `adjustResize`), folded together with
 *      `max` so neither can cancel the other; and `isImeVisible`, which is stuck
 *      `true` on the bench phone, does not come back;
 *   2. focus is cleared on the open → closed edge, not on the closed state itself, so a
 *      page with no keyboard cannot steal focus from a field the user has just tapped;
 *   3. the rule is installed once, above the shell, and every text field is still the
 *      shared `HudInput` rather than a screen's own `BasicTextField`.
 */
class KeyboardFocusTest {

    private val main = File("src/main/java/com/alefinot/dashboardpp")

    /** Comment-stripped read, the way [PhotoEditsLandOnSaveOnlyTest] does it: pin code, not prose. */
    private fun code(path: String): String {
        val f = File(main, path)
        assertTrue("missing ${f.path}", f.exists())
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\\n]*"), "")
    }

    @Test
    fun `closing the keyboard clears the focused field`() {
        val focus = code("ui/components/KeyboardFocus.kt")
        assertTrue("the window no longer observes the IME inset", focus.contains("WindowInsets.ime"))
        assertTrue(
            "the window no longer observes the visible-frame shrink",
            focus.contains("getWindowVisibleDisplayFrame"),
        )
        assertTrue(
            "the keyboard height is no longer the larger of the two signals",
            focus.contains("maxOf(imeHeight, windowHeightAboveKeyboard)"),
        )
        assertFalse(
            "isImeVisible is stuck true on Android 10 and would keep the keyboard latched open",
            focus.contains("isImeVisible"),
        )
        assertTrue("the focused field is not cleared when the keyboard closes", focus.contains("focusManager.clearFocus()"))
        // The clear must sit behind the shown latch: clearing on the closed state itself
        // would fire on the first composition and after every tap that opens no keyboard.
        assertTrue(
            "focus is cleared on the closed state instead of the closing edge",
            Regex("else if \\(keyboardShown\\)[\\s\\S]{0,200}focusManager\\.clearFocus\\(\\)").containsMatchIn(focus),
        )
    }

    @Test
    fun `the rule is installed above every screen once`() {
        val activity = code("MainActivity.kt")
        assertEquals(
            "the keyboard-focus rule must be installed exactly once, above the shell",
            1,
            Regex("\\bClearFocusWhenKeyboardCloses\\(\\)").findAll(activity).count(),
        )
        val rule = activity.indexOf("ClearFocusWhenKeyboardCloses()")
        val shell = activity.indexOf("DashboardRoot(this, vm)")
        assertTrue("the rule must sit above the shell, not inside a page", rule in 0 until shell)
    }

    @Test
    fun `every text field is the shared one`() {
        // The rule is field-agnostic, but a screen that hand-builds a BasicTextField would
        // be the first place a future focus style could drift from HudInput's.
        val offenders = File(main, "ui").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") && it.name != "Hud.kt" }
            .filter { code(it.relativeTo(main).path).contains("BasicTextField(") }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals("text fields built outside HudInput: $offenders", emptyList<String>(), offenders)
    }
}
