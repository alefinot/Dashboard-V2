package com.alefinot.dashboardpp.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vehicle editor's photo rules, pinned as source (vehicle editor fixes, 2026-11-10).
 *
 * Three bench complaints made this file: the keyboard buried SAVE and CANCEL; re-framing
 * could only zoom inside the last crop; and CANCEL reverted the words but not the picture,
 * because photo edits used to commit the moment a button was pressed. The fixes moved the
 * photo into the same draft the text fields live in — `PhotoEdit`, staged in the editor,
 * executed by `saveVehicleWithPhoto` — and a draft is exactly the kind of promise a
 * source-read can keep: the module has no Compose harness, and the failures this guards
 * are a method called from the wrong screen, or a file write ordered before a record write.
 *
 * What each guard protects:
 *   1. the immediate-commit API is **gone**, not merely unused — an editor cannot
 *      quietly re-grow a disk write at `USE THIS PHOTO` if there is nothing to call;
 *   2. the editor holds a `PhotoEdit` and hands it over exactly once, at SAVE;
 *   3. in `saveVehicleWithPhoto` the files move **before** the record lands — the
 *      crash-safe order the deleted `removePhoto` documented ("file first, then
 *      pointer") and the order that stops a record pointing at a JPEG that failed;
 *   4. both save paths run the **same** validation (one MAC rule, not two copies);
 *   5. the editor consumes the keyboard inset itself, and only the editor does;
 *   6. the crop panel's window is still the square `PhotoCrop.rectFor` assumes, and
 *      the ZOOM buttons clamp through the same maths a drag does.
 */
class PhotoEditsLandOnSaveOnlyTest {

    private val app = File("src/main")
    private val screens = File(app, "java/com/alefinot/dashboardpp/ui/screens")
    private val viewmodel = File(app, "java/com/alefinot/dashboardpp/viewmodel")

    /** Comment-stripped read, the way [PhoneViewportTest] does it: pin code, not prose. */
    private fun code(dir: File, name: String): String {
        val f = File(dir, name)
        assertTrue("missing ${f.path}", f.exists())
        var s = f.readText()
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).findAll(s).forEach { m ->
            s = s.replace(m.value, " ")
        }
        return s.replace(Regex("//[^\n]*"), "")
    }

    private val editor = code(screens, "VehicleEditorScreen.kt")
    private val vm = code(viewmodel, "ConnectionViewModel.kt")

    /** The body of one view-model method, from its signature to the next `fun ` header. */
    private fun vmMethod(name: String): String {
        val start = vm.indexOf("fun $name(")
        assertTrue("ConnectionViewModel lost $name", start >= 0)
        val after = vm.indexOf("\n    fun ", start + 1)
        return vm.substring(start, if (after >= 0) after else vm.length)
    }

    // ── 1. the immediate-commit door is welded shut ────────────────────────────

    @Test
    fun `the editor can no longer commit a photo behind SAVE's back`() {
        // The methods used to be the only way the editor could write a photo. Deleted,
        // not deprecated: a call site cannot reappear against an API that does not exist.
        assertFalse(vm.contains("fun applyPhotoCrop"))
        assertFalse(vm.contains("fun removePhoto"))
        assertFalse(editor.contains("vm.applyPhotoCrop"))
        assertFalse(editor.contains("vm.removePhoto"))
        // And the editor holds no photo-store reference at all — every disk thing it
        // wants done, it wants of the view model, through the draft.
        assertFalse(editor.contains("vehicles.photos"))
    }

    // ── 2. the editor is a draft ───────────────────────────────────────────────

    @Test
    fun `the editor stages every photo action as a PhotoEdit`() {
        // USE THIS PHOTO stages a Crop (with a decoded preview for the well, never a
        // write); REMOVE stages a Remove; the initial and dropped state is Keep.
        assertTrue(editor, Regex("mutableStateOf<PhotoEdit>\\(PhotoEdit\\.Keep\\)").containsMatchIn(editor))
        assertTrue(editor, Regex("photoEdit = PhotoEdit\\.Crop\\(").containsMatchIn(editor))
        assertTrue(editor, Regex("photoEdit = PhotoEdit\\.Remove").containsMatchIn(editor))
        // The panel's USE answer decodes a preview and closes over the draft - it must
        // not name any write API. (Test 1 already pinned that the editor holds no
        // photo-store reference at all; this pins the one call the flow does make.)
        assertTrue(editor, Regex("vm\\.previewPhotoCrop\\(").containsMatchIn(editor))
    }

    @Test
    fun `SAVE is the one and only door a photo decision leaves the editor through`() {
        // The typed fields and the staged edit go out in the same onSave(...) call -
        // which is what makes CANCEL (or Back) revert the picture as surely as the words.
        assertTrue(
            "the editor's onSave no longer carries the draft PhotoEdit to the page",
            Regex("onSave\\(\\s*draft\\.copy\\((?s).*?\\),\\s*photoEdit,?\\s*\\)").containsMatchIn(editor),
        )
        // And ConnectedTabs hands it to the save that knows about photos, not the one
        // that would drop the decision on the floor.
        val tabs = code(screens, "ConnectedTabs.kt")
        assertTrue(
            "ConnectedTabs went back to saveVehicle, losing the staged photo on SAVE",
            Regex("saveVehicleWithPhoto\\(typed, photoEdit\\)").containsMatchIn(tabs),
        )
    }

    // ── 3. inside the save: files first, then the record ───────────────────────

    @Test
    fun `saveVehicleWithPhoto lands the photo files before the record`() {
        val body = vmMethod("saveVehicleWithPhoto")
        val put = body.indexOf("vehicles.put(")
        assertTrue("no record write in saveVehicleWithPhoto?", put >= 0)
        val files = body.indexOf("photos.delete(")
        val crop = body.indexOf("cropFrom(")
        assertTrue("Remove must delete the photo files", files >= 0)
        assertTrue("Crop must crop", crop >= 0)
        assertTrue(
            "the record must not land before the files move - file first, then pointer",
            files < put && crop < put,
        )
        // A crop that cannot land refuses and returns: no vehicles.put between its null
        // answer and the return.
        val fail = Regex("cropFrom\\(.*?\\)\\s*\\?:\\s*run\\s*\\{(?s).*?Could not save the photo.*?return@launch", )
            .containsMatchIn(body)
        assertTrue("a failed crop no longer refuses without writing the record", fail)
    }

    // ── 4. one validation, two doors ───────────────────────────────────────────

    @Test
    fun `both save paths run the same validation and the MAC rule exists once`() {
        // Two copies of the MAC/name rules is two chances to fix one and forget the
        // other - the reason checkSave() was extracted when the photo save grew.
        assertEquals(
            "macConflict is referenced more than once in the view model: checkSave's " +
                "shared half has been cloned",
            1, Regex("macConflict\\(").findAll(vm).count(),
        )
        assertEquals(2, Regex("checkSave\\(typed\\)").findAll(vm).count())
        // The validation refuses before anything touches disk: neither photos call nor
        // record write sits between checkSave and its Refused return in the photo save.
        val body = vmMethod("saveVehicleWithPhoto")
        val refused = Regex("checkSave\\(typed\\)(?s).*?CheckedSave\\.Refused.*?return@launch").containsMatchIn(body)
        assertTrue("saveVehicleWithPhoto no longer refuses a validation failure outright", refused)
    }

    @Test
    fun `Keep does not churn every photo in the app`() {
        // The version bump is what repaints every card cover and photo well; on a Keep
        // save (an ordinary rename) nothing about a photo changed, so nothing repaints.
        val body = vmMethod("saveVehicleWithPhoto")
        assertEquals(
            "photoVersion must be bumped exactly once in saveVehicleWithPhoto, guarded " +
                "by a real change",
            1, Regex("photoVersion\\.intValue\\+\\+").findAll(body).count(),
        )
        assertTrue(
            "the bump is no longer guarded - Keep would churn the wells again",
            Regex("if \\(photoChanged\\) photoVersion\\.intValue\\+\\+").containsMatchIn(body),
        )
    }

    // ── 5. the keyboard, in the one place that has text fields ─────────────────

    @Test
    fun `the editor clamps its own sheet to the keyboard and scrolls only the cards`() {
        // targetSdk 35 forces edge-to-edge and the window no longer resizes for the IME
        // (API 30+): the keyboard is an inset to consume, or SAVE sits under it. It goes
        // on the sheet, outside the cards' scroll, so the whole sheet - heading, viewport
        // and pinned footer - clamps to (scrim - keyboard). The footer is outside the
        // scroll on purpose: SAVE and CANCEL stay reachable while the form scrolls.
        val sheet = Regex("Column\\(\\s*Modifier\\s*\\.hudGlass\\((?s).*?\\.imePadding\\(\\),?\\s*\\)\\s*\\{")
            .find(editor)
        assertTrue("the editor's sheet is not recognisable - check the inset order", sheet != null)
        assertTrue(
            "the editor's cards are no longer a scrolling viewport",
            Regex("\\.weight\\(1f, fill = false\\)\\s*\\.verticalScroll\\(").containsMatchIn(editor),
        )
        val ime = editor.indexOf(".imePadding()")
        val scroll = editor.indexOf(".verticalScroll(")
        assertTrue("the editor stopped consuming the IME inset", ime >= 0)
        assertTrue("imePadding must sit outside verticalScroll or the sheet will not clamp", ime < scroll)
    }

    @Test
    fun `no other surface consumes the IME inset`() {
        // Per the maintainer's scope decision: the editor only. An imePadding on the
        // root or the settings pages would move decisions nobody made this time.
        val offenders = screens.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
            .orEmpty()
            .filter { it.name != "VehicleEditorScreen.kt" && code(screens, it.name).contains("imePadding") }
            .map { it.name }
            .sorted()
        assertEquals("the IME inset is consumed outside the vehicle editor: $offenders", emptyList<String>(), offenders)
    }

    // ── 6. the crop window is still the window the maths assume ────────────────

    @Test
    fun `the wider viewport did not move the square the crop maths measure`() {
        val panel = code(screens, "PhotoCropPanel.kt")
        // [frame] is still fed by an onSizeChanged on a square Box sized min(maxWidth,
        // maxHeight) - the same side, the same centring, inside the viewport.
        assertTrue(
            "the crop window stopped being the measured square",
            Regex("Box\\(\\s*Modifier\\s*\\.size\\(side\\)\\s*\\.align\\(Alignment\\.Center\\)\\s*\\.onSizeChanged \\{ frame = it \\}")
                .containsMatchIn(panel),
        )
        // USE THIS PHOTO still asks PhotoCrop for the rectangle of *the window*.
        assertTrue(
            "PhotoCrop.rectFor is no longer measured against the crop window's side",
            Regex("PhotoCrop\\.rectFor\\(\\s*img\\.first,\\s*img\\.second,\\s*frame\\.width,\\s*zoom,\\s*offX,\\s*offY,?\\s*\\)")
                .containsMatchIn(panel),
        )
        // The ZOOM buttons speak through the same clamp a drag uses - and both doors
        // share the same zoom ceiling.
        assertEquals(
            "the drag and the ZOOM buttons must clamp offsets through maxOffsetPx together",
            2, Regex("PhotoCrop\\.maxOffsetPx\\(").findAll(panel).count(),
        )
        assertEquals(
            "the drag and the ZOOM buttons must share the zoom ceiling",
            2, Regex("coerceIn\\(1f, MAX_ZOOM\\)").findAll(panel).count(),
        )
        // And the photo may no longer be clipped to the window - that is the whole crop
        // metaphor; the clip lives on the viewport now.
        assertTrue("the viewport lost its clip, so a zoomed photo can draw over the buttons",
            panel.contains(".clipToBounds()"))
    }
}
