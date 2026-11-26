package com.alefinot.dashboardpp

import android.app.Application
import android.content.Context
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel

/**
 * The process's owner of the one dialer (background link plan §2).
 *
 * [ConnectionViewModel] used to be built by [MainActivity] and thrown away with it, which
 * is what made a link something the phone only had while a window was open: close the app
 * and the ladder, the re-arm timer and the weather tick all went with it. Same class, same
 * rules, one level up - so the always-on lane and the page in front of the driver look at
 * one dialer and cannot each be reaching for a different car.
 */
class DashboardApp : Application() {

    /**
     * The dialer, built on first touch: the first window, or the always-on lane asking for
     * a target. Nothing is read from disk here, so touching it early costs nothing.
     */
    val controller: ConnectionViewModel by lazy { ConnectionViewModel(this) }

    companion object {
        /**
         * The dialer from a service, or null when nothing has built one.
         *
         * A function rather than a property that creates it on demand: a service reaching
         * the ladder is the app's normal shape, but a context that is not this
         * [Application] must not be able to keep one alive.
         */
        fun controllerFor(context: Context): ConnectionViewModel? =
            (context.applicationContext as? DashboardApp)?.controller
    }
}
