// app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.media3.ui.AspectRatioFrameLayout
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
    private val updateIntervalMs = 1000L
    private var isUserScrubbing = false
    
    private val positionUpdater = object : Runnable {
        override fun run() {
            try {
                val p = player
                if (p != null && !isUserScrubbing && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED) {
                    val pos = p.currentPosition
                    val dur = p.duration.takeIf { it > 0 } ?: 0L
                    
                    txtPosition?.text = formatTime(pos)
                    txtDuration?.text = formatTime(dur)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set 16:9 aspect ratio for player container
        val screenWidth = resources.displayMetrics.widthPixels
        val expected16by9Height = (screenWidth * 9f / 16f).toInt()
        val containerParams = binding.playerContainer.layoutParams
        if (containerParams is ConstraintLayout.LayoutParams) {
            containerParams.height = expected16by9Height
            containerParams.dimensionRatio = "16:9"
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
        setupLockOverlay()

        mainHandler.post(positionUpdater)

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
                    
                    // KEY FIX: Set resize mode to FIT to prevent black bars
                    binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true
                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    if (exo.playWhenReady) {
                                        btnPlayPause?.setImageResource(R.drawable.ic_pause)
                                    } else {
                                        btnPlayPause?.setImageResource(R.drawable.ic_play)
                                    }
                                }
                            }
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
        }

        binding.playerView.apply {
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 5000
        }

        binding.playerContainer.isClickable = false
        binding.playerContainer.isFocusable = false
    }

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

        val tbId = resources.getIdentifier("exo_progress", "id", packageName)
        timeBar = if (tbId != 0) {
            try {
                binding.playerView.findViewById(tbId) as? TimeBar
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnMute?.setImageResource(R.drawable.ic_volume_up)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_pause)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            finish()
        }

        btnPip?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            enterPipMode()
        }

        btnSettings?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            Timber.d("Settings clicked")
        }

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

        btnLock?.setOnClickListener {
            toggleLock()
        }

        btnRewind?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val pos = it.currentPosition
                it.seekTo((pos - skipMs).coerceAtLeast(0L))
            }
        }

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

        btnForward?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val pos = it.currentPosition
                val duration = it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                it.seekTo((pos + skipMs).coerceAtMost(duration))
            }
        }

        btnFullscreen?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            toggleFullscreen()
        }

        timeBar?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                isUserScrubbing = true
            }
            
            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                txtPosition?.text = formatTime(position)
            }
            
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) {
                    player?.seekTo(position)
                }
                isUserScrubbing = false
            }
        })
    }

    private fun setupPlayerViewInteractions() {
        binding.playerView.setOnClickListener(null)
        binding.playerView.controllerAutoShow = true
        binding.playerView.useController = true
    }

    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener {
            toggleLock()
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
            binding.unlockButton.visibility = View.VISIBLE
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            binding.playerView.setOnTouchListener { _, _ -> true }
            Timber.d("Player LOCKED")
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            btnLock?.setImageResource(R.drawable.ic_lock_open)
            binding.playerView.setOnTouchListener(null)
            Timber.d("Player UNLOCKED")
        }
    }

    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        if (!isLandscape) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            findAndHideRelatedRecycler()
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            findAndShowRelatedRecycler()
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Timber.w("PiP not supported on this device (SDK < O)")
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Timber.w("PiP feature not available")
            return
        }

        try { 
            findAndHideRelatedRecycler() 
        } catch (_: Throwable) {}
        
        binding.playerView.useController = false
        
        // KEY FIX: Ensure resize mode is FIT before entering PiP
        binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        
        updatePipParams()
        isInPipMode = true
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            // FIXED: Always use 16:9 aspect ratio - don't change during resize
            val aspectRatio = Rational(16, 9)
            
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            
            if (isInPipMode) {
                // Update existing PiP params
                setPictureInPictureParams(params)
            } else {
                // Enter PiP mode
                enterPictureInPictureMode(params)
            }
            
            Timber.d("Updated PiP params with fixed 16:9 aspect ratio")
        } catch (e: Exception) {
            Timber.e(e, "Error updating PiP params")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        
        if (isInPictureInPictureMode) {
            // Entering PiP mode
            findAndHideRelatedRecycler()
            binding.playerView.useController = false
            
            // KEY FIX: Ensure resize mode is FIT in PiP
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            
            Timber.d("Entered PiP mode")
        } else {
            // FIXED: Exiting PiP mode - STOP and RELEASE player
            Timber.d("Exiting PiP mode - stopping playback")
            
            // Pause and release the player when PiP closes
            releasePlayer()
            
            // Finish the activity to close it completely
            finish()
        }
    }

    private fun findRelatedRecyclerView(): RecyclerView? {
        val names = listOf("related_recycler_view", "relatedRecyclerView", "related_recycler", "related_list", "relatedChannelsRecycler")
        for (n in names) {
            val id = resources.getIdentifier(n, "id", packageName)
            if (id != 0) {
                val v = try {
                    findViewById(id) as? RecyclerView
                } catch (_: Throwable) {
                    null
                }
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

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return "0:00"
        val totalSeconds = (millis / 1000).toInt()
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) 
               else String.format("%d:%02d", minutes, seconds)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
