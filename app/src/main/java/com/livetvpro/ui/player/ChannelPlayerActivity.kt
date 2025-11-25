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
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

        // --- Option A: force expected 16:9 height immediately so PlayerView + top controls start at top ---
        val screenWidth = resources.displayMetrics.widthPixels
        val expected16by9Height = (screenWidth * 9f / 16f).toInt()
        val containerParams = binding.playerContainer.layoutParams
        if (containerParams is ConstraintLayout.LayoutParams) {
            containerParams.height = expected16by9Height
            containerParams.dimensionRatio = null
            binding.playerContainer.layoutParams = containerParams
        } else {
            containerParams.height = expected16by9Height
            binding.playerContainer.layoutParams = containerParams
        }
        // -------------------------------------------------------------------------------------------

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
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

                    // prepare media item
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.playWhenReady = true
                    exoPlayer.prepare()

                    // Player listeners for state changes / errors
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
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

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            // optionally handle aspect ratio changes
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to setup player")
            showError("Failed to initialize player")
        }
    }

    private fun setupCustomControls() {
        // find controller views inside PlayerView (via binding)
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

        val lockOverlay = binding.lockOverlay
        val unlockButton = binding.unlockButton

        (exoChannelName as? android.widget.TextView)?.text = channel.name

        // ensure fullscreen button exists and is visible — copy to local val to avoid smart-cast issues
        val fullscreenBtn = exoFullscreen
        if (fullscreenBtn == null) {
            Timber.e("exo_fullscreen not found — check controller layout id and file")
        } else {
            fullscreenBtn.visibility = View.VISIBLE
        }

        // aspect hidden in portrait by default
        exoAspectRatio?.visibility = View.GONE

        exoBack?.setOnClickListener { if (isFullscreen) exitFullscreen() else finish() }

        exoPip?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                visibility = View.VISIBLE
                setOnClickListener { enterPipModeCompat() } // updated PiP entry
            } else visibility = View.GONE
        }

        exoSettings?.setOnClickListener { /* TODO: settings */ }

        exoMute?.setOnClickListener { toggleMute() }

        exoLock?.setOnClickListener {
            isLocked = true
            binding.playerView.useController = false
            lockOverlay.visibility = View.VISIBLE
            unlockButton.visibility = View.VISIBLE
            exoLock?.setImageResource(R.drawable.ic_lock_closed)
        }

        lockOverlay.setOnClickListener {
            // toggle unlock button visibility (quick peek)
            unlockButton.visibility = if (unlockButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            lockOverlay.visibility = View.GONE
            exoLock?.setImageResource(R.drawable.ic_lock_open)
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

        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        updateResizeMode()
    }

    private fun exitFullscreen() {
        isFullscreen = false
        updateResizeMode()
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            exoMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        exoPlayPause?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { ch ->
            ChannelPlayerActivity.start(this, ch)
        }
        binding.relatedChannelsRecycler.apply {
            layoutManager = GridLayoutManager(context, 4, GridLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelsAdapter
        }
    }

    private fun loadRelatedChannels() {
        // load from view model — keep your existing logic
        viewModel.relatedChannels.observe(this) { list ->
            relatedChannelsAdapter.submitList(list)
        }
    }

    // --- Robust PiP entry using PictureInPictureParams ---
    private fun enterPipModeCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {

            val w = binding.playerView.width
            val h = binding.playerView.height
            val aspect = if (w > 0 && h > 0) Rational(w, h) else Rational(16, 9)

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspect)
                .build()

            enterPictureInPictureMode(params)
        } else {
            Timber.d("PiP not supported on this device")
        }
    }

    override fun onUserLeaveHint() {
        // Optionally auto-enter PiP, but only when not locked
        if (!isLocked) {
            enterPipModeCompat()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        if (isInPictureInPictureMode) {
            // hide UI and overlays in PiP, keep playback running
            binding.playerView.useController = false
            binding.lockOverlay.visibility = View.GONE
        } else {
            // restore UI when full-screen again
            if (!isLocked) {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
            } else {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }
}
