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
import android.net.Uri
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

    // ==========================================
    //  UPDATED PLAYER SETUP WITH DRM SUPPORT
    // ==========================================
    private fun setupPlayer() {
        player?.release()
        trackSelector = DefaultTrackSelector(this)

        try {
            // 1. Parse URL to separate Clean URL, Headers, and DRM Params
            val (cleanUrl, headers, drmParams) = parseUrlAndDrm(channel.streamUrl)

            Timber.d("▶ Setup Player: $cleanUrl")
            Timber.d("   Headers: ${headers.size}")
            Timber.d("   DRM: ${drmParams["drmScheme"] ?: "None"}")

            // 2. Prepare HTTP DataSource with Headers
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(headers["User-Agent"] ?: "LiveTVPro/1.0")

            // 3. Build MediaItem with DRM Configuration
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(cleanUrl)

            // DRM Logic
            if (drmParams.containsKey("drmScheme")) {
                val scheme = drmParams["drmScheme"]!!
                val license = drmParams["drmLicense"]

                if (scheme.equals("clearkey", ignoreCase = true) && license != null) {
                    // Handle ClearKey (convert id:key to JSON data URI)
                    val drmConfig = buildClearKeyConfig(license)
                    if (drmConfig != null) {
                        mediaItemBuilder.setDrmConfiguration(drmConfig)
                        Timber.d("🔓 ClearKey DRM configured")
                    }
                } else if (scheme.equals("widevine", ignoreCase = true) && license != null) {
                    // Handle Widevine
                    mediaItemBuilder.setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(license)
                            .setMultiSession(true)
                            .build()
                    )
                    Timber.d("🔐 Widevine DRM configured")
                }
            }

            // 4. Build ExoPlayer
            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                )
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .build().also { exo ->
                    binding.playerView.player = exo
                    exo.setMediaItem(mediaItemBuilder.build())
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
                                Player.STATE_IDLE -> {}
                            }
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                            if (isInPipMode) updatePipParams()
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Timber.e(error, "Playback error")
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@ChannelPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
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
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
    }

    /**
     * Helper: Parse Clean URL, Headers, and DRM params from stored string
     */
    private fun parseUrlAndDrm(rawUrl: String): Triple<String, Map<String, String>, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        val drmParams = mutableMapOf<String, String>()
        
        // 1. Split by pipe (storage format from M3uParser)
        val parts = rawUrl.split("|")
        val streamUrl = parts[0].trim()

        // 2. Parse inline parameters (User-Agent, Cookie, etc.)
        for (i in 1 until parts.size) {
            val part = parts[i].trim()
            val eqIndex = part.indexOf('=')
            if (eqIndex > 0) {
                val key = part.substring(0, eqIndex).trim()
                val value = part.substring(eqIndex + 1).trim()
                
                when (key.lowercase()) {
                    "drmscheme" -> drmParams["drmScheme"] = value
                    "drmlicense" -> drmParams["drmLicense"] = value
                    "user-agent", "useragent" -> headers["User-Agent"] = value
                    "referer", "referrer" -> headers["Referer"] = value
                    "cookie" -> headers["Cookie"] = value
                    "origin" -> headers["Origin"] = value
                    else -> headers[key] = value
                }
            }
        }

        // 3. Parse URL Query Parameters (common in some m3u8 links)
        try {
            val uri = Uri.parse(streamUrl)
            uri.getQueryParameter("drmScheme")?.let { drmParams["drmScheme"] = it }
            uri.getQueryParameter("drmLicense")?.let { drmParams["drmLicense"] = it }
        } catch (e: Exception) {
            Timber.w("Failed to parse URI params: ${e.message}")
        }

        return Triple(streamUrl, headers, drmParams)
    }

    /**
     * Helper: Build ClearKey config from "id:key" string
     */
    private fun buildClearKeyConfig(licenseStr: String): MediaItem.DrmConfiguration? {
        try {
            // licenseStr format: "kid:key" (hex strings)
            val parts = licenseStr.split(":")
            if (parts.size != 2) return null

            val kidHex = parts[0]
            val keyHex = parts[1]

            // Convert Hex to Base64 (URL Safe, No Padding, No Wrap)
            val kidBytes = hexToBytes(kidHex)
            val keyBytes = hexToBytes(keyHex)
            
            val kidB64 = Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val keyB64 = Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

            // Construct ClearKey JSON
            val clearKeyJson = """{"keys":[{"kty":"oct","k":"$keyB64","kid":"$kidB64"}],"type":"temporary"}"""
            
            // Encode JSON to Base64 for Data URI
            val jsonB64 = Base64.encodeToString(clearKeyJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val dataUri = "data:application/json;base64,$jsonB64"

            return MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                .setLicenseUri(dataUri)
                .build()

        } catch (e: Exception) {
            Timber.e(e, "Failed to build ClearKey config")
            return null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    // ==========================================
    //  UI & LIFECYCLE (Standard)
    // ==========================================

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
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            binding.playerView.controllerAutoShow = true
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
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
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
        try { unregisterReceiver(pipReceiver) } catch (e: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun releasePlayer() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
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
        
        btnAspectRatio?.visibility = View.VISIBLE
        btnPip?.visibility = View.VISIBLE
        btnFullscreen?.visibility = View.VISIBLE
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        btnPip?.setOnClickListener { if (!isLocked) { userRequestedPip = true; enterPipMode() } }
        btnSettings?.setOnClickListener { if (!isLocked) showQualityDialog() }
        btnAspectRatio?.setOnClickListener { if (!isLocked) toggleAspectRatio() }
        btnLock?.setOnClickListener { toggleLock() }
        btnRewind?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) - skipMs) }
        btnPlayPause?.setOnClickListener { if (!isLocked) player?.let { if (it.isPlaying) it.pause() else it.play() } }
        btnForward?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) + skipMs) }
        btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
        btnMute?.setOnClickListener { if (!isLocked) toggleMute() }
    }

    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            Toast.makeText(this, if (isMuted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.playerView.resizeMode = currentResizeMode
        Toast.makeText(this, if(currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) "Fill" else if(currentResizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else "Fit", Toast.LENGTH_SHORT).show()
    }

    private fun showQualityDialog() {
        if (trackSelector != null && player != null) {
            TrackSelectionDialogBuilder(this, "Select Quality", player!!, C.TRACK_TYPE_VIDEO).build().show()
        }
    }

    private fun setupPlayerViewInteractions() { binding.playerView.setOnClickListener(null) }
    
    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener { 
            if (binding.unlockButton.visibility == View.VISIBLE) hideUnlockButton() else showUnlockButton()
        }
    }

    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { relatedChannel -> switchChannel(relatedChannel) }
        binding.relatedChannelsRecycler.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
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
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // PiP Logic
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return

        player?.let { if (!it.isPlaying) it.play() }
        binding.relatedChannelsSection.visibility = View.GONE
        binding.playerView.useController = false
        binding.lockOverlay.visibility = View.GONE
        
        updatePipParams(enter = true)
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val format = player?.videoFormat
                val ratio = if (format != null && format.width > 0 && format.height > 0) Rational(format.width, format.height) else Rational(16, 9)
                
                val builder = PictureInPictureParams.Builder()
                builder.setAspectRatio(ratio)
                
                val isPlaying = player?.isPlaying == true
                val icon = Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                val title = if (isPlaying) "Pause" else "Play"
                val intent = Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY)
                val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                
                builder.setActions(listOf(RemoteAction(icon, title, title, pendingIntent)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true)

                if (enter) enterPictureInPictureMode(builder.build()) else setPictureInPictureParams(builder.build())
            } catch (e: Exception) { Timber.e(e, "PiP Error") }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        
        if (isInPipMode) {
            binding.relatedChannelsSection.visibility = View.GONE
            binding.playerView.useController = false 
            binding.playerView.hideController()
        } else {
            userRequestedPip = false
            if (isFinishing) return
            
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            applyOrientationSettings(isLandscape)
            
            if (isLocked) {
                binding.playerView.useController = false
                binding.lockOverlay.visibility = View.VISIBLE
            } else {
                binding.playerView.useController = true
                binding.lockOverlay.visibility = View.GONE
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
        releasePlayer()
        super.finish()
    }
}
