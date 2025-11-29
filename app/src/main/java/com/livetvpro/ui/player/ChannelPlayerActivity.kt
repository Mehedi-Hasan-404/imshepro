// app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
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

    // Controller button references (nullable)
    private var exoPip: ImageButton? = null
    private var exoFullscreen: ImageButton? = null

    // State
    private var isInPipMode = false
    private var isFullscreen = false
    private var isLocked = false
    private val skipMs = 10_000L
    private var lastVolume = 1f

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
    // Lifecycle
    // --------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Force player container to a 16:9 top-anchored box to avoid vertical-centering issues
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

        // lock portrait by default (change if needed)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Expecting a Channel parcelable in intent
        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        // Setup player and UI
        setupPlayer()
        setupCustomControls()
        setupUIInteractions()

        // Restore related section visibility if present
        // Use binding directly - if layout doesn't have it, this will throw at compile time; if you have different id change accordingly
        try {
            binding.relatedRecyclerView.visibility = View.VISIBLE
        } catch (t: Throwable) {
            // safe: some layouts may not have relatedRecyclerView; ignore
            Timber.d("relatedRecyclerView not found in binding")
        }
    }

    override fun onStop() {
        super.onStop()
        // If we're in PiP keep the player alive. Otherwise release to avoid leaks/double audio.
        if (!isInPipMode) {
            player?.run {
                playWhenReady = false
                release()
            }
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isInPipMode) {
            player?.release()
            player = null
        }
    }

    // --------------------
    // Player setup (single instance)
    // --------------------
    private fun setupPlayer() {
        // Ensure previous player is fully released before creating a new one
        player?.let {
            try {
                it.pause()
                it.release()
            } catch (t: Throwable) { /* ignore */ }
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
                )
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            // no-op; keep for telemetry if needed
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
        }

        // Make PlayerView interactive and ensure controller is usable
        binding.playerView.apply {
            useController = true
            isClickable = true
            isFocusable = true
            requestFocus()
            bringToFront()
            controllerShowTimeoutMs = 4000
        }

        // avoid parent intercepting touches
        binding.playerContainer.isClickable = false
        binding.playerContainer.isFocusable = false
    }

    // --------------------
    // Controls wiring: back, play/pause, skip, mute, lock, pip, fullscreen
    // --------------------
    private fun setupCustomControls() {
        // Find PiP / fullscreen inside the PlayerView controller (IDs must match controller layout)
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)

        // Look up common control IDs used in controller layouts:
        val btnBack = binding.playerView.findViewById<ImageButton?>(R.id.exo_back)
            ?: binding.playerView.findViewById(R.id.exo_back_button)
        val btnPlay = binding.playerView.findViewById<ImageButton?>(R.id.exo_play)
        val btnPause = binding.playerView.findViewById<ImageButton?>(R.id.exo_pause)
        val btnRewind = binding.playerView.findViewById<ImageButton?>(R.id.exo_rew)
            ?: binding.playerView.findViewById(R.id.exo_rewind)
        val btnForward = binding.playerView.findViewById<ImageButton?>(R.id.exo_ffwd)
            ?: binding.playerView.findViewById(R.id.exo_forward)
        val btnMute = binding.playerView.findViewById<ImageButton?>(R.id.exo_mute)
            ?: binding.playerView.findViewById(R.id.exo_volume)
        val btnLock = binding.playerView.findViewById<ImageButton?>(R.id.exo_lock)

        // Back - finish
        btnBack?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            finish()
        }

        // Play / Pause
        btnPlay?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.play()
        }
        btnPause?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.pause()
        }

        // Rewind / Forward (seek)
        btnRewind?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val pos = it.currentPosition
                it.seekTo((pos - skipMs).coerceAtLeast(0L))
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

        // Mute / Unmute toggle
        btnMute?.setOnClickListener {
            if (isLocked) return@setOnClickListener
            player?.let {
                val currentVol = it.volume
                if (currentVol > 0f) {
                    lastVolume = currentVol
                    it.volume = 0f
                    btnMute.setImageResource(
                        resources.getIdentifier("ic_volume_off", "drawable", packageName)
                            .takeIf { id -> id != 0 } ?: R.drawable.ic_volume_off
                    )
                } else {
                    it.volume = lastVolume.takeIf { v -> v > 0f } ?: 1f
                    btnMute.setImageResource(
                        resources.getIdentifier("ic_volume_up", "drawable", packageName)
                            .takeIf { id -> id != 0 } ?: R.drawable.ic_volume_up
                    )
                }
            }
        }

        // Lock / Unlock controls
        btnLock?.setOnClickListener {
            isLocked = !isLocked
            if (isLocked) {
                // disable controller and consume touches
                binding.playerView.useController = false
                binding.playerView.setOnTouchListener { _, _ -> true }
                btnLock.setImageResource(
                    resources.getIdentifier("ic_lock_on", "drawable", packageName)
                        .takeIf { id -> id != 0 } ?: R.drawable.ic_lock
                )
            } else {
                binding.playerView.useController = true
                binding.playerView.setOnTouchListener(null)
                btnLock.setImageResource(
                    resources.getIdentifier("ic_lock_off", "drawable", packageName)
                        .takeIf { id -> id != 0 } ?: R.drawable.ic_unlock
                )
            }
        }

        // PiP & Fullscreen wiring
        exoPip?.setOnClickListener { enterPipMode() }
        exoFullscreen?.setOnClickListener { toggleFullscreen() }

        // Ensure visibility for wired buttons (if controller layout has them)
        exoPip?.visibility = View.VISIBLE
        exoFullscreen?.visibility = View.VISIBLE
    }

    // --------------------
    // UI interactions
    // --------------------
    private fun setupUIInteractions() {
        binding.playerView.setOnClickListener {
            // toggle/show controller
            if (!isLocked) binding.playerView.showController()
        }

        // Use explicit ControllerVisibilityListener to avoid overload ambiguity
        binding.playerView.setControllerVisibilityListener(object :
            PlayerView.ControllerVisibilityListener {
            override fun onVisibilityChanged(visibility: Int) {
                if (visibility == View.VISIBLE) {
                    binding.playerView.bringToFront()
                }
            }
        })
    }

    // --------------------
    // PiP behavior: hide non-video UI and prevent double playback
    // --------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.pip_not_supported), Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, getString(R.string.pip_not_supported), Toast.LENGTH_SHORT).show()
            return
        }

        // Build aspect ratio using the player container dimensions
        val width = binding.playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = binding.playerContainer.height.takeIf { it > 0 } ?: (width * 9 / 16)
        val aspectRatio = Rational(width, height)

        val params = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
        isInPipMode = true

        // Hide non-video UI before entering PiP to ensure only video is shown in the PiP
        try {
            binding.relatedRecyclerView.visibility = View.GONE
        } catch (t: Throwable) { /* ignore if not present */ }

        binding.playerView.useController = false
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        // if you want auto-PiP on home press, uncomment:
        // if (!isInPipMode) enterPipMode()
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            // hide related UI / overlays when in PiP
            try {
                binding.relatedRecyclerView.visibility = View.GONE
            } catch (t: Throwable) { /* ignore */ }
            binding.playerView.useController = false
        } else {
            // restore UI after leaving PiP
            try {
                binding.relatedRecyclerView.visibility = View.VISIBLE
            } catch (t: Throwable) { /* ignore */ }
            binding.playerView.useController = !isLocked
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
        }
    }

    // --------------------
    // Fullscreen handling
    // --------------------
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            try {
                binding.relatedRecyclerView.visibility = View.GONE
            } catch (t: Throwable) { /* ignore */ }
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            try {
                binding.relatedRecyclerView.visibility = View.VISIBLE
            } catch (t: Throwable) { /* ignore */ }
        }

        // swap fullscreen icon if drawable exists
        val drawableName = if (isFullscreen) "ic_fullscreen_exit" else "ic_fullscreen"
        val drawableId = resources.getIdentifier(drawableName, "drawable", packageName)
        if (drawableId != 0) exoFullscreen?.setImageResource(drawableId)
    }

    // --------------------
    // Utility
    // --------------------
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
