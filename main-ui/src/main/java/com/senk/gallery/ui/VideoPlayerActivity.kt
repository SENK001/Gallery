package com.senk.gallery.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.senk.gallery.ui.gl.GlVideoView
import com.senk.gallery.util.DateFormats
import com.senk.gallery.util.SystemBarUtils

class VideoPlayerActivity : AppCompatActivity(), GlVideoView.Listener {

    private lateinit var rootView: View
    private lateinit var videoView: GlVideoView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var controls: View
    private lateinit var playPause: ImageButton
    private lateinit var rotateButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeCurrent: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var tracking = false
    private var barsVisible = true
    private var totalDurationMs = 0L
    private var controlsBaseTop = 0
    private var controlsBaseBottom = 0

    private val hideBarsRunnable = Runnable { setBarsVisible(false) }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!tracking && videoView.duration() > 0L) {
                val position = videoView.currentPosition().toInt()
                seekBar.progress = position
                updateTimeText(position.toLong())
            }
            handler.postDelayed(this, PROGRESS_INTERVAL)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_video_player)
        SystemBarUtils.applyLightBackgroundAppearance(this, lightBackground = false)
        rootView = findViewById(R.id.player_root)
        videoView = findViewById(R.id.video_view)
        toolbar = findViewById(R.id.toolbar)
        controls = findViewById(R.id.controls)
        controlsBaseTop = controls.paddingTop
        controlsBaseBottom = controls.paddingBottom
        playPause = findViewById(R.id.play_pause)
        rotateButton = findViewById(R.id.action_rotate)
        seekBar = findViewById(R.id.seek_bar)
        timeCurrent = findViewById(R.id.time_current)
        toolbar.title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        toolbar.setNavigationOnClickListener { finish() }
        applyInsets()
        hideSystemBars()
        videoView.isClickable = true
        videoView.setOnClickListener { toggleBars() }
        playPause.setOnClickListener { togglePlayback() }
        rotateButton.setOnClickListener {
            scheduleAutoHide()
            toggleOrientation()
        }
        updateRotateIcon()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateTimeText(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                tracking = true
                handler.removeCallbacks(hideBarsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                videoView.seekTo(seekBar.progress.toLong())
                scheduleAutoHide()
            }
        })
        videoView.listener = this
        intent.getStringExtra(EXTRA_URI)?.let { uriString ->
            videoView.setVideoUri(Uri.parse(uriString))
        }
        handler.post(progressUpdater)
    }

    override fun onResume() {
        super.onResume()
        videoView.onResume()
        hideSystemBars()
        updatePlayIcon(videoView.isPlaying())
        updateKeepScreenOn(videoView.isPlaying())
        scheduleAutoHide()
    }

    override fun onPause() {
        handler.removeCallbacks(hideBarsRunnable)
        updateKeepScreenOn(false)
        videoView.pause()
        updatePlayIcon(false)
        videoView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(hideBarsRunnable)
        videoView.release()
        super.onDestroy()
    }

    override fun onPrepared(durationMs: Long) {
        totalDurationMs = durationMs
        seekBar.max = durationMs.toInt().coerceAtLeast(1)
        updateTimeText(videoView.currentPosition())
        updatePlayIcon(videoView.isPlaying())
        updateKeepScreenOn(videoView.isPlaying())
        scheduleAutoHide()
    }

    override fun onCompletion() {
        updatePlayIcon(false)
        updateKeepScreenOn(false)
        seekBar.progress = 0
        updateTimeText(0L)
        setBarsVisible(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotateIcon()
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun togglePlayback() {
        if (videoView.isPlaying()) {
            videoView.pause()
            updatePlayIcon(false)
            updateKeepScreenOn(false)
            setBarsVisible(true)
            handler.post { scheduleAutoHide() }
        } else {
            videoView.start()
            updatePlayIcon(true)
            updateKeepScreenOn(true)
            handler.post { scheduleAutoHide() }
        }
    }

    private fun updateKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updatePlayIcon(playing: Boolean) {
        playPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow,
        )
    }

    private fun updateTimeText(positionMs: Long) {
        timeCurrent.text = getString(
            R.string.gallery_time_format,
            DateFormats.formatDuration(positionMs),
            DateFormats.formatDuration(totalDurationMs),
        )
    }

    private fun updateRotateIcon() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        rotateButton.setImageResource(
            if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
        )
    }

    private fun toggleOrientation() {
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            toolbar.setPadding(0, bars.top, 0, 0)
            controls.setPadding(
                0,
                controlsBaseTop,
                0,
                controlsBaseBottom + bars.bottom,
            )
            insets
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleBars() {
        setBarsVisible(!barsVisible)
    }

    private fun setBarsVisible(visible: Boolean) {
        barsVisible = visible
        toolbar.isVisible = visible
        controls.isVisible = visible
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideBarsRunnable)
        if (barsVisible && videoView.isPlaying()) {
            handler.postDelayed(hideBarsRunnable, AUTO_HIDE_DELAY)
        }
    }

    companion object {

        private const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val PROGRESS_INTERVAL = 500L
        private const val AUTO_HIDE_DELAY = 5000L

        fun intent(context: Context, uri: Uri, title: CharSequence?): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title?.toString())
    }
}
