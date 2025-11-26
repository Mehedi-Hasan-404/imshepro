package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
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

    // Controller views
    private var exoBack: ImageButton? = null
    private var exoChannelName: View? = null
    private var exoPip: ImageButton? = null
    private var exoSettings: ImageButton? = null
    private var exoMute: ImageButton? = null
    private var exoLock: ImageButton? = null
    private var exoAspectRatio: ImageButton? = null
    private var exoPlayPause: ImageButton? = null
    private var exoFullscreen: ImageButton? = null
    private var exoRewind: ImageButton? = null
    private var exoForward: ImageButton? = null

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

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        
        // START IN PORTRAIT MODE - Fixed at top
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()
    }

    private fun setupPlayer() {
        try {
            player?.release()
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(
                            DefaultHttpDataSource.Factory()
                                .setUserAgent("LiveTVPro/1.0")
                                .setConnectTimeoutMs(30000)
                                .setReadTimeoutMs(30000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    binding.playerView.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
                                    updatePlayPauseIcon()
                                }
                                Player.STATE_ENDED -> showError("Stream ended")
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            binding.progressBar.visibility = View.GONE
                            val msg = when (error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server error"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network error"
                                else -> "Playback failed"
                            }
                            showError(msg)
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon()
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // Find controller views
        exoBack = binding.playerView.findViewById(R.id.exo_back)
        exoChannelName = binding.playerView.findViewById(R.id.exo_channel_name)
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoSettings = binding.playerView.findViewById(R.id.exo_settings)
        exoMute = binding.playerView.findViewById(R.id.exo_mute)
        exoLock = binding.playerView.findViewById(R.id.exo_lock)
        exoAspectRatio = binding.playerView.findViewById(R.id.exo_aspect_ratio)
        exoPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)
        exoRewind = binding.playerView.findViewById(R.id.exo_rewind)
        exoForward = binding.playerView.findViewById(R.id.exo_forward)

        (exoChannelName as? android.widget.TextView)?.text = channel.name

        // Aspect ratio hidden in portrait, visible in landscape
        exoAspectRatio?.visibility = View.GONE

        exoBack?.setOnClickListener { finish() }

        // PiP button
        exoPip?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                visibility = View.VISIBLE
                setOnClickListener { enterPipMode() }
            } else {
                visibility = View.GONE
            }
        }

        exoSettings?.setOnClickListener { /* TODO: settings */ }
        exoMute?.setOnClickListener { toggleMute() }

        // Lock functionality
        exoLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            binding.unlockButton.visibility = View.VISIBLE
            exoLock?.setImageResource(R.drawable.ic_lock_closed)
        }

        binding.lockOverlay.setOnClickListener {
            binding.unlockButton.visibility = if (binding.unlockButton.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            exoLock?.setImageResource(R.drawable.ic_lock_open)
        }

        exoAspectRatio?.setOnClickListener {
            currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
            binding.playerView.resizeMode = aspectRatios[currentAspectRatioIndex]
        }

        exoPlayPause?.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }

        exoRewind?.setOnClickListener {
            player?.let {
                val newPos = (it.currentPosition - 15_000L).coerceAtLeast(0L)
                it.seekTo(newPos)
            }
        }

        exoForward?.setOnClickListener {
            player?.let {
                val dur = if (it.duration > 0) it.duration else Long.MAX_VALUE
                val newPos = (it.currentPosition + 15_000L).coerceAtMost(dur)
                it.seekTo(newPos)
            }
        }

        // Fullscreen is DISABLED in this version - player stays at top
        exoFullscreen?.visibility = View.GONE

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            setupPlayer()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            if (!isLocked) switchChannel(relatedChannel)
        }
        
        // Use GridLayoutManager with 3 columns for portrait
        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        // Load all channels from same category (including M3U parsed channels)
        viewModel.loadAllChannelsFromCategory(channel.categoryId, channel.categoryName, channel.id)
        
        viewModel.relatedChannels.observe(this) { channels ->
            Timber.d("Loaded ${channels.size} related channels (excluding current)")
            relatedChannelsAdapter.submitList(channels)
            
            // Always show related channels section
            binding.relatedChannelsSection.visibility = View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        channel = newChannel
        (exoChannelName as? android.widget.TextView)?.text = channel.name

        player?.stop()
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true

        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        
        Timber.d("Switched to channel: ${channel.name}")
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            exoMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    private fun updatePlayPauseIcon() {
        player?.let { 
            exoPlayPause?.setImageResource(
                if (it.isPlaying) R.drawable.ic_pause 
                else R.drawable.ic_play
            ) 
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Hide controls before entering PiP
                binding.playerView.useController = false
                binding.playerView.hideController()
                
                binding.playerView.postDelayed({
                    try {
                        val videoWidth = player?.videoSize?.width ?: 16
                        val videoHeight = player?.videoSize?.height ?: 9
                        
                        val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
                            Rational(videoWidth, videoHeight)
                        } else {
                            Rational(16, 9)
                        }

                        val sourceRectHint = android.graphics.Rect()
                        binding.playerView.getGlobalVisibleRect(sourceRectHint)

                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(aspectRatio)
                            .setSourceRectHint(sourceRectHint)
                            .build()
                            
                        enterPictureInPictureMode(params)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to enter PiP mode")
                        Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
                        binding.playerView.useController = true
                    }
                }, 100)
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode")
                Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, 
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // Entered PiP - hide everything except player
            binding.playerView.useController = false
            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
        } else {
            // Exited PiP - restore UI
            binding.relatedChannelsSection.visibility = View.VISIBLE
            binding.playerView.useController = !isLocked
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun hideSystemUI() {
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
        if (!isInPictureInPictureMode) {
            player?.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
