package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.DefaultTimeBar
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    // Controller views (inside PlayerView controller_layout)
    private var exoBack: ImageButton? = null
    private var exoChannelName: TextView? = null
    private var exoPip: ImageButton? = null
    private var exoSettings: ImageButton? = null
    private var exoMute: ImageButton? = null
    private var exoLock: ImageButton? = null
    private var exoAspectRatio: ImageButton? = null
    private var exoPlayPause: ImageButton? = null
    private var exoRewind: ImageButton? = null
    private var exoForward: ImageButton? = null
    private var exoFullscreen: ImageButton? = null

    // Seekbar/time (optional)
    private var timeBar: DefaultTimeBar? = null
    private var positionText: TextView? = null
    private var durationText: TextView? = null

    // Lock overlay in activity layout
    private var lockOverlay: FrameLayout? = null
    private var unlockButton: ImageButton? = null

    // State
    private var isFullscreen = false
    private var isLocked = false
    private var isMuted = false
    private var currentAspectRatioIndex = 0

    private val aspectRatios = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

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

        // Keep screen on & immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // Start in portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        // set up UI wiring first (so controller clicks will work when player attaches)
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()

        // Then setup player (it will attach to binding.playerView)
        setupPlayer()
    }

    private fun setupPlayer() {
        try {
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("LiveTVPro/1.0")
                                .setConnectTimeoutMs(30_000)
                                .setReadTimeoutMs(30_000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build().also { exo ->
                    binding.playerView.player = exo

                    // PlayerView config
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    // Prepare media
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.isVisible = true
                                    binding.errorView.isVisible = false
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.isVisible = false
                                    binding.errorView.isVisible = false
                                    updatePlayPauseIcon()
                                    // When ready, ensure top controls anchored at top (no layout changes needed here,
                                    // but you might animate or adjust spacing if you changed layout at runtime)
                                }
                                Player.STATE_ENDED -> {
                                    showError("Stream ended")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            binding.progressBar.isVisible = false
                            val message = when (error.errorCode) {
                                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server error"
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network error"
                                else -> "Playback failed"
                            }
                            showError(message)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon()
                        }
                    })
                }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // PlayerView holds the controller layout; find controller views from it
        val pv = binding.playerView

        // Buttons & texts inside controller layout
        exoBack = pv.findViewById(R.id.exo_back)
        exoChannelName = pv.findViewById(R.id.exo_channel_name)
        exoPip = pv.findViewById(R.id.exo_pip)
        exoSettings = pv.findViewById(R.id.exo_settings)
        exoMute = pv.findViewById(R.id.exo_volume)
        exoLock = pv.findViewById(R.id.exo_lock)
        exoAspectRatio = pv.findViewById(R.id.exo_aspect_ratio)
        exoRewind = pv.findViewById(R.id.exo_rewind)
        exoPlayPause = pv.findViewById(R.id.exo_play_pause) // center big play/pause
        exoForward = pv.findViewById(R.id.exo_forward)
        exoFullscreen = pv.findViewById(R.id.exo_fullscreen)

        // Seek/time widgets
        timeBar = pv.findViewById(R.id.exo_progress)
        positionText = pv.findViewById(R.id.exo_position)
        durationText = pv.findViewById(R.id.exo_duration)

        // Lock overlay (activity layout)
        lockOverlay = findViewById(R.id.lock_overlay)
        unlockButton = findViewById(R.id.unlock_button)

        // Channel title
        exoChannelName?.text = channel.name

        // Back
        exoBack?.setOnClickListener {
            if (isFullscreen) exitFullscreen() else finish()
        }

        // PiP - ensure Android O+ and feature present
        exoPip?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                visibility = View.VISIBLE
                setOnClickListener {
                    // hide unrelated UI before entering PiP so PiP shows just the video
                    binding.relatedChannelsSection.visibility = View.GONE
                    binding.playerView.useController = false
                    enterPipMode()
                }
            } else {
                visibility = View.GONE
            }
        }

        // Settings (placeholder)
        exoSettings?.setOnClickListener {
            // TODO: show settings dialog (quality/speed)
        }

        // Mute toggle
        exoMute?.setOnClickListener {
            toggleMute()
        }

        // Lock: show overlay & disable controller
        exoLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            lockOverlay?.visibility = View.VISIBLE
            // optionally disable lock button until unlocked
            exoLock?.isEnabled = false
        }

        // Unlock
        unlockButton?.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            lockOverlay?.visibility = View.GONE
            exoLock?.isEnabled = true
        }

        // Aspect ratio cycle (we'll only allow in landscape - but clicking cycles for now)
        exoAspectRatio?.setOnClickListener {
            cycleAspectRatio()
        }

        // Rewind / Forward (15s)
        exoRewind?.setOnClickListener {
            player?.let {
                val newPos = (it.currentPosition - 15_000L).coerceAtLeast(0L)
                it.seekTo(newPos)
            }
        }
        exoForward?.setOnClickListener {
            player?.let {
                val duration = if (it.duration > 0) it.duration else Long.MAX_VALUE
                val newPos = (it.currentPosition + 15_000L).coerceAtMost(duration)
                it.seekTo(newPos)
            }
        }

        // Play/pause (center)
        exoPlayPause?.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else it.play()
            }
        }

        // Fullscreen toggle
        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        // Retry in error view
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            setupPlayer() // recreate player
        }

        // Optional: wire timeBar to seek if you want simple behaviour
        timeBar?.addListener(object : DefaultTimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: DefaultTimeBar, position: Long) { /* no-op */ }
            override fun onScrubMove(timeBar: DefaultTimeBar, position: Long) { /* no-op */ }
            override fun onScrubStop(timeBar: DefaultTimeBar, position: Long, canceled: Boolean) {
                player?.seekTo(position)
            }
        })

        // When player becomes available, we will update play/pause drawable (done in listener)
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            if (!isLocked) switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        viewModel.relatedChannels.observe(this) { channels ->
            relatedChannelsAdapter.submitList(channels)
            binding.relatedCount.text = channels.size.toString()
            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        channel = newChannel
        exoChannelName?.text = channel.name

        player?.stop()
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true

        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Hide related channels and expand player container to full
        binding.relatedChannelsSection.visibility = View.GONE

        val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = null
        params.bottomToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerContainer.layoutParams = params

        // Update icon (ensure drawable exists)
        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        binding.relatedChannelsSection.visibility = View.VISIBLE

        val params = binding.playerContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = "16:9"
        params.bottomToTop = R.id.related_channels_section
        params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
        binding.playerContainer.layoutParams = params

        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen)
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            exoMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    private fun cycleAspectRatio() {
        // Optionally only allow when landscape; currently cycles regardless.
        currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
        binding.playerView.resizeMode = aspectRatios[currentAspectRatioIndex]
    }

    private fun updatePlayPauseIcon() {
        player?.let {
            val playing = it.isPlaying
            // Update both center and bottom play/pause buttons if present
            exoPlayPause?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            // If you have a bottom smaller play button use id exo_play_pause_bottom (not required)
            val bottomPlay: ImageButton? = binding.playerView.findViewById(R.id.exo_play_pause_bottom)
            bottomPlay?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspect = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // In PiP: hide controls so PiP window only shows the video surface
            binding.relatedChannelsSection.visibility = View.GONE
            binding.playerView.useController = false
        } else {
            // Restore UI when exiting PiP
            if (!isFullscreen) binding.relatedChannelsSection.visibility = View.VISIBLE
            binding.playerView.useController = true
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        // If in PiP we keep the player; otherwise release to free resources
        if (!isInPictureInPictureMode) {
            player?.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
