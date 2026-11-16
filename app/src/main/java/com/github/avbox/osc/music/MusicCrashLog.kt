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
                    val file = File(filesDir, LOG_FILE_NAME)
                    if (file.exists() && file.length() > MAX_LOG_BYTES) {
                        file.delete()
                    }
                    val trace = StringWriter().also { writer ->
                        error.printStackTrace(PrintWriter(writer))
                    }.toString()
                    val stamp = SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        Locale.US,
                    ).format(Date())
                    file.appendText("==== $stamp thread=${thread.name} ====\n$trace\n")
                } catch (t: Throwable) {
                    // Logging must never mask or alter the original crash.
                }
                previous?.uncaughtException(thread, error)
            }
        }
    }
}
