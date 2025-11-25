package com.livetvpro.utils

import com.livetvpro.data.models.Channel
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object M3uParser {

    data class M3uChannel(
        val name: String,
        val logoUrl: String,
        val streamUrl: String,
        val groupTitle: String = ""
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            val url = URL(m3uUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "LiveTVPro/1.0")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val content = reader.readText()
                reader.close()
                connection.disconnect()

                parseM3uContent(content)
            } else {
                Timber.e("Failed to fetch M3U: HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing M3U from URL: $m3uUrl")
            emptyList()
        }
    }

    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()

        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            Timber.e("Invalid M3U file format")
            return emptyList()
        }

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""

        for (i in lines.indices) {
            val line = lines[i].trim()

            if (line.startsWith("#EXTINF:")) {
                // Parse channel info
                currentName = extractChannelName(line)
                currentLogo = extractAttribute(line, "tvg-logo")
                currentGroup = extractAttribute(line, "group-title")
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                // This is the stream URL
                if (currentName.isNotEmpty()) {
                    channels.add(
                        M3uChannel(
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = line,
                            groupTitle = currentGroup
                        )
                    )
                }
                // Reset for next channel
                currentName = ""
                currentLogo = ""
                currentGroup = ""
            }
        }

        Timber.d("Parsed ${channels.size} channels from M3U")
        return channels
    }

    private fun extractChannelName(line: String): String {
        // Extract name after the last comma
        val lastCommaIndex = line.lastIndexOf(',')
        return if (lastCommaIndex != -1 && lastCommaIndex < line.length - 1) {
            line.substring(lastCommaIndex + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attributeName: String): String {
        // Extract attribute value like tvg-logo="..." or group-title="..."
        val pattern = """$attributeName="([^"]*)"""".toRegex()
        val match = pattern.find(line)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    /**
     * Generate a unique ID for an M3U channel based on its stream URL and name
     * This ensures consistent IDs across app restarts
     */
    private fun generateChannelId(streamUrl: String, name: String): String {
        // Create a unique identifier by combining URL and name
        val combined = "$streamUrl|$name"
        
        // Generate MD5 hash for consistent, unique ID
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(combined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback: use hash code if MD5 fails
            "m3u_${combined.hashCode().toString(16)}"
        }
    }

    fun convertToChannels(
        m3uChannels: List<M3uChannel>,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return m3uChannels.map { m3uChannel ->
            Channel(
                // ✅ Generate unique ID based on stream URL and name
                id = generateChannelId(m3uChannel.streamUrl, m3uChannel.name),
                name = m3uChannel.name,
                logoUrl = m3uChannel.logoUrl.ifEmpty { 
                    "https://via.placeholder.com/150?text=${m3uChannel.name.take(2)}" 
                },
                streamUrl = m3uChannel.streamUrl,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }
}
