package com.github.avbox.osc.music

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide warm connection to [LxPlaybackService] (v1.15.1).
 *
 * Why this exists: up to v1.13.3 the player Activity built its
 * MediaController cold when the page opened and gave it 3 s; on the
 * test device (Honor) the cold service start lost that race against
 * the QuickJS/Python initialisation running at the same time, the
 * session degraded to the in-Activity player and never retried — no
 * media notification, no background playback, no mini bar. The music
 * page now calls [ensureConnecting] as soon as it is composed, so by
 * the time the user taps a song the connection is usually already
 * established and the player Activity simply adopts it.
 *
 * Ownership: the controller built here is shared and process-lived.
 * Adopters (the player Activity) add/remove their own listeners but
 * never release it; the reference is dropped only when the build
 * fails or the controller reports a disconnect, so the next
 * [ensureConnecting] starts a fresh build. The connection is built
 * with the application context, so no Activity is ever leaked.
 */
object MusicServiceConnection {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var future: ListenableFuture<MediaController>? = null

    @Volatile
    private var controller: MediaController? = null

    /** Drops the shared references when the service connection dies. */
    private val disconnectListener = object : MediaController.Listener {
        override fun onDisconnected(mc: MediaController) {
            if (controller === mc) {
                controller = null
                future = null
            }
        }
    }

    /**
     * Starts building the shared controller if none exists and none
     * is in flight; a no-op otherwise. Safe to call on every music
     * page composition and before every player connect attempt.
     */
    fun ensureConnecting(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (connectedController() != null) return
        synchronized(this) {
            if (controller != null || future != null) return
            val startedAt = SystemClock.elapsedRealtime()
            MusicCrashLog.log(app, "svc: shared connect start")
            try {
                val token = SessionToken(
                    app,
                    ComponentName(app, LxPlaybackService::class.java),
                )
                val built = MediaController.Builder(app, token)
                    .setListener(disconnectListener)
                    .buildAsync()
                future = built
                built.addListener(
                    {
                        val mc = try {
                            built.get()
                        } catch (t: Throwable) {
                            null
                        }
                        if (mc != null) {
                            controller = mc
                            MusicCrashLog.log(
                                app,
                                "svc: shared connected in " +
                                    "${SystemClock.elapsedRealtime() - startedAt}ms",
                            )
                        } else {
                            if (future === built) {
                                future = null
                            }
                            MusicCrashLog.log(app, "svc: shared connect failed")
                        }
                    },
                    ContextCompat.getMainExecutor(app),
                )
            } catch (t: Throwable) {
                future = null
                MusicCrashLog.log(
                    app,
                    "svc: shared connect start failed ${t.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * The shared controller when it is connected right now. A stored
     * controller that turns out to be disconnected is dropped (with
     * its future) so the next [ensureConnecting] rebuilds.
     */
    fun connectedController(): MediaController? {
        val mc = controller ?: return null
        val connected = try {
            mc.isConnected()
        } catch (t: Throwable) {
            false
        }
        if (!connected) {
            if (controller === mc) {
                controller = null
                future = null
            }
            return null
        }
        return mc
    }

    /**
     * Waits (bounded by [timeoutMs]) for the in-flight shared build
     * and returns the connected controller, or null on timeout /
     * failure. Call [ensureConnecting] first; with nothing in flight
     * this returns whatever [connectedController] reports.
     */
    suspend fun awaitConnected(timeoutMs: Long): MediaController? {
        connectedController()?.let { return it }
        val fut = future ?: return null
        val app = appContext ?: return null
        val mc = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<MediaController?> { cont ->
                fut.addListener(
                    {
                        val built = try {
                            fut.get()
                        } catch (t: Throwable) {
                            null
                        }
                        if (cont.isActive) cont.resume(built)
                    },
                    ContextCompat.getMainExecutor(app),
                )
            }
        } ?: return null
        val connected = try {
            mc.isConnected()
        } catch (t: Throwable) {
            false
        }
        if (connected) {
            controller = mc
        }
        return connectedController()
    }
}
