package com.livetvpro.utils

import com.livetvpro.data.models.Channel
import org.json.JSONArray
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
        val groupTitle: String = "",
        val userAgent: String? = null,
        val cookies: Map<String, String> = emptyMap(),
        val httpHeaders: Map<String, String> = emptyMap()
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            // Check if it's a JSON array first
            if (m3uUrl.trim().startsWith("[") || m3uUrl.trim().startsWith("{")) {
                Timber.d("Detected JSON format playlist")
                return parseJsonPlaylist(m3uUrl)
            }
            
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

                // Check if response is JSON
                val trimmedContent = content.trim()
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    Timber.d("Response is JSON format")
                    return parseJsonPlaylist(trimmedContent)
                }

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

    /**
     * Parse JSON playlist format
     * Format: [{"name":"...","link":"...","logo":"...","cookie":"..."}]
     */
    fun parseJsonPlaylist(jsonContent: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        
        try {
            val jsonArray = JSONArray(jsonContent)
            
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                
                val name = item.optString("name", "Unknown Channel")
                val link = item.optString("link", "")
                val logo = item.optString("logo", "")
                val cookie = item.optString("cookie", "")
                val userAgent = item.optString("user-agent", null)
                val referer = item.optString("referer", null)
                val origin = item.optString("origin", null)
                
                if (link.isNotEmpty()) {
                    val headers = mutableMapOf<String, String>()
                    val cookies = mutableMapOf<String, String>()
                    
                    // Parse cookie if present
                    if (cookie.isNotEmpty()) {
                        parseCookieString(cookie, cookies)
                    }
                    
                    // Add other headers
                    referer?.let { headers["Referer"] = it }
                    origin?.let { headers["Origin"] = it }
                    
                    channels.add(
                        M3uChannel(
                            name = name,
                            logoUrl = logo,
                            streamUrl = link,
                            groupTitle = "",
                            userAgent = userAgent,
                            cookies = cookies,
                            httpHeaders = headers
                        )
                    )
                    
                    Timber.d("Parsed JSON channel: $name with ${cookies.size} cookies")
                }
            }
            
            Timber.d("Parsed ${channels.size} channels from JSON playlist")
        } catch (e: Exception) {
            Timber.e(e, "Error parsing JSON playlist")
        }
        
        return channels
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
        var currentUserAgent: String? = null
        var currentCookies = mutableMapOf<String, String>()
        var currentHeaders = mutableMapOf<String, String>()

        for (i in lines.indices) {
            val line = lines[i].trim()

            when {
                line.startsWith("#EXTINF:") -> {
                    currentName = extractChannelName(line)
                    currentLogo = extractAttribute(line, "tvg-logo")
                    currentGroup = extractAttribute(line, "group-title")
                }
                
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = line.substringAfter("http-user-agent=").trim()
                    Timber.d("Parsed user-agent: $currentUserAgent")
                }
                
                line.startsWith("#EXTVLCOPT:http-origin=") -> {
                    val origin = line.substringAfter("http-origin=").trim()
                    currentHeaders["Origin"] = origin
                    Timber.d("Parsed origin: $origin")
                }
                
                line.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    val referrer = line.substringAfter("http-referrer=").trim()
                    currentHeaders["Referer"] = referrer
                    Timber.d("Parsed referrer: $referrer")
                }
                
                line.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonStr = line.substringAfter("#EXTHTTP:").trim()
                        val json = org.json.JSONObject(jsonStr)
                        
                        if (json.has("cookie")) {
                            val cookieStr = json.getString("cookie")
                            parseCookieString(cookieStr, currentCookies)
                            Timber.d("Parsed cookie from #EXTHTTP")
                        }
                        
                        json.keys().forEach { key ->
                            if (key != "cookie") {
                                currentHeaders[key] = json.getString(key)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error parsing #EXTHTTP line: $line")
                    }
                }
                
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (currentName.isNotEmpty()) {
                        val (streamUrl, inlineHeaders) = parseInlineHeaders(line)
                        
                        val finalHeaders = currentHeaders.toMutableMap().apply {
                            putAll(inlineHeaders)
                        }
                        
                        channels.add(
                            M3uChannel(
                                name = currentName,
                                logoUrl = currentLogo,
                                streamUrl = streamUrl,
                                groupTitle = currentGroup,
                                userAgent = currentUserAgent,
                                cookies = currentCookies.toMap(),
                                httpHeaders = finalHeaders
                            )
                        )
                        
                        Timber.d("Added channel: $currentName with ${currentCookies.size} cookies, ${finalHeaders.size} headers")
                    }
                    
                    currentName = ""
                    currentLogo = ""
                    currentGroup = ""
                    currentUserAgent = null
                    currentCookies = mutableMapOf()
                    currentHeaders = mutableMapOf()
                }
            }
        }

        Timber.d("Parsed ${channels.size} channels from M3U")
        return channels
    }

    private fun extractChannelName(line: String): String {
        val lastCommaIndex = line.lastIndexOf(',')
        return if (lastCommaIndex != -1 && lastCommaIndex < line.length - 1) {
            line.substring(lastCommaIndex + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attributeName: String): String {
        val pattern = """$attributeName="([^"]*)"""".toRegex()
        val match = pattern.find(line)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    private fun parseCookieString(cookieStr: String, cookieMap: MutableMap<String, String>) {
        cookieStr.split(";").forEach { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) {
                cookieMap[parts[0].trim()] = parts[1].trim()
            }
        }
    }

    private fun parseInlineHeaders(urlLine: String): Pair<String, Map<String, String>> {
        val parts = urlLine.split("|")
        
        if (parts.size == 1) {
            return Pair(urlLine, emptyMap())
        }
        
        val streamUrl = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        
        for (i in 1 until parts.size) {
            val headerPart = parts[i].trim()
            val separatorIndex = headerPart.indexOf('=')
            
            if (separatorIndex > 0) {
                val headerName = headerPart.substring(0, separatorIndex).trim()
                val headerValue = headerPart.substring(separatorIndex + 1).trim()
                
                when (headerName.lowercase()) {
                    "referer", "referrer" -> headers["Referer"] = headerValue
                    "user-agent", "useragent" -> headers["User-Agent"] = headerValue
                    "origin" -> headers["Origin"] = headerValue
                    "cookie" -> parseCookieString(headerValue, headers)
                    else -> headers[headerName] = headerValue
                }
                
                Timber.d("Parsed inline header: $headerName = ${headerValue.take(50)}...")
            }
        }
        
        return Pair(streamUrl, headers)
    }

    private fun generateChannelId(streamUrl: String, name: String): String {
        val combined = "$streamUrl|$name"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(combined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "m3u_${combined.hashCode().toString(16)}"
        }
    }

    fun convertToChannels(
        m3uChannels: List<M3uChannel>,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return m3uChannels.map { m3uChannel ->
            // Build stream URL with inline headers for compatibility
            val streamUrlWithHeaders = buildStreamUrlWithHeaders(m3uChannel)
            
            Channel(
                id = generateChannelId(m3uChannel.streamUrl, m3uChannel.name),
                name = m3uChannel.name,
                logoUrl = m3uChannel.logoUrl.ifEmpty { 
                    "https://via.placeholder.com/150?text=${m3uChannel.name.take(2)}" 
                },
                streamUrl = streamUrlWithHeaders,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }

    /**
     * Build stream URL with inline headers for storage
     * This allows the player to extract headers later
     */
    private fun buildStreamUrlWithHeaders(m3uChannel: M3uChannel): String {
        val parts = mutableListOf(m3uChannel.streamUrl)
        
        // Add user agent
        m3uChannel.userAgent?.let {
            parts.add("User-Agent=$it")
        }
        
        // Add other headers
        m3uChannel.httpHeaders.forEach { (key, value) ->
            parts.add("$key=$value")
        }
        
        // Add cookies as single Cookie header
        if (m3uChannel.cookies.isNotEmpty()) {
            val cookieStr = m3uChannel.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            parts.add("Cookie=$cookieStr")
        }
        
        return parts.joinToString("|")
    }
}
