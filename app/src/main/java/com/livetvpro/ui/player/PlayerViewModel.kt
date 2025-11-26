package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.CategoryRepository
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val channelRepository: ChannelRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _relatedChannels = MutableLiveData<List<Channel>>()
    val relatedChannels: LiveData<List<Channel>> = _relatedChannels

    fun isFavorite(channelId: String): LiveData<Boolean> {
        val liveData = MutableLiveData<Boolean>()
        liveData.value = favoritesRepository.isFavorite(channelId)
        return liveData
    }

    fun toggleFavorite(channel: Channel) {
        if (favoritesRepository.isFavorite(channel.id)) {
            favoritesRepository.removeFavorite(channel.id)
        } else {
            val favorite = FavoriteChannel(
                id = channel.id,
                name = channel.name,
                logoUrl = channel.logoUrl,
                categoryId = channel.categoryId,
                categoryName = channel.categoryName
            )
            favoritesRepository.addFavorite(favorite)
        }
    }

    /**
     * Load ALL channels from the category (Firestore + M3U parsed)
     * and intelligently select related channels around the current one
     */
    fun loadAllChannelsFromCategory(categoryId: String, categoryName: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                Timber.d("Loading all channels for category: $categoryId")
                
                // Get category to check for M3U URL
                val category = categoryRepository.getCategoryBySlug(categoryId) 
                    ?: categoryRepository.getCategories().find { it.id == categoryId }
                
                // Get ALL channels (Firestore + M3U)
                val allChannels = if (category?.m3uUrl?.isNotEmpty() == true) {
                    Timber.d("Loading channels from M3U: ${category.m3uUrl}")
                    channelRepository.getAllChannels(categoryId, category.m3uUrl, categoryName)
                } else {
                    Timber.d("Loading channels from Firestore only")
                    channelRepository.getChannelsByCategory(categoryId)
                }
                
                Timber.d("Total channels found: ${allChannels.size}")
                
                // Find current channel index
                val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
                
                if (currentIndex == -1) {
                    Timber.w("Current channel not found in list, showing random channels")
                    // Current channel not found, show random channels (excluding current)
                    val related = allChannels
                        .filter { it.id != currentChannelId }
                        .shuffled()
                        .take(10)
                    _relatedChannels.value = related
                    Timber.d("Showing ${related.size} random related channels")
                    return@launch
                }
                
                // Get channels around the current channel (5 before, 5 after)
                val relatedFromBothSides = mutableListOf<Channel>()
                
                // Get 5 channels BEFORE current (or as many as available)
                val startIndex = (currentIndex - 5).coerceAtLeast(0)
                val beforeChannels = allChannels.subList(startIndex, currentIndex)
                relatedFromBothSides.addAll(beforeChannels.takeLast(5))
                
                // Get 5 channels AFTER current (or as many as available)
                val endIndex = (currentIndex + 6).coerceAtMost(allChannels.size)
                val afterChannels = allChannels.subList(currentIndex + 1, endIndex)
                relatedFromBothSides.addAll(afterChannels.take(5))
                
                // If we don't have enough (less than 10), fill with random channels
                if (relatedFromBothSides.size < 10) {
                    val remaining = 10 - relatedFromBothSides.size
                    val existingIds = relatedFromBothSides.map { it.id }.toSet() + currentChannelId
                    val additionalChannels = allChannels
                        .filter { it.id !in existingIds }
                        .shuffled()
                        .take(remaining)
                    relatedFromBothSides.addAll(additionalChannels)
                }
                
                _relatedChannels.value = relatedFromBothSides
                Timber.d("Loaded ${relatedFromBothSides.size} related channels (current at index $currentIndex)")
                
            } catch (e: Exception) {
                Timber.e(e, "Error loading related channels")
                _relatedChannels.value = emptyList()
            }
        }
    }

    // Legacy method - keeping for backward compatibility
    fun loadRelatedChannels(categoryId: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                Timber.d("Loading related channels for category: $categoryId")
                
                // Get all channels from the same category
                val allChannels = channelRepository.getChannelsByCategory(categoryId)
                
                // Filter out the current channel and limit to 10 channels
                val related = allChannels
                    .filter { it.id != currentChannelId }
                    .shuffled() // Randomize for variety
                    .take(10)
                
                _relatedChannels.value = related
                Timber.d("Loaded ${related.size} related channels")
            } catch (e: Exception) {
                Timber.e(e, "Error loading related channels")
                _relatedChannels.value = emptyList()
            }
        }
    }
}
