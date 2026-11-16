package com.github.avbox.osc.ui.page

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxFavorites
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Music tab content mode: platform search, per-platform charts, favourites. */
enum class MusicMode { SEARCH, CHARTS, FAVORITES }

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

    /** Episode choice for a multi-episode (album/playlist) py vod. */
    data class PyEpisodePicker(
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

    private val _episodePicker = MutableStateFlow<PyEpisodePicker?>(null)
    val episodePicker: StateFlow<PyEpisodePicker?> = _episodePicker.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
            if (initBridge(id)) {
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
    private suspend fun activatePy(def: PyMusicSources.PyDef, persist: Boolean): Boolean {
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
            val firstClass = home.classes.firstOrNull()
            _selectedPyClass.value = firstClass?.id ?: ""
            if (firstClass != null) {
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

    fun selectPyClass(typeId: String) {
        if (!_pyActive.value) return
        if (typeId == _selectedPyClass.value && !_pySearchActive.value) return
        _selectedPyClass.value = typeId
        _pySearchActive.value = false
        pyKeyword = ""
        loadPyList(reset = true)
    }

    fun pySearch(keyword: String) {
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
     * multi-episode vods (albums/playlists) open the episode picker and
     * the chosen episode list becomes the queue.
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
                    _episodePicker.value = PyEpisodePicker(track.name, episodes)
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

    fun dismissEpisodePicker() {
        _episodePicker.value = null
    }

    fun playEpisode(index: Int) {
        val picker = _episodePicker.value ?: return
        _episodePicker.value = null
        _pendingPyPlay.value = PyPlayRequest(picker.episodes, index)
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
        if (mode == MusicMode.FAVORITES) loadFavorites()
    }

    /** Reloads the favourite list; the player page may have changed it. */
    fun loadFavorites() {
        _favorites.value = LxFavorites.load()
    }

    /** (Re)loads the chart catalog of the selected platform. */
    fun loadCharts() {
        val source = _selectedSource.value
        _selectedChart.value = null
        _chartSongs.value = emptyList()
        if (source.isEmpty()) {
            _charts.value = emptyList()
            chartsSource = ""
            return
        }
        if (source == chartsSource && _charts.value.isNotEmpty()) return
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

    // ------------------------------------------------------------- misc

    private fun str(resId: Int, vararg args: Any): String {
        val context = AppContextHolder.context() ?: return ""
        return LanguageManager.localized(context).getString(resId, *args)
    }

    companion object {
        private const val KV_SCRIPT_ID = "lx_music_script_id"
        private val SOURCE_ORDER = listOf("kg", "kw", "tx", "wy", "mg")
        private val SEARCHABLE_SOURCES = setOf("kg", "kw", "tx", "wy")
    }
}
