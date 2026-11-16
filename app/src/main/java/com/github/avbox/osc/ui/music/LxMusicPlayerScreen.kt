@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
) {
    val track = state.current
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
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleQueue) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_queue),
                    contentDescription = stringResource(R.string.music_queue),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.CenterHorizontally),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.buffering) {
                CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = track?.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = track?.singer ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        LyricArea(state = state, modifier = Modifier.weight(1f))

        ProgressControls(
            state = state,
            onSeek = onSeek,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            onPrev = onPrev,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
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
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatTimeMs((fraction * duration).toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimeMs(state.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    painter = painterResource(R.drawable.player_ic_next),
                    contentDescription = stringResource(R.string.music_next),
                    modifier = Modifier.size(36.dp),
                )
            }
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
