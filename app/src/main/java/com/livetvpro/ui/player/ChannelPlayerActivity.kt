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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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
    private lateinit var relatedChannelsAdapter: RelatedChannelAdapter

    // UI Components
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

    // State
    private var isInPipMode = false
    private var isLocked = false
    private var isMuted = false
    private val skipMs = 10_000L
    private var userRequestedPip = false
    private var currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUnlockButtonRunnable = Runnable { binding.unlockButton.visibility = View.GONE }

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

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                    CONTROL_TYPE_PLAY -> { player?.play(); updatePipParams() }
                    CONTROL_TYPE_PAUSE -> { player?.pause(); updatePipParams() }
                }
            }
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

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run { finish(); return }
        
        // Initial setup
        tvChannelName?.text = channel.name
        binding.progressBar.visibility = View.VISIBLE
        
        applyOrientationSettings(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        bindControllerViewsExact()
        setupControlListenersExact()
        setupPlayerViewInteractions()
        setupLockOverlay()
        setupRelatedChannels()

        // Start Player
        setupPlayer()
        loadRelatedChannels()
    }

    private data class StreamInfo(
        val url: String,
        val headers: Map<String, String>,
        val drmScheme: String?,
        val drmLicense: String?
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
                "drmlicense" -> drmLicense = value
                "user-agent", "useragent" -> headers["User-Agent"] = value
                "referer", "referrer" -> headers["Referer"] = value
                "cookie" -> headers["Cookie"] = value
                "origin" -> headers["Origin"] = value
                else -> headers[key] = value
            }
        }
        return StreamInfo(url, headers, drmScheme, drmLicense)
    }

    /**
     * 🟢 CUSTOM DRM CALLBACK
     * Handles both Static ClearKey (locally) and Widevine/PlayReady (via Network with headers)
     */
    private inner class LocalMediaDrmCallback(
        private val streamInfo: StreamInfo,
        private val dataSourceFactory: DataSource.Factory
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest
        ): ByteArray {
            // Provisioning is rarely needed for basic playback, passing through if needed
            val url = request.defaultUrl + "&signedRequest=" + String(request.data)
            return executeHttp(url, ByteArray(0), emptyMap())
        }

        override fun executeKeyRequest(
            uuid: UUID,
            request: ExoMediaDrm.KeyRequest
        ): ByteArray {
            // 1. Handle ClearKey Locally
            if (C.CLEARKEY_UUID == uuid) {
                if (streamInfo.drmLicense != null && streamInfo.drmLicense.contains(":")) {
                    Timber.d("🔐 Handling ClearKey Locally")
                    return createClearKeyJson(streamInfo.drmLicense).toByteArray(Charsets.UTF_8)
                }
            }

            // 2. Handle Widevine / PlayReady via Network
            var url = request.licenseServerUrl
            if (streamInfo.drmLicense?.startsWith("http") == true) {
                url = streamInfo.drmLicense
            }
            
            if (url.isNullOrEmpty()) {
                // If URL is missing, try to find it in the M3U "drmLicense" field or throw
                if (streamInfo.drmLicense?.startsWith("http") == true) {
                    url = streamInfo.drmLicense
                } else {
                    Timber.e("❌ No License URL found for Widevine/PlayReady")
                    throw Exception("No License URL")
                }
            }

            Timber.d("🌍 Fetching DRM License from: $url")
            return executeHttp(url, request.data, streamInfo.headers)
        }

        private fun executeHttp(url: String, data: ByteArray, headers: Map<String, String>): ByteArray {
            val dataSource = dataSourceFactory.createDataSource()
            val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(url)
                .setHttpMethod(androidx.media3.datasource.DataSpec.HTTP_METHOD_POST)
                .setHttpBody(data)
                .setHttpRequestHeaders(headers)
                .build()

            val inputStream = androidx.media3.datasource.DataSourceInputStream(dataSource, dataSpec)
            return try {
                inputStream.readBytes()
            } finally {
                inputStream.close()
            }
        }

        private fun createClearKeyJson(rawKeys: String): String {
            // format: keyId:key (potentially multiple pairs could be handled here if split by comma)
            try {
                val keyPairs = rawKeys.split(",") // handle multiple keys if comma separated
                val keysJson = keyPairs.joinToString(",") { pair ->
                    val parts = pair.split(":")
                    val kid = hexToBase64Url(parts[0].trim())
                    val k = hexToBase64Url(parts[1].trim())
                    """{"kty":"oct","k":"$k","kid":"$kid"}"""
                }
                return """{"keys":[$keysJson],"type":"temporary"}"""
            } catch (e: Exception) {
                Timber.e(e, "Error creating ClearKey JSON")
                return "{}"
            }
        }

        private fun hexToBase64Url(hex: String): String {
            return try {
                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (e: Exception) { "" }
        }
    }

    private fun setupPlayer() {
        releasePlayer()
        trackSelector = DefaultTrackSelector(this)

        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            // 1. Setup Data Source (for Manifest & Segments)
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(streamInfo.headers["User-Agent"] ?: "LiveTVPro/1.0")
                .setDefaultRequestProperties(streamInfo.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)

            // 2. Setup DRM (if needed)
            val drmSessionManagerProvider = if (streamInfo.drmScheme != null) {
                val uuid = when (streamInfo.drmScheme.lowercase()) {
                    "clearkey" -> C.CLEARKEY_UUID
                    "playready" -> C.PLAYREADY_UUID
                    else -> C.WIDEVINE_UUID
                }

                val drmCallback = LocalMediaDrmCallback(streamInfo, httpDataSourceFactory)
                
                val drmSessionManager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(false)
                    .build(drmCallback)

                androidx.media3.exoplayer.drm.DrmSessionManagerProvider { drmSessionManager }
            } else {
                androidx.media3.exoplayer.drm.DrmSessionManagerProvider { androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED }
            }

            // 3. Build Player
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpDataSourceFactory)
                .setDrmSessionManagerProvider(drmSessionManagerProvider)

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector!!)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(skipMs)
                .setSeekForwardIncrementMs(skipMs)
                .build()

            binding.playerView.player = player
            
            // 4. Start Playback
            val mediaItem = MediaItem.Builder()
                .setUri(streamInfo.url)
                // We handle DRM in the factory, but setting it here doesn't hurt for metadata
                .build()

            player?.setMediaItem(mediaItem)
            player?.prepare()
            player?.playWhenReady = true

            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            binding.progressBar.visibility = View.GONE
                            binding.errorView.visibility = View.GONE
                            updatePlayPauseIcon(true)
                            updatePipParams()
                        }
                        Player.STATE_ENDED -> binding.progressBar.visibility = View.GONE
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    binding.progressBar.visibility = View.GONE
                    binding.errorView.visibility = View.VISIBLE
                    val cause = error.errorCodeName
                    val msg = error.message ?: "Unknown Error"
                    binding.errorText.text = "Error: $cause\n$msg"
                    Timber.e(error, "Playback Error")
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                    if (isInPipMode) updatePipParams()
                }
            })

        } catch (e: Exception) {
            Timber.e(e, "Error initializing player")
            Toast.makeText(this, "Player Init Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================================
    // UI & CONTROLLER LOGIC (Same as before, kept for completeness)
    // =========================================================================================

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
        
        updateMuteIcon()
    }

    private fun setupControlListenersExact() {
        btnBack?.setOnClickListener { if (!isLocked) finish() }
        btnPip?.setOnClickListener { if (!isLocked) { userRequestedPip = true; enterPipMode() } }
        btnSettings?.setOnClickListener { if (!isLocked) showQualityDialog() }
        btnLock?.setOnClickListener { toggleLock() }
        btnRewind?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) - skipMs) }
        btnPlayPause?.setOnClickListener { if (!isLocked) { player?.let { if (it.isPlaying) it.pause() else it.play() } } }
        btnForward?.setOnClickListener { if (!isLocked) player?.seekTo((player?.currentPosition ?: 0) + skipMs) }
        btnMute?.setOnClickListener { if (!isLocked) toggleMute() }
        btnFullscreen?.setOnClickListener { if (!isLocked) toggleFullscreen() }
        btnAspectRatio?.setOnClickListener { if (!isLocked) toggleAspectRatio() }
    }
    
    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        btnPlayPause?.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }
    
    private fun toggleMute() {
        player?.let {
            isMuted = !isMuted
            it.volume = if (isMuted) 0f else 1f
            updateMuteIcon()
        }
    }

    private fun updateMuteIcon() {
        btnMute?.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun showQualityDialog() {
        if (trackSelector == null || player == null) return
        try {
            TrackSelectionDialogBuilder(this, "Select Quality", player!!, C.TRACK_TYPE_VIDEO).build().show()
        } catch (_: Exception) {}
    }

    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        binding.playerView.resizeMode = currentResizeMode
        val msg = when(currentResizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            else -> "Fit"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toggleFullscreen() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
    
    private fun showUnlockButton() {
        binding.unlockButton.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        mainHandler.postDelayed(hideUnlockButtonRunnable, 3000)
    }

    private fun hideUnlockButton() {
        mainHandler.removeCallbacks(hideUnlockButtonRunnable)
        binding.unlockButton.visibility = View.GONE
    }
    
    private fun setupLockOverlay() {
        binding.unlockButton.setOnClickListener { toggleLock() }
        binding.lockOverlay.setOnClickListener { 
            if (binding.unlockButton.visibility == View.VISIBLE) hideUnlockButton() else showUnlockButton()
        }
        binding.lockOverlay.visibility = View.GONE
        binding.unlockButton.visibility = View.GONE
        
        binding.retryButton.setOnClickListener {
            binding.errorView.visibility = View.GONE
            setupPlayer()
        }
    }

    // =========================================================================================
    // ORIENTATION & PIP LOGIC
    // =========================================================================================

    private fun applyOrientationSettings(isLandscape: Boolean) {
        val params = binding.playerContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isLandscape) {
            binding.playerView.hideController()
            binding.playerView.controllerAutoShow = false
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            params.dimensionRatio = null
            params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            binding.relatedChannelsSection.visibility = View.GONE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen_exit)
            hideSystemUI()
        } else {
            binding.playerView.controllerAutoShow = true
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            params.dimensionRatio = "16:9"
            params.height = 0
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            binding.relatedChannelsSection.visibility = View.VISIBLE
            btnFullscreen?.setImageResource(R.drawable.ic_fullscreen)
            showSystemUI()
        }
        binding.playerContainer.layoutParams = params
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showSystemUI() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
    }
    
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationSettings(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            binding.playerView.useController = false
            updatePipParams(true)
        }
    }

    private fun updatePipParams(enter: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val format = player?.videoFormat
            val width = format?.width ?: 16
            val height = format?.height ?: 9
            val ratio = Rational(if (width > 0) width else 16, if (height > 0) height else 9)
            
            val actions = ArrayList<RemoteAction>()
            val isPlaying = player?.isPlaying == true
            val icon = Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            val title = if (isPlaying) "Pause" else "Play"
            val intent = Intent(ACTION_MEDIA_CONTROL).apply { putExtra(EXTRA_CONTROL_TYPE, if (isPlaying) CONTROL_TYPE_PAUSE else CONTROL_TYPE_PLAY) }
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            actions.add(RemoteAction(icon, title, title, pendingIntent))

            val params = PictureInPictureParams.Builder()
                .setAspectRatio(ratio)
                .setActions(actions)
                .build()

            if (enter) enterPictureInPictureMode(params) else setPictureInPictureParams(params)
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
            if (!isFinishing) {
                applyOrientationSettings(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
                if (!isLocked) binding.playerView.useController = true
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPipMode && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    // =========================================================================================
    // CLEANUP & RELATED CHANNELS
    // =========================================================================================
    
    private fun setupRelatedChannels() {
        relatedChannelsAdapter = RelatedChannelAdapter { switchChannel(it) }
        binding.relatedChannelsRecycler.apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@ChannelPlayerActivity, 3)
            adapter = relatedChannelsAdapter
        }
    }

    private fun loadRelatedChannels() {
        viewModel.loadRelatedChannels(channel.categoryId, channel.id)
        viewModel.relatedChannels.observe(this) { 
            binding.relatedCount.text = it.size.toString()
            relatedChannelsAdapter.submitList(it)
            binding.relatedChannelsSection.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun switchChannel(newChannel: Channel) {
        releasePlayer()
        channel = newChannel
        tvChannelName?.text = channel.name
        setupPlayer()
        loadRelatedChannels()
    }
    
    private fun setupPlayerViewInteractions() { binding.playerView.setOnClickListener(null) }

    private fun releasePlayer() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
    }
}
