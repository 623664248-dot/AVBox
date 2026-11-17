// Build retrigger marker: v1.15.3 (push trigger did not fire for cf43a42c).
package com.github.avbox.osc.music

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal crash evidence for the music player (v1.13.3): installs a
 * default uncaught-exception handler that appends the stack trace to
 * filesDir/music_crash.log and then delegates to the previously
 * installed handler, so the process's default crash behaviour is
 * unchanged. Installed once per process from both the player Activity
 * and the playback service (whichever starts first wins); exists so a
 * future on-device failure leaves a readable trace instead of the
 * untraceable v1.9.0 tap-to-play crash.
 *
 * v1.15.1 adds [log]: one-line diagnostic events (service connection
 * attempts, degrade reasons, mode locks) appended with a timestamp to
 * BOTH the internal log and the app-specific external files dir
 * (Android/data/<pkg>/files/music_crash.log), so the file can be
 * pulled off the device without root when background playback misbehaves.
 */
object MusicCrashLog {

    private const val LOG_FILE_NAME = "music_crash.log"
    private const val MAX_LOG_BYTES = 256 * 1024

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val filesDir = context.applicationContext.filesDir
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try {
                    val trace = StringWriter().also { writer ->
                        error.printStackTrace(PrintWriter(writer))
                    }.toString()
                    val stamp = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US,
                    ).format(Date())
                    appendTo(
                        File(filesDir, LOG_FILE_NAME),
                        "==== $stamp thread=${thread.name} ====\n$trace\n",
                    )
                } catch (t: Throwable) {
                    // Logging must never mask or alter the original crash.
                }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Appends one timestamped diagnostic line to the internal log and
     * to the external-files copy (skipped when the external dir is
     * unavailable). Never throws: diagnostics must not break playback.
     */
    fun log(context: Context, message: String) {
        try {
            val app = context.applicationContext
            val stamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.US,
            ).format(Date())
            val line = "[$stamp] $message\n"
            synchronized(this) {
                appendTo(File(app.filesDir, LOG_FILE_NAME), line)
                val external = try {
                    app.getExternalFilesDir(null)
                } catch (t: Throwable) {
                    null
                }
                if (external != null) {
                    appendTo(File(external, LOG_FILE_NAME), line)
                }
            }
        } catch (t: Throwable) {
            // Logging must never break the caller.
        }
    }

    /** Appends [text] to [file], restarting it past the size cap. */
    private fun appendTo(file: File, text: String) {
        try {
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.delete()
            }
            file.appendText(text)
        } catch (t: Throwable) {
            // best effort only
        }
    }
}
