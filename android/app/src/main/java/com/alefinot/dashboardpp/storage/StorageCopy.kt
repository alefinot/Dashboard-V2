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
 * have.** Where there is nothing to grant, it says so and stops, because a dead-end button and a
 * dead-end sentence cost the owner a trip to App Info and a hunt for a toggle that was never
 * there (bench, Mi 9 on MIUI 12 — its App Info page has no storage entry at all).
 */
object StorageCopy {

    /** "1 car", "4 cars". A bare `"$n cars"` says "1 cars", which is the bug this exists for. */
    fun count(n: Int, singular: String, plural: String = "${singular}s"): String =
        "$n ${if (n == 1) singular else plural}"

    fun cars(n: Int): String = count(n, "car")

    fun tripFiles(n: Int): String = count(n, "trip file", "trip files")

    fun drives(n: Int): String = count(n, "drive")

    /**
     * The Storage card's button, or `null` when this Android version has nothing to offer — in
     * which case the card draws no button at all. A disabled "GRANT STORAGE ACCESS" is worse than
     * none: it looks like a control that is broken rather than a path that does not exist.
     */
    fun grantButton(shared: Boolean, path: AppRoot.GrantPath): String? = when {
        shared -> "STORAGE ACCESS GRANTED"
        path == AppRoot.GrantPath.ALL_FILES -> "GRANT STORAGE ACCESS"
        else -> null
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
        path == AppRoot.GrantPath.RUNTIME_PERMISSION ->
            "Trip logs, card photos and the garage are in the app's own folder, which no file " +
                "manager shows and no USB copy sees. This Android version asks for storage " +
                "permission when the app starts; allow it once and new files land in Documents."
        else ->
            "Trip logs, card photos and the garage are in the app's own folder, which no file " +
                "manager shows and no USB copy sees. This Android version gives an app no way " +
                "to open the shared folder and no switch to grant, so there is nothing to " +
                "change here — the files come out with the app's own Share buttons."
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
                AppRoot.GrantPath.NONE -> "in the app's own folder; this Android version has no storage switch to grant"
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
            AppRoot.GrantPath.RUNTIME_PERMISSION -> "the storage permission was never allowed — grant it in the system's app settings."
            AppRoot.GrantPath.NONE -> "this Android version gives the app no way to open the shared folder."
        }
    }
}
