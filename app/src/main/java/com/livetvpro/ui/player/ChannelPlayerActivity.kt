// app/src/main/java/com/livetvpro/ui/player/ChannelPlayerActivity.kt
package com.livetvpro.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.UUID

@UnstableApi // Media3 is marked as unstable
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var channel: Channel
    private var trackSelector: DefaultTrackSelector? = null // Make nullable as per Channel Player 11
    private var isLocked = false
    private var isInPipMode = false
    private var userRequestedPip = false // Track if PiP was initiated by the user
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable { hideUnlockButton() }
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter
    private val viewModel: PlayerViewModel by viewModels() // Use ViewModel for related channels

    // State variables from Channel Player 11
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var isMuted = false
    private val skipMs = 10000L // 10 seconds for skip buttons, adjust as needed

    // PiP Action Constants
    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"
        private const val ACTION_MEDIA_CONTROL = "media_control"
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_TYPE_PLAY = 1
        private const val CONTROL_TYPE_PAUSE = 2

        fun start(context: Context, channel: Channel) {
            val intent = Intent(context, ChannelPlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL, channel)
            }
            context.startActivity(intent)
        }
    }

    // PiP Action Receiver
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                when (controlType) {
                    CONTROL_TYPE_PLAY -> {
                        player?.play()
                        Timber.d("PiP: Play command received")
                        if (isInPipMode) updatePipParams() // Update actions in PiP
                    }
                    CONTROL_TYPE_PAUSE -> {
                        player?.pause()
                        Timber.d("PiP: Pause command received")
                        if (isInPipMode) updatePipParams() // Update actions in PiP
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register PiP control receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        }

        // Get channel data from intent
        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            Timber.e("No channel data provided")
            Toast.makeText(this, "Channel data missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Make fullscreen and keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Apply initial orientation settings
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)

        binding.progressBar.visibility = View.VISIBLE // Show initial loading indicator

        // Setup player with the logic from Channel Player 11
        setupPlayer()

        // Setup other UI interactions and components
        setupPlayerViewInteractions()
        setupLockOverlay()
        setupRelatedChannels() // Initialize adapter and view
        loadRelatedChannels() // Load related channels after player setup
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
    }

    // --- Player Setup from Channel Player 11 ---
    private fun setupPlayer() {
        player?.release() // Release any existing player instance
        trackSelector = DefaultTrackSelector(this) // Initialize track selector

        try {
            // Parse inline headers from stream URL
            val (cleanUrl, inlineHeaders) = parseInlineHeaders(channel.streamUrl)

            // Prepare headers map
            val headers = mutableMapOf<String, String>()
            headers.putAll(inlineHeaders)

            // Add default user agent if not provided
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "LiveTVPro/1.0"
            }

            Timber.d("═══════════════════════════════════════")
            Timber.d("🎬 Setting up player for: ${channel.name}")
            Timber.d("📺 Clean URL: ${cleanUrl.take(100)}")
            Timber.d("📡 Total Headers: ${headers.size}")
            headers.forEach { (key, value) -> Timber.d(" → $key: ${value.take(80)}") }
            Timber.d("═══════════════════════════════════════")

            // Create DataSource with custom headers
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)

            // --- DRM Setup (ClearKey Example) ---
            val drmSessionManager = if (!channel.drmLicenseUrl.isNullOrEmpty()) { // Assuming Channel model has drmLicenseUrl
                val drmCallback = HttpMediaDrmCallback(channel.drmLicenseUrl!!, dataSourceFactory) // Use the same data source factory for license requests
                DefaultDrmSessionManager.Builder()
                    .setMultiSession(false)
                    .setKeyRequestExecutor(drmCallback)
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build()
            } else {
                null // No DRM
            }

            // Build the player
            val playerBuilder = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                )
                .setSeekBackIncrementMs(skipMs) // Set skip increment
                .setSeekForwardIncrementMs(skipMs)

            // Add DRM manager if needed
            if (drmSessionManager != null) {
                playerBuilder.setDrmSessionManager(drmSessionManager)
            }

            player = playerBuilder.build().also { exo ->
                binding.playerView.player = exo
                binding.playerView.controllerAutoShow = true
                binding.playerView.controllerShowTimeoutMs = 5000 // Adjust timeout as needed
                binding.playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) // Optional: show buffering
                binding.playerView.setShutterBackgroundColor(Color.BLACK) // Black background when loading
                binding.playerView.useController = true // Ensure controller is enabled initially

                // Use CLEAN URL (without inline headers) for MediaItem
                val mediaItem = MediaItem.fromUri(cleanUrl)
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true // Start playing automatically

                // Add listener for player events
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player: Buffering")
                                binding.progressBar.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                Timber.d("Player: Ready, playing: ${exo.isPlaying}")
                                binding.progressBar.visibility = View.GONE
                                if (!exo.isPlaying) {
                                    // Potentially paused due to buffering or other reasons
                                    // You might want to handle this differently
                                }
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Player: Ended")
                                // Handle end of stream if needed
                                Toast.makeText(this@ChannelPlayerActivity, "Stream ended", Toast.LENGTH_SHORT).show()
                            }
                            Player.STATE_IDLE -> {
                                Timber.d("Player: Idle")
                                binding.progressBar.visibility = View.GONE
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "❌ Playback error for ${channel.name}")
                        binding.progressBar.visibility = View.GONE
                        val errorMessage = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_TIMEOUT -> "Connection timeout: Stream took too long to respond"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Network connection failed"
                            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "Invalid stream format"
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Invalid stream container"
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Invalid stream manifest"
                            else -> {
                                if (error.message?.contains("drm", ignoreCase = true) == true) {
                                    "DRM error: Unable to decrypt stream"
                                } else if (error.message?.contains("clearkey", ignoreCase = true) == true) {
                                    "ClearKey DRM error: Invalid license keys"
                                } else {
                                    "Playback error: ${error.message}"
                                }
                            }
                        }
                        Toast.makeText(this@ChannelPlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // Can be used to update UI based on available tracks
                        // e.g., show/hide quality button if video tracks are available
                        Timber.d("Tracks changed: ${tracks.groups.size} groups available")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            Timber.d("Playback started")
                        } else {
                            Timber.d("Playback paused")
                        }
                        // Update PiP actions if needed
                        if (isInPipMode) {
                            updatePipParams()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        // First frame rendered, hide loading indicator if not already hidden
                        binding.progressBar.visibility = View.GONE
                    }
                })
            }

            Timber.d("Player initialized successfully for channel: ${channel.name}, Clean URL: $cleanUrl")
            // Update channel name display
            binding.tvChannelName?.text = channel.name // Assuming tvChannelName exists in layout

        } catch (e: Exception) {
            Timber.e(e, "❌ Error creating ExoPlayer for ${channel.name}")
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Failed to initialize player: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Inline Header Parsing from Channel Player 11 ---
    private fun parseInlineHeaders(url: String): Pair<String, Map<String, String>> {
        var streamUrl = url
        val headers = mutableMapOf<String, String>()

        // Check for inline headers in the URL (e.g., #EXTVLCOPT:header=value)
        val parts = streamUrl.split("#EXTVLCOPT:")
        streamUrl = parts[0] // The actual stream URL is the first part

        for (i in 1 until parts.size) { // Process header parts
            val headerPart = parts[i].trim()
            val separatorIndex = headerPart.indexOf('=')
            if (separatorIndex > 0) {
                val headerName = headerPart.substring(0, separatorIndex).trim().lowercase()
                val headerValue = headerPart.substring(separatorIndex + 1).trim()

                when (headerName) {
                    "user-agent" -> {
                        headers["User-Agent"] = headerValue
                        Timber.d(" ✓ User-Agent: $headerValue")
                    }
                    "referer" -> {
                        headers["Referer"] = headerValue
                        Timber.d(" ✓ Referer: $headerValue")
                    }
                    "cookie" -> {
                        headers["Cookie"] = headerValue
                        Timber.d(" ✓ Cookie: ${headerValue.take(50)}...")
                    }
                    else -> {
                        headers[headerName] = headerValue
                        Timber.d(" ✓ $headerName: ${headerValue.take(50)}...")
                    }
                }
            }
        }

        Timber.d("Extracted clean URL: ${streamUrl.take(100)}")
        Timber.d("Parsed ${headers.size} inline headers")
        return Pair(streamUrl, headers)
    }


    // --- UI Interaction Setup ---
    private fun setupPlayerViewInteractions() {
        binding.playerView.setOnClickListener { toggleControllerVisibility() }
        // Add other click listeners if needed based on your layout
    }

    private fun toggleControllerVisibility() {
        if (isLocked) return // Don't toggle if locked
        if (binding.playerView.isControllerVisible) {
            binding.playerView.hideController()
        } else {
            binding.playerView.showController()
        }
    }

    // --- Lock Overlay Setup ---
    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener {
            if (binding.unlockButton.visibility == View.VISIBLE) {
                hideUnlockButton()
            } else {
                showUnlockButton()
            }
        }
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
    }

    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun hideUnlockButton() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        binding.unlockButton.visibility = View.GONE
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.playerView.useController = false
            binding.playerView.hideController()
            binding.lockOverlay.visibility = View.VISIBLE
        } else {
            binding.playerView.useController = true
            binding.lockOverlay.visibility = View.GONE
            showUnlockButton() // Show unlock button temporarily after unlocking
        }
    }

    // --- Related Channels Setup (using ViewModel) ---
    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }
        binding.relatedChannelsRecycler.apply {
            // Use GridLayoutManager with 3 columns as per Channel Player 11
            layoutManager = GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        // Observe related channels from ViewModel
        viewModel.loadRelatedChannels(channel.categoryId, channel.id) // Pass current channel ID to exclude it
        viewModel.relatedChannels.observe(this) { channels ->
            Timber.d("Loaded ${channels.size} related channels from ViewModel")
            updateRelatedChannelsUI(channels)
        }
    }

    private fun updateRelatedChannelsUI(channels: List<Channel>) {
        if (channels.isEmpty()) {
            binding.relatedChannelsSection.visibility = View.GONE
            Timber.w("No related channels available")
        } else {
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsSection.visibility = View.VISIBLE
            Timber.d("Showing ${channels.size} related channels in 3-column grid")
        }
    }

    // --- Switch Channel Logic ---
    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        // Release current player
        player?.release()
        player = null

        // Update current channel
        channel = newChannel

        // Update UI (e.g., channel name)
        binding.tvChannelName?.text = channel.name

        // Reinitialize player with new channel (using the updated setupPlayer logic)
        setupPlayer()

        // Reload related channels for the new channel
        loadRelatedChannels()
    }

    // --- Aspect Ratio Control from Channel Player 11 ---
    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                Toast.makeText(this, "Zoom", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                Toast.makeText(this, "Fit", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            else -> { // Covers RESIZE_MODE_FIT
                Toast.makeText(this, "Fill", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    // --- Mute Control from Channel Player 11 ---
    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
            Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMuteIcon() {
        // Assuming you have a mute button in your layout
        binding.btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    // --- Quality Dialog from Channel Player 11 ---
    private fun showQualityDialog() {
        if (trackSelector == null || player == null) {
            Toast.makeText(this, "Track selector not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            TrackSelectionDialogBuilder(
                this,
                "Select Video Quality",
                player!!, // Use non-null assertion as checked above
                C.TRACK_TYPE_VIDEO
            ).build().show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing quality dialog")
            Toast.makeText(this, "Quality settings unavailable: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- PiP Handling ---
    private fun enterPipMode() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
            return
        }
        userRequestedPip = true
        player?.let { if (!it.isPlaying) it.play() } // Ensure playback starts in PiP
        binding.relatedChannelsSection.visibility = View.GONE // Hide related channels in PiP
        binding.playerView.useController = false // Hide controller in PiP
        binding.lockOverlay.visibility = View.GONE // Hide lock overlay in PiP
        binding.unlockButton.visibility = View.GONE // Hide unlock button in PiP

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder().build()
            enterPictureInPictureMode(params)
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPipMode) {
            val isPlaying = player?.isPlaying == true
            val actions = mutableListOf<android.app.RemoteAction>()

            val playAction = android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_play),
                "Play",
                "Play",
                PendingIntent.getBroadcast(
                    this,
                    CONTROL_TYPE_PLAY,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            val pauseAction = android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pause),
                "Pause",
                "Pause",
                PendingIntent.getBroadcast(
                    this,
                    CONTROL_TYPE_PAUSE,
                    Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

            // Add pause if playing, play if paused
            if (isPlaying) {
                actions.add(pauseAction)
            } else {
                actions.add(playAction)
            }

            val params = android.app.PictureInPictureParams.Builder()
                .setActions(actions)
                .build()
            setPictureInPictureParams(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode

        if (isInPictureInPictureMode) {
            Timber.d("Entered PiP mode")
            // Adjust UI for PiP: hide controls, related channels, etc.
            binding.playerView.useController = false
            binding.relatedChannelsSection.visibility = View.GONE
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            updatePipParams() // Set initial PiP actions
        } else {
            Timber.d("Exited PiP mode")
            // Restore UI when exiting PiP
            // Check if it was user-requested or system-closed
            if (userRequestedPip || !isFinishing) {
                binding.playerView.useController = true
                binding.relatedChannelsSection.visibility = View.VISIBLE
                if (isLocked) {
                    binding.lockOverlay.visibility = View.VISIBLE
                } else {
                    showUnlockButton() // Show unlock button temporarily
                }
                userRequestedPip = false // Reset flag
            }
            // If isFinishing is true, the activity is closing, so we don't need to restore UI.
        }
    }

    // --- Orientation Handling ---
    private fun applyOrientationSettings(isLandscape: Boolean) {
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // You might want to adjust UI elements based on orientation here if needed
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
    }

    // --- Lifecycle Methods ---
    override fun onStart() {
        super.onStart()
        // Player is created in onCreate, so no need to recreate here unless destroyed
    }

    override fun onResume() {
        super.onResume()
        if (!isInPipMode) { // Only play if not in PiP
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // Optionally pause playback when app is not in foreground (only if not in PiP)
        if (!isInPipMode && player?.isPlaying == true) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Release the player when the activity is stopped
        // Only release if not in PiP mode, otherwise player continues in PiP
        if (!isInPipMode) {
            player?.release()
            player = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure player is released if it wasn't already
        player?.release()
        player = null
        // Remove any pending callbacks
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        // Unregister PiP receiver
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
            Timber.w("PiP receiver was not registered or already unregistered.")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Optional: Enter PiP when user leaves the activity (e.g., presses home button)
        // enterPipMode() // Uncomment if desired, but be careful about user expectations
    }

    // Override finish() to handle PiP exit correctly if needed
    override fun finish() {
        // If exiting PiP, just call super.finish()
        // The onStop lifecycle will handle player release if necessary
        super.finish()
    }
}
