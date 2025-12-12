package com.livetvpro.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import com.livetvpro.ui.adapters.RelatedChannelAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.UUID

@UnstableApi
@AndroidEntryPoint
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var channel: Channel
    
    // Related channels adapter
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    // Controller Views
    private var btnBack: ImageButton? = null
    private var btnPip: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnLock: ImageButton? = null
    private var btnMute: ImageButton? = null
    private var btnRewind: ImageButton? = null
    private var btnPlayPause: ImageButton? = null
    private var btnForward: ImageButton? = null
    private var btnFullscreen: ImageButton? = null
    private var btnAspectRatio: ImageButton? = null
    private var tvChannelName: TextView? = null

    // State flags
    private var isInPipMode = false
    private var isLocked = false
    private var isMuted = false
    private val skipMs = 10_000L
    private var userRequestedPip = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable {
        binding.unlockButton.visibility = View.GONE
    }
    
    // PiP Action Receiver
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.d("PiP Receiver: ${intent?.action}")
            
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                
                Timber.d("PiP Control Type: $controlType")
                
                when (controlType) {
                    CONTROL_TYPE_PLAY -> {
                        player?.play()
                        Timber.d("PiP: Playing")
                        updatePipParams()
                    }
                    CONTROL_TYPE_PAUSE -> {
                        player?.pause()
                        Timber.d("PiP: Paused")
                        updatePipParams()
                    }
                }
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityChannelPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Register PiP control receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL))
            }
        }

        // Get current orientation and apply initial settings
        val currentOrientation = resources.configuration.orientation
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        binding.progressBar.visibility = View.GONE
        setupPlayer()
        bindControllerViewsExact()
        tvChannelName?.text = channel.name
        setupControlListenersExact()
        setupPlayerViewInteractions()
        setupLockOverlay()
        
        // Setup related channels
        setupRelatedChannels()
        loadRelatedChannels()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
    }

    override fun onResume() {
        super.onResume()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationSettings(isLandscape)
    }

    private fun applyOrientationSettings(isLandscape: Boolean) {
        setWindowFlags(isLandscape)
        adjustLayoutForOrientation(isLandscape)
        binding.playerContainer.requestLayout()
        binding.root.requestLayout()
    }

    private fun adjustLayoutForOrientation(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams

        if (isLandscape) {
            binding.playerView.hideController()
            binding.playerView.controllerAutoShow = false
            binding.playerView.controllerShowTimeoutMs = 3000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            binding.playerView.controllerAutoShow = true
            binding.playerView.controllerShowTimeoutMs = 5000
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            
            params.dimensionRatio = "16:9"
            params.height = 0
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            binding.relatedChannelsSection.visibility = View.VISIBLE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        }
        binding.playerContainer.layoutParams = params
    }

    private fun setWindowFlags(isLandscape: Boolean) {
        if (isLandscape) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = 
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }
        
        if (!isPip) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.d("onStop() called - isInPipMode: $isInPipMode, isFinishing: $isFinishing")
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("onDestroy() called")
        releasePlayer()
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering PiP receiver")
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun releasePlayer() {
        player?.let {
            try {
                Timber.d("Releasing player")
                it.stop()
                it.release()
            } catch (t: Throwable) {
                Timber.w(t, "Error releasing player")
            }
        }
        player = null
    }

    private data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: String?,
        val drmKeyId: String?,
        val drmKey: String?
    )

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        val parts = streamUrl.split("|")
        val url = parts[0].trim()
        
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmKeyId: String? = null
        var drmKey: String? = null
        
        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) continue

            val key = part.substring(0, eqIndex).trim()
            val value = part.substring(eqIndex + 1).trim()
            
            when (key.lowercase()) {
                "drmscheme" -> {
                    drmScheme = value.lowercase()
                    Timber.d("DRM Scheme: $drmScheme")
                }
                "drmlicense" -> {
                    val keyParts = value.split(":")
                    if (keyParts.size == 2) {
                        drmKeyId = keyParts[0].trim()
                        drmKey = keyParts[1].trim()
                        Timber.d("DRM KeyID found")
                    }
                }
                "referer", "referrer" -> headers["Referer"] = value
                "user-agent", "useragent" -> headers["User-Agent"] = value
                "origin" -> headers["Origin"] = value
                "cookie" -> {
                    headers["Cookie"] = value
                    Timber.d("Cookie found")
                }
                else -> headers[key] = value
            }
        }
        
        return StreamInfo(url, headers, drmScheme, drmKeyId, drmKey)
    }

    private fun setupPlayer() {
        player?.release()
        trackSelector = DefaultTrackSelector(this)
        
        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            Timber.d("Setting up player for: ${channel.name}")
            Timber.d("URL: ${streamInfo.url}")
            Timber.d("DRM: ${streamInfo.drmScheme ?: "None"}")

            val headers = streamInfo.headers.toMutableMap()
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0"
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)

            val mediaSourceFactory = if (streamInfo.drmScheme != null && 
                                         streamInfo.drmKeyId != null && 
                                         streamInfo.drmKey != null) {
                
                Timber.d("Setting up DRM protection...")
                
                val drmSessionManager = if (streamInfo.drmScheme.equals("clearkey", ignoreCase = true)) {
                    createClearKeyDrmManager(
                        streamInfo.drmKeyId,
                        streamInfo.drmKey,
                        dataSourceFactory
                    )
                } else {
                    Timber.e("Unsupported DRM scheme: ${streamInfo.drmScheme}")
                    null
                }
                
                if (drmSessionManager != null) {
                    Timber.d("DRM manager created successfully")
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                } else {
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                }
            } else {
                Timber.d("No DRM - regular stream")
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .setLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 50000, 2500, 5000)
                        .build()
                )
                .build().also { exo ->
                    binding.playerView.player = exo
                    
                    val mediaItem = MediaItem.fromUri(streamInfo.url)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    exo.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    updatePlayPauseIcon(exo.playWhenReady)
                                    binding.progressBar.visibility = View.GONE
                                    updatePipParams()
                                    Timber.d("Player ready - PLAYING!")
                                }
                                Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = View.VISIBLE
                                    Timber.d("Buffering...")
                                }
                                Player.STATE_ENDED -> {
                                    binding.progressBar.visibility = View.GONE
                                    Timber.d("Playback ended")
                                }
                                Player.STATE_IDLE -> {
                                    Timber.d("Player idle")
                                }
                            }
                        }
                        
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            Timber.d("Is playing: $isPlaying")
                            if (isInPipMode) {
                                updatePipParams()
                            }
                        }
                        
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            Timber.d("Video size: ${videoSize.width}x${videoSize.height}")
                            if (isInPipMode) {
                                updatePipParams()
                            }
                        }
                        
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            Timber.e(error, "PLAYBACK ERROR")
                            Timber.e("Stream URL: ${streamInfo.url}")
                            binding.progressBar.visibility = View.GONE
                            
                            val errorMessage = when {
                                error.message?.contains("drm", ignoreCase = true) == true -> 
                                    "DRM error: Unable to decrypt stream"
                                error.message?.contains("clearkey", ignoreCase = true) == true -> 
                                    "ClearKey DRM error: Invalid license keys"
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> 
                                    "Server error: Unable to connect"
                                else -> "Playback error: ${error.message}"
                            }
                            
                            Toast.makeText(this@ChannelPlayerActivity, errorMessage, Toast.LENGTH_LONG).show()
                            binding.errorView.visibility = View.VISIBLE
                            binding.errorText.text = errorMessage
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "Error creating ExoPlayer")
            Toast.makeText(this, "Failed to initialize player", Toast.LENGTH_SHORT).show()
        }
        
        binding.playerView.apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    private fun createClearKeyDrmManager(
        keyIdHex: String,
        keyHex: String,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): DefaultDrmSessionManager? {
        return try {
            val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            
            Timber.d("Creating ClearKey DRM manager")
            
            val drmCallback = object : MediaDrmCallback {
                override fun executeProvisionRequest(
                    uuid: UUID,
                    request: ExoMediaDrm.ProvisionRequest
                ): ByteArray {
                    return ByteArray(0)
                }

                override fun executeKeyRequest(
                    uuid: UUID,
                    request: ExoMediaDrm.KeyRequest
                ): ByteArray {
                    val keyIdBytes = hexToBytes(keyIdHex)
                    val keyBytes = hexToBytes(keyHex)
                    
                    val keyIdBase64 = android.util.Base64.encodeToString(
                        keyIdBytes,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    )
                    val keyBase64 = android.util.Base64.encodeToString(
                        keyBytes,
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                    )
                    
                    val jwkResponse = """
                        {
                          "keys": [
                            {
                              "kty": "oct",
                              "k": "$keyBase64",
                              "kid": "$keyIdBase64"
                            }
                          ]
                        }
                    """.trimIndent()
                    
                    Timber.d("Generated JWK response for ClearKey")
                    return jwkResponse.toByteArray()
                }
            }
            
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    clearKeyUuid,
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .setMultiSession(false)
                .build(drmCallback).also {
                    Timber.d("ClearKey DRM manager created successfully")
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ClearKey DRM manager")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "").replace("-", "")
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun bindControllerViewsExact() {
        with(binding.playerView) {
            btnBack = findViewById(R.id.exo_back)
            btnPip = findViewById(R.id.exo_pip)
            btnSettings = findViewById(R.id.exo_settings)
            btnLock = findViewById(R.id.exo_lock)
            btnMute = findViewById(R.id.exo_mute)
            btnRewind = findViewById(R.id.exo_rewind)
            btnPlayPause = findViewById(R.id.exo_play_pause)
            btnForward = findViewById(R.id.exo_forward)
            btnFullscreen = findViewById(R.id.exo_fullscreen)
            btnAspectRatio = findViewById(R.id.exo_aspect_ratio) 
            tvChannelName = findViewById(R.id.exo_channel_name)
        }

        btnBack?.setImageResource(R.drawable.ic_arrow_back)
        btnPip?.setImageResource(R.drawable.ic_pip)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnLock?.setImageResource(R.drawable.ic_lock_open)
        updateMuteIcon()
        btnRewind?.setImageResource(R.drawable.ic_skip_backward)
        btnPlayPause?.setImageResource(R.drawable.ic_pause)
        btnForward?.setImageResource(R.drawable.ic_skip_forward)
        btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
        btnAspectRatio?.setImageResource(R.drawable.ic_aspect_ratio)

        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener { 
            if (!isLocked) finish() 
        }
        
        btnPip?.setOnClickListener { 
            if (!isLocked) {
                userRequestedPip = true
                enterPipMode() 
            }
        }

        btnSettings?.setOnClickListener { 
            if (!isLocked) showQualityDialog() 
        }
        
        btnAspectRatio?.setOnClickListener {
            if (!isLocked) toggleAspectRatio()
        }
        
        btnLock?.setOnClickListener { toggleLock() }
        
        btnRewind?.setOnClickListener { 
            if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) - skipMs) 
        }
        
        btnPlayPause?.setOnClickListener {
            if (!isLocked) {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
            }
        }
        
        btnForward?.setOnClickListener { 
            if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) + skipMs) 
        }
        
        btnFullscreen?.setOnClickListener { 
            if (!isLocked) toggleFullscreen() 
        }
        
        btnMute?.setOnClickListener {
            if (!isLocked) toggleMute()
        }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
            Toast.makeText(
                this, 
                if (isMuted) "Muted" else "Unmuted", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

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
            else -> {
                Toast.makeText(this, "Fill", Toast.LENGTH_SHORT).show()
                AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        }
        binding.playerView.resizeMode = currentResizeMode
    }

    private fun showQualityDialog() {
        if (trackSelector == null || player == null) {
            Toast.makeText(this, "Track selector not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            TrackSelectionDialogBuilder(
                this,
                "Select Video Quality",
                player!!,
                C.TRACK_TYPE_VIDEO
            ).build().show()
        } catch (e: Exception) {
            Timber.e(e, "Error showing quality dialog")
            Toast.makeText(this, "Quality settings unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPlayerViewInteractions() { 
        binding.playerView.setOnClickListener(null) 
    }
    
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
        
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.progressBar.visibility = View.VISIBLE
            setupPlayer()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel ->
            switchChannel(relatedChannel)
        }

        binding.relatedChannelsRecycler.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(
                this@ChannelPlayerActivity,
                3 
            )
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        Timber.d("Starting to load related channels for: ${channel.name} (${channel.id})")
        
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        
        viewModel.relatedChannels.removeObservers(this)
        viewModel.relatedChannels.observe(this) { channels ->
            Timber.d("Received ${channels.size} related channels")
            
            binding.relatedCount.text = channels.size.toString()
            relatedChannelsAdapter.submitList(channels)
            
            if (channels.isEmpty()) {
                binding.relatedChannelsSection.visibility = View.GONE
                Timber.w("No related channels available")
            } else {
                binding.relatedChannelsSection.visibility = View.VISIBLE
                Timber.d("Showing ${channels.size} related channels in 3-column grid")
            }
        }

        // Continuation of ChannelPlayerActivity.kt

    private fun switchChannel(newChannel: Channel) {
        Timber.d("Switching to channel: ${newChannel.name}")
        
        player?.release()
        player = null
        
        channel = newChannel
        tvChannelName?.text = channel.name
        
        setupPlayer()
        loadRelatedChannels()
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
            showUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_closed)
            binding.lockOverlay.isClickable = true
            binding.lockOverlay.isFocusable = true
        } else {
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.lockOverlay.visibility = View.GONE
            hideUnlockButton()
            btnLock?.setImageResource(R.drawable.ic_lock_open)
        }
    }
    
    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP not available", Toast.LENGTH_SHORT).show()
            return
        }

        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }

        binding.relatedChannelsSection.visibility = View.GONE
        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE

        updatePipParams(enter = true)
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val format = player?.videoFormat
                val width = format?.width ?: 16
                val height = format?.height ?: 9
                val ratio = if (width > 0 && height > 0) {
                    Rational(width, height)
                } else {
                    Rational(16, 9)
                }
                
                val builder = PictureInPictureParams.Builder()
                builder.setAspectRatio(ratio)
                
                val actions = ArrayList<RemoteAction>()
                val isPlaying = player?.isPlaying == true
                
                val playPauseIconId = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                val playPauseTitle = if (isPlaying) "Pause" else "Play"
                val playPauseControlType = if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY
                
                val playPauseIntent = Intent(ACTION_MEDIA_CONTROL).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_CONTROL_TYPE, playPauseControlType)
                }
                
                val playPausePendingIntent = PendingIntent.getBroadcast(
                    this,
                    playPauseControlType,
                    playPauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                
                val playPauseIcon = Icon.createWithResource(this, playPauseIconId)
                actions.add(RemoteAction(playPauseIcon, playPauseTitle, playPauseTitle, playPausePendingIntent))
                
                builder.setActions(actions)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                    builder.setSeamlessResizeEnabled(true)
                }
                
                if (enter) {
                    val success = enterPictureInPictureMode(builder.build())
                    if (success) {
                        isInPipMode = true
                        Timber.d("Successfully entered PiP mode")
                    } else {
                        Timber.w("Failed to enter PiP mode")
                        Toast.makeText(this, "Could not enter PiP", Toast.LENGTH_SHORT).show()
                    }
                } else if (isInPipMode) {
                    setPictureInPictureParams(builder.build())
                }
            } catch (e: Exception) {
                Timber.e(e, "PiP Error")
                if (enter) {
                    Toast.makeText(this, "PiP error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, 
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        Timber.d("PiP mode changed: $isInPictureInPictureMode, isFinishing: $isFinishing")
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.playerView.useController = false 
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.hideController()
            
            Timber.d("Entered PiP mode")
        } else {
            userRequestedPip = false
            
            if (isFinishing) {
                Timber.d("Activity is finishing - PiP was closed by user via system gesture/button")
                return
            }
            
            Timber.d("Exiting PiP - restoring UI")
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
            
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
                binding.playerView.postDelayed({
                    if (!isInPipMode) {
                        binding.playerView.showController()
                    }
                }, 150)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            Timber.d("User leaving app - auto-entering PiP")
            userRequestedPip = true
            enterPipMode()
        }
    }

    override fun finish() {
        Timber.d("finish() called - isInPipMode: $isInPipMode, isFinishing: $isFinishing")
        
        try {
            releasePlayer()
            isInPipMode = false
            userRequestedPip = false
            super.finish()
            Timber.d("Activity finished successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error in finish()")
            super.finish()
        }
    }
}
