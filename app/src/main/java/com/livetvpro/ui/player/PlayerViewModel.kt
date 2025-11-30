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
     * ✅ UPDATED: Load related channels from the same category
     * - Gets ALL channels (Firestore + M3U)
     * - Filters out current channel IMMEDIATELY
     * - Gets 9 channels: 4 before + 5 after the current channel (if possible)
     * - Falls back to random 9 if position-based selection isn't possible
     */
    fun loadRelatedChannels(categoryId: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                Timber.d("Loading related channels for category: $categoryId, current channel: $currentChannelId")
                
                // Get category to check for M3U URL
                val category = categoryRepository.getCategoryBySlug(categoryId) 
                    ?: categoryRepository.getCategories().find { it.id == categoryId }
                
                // Get ALL channels (manual + M3U)
                val allChannels = if (category?.m3uUrl?.isNotEmpty() == true) {
                    Timber.d("Loading channels from M3U: ${category.m3uUrl}")
                    channelRepository.getAllChannels(categoryId, category.m3uUrl, category.name)
                } else {
                    Timber.d("Loading channels from Firestore for category: $categoryId")
                    channelRepository.getChannelsByCategory(categoryId)
                }
                
                Timber.d("Total channels loaded: ${allChannels.size}")
                
                // ✅ FIX: Filter out current channel FIRST
                val availableChannels = allChannels.filter { it.id != currentChannelId }
                
                if (availableChannels.isEmpty()) {
                    Timber.w("No other channels available in this category")
                    _relatedChannels.value = emptyList()
                    return@launch
                }
                
                // Find the index of the current channel in the original list
                val currentIndex = allChannels.indexOfFirst { it.id == currentChannelId }
                
                val related = if (currentIndex != -1 && availableChannels.size > 0) {
                    // ✅ Position-based selection: Get 4 before + 5 after
                    val beforeCount = 4
                    val afterCount = 5
                    
                    val relatedChannels = mutableListOf<Channel>()
                    
                    // Add channels before current (from available channels)
                    val beforeStart = maxOf(0, currentIndex - beforeCount)
                    for (i in beforeStart until currentIndex) {
                        if (i < allChannels.size && allChannels[i].id != currentChannelId) {
                            relatedChannels.add(allChannels[i])
                        }
                    }
                    
                    // Add channels after current (from available channels)
                    val afterEnd = minOf(allChannels.size - 1, currentIndex + afterCount)
                    for (i in (currentIndex + 1)..afterEnd) {
                        if (i < allChannels.size && allChannels[i].id != currentChannelId) {
                            relatedChannels.add(allChannels[i])
                        }
                    }
                    
                    Timber.d("Position-based: Selected ${relatedChannels.size} channels around index $currentIndex")
                    
                    // If we have less than 9, add random channels to fill up
                    if (relatedChannels.size < 9 && availableChannels.size > relatedChannels.size) {
                        val remaining = availableChannels
                            .filter { !relatedChannels.contains(it) }
                            .shuffled()
                            .take(9 - relatedChannels.size)
                        relatedChannels.addAll(remaining)
                        Timber.d("Added ${remaining.size} random channels to fill up to ${relatedChannels.size}")
                    }
                    
                    relatedChannels.take(9) // Ensure max 9 channels
                } else {
                    // ✅ Fallback: Random 9 channels (already filtered)
                    Timber.d("Using fallback random selection from ${availableChannels.size} channels")
                    availableChannels
                        .shuffled()
                        .take(9)
                }
                
                _relatedChannels.postValue(related)
                Timber.d("✅ Successfully loaded ${related.size} related channels")
            } catch (e: Exception) {
                Timber.e(e, "Error loading related channels")
                _relatedChannels.postValue(emptyList())
            }
        }
    }
}
