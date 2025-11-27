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
import android.view.*
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.databinding.FloatingPlayerViewBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    // controller views (nullable)
    private var exoPip: ImageButton? = null
    private var exoFullscreen: ImageButton? = null

    // floating mini-player state
    private var isFloating = false
    private var touchSlop = 0

    private lateinit var floatingBinding: FloatingPlayerViewBinding

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

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // Keep initial 16:9 height to prevent jumping
        binding.playerView.post {
            val screenWidth = resources.displayMetrics.widthPixels
            val height16by9 = (screenWidth * 9f / 16f).toInt()

            binding.playerView.layoutParams = binding.playerView.layoutParams.apply {
                height = height16by9
            }
            binding.playerContainer.layoutParams = binding.playerContainer.layoutParams.apply {
                height = height16by9
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel provided")
            finish()
            return
        }

        // NOTE: your layout ProgressBar id is loading_progress so use binding.loadingProgress
        binding.loadingProgress.visibility = android.view.View.GONE

        setupPlayer()
        setupCustomControls()
        setupRelatedChannels()
        loadRelatedChannels()
        setupFloatingContainer()
    }

    // ------------------------
    // Player initialization
    // ------------------------
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
                    binding.playerView.useController = true
                    binding.playerView.controllerAutoShow = true
                    binding.playerView.controllerShowTimeoutMs = 3000

                    val mediaItem = MediaItem.fromUri(channel.streamUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.loadingProgress.visibility = android.view.View.VISIBLE
                                    binding.errorView.visibility = android.view.View.GONE
                                }
                                Player.STATE_READY -> {
                                    binding.loadingProgress.visibility = android.view.View.GONE
                                    binding.errorView.visibility = android.view.View.GONE
                                }
                                Player.STATE_ENDED -> {
                                    showError("Stream ended")
                                }
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Timber.e(error, "Playback error for ${channel.name}")
                            binding.loadingProgress.visibility = android.view.View.GONE
                            showError("Playback failed: ${error.message ?: "Unknown error"}")
                        }
                    })
                }
            Timber.d("Player initialized for ${channel.name}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize player")
            showError("Failed to initialize player: ${e.message}")
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = android.view.View.VISIBLE
        binding.errorText.text = message
    }

    // ------------------------
    // Controls and related channels
    // ------------------------
    private fun setupCustomControls() {
        exoPip = binding.playerView.findViewById(R.id.exo_pip)
        exoFullscreen = binding.playerView.findViewById(R.id.exo_fullscreen)

        exoPip?.let { pip ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            ) {
                pip.visibility = View.VISIBLE
                pip.setOnClickListener { enterPipMode() }
            } else {
                pip.visibility = View.GONE
            }
        }

        exoFullscreen?.setOnClickListener {
            toggleFloatingMode()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }

        // Use a horizontal LinearLayoutManager for related channels
        binding.relatedChannelsRecycler.apply {
            layoutManager = LinearLayoutManager(this@ChannelPlayerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)

        viewModel.relatedChannels.observe(this) { channels ->
            Timber.d("Loaded ${channels.size} related channels")
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsContainer.visibility =
                if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        player?.release()
        player = null
        channel = newChannel

        binding.playerView.findViewById<android.widget.TextView?>(
            resources.getIdentifier("exo_channel_name", "id", packageName)
        )?.text = channel.name

        if (validateChannel()) {
            setupPlayer()
        }
    }

    private fun validateChannel(): Boolean {
        return when {
            channel.name.isEmpty() -> {
                Timber.e("Channel name is empty")
                showError("Invalid channel: missing name")
                false
            }
            channel.streamUrl.isEmpty() -> {
                Timber.e("Stream URL is empty for channel: ${channel.name}")
                showError("Invalid stream URL for ${channel.name}")
                false
            }
            !isValidUrl(channel.streamUrl) -> {
                Timber.e("Invalid stream URL format: ${channel.streamUrl}")
                showError("Invalid stream URL format")
                false
            }
            else -> true
        }
    }

    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
    }

    // ------------------------
    // Floating (mini) player logic
    // ------------------------
    private fun setupFloatingContainer() {
        val floatingView = layoutInflater.inflate(R.layout.floating_player_view, binding.activityRoot, false)
        floatingBinding = FloatingPlayerViewBinding.bind(floatingView)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = 24.dpToPx()
            topMargin = 24.dpToPx()
        }

        binding.activityRoot.addView(floatingView, params)
        floatingView.visibility = View.GONE

        floatingBinding.btnExpand.setOnClickListener {
            restoreFromFloating()
        }

        floatingBinding.btnClose.setOnClickListener {
            player?.pause()
            player?.release()
            player = null
            finish()
        }

        makeViewDraggable(floatingView)
    }

    private fun toggleFloatingMode() {
        if (isFloating) restoreFromFloating() else enterFloatingMode()
    }

    private fun enterFloatingMode() {
        val playerView = binding.playerView as PlayerView
        val floatingView = binding.activityRoot.findViewById<View>(R.id.floating_root) ?: return

        binding.relatedChannelsContainer.visibility = View.GONE
        binding.errorView.visibility = View.GONE
        binding.loadingProgress.visibility = View.GONE

        reparentPlayerView(playerView, floatingView.findViewById(R.id.floating_player_container))

        floatingView.visibility = View.VISIBLE
        playerView.useController = true
        isFloating = true
    }

    private fun restoreFromFloating() {
        val playerView = binding.playerView as PlayerView
        val floatingView = binding.activityRoot.findViewById<View>(R.id.floating_root) ?: return
        val fullContainer = binding.playerContainer

        reparentPlayerView(playerView, fullContainer)
        floatingView.visibility = View.GONE
        binding.relatedChannelsContainer.visibility = View.VISIBLE
        isFloating = false
    }

    private fun reparentPlayerView(playerView: PlayerView, newParent: ViewGroup) {
        (playerView.parent as? ViewGroup)?.removeView(playerView)

        val lp = if (newParent is FrameLayout) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        } else {
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        newParent.addView(playerView, 0, lp)
    }

    private fun makeViewDraggable(view: View) {
        var lastX = 0f
        var lastY = 0f
        var downX = 0f
        var downY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            val parent = v.parent as? ViewGroup ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = v.x
                    lastY = v.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val newX = lastX + dx
                        val newY = lastY + dy
                        val maxX = parent.width - v.width
                        val maxY = parent.height - v.height
                        v.x = min(max(newX, 0f), maxX.toFloat())
                        v.y = min(max(newY, 0f), maxY.toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    !isDragging
                }
                else -> false
            }
        }
    }

    // ------------------------
    // PiP support
    // ------------------------
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            binding.relatedChannelsContainer.visibility = View.GONE
            binding.errorView.visibility = View.GONE
            binding.loadingProgress.visibility = View.GONE

            val aspectRatio = Rational(16, 9)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()

            enterPictureInPictureMode(params)
        } else {
            Snackbar.make(binding.activityRoot, "Picture-in-Picture not supported on this device", Snackbar.LENGTH_SHORT).show()
        }
    }

    // Provide both overrides to be compatible with different platform / androidX versions
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        handlePipChange(isInPictureInPictureMode, null)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        handlePipChange(isInPictureInPictureMode, newConfig)
    }

    private fun handlePipChange(isInPip: Boolean, newConfig: Configuration?) {
        if (isInPip) {
            // show only PlayerView
            binding.activityRoot.children.forEach { it.visibility = if (it.id == binding.playerView.id) View.VISIBLE else View.GONE }
        } else {
            // restore UI
            binding.activityRoot.children.forEach { it.visibility = View.VISIBLE }
            if (!isFloating) {
                restoreFromFloating()
            }
        }
    }

    // ------------------------
    // Lifecycle
    // ------------------------
    override fun onPause() {
        super.onPause()
        player?.pause()
        Timber.d("Player paused")
    }

    override fun onStop() {
        super.onStop()
        Timber.d("Activity stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        Timber.d("Player destroyed")
    }

    override fun onBackPressed() {
        if (isFloating) {
            restoreFromFloating()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------
    // Utilities
    // ------------------------
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

    private fun Int.dpToPx(): Int {
        val scale = resources.displayMetrics.density
        return (this * scale + 0.5f).toInt()
    }
}
