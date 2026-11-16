@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.changedToCanceled
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.MusicChart
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.music.LxMusicPlayerActivity
import com.github.avbox.osc.util.KV
import kotlin.math.roundToInt

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

    var query by rememberSaveable { mutableStateOf("") }
    var scriptDialogOpen by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var fabOffset by remember { mutableStateOf(readSavedFabOffset()) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importScript(uri)
    }

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
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
            // First row: the platform chips act as the resource-site switcher
            // and span the full width; the source-script entry moved to the
            // draggable floating button overlaid on the page.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sources, key = { it.code }) { source ->
                        FilterChip(
                            selected = source.code == selectedSource,
                            onClick = { vm.selectSource(source.code) },
                            label = { Text(sourceLabel(source.code)) },
                        )
                    }
                }
            }

            // Second row: search / charts mode switch.
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            }

            BackHandler(enabled = mode == MusicMode.CHARTS && selectedChart != null) {
                vm.closeChart()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (mode == MusicMode.CHARTS) {
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        MusicSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = { vm.search(query) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
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

    if (scriptDialogOpen) {
        AVBoxAlertDialog(
            onDismissRequest = { scriptDialogOpen = false },
            title = { Text(stringResource(R.string.music_source_switch)) },
            text = {
                Column {
                    scriptOptions.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scriptDialogOpen = false
                                    vm.switchScript(option.id)
                                }
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
                                IconButton(onClick = { vm.deleteScript(option.id) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.music_source_delete),
                                    )
                                }
                            }
                        }
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
        color = Color.Transparent,
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
    Row(
        modifier = Modifier
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
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
            modifier = Modifier.padding(top = 6.dp),
        )
        if (chart.updateTip.isNotEmpty()) {
            Text(
                text = chart.updateTip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
                        } else if (change.changedToCanceled()) {
                            dragOffset = startDragOffset
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
