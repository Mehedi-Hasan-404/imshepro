package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.recyclerview.widget.GridLayoutManager
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

    // controller views (nullable)
    private var exoBack: ImageButton? = null
    private var exoPip: ImageButton? = null
    private var exoMute: ImageButton? = null
    private var exoLock: ImageButton? = null
    private var exoAspectRatio: ImageButton? = null
    private var exoPlayPause: ImageButton? = null
    private var exoFullscreen: ImageButton? = null
    private var exoRewind: ImageButton? = null
    private var exoForward: ImageButton? = null

    // state
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

        // Set initial 16:9 height to prevent jumping (optional)
        binding.playerView.post {
            val screenWidth = resources.displayMetrics.widthPixels
            val height16by9 = (screenWidth * 9f / 16f).toInt()

            val playerParams = binding.playerView.layoutParams
            playerParams.height = height16by9
            binding.playerView.layoutParams = playerParams

            val containerParams = binding.playerContainer.layoutParams
            containerParams.height = height16by9
            binding.playerContainer.layoutParams = containerParams
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        // Ensure initial UI hidden states
        binding.progressBar.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.lockOverlay.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.relatedChannels.observe(this) { list ->
            relatedChannelsAdapter.submitList(list)
            binding.relatedChannelsSection.visibility =
                if (list.isNullOrEmpty()) View.GONE else View.VISIBLE
            binding.relatedCount.text = (list?.size ?: 0).toString()
        }
    }

    private fun updateResizeMode() {
        binding.playerView.resizeMode =
            if (isFullscreen) aspectRatios[currentAspectRatioIndex]
            else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
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
                    updateResizeMode()
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 5000

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    binding.errorView.visibility = View.GONE
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.visibility = View.GONE
                                    binding.errorView.visibility = View.GONE
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
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // find controller views inside PlayerView (ids defined in your controller layout)
        exoBack = binding.playerView.findViewById(com.livetvpro.R.id.exo_back)
        exoPip = binding.playerView.findViewById(com.livetvpro.R.id.exo_pip)
        exoMute = binding.playerView.findViewById(com.livetvpro.R.id.exo_mute)
        exoLock = binding.playerView.findViewById(com.livetvpro.R.id.exo_lock)
        exoAspectRatio = binding.playerView.findViewById(com.livetvpro.R.id.exo_aspect_ratio)
        exoPlayPause = binding.playerView.findViewById(com.livetvpro.R.id.exo_play_pause)
        exoFullscreen = binding.playerView.findViewById(com.livetvpro.R.id.exo_fullscreen)
        exoRewind = binding.playerView.findViewById(com.livetvpro.R.id.exo_rewind)
        exoForward = binding.playerView.findViewById(com.livetvpro.R.id.exo_forward)

        (binding.playerView.findViewById<View>(com.livetvpro.R.id.exo_channel_name) as? android.widget.TextView)
            ?.text = channel.name

        exoBack?.setOnClickListener {
            if (isFullscreen) exitFullscreen() else finish()
        }

        // PiP button wiring - will hide non-video UI then enter PiP
        exoPip?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                visibility = View.VISIBLE
                setOnClickListener { requestEnterPip() }
            } else {
                visibility = View.GONE
            }
        }

        exoMute?.setOnClickListener { toggleMute() }

        exoLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.VISIBLE
            binding.unlockButton.visibility = View.VISIBLE
            exoLock?.setImageResource(com.livetvpro.R.drawable.ic_lock_closed)
        }

        binding.unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            exoLock?.setImageResource(com.livetvpro.R.drawable.ic_lock_open)
        }

        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            binding.relatedChannelsSection.visibility = View.GONE
            exoFullscreen?.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            binding.relatedChannelsSection.visibility = View.VISIBLE
            exoFullscreen?.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        updateResizeMode()
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3, GridLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        binding.relatedChannelsSection.visibility =
            if (relatedChannelsAdapter.currentList.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        player?.release()
        player = null
        channel = newChannel

        (binding.playerView.findViewById<View>(com.livetvpro.R.id.exo_channel_name) as? android.widget.TextView)
            ?.text = channel.name

        setupPlayer()
    }

    /**
     * Entry point for PiP: hide non-video UI first, then enter PiP with PlayerView rect hint.
     */
    fun requestEnterPip() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Timber.w("PiP not available on this device")
            return
        }

        // 1) Hide non-video UI & disable controller auto-show
        hideNonVideoUiForPip()

        // 2) Post a tiny delay to ensure UI changes are applied before entering PiP.
        // This helps on some devices where immediate PiP entry still captures visible controller.
        binding.playerView.post {
            binding.playerView.postDelayed({
                // extra defensive hide just before PiP
                try {
                    binding.playerView.controllerAutoShow = false
                    binding.playerView.useController = false
                    binding.playerView.hideController()
                    // Defensive: hide internal exo controller view if present
                    val exoControllerId = com.google.android.exoplayer2.ui.R.id.exo_controller
                    binding.playerView.findViewById<View?>(exoControllerId)?.visibility = View.GONE
                } catch (t: Throwable) {
                    Timber.w(t, "Could not fully hide controller before PiP")
                }
                enterPipUsingPlayerViewRect()
            }, 50) // 50ms gives the UI a moment to settle; small enough to be imperceptible
        }
    }

    private fun hideNonVideoUiForPip() {
        // Hide anything that should not be visible in PiP
        binding.relatedChannelsSection.visibility = View.GONE
        binding.lockOverlay.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE

        // Disable auto-show and hide controller immediately
        binding.playerView.controllerAutoShow = false
        binding.playerView.useController = false
        binding.playerView.hideController()

        // Defensive: hide the internal ExoPlayer controller view by id (so it cannot auto-appear)
        try {
            val exoControllerId = com.google.android.exoplayer2.ui.R.id.exo_controller
            binding.playerView.findViewById<View?>(exoControllerId)?.visibility = View.GONE
        } catch (t: Throwable) {
            Timber.w(t, "Could not force-hide exo controller view")
        }
    }

    private fun restoreUiAfterPip() {
        binding.relatedChannelsSection.visibility = View.VISIBLE
        binding.lockOverlay.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE

        binding.playerView.controllerAutoShow = true
        binding.playerView.useController = !isLocked
    }

    private fun enterPipUsingPlayerViewRect() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // compute PlayerView rect in window coordinates
                val loc = IntArray(2)
                binding.playerView.getLocationInWindow(loc)
                val left = loc[0]
                val top = loc[1]
                val right = left + binding.playerView.width
                val bottom = top + binding.playerView.height
                val sourceRect = Rect(left, top, right, bottom)

                Timber.d("enterPipUsingPlayerViewRect: $sourceRect (w=${binding.playerView.width}, h=${binding.playerView.height})")

                // compute aspect ratio - prefer actual video size if available
                val videoWidth = player?.videoSize?.width ?: binding.playerView.width.takeIf { it > 0 } ?: 16
                val videoHeight = player?.videoSize?.height ?: binding.playerView.height.takeIf { it > 0 } ?: 9
                val aspect = Rational(videoWidth.coerceAtLeast(1), videoHeight.coerceAtLeast(1))

                val params = PictureInPictureParams.Builder()
                    .setSourceRectHint(sourceRect)
                    .setAspectRatio(aspect)
                    .build()

                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode")
                restoreUiAfterPip()
            }
        } else {
            try {
                enterPictureInPictureMode()
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode (pre-O)")
                restoreUiAfterPip()
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // In PiP: aggressively ensure only video is visible
            try {
                binding.playerView.controllerAutoShow = false
                binding.playerView.useController = false
                binding.playerView.hideController()
                val exoControllerId = com.google.android.exoplayer2.ui.R.id.exo_controller
                binding.playerView.findViewById<View?>(exoControllerId)?.visibility = View.GONE
            } catch (t: Throwable) {
                Timber.w(t, "Could not fully hide controller in PiP")
            }

            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            supportActionBar?.hide()
        } else {
            // Out of PiP: restore UI
            supportActionBar?.show()
            restoreUiAfterPip()
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            exoMute?.setImageResource(if (isMuted) com.livetvpro.R.drawable.ic_volume_off else com.livetvpro.R.drawable.ic_volume_up)
        }
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsSection.visibility = View.VISIBLE
        updateResizeMode()
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
        // Do not release player when entering PiP; release when appropriate for your flow.
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
