@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.github.avbox.osc.ui.music

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

    private const val BG_DIR_NAME = "music_bg"

    const val EQ_OFF = "off"
    const val EQ_BASS = "bass"
    const val EQ_SURROUND = "surround"
    const val EQ_PRESET_PREFIX = "preset:"

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
    val model: Any? = if (customPath.isNotBlank()) File(customPath) else fallbackModel
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFailedMsg = stringResource(R.string.music_appearance_pick_failed)
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
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
                    onClick = { pickLauncher.launch(arrayOf("image/*")) },
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
                    label = stringResource(R.string.music_eq_bass),
                    selected = currentMode == MusicAppearance.EQ_BASS,
                    onClick = { selectEq(MusicAppearance.EQ_BASS, onEqModeSelected) },
                )
                EqOptionRow(
                    label = stringResource(R.string.music_eq_surround),
                    selected = currentMode == MusicAppearance.EQ_SURROUND,
                    onClick = { selectEq(MusicAppearance.EQ_SURROUND, onEqModeSelected) },
                )
            }
        }
    }
}

private fun selectEq(mode: String, onEqModeSelected: (String) -> Unit) {
    MusicAppearance.updateEqMode(mode)
    onEqModeSelected(mode)
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
