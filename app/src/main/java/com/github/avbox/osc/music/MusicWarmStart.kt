package com.github.avbox.osc.music

import android.content.Context
import com.github.avbox.osc.util.KV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Warms the remembered music source at app start (v1.28.0), so
 * entering the music tab no longer pays the full load while the
 * user watches a spinner:
 *
 * - a remembered JS script is loaded into the bridge and awaited
 *   in the background — LxBridge.loadScript skips a script it
 *   already holds, so the page's own init then completes at once;
 * - a remembered py source's homeContent is fetched in the
 *   background, filling the bridge's memory + disk caches (and
 *   paying the Python cold start) before the page asks for it.
 *
 * Best-effort by design: any failure is swallowed and the music
 * page's normal init path retries properly when the user arrives.
 */
object MusicWarmStart {

    // Same key MusicViewModel persists the remembered source under
    // (KV_SCRIPT_ID there); duplicated because the music package
    // must not depend on the ui layer.
    private const val KV_SCRIPT_ID = "lx_music_script_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun warm(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                val saved: String = KV.get(KV_SCRIPT_ID, "")
                if (saved.startsWith(MusicTrack.PY_SOURCE_PREFIX)) {
                    val def = PyMusicSources.loadDefs(appContext).firstOrNull {
                        MusicTrack.PY_SOURCE_PREFIX + it.id == saved
                    }
                    if (def != null) {
                        PyMusicBridge.home(appContext, def)
                    }
                } else if (saved.isNotEmpty()) {
                    val def = LxUserScripts.loadDefs(appContext).firstOrNull {
                        it.id == saved
                    }
                    if (def != null) {
                        LxBridge.loadScript(def)
                        LxBridge.awaitInited()
                    }
                }
            } catch (t: Throwable) {
                // best-effort; the music page init retries on entry
            }
        }
    }
}
