package com.livetvpro.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultMediaSourceFactory
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import timber.log.Timber

@UnstableApi
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

    // control references (nullable)
    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var txtChannelName: TextView? = null

    private var isLocked = false
    private var isMuted = false
    private var isFullscreen = false

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        /** Safe start: ensures Channel is passed as Parcelable. */
        fun start(context: Context, channel: Channel) {
            try {
                val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                    putExtra(EXTRA_CHANNEL, channel)
                    // optional: ensure Activity start works from non-activity contexts
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start ChannelPlayerActivity")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep display on while viewing player
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Validate and read Channel extra safely
        val ch = intent?.getParcelableExtra<Channel>(EXTRA_CHANNEL)
        if (ch == null) {
            Timber.e("Missing Channel extra - finishing activity")
            showErrorAndFinish("Channel data missing")
            return
        }
        channel = ch

        // prepare UI references and actions
        setupControllerRefs()
        setupControllerActions()
        setupRelatedRecycler()

        // Delay creation to onStart for better lifecycle handling; but we can prepare now if needed
    }

    override fun onStart() {
        super.onStart()
        // initialize player only after UI created and channel validated
        if (!validateChannel()) return
        initializePlayerSafe()
    }

    override fun onStop() {
        super.onStop()
        // only release if not in PiP mode (Media3 will continue in PiP)
        if (!isInPictureInPictureMode) {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun setupControllerRefs() {
        // null-safe lookups inside PlayerView's controller layout
        try {
            btnBack = binding.playerView.findViewById(R.id.exo_back)
            btnPip = binding.playerView.findViewById(R.id.exo_pip)
            btnMute = binding.playerView.findViewById(R.id.exo_mute)
            btnLock = binding.playerView.findViewById(R.id.exo_lock)
            btnPlayPause = binding.playerView.findViewById(R.id.exo_play_pause)
            btnFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)
            btnRewind = binding.playerView.findViewById(R.id.exo_rewind)
            btnForward = binding.playerView.findViewById(R.id.exo_forward)
            txtChannelName = binding.playerView.findViewById(R.id.exo_channel_name)
            txtChannelName?.text = channel.name ?: getString(R.string.app_name)
        } catch (e: Exception) {
            Timber.w(e, "Error obtaining controller refs (safe to continue)")
        }
    }

    private fun setupControllerActions() {
        btnBack?.setOnClickListener { safeFinishOrExitFullscreen() }

        // PiP button - only show/call on supported devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            btnPip?.visibility = View.VISIBLE
            btnPip?.setOnClickListener { enterPipMode() }
        } else {
            btnPip?.visibility = View.GONE
        }

        btnMute?.setOnClickListener { toggleMute() }
        btnLock?.setOnClickListener { lockControls() }
        binding.unlockButton.setOnClickListener { unlockControls() }
        btnPlayPause?.setOnClickListener { togglePlayPause() }
        btnFullscreen?.setOnClickListener { toggleFullscreen() }

        btnRewind?.setOnClickListener {
            safeSeekBy(-10_000L)
        }
        btnForward?.setOnClickListener {
            safeSeekBy(10_000L)
        }

        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            initializePlayerSafe()
        }
    }

    private fun setupRelatedRecycler() {
        try {
            // adapter exists in your project; keep safe call
            // if adapter is null or view missing, ignore
            // RelatedChannelAdapter click should call ChannelPlayerActivity.start or switchChannel
        } catch (e: Exception) {
            Timber.w(e, "setupRelatedRecycler failed but continuing")
        }
    }

    // -------------------------
    // Player lifecycle & guards
    // -------------------------
    private fun validateChannel(): Boolean {
        if (channel.name.isNullOrBlank()) {
            showError("Invalid channel")
            return false
        }
        val url = channel.streamUrl ?: ""
        if (url.isBlank()) {
            showError("Stream URL missing")
            return false
        }
        // lightweight validation
        try {
            Uri.parse(url)
        } catch (e: Exception) {
            showError("Invalid stream URL")
            return false
        }
        return true
    }

    private fun initializePlayerSafe() {
        // Guard against double-inits
        if (player != null) {
            // already exist: re-attach to view if needed
            binding.playerView.player = player
            return
        }

        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("LiveTVPro/1.0")
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .also { exo ->
                    binding.playerView.player = exo
                    binding.playerView.useController = true
                    binding.playerView.controllerShowTimeoutMs = 5000
                    // safe add listener
                    exo.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            // no-op: optional adjustments
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                                Player.STATE_READY -> binding.progressBar.visibility = View.GONE
                                Player.STATE_ENDED -> binding.progressBar.visibility = View.GONE
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Timber.e(error, "Playback error")
                            binding.errorText.text = error.message ?: "Playback error"
                            binding.errorView.visibility = View.VISIBLE
                        }
                    })

                    // construct media item defensively
                    val url = channel.streamUrl ?: ""
                    if (url.isNotBlank()) {
                        try {
                            val item = MediaItem.fromUri(url)
                            exo.setMediaItem(item)
                            exo.prepare()
                            exo.playWhenReady = true
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to set media item: $url")
                            showError("Unable to play stream")
                        }
                    } else {
                        showError("Empty stream URL")
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "initializePlayerSafe failed")
            showError("Player initialization failed")
        }
    }

    private fun releasePlayer() {
        try {
            player?.removeListener { }
        } catch (ignored: Exception) { /* ignore */ }
        try {
            player?.release()
        } catch (ignored: Exception) { /* ignore */ }
        player = null
        binding.playerView.player = null
    }

    private fun safeSeekBy(deltaMs: Long) {
        player?.let {
            val target = (it.currentPosition + deltaMs).coerceAtLeast(0L)
            it.seekTo(target)
        }
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        }
    }

    private fun lockControls() {
        isLocked = true
        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.VISIBLE
        binding.unlockButton.visibility = View.VISIBLE
    }

    private fun unlockControls() {
        isLocked = false
        binding.playerView.useController = true
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
    }

    private fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding.relatedChannelsSection.visibility = View.GONE
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
    }

    private fun exitFullscreen() {
        isFullscreen = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding.relatedChannelsSection.visibility = View.VISIBLE
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
    }

    private fun safeFinishOrExitFullscreen() {
        if (isFullscreen) exitFullscreen() else finish()
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.progressBar.visibility = View.GONE
    }

    private fun showErrorAndFinish(message: String) {
        showError(message)
        // give user a moment to read error then finish
        binding.playerView.postDelayed({ finish() }, 1000L)
    }

    // -------------------------
    // Switching channels safely
    // -------------------------
    /** Call this to switch the playing channel without starting a new Activity. */
    fun switchChannel(newChannel: Channel) {
        runOnUiThread {
            try {
                // guard
                if (newChannel.streamUrl.isNullOrBlank()) {
                    showError("Stream unavailable")
                    return@runOnUiThread
                }

                // stop existing player gracefully
                player?.let {
                    it.stop()
                    it.clearMediaItems()
                }

                // update UI
                channel = newChannel
                txtChannelName?.text = channel.name ?: ""

                // set new media item safely
                try {
                    val mediaItem = MediaItem.fromUri(newChannel.streamUrl)
                    if (player == null) {
                        initializePlayerSafe()
                    }
                    player?.setMediaItem(mediaItem)
                    player?.prepare()
                    player?.playWhenReady = true
                    binding.errorView.visibility = View.GONE
                } catch (e: Exception) {
                    Timber.e(e, "switchChannel: setMediaItem failed")
                    showError("Failed to load channel")
                }
            } catch (e: Exception) {
                Timber.e(e, "switchChannel failed")
                showError("Cannot switch channel")
            }
        }
    }

    // -------------------------
    // Picture-in-Picture helpers
    // -------------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        try {
            // hide controllers for PiP snapshot
            binding.playerView.useController = false
            binding.playerView.hideController()

            binding.playerView.postDelayed({
                try {
                    val vw = player?.videoSize?.width ?: 16
                    val vh = player?.videoSize?.height ?: 9
                    val ratio = if (vw > 0 && vh > 0) Rational(vw, vh) else Rational(16, 9)

                    val rect = Rect()
                    binding.playerView.getGlobalVisibleRect(rect)

                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(ratio)
                        .setSourceRectHint(rect)
                        .build()
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    Timber.e(e, "enterPipMode inner failed")
                    binding.playerView.useController = true
                }
            }, 120L)
        } catch (e: Exception) {
            Timber.e(e, "enterPipMode failed")
            binding.playerView.useController = true
        }
    }

    override fun onUserLeaveHint() {
        if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPipMode()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.playerView.useController = false
        } else {
            binding.relatedChannelsSection.visibility = if (isFullscreen) View.GONE else View.VISIBLE
            binding.playerView.useController = !isLocked
        }
    }

    // Safe onBackPressed behavior: if not in PiP, request PiP; else super
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onBackPressed() {
        if (!isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            enterPipMode()
        } else {
            super.onBackPressed()
        }
    }
}
