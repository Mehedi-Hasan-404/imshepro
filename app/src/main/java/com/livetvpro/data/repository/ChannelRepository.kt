package com.livetvpro.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.livetvpro.data.models.Channel
import com.livetvpro.utils.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    suspend fun getChannelsByCategory(categoryId: String): List<Channel> {
        return try {
            firestore.collection("channels")
                .whereEqualTo("categoryId", categoryId)
                .get()
                .await()
                .documents
                .mapNotNull { it.toObject(Channel::class.java)?.copy(id = it.id) }
        } catch (e: Exception) {
            Timber.e(e, "Error loading channels for category: $categoryId")
            emptyList()
        }
    }

    suspend fun getChannelsFromM3u(
        m3uUrl: String,
        categoryId: String,
        categoryName: String
    ): List<Channel> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Fetching channels from M3U: $m3uUrl")
                val m3uChannels = M3uParser.parseM3uFromUrl(m3uUrl)
                val channels = M3uParser.convertToChannels(m3uChannels, categoryId, categoryName)
                Timber.d("Fetched ${channels.size} channels from M3U")
                channels
            } catch (e: Exception) {
                Timber.e(e, "Error fetching channels from M3U: $m3uUrl")
                emptyList()
            }
        }
    }

    suspend fun getAllChannels(categoryId: String, m3uUrl: String?, categoryName: String): List<Channel> {
        // First, get manually added channels from Firestore
        val manualChannels = getChannelsByCategory(categoryId)
        
        // If M3U URL is provided, fetch and merge M3U channels
        return if (!m3uUrl.isNullOrEmpty()) {
            val m3uChannels = getChannelsFromM3u(m3uUrl, categoryId, categoryName)
            Timber.d("Merged ${manualChannels.size} manual + ${m3uChannels.size} M3U channels")
            manualChannels + m3uChannels
        } else {
            manualChannels
        }
    }

    suspend fun getChannelById(channelId: String): Channel? {
        return try {
            firestore.collection("channels")
                .document(channelId)
                .get()
                .await()
                .toObject(Channel::class.java)
                ?.copy(id = channelId)
        } catch (e: Exception) {
            Timber.e(e, "Error loading channel: $channelId")
            null
        }
    }
}
