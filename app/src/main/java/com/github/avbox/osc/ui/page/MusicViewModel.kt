package com.github.avbox.osc.ui.page

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxFavorites
import com.github.avbox.osc.music.LxPlayHistory
import com.github.avbox.osc.music.LxUserScripts
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicChart
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.music.PyMusicBridge
import com.github.avbox.osc.music.PyMusicSources
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Music tab content mode: per-platform charts or platform search.
 * Internal state only since v1.19.1 — the tab row that switched it
 * by hand is gone; charts are the default, a submitted search
 * switches to SEARCH, and clearing the query (or system back)
 * switches back to CHARTS. The switch is a pure UI-content choice:
 * 音源 scripts and their interfaces are not involved.
 */
enum class MusicMode { SEARCH, CHARTS }

/**
 * A full-screen list page opened from the top-right multi-function
 * ball (v1.19.0): favourites and play history. Both used to render
 * inside the music content area (favourites as a mode in native
 * mode, as an overlay in spider mode); since v1.19.0 they are
 * whole-screen pages in both modes, like the chart detail and the
 * py episode page.
 */
enum class MusicListOverlay { FAVORITES, HISTORY }

/**
 * Music tab state: owns the LX source bridge lifecycle (user-imported
 * scripts and py spiders only since v1.15.0 — no bundled sources),
 * platform search (native, via MusicApi) and pagination. The Compose
 * page only renders this state.
 */
class MusicViewModel : ViewModel() {

    data class ScriptOption(
        val id: String,
        val label: String,
        val selected: Boolean,
        val user: Boolean = false,
        /** Py spider source; its id is "py:" + the PyMusicSources slug. */
        val py: Boolean = false,
    )

    /** A play request the page fulfils by opening the player activity. */
    data class PyPlayRequest(
        val queue: List<MusicTrack>,
        val index: Int,
    )

    /**
     * Episodes of a multi-episode (album/playlist) py vod, shown as
     * a full-screen list page (v1.18.1; a picker dialog before).
     */
    data class PyEpisodePage(
        val title: String,
        val episodes: List<MusicTrack>,
    )

    private val _hotWords = MutableStateFlow<List<String>>(emptyList())
    val hotWords: StateFlow<List<String>> = _hotWords.asStateFlow()

    private val _sources = MutableStateFlow<List<LxBridge.SourceInfo>>(emptyList())
    val sources: StateFlow<List<LxBridge.SourceInfo>> = _sources.asStateFlow()

    private val _selectedSource = MutableStateFlow("")
    val selectedSource: StateFlow<String> = _selectedSource.asStateFlow()

    private val _results = MutableStateFlow<List<MusicTrack>>(emptyList())
    val results: StateFlow<List<MusicTrack>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched.asStateFlow()

    private val _mode = MutableStateFlow(MusicMode.CHARTS)
    val mode: StateFlow<MusicMode> = _mode.asStateFlow()

    private val _charts = MutableStateFlow<List<MusicChart>>(emptyList())
    val charts: StateFlow<List<MusicChart>> = _charts.asStateFlow()

    private val _chartSongs = MutableStateFlow<List<MusicTrack>>(emptyList())
    val chartSongs: StateFlow<List<MusicTrack>> = _chartSongs.asStateFlow()

    private val _selectedChart = MutableStateFlow<MusicChart?>(null)
    val selectedChart: StateFlow<MusicChart?> = _selectedChart.asStateFlow()

    private val _chartsLoading = MutableStateFlow(false)
    val chartsLoading: StateFlow<Boolean> = _chartsLoading.asStateFlow()

    private val _favorites = MutableStateFlow<List<MusicTrack>>(emptyList())
    val favorites: StateFlow<List<MusicTrack>> = _favorites.asStateFlow()

    /** Menu-opened list overlay; null shows the regular mode content. */
    private val _listOverlay = MutableStateFlow<MusicListOverlay?>(null)
    val listOverlay: StateFlow<MusicListOverlay?> = _listOverlay.asStateFlow()

    /** Snapshot of [LxPlayHistory] taken when the history overlay opens. */
    private val _playHistory = MutableStateFlow<List<MusicTrack>>(emptyList())
    val playHistory: StateFlow<List<MusicTrack>> = _playHistory.asStateFlow()

    private val _chartSongsLoading = MutableStateFlow(false)
    val chartSongsLoading: StateFlow<Boolean> = _chartSongsLoading.asStateFlow()

    private val _scriptOptions = MutableStateFlow<List<ScriptOption>>(emptyList())
    val scriptOptions: StateFlow<List<ScriptOption>> = _scriptOptions.asStateFlow()

    private val _scriptBusy = MutableStateFlow(true)
    val scriptBusy: StateFlow<Boolean> = _scriptBusy.asStateFlow()

    /** True when the user has not imported any source (JS or py) at all. */
    private val _noSource = MutableStateFlow(false)
    val noSource: StateFlow<Boolean> = _noSource.asStateFlow()

    // ------------------------------------------------------------- py mode

    /** True while a py spider source is the selected music source. */
    private val _pyActive = MutableStateFlow(false)
    val pyActive: StateFlow<Boolean> = _pyActive.asStateFlow()

    private val _pyClasses = MutableStateFlow<List<PyMusicBridge.PyClass>>(emptyList())
    val pyClasses: StateFlow<List<PyMusicBridge.PyClass>> = _pyClasses.asStateFlow()

    private val _selectedPyClass = MutableStateFlow("")
    val selectedPyClass: StateFlow<String> = _selectedPyClass.asStateFlow()

    /** Current spider list: a category page feed or search results. */
    private val _pyItems = MutableStateFlow<List<MusicTrack>>(emptyList())
    val pyItems: StateFlow<List<MusicTrack>> = _pyItems.asStateFlow()

    private val _pyLoading = MutableStateFlow(false)
    val pyLoading: StateFlow<Boolean> = _pyLoading.asStateFlow()

    private val _pyLoadingMore = MutableStateFlow(false)
    val pyLoadingMore: StateFlow<Boolean> = _pyLoadingMore.asStateFlow()

    private val _pySearchActive = MutableStateFlow(false)
    val pySearchActive: StateFlow<Boolean> = _pySearchActive.asStateFlow()

    private val _pyDetailLoading = MutableStateFlow(false)
    val pyDetailLoading: StateFlow<Boolean> = _pyDetailLoading.asStateFlow()

    private val _pendingPyPlay = MutableStateFlow<PyPlayRequest?>(null)
    val pendingPyPlay: StateFlow<PyPlayRequest?> = _pendingPyPlay.asStateFlow()

    private val _episodePage = MutableStateFlow<PyEpisodePage?>(null)
    val episodePage: StateFlow<PyEpisodePage?> = _episodePage.asStateFlow()

    /**
     * True while any music sub-page is open: a chart detail, a
     * menu-opened list (favourites / history) or a py episode page.
     * The app shell collects this and hides every navigation bar
     * while it is true (and the music tab is current), so the
     * sub-page takes over the whole screen (v1.19.0) — the same
     * shell-flag pattern the home tab's full-screen web view uses.
     */
    val subPageOpen: StateFlow<Boolean> =
        combine(_selectedChart, _listOverlay, _episodePage) { chart, overlay, episode ->
            chart != null || overlay != null || episode != null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** One-shot: play this queue at this index (voice-search hit). */
    private val _playRequests =
        MutableSharedFlow<Pair<List<MusicTrack>, Int>>(extraBufferCapacity = 1)
    val playRequests: SharedFlow<Pair<List<MusicTrack>, Int>> = _playRequests.asSharedFlow()


    private var lastKeyword: String = ""
    private var page: Int = 1
    private var hasMore: Boolean = false
    private var searchSeq: Int = 0
    private var chartSeq: Int = 0
    private var chartsSource: String = ""

    private var userDefs: List<LxBridge.ScriptDef> = emptyList()

    private var pyDefs: List<PyMusicSources.PyDef> = emptyList()
    private var currentPyDef: PyMusicSources.PyDef? = null
    private var pyPage: Int = 1
    private var pyHasMore: Boolean = false
    private var pyKeyword: String = ""

    /** Voice search: auto-play the first song row when results land. */
    private var pyVoicePlayPending: Boolean = false
    private var pySeq: Int = 0

    init {
        reloadUserDefs()
        reloadPyDefs()
        refreshScriptOptions(selectedId = savedScriptId())
        viewModelScope.launch {
            // A remembered py source re-activates in spider mode and a
            // remembered JS script re-inits the bridge. When the
            // remembered source is gone (e.g. a bundled source removed
            // in v1.15.0) or fails to load, the first remaining user
            // source in dialog order (JS scripts, then py spiders)
            // takes over. With no user sources at all the tab stays in
            // its empty state: no bridge init, nothing that can fail.
            var started = false
            val saved: String = KV.get(KV_SCRIPT_ID, "")
            if (saved.startsWith(MusicTrack.PY_SOURCE_PREFIX)) {
                val def = pyDefs.firstOrNull {
                    MusicTrack.PY_SOURCE_PREFIX + it.id == saved
                }
                if (def != null) {
                    started = activatePy(def, persist = false)
                }
            }
            if (!started && saved.isNotEmpty() &&
                !saved.startsWith(MusicTrack.PY_SOURCE_PREFIX) &&
                userDefs.any { it.id == saved }
            ) {
                started = initBridge(saved)
            }
            if (!started) {
                started = startFirstAvailable(exclude = setOf(saved))
            }
            if (!started && userDefs.isEmpty() && pyDefs.isEmpty()) {
                _scriptBusy.value = false
            }
            // Charts is the default mode: once the platform list is ready,
            // load its catalog. Script switches and platform changes reload
            // through their own paths, so this hook runs only at startup.
            if (_mode.value == MusicMode.CHARTS) loadCharts()
        }
        viewModelScope.launch {
            _hotWords.value = MusicApi.hotWords()
        }
    }

    // ------------------------------------------------------------- bridge

    /** All selectable JS scripts: the user imports (no bundled ones). */
    private fun allDefs(): List<LxBridge.ScriptDef> = userDefs

    private fun findDef(scriptId: String): LxBridge.ScriptDef? =
        userDefs.firstOrNull { it.id == scriptId }

    private fun reloadUserDefs() {
        val context = AppContextHolder.context() ?: return
        userDefs = LxUserScripts.loadDefs(context)
        updateNoSource()
    }

    private fun reloadPyDefs() {
        val context = AppContextHolder.context() ?: return
        pyDefs = PyMusicSources.loadDefs(context)
        updateNoSource()
    }

    private fun updateNoSource() {
        _noSource.value = userDefs.isEmpty() && pyDefs.isEmpty()
    }

    /**
     * Tries the remaining user sources in dialog order (JS scripts
     * first, then py spiders) until one starts. [exclude] holds option
     * ids (a raw JS id, or "py:" + slug for spiders) to skip, e.g. the
     * remembered source that just failed. With [persist] the winner is
     * also written to KV as the remembered choice.
     */
    private suspend fun startFirstAvailable(
        exclude: Set<String> = emptySet(),
        persist: Boolean = false,
    ): Boolean {
        for (def in userDefs) {
            if (def.id in exclude) continue
            if (initBridge(def.id)) {
                if (persist) KV.put(KV_SCRIPT_ID, def.id)
                return true
            }
        }
        for (def in pyDefs) {
            if (MusicTrack.PY_SOURCE_PREFIX + def.id in exclude) continue
            if (activatePy(def, persist = persist)) return true
        }
        return false
    }

    /**
     * After the in-use source went away (deleted): start another user
     * source, or settle into the no-source empty state with a cleared
     * platform list instead of pointing at a dead script id.
     */
    private suspend fun enterFallbackOrNoSource() {
        if (startFirstAvailable(persist = true)) return
        _pyActive.value = false
        currentPyDef = null
        _sources.value = emptyList()
        _selectedSource.value = ""
        KV.put(KV_SCRIPT_ID, "")
        refreshScriptOptions(selectedId = "")
        _scriptBusy.value = false
    }

    /**
     * Loads [scriptId] into the bridge. Returns false (after emitting the
     * failure toast) when the script cannot init; the caller decides on a
     * fallback. On failure the platform list is cleared because the failed
     * load already tore the previous script's context down.
     */
    private suspend fun initBridge(scriptId: String): Boolean {
        val def = findDef(scriptId) ?: return false
        _scriptBusy.value = true
        return try {
            val info = LxBridge.loadScript(def)
            val registry = LxBridge.awaitInited()
            applyRegistry(registry)
            refreshScriptOptions(selectedId = def.id, info = info)
            _messages.tryEmit(str(R.string.music_script_ready, info.name + " " + info.version))
            true
        } catch (e: LxBridge.LxException) {
            _sources.value = emptyList()
            _messages.tryEmit(str(R.string.music_script_init_failed, e.message ?: ""))
            false
        } catch (e: Exception) {
            _sources.value = emptyList()
            _messages.tryEmit(str(R.string.music_script_init_failed, e.message ?: ""))
            false
        } finally {
            _scriptBusy.value = false
        }
    }

    /**
     * Re-activates the script still loaded in the bridge (v1.25.0):
     * switching to a py source never unloads the JS runtime, so
     * coming back to the same script needs no re-init — its source
     * registry is still there and awaitInited returns at once.
     * Previously this path paid the script's full init (its server
     * checks included, ~15 s for 星海） all over again. Returns
     * false when the bridge holds another (or no) script, or the
     * registry is gone; the caller then does the full init.
     */
    private suspend fun reuseLoadedScript(id: String): Boolean {
        if (id != LxBridge.loadedScriptId) return false
        val info = LxBridge.scriptInfo ?: return false
        return try {
            applyRegistry(LxBridge.awaitInited())
            refreshScriptOptions(selectedId = id, info = info)
            _pyActive.value = false
            currentPyDef = null
            KV.put(KV_SCRIPT_ID, id)
            _messages.tryEmit(
                str(R.string.music_script_ready, info.name + " " + info.version),
            )
            if (lastKeyword.isNotEmpty() && _selectedSource.value.isNotEmpty()) {
                search(lastKeyword)
            }
            if (_mode.value == MusicMode.CHARTS) {
                loadCharts()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyRegistry(registry: Map<String, LxBridge.SourceInfo>) {
        // Only offer platforms MusicApi can actually search; the scripts also
        // register sources like mg/qs that have no native search yet, and
        // showing them would dead-end on an empty result list.
        val supported = registry.values.filter {
            it.actions.contains("musicUrl") && it.code in SEARCHABLE_SOURCES
        }
        val ordered = supported.sortedBy { SOURCE_ORDER.indexOf(it.code).let { i -> if (i < 0) 99 else i } }
        _sources.value = ordered
        val current = _selectedSource.value
        if (ordered.none { it.code == current }) {
            _selectedSource.value = ordered.firstOrNull()?.code ?: ""
        }
    }

    fun switchScript(id: String) {
        if (id.startsWith(MusicTrack.PY_SOURCE_PREFIX)) {
            val def = pyDefs.firstOrNull {
                MusicTrack.PY_SOURCE_PREFIX + it.id == id
            } ?: return
            if (_pyActive.value && currentPyDef?.id == def.id) return
            refreshScriptOptions(selectedId = id)
            viewModelScope.launch { activatePy(def, persist = true) }
            return
        }
        if (id == LxBridge.loadedScriptId && _sources.value.isNotEmpty() && !_pyActive.value) return
        val previousId = LxBridge.loadedScriptId
        refreshScriptOptions(selectedId = id)
        viewModelScope.launch {
            if (reuseLoadedScript(id)) {
                // The bridge still holds this script (a py detour
                // never unloads it): re-applied without a re-init.
            } else if (initBridge(id)) {
                // Only persist the choice once the script actually works.
                _pyActive.value = false
                currentPyDef = null
                KV.put(KV_SCRIPT_ID, id)
                if (lastKeyword.isNotEmpty() && _selectedSource.value.isNotEmpty()) {
                    search(lastKeyword)
                }
                if (_mode.value == MusicMode.CHARTS) {
                    loadCharts()
                }
            } else if (previousId != null && previousId != id && allDefs().any { it.id == previousId }) {
                // The new script failed: reload the one that worked before
                // so the tab is not left with an empty platform list.
                refreshScriptOptions(selectedId = previousId)
                if (initBridge(previousId)) {
                    _pyActive.value = false
                    currentPyDef = null
                    KV.put(KV_SCRIPT_ID, previousId)
                }
            }
        }
    }

    /**
     * The 音源 dialog's single import entry (v1.18.0): decides whether
     * the picked file is a JS source or a py spider and routes to the
     * matching existing import below. The extension wins when present;
     * otherwise the head of the file is sniffed for the spider markers
     * or the LX registration signs [LxUserScripts] validates against.
     * An unrecognized file only toasts and changes nothing.
     */
    fun importSource(uri: Uri) {
        val context = AppContextHolder.context() ?: return
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                queryDisplayName(context, uri)
            }
            val lower = name.lowercase()
            when {
                lower.endsWith(".py") -> importPyScript(uri)
                lower.endsWith(".js") -> importScript(uri)
                else -> {
                    val head = withContext(Dispatchers.IO) {
                        readHeadText(context, uri)
                    }
                    when {
                        head.contains("base.spider") || head.contains("class Spider") ->
                            importPyScript(uri)
                        LX_SCRIPT_SIGNS.any { head.contains(it) } ->
                            importScript(uri)
                        else -> _messages.tryEmit(str(R.string.music_import_unknown))
                    }
                }
            }
        }
    }

    private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
        var name = ""
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: ""
                }
            }
        } catch (e: Exception) {
            // No name: the content sniff below decides instead.
        }
        return name
    }

    /** First chunk of the picked file as text; "" when unreadable. */
    private fun readHeadText(context: android.content.Context, uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(SNIFF_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                String(buffer, 0, total, Charsets.UTF_8).removePrefix("\uFEFF")
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Imports a user-picked .js source script, then switches to it. */
    fun importScript(uri: Uri) {
        val context = AppContextHolder.context() ?: return
        viewModelScope.launch {
            try {
                val def = withContext(Dispatchers.IO) { LxUserScripts.importScript(context, uri) }
                reloadUserDefs()
                refreshScriptOptions(selectedId = selectedScriptId())
                _messages.tryEmit(
                    str(R.string.music_source_imported, (def.fallbackName + " " + def.version).trim()),
                )
                switchScript(def.id)
            } catch (e: Exception) {
                _messages.tryEmit(str(R.string.music_source_import_failed, e.message ?: ""))
            }
        }
    }

    /** Deletes a user-imported script; falls back to another user source if it was in use. */
    fun deleteScript(id: String) {
        if (userDefs.none { it.id == id }) return
        val context = AppContextHolder.context() ?: return
        val wasInUse = id == LxBridge.loadedScriptId || id == savedScriptId()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LxUserScripts.delete(context, id) }
            reloadUserDefs()
            _messages.tryEmit(str(R.string.music_source_deleted))
            if (wasInUse) {
                enterFallbackOrNoSource()
            } else {
                refreshScriptOptions(selectedId = selectedScriptId())
            }
        }
    }

    // ------------------------------------------------------------- py sources

    /**
     * Loads [def]'s homeContent and enters spider mode. State is only
     * mutated on success: when the spider fails to load, the previously
     * working source (JS or py) stays selected and a toast gives the
     * reason. Returns whether spider mode was entered.
     */
    private suspend fun activatePy(
        def: PyMusicSources.PyDef,
        persist: Boolean,
        keepClass: Boolean = false,
    ): Boolean {
        val context = AppContextHolder.context() ?: return false
        _scriptBusy.value = true
        return try {
            val home = PyMusicBridge.home(context, def)
            currentPyDef = def
            _pyActive.value = true
            _pyClasses.value = home.classes
            _pySearchActive.value = false
            pyKeyword = ""
            pyPage = 1
            pyHasMore = false
            if (persist) KV.put(KV_SCRIPT_ID, MusicTrack.PY_SOURCE_PREFIX + def.id)
            refreshScriptOptions(selectedId = MusicTrack.PY_SOURCE_PREFIX + def.id)
            _messages.tryEmit(str(R.string.music_script_ready, def.name))
            // A refresh (keepClass) stays on the category the user
            // is browsing when it still exists; activations from a
            // switch or startup start at the first category.
            val keepId = if (keepClass) _selectedPyClass.value else ""
            val target = home.classes.firstOrNull { it.id == keepId }
                ?: home.classes.firstOrNull()
            _selectedPyClass.value = target?.id ?: ""
            if (target != null) {
                loadPyList(reset = true)
            } else {
                // No categories: fall back to whatever homeContent listed.
                _pyItems.value = home.items
            }
            true
        } catch (e: Exception) {
            _messages.tryEmit(str(R.string.music_py_load_failed, e.message ?: ""))
            // The previously working source stays in charge; point the
            // option highlight back at it (py source if one is active,
            // else whichever JS script the bridge still holds).
            val activePy = currentPyDef
            val activeId = when {
                _pyActive.value && activePy != null ->
                    MusicTrack.PY_SOURCE_PREFIX + activePy.id
                LxBridge.loadedScriptId != null -> LxBridge.loadedScriptId!!
                else -> selectedScriptId()
            }
            refreshScriptOptions(selectedId = activeId)
            false
        } finally {
            _scriptBusy.value = false
        }
    }

    /** Imports a user-picked .py spider, then switches to it. */
    fun importPyScript(uri: Uri) {
        val context = AppContextHolder.context() ?: return
        viewModelScope.launch {
            try {
                val def = withContext(Dispatchers.IO) {
                    PyMusicSources.importScript(context, uri)
                }
                // An import always mints a fresh slug, so this is a
                // defensive no-op today; it keeps the bridge cache
                // correct if imports ever replace a file in place.
                PyMusicBridge.clearCacheFor(def.id)
                reloadPyDefs()
                refreshScriptOptions(selectedId = selectedScriptId())
                _messages.tryEmit(str(R.string.music_source_imported, def.name))
                switchScript(MusicTrack.PY_SOURCE_PREFIX + def.id)
            } catch (e: Exception) {
                _messages.tryEmit(str(R.string.music_py_import_failed, e.message ?: ""))
            }
        }
    }

    /** Deletes a user-imported py spider; falls back to another user source if in use. */
    fun deletePyScript(optionId: String) {
        val defId = optionId.removePrefix(MusicTrack.PY_SOURCE_PREFIX)
        val def = pyDefs.firstOrNull { it.id == defId && it.user } ?: return
        val context = AppContextHolder.context() ?: return
        val wasInUse = _pyActive.value && currentPyDef?.id == def.id
        viewModelScope.launch {
            withContext(Dispatchers.IO) { PyMusicSources.delete(context, def.id) }
            PyMusicBridge.clearCacheFor(def.id)
            reloadPyDefs()
            _messages.tryEmit(str(R.string.music_source_deleted))
            if (wasInUse) {
                currentPyDef = null
                _pyActive.value = false
                enterFallbackOrNoSource()
            } else {
                refreshScriptOptions(selectedId = selectedScriptId())
            }
        }
    }

    /**
     * Long-press on the top-left source (vinyl) button (v1.19.2):
     * refreshes the CURRENT source in place — the user-facing
     * "刷新站点". A py source drops its bridge-side home / page-1
     * caches and re-runs activation, so homeContent and the first
     * category are fetched from the spider again instead of replayed
     * from the v1.17.0 cache; the spider instance itself is kept
     * (the loader only offers an all-or-nothing clear, which would
     * disturb the video side). A JS source re-inits its script and
     * reloads the visible content (search results or charts). With
     * no source imported there is nothing to refresh.
     */
    fun refreshCurrentSource() {
        val def = currentPyDef
        if (_pyActive.value && def != null) {
            viewModelScope.launch {
                PyMusicBridge.clearCacheFor(def.id)
                activatePy(def, persist = false, keepClass = true)
            }
            return
        }
        val scriptId = LxBridge.loadedScriptId ?: return
        viewModelScope.launch {
            if (initBridge(scriptId)) {
                if (lastKeyword.isNotEmpty() && _selectedSource.value.isNotEmpty()) {
                    search(lastKeyword)
                }
                if (_mode.value == MusicMode.CHARTS) {
                    loadCharts(force = true)
                }
            }
        }
    }

    fun selectPyClass(typeId: String) {
        if (!_pyActive.value) return
        if (typeId == _selectedPyClass.value && !_pySearchActive.value) {
            // Re-tapping the already selected category refreshes it
            // from page 1 (v1.15.2); the page scrolls the list back
            // to the top when the reset load starts.
            loadPyList(reset = true)
            return
        }
        _selectedPyClass.value = typeId
        _pySearchActive.value = false
        pyKeyword = ""
        loadPyList(reset = true)
    }

    fun pySearch(keyword: String) {
        pyVoicePlayPending = false
        val kw = keyword.trim()
        if (kw.isEmpty() || !_pyActive.value) return
        pyKeyword = kw
        _pySearchActive.value = true
        loadPyList(reset = true)
    }

    /** Leaves spider search results and reloads the selected category. */
    fun pyClearSearch() {
        if (!_pySearchActive.value) return
        _pySearchActive.value = false
        pyKeyword = ""
        loadPyList(reset = true)
    }

    fun pyLoadMore() {
        loadPyList(reset = false)
    }

    private fun loadPyList(reset: Boolean) {
        val def = currentPyDef ?: return
        val context = AppContextHolder.context() ?: return
        if (reset) {
            pyPage = 1
            pyHasMore = true
            _pyLoading.value = true
        } else {
            if (_pyLoading.value || _pyLoadingMore.value || !pyHasMore) return
            _pyLoadingMore.value = true
        }
        val seq = ++pySeq
        val targetPage = if (reset) 1 else pyPage + 1
        val searching = _pySearchActive.value
        val keyword = pyKeyword
        val typeId = _selectedPyClass.value
        viewModelScope.launch {
            try {
                val result = if (searching) {
                    PyMusicBridge.search(context, def, keyword, targetPage)
                } else {
                    PyMusicBridge.category(context, def, typeId, targetPage)
                }
                if (seq != pySeq) return@launch
                pyPage = targetPage
                pyHasMore = result.items.isNotEmpty() &&
                    (result.pageCount <= 0 || targetPage < result.pageCount)
                _pyItems.value = if (reset) {
                    result.items
                } else {
                    appendDedup(_pyItems.value, result.items)
                }
                if (reset && searching && pyVoicePlayPending) {
                    pyVoicePlayPending = false
                    val firstSong = _pyItems.value.firstOrNull {
                        !it.songmid.startsWith("artist:")
                    }
                    if (firstSong != null) {
                        onPyTrackClick(firstSong)
                    }
                }
            } catch (e: Exception) {
                if (seq == pySeq) {
                    _messages.tryEmit(str(R.string.music_py_load_failed, e.message ?: ""))
                }
            } finally {
                if (seq == pySeq) {
                    _pyLoading.value = false
                    _pyLoadingMore.value = false
                }
            }
        }
    }

    /** Spiders often repeat page 1 when asked past the end; dedupe appends. */
    private fun appendDedup(
        old: List<MusicTrack>,
        new: List<MusicTrack>,
    ): List<MusicTrack> {
        if (new.isEmpty()) return old
        val seen = old.mapTo(HashSet()) { it.songmid + "|" + it.name }
        return old + new.filterNot { (it.songmid + "|" + it.name) in seen }
    }

    /**
     * A tap on a spider list row: one-episode vods play directly with the
     * current list as the queue (each row resolves lazily at its turn);
     * multi-episode vods (albums/playlists) open the full-screen episode
     * list and the chosen episode list becomes the queue.
     */
    fun onPyTrackClick(track: MusicTrack) {
        val def = currentPyDef ?: return
        val context = AppContextHolder.context() ?: return
        if (_pyDetailLoading.value) return
        _pyDetailLoading.value = true
        viewModelScope.launch {
            try {
                val episodes = PyMusicBridge.detailEpisodes(context, def, track)
                if (episodes.size <= 1) {
                    val queue = _pyItems.value
                    val index = queue.indexOfFirst {
                        it.songmid == track.songmid && it.name == track.name
                    }
                    _pendingPyPlay.value = PyPlayRequest(queue, if (index >= 0) index else 0)
                } else {
                    _episodePage.value = PyEpisodePage(track.name, episodes)
                }
            } catch (e: Exception) {
                _messages.tryEmit(str(R.string.music_py_load_failed, e.message ?: ""))
            } finally {
                _pyDetailLoading.value = false
            }
        }
    }

    fun consumePyPlay() {
        _pendingPyPlay.value = null
    }

    /**
     * 播放全部 on a spider song list (v1.19.2): the current list's
     * song rows become the play queue from the top; each row resolves
     * lazily at its turn, exactly like the queue a single-row tap
     * builds in [onPyTrackClick]. Singer rows (vod ids starting
     * "artist:") are not songs — a singer's list lives behind its
     * detail — so they are filtered out; with no song rows at all
     * the call is a no-op (the page hides the button then anyway).
     */
    fun pyPlayAll() {
        val queue = _pyItems.value.filterNot { it.songmid.startsWith("artist:") }
        if (queue.isEmpty()) return
        _pendingPyPlay.value = PyPlayRequest(queue, 0)
    }

    fun closeEpisodePage() {
        _episodePage.value = null
    }

    fun playEpisode(index: Int) {
        val page = _episodePage.value ?: return
        _episodePage.value = null
        _pendingPyPlay.value = PyPlayRequest(page.episodes, index)
    }

    private fun selectedScriptId(): String =
        _scriptOptions.value.firstOrNull { it.selected }?.id ?: savedScriptId()

    private fun refreshScriptOptions(selectedId: String, info: LxBridge.ScriptInfo? = null) {
        val jsOptions = allDefs().map { def ->
            val label = when {
                def.id == selectedId && info != null -> (info.name + " " + info.version).trim()
                def.version.isNotEmpty() -> (def.fallbackName + " " + def.version).trim()
                else -> def.fallbackName
            }
            ScriptOption(
                id = def.id,
                label = label,
                selected = def.id == selectedId,
                user = def.filePath != null,
            )
        }
        val pyOptions = pyDefs.map { def ->
            ScriptOption(
                id = MusicTrack.PY_SOURCE_PREFIX + def.id,
                label = def.name,
                selected = MusicTrack.PY_SOURCE_PREFIX + def.id == selectedId,
                user = def.user,
                py = true,
            )
        }
        _scriptOptions.value = jsOptions + pyOptions
    }

    private fun savedScriptId(): String {
        // Must pass a String default: KV returns null for keys that are not
        // registered in the KVKeySpec table when no default is given, which
        // made the saved script choice never persist. "" means "not saved"
        // — and, since v1.15.0, also the answer when the remembered id no
        // longer names a user source (there is no bundled fallback).
        val saved: String = KV.get(KV_SCRIPT_ID, "")
        if (saved.startsWith(MusicTrack.PY_SOURCE_PREFIX)) {
            return saved.takeIf { id ->
                pyDefs.any { MusicTrack.PY_SOURCE_PREFIX + it.id == id }
            } ?: ""
        }
        return saved.takeIf { id -> id.isNotEmpty() && userDefs.any { it.id == id } }
            ?: ""
    }

    // ------------------------------------------------------------- search

    fun selectSource(code: String) {
        if (code == _selectedSource.value) return
        _selectedSource.value = code
        if (lastKeyword.isNotEmpty()) search(lastKeyword)
        if (_mode.value == MusicMode.CHARTS) loadCharts()
    }

    fun search(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val source = _selectedSource.value
        if (source.isEmpty()) {
            _messages.tryEmit(str(R.string.music_script_not_ready))
            return
        }
        lastKeyword = kw
        page = 1
        hasMore = false
        val seq = ++searchSeq
        _loading.value = true
        _searched.value = true
        viewModelScope.launch {
            val list = MusicApi.search(source, kw, 1)
            if (seq != searchSeq) return@launch
            _results.value = list
            hasMore = list.size >= MusicApi.PAGE_SIZE
            _loading.value = false
        }
    }

    fun loadMore() {
        if (_loading.value || _loadingMore.value || !hasMore) return
        val source = _selectedSource.value
        if (source.isEmpty() || lastKeyword.isEmpty()) return
        val seq = searchSeq
        val nextPage = page + 1
        _loadingMore.value = true
        viewModelScope.launch {
            val list = MusicApi.search(source, lastKeyword, nextPage)
            if (seq != searchSeq) return@launch
            page = nextPage
            hasMore = list.size >= MusicApi.PAGE_SIZE
            if (list.isNotEmpty()) {
                _results.value = _results.value + list
            }
            _loadingMore.value = false
        }
    }

    // ------------------------------------------------------------- charts

    fun selectMode(mode: MusicMode) {
        if (mode == _mode.value) return
        _mode.value = mode
        if (mode == MusicMode.CHARTS) loadCharts()
    }

    /** Reloads the favourite list; the player page may have changed it. */
    fun loadFavorites() {
        _favorites.value = LxFavorites.load()
    }

    // ------------------------------------------------------------- menu lists

    /**
     * Menu entry 歌曲收藏： the full-screen favourites page in both
     * modes since v1.19.0 (the FAVORITES mode and its tab are gone;
     * the ball is the only entry).
     */
    fun openFavorites() {
        loadFavorites()
        _listOverlay.value = MusicListOverlay.FAVORITES
    }

    /** Menu entry 播放记录： the full-screen history page. */
    fun openHistory() {
        val context = AppContextHolder.context() ?: return
        _playHistory.value = LxPlayHistory.all(context)
        _listOverlay.value = MusicListOverlay.HISTORY
    }

    fun closeOverlay() {
        _listOverlay.value = null
    }

    /** 清空 on the history overlay: wipes the store and the snapshot. */
    fun clearHistory() {
        val context = AppContextHolder.context() ?: return
        LxPlayHistory.clear(context)
        _playHistory.value = emptyList()
    }

    /** (Re)loads the chart catalog of the selected platform. */
    fun loadCharts(force: Boolean = false) {
        val source = _selectedSource.value
        _selectedChart.value = null
        _chartSongs.value = emptyList()
        if (source.isEmpty()) {
            _charts.value = emptyList()
            chartsSource = ""
            return
        }
        if (!force && source == chartsSource && _charts.value.isNotEmpty()) return
        val seq = ++chartSeq
        _chartsLoading.value = true
        viewModelScope.launch {
            val list = MusicApi.charts(source)
            if (seq != chartSeq) return@launch
            chartsSource = source
            _charts.value = list
            _chartsLoading.value = false
        }
    }

    fun openChart(chart: MusicChart) {
        val source = _selectedSource.value
        if (source.isEmpty()) return
        _selectedChart.value = chart
        _chartSongs.value = emptyList()
        val seq = ++chartSeq
        _chartSongsLoading.value = true
        viewModelScope.launch {
            val list = MusicApi.chartSongs(source, chart.id, 1)
            if (seq != chartSeq) return@launch
            _chartSongs.value = list
            _chartSongsLoading.value = false
        }
    }

    fun closeChart() {
        chartSeq++
        _selectedChart.value = null
        _chartSongs.value = emptyList()
        _chartSongsLoading.value = false
    }

    // ------------------------------------------------- voice search play

    /**
     * Voice search follow-through (v1.21.0, Torres: 识别好直接播放）:
     * like [search], but the first result is handed to the page as a
     * play request, so a dictated song starts playing instead of
     * just landing in the result list.
     */
    fun voiceSearchAndPlay(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        val source = _selectedSource.value
        if (source.isEmpty()) {
            search(kw)
            return
        }
        lastKeyword = kw
        page = 1
        hasMore = false
        val seq = ++searchSeq
        _loading.value = true
        _searched.value = true
        viewModelScope.launch {
            val list = MusicApi.search(source, kw, 1)
            if (seq != searchSeq) return@launch
            _results.value = list
            hasMore = list.size >= MusicApi.PAGE_SIZE
            _loading.value = false
            if (list.isNotEmpty()) {
                _playRequests.tryEmit(list to 0)
            }
        }
    }

    /**
     * Voice search in spider mode: run the spider search and flag
     * its first song row for [onPyTrackClick] once the results land
     * (see loadPyList), so it plays straight away.
     */
    fun voicePySearch(keyword: String) {
        pySearch(keyword)
        pyVoicePlayPending = true
    }

    // ------------------------------------------------------------- misc

    private fun str(resId: Int, vararg args: Any): String {
        val context = AppContextHolder.context() ?: return ""
        return LanguageManager.localized(context).getString(resId, *args)
    }

    companion object {
        private const val KV_SCRIPT_ID = "lx_music_script_id"

        /** How much of a picked file the import dispatch sniffs. */
        private const val SNIFF_BYTES = 64 * 1024

        /** The LX registration signs [LxUserScripts] validates against. */
        private val LX_SCRIPT_SIGNS =
            listOf("globalThis.lx", "EVENT_NAMES", "lx.on(", "lx.send(", "window.lx")
        private val SOURCE_ORDER = listOf("kg", "kw", "tx", "wy", "mg")
        private val SEARCHABLE_SOURCES = setOf("kg", "kw", "tx", "wy")
    }
}
