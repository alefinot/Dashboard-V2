package com.alefinot.dashboardpp.storage

object StorageCopy {

    /** "1 car", "4 cars". A bare `"$n cars"` says "1 cars", which is the bug this exists for. */
    fun count(n: Int, singular: String, plural: String = "${singular}s"): String =
        "$n ${if (n == 1) singular else plural}"

    fun cars(n: Int): String = count(n, "car")

    fun tripFiles(n: Int): String = count(n, "trip file", "trip files")

    fun drives(n: Int): String = count(n, "drive")

    fun cardCounts(here: Int, carCount: Int, elsewhere: Int, elsewhereInOwnFolder: Boolean): String {
        val strays = if (elsewhere > 0) {
            val where = if (elsewhereInOwnFolder) "the app's own folder" else "another folder"
            " \u00b7 + ${tripFiles(elsewhere)} in $where"
        } else ""
        return "\u00b7 ${tripFiles(here)} \u00b7 ${cars(carCount)}" + strays
    }

    fun grantButton(shared: Boolean, path: AppRoot.GrantPath): String = when {
        shared -> "STORAGE ACCESS GRANTED"
        else -> when (path) {
            AppRoot.GrantPath.ALL_FILES -> "GRANT STORAGE ACCESS"
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
