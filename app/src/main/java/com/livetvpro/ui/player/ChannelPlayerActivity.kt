// app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import androidx.recyclerview.widget.RecyclerView
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

    // exact controller ids from your controller XML
    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null

    private var txtPosition: TextView? = null
    private var txtDuration: TextView? = null
    private var timeBar: TimeBar? = null

    private var isInPipMode = false
    private var isLocked = false
    private var lastVolume = 1f
    private val skipMs = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val updateIntervalMs = 500L
    private val positionUpdater = object : Runnable {
        override fun run() {
            try {
                val p = player
                if (p != null && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED) {
                    val pos = p.currentPosition
                    val dur = p.duration.takeIf { it > 0 } ?: 0L
                    txtPosition?.text = formatTime(pos)
                    txtDuration?.text = formatTime(dur)
                    timeBar?.let { tb ->
                        if (dur > 0L) {
                            tb.setPosition(pos)
                            tb.setBufferedPosition(p.bufferedPosition)
                            tb.setDuration(dur)
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "positionUpdater")
            } finally {
                mainHandler.postDelayed(this, updateIntervalMs)
            }
        }
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    // --------------------
    // lifecycle
    // --------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // anchor player container to top and enforce a 16:9 height to avoid vertical-centering issues
        val screenWidth = resources.displayMetrics.widthPixels
        val expected16by9Height = (screenWidth * 9f / 16f).toInt()
        val containerParams = binding.playerContainer.layoutParams
        if (containerParams is ConstraintLayout.LayoutParams) {
            containerParams.height = expected16by9Height
            containerParams.dimensionRatio = null
            containerParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            containerParams.topMargin = 0
            binding.playerContainer.layoutParams = containerParams
        } else {
            containerParams.height = expected16by9Height
            binding.playerContainer.layoutParams = containerParams
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        bindControllerViewsExact()
        setupControlListenersExact()
        setupPlayerViewInteractions()

        mainHandler.post(positionUpdater)

        // runtime-safe show of related recycler (won't compile-time reference binding.relatedRecyclerView)
        findAndShowRelatedRecycler()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode) releasePlayer()
        mainHandler.removeCallbacks(positionUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isInPipMode) releasePlayer()
        mainHandler.removeCallbacks(positionUpdater)
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.pause()
                it.release()
            } catch (t: Throwable) {
                Timber.w(t, "releasePlayer")
            }
        }
        player = null
    }

    // --------------------
    // ExoPlayer setup (single instance)
    // --------------------
    private fun setupPlayer() {
        player?.let {
            try { it.pause(); it.release() } catch (_: Throwable) {}
            player = null
        }

        try {
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this).setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setUserAgent("LiveTVPro/1.0")
                            .setConnectTimeoutMs(30_000)
                            .setReadTimeoutMs(30_000)
                            .setAllowCrossProtocolRedirects(true)
                    )
                ).build().also { exo ->
                    binding.playerView.player = exo
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true
                    exo.addListener(object : Player.Listener {})
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
        }

        binding.playerView.apply {
            useController = true
            isClickable = true
            isFocusable = true
            requestFocus()
            controllerShowTimeoutMs = 4000
            bringToFront()
        }

        binding.playerContainer.isClickable = false
        binding.playerContainer.isFocusable = false
    }

    // --------------------
    // Bind the exact ids in your controller file (safe runtime lookup)
    // --------------------
    private fun bindControllerViewsExact() {
        btnBack = binding.playerView.findViewById(R.id.exo_back)
        btnPip = binding.playerView.findViewById(R.id.exo_pip)
        btnSettings = binding.playerView.findViewById(R.id.exo_settings)
        btnMute = binding.playerView.findViewById(R.id.exo_mute)
        btnLock = binding.playerView.findViewById(R.id.exo_lock)
        btnRewind = binding.playerView.findViewById(R.id.exo_rewind)
        btnPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
        btnForward = binding.playerView.findViewById(R.id.exo_forward)
        btnFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)

        txtPosition = binding.playerView.findViewById(R.id.exo_position)
        txtDuration = binding.playerView.findViewById(R.id.exo_duration)

        // runtime-safe lookup for timebar id (handles @id/exo_progress case)
        val tbId = resources.getIdentifier("exo_progress", "id", packageName)
        timeBar = if (tbId != 0) {
            try { 
                binding.playerView.findViewById<TimeBar>(tbId)
            } catch (_: Throwable) { 
                null 
            }
        } else {
            null
        }

        // set icons using the exact drawable names present in your repo
        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnMute?.setImageResource(R.drawable.ic_volume_up)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_play)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)

        Timber.d(
            "bindExact: back=%s pip=%s settings=%s mute=%s lock=%s rew=%s playpause=%s forward=%s fullscreen=%s timebar=%s",
            btnBack != null, btnPip != null, btnSettings != null, btnMute != null, btnLock != null,
            btnRewind != null, btnPlayPause != null, btnForward != null, btnFullscreen != null, timeBar != null
        )
    }

    // --------------------
    // Wire actions for those exact ids
    // --------------------
    private fun setupControlListenersExact() {
        // Back
        btnBack?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            finish()
        }

        // PiP
        btnPip?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            enterPipMode()
        }

        // Settings (placeholder)
        btnSettings?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            Timber.d("Settings clicked")
        }

        // Mute toggle
        btnMute?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val cur = it.volume
                if (cur > 0f) {
                    lastVolume = cur
                    it.volume = 0f
                    btnMute?.setImageResource(R.drawable.ic_volume_off)
                } else {
                    it.volume = lastVolume.takeIf { v -> v > 0f } ?: 1f
                    btnMute?.setImageResource(R.drawable.ic_volume_up)
                }
            }
        }

        // Lock
        btnLock?.setOnClickListener {
            isLocked = !isLocked
            if (isLocked) {
                binding.playerView.useController = false
                binding.playerView.setOnTouchListener { _, _ -> true }
                btnLock?.setImageResource(R.drawable.ic_lock_closed)
            } else {
                binding.playerView.useController = true
                binding.playerView.setOnTouchListener(null)
                btnLock?.setImageResource(R.drawable.ic_lock_open)
            }
        }

        // Rewind
        btnRewind?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val pos = it.currentPosition
                it.seekTo((pos - skipMs).coerceAtLeast(0L))
            }
        }

        // Play/Pause single button
        btnPlayPause?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                if (it.playWhenReady) {
                    it.pause()
                    btnPlayPause?.setImageResource(R.drawable.ic_play)
                } else {
                    it.play()
                    btnPlayPause?.setImageResource(R.drawable.ic_pause)
                }
            }
        }

        // Forward
        btnForward?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val pos = it.currentPosition
                val duration = it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                it.seekTo((pos + skipMs).coerceAtMost(duration))
            }
        }

        // Fullscreen toggle (simple orientation switch)
        btnFullscreen?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            val isLandscape = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT
            if (!isLandscape) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                findAndHideRelatedRecycler()
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            } else {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                findAndShowRelatedRecycler()
                btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
            }
        }

        // TimeBar scrubbing (use correct TimeBar.OnScrubListener)
        timeBar?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {}
            override fun onScrubMove(timeBar: TimeBar, position: Long) {}
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                player?.seekTo(position)
            }
        })
    }

    // --------------------
    // PlayerView interactions
    // --------------------
    private fun setupPlayerViewInteractions() {
        binding.playerView.setOnClickListener {
            if (!isLocked) binding.playerView.showController()
        }

        binding.playerView.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                if (visibility == View.VISIBLE) binding.playerView.bringToFront()
            }
        })
    }

    // --------------------
    // PiP handling (hides related & controller)
    // --------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timber.w("PiP not supported on this device (SDK < O)")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Timber.w("PiP feature not available")
            return
        }

        try { findAndHideRelatedRecycler() } catch (_: Throwable) {}
        binding.playerView.useController = false

        val width = binding.playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = binding.playerContainer.height.takeIf { it > 0 } ?: (width * 9 / 16)
        val aspectRatio = Rational(width, height)

        val params = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
        isInPipMode = true
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            findAndHideRelatedRecycler()
            binding.playerView.useController = false
        } else {
            findAndShowRelatedRecycler()
            binding.playerView.useController = !isLocked
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
        }
    }

    // --------------------
    // Helpers to find/hide/show related RecyclerView safely at runtime
    // --------------------
    private fun findRelatedRecyclerView(): RecyclerView? {
        val names = listOf("related_recycler_view", "relatedRecyclerView", "related_recycler", "related_list")
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) {
                val v = try { findViewById<RecyclerView?>(id) } catch (_: Throwable) { null }
                if (v != null) return v
            }
        }
        return null
    }

    private fun findAndHideRelatedRecycler() {
        try {
            findRelatedRecyclerView()?.visibility = View.GONE
        } catch (_: Throwable) { }
    }

    private fun findAndShowRelatedRecycler() {
        try {
            findRelatedRecyclerView()?.visibility = View.VISIBLE
        } catch (_: Throwable) { }
    }

    // --------------------
    // Helpers
    // --------------------
    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "0:00"
        val totalSeconds = (millis / 1000).toInt()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
