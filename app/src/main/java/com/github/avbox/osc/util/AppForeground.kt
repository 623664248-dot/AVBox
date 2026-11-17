package com.github.avbox.osc.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide foreground tracker: installed once from the
 * Application and driven by activity start/stop counts, so it is
 * correct no matter which component asks first (a counter
 * registered late by a service would miss already-started
 * activities and drift). Listeners hear only real transitions:
 * to true when the first activity starts, to false when the last
 * one stops — the app has left the screen entirely, as opposed to
 * moving between its own pages.
 */
object AppForeground {

    @Volatile
    var inForeground: Boolean = false
        private set

    private var startedCount = 0
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedCount++
                    if (startedCount == 1) transition(true)
                }

                override fun onActivityStopped(activity: Activity) {
                    startedCount--
                    if (startedCount == 0) transition(false)
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private fun transition(foreground: Boolean) {
        inForeground = foreground
        for (listener in listeners) {
            try {
                listener(foreground)
            } catch (t: Throwable) {
                // A broken listener must not break the tracker.
            }
        }
    }
}
