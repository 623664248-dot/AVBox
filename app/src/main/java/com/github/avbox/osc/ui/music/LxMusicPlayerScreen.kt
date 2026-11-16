@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.github.avbox.osc.R

@Composable
fun LxMusicPlayerScreen(
    state: LxPlayerState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleQueue: () -> Unit,
    onSelectQueueItem: (Int) -> Unit,
    onEqModeSelected: (String) -> Unit = {},
) {
    val track = state.current
    var immersive by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    if (immersive) {
        BackHandler { immersive = false }
        ImmersiveLyrics(
            state = state,
            onExit = { immersive = false },
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrev = onPrev,
        )
    } else {
    Box(modifier = Modifier.fillMaxSize()) {
        // RikkaHub-style backdrop: custom image or the current cover,
        // blurred and dimmed per MusicAppearance.
        MusicBackground(fallbackModel = track?.img)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.common_back),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { appearanceOpen = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_theme),
                    contentDescription = stringResource(R.string.music_appearance),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onToggleQueue) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_queue),
                    contentDescription = stringResource(R.string.music_queue),
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .size(184.dp)
                    .clickable { immersive = true },
                contentAlignment = Alignment.Center,
            ) {
                if (track?.img != null) {
                    AsyncImage(
                        model = track.img,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                }
                if (state.buffering) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = track?.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = track?.singer ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        state.errorText?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LyricArea(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ProgressControls(
                    state = state,
                    onSeek = onSeek,
                    onTogglePlay = onTogglePlay,
                    onNext = onNext,
                    onPrev = onPrev,
                )
            }
        }
    }
    }
    }

    if (appearanceOpen) {
        MusicAppearanceSheet(
            onDismissRequest = { appearanceOpen = false },
            showEqSection = true,
            eqPresets = state.eqPresets,
            onEqModeSelected = onEqModeSelected,
        )
    }

    if (state.showQueue) {
        Dialog(onDismissRequest = onToggleQueue) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.music_queue),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    LazyColumn {
                        itemsIndexed(state.queue, key = { index, _ -> index }) { index, item ->
                            val isCurrent = index == state.index
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectQueueItem(index) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.singer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricArea(state: LxPlayerState, modifier: Modifier = Modifier) {
    if (state.lyricLines.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.music_no_lyric),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        return
    }
    val currentLine = remember(state.lyricLines, state.positionMs) {
        state.lyricLines.indexOfLast { it.timeMs <= state.positionMs }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine) {
        if (currentLine >= 0) {
            listState.animateScrollToItem(currentLine)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(state.lyricLines, key = { index, _ -> index }) { index, line ->
            Text(
                text = line.text,
                style = if (index == currentLine) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (index == currentLine) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.6f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProgressControls(
    state: LxPlayerState,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = seekFraction
        ?: (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { seekFraction = it },
            onValueChangeFinished = {
                seekFraction?.let { onSeek((it * duration).toLong()) }
                seekFraction = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.35f),
            ),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatTimeMs((fraction * duration).toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimeMs(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_prev),
                    contentDescription = stringResource(R.string.music_previous),
                    modifier = Modifier.size(36.dp),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                Icon(
                    painter = painterResource(
                        if (state.playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                    ),
                    contentDescription = stringResource(
                        if (state.playing) R.string.common_pause else R.string.common_play,
                    ),
                    modifier = Modifier.size(56.dp),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_next),
                    contentDescription = stringResource(R.string.music_next),
                    modifier = Modifier.size(36.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Immersive mode: the cover fills the whole screen as the background under a
 * dark gradient, lyrics render large and centered on top. Tapping anywhere
 * outside the controls (or the system back key, handled by the caller)
 * exits back to the normal layout. Controls stay minimal: prev/play/next.
 */
@Composable
private fun ImmersiveLyrics(
    state: LxPlayerState,
    onExit: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val track = state.current
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onExit() },
        ) {
            if (track?.img != null) {
                AsyncImage(
                    model = track.img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                text = track?.name ?: "",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = track?.singer ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.buffering) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            ImmersiveLyricList(state = state, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.player_ic_prev),
                        contentDescription = stringResource(R.string.music_previous),
                        modifier = Modifier.size(36.dp),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onTogglePlay, modifier = Modifier.size(72.dp)) {
                    Icon(
                        painter = painterResource(
                            if (state.playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
                        ),
                        contentDescription = stringResource(
                            if (state.playing) R.string.common_pause else R.string.common_play,
                        ),
                        modifier = Modifier.size(56.dp),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.player_ic_next),
                        contentDescription = stringResource(R.string.music_next),
                        modifier = Modifier.size(36.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** Same lyric data/parser as the normal layout, just larger and on-dark. */
@Composable
private fun ImmersiveLyricList(state: LxPlayerState, modifier: Modifier = Modifier) {
    if (state.lyricLines.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.music_no_lyric),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        return
    }
    val currentLine = remember(state.lyricLines, state.positionMs) {
        state.lyricLines.indexOfLast { it.timeMs <= state.positionMs }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine) {
        if (currentLine >= 0) {
            listState.animateScrollToItem(currentLine)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 24.dp),
    ) {
        itemsIndexed(state.lyricLines, key = { index, _ -> index }) { index, line ->
            Text(
                text = line.text,
                style = if (index == currentLine) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = if (index == currentLine) {
                    Color.White
                } else {
                    Color.White.copy(alpha = 0.55f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
}
