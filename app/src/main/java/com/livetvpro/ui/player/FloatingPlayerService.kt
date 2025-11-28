package com.livetvpro.ui.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.livetvpro.R
import com.livetvpro.databinding.FloatingPlayerViewBinding
import timber.log.Timber

@UnstableApi
class FloatingPlayerService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var binding: FloatingPlayerViewBinding? = null
    private var player: ExoPlayer? = null
    
    private var streamUrl: String = ""
    private var channelName: String = ""
    
    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    companion object {
        private const val EXTRA_STREAM_URL = "stream_url"
        private const val EXTRA_CHANNEL_NAME = "channel_name"
        
        fun start(context: Context, streamUrl: String, channelName: String) {
            val intent = Intent(context, FloatingPlayerService::class.java).apply {
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPlayerService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        streamUrl = intent?.getStringExtra(EXTRA_STREAM_URL) ?: ""
        channelName = intent?.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        
        if (streamUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        
        createFloatingPlayer()
        return START_STICKY
    }

    private fun createFloatingPlayer() {
        // Inflate the floating view
        binding = FloatingPlayerViewBinding.inflate(LayoutInflater.from(this))
        floatingView = binding?.root
        
        // Setup window parameters
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
        
        // Add view to window manager
        windowManager?.addView(floatingView, params)
        
        // Setup player
        setupPlayer()
        
        // Setup touch listener for dragging
        setupTouchListener(params)
        
        // Setup button listeners
        setupButtonListeners()
    }

    private fun setupPlayer() {
        try {
            // Initialize ExoPlayer
            player = ExoPlayer.Builder(this).build()
            
            // Find PlayerView inside the floating container
            val playerView = binding?.floatingPlayerContainer?.findViewById<PlayerView>(R.id.floating_player_view)
            
            // If PlayerView doesn't exist, create it programmatically
            val actualPlayerView = playerView ?: PlayerView(this).apply {
                id = R.id.floating_player_view
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                useController = false
                binding?.floatingPlayerContainer?.addView(this)
            }
            
            actualPlayerView.player = player
            
            // Prepare media
            val mediaItem = MediaItem.fromUri(streamUrl)
            player?.apply {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
            
            Timber.d("Floating player initialized for: $channelName")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up floating player")
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtonListeners() {
        binding?.btnClose?.setOnClickListener {
            stopSelf()
        }
        
        binding?.btnExpand?.setOnClickListener {
            // Return to full player activity and resume playback
            val intent = Intent(this, ChannelPlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_CHANNEL_NAME, channelName)
            }
            startActivity(intent)
            stopSelf()
        }
        
        // Click on floating player to enter PiP mode (Android 8.0+)
        binding?.floatingPlayerContainer?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPicture()
            }
        }
    }
    
    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Create PiP intent to return to ChannelPlayerActivity
                val intent = Intent(this, ChannelPlayerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_STREAM_URL, streamUrl)
                    putExtra(EXTRA_CHANNEL_NAME, channelName)
                }
                startActivity(intent)
                
                // The activity will handle PiP mode
                stopSelf()
                
                Timber.d("Transitioning to PiP mode")
            } catch (e: Exception) {
                Timber.e(e, "Failed to enter PiP mode")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Release player
        player?.release()
        player = null
        
        // Remove floating view
        floatingView?.let {
            windowManager?.removeView(it)
        }
        floatingView = null
        binding = null
        
        Timber.d("Floating player destroyed")
    }
}
