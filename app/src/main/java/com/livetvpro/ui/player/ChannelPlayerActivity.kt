// ===================================
// FILE: app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
// ACTION: REPLACE - Fix floating player implementation
// ===================================

package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
    private var isFloatingPlayerActive = false

    private val aspectRatios = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        
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
                            runOnUiThread {
                                try {
                                    val vw = videoSize.width
                                    val vh = videoSize.height
                                    if (vw > 0 && vh > 0) {
                                        val screenW = binding.playerView.width.takeIf { it > 0 }
                                            ?: resources.displayMetrics.widthPixels
                                        val desiredHeight = (screenW.toFloat() * vh.toFloat() / vw.toFloat()).toInt()

                                        val params =
                                            binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
                                        if (abs(params.height - desiredHeight) > 4) {
                                            params.height = desiredHeight
                                            params.dimensionRatio = null
                                            binding.playerContainer.layoutParams = params
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "failed to apply video size/layout")
                                }
                            }
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

        val fullscreenBtn = exoFullscreen
        if (fullscreenBtn == null) {
            Timber.e("exo_fullscreen not found")
        } else {
            fullscreenBtn.visibility = View.VISIBLE
        }

        exoAspectRatio?.visibility = View.GONE

        exoBack?.setOnClickListener { 
            if (isFullscreen) exitFullscreen() else finish() 
        }

        // ✅ FIXED: PiP button - Check permission and start floating player
        exoPip?.apply {
            visibility = View.VISIBLE
            setOnClickListener { 
                startFloatingPlayer()
            }
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
            unlockButton.visibility = if (unlockButton.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        unlockButton.setOnClickListener {
            isLocked = false
            binding.playerView.useController = true
            lockOverlay.visibility = View.GONE
            exoLock?.setImageResource(R.drawable.ic_lock_open)
        }

        exoAspectRatio?.setOnClickListener {
            if (!isFullscreen) return@setOnClickListener
            currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
            updateResizeMode()
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

        exoFullscreen?.setOnClickListener { toggleFullscreen() }

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            setupPlayer()
        }
    }

    // ✅ FIXED: Improved floating player startup
    private fun startFloatingPlayer() {
        // Check for overlay permission on Android M+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
                return
            }
        }
        
        Timber.d("Starting floating player for: ${channel.name}")
        
        // Mark that we're starting floating player
        isFloatingPlayerActive = true
        
        // Start floating player service FIRST
        FloatingPlayerService.start(
            context = this,
            streamUrl = channel.streamUrl,
            channelName = channel.name
        )
        
        // Give the service a moment to initialize before minimizing
        binding.root.postDelayed({
            Toast.makeText(this, "Floating player started", Toast.LENGTH_SHORT).show()
            
            // Move to background instead of finishing
            moveTaskToBack(true)
        }, 100) // Small delay to ensure service starts
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs overlay permission to show floating player. Grant permission in settings?")
            .setPositiveButton("Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingPlayer()
                } else {
                    Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            if (channels.isEmpty()) binding.relatedChannelsSection.visibility = View.GONE
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
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding.relatedChannelsSection.visibility = View.GONE

        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = null
        params.bottomToTop = ConstraintLayout.LayoutParams.UNSET
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        binding.playerContainer.layoutParams = params

        exoAspectRatio?.visibility = View.VISIBLE
        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        updateResizeMode()
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsSection.visibility = View.VISIBLE

        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = "16:9"
        params.bottomToTop = R.id.related_channels_section
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        binding.playerContainer.layoutParams = params

        exoAspectRatio?.visibility = View.GONE
        exoFullscreen?.setImageResource(R.drawable.ic_fullscreen)
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
        player?.let { 
            exoPlayPause?.setImageResource(
                if (it.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            ) 
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
        // Don't pause if floating player is starting
        if (!isFloatingPlayerActive) {
            player?.pause()
        }
    }

    // ✅ FIXED: Don't release player when going to background with floating player
    override fun onStop() {
        super.onStop()
        // Only release player if NOT starting floating player
        if (!isFloatingPlayerActive) {
            player?.release()
            player = null
        }
    }

    // ✅ FIXED: Handle returning from floating player
    override fun onResume() {
        super.onResume()
        
        // If we were in floating player mode and came back
        if (isFloatingPlayerActive) {
            Timber.d("Returning from floating player mode")
            
            // Stop floating player service
            FloatingPlayerService.stop(this)
            
            // Reset flag
            isFloatingPlayerActive = false
            
            // Reinitialize player if needed
            if (player == null) {
                setupPlayer()
            } else {
                player?.playWhenReady = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always stop floating player service when activity is destroyed
        if (isFloatingPlayerActive) {
            FloatingPlayerService.stop(this)
        }
        player?.release()
        player = null
    }
}
