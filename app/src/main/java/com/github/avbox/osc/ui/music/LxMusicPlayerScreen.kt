@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.avbox.osc.ui.music

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.MusicTrack
import com.github.avbox.osc.ui.components.AVBoxOptionSheet

/** Background when no cover colour has been sampled yet: deep blue-grey. */
private val FallbackBgColor = Color(0xFF262833)

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
    onEqCustomLevelsChanged: (List<Int>) -> Unit = {},
    onToggleLoopMode: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onDownload: () -> Unit = {},
    onSleepTimerSelected: (Int) -> Unit = {},
) {
    val track = state.current
    var immersive by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var sleepSheetOpen by remember { mutableStateOf(false) }
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
        // Kugou-style backdrop: a custom background image (v1.10.0
        // MusicBackground, blurred + dimmed) wins; otherwise the solid
        // colour sampled from the current cover, darkened, with a deep
        // blue-grey fallback and a soft bottom shade for readability.
        if (MusicAppearance.bgPath.isNotBlank()) {
            MusicBackground(fallbackModel = track?.img)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(state.bgColor ?: FallbackBgColor),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.30f),
                            ),
                        ),
                    ),
            )
        }
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
            if (state.sleepTimerEndAtMs > 0L) {
                Text(
                    text = stringResource(
                        R.string.music_sleep_remaining,
                        state.sleepMinutesLeft,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            IconButton(onClick = { sleepSheetOpen = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_sleep_timer),
                    contentDescription = stringResource(R.string.music_sleep_timer),
                    tint = if (state.sleepTimerEndAtMs > 0L) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White
                    },
                )
            }
            IconButton(onClick = { appearanceOpen = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_theme),
                    contentDescription = stringResource(R.string.music_appearance),
                    tint = Color.White,
                )
            }
        }

        // Big cover area; the square takes whatever room is left
        // between the fixed rows, capped by the narrower dimension.
        // Tapping it cycles the cover display style — 专辑图 →
        // 黑胶唱片 → 唱机 — persisted in MusicAppearance (v1.20.0,
        // Torres's reference player); the immersive lyrics still
        // open from the lyric lines below, as before.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            val context = LocalContext.current
            Box(
                modifier = Modifier
                    .size(side)
                    .clickable {
                        val next = MusicAppearance.cycleCoverStyle()
                        val nameRes = when (next) {
                            MusicAppearance.COVER_VINYL ->
                                R.string.music_cover_style_vinyl
                            MusicAppearance.COVER_TURNTABLE ->
                                R.string.music_cover_style_turntable
                            else -> R.string.music_cover_style_album
                        }
                        Toast.makeText(context, nameRes, Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (MusicAppearance.coverStyle) {
                    MusicAppearance.COVER_VINYL -> VinylCoverArt(
                        track = track,
                        playing = state.playing,
                        buffering = state.buffering,
                        modifier = Modifier.fillMaxSize(),
                    )
                    MusicAppearance.COVER_TURNTABLE -> TurntableCoverArt(
                        track = track,
                        playing = state.playing,
                        buffering = state.buffering,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> AlbumCoverArt(
                        track = track,
                        buffering = state.buffering,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Text(
            text = track?.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )

        // Small tag chips: the display name of the source the song came
        // from (computed by the Activity into state.sourceLabel — the
        // raw source id, e.g. "py:<slug>", is never shown), plus the
        // resolved quality when the bridge reported one.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (track != null && state.sourceLabel.isNotEmpty()) {
                PlayerTagChip(text = state.sourceLabel)
            }
            if (state.qualityText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                PlayerTagChip(text = state.qualityText)
            }
        }

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

        TwoLineLyrics(
            state = state,
            onClick = { immersive = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp),
        )

        // Favourite + download, spread across the row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(
                        if (state.isFavorite) {
                            R.drawable.ic_favorite
                        } else {
                            R.drawable.ic_favorite_border
                        },
                    ),
                    contentDescription = stringResource(
                        if (state.isFavorite) {
                            R.string.music_favorite_remove
                        } else {
                            R.string.music_favorite_add
                        },
                    ),
                    modifier = Modifier.size(26.dp),
                    tint = if (state.isFavorite) {
                        Color(0xFFFF5A76)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                )
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (state.downloadStatus) {
                    LxDownloadStatus.DOWNLOADING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            if (state.downloadPercent >= 0) {
                                Text(
                                    text = state.downloadPercent.toString() + "%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }

                    LxDownloadStatus.DONE -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(R.string.music_downloaded),
                            modifier = Modifier.size(26.dp),
                            tint = Color(0xFF7BD88F),
                        )
                    }

                    LxDownloadStatus.IDLE -> {
                        IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = stringResource(R.string.music_download),
                                modifier = Modifier.size(26.dp),
                                tint = Color.White.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }
        }

        // Rhythm visualizer (v1.20.0): FFT bars fed by the activity's
        // Visualizer, bouncing with the music above the progress bar
        // like Torres's reference player. Renders nothing until the
        // first frame lands, so without the microphone permission
        // (or on a device without audiofx) the page is as before.
        if (state.vizLevels.isNotEmpty()) {
            RhythmBars(levels = state.vizLevels)
            Spacer(Modifier.height(4.dp))
        }

        ProgressRow(state = state, onSeek = onSeek)

        // Control row: loop mode on the left, transport in the middle,
        // the play queue on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleLoopMode, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(loopModeIcon(state.loopMode)),
                    contentDescription = stringResource(state.loopMode.labelRes),
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
            Row(
                modifier = Modifier.weight(1f),
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
            IconButton(onClick = onToggleQueue, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_music_queue),
                    contentDescription = stringResource(R.string.music_queue),
                    modifier = Modifier.size(24.dp),
                    tint = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
    }
    }

    if (sleepSheetOpen) {
        val sleepMinutes = listOf(0, 15, 30, 45, 60)
        val offLabel = stringResource(R.string.music_sleep_off)
        val sleepLabels = sleepMinutes.map { minutes ->
            if (minutes == 0) {
                offLabel
            } else {
                stringResource(R.string.music_sleep_minutes, minutes)
            }
        }
        val sleepTitle = if (state.sleepTimerEndAtMs > 0L) {
            stringResource(R.string.music_sleep_timer) + " · " + stringResource(
                R.string.music_sleep_remaining,
                state.sleepMinutesLeft,
            )
        } else {
            stringResource(R.string.music_sleep_timer)
        }
        AVBoxOptionSheet(
            onDismissRequest = { sleepSheetOpen = false },
            title = sleepTitle,
            options = sleepLabels,
            selected = if (state.sleepTimerEndAtMs > 0L) null else offLabel,
            onSelect = { label ->
                sleepSheetOpen = false
                val index = sleepLabels.indexOf(label)
                onSleepTimerSelected(if (index >= 0) sleepMinutes[index] else 0)
            },
        )
    }

    if (appearanceOpen) {
        MusicAppearanceSheet(
            onDismissRequest = { appearanceOpen = false },
            showEqSection = true,
            eqPresets = state.eqPresets,
            onEqModeSelected = onEqModeSelected,
            eqBandCount = state.eqBandCount,
            eqBandMinMb = state.eqBandMinMb,
            eqBandMaxMb = state.eqBandMaxMb,
            eqCustomLevels = state.eqCustomLevels,
            onEqCustomLevelsChanged = onEqCustomLevelsChanged,
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

private fun loopModeIcon(mode: LxLoopMode): Int {
    return when (mode) {
        LxLoopMode.LIST -> R.drawable.ic_repeat
        LxLoopMode.SINGLE -> R.drawable.ic_repeat_one
        LxLoopMode.SHUFFLE -> R.drawable.ic_shuffle
        LxLoopMode.ORDER -> R.drawable.ic_play_order
    }
}

@Composable
private fun PlayerTagChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * Kugou-style lyric preview: the current line in full white and the next
 * one dimmed. Tapping opens the full immersive lyric view, same as
 * tapping the cover.
 */
@Composable
private fun TwoLineLyrics(
    state: LxPlayerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.lyricLines.isEmpty()) {
        Box(
            modifier = modifier.clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.music_no_lyric),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val currentLine = remember(state.lyricLines, state.positionMs) {
            state.lyricLines.indexOfLast { it.timeMs <= state.positionMs }
        }
        val current = state.lyricLines.getOrNull(currentLine.coerceAtLeast(0))
        val next = state.lyricLines.getOrNull(currentLine + 1)
        Text(
            text = current?.text ?: "",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = next?.text ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

/**
 * Rhythm visualizer bars (v1.20.0): rounded vertical bars mirrored
 * around the centre line, heights from [LxPlayerState.vizLevels] —
 * the FFT levels the activity's Visualizer publishes at capture
 * rate. Sits above the progress bar, like the reference player.
 */
@Composable
private fun RhythmBars(levels: FloatArray) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 20.dp),
    ) {
        if (levels.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (levels.size - 1)) / levels.size
        val midY = size.height / 2f
        val maxHalf = size.height / 2f - 1.dp.toPx()
        for (i in levels.indices) {
            val half = (levels[i].coerceIn(0f, 1f) * maxHalf)
                .coerceAtLeast(1.2.dp.toPx())
            drawRoundRect(
                color = Color.White.copy(alpha = 0.8f),
                topLeft = Offset(i * (barWidth + gap), midY - half),
                size = Size(barWidth, half * 2f),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Cover style 专辑图 (v1.20.0): the original square album art. */
@Composable
private fun AlbumCoverArt(
    track: MusicTrack?,
    buffering: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (track?.img != null) {
            AsyncImage(
                model = track.img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = Color.White.copy(alpha = 0.7f),
            )
        }
        if (buffering) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * A vinyl record: black disc with groove rings and a light arc, the
 * song's cover as the centre label, rotating while playing. The
 * spin rate eases toward its target (one turn per 12 s playing,
 * stopped paused) instead of snapping, like a real record changing
 * speed — the same feel as the music page's source-button vinyl.
 */
@Composable
private fun VinylDisc(
    track: MusicTrack?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    var rotation by remember { mutableStateOf(0f) }
    val playingNow = rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        val playingRate = 360f / 12_000f
        val slopePerMs = playingRate / 600f
        var rate = 0f
        var lastFrameMs = -1L
        while (true) {
            withFrameMillis { frameMs ->
                if (lastFrameMs >= 0L) {
                    val dtMs = (frameMs - lastFrameMs).toFloat()
                    val target = if (playingNow.value) playingRate else 0f
                    val maxStep = slopePerMs * dtMs
                    rate += (target - rate).coerceIn(-maxStep, maxStep)
                    rotation = (rotation + dtMs * rate) % 360f
                }
                lastFrameMs = frameMs
            }
        }
    }
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .clip(CircleShape)
            .background(Color(0xFF101114)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            // Grooves as a dense, faint texture like a real record
            // (v1.21.2): the first cut's four sparse rings read as
            // target circles against the reference deck, and its
            // bright light arc has no counterpart there — both gone.
            var fraction = 0.60f
            while (fraction <= 0.975f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.05f),
                    radius = r * fraction,
                    center = c,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
                fraction += 0.034f
            }
        }
        if (track?.img != null) {
            AsyncImage(
                model = track.img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize(0.46f)
                    .clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.46f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        // Spindle hole.
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color(0xFF050506)),
        )
    }
}

/**
 * The tonearm overlay of the vinyl / turntable styles, modelled on
 * the reference deck (v1.21.1): a ball pivot in the top-right
 * corner, a SHORT white arm and a black headshell that lands on
 * the record's right flank. The first cut's arm was 0.72 of the
 * box and slashed diagonally to the centre, which Torres flagged
 * as 还是不一样 against the reference. Playing swings the
 * headshell onto the record; pausing swings it back to rest
 * (animated).
 */
@Composable
private fun Tonearm(
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val angle by animateFloatAsState(
        targetValue = if (playing) 30f else 10f,
        animationSpec = tween(durationMillis = 700),
        label = "tonearm",
    )
    Canvas(modifier = modifier) {
        val pivot = Offset(size.width * 0.86f, size.height * 0.10f)
        val armLength = size.height * 0.37f
        drawCircle(
            color = Color(0xFFB9BBC2),
            radius = 11.dp.toPx(),
            center = pivot,
        )
        drawCircle(
            color = Color(0xFF3A3B40),
            radius = 5.dp.toPx(),
            center = pivot,
        )
        rotate(degrees = angle, pivot = pivot) {
            // Counterweight stub behind the pivot.
            drawLine(
                color = Color(0xFF3A3B40),
                start = pivot,
                end = Offset(pivot.x, pivot.y - size.height * 0.07f),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xFFF2F2F4),
                start = pivot,
                end = Offset(pivot.x, pivot.y + armLength),
                strokeWidth = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawRoundRect(
                color = Color(0xFF26272C),
                topLeft = Offset(
                    pivot.x - 4.5.dp.toPx(),
                    pivot.y + armLength - 2.dp.toPx(),
                ),
                size = Size(9.dp.toPx(), 17.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
        }
    }
}

/** Cover style 黑胶唱片 (v1.20.0): the vinyl disc plus its tonearm. */
@Composable
private fun VinylCoverArt(
    track: MusicTrack?,
    playing: Boolean,
    buffering: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        VinylDisc(
            track = track,
            playing = playing,
            modifier = Modifier.fillMaxSize(),
        )
        Tonearm(playing = playing, modifier = Modifier.fillMaxSize())
        if (buffering) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/**
 * Cover style 唱机 (v1.20.0): a light turntable platter holding the
 * vinyl disc, with the tonearm over it and the reference deck's
 * left-edge details (green power dot over a slim slider).
 */
@Composable
private fun TurntableCoverArt(
    track: MusicTrack?,
    playing: Boolean,
    buffering: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE9E6E0)),
        contentAlignment = Alignment.Center,
    ) {
        VinylDisc(
            track = track,
            playing = playing,
            modifier = Modifier.fillMaxSize(0.82f),
        )
        Tonearm(playing = playing, modifier = Modifier.fillMaxSize())
        // Left-edge details: the slim slider and the power dot.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 13.dp)
                .width(3.dp)
                .height(58.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFB9B4AA)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 11.dp, bottom = 13.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(Color(0xFF3ECF5A)),
        )
        if (buffering) {
            CircularProgressIndicator(color = Color(0xFF444444))
        }
    }
}

/** Thin progress bar with the elapsed / total times on the same row. */
@Composable
private fun ProgressRow(
    state: LxPlayerState,
    onSeek: (Long) -> Unit,
) {
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = seekFraction
        ?: (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    // This material3 version only offers the thumb/track slots on the
    // state-based Slider overload, so the slider owns a SliderState.
    // While the user is not dragging it is kept in sync with playback
    // progress; during a drag onValueChange drives seekFraction, so
    // the sync target equals the state's own value (no fight).
    val sliderState = remember {
        SliderState(value = fraction, trackRange = 0f..1f)
    }
    SideEffect {
        sliderState.value = fraction
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimeMs((fraction * duration).toLong()),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f),
        )
        Slider(
            state = sliderState,
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
            // Custom thumb/track (v1.17.2): the default M3 thumb is a
            // tall vertical pill that dwarfs this thin progress row, so
            // the thumb is a small round dot and the track is a hand-
            // drawn 4dp rounded bar (solid white up to the playhead,
            // translucent white beyond it).
            thumb = {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .shadow(2.dp, CircleShape)
                        .background(Color.White, CircleShape),
                )
            },
            track = { st ->
                // trackRange is 0f..1f, so st.value IS the fraction.
                val trackFraction = st.value.coerceIn(0f, 1f)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f)),
                    )
                    if (trackFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .width(maxWidth * trackFraction)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White),
                        )
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        Text(
            text = formatTimeMs(state.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

/**
 * Immersive mode: lyrics render large and centered over a solid background
 * in the same cover-sampled colour as the player page (never the cover
 * image itself, and never a custom background image). Tapping anywhere
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(state.bgColor ?: FallbackBgColor)
            .clickableNoRipple { onExit() },
    ) {
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
            ImmersiveLyricList(
                state = state,
                onExit = onExit,
                modifier = Modifier.weight(1f),
            )
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

/**
 * Tap target without the default ripple, for the immersive lyrics'
 * tap-anywhere-to-exit areas. The tap only fires when the gesture is not
 * consumed elsewhere, so scrolling the lyric list and pressing the
 * transport buttons keep working.
 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/** Same lyric data/parser as the normal layout, just larger and on-dark. */
@Composable
private fun ImmersiveLyricList(
    state: LxPlayerState,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.lyricLines.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clickableNoRipple { onExit() },
            contentAlignment = Alignment.Center,
        ) {
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
        modifier = modifier
            .fillMaxWidth()
            .clickableNoRipple { onExit() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp),
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
