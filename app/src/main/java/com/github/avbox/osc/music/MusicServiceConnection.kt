package com.github.avbox.osc.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide binding to [LxPlaybackService] (v1.16.0).
 *
 * This replaces the media3 MediaController / SessionToken connection
 * used up to v1.15.4: on the test device (Honor / MagicOS) SessionToken
 * construction threw IllegalArgumentException because the package
 * manager query behind it returned nothing for this app's own
 * MediaSessionService, so the service never started and every session
 * silently degraded to in-Activity playback. A same-process bind via
 * LocalBinder needs no token and no package-manager query at all —
 * onServiceConnected hands over the service object directly.
 *
 * Ownership: the binding is made with the application context and is
 * process-lived. Adopters (the player Activity, the music page's mini
 * bar) use the service's player through [connectedService] /
 * [connectedPlayer] and add/remove their own listeners, but never
 * unbind or release anything; the references are dropped only when
 * the system reports a disconnect, so the next [ensureConnecting]
 * rebinds.
 */
object MusicServiceConnection {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var service: LxPlaybackService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var pending: CompletableDeferred<LxPlaybackService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? LxPlaybackService.LocalBinder)?.getService()
            val app = appContext
            synchronized(this@MusicServiceConnection) {
                service = svc
                binding = false
                pending?.complete(svc)
                pending = null
            }
            if (app != null) {
                if (svc != null) {
                    MusicCrashLog.log(app, "svc2: shared bound")
                } else {
                    MusicCrashLog.log(app, "svc2: shared bind gave no service")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val app = appContext
            synchronized(this@MusicServiceConnection) {
                service = null
                binding = false
                pending?.complete(null)
                pending = null
            }
            if (app != null) {
                MusicCrashLog.log(app, "svc2: shared disconnected")
            }
        }
    }

    /**
     * Starts binding the shared service if it is not bound and no bind
     * is in flight; a no-op otherwise. Safe to call on every music
     * page composition and before every player connect attempt.
     */
    fun ensureConnecting(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (service != null) return
        synchronized(this) {
            if (service != null || binding) return
            binding = true
            pending = CompletableDeferred()
            val startedAt = SystemClock.elapsedRealtime()
            MusicCrashLog.log(app, "svc2: shared bind start")
            try {
                val ok = app.bindService(
                    Intent(app, LxPlaybackService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
                if (!ok) {
                    binding = false
                    pending?.complete(null)
                    pending = null
                    MusicCrashLog.log(app, "svc2: shared bind returned false")
                } else {
                    MusicCrashLog.log(
                        app,
                        "svc2: shared bind requested in " +
                            "${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                }
            } catch (t: Throwable) {
                binding = false
                pending?.complete(null)
                pending = null
                MusicCrashLog.log(
                    app,
                    "svc2: shared bind failed " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
    }

    /** The bound service when it is connected right now, else null. */
    fun connectedService(): LxPlaybackService? = service

    /** The bound service's player when connected right now, else null. */
    fun connectedPlayer(): Player? = service?.player

    /**
     * Waits (bounded by [timeoutMs]) for the in-flight bind and
     * returns the connected service, or null on timeout / failure.
     * Call [ensureConnecting] first; with nothing in flight this
     * returns whatever [connectedService] reports.
     */
    suspend fun awaitConnected(timeoutMs: Long): LxPlaybackService? {
        service?.let { return it }
        val deferred = pending ?: return service
        val svc = withTimeoutOrNull(timeoutMs) { deferred.await() }
        return svc ?: service
    }

    /**
     * Explicitly starts [LxPlaybackService] as a foreground service so
     * the system treats it as a started foreground service (harder to
     * throttle on Honor/MagicOS than a merely bound one). Call while
     * the app is in the foreground, when playback is about to be
     * handed to the service. Failures are logged and ignored.
     */
    fun startForegroundService(context: Context) {
        val app = context.applicationContext
        appContext = app
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, LxPlaybackService::class.java),
            )
            MusicCrashLog.log(app, "svc2: startForegroundService ok")
        } catch (t: Throwable) {
            MusicCrashLog.log(
                app,
                "svc2: startForegroundService failed " +
                    "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }
}
