package com.github.avbox.osc.ui.page

import android.util.Base64
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * v1.35.3: his second recording STILL showed a frozen
 * wash, so the soft-gradient approach is abandoned as
 * the motion carrier. All animated values are now read
 * in composition and applied through layout offsets
 * (no graphicsLayer animation at all), the blobs are
 * darker, and a wide ink band sweeps across the whole
 * card every ~5.6 s — motion nobody can miss, and a
 * visible signature of the new build.
 */
@Composable
internal fun AppInfoHeaderCard() {
    val calligraphy = rememberCalligraphyFamily()
    val anim = rememberInfiniteTransition(label = "inkCard")
    // Blob drift, in dp, applied via Modifier.offset
    // (layout) — read in composition on purpose (v1.35.3).
    val drift by anim.animateFloat(
        initialValue = -16f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkDriftX",
    )
    val inkPulse by anim.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inkPulse",
    )
    // Sweep: crosses the card in the first 1.8 s of each
    // 5.6 s cycle, then rests off-card (v1.35.3).
    val sweep by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5600
                0f at 0
                1f at 1800
                1f at 5600
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "inkSweep",
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeaderCardShape)
            .background(PaperColor),
    ) {
        val cardWidthDp = maxWidth.value
        // Ink layer (v1.35.1): matchParentSize keeps the
        // decorations from sizing the card.
        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (30f + drift).dp, y = (-70).dp)
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
                    .offset(x = (-40f - drift).dp, y = 60.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                InkColor.copy(alpha = inkPulse * 0.7f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            // The sweeping ink band (v1.35.3): a soft dark
            // band, tilted like a brush stroke, travelling
            // the full width of the card.
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .offset(x = (-130f + sweep * (cardWidthDp + 260f)).dp)
                    .graphicsLayer { rotationZ = -10f }
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                InkColor.copy(alpha = 0.16f),
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
