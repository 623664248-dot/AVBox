package com.github.avbox.osc.ui.page

import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.avbox.osc.R
import java.io.File

private val HeaderCardShape = RoundedCornerShape(32.dp)

/** Warm paper tone of the ink-wash brand card (v1.35.0). */
private val PaperColor = Color(0xFFF6F1E7)

/** Ink black for the calligraphy on the brand card. */
private val InkColor = Color(0xFF1B1B1B)

/** Seal-stamp red of the small 靖 stamp on the brand card. */
private val SealRed = Color(0xFFB23A2E)

/**
 * Ma Shan Zheng calligraphy, subset to the brand glyphs
 * (~49 KB of the OFL-licensed full font) — it only ever
 * renders the owner's name, the card's tagline and the
 * seal character. The subset TTF ships as a base64 asset
 * (assets/jingfeiyang_ma.b64) and is decoded once into
 * filesDir/fonts; any failure falls back to the default
 * font family so the card can never break over a font.
 */
@Composable
private fun rememberCalligraphyFamily(): FontFamily {
    val context = LocalContext.current
    return remember {
        try {
            val dir = File(context.filesDir, "fonts")
            dir.mkdirs()
            val file = File(dir, "jingfeiyang_ma.ttf")
            if (!file.exists() || file.length() == 0L) {
                val b64 = context.assets.open("jingfeiyang_ma.b64")
                    .bufferedReader()
                    .readText()
                    .replace(Regex("\\s"), "")
                file.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            }
            FontFamily(Font(file))
        } catch (t: Throwable) {
            FontFamily.Default
        }
    }
}

/**
 * The 我的 page header card (v1.35.0): Torres asked for his
 * own mark instead of the upstream AudioVideoBox card, and
 * picked the ink-calligraphy design from the mockups — his
 * name 靖飞扬 in brush calligraphy on a paper-toned card
 * with a red seal stamp, the tagline under the name, and
 * two ink-wash blobs drifting very slowly behind it (the
 * "dynamic" touch from the animated mockup, kept subtle).
 * The app-version pill he asked to drop is gone; the
 * version still lives in the About sheet.
 * v1.35.1: his screenshot showed the card stretched to
 * over twice its height and the drift invisible — the
 * ink blobs now live in a matchParentSize layer and
 * drift wider and deeper.
 * v1.35.2: his recording showed the height fixed but
 * the wash still frozen — the drift had been written
 * in raw pixels (~9 dp of travel). It is now computed
 * in dp, and the blobs also scale-breathe against each
 * other so the motion is unmistakable.
 */
@Composable
internal fun AppInfoHeaderCard() {
    val calligraphy = rememberCalligraphyFamily()
    val driftTransition = rememberInfiniteTransition(label = "inkDrift")
    // Drift values are in dp (v1.35.2): graphicsLayer
    // translations are raw pixels, and v1.35.1's ±26 px was
    // only ~9 dp on Torres's high-density screen — on a soft
    // radial wash that is effectively invisible, as his
    // recording proved. The graphicsLayer block converts
    // dp to px itself (its scope is a Density).
    val drift by driftTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkDriftX",
    )
    val driftY by driftTransition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkDriftY",
    )
    val inkPulse by driftTransition.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkPulse",
    )
    val inkScale by driftTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeaderCardShape)
            .background(PaperColor),
    ) {
        // Ink layer (v1.35.1): matchParentSize keeps the blobs
        // from sizing the card — in v1.35.0 they were direct
        // children, so the 240 dp blob stretched the card to
        // over twice its intended height. The drift is also
        // wider, faster and deeper than v1.35.0's, whose wash
        // was too faint to see moving.
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        translationX = 30.dp.toPx() + drift.dp.toPx()
                        translationY = (-70).dp.toPx() + driftY.dp.toPx()
                        scaleX = inkScale
                        scaleY = inkScale
                    }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                InkColor.copy(alpha = inkPulse),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .align(Alignment.BottomStart)
                    .graphicsLayer {
                        translationX = (-40).dp.toPx() - drift.dp.toPx()
                        translationY = 60.dp.toPx() - driftY.dp.toPx()
                        scaleX = 2.04f - inkScale
                        scaleY = 2.04f - inkScale
                    }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                InkColor.copy(alpha = inkPulse * 0.7f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_brand_name),
                    fontFamily = calligraphy,
                    fontSize = 42.sp,
                    lineHeight = 50.sp,
                    color = InkColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_brand_tagline),
                    fontFamily = calligraphy,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = InkColor.copy(alpha = 0.72f),
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer { rotationZ = -5f }
                    .clip(RoundedCornerShape(8.dp))
                    .background(SealRed),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_brand_seal),
                    fontFamily = calligraphy,
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    color = Color.White,
                )
            }
        }
    }
}
