@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.MusicChart
import com.github.avbox.osc.music.MusicServiceConnection
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.music.NowPlayingState
import com.github.avbox.osc.music.PyMusicBridge
import com.github.avbox.osc.music.PyMusicSources
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.music.LxLoopMode
import com.github.avbox.osc.ui.music.LxMusicPlayerActivity
import com.github.avbox.osc.ui.music.MusicAppearance
import com.github.avbox.osc.ui.music.MusicAppearanceSheet
import com.github.avbox.osc.ui.music.MusicBackground
import com.github.avbox.osc.util.KV
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.launch

@Composable
fun MusicPage(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: MusicViewModel = viewModel()
    val context = LocalContext.current

    val hotWords by vm.hotWords.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val searched by vm.searched.collectAsStateWithLifecycle()
    val scriptOptions by vm.scriptOptions.collectAsStateWithLifecycle()
    val scriptBusy by vm.scriptBusy.collectAsStateWithLifecycle()
    val noSource by vm.noSource.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val chartSongs by vm.chartSongs.collectAsStateWithLifecycle()
    val selectedChart by vm.selectedChart.collectAsStateWithLifecycle()
    val chartsLoading by vm.chartsLoading.collectAsStateWithLifecycle()
    val chartSongsLoading by vm.chartSongsLoading.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val listOverlay by vm.listOverlay.collectAsStateWithLifecycle()
    val playHistory by vm.playHistory.collectAsStateWithLifecycle()
    val pyActive by vm.pyActive.collectAsStateWithLifecycle()
    val pyClasses by vm.pyClasses.collectAsStateWithLifecycle()
    val selectedPyClass by vm.selectedPyClass.collectAsStateWithLifecycle()
    val pyItems by vm.pyItems.collectAsStateWithLifecycle()
    val pyTileGrid by vm.pyTileGrid.collectAsStateWithLifecycle()
    val pyLoading by vm.pyLoading.collectAsStateWithLifecycle()
    val pyLoadingMore by vm.pyLoadingMore.collectAsStateWithLifecycle()
    val pySearchActive by vm.pySearchActive.collectAsStateWithLifecycle()
    val pyDetailLoading by vm.pyDetailLoading.collectAsStateWithLifecycle()
    val pendingPyPlay by vm.pendingPyPlay.collectAsStateWithLifecycle()
    val episodePage by vm.episodePage.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var scriptDialogOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }

    // Spider category list/grid scroll states are hoisted here,
    // above the content branch chain: the episode page and the
    // overlays replace PySpiderContent in composition, and states
    // remembered inside it would be dropped — returning from an
    // episode list must restore the category list exactly where
    // the user left it (v1.18.1).
    val pyListState = rememberLazyListState()
    val pyGridState = rememberLazyGridState()

    // After a pick, the import runs in the ViewModel and its result
    // toasts arrive through the page-level messages flow below, so
    // the source dialog closes right away and the user lands back on
    // the music page instead of staring at the dialog while the new
    // source loads (v1.17.0). Cancelling the picker (null uri) keeps
    // the dialog open. One launcher serves both source kinds since
    // v1.18.0: the ViewModel's import dispatch decides JS vs py from
    // the file name / content and routes to the matching import.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importSource(uri)
            scriptDialogOpen = false
        }
    }

    // Spider-mode play requests (single-episode vods and picked episodes)
    // open the shared player activity with the queue the ViewModel built.
    LaunchedEffect(pendingPyPlay) {
        val request = pendingPyPlay ?: return@LaunchedEffect
        LxMusicPlayerActivity.start(
            context,
            MusicTrack.listToJson(request.queue),
            request.index,
        )
        vm.consumePyPlay()
    }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(vm) {
        vm.playRequests.collect { (queue, index) ->
            LxMusicPlayerActivity.start(
                context,
                MusicTrack.listToJson(queue),
                index,
            )
        }
    }

    // One search trigger for the IME action and voice results:
    // spider mode searches the spider; native mode switches to the
    // search content first (v1.19.1's model).
    fun submitSearch() {
        if (pyActive) {
            vm.pySearch(query)
        } else {
            if (mode != MusicMode.SEARCH) {
                vm.selectMode(MusicMode.SEARCH)
            }
            vm.search(query)
        }
    }

    // Voice results skip the result list (v1.21.0, Torres: 识别好
    // 直接播放）: the first hit plays straight away in both modes.
    fun submitVoiceSearch() {
        if (pyActive) {
            vm.voicePySearch(query)
        } else {
            if (mode != MusicMode.SEARCH) {
                vm.selectMode(MusicMode.SEARCH)
            }
            vm.voiceSearchAndPlay(query)
        }
    }

    // ------------------------------------------------------ voice (v1.20.0)
    // The search field's leading magnifier became a microphone
    // (Torres): tapping it dictates the query through the system's
    // speech recogniser, partial results stream into the field and
    // the final result submits the search. Devices without a
    // recognition service fall back to the system speech UI, and
    // devices with neither get a toast — never a dead button.

    var voiceListening by remember { mutableStateOf(false) }
    val voiceErrorText = stringResource(R.string.music_voice_error)
    val voiceUnavailableText = stringResource(R.string.music_voice_unavailable)
    val micPermissionText = stringResource(R.string.music_mic_permission_needed)

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(speechRecognizer) {
        val recognizer = speechRecognizer
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                voiceListening = false
                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    Toast.makeText(context, voiceErrorText, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                voiceListening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    query = text
                    submitVoiceSearch()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    query = text
                }
            }
        })
        onDispose { recognizer?.destroy() }
    }

    val voiceIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            query = text
            submitVoiceSearch()
        }
    }

    fun startVoiceSearch() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            try {
                voiceIntentLauncher.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    },
                )
            } catch (t: Throwable) {
                Toast.makeText(context, voiceUnavailableText, Toast.LENGTH_SHORT).show()
            }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            voiceListening = true
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            voiceListening = false
            Toast.makeText(context, voiceErrorText, Toast.LENGTH_SHORT).show()
        }
    }

    // One microphone-permission gate shared by voice search and the
    // radar: the pending action runs when the grant comes back.
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingMicAction
        pendingMicAction = null
        if (granted) {
            action?.invoke()
        } else {
            Toast.makeText(context, micPermissionText, Toast.LENGTH_SHORT).show()
        }
    }

    fun withMicPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Kuwo's chart APIs reject unsigned requests, so its catalog is
    // always empty: in charts mode the 酷我 chip is hidden below (its
    // search still works in search mode). If 酷我 was the selected
    // platform when charts mode is entered, fall back to the first
    // remaining platform through the normal select path, which also
    // reloads the catalog (v1.17.2).
    LaunchedEffect(mode, sources, selectedSource) {
        if (mode == MusicMode.CHARTS && selectedSource == "kw") {
            sources.firstOrNull { it.code != "kw" }?.let { vm.selectSource(it.code) }
        }
    }

    // Since v1.19.1 there is no search / charts tab row: the charts
    // are the default content, a submitted search switches to the
    // search mode (see the field's onSearch below), and emptying
    // the query again returns to the charts — the mode is driven
    // purely by search activity. Spider mode has its own search
    // state (pySearchActive) and is left untouched here.
    LaunchedEffect(query, mode, pyActive) {
        if (!pyActive && mode == MusicMode.SEARCH && query.isBlank()) {
            vm.selectMode(MusicMode.CHARTS)
        }
    }

    // Warm the shared playback-service binding while the user is
    // still browsing (v1.15.1): the player page then adopts an
    // already-bound service instead of cold-starting it on tap,
    // which timed out on the test device and silently degraded
    // every session to in-page playback.
    LaunchedEffect(Unit) {
        MusicServiceConnection.ensureConnecting(context)
    }

    // Mini player bar: the shared now-playing record plus, for
    // service-backed records, the process-wide binding from
    // MusicServiceConnection (v1.16.0: a same-process LocalBinder —
    // the bar drives the bound service's player directly). The
    // binding is best-effort — any failure just keeps the bar
    // hidden and never affects the page itself.
    val nowPlaying by NowPlayingState.info.collectAsStateWithLifecycle()
    var serviceBound by remember { mutableStateOf(false) }
    val barScope = rememberCoroutineScope()
    LaunchedEffect(nowPlaying?.serviceBacked) {
        if (nowPlaying?.serviceBacked != true) {
            serviceBound = false
            return@LaunchedEffect
        }
        serviceBound = MusicServiceConnection.connectedService() != null
        if (!serviceBound) {
            MusicServiceConnection.ensureConnecting(context)
            serviceBound = MusicServiceConnection.awaitConnected(10_000L) != null
        }
    }
    val miniBarVisible = nowPlaying != null &&
        (nowPlaying?.serviceBacked != true || serviceBound)

    // A music sub-page (chart detail, favourites, history, py
    // episode page) takes over the whole screen (v1.19.0): the app
    // shell hides its navigation bars off the same condition (it
    // collects MusicViewModel.subPageOpen), and inside the page
    // the sub-page renders at the root above the main content
    // while the mini bar and the multi-function ball stay hidden.
    val subPageOpen = selectedChart != null || listOverlay != null || episodePage != null
    val showMiniBar = miniBarVisible && !subPageOpen

    Box(modifier = Modifier.fillMaxSize()) {
        // Custom background (when set) spans the whole page; the content
        // box below keeps its original insets.
        if (MusicAppearance.bgPath.isNotBlank()) {
            MusicBackground(fallbackModel = null)
        }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            // Top row: the vinyl source button, the search field and
            // the multi-function ball's seat. The field lives here in
            // both native and spider mode (it used to sit inside the
            // mode contents below); its query state, IME search action
            // and pill styling are unchanged, only the search target
            // differs (native search switches to the search mode first
            // so the results stay visible). Appearance / favourites /
            // history sat in this row as a button in v1.18.0, left for
            // the multi-function ball in v1.18.2, and since v1.18.4
            // the ball is back at this row's right end — drawn by the
            // page-root overlay (MusicMultiFab below) so its fan and
            // scrim can cover the page while the ball stays on top;
            // the row itself only reserves the ball's spot with the
            // 48dp spacer after the search field, which also makes
            // the row 48dp tall, exactly what the overlay's anchor
            // math assumes.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VinylSourceButton(
                    playing = nowPlaying?.isPlaying == true,
                    onClick = { scriptDialogOpen = true },
                    // Long-press refreshes the current source in
                    // place (v1.19.2): py sources re-fetch their
                    // home/category, JS sources re-init.
                    onLongClick = { vm.refreshCurrentSource() },
                )
                MusicSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { submitSearch() },
                    onVoiceClick = { withMicPermission { startVoiceSearch() } },
                    voiceListening = voiceListening,
                    modifier = Modifier.weight(1f),
                )
                // The multi-function ball's seat: a 48dp spacer at
                // the row's right end (8dp spacing like the other
                // gaps). MusicMultiFab draws the ball itself over
                // exactly this spot.
                Spacer(Modifier.size(48.dp))
            }

            // Second row: the platform chips act as the resource-site
            // switcher and span the full width; the source-script entry
            // is the vinyl button in the top row (the floating button
            // was removed in v1.15.0).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pyActive) {
                        // Spider mode: the first row switches the spider's
                        // own homeContent categories instead of platforms.
                        items(pyClasses, key = { it.id }) { pyClass ->
                            FilterChip(
                                selected = pyClass.id == selectedPyClass && !pySearchActive,
                                onClick = { vm.selectPyClass(pyClass.id) },
                                label = { Text(pyClass.name) },
                            )
                        }
                    } else {
                        // Charts mode hides the 酷我 chip (see the
                        // fallback effect above); search mode keeps it.
                        val chipSources = if (mode == MusicMode.CHARTS) {
                            sources.filter { it.code != "kw" }
                        } else {
                            sources
                        }
                        items(chipSources, key = { it.code }) { source ->
                            FilterChip(
                                selected = source.code == selectedSource,
                                onClick = { vm.selectSource(source.code) },
                                label = { Text(sourceLabel(source.code)) },
                            )
                        }
                    }
                }
            }

            // No third row since v1.19.1: the search / charts tab
            // switch is gone (favourites already left it in v1.19.0
            // for the multi-function ball). The charts below are the
            // default content; a submitted search replaces them with
            // the results, and clearing the query or pressing back
            // returns here — see the effect above and the BackHandler
            // below. Spider mode never had the row and is unchanged.

            BackHandler(enabled = mode == MusicMode.CHARTS && selectedChart != null) {
                vm.closeChart()
            }

            BackHandler(enabled = listOverlay != null) {
                vm.closeOverlay()
            }

            BackHandler(enabled = episodePage != null && listOverlay == null) {
                vm.closeEpisodePage()
            }

            // Search results are a detour off the charts (v1.19.1):
            // system back returns to them. The conditions keep this
            // behind the sub-page handlers above — an open chart,
            // overlay or episode page consumes back first.
            BackHandler(
                enabled = !pyActive && mode == MusicMode.SEARCH &&
                    selectedChart == null && listOverlay == null && episodePage == null,
            ) {
                vm.selectMode(MusicMode.CHARTS)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showMiniBar) 64.dp else 0.dp),
            ) {
                if (noSource) {
                    // No user source imported yet: the bridge never
                    // inits and every list call safely returns empty,
                    // so all the page needs is this hint.
                    Text(
                        text = stringResource(R.string.music_no_source),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 32.dp),
                    )
                } else if (pyActive) {
                    PySpiderContent(
                        items = pyItems,
                        loading = pyLoading,
                        loadingMore = pyLoadingMore,
                        listState = pyListState,
                        gridState = pyGridState,
                        searchActive = pySearchActive,
                        tileGrid = pyTileGrid,
                        onToggleLayout = { vm.togglePyTileGrid() },
                        singerGrid = !pySearchActive &&
                            pyClasses.firstOrNull { it.id == selectedPyClass }
                                ?.name?.let(::isSingerClassName) == true,
                        onClearSearch = { vm.pyClearSearch() },
                        onLoadMore = { vm.pyLoadMore() },
                        onItemClick = { track -> vm.onPyTrackClick(track) },
                        onPlayAll = if (
                            (pySearchActive ||
                                pyClasses.firstOrNull { it.id == selectedPyClass }
                                    ?.name?.let(::isSingerClassName) != true) &&
                            pyItems.any { !it.songmid.startsWith("artist:") }
                        ) {
                            { vm.pyPlayAll() }
                        } else {
                            null
                        },
                    )
                    if (pyDetailLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                } else if (mode == MusicMode.CHARTS) {
                    ChartsContent(
                        charts = charts,
                        chartsLoading = chartsLoading,
                        initializing = scriptBusy || pyLoading,
                        onOpenChart = { vm.openChart(it) },
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                                loading -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }

                                results.isNotEmpty() -> {
                                    // Bottom content padding (also on the
                                    // other lists below and the py /
                                    // favourites / chart-songs lists): the
                                    // page root already reserves the
                                    // floating nav bar's full occlusion
                                    // (76dp + nav inset) through
                                    // contentPadding, so without this the
                                    // last row rests flush against the
                                    // bar's top edge — 16dp matches the
                                    // charts grid's existing clearance.
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp),
                                    ) {
                                        itemsIndexed(
                                            results,
                                            key = { index, _ -> index },
                                        ) { index, track ->
                                            if (index >= results.size - 5) {
                                                LaunchedEffect(index) { vm.loadMore() }
                                            }
                                            MusicTrackRow(
                                                track = track,
                                                onClick = {
                                                    LxMusicPlayerActivity.start(
                                                        context,
                                                        MusicTrack.listToJson(results),
                                                        index,
                                                    )
                                                },
                                            )
                                        }
                                        if (loadingMore) {
                                            item {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                searched -> {
                                    Text(
                                        text = stringResource(R.string.music_empty_result),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }

                                else -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        if (scriptBusy) {
                                            Text(
                                                text = stringResource(R.string.music_script_loading),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 24.dp),
                                            )
                                        }
                                        if (hotWords.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.music_hot_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                hotWords.forEach { word ->
                                                    FilterChip(
                                                        selected = false,
                                                        onClick = {
                                                            query = word
                                                            vm.search(word)
                                                        },
                                                        label = { Text(word) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
        if (showMiniBar) {
            val np = nowPlaying
            if (np != null) {
                MiniPlayerBar(
                    info = np,
                    controlsEnabled = if (np.serviceBacked) {
                        serviceBound
                    } else {
                        NowPlayingState.localControls != null
                    },
                    onBodyClick = { LxMusicPlayerActivity.startAttached(context) },
                    onPlayPause = {
                        try {
                            if (np.serviceBacked) {
                                val player = MusicServiceConnection.connectedPlayer()
                                if (player != null) {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            } else {
                                NowPlayingState.localControls?.playPause()
                            }
                        } catch (t: Throwable) {
                            // a dead session must not break the page
                        }
                    },
                    onNext = {
                        if (np.serviceBacked) {
                            val player = MusicServiceConnection.connectedPlayer()
                            if (player != null && np.canNext) {
                                barScope.launch {
                                    val queue = MusicTrack.listFromJson(np.queueJson)
                                    val next = barNextIndex(queue.size, np.queueIndex)
                                    if (next != null &&
                                        !playBarTrack(context, player, queue, next)
                                    ) {
                                        Toast.makeText(
                                            context,
                                            R.string.music_resolve_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        } else {
                            try {
                                NowPlayingState.localControls?.next()
                            } catch (t: Throwable) {
                                // a dead session must not break the page
                            }
                        }
                    },
                    onPrev = {
                        if (np.serviceBacked) {
                            val player = MusicServiceConnection.connectedPlayer()
                            if (player != null && np.canPrev) {
                                barScope.launch {
                                    val queue = MusicTrack.listFromJson(np.queueJson)
                                    val prev = barPrevIndex(queue.size, np.queueIndex)
                                    if (prev != null &&
                                        !playBarTrack(context, player, queue, prev)
                                    ) {
                                        Toast.makeText(
                                            context,
                                            R.string.music_resolve_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        } else {
                            try {
                                NowPlayingState.localControls?.prev()
                            } catch (t: Throwable) {
                                // a dead session must not break the page
                            }
                        }
                    },
                    onClose = {
                        if (np.serviceBacked) {
                            try {
                                MusicServiceConnection.connectedPlayer()?.stop()
                            } catch (t: Throwable) {
                                // already gone
                            }
                        } else {
                            try {
                                NowPlayingState.localControls?.stop()
                            } catch (t: Throwable) {
                                // already gone
                            }
                        }
                        NowPlayingState.clear()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                )
            }
        }

        // Multi-function ball overlay (v1.18.2; fixed top-right
        // fan since v1.18.4): the appearance / favourites / history
        // entry. It is the last child of this box, so its scrim,
        // fan items and ball render above the content and the mini
        // bar (sheets and dialogs live in their own windows, still
        // above it), in both native and spider mode; the ball
        // itself lands on the 48dp spacer reserved for it in the
        // top row. Hidden while a sub-page is open (v1.19.0).
        if (!subPageOpen) {
            MusicMultiFab(
                onAppearance = { appearanceOpen = true },
                onFavorites = { vm.openFavorites() },
                onHistory = { vm.openHistory() },
            )
        }
    }

        // True full-screen sub-pages (v1.19.0): the chart detail,
        // favourites, history and the py episode page render here,
        // at the page root above the main content, on an opaque
        // background with their own system-bar insets. The shell
        // hides its navigation bars while any of them is open (it
        // collects MusicViewModel.subPageOpen), so nothing of the
        // main interface — bottom nav, search row,
        // source chips, mini bar, fan ball — shows around them:
        // only the page's own compact header and its list remain.
        // Song taps still open the player activity, which lives in
        // its own window above this whole composition. The main
        // content stays composed underneath, so its scroll
        // positions (including the hoisted spider states) survive
        // the return untouched.
        if (subPageOpen) {
            val episodePageState = episodePage
            val chartState = selectedChart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp),
            ) {
                when {
                    listOverlay == MusicListOverlay.FAVORITES -> {
                        MusicOverlayList(
                            title = stringResource(R.string.music_favorites_title),
                            tracks = favorites,
                            emptyText = stringResource(R.string.music_favorites_empty),
                            onClose = { vm.closeOverlay() },
                            onClear = null,
                            onPlay = { index ->
                                LxMusicPlayerActivity.start(
                                    context,
                                    MusicTrack.listToJson(favorites),
                                    index,
                                )
                            },
                        )
                    }

                    listOverlay == MusicListOverlay.HISTORY -> {
                        MusicOverlayList(
                            title = stringResource(R.string.music_menu_history),
                            tracks = playHistory,
                            emptyText = stringResource(R.string.music_history_empty),
                            onClose = { vm.closeOverlay() },
                            onClear = { vm.clearHistory() },
                            onPlay = { index ->
                                LxMusicPlayerActivity.start(
                                    context,
                                    MusicTrack.listToJson(playHistory),
                                    index,
                                )
                            },
                        )
                    }

                    episodePageState != null -> {
                        // A multi-episode spider vod (a singer, an
                        // album, a playlist): the rows play through
                        // the same ViewModel call the old picker
                        // dialog used, and closing the page returns
                        // to the category list exactly as it was
                        // left (its items live in the ViewModel,
                        // nothing reloads).
                        PyEpisodeListContent(
                            title = episodePageState.title,
                            episodes = episodePageState.episodes,
                            onClose = { vm.closeEpisodePage() },
                            onPlay = { index -> vm.playEpisode(index) },
                        )
                    }

                    chartState != null -> {
                        ChartDetailContent(
                            chart = chartState,
                            songs = chartSongs,
                            songsLoading = chartSongsLoading,
                            initializing = scriptBusy || pyLoading,
                            onClose = { vm.closeChart() },
                            onPlaySong = { index ->
                                LxMusicPlayerActivity.start(
                                    context,
                                    MusicTrack.listToJson(chartSongs),
                                    index,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (scriptDialogOpen) {
        AVBoxAlertDialog(
            onDismissRequest = { scriptDialogOpen = false },
            title = { Text(stringResource(R.string.music_source_switch)) },
            text = {
                Column {
                    scriptOptions.filter { !it.py }.forEach { option ->
                        ScriptOptionRow(
                            option = option,
                            onSelect = {
                                scriptDialogOpen = false
                                vm.switchScript(option.id)
                            },
                            onDelete = { vm.deleteScript(option.id) },
                        )
                    }
                    Text(
                        text = stringResource(R.string.music_py_section),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    scriptOptions.filter { it.py }.forEach { option ->
                        ScriptOptionRow(
                            option = option,
                            onSelect = {
                                scriptDialogOpen = false
                                vm.switchScript(option.id)
                            },
                            onDelete = { vm.deletePyScript(option.id) },
                        )
                    }
                    TextButton(
                        onClick = {
                            // One entry for both kinds (v1.18.0): .js
                            // sources and .py spiders report all kinds of
                            // mime types from file managers, so accept
                            // the union of both old lists and fall back
                            // to */*; the ViewModel dispatch sorts out
                            // which kind the picked file actually is.
                            importLauncher.launch(
                                arrayOf(
                                    "text/javascript",
                                    "application/javascript",
                                    "application/x-javascript",
                                    "text/x-python",
                                    "text/plain",
                                    "application/octet-stream",
                                    "text/*",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.music_import_entry))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { scriptDialogOpen = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (appearanceOpen) {
        MusicAppearanceSheet(
            onDismissRequest = { appearanceOpen = false },
            showEqSection = false,
        )
    }

}

/**
 * The music page's multi-function entry: a 48dp ball in the style
 * of the reference app Torres screenshotted, carrying the v1.18.0
 * actions — appearance, favourites, play history. Only the entry
 * point and its expansion changed across versions: v1.18.0 top-row
 * button + bottom-sheet menu, v1.18.2 a draggable ball, v1.18.3
 * the ball fixed at the bottom-right, and since v1.18.4 the ball
 * sits back at the top row's right end (Torres: 「固定在右上角」)
 * and expands into a fan (「点一下扇形打开，再点一下关闭」).
 *
 * The ball is drawn here, in the page-root overlay, not in the
 * top row itself — the row only reserves a 48dp spacer in the
 * ball's spot. That gives the whole widget one composition order:
 * transparent scrim first, fan items above it, the ball last
 * (topmost). While the fan is open the scrim therefore catches a
 * collapse tap anywhere on the page yet never covers the ball,
 * which stays tappable to close. Anchor math, in this box's
 * coordinates (the box is the page's padded content area, and the
 * overlay shares that padding): the top row starts 12dp below
 * the box's top (the Column's spacer), is 48dp tall because of
 * the reserved spacer, and spans the box's full width, so the
 * ball's centre sits 24dp in from the right edge and 36dp down
 * from the top. A TopEnd ball with a 12dp top padding in this
 * box lands exactly on the spacer.
 *
 * The fan items are children of this full-size box, never of a
 * ball-sized anchor: a fixed-size parent caps its children's
 * measure constraints at its own size, which is what squeezed
 * v1.18.3's label pills into circles with the label text wrapped
 * inside them and the icons nowhere to be seen. Each item is
 * placed by TopEnd alignment plus an offset derived from its
 * circle-centre target (see the fan table in the body). Tapping
 * the scrim or the ball collapses the fan; tapping an item
 * collapses first, then invokes its action.
 */
@Composable
private fun MusicMultiFab(
    onAppearance: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val moreDescription = stringResource(R.string.music_menu_more)

    Box(modifier = Modifier.fillMaxSize()) {
        if (expanded) {
            // Transparent scrim: catches the collapse tap anywhere
            // on the page without dimming it and without a ripple.
            // Drawn first, so it stays under the fan items and
            // under the ball.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = false },
            )

            // The fan: three items arc down-left from the ball —
            // 外观 directly left of it, 我的收藏 diagonally
            // down-left, 播放记录 directly below (the v1.18.0
            // menu order along the arc). dx/dy are item-circle
            // centres relative to the ball's centre. Under TopEnd
            // alignment an item row's right edge sits on this
            // box's right edge with the 44dp circle last in the
            // row, so the circle's centre starts 22dp in from the
            // edge and 22dp below the box's top; reaching a centre
            // at (ball + dx, ball + dy) therefore takes an offset
            // of (dx - 2dp, dy + 14dp) from the ball centre at
            // (24dp from the edge, 36dp from the top). The arc
            // radius is ~95dp (v1.18.5 widened it from ~64dp,
            // whose items and label chips crowded into each
            // other); 播放记录 sits 10dp past the radius so its
            // label chip clears the bottom of the 我的收藏
            // circle.
            val fanItems = listOf(
                FanItem(
                    iconRes = R.drawable.ic_settings_theme,
                    labelRes = R.string.music_appearance,
                    action = onAppearance,
                    dx = -95,
                    dy = 0,
                ),
                FanItem(
                    iconRes = R.drawable.ic_favorite,
                    labelRes = R.string.music_favorites_title,
                    action = onFavorites,
                    dx = -67,
                    dy = 67,
                ),
                FanItem(
                    iconRes = R.drawable.ic_tab_history,
                    labelRes = R.string.music_menu_history,
                    action = onHistory,
                    dx = 0,
                    dy = 105,
                ),
            )
            fanItems.forEach { item ->
                MusicMultiFabItem(
                    iconRes = item.iconRes,
                    label = stringResource(item.labelRes),
                    onClick = {
                        expanded = false
                        item.action()
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (item.dx - 2).dp, y = (item.dy + 14).dp),
                )
            }
        }

        // The ball: last child of this box, hence drawn above the
        // scrim, so a second tap on it closes the fan. TopEnd
        // alignment plus the 12dp top padding put it exactly over
        // the 48dp spacer the top row reserves for it.
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp)
                .size(48.dp)
                .semantics {
                    contentDescription = moreDescription
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_more),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * One fan position of the multi-function ball: an action plus its
 * circle centre's offset from the ball's centre, in dp (see the
 * fan table in [MusicMultiFab]).
 */
private data class FanItem(
    val iconRes: Int,
    val labelRes: Int,
    val action: () -> Unit,
    val dx: Int,
    val dy: Int,
)

/**
 * One fanned-out action of the multi-function ball: a small label
 * chip and a 44dp circle holding only the icon, side by side in a
 * single clickable row — chip and circle together are the tap
 * target, so tapping the label fires the action too. The circle
 * is a fixed-size Box containing nothing but the centred icon,
 * and the label is a sibling chip whose text is single-line with
 * wrapping disabled; neither can absorb the other, which is the
 * structural fix for v1.18.3's rendering bug (the label rendered
 * wrapped inside a squeezed pill, the icon invisible).
 */
@Composable
private fun MusicMultiFabItem(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicMultiFabLabel(label)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(6.dp, CircleShape)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The small dark label chip beside a fanned-out ball item: a
 * rounded rectangle (not a pill) whose text never wraps, so the
 * chip always reads as a label next to the item's icon circle.
 */
@Composable
private fun MusicMultiFabLabel(text: String) {
    Box(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A ball-opened full-screen list page (favourites / history): a
 * header row (title, optional 清空 action, close button) over the
 * shared track rows. Tapping a row plays it with the whole list as
 * the queue. Hosted at the music page root since v1.19.0 so it
 * covers the whole screen (see the sub-page block in [MusicPage]).
 */
@Composable
private fun MusicOverlayList(
    title: String,
    tracks: List<MusicTrack>,
    emptyText: String,
    onClose: () -> Unit,
    onClear: (() -> Unit)?,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (onClear != null) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.music_history_clear))
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_close),
                    contentDescription = stringResource(R.string.common_off),
                )
            }
        }
        if (tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(
                    tracks,
                    key = { index, _ -> index },
                ) { index, track ->
                    MusicTrackRow(
                        track = track,
                        onClick = { onPlay(index) },
                    )
                }
            }
        }
    }
}

/**
 * The 播放全部 pill at the top of a song list (v1.19.2, styled after
 * Torres's reference screenshot): a filled primary capsule with a
 * play glyph, left-aligned above the rows. Shared by the spider
 * song list, the spider episode page and the chart detail, which
 * used a plain icon-and-text row before.
 */
@Composable
private fun PlayAllButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.player_ic_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.music_play_all),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Full-screen episode list of a multi-episode spider vod (a singer,
 * an album, a playlist), rendered instead of the category content
 * (v1.18.1; this used to be a cramped picker dialog capped at
 * 420dp). The header is a back button, the vod's title and the
 * episode count; the rows keep the dialog's shape (episode name,
 * tap to play) and play through the same ViewModel call, so the
 * episode list becomes the queue exactly as it did from the dialog.
 */
@Composable
private fun PyEpisodeListContent(
    title: String,
    episodes: List<MusicTrack>,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.music_py_back_category),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.music_py_episode_count, episodes.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // 播放全部 (v1.19.2): with a singer/album vod this page IS
            // the song list, so it gets the same pill as the category
            // lists. A single song's quality lines (2 rows) are not
            // a list worth a play-all, so it only shows above that.
            if (episodes.size > 2) {
                item {
                    PlayAllButton(onClick = { onPlay(0) })
                }
            }
            itemsIndexed(
                episodes,
                key = { index, _ -> index },
            ) { index, episode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(index) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = episode.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** One selectable row in the source dialog (JS script or py spider). */
@Composable
private fun ScriptOptionRow(
    option: MusicViewModel.ScriptOption,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (option.selected) "● " else "○ ",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (option.user) {
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.music_source_delete),
                )
            }
        }
    }
}

/**
 * Spider-mode content: search runs from the page's top search row
 * through the spider's searchContent; the list below shows either the
 * selected category feed or the search results, paged by scrolling
 * like the native search list. Rows reuse [MusicTrackRow]: cover,
 * name and remarks (singer) come from the vod fields the bridge
 * mapped onto the track. A singer category (its name contains 歌手)
 * outside search renders as a round-avatar grid instead (v1.15.2,
 * [singerGrid]); taps still go through the same [onItemClick].
 */
/**
 * Whether a spider-mode class lists singers (rendered as the
 * round-avatar grid) rather than songs or playlists (list rows).
 * Beyond the obvious 歌手/艺人 names, some sources — the imported
 * Kuwo spider is the known case — group their singer classes by
 * region and gender instead （华语男 / 华语女 / 华语组合 / 日韩 …),
 * so those exact names and the region+group pattern count too.
 * Song and playlist classes （听歌模式 / 发现 / 新歌榜 / 电台 …)
 * match none of the three rules and stay list-rendered.
 */
private fun isSingerClassName(name: String): Boolean {
    if (name.contains("歌手") || name.contains("艺人")) return true
    val trimmed = name.trim()
    if (trimmed in SINGER_CLASS_EXACT_NAMES) return true
    return SINGER_CLASS_PATTERN.matches(trimmed)
}

private val SINGER_CLASS_EXACT_NAMES = setOf(
    "华语男", "华语女", "华语组合", "日韩", "欧美",
    "内地男", "内地女", "港台男", "港台女", "男歌手", "女歌手",
)

private val SINGER_CLASS_PATTERN =
    Regex("^(华语|内地|港台|大陆|日韩|日本|韩国|欧美)(男|女|组合|乐队|团体)$")

@Composable
private fun PySpiderContent(
    items: List<MusicTrack>,
    loading: Boolean,
    loadingMore: Boolean,
    listState: LazyListState,
    gridState: LazyGridState,
    searchActive: Boolean,
    singerGrid: Boolean,
    tileGrid: Boolean,
    onToggleLayout: () -> Unit,
    onClearSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (MusicTrack) -> Unit,
    onPlayAll: (() -> Unit)?,
) {
    // The scroll states are parameters, hoisted into MusicPage:
    // this content leaves composition while the episode page is
    // open, and internally remembered states would reset the
    // category list's scroll position on return (v1.18.1).
    // A reset load (category switch, re-tap of the current category,
    // a fresh search, leaving search results) raises `loading` first:
    // park the visible container back at the top so the refreshed
    // first page is seen from its start. Load-more runs on
    // `loadingMore` and never trips this.
    LaunchedEffect(loading) {
        if (loading) {
            if (singerGrid || tileGrid) {
                gridState.scrollToItem(0)
            } else {
                listState.scrollToItem(0)
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (searchActive || !singerGrid) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchActive) {
                    Text(
                        text = stringResource(R.string.music_tab_search),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (searchActive) {
                    TextButton(onClick = onClearSearch) {
                        Text(stringResource(R.string.music_py_back_category))
                    }
                }
                // Layout toggle (v1.26.0): rows ⇄ cover tiles.
                // Singer classes keep their avatar grid and never
                // see this button.
                if (!singerGrid) {
                    IconButton(
                        onClick = onToggleLayout,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (tileGrid) {
                                    R.drawable.ic_view_list
                                } else {
                                    R.drawable.ic_view_grid
                                },
                            ),
                            contentDescription = stringResource(
                                if (tileGrid) {
                                    R.string.music_layout_list
                                } else {
                                    R.string.music_layout_grid
                                },
                            ),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                items.isNotEmpty() -> {
                    if (singerGrid) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        ) {
                            itemsIndexed(
                                items,
                                key = { index, _ -> index },
                            ) { index, track ->
                                // Load-more only once the user has
                                // actually scrolled: without the gate,
                                // a short first page composes every
                                // tail item at once and chains page
                                // 2, 3, … spider calls on entry.
                                if (index >= items.size - 5 &&
                                    (gridState.firstVisibleItemIndex > 0 ||
                                        gridState.firstVisibleItemScrollOffset > 0)
                                ) {
                                    LaunchedEffect(index) { onLoadMore() }
                                }
                                PySingerCell(
                                    track = track,
                                    onClick = { onItemClick(track) },
                                )
                            }
                            if (loadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else if (tileGrid) {
                        // Cover tiles (v1.26.0): portrait 3:4 cells
                        // like the TVBox shells render vod posters —
                        // the layout Torres pointed at for audiobook
                        // sources. Same scroll-gated load-more and
                        // 播放全部 span as the other containers.
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                        ) {
                            if (onPlayAll != null) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    PlayAllButton(onClick = onPlayAll)
                                }
                            }
                            itemsIndexed(
                                items,
                                key = { index, _ -> index },
                            ) { index, track ->
                                if (index >= items.size - 5 &&
                                    (gridState.firstVisibleItemIndex > 0 ||
                                        gridState.firstVisibleItemScrollOffset > 0)
                                ) {
                                    LaunchedEffect(index) { onLoadMore() }
                                }
                                PyTileCell(
                                    track = track,
                                    onClick = { onItemClick(track) },
                                )
                            }
                            if (loadingMore) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            // 播放全部 pill (v1.19.2) above the song
                            // rows; the page passes null on singer
                            // grids and artist-only lists.
                            if (onPlayAll != null) {
                                item {
                                    PlayAllButton(onClick = onPlayAll)
                                }
                            }
                            itemsIndexed(
                                items,
                                key = { index, _ -> index },
                            ) { index, track ->
                                // Same scroll gate as the singer grid
                                // above: no chained page fetches while
                                // the user is still at the top.
                                if (index >= items.size - 5 &&
                                    (listState.firstVisibleItemIndex > 0 ||
                                        listState.firstVisibleItemScrollOffset > 0)
                                ) {
                                    LaunchedEffect(index) { onLoadMore() }
                                }
                                MusicTrackRow(
                                    track = track,
                                    onClick = { onItemClick(track) },
                                )
                            }
                            if (loadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.music_empty_result),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/**
 * One cell of the singer grid: a round cover (the same img field the
 * list rows use) over a centered one-line name. Taps go through the
 * same handler as the list rows, so singer vods follow the usual
 * episode-list / direct-play flow.
 */
@Composable
private fun PySingerCell(track: MusicTrack, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = track.img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape),
        )
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/**
 * One cell of the tile layout (v1.26.0): a portrait 3:4 cover over
 * the title (up to two lines) and the singer / author line. Taps go
 * through the same handler as the list rows.
 */
@Composable
private fun PyTileCell(track: MusicTrack, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = track.img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        )
        Text(
            text = track.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (track.singer.isNotEmpty()) {
            Text(
                text = track.singer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shared shell of the two round buttons flanking the search field: a
 * 40dp circle with a light outline and a translucent fill (the page's
 * usual 0.45-alpha surface over a custom background, a soft
 * surfaceVariant tint otherwise), content centered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopRoundButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
        shape = CircleShape,
        color = if (MusicAppearance.bgPath.isNotBlank()) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

/**
 * The source-script entry of the top row, inside the shared
 * round-button shell. On API 28+ it shows Torres's neon vinyl GIF
 * (shipped as the base64 text resource res/raw/vinyl_neon.b64 and
 * materialised to a cache file at runtime — see [VinylGifDisc]) as
 * an animated drawable filling the whole button; older systems
 * keep the self-drawn rotating disc ([VinylCanvasDisc]). Tapping
 * opens the same source dialog the removed floating button used to
 * open. [playing] only drives the fallback disc's speed now.
 */
@Composable
private fun VinylSourceButton(
    playing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val description = stringResource(R.string.music_source_switch)
    TopRoundButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            VinylGifDisc()
        } else {
            VinylCanvasDisc(playing = playing)
        }
    }
}

/**
 * The neon vinyl GIF as a circular, animated disc. It fills the
 * whole 40dp round button edge to edge (Torres: the earlier 30dp
 * rendering left a visible ring of shell background around it).
 * The GIF ships as a base64 text resource (res/raw/vinyl_neon.b64)
 * because this repo's push channel accepts text files only; on
 * first use [materialiseVinylGif] decodes it to a real file in the
 * cache dir and ImageDecoder decodes from there, so the system
 * hands back an AnimatedImageDrawable, which is started on
 * creation and stopped when the view leaves composition. Any
 * failure (resource, base64 or decode) leaves the round shell
 * empty rather than crashing the page.
 */
@Composable
private fun VinylGifDisc() {
    AndroidView(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape),
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    val source = ImageDecoder.createSource(
                        materialiseVinylGif(ctx),
                    )
                    val decoded = ImageDecoder.decodeDrawable(source)
                    setImageDrawable(decoded)
                    if (decoded is AnimatedImageDrawable) decoded.start()
                } catch (t: Throwable) {
                    // The shell still reads as the source entry; a
                    // missing animation must not break the page.
                }
            }
        },
        onRelease = { view ->
            (view.drawable as? AnimatedImageDrawable)?.stop()
        },
    )
}

/**
 * Decodes the base64 vinyl GIF resource (R.raw.vinyl_neon, the
 * res/raw/vinyl_neon.b64 text file) into cacheDir/vinyl_neon.gif
 * and returns that file. The file is (re)written only when it is
 * missing or its length differs from the decoded bytes, so repeat
 * calls cost just the resource read plus a stat.
 */
private fun materialiseVinylGif(context: Context): File {
    val text = context.resources
        .openRawResource(R.raw.vinyl_neon)
        .bufferedReader()
        .use { it.readText() }
    val bytes = Base64.decode(text, Base64.DEFAULT)
    val file = File(context.cacheDir, "vinyl_neon.gif")
    if (!file.exists() || file.length() != bytes.size.toLong()) {
        file.writeBytes(bytes)
    }
    return file
}

/**
 * Fallback for API < 28 (no ImageDecoder): a self-drawn vinyl record
 * (black disc, groove rings, a light arc, colored center label with
 * an off-center dot, spindle hole). The disc always turns: at one
 * turn per 20 seconds while idle, and one turn per 6 seconds while
 * the shared now-playing record reports playback. The angle is
 * accumulated frame by frame and the current rate eases toward its
 * target over roughly 0.6 seconds, so toggling play state never
 * makes the disc jump or snap to a new speed.
 */
@Composable
private fun VinylCanvasDisc(playing: Boolean) {
    var rotation by remember { mutableStateOf(0f) }
    val playingNow = rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        // Degrees per millisecond: one turn per 20s while idle, one
        // turn per 6s while playing. The current rate moves toward the
        // target at a fixed slope (full swing in ~600ms), so speeding
        // up and slowing down both feel like a record changing speed.
        val idleRate = 360f / 20_000f
        val playingRate = 360f / 6_000f
        val rateSlopePerMs = (playingRate - idleRate) / 600f
        var rate = idleRate
        var lastFrameMs = -1L
        while (true) {
            withFrameMillis { frameMs ->
                if (lastFrameMs >= 0L) {
                    val dtMs = (frameMs - lastFrameMs).toFloat()
                    val target = if (playingNow.value) playingRate else idleRate
                    val maxStep = rateSlopePerMs * dtMs
                    rate += (target - rate).coerceIn(-maxStep, maxStep)
                    rotation = (rotation + dtMs * rate) % 360f
                }
                lastFrameMs = frameMs
            }
        }
    }
    val labelColor = MaterialTheme.colorScheme.primary
    val holeColor = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = Modifier
            .size(30.dp)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val c = center
        val r = size.minDimension / 2f
        // Black disc.
        drawCircle(color = Color(0xFF17181C), radius = r)
        // Groove rings.
        for (fraction in listOf(0.84f, 0.70f, 0.57f)) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = r * fraction,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        // A light arc so the spin stays visible.
        drawArc(
            color = Color.White.copy(alpha = 0.35f),
            startAngle = -40f,
            sweepAngle = 70f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx()),
            topLeft = Offset(c.x - r * 0.84f, c.y - r * 0.84f),
            size = Size(r * 1.68f, r * 1.68f),
        )
        // Center label, an off-center dot and the spindle hole.
        drawCircle(color = labelColor, radius = r * 0.38f)
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r * 0.07f,
            center = Offset(c.x + r * 0.20f, c.y - r * 0.12f),
        )
        drawCircle(color = holeColor, radius = r * 0.09f)
    }
}

/**
 * Self-drawn pill search field. The previous OutlinedTextField with a fixed
 * 44dp height clipped its text vertically on real devices, so the pill is a
 * bordered Surface whose content is centered by the Row instead.
 */
@Composable
private fun MusicSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onVoiceClick: () -> Unit,
    voiceListening: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        shape = CircleShape,
        color = if (MusicAppearance.bgPath.isNotBlank()) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The leading magnifier became a microphone (v1.20.0,
            // Torres): tapping it dictates the search instead of
            // typing. While the recogniser listens it takes the
            // primary colour so the state is visible at a glance.
            IconButton(
                onClick = onVoiceClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.music_voice_search),
                    modifier = Modifier.size(19.dp),
                    tint = if (voiceListening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.width(4.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.music_search_hint),
                                style = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp,
                                ),
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun MusicTrackRow(track: MusicTrack, onClick: () -> Unit) {
    // With a custom page background the row sits on a translucent surface
    // so the artwork shows through; without one the layout is unchanged.
    if (MusicAppearance.bgPath.isNotBlank()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        ) {
            TrackRowContent(
                track = track,
                onClick = onClick,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    } else {
        TrackRowContent(track = track, onClick = onClick)
    }
}

@Composable
private fun TrackRowContent(
    track: MusicTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.img,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.singer, track.albumName)
                    .filter { it.isNotEmpty() }
                    .joinToString(" - "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.intervalText.isNotEmpty()) {
            Text(
                text = track.intervalText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shown in place of an empty-list state while the selected source is
 * still initialising or activating: a spinner plus the script-loading
 * text, so a cold start never reads as "no content" (v1.17.0).
 */
@Composable
private fun MusicInitializingPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.music_script_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Full-screen chart detail (v1.19.0): a compact header (back +
 * chart name) over the song list, hosted at the music page root
 * so it covers the whole screen. The list's first row is
 * 播放全部， which plays the chart from the top through the same
 * call as tapping the first song.
 */
@Composable
private fun ChartDetailContent(
    chart: MusicChart,
    songs: List<MusicTrack>,
    songsLoading: Boolean,
    initializing: Boolean,
    onClose: () -> Unit,
    onPlaySong: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Text(
                text = chart.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                songsLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                songs.isEmpty() -> {
                    if (initializing) {
                        MusicInitializingPlaceholder(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.music_empty_result),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item {
                            PlayAllButton(onClick = { onPlaySong(0) })
                        }
                        itemsIndexed(
                            songs,
                            key = { index, _ -> index },
                        ) { index, track ->
                            MusicTrackRow(
                                track = track,
                                onClick = { onPlaySong(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartsContent(
    charts: List<MusicChart>,
    chartsLoading: Boolean,
    initializing: Boolean,
    onOpenChart: (MusicChart) -> Unit,
) {
    when {
        chartsLoading -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        charts.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize()) {
                if (initializing) {
                    // A source is still initialising/activating (the
                    // remembered py source cold-starting, a JS script
                    // init): the catalog is not known to be empty yet,
                    // so show the loading state instead of 暂无榜单
                    // (v1.17.0).
                    MusicInitializingPlaceholder(
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.music_charts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                items(charts, key = { it.id }) { chart ->
                    MusicChartCard(chart = chart, onClick = { onOpenChart(chart) })
                }
            }
        }
    }
}

@Composable
private fun MusicChartCard(chart: MusicChart, onClick: () -> Unit) {
    // With a custom page background the card gets a translucent surface so
    // the artwork shows through; without one it stays fully transparent.
    val hasBg = MusicAppearance.bgPath.isNotBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (hasBg) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
    ) {
        // Uniform card height (v1.17.2): the name block always reserves
        // exactly two lines of bodyMedium and the update-tip block
        // always reserves exactly one line of bodySmall — even when the
        // tip is empty — so every card in the 2-column grid measures
        // the same height and no row measurement can clip a card's
        // last text line mid-glyph.
        val density = LocalDensity.current
        val nameBlockHeight = with(density) {
            val lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            if (lineHeight.isSpecified) lineHeight.toDp() * 2 else 40.dp
        }
        val tipBlockHeight = with(density) {
            val lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            if (lineHeight.isSpecified) lineHeight.toDp() else 16.dp
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = chart.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Text(
                text = chart.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(
                        top = 6.dp,
                        start = if (hasBg) 8.dp else 0.dp,
                        end = if (hasBg) 8.dp else 0.dp,
                    )
                    .height(nameBlockHeight),
            )
            Text(
                text = chart.updateTip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(
                        start = if (hasBg) 8.dp else 0.dp,
                        end = if (hasBg) 8.dp else 0.dp,
                    )
                    .height(tipBlockHeight),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}


@Composable
private fun sourceLabel(code: String): String {
    return when (code) {
        "kg" -> stringResource(R.string.music_source_kg)
        "kw" -> stringResource(R.string.music_source_kw)
        "tx" -> stringResource(R.string.music_source_tx)
        "wy" -> stringResource(R.string.music_source_wy)
        "mg" -> stringResource(R.string.music_source_mg)
        else -> code
    }
}

/**
 * The music page's mini player bar: cover, title, singer and
 * play/pause + next + close for the shared now-playing record. Pure
 * rendering — every action is a callback wired up in MusicPage.
 */
@Composable
private fun MiniPlayerBar(
    info: NowPlayingState.Info,
    controlsEnabled: Boolean,
    onBodyClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onBodyClick() },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = info.img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = info.singer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onPrev,
                enabled = controlsEnabled && info.canPrev,
            ) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_prev),
                    contentDescription = stringResource(R.string.music_previous),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onPlayPause, enabled = controlsEnabled) {
                Icon(
                    painter = painterResource(
                        if (info.isPlaying) {
                            R.drawable.player_ic_pause
                        } else {
                            R.drawable.player_ic_play
                        },
                    ),
                    contentDescription = stringResource(
                        if (info.isPlaying) {
                            R.string.common_pause
                        } else {
                            R.string.common_play
                        },
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onNext,
                enabled = controlsEnabled && info.canNext,
            ) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_next),
                    contentDescription = stringResource(R.string.music_next),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onClose, enabled = controlsEnabled) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_close),
                    contentDescription = stringResource(R.string.music_mini_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Loop mode the player page persisted (the KV key is shared with it). */
private fun barLoopMode(): LxLoopMode {
    val saved: String = KV.get("lx_loop_mode", "")
    return LxLoopMode.fromName(saved)
}

/** Manual-next availability, mirroring the player page's rule. */
private fun barCanNext(size: Int, index: Int): Boolean {
    if (size <= 1) return false
    return !(barLoopMode() == LxLoopMode.ORDER && index >= size - 1)
}

/** Manual-previous availability for the mini bar (v1.26.0). */
private fun barCanPrev(size: Int, index: Int): Boolean {
    if (size <= 1) return false
    return !(barLoopMode() == LxLoopMode.ORDER && index <= 0)
}

/** Previous queue index for the mini bar, mirroring barNextIndex. */
private fun barPrevIndex(size: Int, index: Int): Int? {
    if (size <= 0) return null
    return when (barLoopMode()) {
        LxLoopMode.SHUFFLE -> {
            if (size <= 1) {
                index
            } else {
                var prev = Random.nextInt(size)
                while (prev == index) {
                    prev = Random.nextInt(size)
                }
                prev
            }
        }

        LxLoopMode.ORDER -> if (index > 0) index - 1 else null
        else -> (index - 1 + size) % size
    }
}

/** Next queue index for the mini bar, mirroring the player page. */
private fun barNextIndex(size: Int, index: Int): Int? {
    if (size <= 0) return null
    return when (barLoopMode()) {
        LxLoopMode.SHUFFLE -> {
            if (size <= 1) {
                index
            } else {
                var next = Random.nextInt(size)
                while (next == index) {
                    next = Random.nextInt(size)
                }
                next
            }
        }

        LxLoopMode.ORDER -> if (index < size - 1) index + 1 else null
        else -> (index + 1) % size
    }
}

/** Display name of a track's source for a mini-bar-published record. */
private fun barSourceLabel(context: Context, track: MusicTrack): String {
    if (track.isPy) {
        return PyMusicSources.findDef(context, track.pySourceId)?.name
            ?: context.getString(R.string.music_py_fallback)
    }
    return when (track.source) {
        "kg" -> context.getString(R.string.music_source_kg)
        "kw" -> context.getString(R.string.music_source_kw)
        "tx" -> context.getString(R.string.music_source_tx)
        "wy" -> context.getString(R.string.music_source_wy)
        "mg" -> context.getString(R.string.music_source_mg)
        else -> track.source
    }
}

/**
 * Mini-bar "next": resolves [queue]'s track at [index] exactly like
 * the player page does (LX script or py spider), publishes the new
 * now-playing record, then hands the MediaItem to the bound service's
 * player. The track's HTTP headers are applied to the service first
 * (direct call, v1.16.0). Returns false on any failure; the caller
 * toasts and the current song keeps playing.
 */
private suspend fun playBarTrack(
    context: Context,
    player: Player,
    queue: List<MusicTrack>,
    index: Int,
): Boolean {
    val track = queue.getOrNull(index) ?: return false
    return try {
        val url: String
        val headers: Map<String, String>
        if (track.isPy) {
            val def = PyMusicSources.findDef(context, track.pySourceId)
                ?: return false
            val resolved = PyMusicBridge.resolve(context, def, track)
            url = resolved.url
            headers = resolved.headers
        } else {
            url = LxBridge.getMusicUrl(track)
            headers = emptyMap()
        }
        MusicServiceConnection.connectedService()?.setHeaders(headers)
        NowPlayingState.publish(
            NowPlayingState.Info(
                trackKey = NowPlayingState.keyOf(track),
                title = track.name,
                singer = track.singer,
                img = track.img,
                sourceLabel = barSourceLabel(context, track),
                isPlaying = true,
                canNext = barCanNext(queue.size, index),
                canPrev = barCanPrev(queue.size, index),
                serviceBacked = true,
                queueJson = MusicTrack.listToJson(queue),
                queueIndex = index,
            ),
        )
        player.setMediaItem(NowPlayingState.mediaItemFor(track, url))
        player.prepare()
        player.play()
        true
    } catch (t: Throwable) {
        false
    }
}
