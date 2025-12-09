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
import android.util.Base64
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
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
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
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                val controlType = intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)
                when (controlType) {
                    CONTROL_TYPE_PLAY -> {
                        player?.play()
                        updatePipParams()
                    }
                    CONTROL_TYPE_PAUSE -> {
                        player?.pause()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(pipReceiver, IntentFilter(ACTION_MEDIA_CONTROL), RECEIVER_NOT_EXPORTED)
        }

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
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!isPip) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
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
        val drmLicense: String? // Changed from keyId/key to generic license string
    )

    private fun parseStreamUrl(streamUrl: String): StreamInfo {
        val parts = streamUrl.split("|")
        val url = parts[0].trim()
        
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmLicense: String? = null
        
        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) continue

            val key = part.substring(0, eqIndex).trim()
            val value = part.substring(eqIndex + 1).trim()
            
            when (key.lowercase()) {
                "drmscheme" -> drmScheme = value.lowercase()
                "drmlicense" -> drmLicense = value // Raw license string (URL or Keys)
                "referer", "referrer" -> headers["Referer"] = value
                "user-agent", "useragent" -> headers["User-Agent"] = value
                "origin" -> headers["Origin"] = value
                "cookie" -> headers["Cookie"] = value
                else -> headers[key] = value
            }
        }
        
        return StreamInfo(url, headers, drmScheme, drmLicense)
    }

    private fun setupPlayer() {
        player?.release()
        trackSelector = DefaultTrackSelector(this)
        
        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            Timber.d("╔═══════════════════════════════════════╗")
            Timber.d("🎬 Setup: ${channel.name}")
            Timber.d("📺 URL: ${streamInfo.url}")
            Timber.d("🔒 DRM: ${streamInfo.drmScheme ?: "None"}")
            Timber.d("╚═══════════════════════════════════════╝")

            val headers = streamInfo.headers.toMutableMap()
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0"
            }

            // Create DataSource Factory with headers
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)

            // ✅ NEW: Unified DRM Handler
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            if (streamInfo.drmScheme != null) {
                try {
                    val drmUuid = when(streamInfo.drmScheme.lowercase()) {
                        "widevine" -> C.WIDEVINE_UUID
                        "playready" -> C.PLAYREADY_UUID
                        "clearkey" -> C.CLEARKEY_UUID
                        else -> C.WIDEVINE_UUID // Default to Widevine
                    }

                    // Prepare DRM Callback
                    val drmCallback = if (streamInfo.drmScheme == "clearkey" && streamInfo.drmLicense?.contains(":") == true && !streamInfo.drmLicense.startsWith("http")) {
                        // 🔑 Case 1: Static ClearKey with "id:key" format
                        val keyParts = streamInfo.drmLicense.split(":")
                        if (keyParts.size == 2) {
                            val keyId = keyParts[0]
                            val key = keyParts[1]
                            val jsonLicense = createClearKeyJson(keyId, key)
                            // Data URI for local callback
                            val licenseUrl = "data:application/json;base64," + 
                                Base64.encodeToString(jsonLicense.toByteArray(), Base64.NO_WRAP)
                            HttpMediaDrmCallback(licenseUrl, dataSourceFactory)
                        } else {
                            // Fallback if split failed but scheme is clearkey
                             HttpMediaDrmCallback(streamInfo.drmLicense, dataSourceFactory)
                        }
                    } else {
                        // 🌍 Case 2: Widevine/PlayReady or ClearKey via URL
                        // Pass the URL directly. If null, ExoPlayer tries to find it in Manifest
                        HttpMediaDrmCallback(streamInfo.drmLicense, dataSourceFactory)
                    }

                    // Apply Headers to DRM requests too (critical for Widevine)
                    streamInfo.headers.forEach { (k, v) -> 
                        drmCallback.setKeyRequestProperty(k, v) 
                    }

                    val drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(drmUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(false)
                        .build(drmCallback)
                    
                    mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
                    Timber.d("✅ DRM Manager attached for ${streamInfo.drmScheme}")

                } catch (e: Exception) {
                    Timber.e(e, "❌ Failed to setup DRM")
                }
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory) // Uses the configured factory
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
                                }
                                Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                                Player.STATE_ENDED -> binding.progressBar.visibility = View.GONE
                            }
                        }
                        
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            if (isInPipMode) updatePipParams()
                        }
                        
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            super.onPlayerError(error)
                            Timber.e(error, "❌ PLAYBACK ERROR: ${error.errorCodeName}")
                            binding.progressBar.visibility = View.GONE
                            
                            val msg = "Error: " + when(error.errorCode) {
                                androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "DRM License Failed"
                                androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Server Error (403/404)"
                                else -> error.message
                            }
                            binding.errorView.visibility = View.VISIBLE
                            binding.errorText.text = msg
                            Toast.makeText(this@ChannelPlayerActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error creating ExoPlayer")
            Toast.makeText(this, "Failed to initialize player", Toast.LENGTH_SHORT).show()
        }
        
        binding.playerView.apply {
            useController = true
            controllerShowTimeoutMs = 5000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    private fun createClearKeyJson(keyIdHex: String, keyHex: String): String {
        return try {
            val keyIdBase64 = hexToBase64Url(keyIdHex)
            val keyBase64 = hexToBase64Url(keyHex)
            """
            {
              "keys": [
                {
                  "kty": "oct",
                  "k": "$keyBase64",
                  "kid": "$keyIdBase64"
                }
              ],
              "type": "temporary"
            }
            """.trimIndent()
        } catch (e: Exception) {
            "{}"
        }
    }

    private fun hexToBase64Url(hex: String): String {
        return try {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        btnPip?.setOnClickListener { 
            if (!isLocked) {
                userRequestedPip = true
                enterPipMode() 
            }
        }
        btnSettings?.setOnClickListener { if (!isLocked) showQualityDialog() }
        btnAspectRatio?.setOnClickListener { if (!isLocked) toggleAspectRatio() }
        btnLock?.setOnClickListener { toggleLock() }
        btnRewind?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) - skipMs) }
        btnPlayPause?.setOnClickListener {
            if (!isLocked) {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
        btnForward?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) + skipMs) }
        btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
        btnMute?.setOnClickListener { if (!isLocked) toggleMute() }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
            Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
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
            TrackSelectionDialogBuilder(this, "Select Video Quality", player!!, C.TRACK_TYPE_VIDEO)
                .build().show()
        } catch (e: Exception) {
            Toast.makeText(this, "Quality settings unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPlayerViewInteractions() { binding.playerView.setOnClickListener(null) }
    
    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener { 
            if (binding.unlockButton.visibility == View.VISIBLE) hideUnlockButton() else showUnlockButton()
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
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel -> switchChannel(relatedChannel) }
        binding.relatedChannelsRecycler.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
            setHasFixedSize(true)
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        viewModel.relatedChannels.removeObservers(this)
        viewModel.relatedChannels.observe(this) { channels ->
            binding.relatedCount.text = channels.size.toString()
            relatedChannelsAdapter.submitList(channels)
            binding.relatedChannelsSection.visibility = if (channels.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "PiP not supported", Toast.LENGTH_SHORT).show()
            return
        }
        player?.let { if (!it.isPlaying) it.play() }
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
                val ratio = if (width > 0 && height > 0) Rational(width, height) else Rational(16, 9)
                
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
                    this, playPauseControlType, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                
                actions.add(RemoteAction(Icon.createWithResource(this, playPauseIconId), playPauseTitle, playPauseTitle, playPausePendingIntent))
                builder.setActions(actions)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(false)
                    builder.setSeamlessResizeEnabled(true)
                }
                
                if (enter) {
                    if (enterPictureInPictureMode(builder.build())) isInPipMode = true
                } else if (isInPipMode) {
                    setPictureInPictureParams(builder.build())
                }
            } catch (e: Exception) {
                Timber.e(e, "PiP Error")
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.playerView.useController = false 
            binding.lockOverlay.visibility = View.GONE
            binding.unlockButton.visibility = View.GONE
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            binding.playerView.hideController()
        } else {
            userRequestedPip = false
            if (isFinishing) return
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
            
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
                showUnlockButton()
            } else {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
                binding.playerView.postDelayed({ if (!isInPipMode) binding.playerView.showController() }, 150)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            userRequestedPip = true
            enterPipMode()
        }
    }

    override fun finish() {
        try {
            releasePlayer()
            isInPipMode = false
            userRequestedPip = false
            super.finish()
        } catch (e: Exception) {
            super.finish()
        }
    }
}
