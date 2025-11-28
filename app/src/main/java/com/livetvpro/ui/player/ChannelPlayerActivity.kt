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

    // controller views (nullable) — wired from controller layout
    private var exoPip: ImageButton? = null

    // state
    private var isInPipMode = false

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

        // Ensure the top player container is anchored to top and sized as expected (fix centering)
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

        // lock portrait (matching previous behavior)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE

        setupPlayer()
        setupCustomControls()
        setupUIInteractions()
        // loadRelatedChannels() remains unchanged and will populate your related list
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
                                .setConnectTimeoutMs(30_000)
                                .setReadTimeoutMs(30_000)
                                .setAllowCrossProtocolRedirects(true)
                        )
                )
                .build()
                .also { exoPlayer ->
                    // Attach player to view
                    binding.playerView.player = exoPlayer

                    // Prepare media item from channel stream
                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    // keep controller responsive
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            // keep simple logging
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error setting up ExoPlayer")
        }

        // make sure PlayerView is interactive and on top (fix controller unresponsive)
        binding.playerView.apply {
            useController = true
            isClickable = true
            isFocusable = true
            requestFocus()
            bringToFront()
            controllerShowTimeoutMs = 4000
        }
    }

    private fun setupCustomControls() {
        // controller layout (exo_modern_player_controls.xml) contains the PiP button with id `exo_pip`
        // safely find it; it may be null if controller not inflated yet
        exoPip = binding.playerView.findViewById(R.id.exo_pip)

        exoPip?.setOnClickListener {
            enterPipMode()
        }
    }

    private fun setupUIInteractions() {
        // When user taps outside or rotates, restore controller visibility behavior
        binding.playerView.setOnClickListener {
            // rely on PlayerView controller toggles by default; ensure controller is shown
            binding.playerView.showController()
        }
    }

    // --------------------
    // Picture-in-Picture (PiP)
    // --------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP is not supported on this device", Toast.LENGTH_SHORT).show()
            return
        }

        // Build aspect ratio from the current player container size
        val width = binding.playerContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = binding.playerContainer.height.takeIf { it > 0 } ?: (width * 9 / 16)
        val aspectRatio = Rational(width, height)

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()

        isInPipMode = true
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        // optionally auto-enter PiP when user presses home — commented out for explicit PiP on button
        // if (!isInPipMode) enterPipMode()
        super.onUserLeaveHint()
    }

    // Use the single-argument override for broader compatibility with compile SDKs
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode

        // hide controls UI when in PiP
        if (isInPictureInPictureMode) {
            // hide in-player UI overlays that aren't useful in small PiP window
            binding.playerView.useController = false
        } else {
            binding.playerView.useController = true
            // restore controls (bring to front to ensure taps work)
            binding.playerView.bringToFront()
            binding.playerView.requestFocus()
        }
    }

    override fun onStop() {
        super.onStop()
        // If in PiP, keep the player running. Otherwise release to avoid leaks.
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
        // final cleanup
        if (!isInPipMode) {
            player?.release()
            player = null
        }
    }

    // Simple helper to hide system UI (keeps previous behavior)
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }
}
