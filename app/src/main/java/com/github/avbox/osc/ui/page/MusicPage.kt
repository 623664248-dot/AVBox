@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.page

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.LxBridge
import com.github.avbox.osc.music.LxPlaybackService
import com.github.avbox.osc.music.MusicChart
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
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val mode by vm.mode.collectAsStateWithLifecycle()
    val charts by vm.charts.collectAsStateWithLifecycle()
    val chartSongs by vm.chartSongs.collectAsStateWithLifecycle()
    val selectedChart by vm.selectedChart.collectAsStateWithLifecycle()
    val chartsLoading by vm.chartsLoading.collectAsStateWithLifecycle()
    val chartSongsLoading by vm.chartSongsLoading.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val pyActive by vm.pyActive.collectAsStateWithLifecycle()
    val pyClasses by vm.pyClasses.collectAsStateWithLifecycle()
    val selectedPyClass by vm.selectedPyClass.collectAsStateWithLifecycle()
    val pyItems by vm.pyItems.collectAsStateWithLifecycle()
    val pyLoading by vm.pyLoading.collectAsStateWithLifecycle()
    val pyLoadingMore by vm.pyLoadingMore.collectAsStateWithLifecycle()
    val pySearchActive by vm.pySearchActive.collectAsStateWithLifecycle()
    val pyDetailLoading by vm.pyDetailLoading.collectAsStateWithLifecycle()
    val pendingPyPlay by vm.pendingPyPlay.collectAsStateWithLifecycle()
    val episodePicker by vm.episodePicker.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var scriptDialogOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var fabOffset by remember { mutableStateOf(readSavedFabOffset()) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importScript(uri)
    }

    val pyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importPyScript(uri)
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

    // Mini player bar: the shared now-playing record plus this page's
    // own lightweight MediaController for service-backed records. The
    // connection is best-effort — any failure just keeps the bar
    // hidden and never affects the page itself.
    val nowPlaying by NowPlayingState.info.collectAsStateWithLifecycle()
    var miniController by remember { mutableStateOf<MediaController?>(null) }
    val barScope = rememberCoroutineScope()
    DisposableEffect(nowPlaying?.serviceBacked) {
        if (nowPlaying?.serviceBacked != true) {
            return@DisposableEffect onDispose { }
        }
        val appContext = context.applicationContext
        var future: ListenableFuture<MediaController>? = null
        var disposed = false
        try {
            val token = SessionToken(
                appContext,
                ComponentName(appContext, LxPlaybackService::class.java),
            )
            val built = MediaController.Builder(appContext, token).buildAsync()
            future = built
            built.addListener(
                {
                    val mc = try {
                        built.get()
                    } catch (t: Throwable) {
                        null
                    }
                    if (mc != null && !disposed) {
                        miniController = mc
                    } else {
                        try {
                            mc?.release()
                        } catch (t: Throwable) {
                            // already gone
                        }
                    }
                },
                ContextCompat.getMainExecutor(appContext),
            )
        } catch (t: Throwable) {
            // silent: no controller, no bar
        }
        onDispose {
            disposed = true
            miniController?.let { mc ->
                try {
                    mc.release()
                } catch (t: Throwable) {
                    // already gone
                }
            }
            miniController = null
            try {
                future?.cancel(true)
            } catch (t: Throwable) {
                // already done or released above
            }
        }
    }
    val miniBarVisible = nowPlaying != null &&
        (nowPlaying?.serviceBacked != true || miniController != null)

    Box(modifier = Modifier.fillMaxSize()) {
        // Custom background (when set) spans the whole page; the content
        // box below keeps its original insets and FAB bounds measurement.
        if (MusicAppearance.bgPath.isNotBlank()) {
            MusicBackground(fallbackModel = null)
        }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp)
            .onSizeChanged { rootSize = it },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            // Top row: animated note, the search field and the appearance
            // entry. The field lives here in both native and spider mode
            // (it used to sit inside the mode contents below); its query
            // state, IME search action and pill styling are unchanged,
            // only the search target differs (native search switches to
            // the search mode first so the results stay visible).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedMusicNote()
                MusicSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        if (pyActive) {
                            vm.pySearch(query)
                        } else {
                            if (mode != MusicMode.SEARCH) {
                                vm.selectMode(MusicMode.SEARCH)
                            }
                            vm.search(query)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { appearanceOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_theme),
                        contentDescription = stringResource(R.string.music_appearance),
                    )
                }
            }

            // Second row: the platform chips act as the resource-site
            // switcher and span the full width; the source-script entry
            // moved to the draggable floating button overlaid on the page.
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
                        items(sources, key = { it.code }) { source ->
                            FilterChip(
                                selected = source.code == selectedSource,
                                onClick = { vm.selectSource(source.code) },
                                label = { Text(sourceLabel(source.code)) },
                            )
                        }
                    }
                }
            }

            // Third row: search / charts / favourites mode switch. The
            // row only exists in native mode (a py source has no platform
            // charts), while the native mode state is left untouched in
            // spider mode; appearance lives in the top search row now.
            if (!pyActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = mode == MusicMode.SEARCH,
                        onClick = { vm.selectMode(MusicMode.SEARCH) },
                        label = { Text(stringResource(R.string.music_tab_search)) },
                    )
                    FilterChip(
                        selected = mode == MusicMode.CHARTS,
                        onClick = { vm.selectMode(MusicMode.CHARTS) },
                        label = { Text(stringResource(R.string.music_tab_charts)) },
                    )
                    FilterChip(
                        selected = mode == MusicMode.FAVORITES,
                        onClick = { vm.selectMode(MusicMode.FAVORITES) },
                        label = { Text(stringResource(R.string.music_favorites_title)) },
                    )
                }
            }

            BackHandler(enabled = mode == MusicMode.CHARTS && selectedChart != null) {
                vm.closeChart()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (miniBarVisible) 64.dp else 0.dp),
            ) {
                if (pyActive) {
                    PySpiderContent(
                        items = pyItems,
                        loading = pyLoading,
                        loadingMore = pyLoadingMore,
                        searchActive = pySearchActive,
                        onClearSearch = { vm.pyClearSearch() },
                        onLoadMore = { vm.pyLoadMore() },
                        onItemClick = { track -> vm.onPyTrackClick(track) },
                    )
                    if (pyDetailLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                } else if (mode == MusicMode.FAVORITES) {
                    FavoritesContent(
                        favorites = favorites,
                        onPlayFavorite = { index ->
                            LxMusicPlayerActivity.start(
                                context,
                                MusicTrack.listToJson(favorites),
                                index,
                            )
                        },
                    )
                } else if (mode == MusicMode.CHARTS) {
                    ChartsContent(
                        charts = charts,
                        chartSongs = chartSongs,
                        selectedChart = selectedChart,
                        chartsLoading = chartsLoading,
                        chartSongsLoading = chartSongsLoading,
                        onOpenChart = { vm.openChart(it) },
                        onCloseChart = { vm.closeChart() },
                        onPlayChartSong = { index ->
                            LxMusicPlayerActivity.start(
                                context,
                                MusicTrack.listToJson(chartSongs),
                                index,
                            )
                        },
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
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        if (miniBarVisible) {
            val np = nowPlaying
            if (np != null) {
                MiniPlayerBar(
                    info = np,
                    controlsEnabled = if (np.serviceBacked) {
                        miniController != null
                    } else {
                        NowPlayingState.localControls != null
                    },
                    onBodyClick = { LxMusicPlayerActivity.startAttached(context) },
                    onPlayPause = {
                        try {
                            if (np.serviceBacked) {
                                val mc = miniController
                                if (mc != null) {
                                    if (mc.isPlaying) mc.pause() else mc.play()
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
                            val mc = miniController
                            if (mc != null && np.canNext) {
                                barScope.launch {
                                    val queue = MusicTrack.listFromJson(np.queueJson)
                                    val next = barNextIndex(queue.size, np.queueIndex)
                                    if (next != null &&
                                        !playBarTrack(context, mc, queue, next)
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
                    onClose = {
                        if (np.serviceBacked) {
                            try {
                                miniController?.stop()
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
        MusicSourceFab(
            boundsSize = rootSize,
            savedOffset = fabOffset,
            enabled = !scriptBusy,
            onPersistOffset = { offset ->
                fabOffset = offset
                saveFabOffset(offset)
            },
            onClick = { scriptDialogOpen = true },
        )
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
                            // .js files report all kinds of mime types from
                            // file managers; accept the usual ones and fall
                            // back to */* so any picker can offer the file.
                            importLauncher.launch(
                                arrayOf(
                                    "text/javascript",
                                    "application/javascript",
                                    "application/x-javascript",
                                    "text/*",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.music_source_import))
                    }
                    TextButton(
                        onClick = {
                            // .py spiders likewise come with inconsistent
                            // mime types; accept python-ish types and */*.
                            pyImportLauncher.launch(
                                arrayOf(
                                    "text/x-python",
                                    "text/plain",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.music_py_import))
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

    val picker = episodePicker
    if (picker != null) {
        AVBoxAlertDialog(
            onDismissRequest = { vm.dismissEpisodePicker() },
            title = { Text(picker.title) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.music_py_choose_episode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        itemsIndexed(
                            picker.episodes,
                            key = { index, _ -> index },
                        ) { index, episode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.playEpisode(index) }
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { vm.dismissEpisodePicker() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
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
 * mapped onto the track.
 */
@Composable
private fun PySpiderContent(
    items: List<MusicTrack>,
    loading: Boolean,
    loadingMore: Boolean,
    searchActive: Boolean,
    onClearSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (MusicTrack) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (searchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.music_tab_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClearSearch) {
                    Text(stringResource(R.string.music_py_back_category))
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
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(
                            items,
                            key = { index, _ -> index },
                        ) { index, track ->
                            if (index >= items.size - 5) {
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
 * The music note at the start of the top search row, gently bobbing and
 * tilting on an infinite transition (same animation APIs as the shimmer
 * in ui/components/Skeleton.kt). Amplitudes stay small (±3dp, ±8°) and
 * the full cycle is 1.2s (600ms each way, reversed), so the single 24dp
 * icon costs nothing measurable next to the lists below.
 */
@Composable
private fun AnimatedMusicNote() {
    val transition = rememberInfiniteTransition(label = "musicNote")
    val bobLimitPx = with(LocalDensity.current) { 3.dp.toPx() }
    val bob by transition.animateFloat(
        initialValue = -bobLimitPx,
        targetValue = bobLimitPx,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "musicNoteBob",
    )
    val tilt by transition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "musicNoteTilt",
    )
    Icon(
        painter = painterResource(R.drawable.ic_tab_music),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer {
                translationY = bob
                rotationZ = tilt
            },
    )
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
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = stringResource(R.string.music_search_action),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
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

@Composable
private fun FavoritesContent(
    favorites: List<MusicTrack>,
    onPlayFavorite: (Int) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.music_favorites_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(
            favorites,
            key = { index, _ -> index },
        ) { index, track ->
            MusicTrackRow(
                track = track,
                onClick = { onPlayFavorite(index) },
            )
        }
    }
}

@Composable
private fun ChartsContent(
    charts: List<MusicChart>,
    chartSongs: List<MusicTrack>,
    selectedChart: MusicChart?,
    chartsLoading: Boolean,
    chartSongsLoading: Boolean,
    onOpenChart: (MusicChart) -> Unit,
    onCloseChart: () -> Unit,
    onPlayChartSong: (Int) -> Unit,
) {
    when {
        selectedChart != null -> {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCloseChart) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                    Text(
                        text = selectedChart.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        chartSongsLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        chartSongs.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.music_empty_result),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(
                                    chartSongs,
                                    key = { index, _ -> index },
                                ) { index, track ->
                                    MusicTrackRow(
                                        track = track,
                                        onClick = { onPlayChartSong(index) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        chartsLoading -> {
            Box(modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        charts.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(R.string.music_charts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
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
                modifier = Modifier.padding(
                    top = 6.dp,
                    start = if (hasBg) 8.dp else 0.dp,
                    end = if (hasBg) 8.dp else 0.dp,
                ),
            )
            if (chart.updateTip.isNotEmpty()) {
                Text(
                    text = chart.updateTip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = if (hasBg) 8.dp else 0.dp,
                        end = if (hasBg) 8.dp else 0.dp,
                        bottom = if (hasBg) 8.dp else 0.dp,
                    ),
                )
            }
        }
    }
}

/** KV key for the floating source button position, stored as "x,y" px. */
private const val KEY_SOURCE_FAB_POSITION = "music_source_fab_pos"

private fun readSavedFabOffset(): IntOffset? {
    // The default value matters: KV reads without one decode unregistered
    // keys to null (the v1.9.0 import bug), so always pass "" here.
    val raw: String = KV.get(KEY_SOURCE_FAB_POSITION, "")
    if (raw.isBlank()) return null
    val parts = raw.split(",")
    if (parts.size != 2) return null
    val x = parts[0].trim().toIntOrNull() ?: return null
    val y = parts[1].trim().toIntOrNull() ?: return null
    return IntOffset(x, y)
}

private fun saveFabOffset(offset: IntOffset) {
    KV.put(KEY_SOURCE_FAB_POSITION, offset.x.toString() + "," + offset.y.toString())
}

/**
 * Draggable floating shortcut to the source-script dialog, overlaid on the
 * music page. A press whose total movement stays under ~8dp counts as a tap
 * and opens the dialog; anything longer drags the pill with the finger,
 * clamped to [boundsSize] so it can never leave the page, and the final
 * offset is reported via [onPersistOffset]. With no saved offset the pill
 * rests at the bottom-end of the page with a 16dp margin. The gesture is a
 * single awaitEachGesture loop because detectDragGestures never reports a
 * press that ends before the touch slop, which would make taps
 * indistinguishable from cancelled drags.
 */
@Composable
private fun MusicSourceFab(
    boundsSize: IntSize,
    savedOffset: IntOffset?,
    enabled: Boolean,
    onPersistOffset: (IntOffset) -> Unit,
    onClick: () -> Unit,
) {
    var ownSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffset by remember { mutableStateOf<IntOffset?>(null) }
    val density = LocalDensity.current
    val marginPx = with(density) { 16.dp.roundToPx() }
    val clickThresholdPx = with(density) { 8.dp.roundToPx() }.toFloat()
    val boundsState = rememberUpdatedState(boundsSize)
    val savedState = rememberUpdatedState(savedOffset)
    val enabledState = rememberUpdatedState(enabled)
    val clickState = rememberUpdatedState(onClick)
    val persistState = rememberUpdatedState(onPersistOffset)

    fun clampToBounds(raw: IntOffset, btnSize: IntSize, area: IntSize): IntOffset {
        val maxX = (area.width - btnSize.width).coerceAtLeast(0)
        val maxY = (area.height - btnSize.height).coerceAtLeast(0)
        return IntOffset(raw.x.coerceIn(0, maxX), raw.y.coerceIn(0, maxY))
    }

    fun restingOffset(): IntOffset {
        val area = boundsState.value
        val dragging = dragOffset
        if (dragging != null) return dragging
        val saved = savedState.value
        if (saved != null) return clampToBounds(saved, ownSize, area)
        return clampToBounds(
            IntOffset(
                area.width - ownSize.width - marginPx,
                area.height - ownSize.height - marginPx,
            ),
            ownSize,
            area,
        )
    }

    val ready = boundsSize != IntSize.Zero && ownSize != IntSize.Zero
    Surface(
        modifier = Modifier
            .alpha(if (ready) 1f else 0f)
            .offset { if (ready) restingOffset() else IntOffset.Zero }
            .onSizeChanged { ownSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startDragOffset = dragOffset
                    var current = restingOffset()
                    var movedPx = 0f
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            dragOffset = startDragOffset
                            finished = true
                        } else if (change.changedToUp()) {
                            // Compose 1.12 has no separate cancel API
                            // (changedToCanceled does not exist); a canceled
                            // pointer also ends the gesture through here.
                            change.consume()
                            if (movedPx < clickThresholdPx) {
                                // Tap: undo the few px of finger drift and
                                // treat the press as a click.
                                dragOffset = startDragOffset
                                if (enabledState.value) clickState.value()
                            } else {
                                persistState.value(current)
                            }
                            finished = true
                        } else {
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                change.consume()
                                movedPx += delta.getDistance()
                                current = clampToBounds(
                                    IntOffset(
                                        current.x + delta.x.roundToInt(),
                                        current.y + delta.y.roundToInt(),
                                    ),
                                    ownSize,
                                    boundsState.value,
                                )
                                dragOffset = current
                            }
                        }
                    }
                }
            },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = stringResource(R.string.music_source_switch),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
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
 * Pushes a track's HTTP headers to the playback service, the same
 * custom-command protocol the player page uses (best effort, bounded).
 */
private suspend fun sendBarHeaders(
    mc: MediaController,
    headers: Map<String, String>,
) {
    try {
        val args = Bundle()
        args.putStringArrayList(
            LxPlaybackService.EXTRA_HEADER_KEYS,
            ArrayList(headers.keys),
        )
        for ((key, value) in headers) {
            args.putString(LxPlaybackService.EXTRA_HEADER_VALUE_PREFIX + key, value)
        }
        val future = mc.sendCustomCommand(
            SessionCommand(LxPlaybackService.ACTION_SET_HEADERS, Bundle()),
            args,
        )
        withContext(Dispatchers.IO) {
            future.get(2000L, TimeUnit.MILLISECONDS)
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        // best effort only
    }
}

/**
 * Mini-bar "next": resolves [queue]'s track at [index] exactly like
 * the player page does (LX script or py spider), publishes the new
 * now-playing record, then hands the MediaItem to the service through
 * the page's controller. Returns false on any failure; the caller
 * toasts and the current song keeps playing.
 */
private suspend fun playBarTrack(
    context: Context,
    controller: MediaController,
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
        sendBarHeaders(controller, headers)
        NowPlayingState.publish(
            NowPlayingState.Info(
                trackKey = NowPlayingState.keyOf(track),
                title = track.name,
                singer = track.singer,
                img = track.img,
                sourceLabel = barSourceLabel(context, track),
                isPlaying = true,
                canNext = barCanNext(queue.size, index),
                serviceBacked = true,
                queueJson = MusicTrack.listToJson(queue),
                queueIndex = index,
            ),
        )
        controller.setMediaItem(NowPlayingState.mediaItemFor(track, url))
        controller.prepare()
        controller.play()
        true
    } catch (t: Throwable) {
        false
    }
}
