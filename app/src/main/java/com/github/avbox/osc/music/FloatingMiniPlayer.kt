package com.github.avbox.osc.music

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.target.Target
import coil3.toBitmap
import com.github.avbox.osc.R
import com.github.avbox.osc.util.AppForeground
import com.github.avbox.osc.util.KV
import kotlin.math.abs

/**
 * The round floating mini player (v1.20.0): a 58dp disc that hovers
 * over OTHER apps while music plays, mirroring the reference
 * player's "全局迷你播放器". Shows only when the pref is on, the
 * system overlay permission is granted, the app itself is in the
 * background (the in-app mini bar covers the foreground case) and
 * a track is loaded. The disc spins while playing; a corner badge
 * shows play/pause; a single tap toggles play/pause, a double tap
 * opens the player page, drag moves it (the position is
 * remembered).
 *
 * Hosted from [LxPlaybackService], the component that outlives
 * every activity while music plays. Every window operation is
 * wrapped: an overlay failure must never take playback down.
 */
object FloatingMiniPlayer {

    private const val KEY_X = "lx_floating_mini_x"
    private const val KEY_Y = "lx_floating_mini_y"
    private const val SIZE_DP = 58

    /** Single/double tap disambiguation window on the disc. */
    private const val DOUBLE_TAP_MS = 280L

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var coverView: ImageView? = null
    private var badgeView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var rotationAnimator: ObjectAnimator? = null
    private var shownCoverKey: String? = null

    /** Re-evaluate visibility and content from the current state. */
    fun sync(context: Context) {
        try {
            val np = NowPlayingState.current()
            val shouldShow = MusicPlayPrefs.floatingMiniPlayer &&
                !AppForeground.inForeground &&
                np != null &&
                Settings.canDrawOverlays(context)
            if (!shouldShow) {
                hide(context)
                return
            }
            if (rootView == null) create(context)
            val root = rootView ?: return
            if (root.parent == null) {
                windowManager?.addView(root, layoutParams)
            }
            val coverKey = (np?.trackKey ?: "") + "|" + (np?.img ?: "")
            if (coverKey != shownCoverKey) {
                shownCoverKey = coverKey
                loadCover(context, np?.img)
            }
            updateState(np?.isPlaying == true)
        } catch (t: Throwable) {
            hide(context)
        }
    }

    /** Remove the disc (pref off, app foregrounded, service dying…). */
    fun hide(context: Context) {
        try {
            rotationAnimator?.cancel()
            rotationAnimator = null
            val root = rootView
            if (root != null && root.parent != null) {
                windowManager?.removeView(root)
            }
        } catch (t: Throwable) {
            // Already detached.
        }
        rootView = null
        coverView = null
        badgeView = null
        layoutParams = null
        shownCoverKey = null
    }

    private fun create(context: Context) {
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = appContext.resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()

        val root = FrameLayout(appContext)
        val cover = ImageView(appContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(0xFF23232B.toInt())
        }
        root.addView(cover, FrameLayout.LayoutParams(sizePx, sizePx))

        val badgeSize = (22 * density).toInt()
        val badge = ImageView(appContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.player_ic_pause)
        }
        root.addView(
            badge,
            FrameLayout.LayoutParams(
                badgeSize,
                badgeSize,
                Gravity.BOTTOM or Gravity.END,
            ),
        )
        root.setOnTouchListener(DragTouchListener())
        rootView = root
        coverView = cover
        badgeView = badge

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val metrics = appContext.resources.displayMetrics
        val defaultX = metrics.widthPixels - sizePx - (12 * density).toInt()
        val defaultY = metrics.heightPixels * 35 / 100
        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = KV.get(KEY_X, "").toIntOrNull() ?: defaultX
            y = KV.get(KEY_Y, "").toIntOrNull() ?: defaultY
        }
    }

    private fun loadCover(context: Context, url: String?) {
        val cover = coverView ?: return
        if (url.isNullOrBlank()) {
            cover.setImageBitmap(null)
            return
        }
        try {
            val request = ImageRequest.Builder(context.applicationContext)
                .data(url)
                .target(
                    object : Target {
                        override fun onSuccess(result: Image) {
                            try {
                                coverView?.setImageBitmap(result.toBitmap())
                            } catch (t: Throwable) {
                                // Keep the plain disc.
                            }
                        }
                    },
                )
                .build()
            SingletonImageLoader.get(context.applicationContext).enqueue(request)
        } catch (t: Throwable) {
            // Cover is decoration; never fail over it.
        }
    }

    private fun updateState(playing: Boolean) {
        badgeView?.setImageResource(
            if (playing) R.drawable.player_ic_pause else R.drawable.player_ic_play,
        )
        val cover = coverView ?: return
        if (playing) {
            var animator = rotationAnimator
            if (animator == null || animator.target !== cover) {
                animator?.cancel()
                animator = ObjectAnimator.ofFloat(cover, View.ROTATION, 0f, 360f).apply {
                    duration = 12_000
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
                rotationAnimator = animator
            }
            if (!animator.isStarted) {
                animator.start()
            } else if (animator.isPaused) {
                animator.resume()
            }
        } else {
            rotationAnimator?.pause()
        }
    }

    /** Double-tap on the disc: bring the player page up, attached. */
    private fun openPlayerPage(context: Context) {
        try {
            val intent = Intent().setComponent(
                ComponentName(
                    context,
                    "com.github.avbox.osc.ui.music.LxMusicPlayerActivity",
                ),
            ).putExtra("lx_attach", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            // No player page to open.
        }
    }

    private fun togglePlayPause() {
        try {
            val np = NowPlayingState.current() ?: return
            if (np.serviceBacked) {
                val player = MusicServiceConnection.connectedService()?.player ?: return
                if (player.isPlaying) player.pause() else player.play()
            } else {
                NowPlayingState.localControls?.playPause()
            }
        } catch (t: Throwable) {
            // Controls are best-effort from the overlay.
        }
    }

    /**
     * Drag to move (position persisted). Taps are disambiguated like
     * everywhere else: a single tap toggles play/pause after a short
     * window, a second tap inside that window opens the player page
     * instead (v1.21.0, Torres).
     */
    private class DragTouchListener : View.OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var pendingToggle: Runnable? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        private fun onTap(view: View) {
            val pending = pendingToggle
            if (pending != null) {
                handler.removeCallbacks(pending)
                pendingToggle = null
                openPlayerPage(view.context)
            } else {
                val toggle = Runnable {
                    pendingToggle = null
                    togglePlayPause()
                }
                pendingToggle = toggle
                handler.postDelayed(toggle, DOUBLE_TAP_MS)
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val lp = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 6 || abs(dy) > 6) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    try {
                        windowManager?.updateViewLayout(rootView, lp)
                    } catch (t: Throwable) {
                        // View already gone.
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        KV.put(KEY_X, lp.x.toString())
                        KV.put(KEY_Y, lp.y.toString())
                    } else {
                        onTap(view)
                    }
                    return true
                }
            }
            return false
        }
    }
}
