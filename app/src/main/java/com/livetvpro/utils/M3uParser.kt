package com.livetvpro.utils

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import com.livetvpro.data.models.Channel

object M3uParser {

    data class M3uChannel(
        val name: String,
        val logoUrl: String,
        val streamUrl: String,
        val groupTitle: String = "",
        val userAgent: String? = null,
        val httpHeaders: Map<String, String> = emptyMap(),
        val drmScheme: String? = null,
        val drmLicenseKey: String? = null
    )

    suspend fun parseM3uFromUrl(m3uUrl: String): List<M3uChannel> {
        return try {
            Timber.d("🔥 FETCHING M3U FROM: $m3uUrl")
            
            val trimmedUrl = m3uUrl.trim()
            if (trimmedUrl.startsWith("[") || trimmedUrl.startsWith("{")) {
                return parseJsonPlaylist(trimmedUrl)
            }
            
            val url = URL(trimmedUrl)
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

                val trimmedContent = content.trim()
                if (trimmedContent.startsWith("[") || trimmedContent.startsWith("{")) {
                    return parseJsonPlaylist(trimmedContent)
                }

                parseM3uContent(content)
            } else {
                Timber.e("❌ Failed to fetch M3U: HTTP ${connection.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing M3U from URL")
            emptyList()
        }
    }

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
                    if (cookie.isNotEmpty()) headers["Cookie"] = cookie
                    referer?.let { headers["Referer"] = it }
                    origin?.let { headers["Origin"] = it }
                    
                    channels.add(M3uChannel(
                        name = name,
                        logoUrl = logo,
                        streamUrl = link,
                        userAgent = userAgent,
                        httpHeaders = headers
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Error parsing JSON playlist")
        }
        return channels
    }

    fun parseM3uContent(content: String): List<M3uChannel> {
        val channels = mutableListOf<M3uChannel>()
        val lines = content.lines()

        // ✅ FIX: Relaxed check. If it doesn't start with #EXTM3U, we still try to parse lines.
        if (lines.isEmpty()) return emptyList()

        var currentName = ""
        var currentLogo = ""
        var currentGroup = ""
        var currentUserAgent: String? = null
        var currentHeaders = mutableMapOf<String, String>()
        var currentDrmScheme: String? = null
        var currentDrmLicenseKey: String? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue
            if (trimmedLine.startsWith("#EXTM3U")) continue

            when {
                trimmedLine.startsWith("#EXTINF:") -> {
                    currentName = extractChannelName(trimmedLine)
                    currentLogo = extractAttribute(trimmedLine, "tvg-logo")
                    currentGroup = extractAttribute(trimmedLine, "group-title")
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                    currentUserAgent = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-origin=") -> {
                    currentHeaders["Origin"] = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTVLCOPT:http-referrer=") -> {
                    currentHeaders["Referer"] = trimmedLine.substringAfter("=").trim()
                }
                
                trimmedLine.startsWith("#EXTHTTP:") -> {
                    try {
                        val jsonStr = trimmedLine.substringAfter("#EXTHTTP:").trim()
                        val json = JSONObject(jsonStr)
                        
                        // Handle explicit cookie field
                        if (json.has("cookie")) {
                            currentHeaders["Cookie"] = json.getString("cookie")
                        }
                        
                        // Handle other headers
                        json.keys().forEach { key ->
                            if (!key.equals("cookie", ignoreCase = true)) {
                                currentHeaders[key] = json.getString(key)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w("Error parsing #EXTHTTP: ${e.message}")
                    }
                }
                
                trimmedLine.startsWith("#KODIPROP:") -> {
                    val prop = trimmedLine.substringAfter("#KODIPROP:").trim()
                    when {
                        prop.startsWith("inputstream.adaptive.license_type=") -> 
                            currentDrmScheme = prop.substringAfter("=").trim()
                        prop.startsWith("inputstream.adaptive.license_key=") -> 
                            currentDrmLicenseKey = prop.substringAfter("=").trim()
                    }
                }
                
                !trimmedLine.startsWith("#") -> {
                    // This is the URL line
                    if (currentName.isNotEmpty()) {
                        val (streamUrl, inlineHeaders, inlineDrmInfo) = parseInlineHeadersAndDrm(trimmedLine)
                        
                        val finalHeaders = currentHeaders.toMutableMap()
                        finalHeaders.putAll(inlineHeaders)
                        
                        val finalDrmScheme = inlineDrmInfo.first ?: currentDrmScheme
                        val finalDrmKey = inlineDrmInfo.second ?: currentDrmLicenseKey
                        
                        channels.add(M3uChannel(
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = streamUrl,
                            groupTitle = currentGroup,
                            userAgent = currentUserAgent,
                            httpHeaders = finalHeaders,
                            drmScheme = finalDrmScheme,
                            drmLicenseKey = finalDrmKey
                        ))
                    }
                    
                    // Reset for next channel
                    currentName = ""
                    currentLogo = ""
                    currentGroup = ""
                    currentUserAgent = null
                    currentHeaders = mutableMapOf()
                    currentDrmScheme = null
                    currentDrmLicenseKey = null
                }
            }
        }
        return channels
    }

    private fun extractChannelName(line: String): String {
        val lastComma = line.lastIndexOf(',')
        return if (lastComma != -1 && lastComma < line.length - 1) {
            line.substring(lastComma + 1).trim()
        } else {
            "Unknown Channel"
        }
    }

    private fun extractAttribute(line: String, attribute: String): String {
        // Handle quoted attributes: attribute="value"
        val pattern = """$attribute="([^"]*)"""".toRegex()
        val match = pattern.find(line)
        if (match != null) return match.groupValues[1]
        
        // Fallback for unquoted attributes (rare but possible)
        val unquotedPattern = """$attribute=([^ ]*)""".toRegex()
        val unquotedMatch = unquotedPattern.find(line)
        return unquotedMatch?.groupValues?.get(1) ?: ""
    }

    private fun parseInlineHeadersAndDrm(urlLine: String): Triple<String, Map<String, String>, Pair<String?, String?>> {
        val parts = urlLine.split("|")
        val url = parts[0].trim()
        val headers = mutableMapOf<String, String>()
        var drmScheme: String? = null
        var drmLicense: String? = null

        if (parts.size > 1) {
            for (i in 1 until parts.size) {
                val part = parts[i]
                val eqIndex = part.indexOf('=')
                if (eqIndex != -1) {
                    val key = part.substring(0, eqIndex).trim()
                    val value = part.substring(eqIndex + 1).trim()
                    
                    when (key.lowercase()) {
                        "drmscheme" -> drmScheme = value
                        "drmlicense" -> drmLicense = value
                        "user-agent", "useragent" -> headers["User-Agent"] = value
                        "referer", "referrer" -> headers["Referer"] = value
                        "cookie" -> headers["Cookie"] = value
                        else -> headers[key] = value
                    }
                }
            }
        }
        return Triple(url, headers, Pair(drmScheme, drmLicense))
    }

    private fun generateChannelId(streamUrl: String, name: String): String {
        val combined = "$streamUrl|$name"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(combined.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "m3u_${combined.hashCode()}"
        }
    }

    fun convertToChannels(
        m3uChannels: List<M3uChannel>,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return m3uChannels.map { m3u ->
            val metaUrl = buildStreamUrlWithMetadata(m3u)
            Channel(
                id = generateChannelId(m3u.streamUrl, m3u.name),
                name = m3u.name,
                logoUrl = m3u.logoUrl,
                streamUrl = metaUrl,
                categoryId = categoryId,
                categoryName = categoryName
            )
        }
    }

    private fun buildStreamUrlWithMetadata(m3u: M3uChannel): String {
        val parts = mutableListOf(m3u.streamUrl)
        m3u.userAgent?.let { parts.add("User-Agent=$it") }
        m3u.httpHeaders.forEach { (k, v) -> parts.add("$k=$v") }
        m3u.drmScheme?.let { parts.add("drmScheme=$it") }
        m3u.drmLicenseKey?.let { parts.add("drmLicense=$it") }
        return parts.joinToString("|")
    }
}
