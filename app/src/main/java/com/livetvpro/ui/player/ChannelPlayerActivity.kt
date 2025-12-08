// ✅ UPDATED: ChannelPlayerActivity with DRM (ClearKey) support

package com.livetvpro.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ActivityChannelPlayerBinding
import timber.log.Timber
import java.util.UUID

@UnstableApi
class ChannelPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var channel: Channel

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

        channel = intent.getParcelableExtra(EXTRA_CHANNEL) ?: run {
            finish()
            return
        }

        setupPlayer()
    }

    /**
     * ✅ Parse stream URL for DRM info
     * Format: url|drmScheme=clearkey&drmLicense=keyId:key
     */
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
            
            when {
                part.startsWith("drmScheme=", ignoreCase = true) -> {
                    drmScheme = part.substringAfter("=").lowercase()
                    Timber.d("📌 DRM Scheme: $drmScheme")
                }
                part.startsWith("drmLicense=", ignoreCase = true) -> {
                    val license = part.substringAfter("=")
                    val licenseParts = license.split(":")
                    if (licenseParts.size == 2) {
                        drmKeyId = licenseParts[0].trim()
                        drmKey = licenseParts[1].trim()
                        Timber.d("🔑 DRM KeyID: ${drmKeyId?.take(16)}...")
                        Timber.d("🔑 DRM Key: ${drmKey?.take(16)}...")
                    }
                }
                part.contains("=") -> {
                    val separatorIndex = part.indexOf('=')
                    val headerName = part.substring(0, separatorIndex).trim()
                    val headerValue = part.substring(separatorIndex + 1).trim()
                    
                    when (headerName.lowercase()) {
                        "referer", "referrer" -> headers["Referer"] = headerValue
                        "user-agent", "useragent" -> headers["User-Agent"] = headerValue
                        "origin" -> headers["Origin"] = headerValue
                        "cookie" -> headers["Cookie"] = headerValue
                        else -> headers[headerName] = headerValue
                    }
                }
            }
        }
        
        return StreamInfo(url, headers, drmScheme, drmKeyId, drmKey)
    }

    private fun setupPlayer() {
        player?.release()

        try {
            val streamInfo = parseStreamUrl(channel.streamUrl)
            
            Timber.d("═══════════════════════════════════════")
            Timber.d("🎬 Setting up player for: ${channel.name}")
            Timber.d("📺 URL: ${streamInfo.url.take(100)}")
            Timber.d("🔒 DRM: ${streamInfo.drmScheme ?: "None"}")
            Timber.d("📡 Headers: ${streamInfo.headers.size}")
            Timber.d("═══════════════════════════════════════")

            // Add default user agent if not provided
            val headers = streamInfo.headers.toMutableMap()
            if (!headers.containsKey("User-Agent")) {
                headers["User-Agent"] = "LiveTVPro/1.0"
            }

            // Create DataSource with headers
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(headers)
                .setConnectTimeoutMs(30000)
                .setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true)

            // ✅ NEW: Setup DRM if present
            val mediaSourceFactory = if (streamInfo.drmScheme != null && 
                                         streamInfo.drmKeyId != null && 
                                         streamInfo.drmKey != null) {
                Timber.d("🔐 Setting up DRM protection...")
                
                val drmSessionManager = when (streamInfo.drmScheme.lowercase()) {
                    "clearkey" -> {
                        createClearKeyDrmManager(
                            streamInfo.drmKeyId,
                            streamInfo.drmKey,
                            dataSourceFactory
                        )
                    }
                    else -> {
                        Timber.e("❌ Unsupported DRM scheme: ${streamInfo.drmScheme}")
                        null
                    }
                }
                
                if (drmSessionManager != null) {
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                        .setDrmSessionManagerProvider { drmSessionManager }
                } else {
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(dataSourceFactory)
                }
            } else {
                Timber.d("🔓 No DRM - regular stream")
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory)
            }

            // Create ExoPlayer
            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build().also { exo ->
                    binding.playerView.player = exo
                    
                    val mediaItem = MediaItem.fromUri(streamInfo.url)
                    exo.setMediaItem(mediaItem)
                    exo.prepare()
                    exo.playWhenReady = true

                    exo.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                androidx.media3.common.Player.STATE_READY -> {
                                    binding.progressBar.visibility = android.view.View.GONE
                                    Timber.d("✅ Player ready")
                                }
                                androidx.media3.common.Player.STATE_BUFFERING -> {
                                    binding.progressBar.visibility = android.view.View.VISIBLE
                                }
                                else -> {}
                            }
                        }
                        
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Timber.e(error, "❌ Playback error")
                            binding.progressBar.visibility = android.view.View.GONE
                            
                            val errorMessage = when {
                                error.message?.contains("drm", ignoreCase = true) == true -> 
                                    "DRM error: Unable to decrypt stream"
                                error.message?.contains("clearkey", ignoreCase = true) == true -> 
                                    "ClearKey DRM error: Invalid license keys"
                                else -> 
                                    "Playback error: ${error.message}"
                            }
                            
                            Toast.makeText(
                                this@ChannelPlayerActivity,
                                errorMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error creating ExoPlayer")
            Toast.makeText(this, "Failed to initialize player", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ✅ Create ClearKey DRM Session Manager
     * ClearKey is a basic DRM system that uses simple key-value pairs
     */
    @UnstableApi
    private fun createClearKeyDrmManager(
        keyId: String,
        key: String,
        dataSourceFactory: DefaultHttpDataSource.Factory
    ): DefaultDrmSessionManager? {
        return try {
            // ClearKey UUID (defined in MPEG-CENC spec)
            val clearKeyUuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
            
            Timber.d("🔐 Creating ClearKey DRM manager")
            Timber.d("   KeyID: ${keyId.take(16)}...")
            Timber.d("   Key: ${key.take(16)}...")
            
            // Create a simple ClearKey license server (local JSON)
            val licenseUrl = "data:application/json;base64," + 
                android.util.Base64.encodeToString(
                    buildClearKeyLicense(keyId, key).toByteArray(),
                    android.util.Base64.NO_WRAP
                )
            
            val drmCallback = HttpMediaDrmCallback(licenseUrl, dataSourceFactory)
            
            DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(
                    clearKeyUuid,
                    FrameworkMediaDrm.DEFAULT_PROVIDER
                )
                .build(drmCallback).also {
                    Timber.d("✅ ClearKey DRM manager created successfully")
                }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to create ClearKey DRM manager")
            null
        }
    }

    /**
     * Build ClearKey license JSON
     * Format: {"keys":[{"kty":"oct","k":"<key>","kid":"<keyId>"}]}
     */
    private fun buildClearKeyLicense(keyId: String, key: String): String {
        // Convert hex strings to base64url (ClearKey format)
        val keyIdBase64 = hexToBase64Url(keyId)
        val keyBase64 = hexToBase64Url(key)
        
        return """
        {
            "keys": [{
                "kty": "oct",
                "k": "$keyBase64",
                "kid": "$keyIdBase64"
            }]
        }
        """.trimIndent()
    }

    /**
     * Convert hex string to base64url (RFC 4648)
     */
    private fun hexToBase64Url(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
