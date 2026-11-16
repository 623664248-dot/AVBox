package com.github.avbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.MusicApi
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.LanguageManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Music tab state: owns the LX source bridge lifecycle (built-in scripts),
 * platform search (native, via MusicApi) and pagination. The Compose page
 * only renders this state.
 */
class MusicViewModel : ViewModel() {

    data class ScriptOption(val id: String, val label: String, val selected: Boolean)

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

    init {
        refreshScriptOptions(selectedId = savedScriptId())
        viewModelScope.launch {
            initBridge(savedScriptId())
        }
        viewModelScope.launch {
            _hotWords.value = MusicApi.hotWords()
        }
    }

    // ------------------------------------------------------------- bridge

    private suspend fun initBridge(scriptId: String) {
        _scriptBusy.value = true
        val def = LxBridge.SCRIPTS.firstOrNull { it.id == scriptId } ?: LxBridge.SCRIPTS.first()
        try {
            val info = LxBridge.loadScript(def)
            val registry = LxBridge.awaitInited()
            applyRegistry(registry)
            refreshScriptOptions(selectedId = def.id, info = info)
            _messages.tryEmit(str(R.string.music_script_ready, info.name + " " + info.version))
        } catch (e: LxBridge.LxException) {
            _sources.value = emptyList()
            _messages.tryEmit(str(R.string.music_script_init_failed, e.message ?: ""))
        } catch (e: Exception) {
            _sources.value = emptyList()
            _messages.tryEmit(str(R.string.music_script_init_failed, e.message ?: ""))
        } finally {
            _scriptBusy.value = false
        }
    }

    private fun applyRegistry(registry: Map<String, LxBridge.SourceInfo>) {
        val supported = registry.values.filter { it.actions.contains("musicUrl") }
        val ordered = supported.sortedBy { SOURCE_ORDER.indexOf(it.code).let { i -> if (i < 0) 99 else i } }
        _sources.value = ordered
        val current = _selectedSource.value
        if (ordered.none { it.code == current }) {
            _selectedSource.value = ordered.firstOrNull()?.code ?: ""
        }
    }

    fun switchScript(id: String) {
        if (id == LxBridge.loadedScriptId && _sources.value.isNotEmpty()) return
        KV.put(KV_SCRIPT_ID, id)
        refreshScriptOptions(selectedId = id)
        viewModelScope.launch {
            initBridge(id)
            if (lastKeyword.isNotEmpty() && _selectedSource.value.isNotEmpty()) {
                search(lastKeyword)
            }
        }
    }

    private fun refreshScriptOptions(selectedId: String, info: LxBridge.ScriptInfo? = null) {
        _scriptOptions.value = LxBridge.SCRIPTS.map { def ->
            val label = if (def.id == selectedId && info != null) {
                info.name + " " + info.version
            } else {
                def.fallbackName
            }
            ScriptOption(id = def.id, label = label, selected = def.id == selectedId)
        }
    }

    private fun savedScriptId(): String {
        val saved: String? = KV.get(KV_SCRIPT_ID)
        return saved?.takeIf { id -> LxBridge.SCRIPTS.any { it.id == id } }
            ?: LxBridge.SCRIPTS.first().id
    }

    // ------------------------------------------------------------- search

    fun selectSource(code: String) {
        if (code == _selectedSource.value) return
        _selectedSource.value = code
        if (lastKeyword.isNotEmpty()) search(lastKeyword)
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

    // ------------------------------------------------------------- misc

    private fun str(resId: Int, vararg args: Any): String {
        val context = AppContextHolder.context() ?: return ""
        return LanguageManager.localized(context).getString(resId, *args)
    }

    companion object {
        private const val KV_SCRIPT_ID = "lx_music_script_id"
        private val SOURCE_ORDER = listOf("kg", "kw", "tx", "wy", "mg")
    }
}
