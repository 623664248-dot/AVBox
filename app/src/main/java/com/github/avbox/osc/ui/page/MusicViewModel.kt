package com.github.avbox.osc.ui.page

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxUserScripts
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicChart
import com.github.avbox.osc.music.MusicTrack
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

/** Music tab content mode: platform search or per-platform charts. */
enum class MusicMode { SEARCH, CHARTS }

/**
 * Music tab state: owns the LX source bridge lifecycle (built-in scripts
 * plus user-imported ones), platform search (native, via MusicApi) and
 * pagination. The Compose page only renders this state.
 */
class MusicViewModel : ViewModel() {

    data class ScriptOption(
        val id: String,
        val label: String,
        val selected: Boolean,
        val user: Boolean = false,
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

    private val _chartSongsLoading = MutableStateFlow(false)
    val chartSongsLoading: StateFlow<Boolean> = _chartSongsLoading.asStateFlow()

    private val _scriptOptions = MutableStateFlow<List<ScriptOption>>(emptyList())
    val scriptOptions: StateFlow<List<ScriptOption>> = _scriptOptions.asStateFlow()

    private val _scriptBusy = MutableStateFlow(true)
    val scriptBusy: StateFlow<Boolean> = _scriptBusy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var lastKeyword: String = ""
    private var page: Int = 1
    private var hasMore: Boolean = false
    private var searchSeq: Int = 0
    private var chartSeq: Int = 0
    private var chartsSource: String = ""

    private var userDefs: List<LxBridge.ScriptDef> = emptyList()

    init {
        reloadUserDefs()
        refreshScriptOptions(selectedId = savedScriptId())
        viewModelScope.launch {
            val saved = savedScriptId()
            if (!initBridge(saved) && saved != LxBridge.SCRIPTS.first().id) {
                // The remembered script (e.g. an imported one) no longer
                // loads; fall back to the default built-in so the tab works.
                initBridge(LxBridge.SCRIPTS.first().id)
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

    /** All selectable scripts: built-ins first, then user-imported ones. */
    private fun allDefs(): List<LxBridge.ScriptDef> = LxBridge.SCRIPTS + userDefs

    private fun findDef(scriptId: String): LxBridge.ScriptDef =
        allDefs().firstOrNull { it.id == scriptId } ?: LxBridge.SCRIPTS.first()

    private fun reloadUserDefs() {
        val context = AppContextHolder.context() ?: return
        userDefs = LxUserScripts.loadDefs(context)
    }

    /**
     * Loads [scriptId] into the bridge. Returns false (after emitting the
     * failure toast) when the script cannot init; the caller decides on a
     * fallback. On failure the platform list is cleared because the failed
     * load already tore the previous script's context down.
     */
    private suspend fun initBridge(scriptId: String): Boolean {
        _scriptBusy.value = true
        val def = findDef(scriptId)
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
        if (id == LxBridge.loadedScriptId && _sources.value.isNotEmpty()) return
        val previousId = LxBridge.loadedScriptId
        refreshScriptOptions(selectedId = id)
        viewModelScope.launch {
            if (initBridge(id)) {
                // Only persist the choice once the script actually works.
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

    /** Deletes a user-imported script; falls back to the default if it was in use. */
    fun deleteScript(id: String) {
        if (userDefs.none { it.id == id }) return
        val context = AppContextHolder.context() ?: return
        val wasInUse = id == LxBridge.loadedScriptId || id == savedScriptId()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LxUserScripts.delete(context, id) }
            reloadUserDefs()
            _messages.tryEmit(str(R.string.music_source_deleted))
            if (wasInUse) {
                switchScript(LxBridge.SCRIPTS.first().id)
            } else {
                refreshScriptOptions(selectedId = selectedScriptId())
            }
        }
    }

    private fun selectedScriptId(): String =
        _scriptOptions.value.firstOrNull { it.selected }?.id ?: savedScriptId()

    private fun refreshScriptOptions(selectedId: String, info: LxBridge.ScriptInfo? = null) {
        _scriptOptions.value = allDefs().map { def ->
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
    }

    private fun savedScriptId(): String {
        val saved: String? = KV.get(KV_SCRIPT_ID)
        return saved?.takeIf { id -> allDefs().any { it.id == id } }
            ?: LxBridge.SCRIPTS.first().id
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
