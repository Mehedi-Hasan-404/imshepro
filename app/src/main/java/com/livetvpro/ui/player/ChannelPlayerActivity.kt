package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import timber.log.Timber

/**
 * ChannelPlayerActivity - plays a Channel stream using ExoPlayer (media3/exoplayer).
 *
 * Expects a parcelable Channel passed as EXTRA_CHANNEL (see companion.start).
 *
 * Relies on activity_channel_player.xml and exo_modern_player_controls.xml for layout & controller IDs.
 */
class ChannelPlayerActivity : ComponentActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

    // Custom Exo controller UI (IDs come from exo_modern_player_controls.xml)
    private var exoBack: ImageButton? = null
    private var exoChannelName: TextView? = null
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
    private var currentAspectMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    private val aspectModes = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java)
            intent.putExtra(EXTRA_CHANNEL, channel)
            context.startActivity(intent)
        }
    }

    // -- lifecycle ----------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep screen on while playing
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        // lock initial orientation to portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("ChannelPlayerActivity: no channel passed")
            finish()
            return
        }

        setupPlayer()
        bindCustomControls()
        setupControlActions()
        setupRelatedChannels() // stubbed — adjust as you need
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    // -- player setup ------------------------------------------------------
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
                .build()
                .also { exo ->
                    binding.playerView.player = exo
                    binding.playerView.useController = true
                    binding.playerView.controllerShowTimeoutMs = 5_000
                    binding.playerView.controllerAutoShow = true

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.isVisible = true
                                    binding.errorView.isVisible = false
                                }
                                Player.STATE_READY -> {
                                    binding.progressBar.isVisible = false
                                    binding.errorView.isVisible = false
                                    updatePlayPauseIcon()
                                }
                                Player.STATE_ENDED -> {
                                    showError("Stream ended")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val msg = error.message ?: "Unknown playback error"
                            showError(msg)
                            Timber.e(error, "Playback error")
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ExoPlayer")
            showError("Failed to start player")
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.release()
            player = null
        }
    }

    // -- UI helpers --------------------------------------------------------
    private fun bindCustomControls() {
        // Controller is part of the PlayerView; findViewById on playerView will search the controller layout
        val root = binding.playerView
        exoBack = root.findViewById(R.id.exo_back)
        exoChannelName = root.findViewById(R.id.exo_channel_name)
        exoPip = root.findViewById(R.id.exo_pip)
        exoSettings = root.findViewById(R.id.exo_settings)
        exoMute = root.findViewById(R.id.exo_mute)
        exoLock = root.findViewById(R.id.exo_lock)
        exoAspectRatio = root.findViewById(R.id.exo_aspect_ratio)
        exoPlayPause = root.findViewById(R.id.exo_play_pause)
        exoFullscreen = root.findViewById(R.id.exo_fullscreen)
        exoRewind = root.findViewById(R.id.exo_rewind)
        exoForward = root.findViewById(R.id.exo_forward)

        // initial UI
        exoChannelName?.text = channel.title ?: channel.name ?: "Channel"
        updatePlayPauseIcon()
        updateMuteIcon()
        updateLockState()
    }

    private fun setupControlActions() {
        exoBack?.setOnClickListener { finish() }

        exoPlayPause?.setOnClickListener {
            togglePlayPause()
        }

        exoMute?.setOnClickListener {
            toggleMute()
        }

        exoLock?.setOnClickListener {
            toggleLock()
        }

        exoAspectRatio?.setOnClickListener {
            toggleAspectRatio()
        }

        exoFullscreen?.setOnClickListener {
            toggleFullscreen()
        }

        exoRewind?.setOnClickListener {
            player?.let {
                val pos = (it.currentPosition - 10_000).coerceAtLeast(0)
                it.seekTo(pos)
            }
        }

        exoForward?.setOnClickListener {
            player?.let {
                val pos = (it.currentPosition + 10_000).coerceAtMost(it.duration.coerceAtLeast(0))
                it.seekTo(pos)
            }
        }

        exoPip?.setOnClickListener {
            enterPipMode()
        }

        // Unlock button shown in controller overlay (center) — id unlock_button in exo controls
        val unlockButton = binding.playerView.findViewById<ImageButton?>(R.id.unlock_button)
        unlockButton?.setOnClickListener {
            // when locked, unlock overlay hides lock overlay and shows controller
            if (isLocked) toggleLock()
        }
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
            updatePlayPauseIcon()
        }
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying ?: false
        exoPlayPause?.setImageResource(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
        }
    }

    private fun updateMuteIcon() {
        exoMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun toggleLock() {
        isLocked = !isLocked
        updateLockState()
    }

    private fun updateLockState() {
        // when locked we hide controller and show the lock overlay in controller layout
        val lockOverlay = binding.playerView.findViewById<View?>(R.id.lock_overlay)
        if (isLocked) {
            // show overlay / hide controller buttons
            lockOverlay?.visibility = View.VISIBLE
            binding.playerView.hideController()
            exoLock?.setImageResource(R.drawable.ic_lock) // ensure drawable exists or change to your lock drawable
        } else {
            lockOverlay?.visibility = View.GONE
            binding.playerView.showController()
            exoLock?.setImageResource(R.drawable.ic_lock_open)
        }
    }

    private fun toggleAspectRatio() {
        val index = (aspectModes.indexOf(currentAspectMode) + 1) % aspectModes.size
        currentAspectMode = aspectModes[index]
        binding.playerView.resizeMode = currentAspectMode
        // you may want to update exoAspectRatio icon/text here
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            // full screen: landscape
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            hideSystemUI()
            exoFullscreen?.setImageResource(android.R.drawable.ic_media_fullscreen) // change if you have custom icon
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            showSystemUI()
            exoFullscreen?.setImageResource(android.R.drawable.ic_media_rew) // placeholder
        }
    }

    // -- Picture-in-Picture (API 26+)
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspect = Rational(binding.playerView.width, binding.playerView.height)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspect)
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Timber.w(e, "Failed to enter PiP")
            }
        }
    }

    // -- error & utils ----------------------------------------------------
    private fun showError(message: String) {
        binding.progressBar.isVisible = false
        binding.errorView.isVisible = true
        // assume your error view has a TextView with id error_text (if not, adapt)
        val tv = binding.errorView.findViewById<TextView?>(R.id.error_text)
        tv?.text = message
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        actionBar?.hide()
    }

    private fun showSystemUI() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        actionBar?.show()
    }

    // Stub — populate your related channels RV as you need
    private fun setupRelatedChannels() {
        // e.g. binding.relatedChannelsRecycler.adapter = ...
    }
}
