@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.avbox.osc.ui.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.music.MusicPlayPrefs
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.util.KV
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared appearance settings for the music pages (RikkaHub-style custom
 * background): an optional user-picked background image, a blur radius and
 * a black scrim opacity, plus the player's EQ mode. Values live in compose
 * snapshot state backed by KV, so editing them in the settings sheet
 * re-renders the page behind it immediately and persists across launches.
 *
 * KV reads always pass a "" default: without one, unregistered keys decode
 * to null (the v1.9.0 music-import bug).
 */
object MusicAppearance {

    private const val KEY_BG_PATH = "music_bg_path"
    private const val KEY_BLUR_DP = "music_bg_blur_dp"
    private const val KEY_SCRIM_PERCENT = "music_bg_scrim_percent"
    private const val KEY_EQ_MODE = "music_eq_mode"
    private const val KEY_EQ_CUSTOM_LEVELS = "music_eq_custom_levels"
    private const val KEY_COVER_STYLE = "music_cover_style"

    /** Player-page cover styles (v1.20.0), cycled by tapping the cover. */
    const val COVER_ALBUM = 0
    const val COVER_VINYL = 1
    const val COVER_TURNTABLE = 2

    private const val BG_DIR_NAME = "music_bg"

    const val EQ_OFF = "off"
    const val EQ_BASS = "bass"
    const val EQ_SURROUND = "surround"
    const val EQ_PRESET_PREFIX = "preset:"

    /** Built-in strong EQ curves, applied band-by-band by the player. */
    const val EQ_CURVE_BASS = "curve:bass"
    const val EQ_CURVE_VOCAL = "curve:vocal"
    const val EQ_CURVE_BRIGHT = "curve:bright"
    const val EQ_CURVE_PREFIX = "curve:"

    /** User-tuned per-band levels, stored under [KEY_EQ_CUSTOM_LEVELS]. */
    const val EQ_CUSTOM = "custom"

    const val DEFAULT_BLUR_DP = 18
    const val DEFAULT_SCRIM_PERCENT = 55
    const val MAX_BLUR_DP = 40
    const val MAX_SCRIM_PERCENT = 90

    var bgPath by mutableStateOf(readString(KEY_BG_PATH))
        private set

    var blurDp by mutableIntStateOf(readInt(KEY_BLUR_DP, DEFAULT_BLUR_DP).coerceIn(0, MAX_BLUR_DP))
        private set

    var scrimPercent by mutableIntStateOf(
        readInt(KEY_SCRIM_PERCENT, DEFAULT_SCRIM_PERCENT).coerceIn(0, MAX_SCRIM_PERCENT),
    )
        private set

    var eqMode by mutableStateOf(readString(KEY_EQ_MODE).ifBlank { EQ_OFF })
        private set

    var coverStyle by mutableIntStateOf(
        readInt(KEY_COVER_STYLE, COVER_ALBUM).coerceIn(COVER_ALBUM, COVER_TURNTABLE),
    )
        private set

    /** Advances to the next cover style and persists it. */
    fun cycleCoverStyle(): Int {
        val next = (coverStyle + 1) % (COVER_TURNTABLE + 1)
        coverStyle = next
        KV.put(KEY_COVER_STYLE, next.toString())
        return next
    }

    private fun readString(key: String): String = KV.get(key, "")

    private fun readInt(key: String, default: Int): Int {
        val raw: String = KV.get(key, "")
        return raw.toIntOrNull() ?: default
    }

    fun updateBgPath(path: String) {
        bgPath = path
        KV.put(KEY_BG_PATH, path)
    }

    fun updateBlurDp(value: Int) {
        val coerced = value.coerceIn(0, MAX_BLUR_DP)
        blurDp = coerced
        KV.put(KEY_BLUR_DP, coerced.toString())
    }

    fun updateScrimPercent(value: Int) {
        val coerced = value.coerceIn(0, MAX_SCRIM_PERCENT)
        scrimPercent = coerced
        KV.put(KEY_SCRIM_PERCENT, coerced.toString())
    }

    fun updateEqMode(mode: String) {
        eqMode = mode
        KV.put(KEY_EQ_MODE, mode)
    }

    /**
     * Per-band levels (millibels) of the custom EQ mode, comma-separated
     * in KV. Empty when never tuned; callers treat missing bands as 0.
     */
    fun readEqCustomLevels(): List<Int> {
        val raw: String = KV.get(KEY_EQ_CUSTOM_LEVELS, "")
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim().toIntOrNull() ?: 0 }
    }

    fun updateEqCustomLevels(levels: List<Int>) {
        KV.put(KEY_EQ_CUSTOM_LEVELS, levels.joinToString(","))
    }

    fun eqModeForPreset(index: Int): String = EQ_PRESET_PREFIX + index

    fun eqPresetIndex(mode: String): Int? =
        if (mode.startsWith(EQ_PRESET_PREFIX)) {
            mode.removePrefix(EQ_PRESET_PREFIX).toIntOrNull()
        } else {
            null
        }

    /**
     * Copies a picked image into filesDir/music_bg/ (old picks are removed)
     * and returns the absolute path, or null when the copy fails. Blocking
     * IO: call from a background dispatcher.
     */
    fun copyBackgroundImage(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, BG_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "bg_" + System.currentTimeMillis() + ".jpg")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                target.outputStream().use { output -> stream.copyTo(output) }
            }
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Full-bleed background layer shared by the player page and the music
 * page: the custom background image when one is set, otherwise
 * [fallbackModel] (the player passes the current cover), blurred by the
 * configured radius and dimmed by a black scrim. The slight scale-up while
 * blurring hides the transparent fringe Modifier.blur leaves at the edges
 * (same trick as PosterBackdrop). On API < 31 blur is a no-op.
 */
@Composable
fun MusicBackground(
    fallbackModel: Any?,
    modifier: Modifier = Modifier,
) {
    val customPath = MusicAppearance.bgPath
    val blur = MusicAppearance.blurDp
    val scrim = MusicAppearance.scrimPercent
    // Stored values are internal file paths (picked images are copied);
    // a content:// value from an older flow is handed to Coil as a Uri.
    val model: Any? = when {
        customPath.isBlank() -> fallbackModel
        customPath.startsWith("content://") -> Uri.parse(customPath)
        else -> File(customPath)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF141419)),
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (blur > 0) {
                            scaleX = 1.3f
                            scaleY = 1.3f
                        }
                    }
                    .blur(blur.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim / 100f)),
        )
    }
}

/**
 * Appearance settings sheet. The background section is always shown;
 * [showEqSection] adds the sound-effect choices (player page only). Every
 * change writes through [MusicAppearance] right away, so the page behind
 * the sheet previews it live. EQ picks additionally invoke
 * [onEqModeSelected] so the host Activity can re-apply the effect to the
 * playing player.
 */
@Composable
fun MusicAppearanceSheet(
    onDismissRequest: () -> Unit,
    showEqSection: Boolean = false,
    eqPresets: List<String> = emptyList(),
    onEqModeSelected: (String) -> Unit = {},
    eqBandCount: Int = 0,
    eqBandMinMb: Int = 0,
    eqBandMaxMb: Int = 0,
    eqCustomLevels: List<Int> = emptyList(),
    onEqCustomLevelsChanged: (List<Int>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFailedMsg = stringResource(R.string.music_appearance_pick_failed)
    // System photo picker (album grid directly) instead of the files
    // picker; the picked Uri's grant is short-lived, so the result is
    // copied into internal storage by copyBackgroundImage as before.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                MusicAppearance.copyBackgroundImage(context, uri)
            }
            if (path != null) {
                MusicAppearance.updateBgPath(path)
            } else {
                Toast.makeText(context, pickFailedMsg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AVBoxBottomSheet(
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.music_appearance),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.music_appearance_section_bg),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.music_appearance_pick_bg))
                }
                TextButton(
                    onClick = { MusicAppearance.updateBgPath("") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.music_appearance_reset_bg))
                }
            }
            Text(
                text = stringResource(R.string.music_appearance_blur, MusicAppearance.blurDp),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = MusicAppearance.blurDp.toFloat(),
                onValueChange = { MusicAppearance.updateBlurDp(it.roundToInt()) },
                valueRange = 0f..MusicAppearance.MAX_BLUR_DP.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.music_appearance_scrim,
                    MusicAppearance.scrimPercent,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = MusicAppearance.scrimPercent.toFloat(),
                onValueChange = { MusicAppearance.updateScrimPercent(it.roundToInt()) },
                valueRange = 0f..MusicAppearance.MAX_SCRIM_PERCENT.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (showEqSection) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.music_eq_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val currentMode = MusicAppearance.eqMode
                EqOptionRow(
                    label = stringResource(R.string.music_eq_off),
                    selected = currentMode == MusicAppearance.EQ_OFF,
                    onClick = { selectEq(MusicAppearance.EQ_OFF, onEqModeSelected) },
                )
                eqPresets.forEachIndexed { index, name ->
                    val mode = MusicAppearance.eqModeForPreset(index)
                    EqOptionRow(
                        label = name,
                        selected = currentMode == mode,
                        onClick = { selectEq(mode, onEqModeSelected) },
                    )
                }
                EqOptionRow(
                    label = stringResource(R.string.music_eq_curve_bass),
                    selected = currentMode == MusicAppearance.EQ_CURVE_BASS,
                    onClick = { selectEq(MusicAppearance.EQ_CURVE_BASS, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_curve_vocal),
                    selected = currentMode == MusicAppearance.EQ_CURVE_VOCAL,
                    onClick = { selectEq(MusicAppearance.EQ_CURVE_VOCAL, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_curve_bright),
                    selected = currentMode == MusicAppearance.EQ_CURVE_BRIGHT,
                    onClick = { selectEq(MusicAppearance.EQ_CURVE_BRIGHT, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_bass),
                    selected = currentMode == MusicAppearance.EQ_BASS,
                    onClick = { selectEq(MusicAppearance.EQ_BASS, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_surround),
                    selected = currentMode == MusicAppearance.EQ_SURROUND,
                    onClick = { selectEq(MusicAppearance.EQ_SURROUND, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_custom),
                    selected = currentMode == MusicAppearance.EQ_CUSTOM,
                    onClick = { selectEq(MusicAppearance.EQ_CUSTOM, onEqModeSelected) },
                )
                if (currentMode == MusicAppearance.EQ_CUSTOM && eqBandCount > 0) {
                    // Per-band sliders for the custom curve. Dragging a
                    // slider persists the levels and re-applies them to
                    // the playing player through the callback right away.
                    for (band in 0 until eqBandCount) {
                        val levelMb = eqCustomLevels.getOrElse(band) { 0 }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Slider(
                                value = levelMb.toFloat(),
                                onValueChange = { value ->
                                    val updated = (0 until eqBandCount).map { i ->
                                        if (i == band) {
                                            value.roundToInt()
                                        } else {
                                            eqCustomLevels.getOrElse(i) { 0 }
                                        }
                                    }
                                    MusicAppearance.updateEqCustomLevels(updated)
                                    onEqCustomLevelsChanged(updated)
                                },
                                valueRange = eqBandMinMb.toFloat()..eqBandMaxMb.toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatEqDb(levelMb),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.music_playback_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var backgroundPlay by remember { mutableStateOf(MusicPlayPrefs.backgroundPlay) }
            PlaybackToggleRow(
                title = stringResource(R.string.music_background_play),
                desc = stringResource(
                    if (backgroundPlay) {
                        R.string.music_background_play_on
                    } else {
                        R.string.music_background_play_off
                    },
                ),
                checked = backgroundPlay,
                onCheckedChange = {
                    backgroundPlay = it
                    MusicPlayPrefs.backgroundPlay = it
                },
            )
            var floatingMini by remember { mutableStateOf(MusicPlayPrefs.floatingMiniPlayer) }
            val overlayHintMsg = stringResource(R.string.music_floating_mini_permission)
            PlaybackToggleRow(
                title = stringResource(R.string.music_floating_mini),
                desc = stringResource(R.string.music_floating_mini_desc),
                checked = floatingMini,
                onCheckedChange = { enabled ->
                    floatingMini = enabled
                    MusicPlayPrefs.floatingMiniPlayer = enabled
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        // The pref stays on; the disc appears once the
                        // system grants the overlay permission, so send
                        // him straight to its settings page.
                        Toast.makeText(context, overlayHintMsg, Toast.LENGTH_LONG).show()
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (t: Throwable) {
                            // No settings page to open on this ROM.
                        }
                    }
                },
            )
            // Reference-player settings (v1.23.0, Torres's LX
            // screenshots): preferred quality, resume position and
            // the audio-focus behaviour.
            var quality by remember { mutableStateOf(MusicPlayPrefs.quality) }
            Text(
                text = stringResource(R.string.music_quality_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (option in listOf("128k", "320k", "flac", "flac24bit")) {
                    FilterChip(
                        selected = quality == option,
                        onClick = {
                            quality = option
                            MusicPlayPrefs.quality = option
                        },
                        label = { Text(option) },
                    )
                }
            }
            var rememberProgress by remember {
                mutableStateOf(MusicPlayPrefs.rememberProgress)
            }
            PlaybackToggleRow(
                title = stringResource(R.string.music_remember_progress),
                desc = stringResource(R.string.music_remember_progress_desc),
                checked = rememberProgress,
                onCheckedChange = {
                    rememberProgress = it
                    MusicPlayPrefs.rememberProgress = it
                },
            )
            var focusPause by remember {
                mutableStateOf(MusicPlayPrefs.audioFocusPause)
            }
            PlaybackToggleRow(
                title = stringResource(R.string.music_audio_focus_pause),
                desc = stringResource(R.string.music_audio_focus_desc),
                checked = focusPause,
                onCheckedChange = {
                    focusPause = it
                    MusicPlayPrefs.audioFocusPause = it
                },
            )
        }
    }
}

private fun selectEq(mode: String, onEqModeSelected: (String) -> Unit) {
    MusicAppearance.updateEqMode(mode)
    onEqModeSelected(mode)
}

/** Millibels as a signed dB label for the custom EQ sliders ("+3.0 dB"). */
private fun formatEqDb(levelMb: Int): String {
    val db = levelMb / 100f
    val sign = if (db > 0f) "+" else ""
    return sign + String.format(java.util.Locale.US, "%.1f", db) + " dB"
}

/** One switch row of the playback section (title + state description). */
@Composable
private fun PlaybackToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EqOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "● " else "○ ",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
