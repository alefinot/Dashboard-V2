package com.alefinot.dashboardpp.storage

/**
 * What the app says out loud about where its files are, as pure functions of the state the
 * screen already computed.
 *
 * Two reasons this lives here rather than in the three screens that use it:
 *
 * **The English has to agree across the app.** The Trips header, a trip's detail page and the
 * App settings Storage card all describe the same folder, and they used to each carry their own
 * sentence. One of them promised a switch that does not exist on every device ("grant storage
 * access in App Settings", written for Android 11 and read on Android 10, where there is no
 * such switch), and none of the others could contradict it because nothing compared them. Here
 * the version decides the sentence once and the screens only pick a colour.
 *
 * **The version matters, so the sentence has to be testable without a device.** A JVM test can
 * hand [AppRoot.GrantPath] values to these functions and assert what an Android 8.1, an Android
 * 10 and an Android 13 are each told; there is no way to make this process claim to be API 29.
 *
 * The rule the copy follows: **the app never points at a switch this Android version does not
 * have.** Below API 30 there is no All files access to point at, and pointing an owner at an
 * App-info page that has no storage entry costs them a trip to Settings for nothing (measured on
 * the bench Mi 9 at API 29: AUTO-START, Displays, Manage Space — no storage row). What Android
 * 8.1 through 10 do have is the storage permission, so there the copy talks about *allowing* it
 * and the button raises the system's dialog; from API 30 up the copy talks about granting access
 * and the button opens the settings page. Same offer, correct door.
 */
object StorageCopy {

    /** "1 car", "4 cars". A bare `"$n cars"` says "1 cars", which is the bug this exists for. */
    fun count(n: Int, singular: String, plural: String = "${singular}s"): String =
        "$n ${if (n == 1) singular else plural}"

    fun cars(n: Int): String = count(n, "car")

    fun tripFiles(n: Int): String = count(n, "trip file", "trip files")

    fun drives(n: Int): String = count(n, "drive")

    /**
     * The Storage card's count line, which hangs off a folder label: `<folder> · counts`. Because
     * the folder is named, the counts are that folder's and nobody else's - and an install that
     * has been through a grant really does hold files in both roots, so the line has to admit the
     * remainder instead of quietly folding it in (bench, 2026-11-06: a tablet whose shared folder
     * had just been created and held nothing read `Documents/Dashboard++ · 5 trip files`).
     *
     * [here] is the count of trip files inside the folder the card names; [elsewhere] is the count
     * of trip files the Trips list shows that live outside it, and [elsewhereInOwnFolder] says
     * whether that outside is the app's own folder - the sentence a grant leaves behind - or a
     * folder only a path can describe. The free-space tail stays with the caller, which owns the
     * byte formatter. (The parameter is not called `tripFiles`: that name belongs to the
     * pluralising helper above, and a parameter would silence it.)
     */
    fun cardCounts(here: Int, carCount: Int, elsewhere: Int, elsewhereInOwnFolder: Boolean): String {
        val strays = if (elsewhere > 0) {
            val where = if (elsewhereInOwnFolder) "the app's own folder" else "another folder"
            " \u00b7 + ${tripFiles(elsewhere)} in $where"
        } else ""
        return "\u00b7 ${tripFiles(here)} \u00b7 ${cars(carCount)}" + strays
    }

    /**
     * The Storage card's button label. Every Android version the app runs on has a way into the
     * shared folder ([AppRoot.GrantPath]), so every one of them gets a button; what differs is the
     * door it opens - the All files access page from API 30 up, the system's storage dialog below
     * that. Only the granted state is disabled, and it is disabled because it is already true.
     */
    fun grantButton(shared: Boolean, path: AppRoot.GrantPath): String = when {
        shared -> "STORAGE ACCESS GRANTED"
        else -> when (path) {
            AppRoot.GrantPath.ALL_FILES -> "GRANT STORAGE ACCESS"
            // Below API 30 there is no settings page to hand them; the app can put the system's
            // own dialog in front of them, which is what this button does.
            AppRoot.GrantPath.RUNTIME_PERMISSION -> "ALLOW STORAGE"
        }
    }

    /** The paragraph under the Storage card's button. */
    fun storageNote(shared: Boolean, path: AppRoot.GrantPath): String = when {
        shared ->
            "Trip logs, card photos and the garage live in that folder — open it in a file " +
                "manager, copy it off over USB, or delete a bad drive from it by hand. " +
                "Revoking the access sends them back to the app's own folder rather than " +
                "deleting anything."
        path == AppRoot.GrantPath.ALL_FILES ->
            "Trip logs, card photos and the garage are in the app's own folder, which no file " +
                "manager shows and no USB copy sees. Grant storage access to save where you " +
                "can see it."
        else ->
            "Trip logs, card photos and the garage are in the app's own folder, which no file " +
                "manager shows and no USB copy sees. This Android version asks for storage " +
                "permission when the app starts; allow it once and new files land in " +
                "Documents, or use ALLOW STORAGE on this card if the dialog went unanswered."
    }

    /** The line under the Trips header: what the cards underneath are, and where they are. */
    fun tripsNote(shared: Boolean, path: AppRoot.GrantPath, sharedLabel: String, tripsDir: String): String {
        val head = "One file per ESP power session · "
        return if (shared) {
            head + "CSV in $sharedLabel/$tripsDir"
        } else {
            head + when (path) {
                AppRoot.GrantPath.ALL_FILES -> "app-private folder — grant storage access in App Settings"
                AppRoot.GrantPath.RUNTIME_PERMISSION -> "app-private folder — allow storage when the app asks"
            }
        }
    }

    /**
     * The sentence on a trip's detail page, where the owner is standing next to the file.
     * [shared] is whether *this file* sits on the shared root — not whether the app could put it
     * there, which is the question [path] answers.
     */
    fun tripFileNote(
        shared: Boolean,
        path: AppRoot.GrantPath,
        sharedLabel: String,
        tripsDir: String,
    ): String = if (shared) {
        "The CSV is shared from $sharedLabel/$tripsDir/ itself; no copy is made to share."
    } else {
        "The CSV is shared from the app's own folder, because " + when (path) {
            AppRoot.GrantPath.ALL_FILES -> "storage access was never granted — App Settings can change that."
            AppRoot.GrantPath.RUNTIME_PERMISSION -> "the storage permission was never allowed — ALLOW STORAGE on the Storage card in App Settings."
        }
    }
}
