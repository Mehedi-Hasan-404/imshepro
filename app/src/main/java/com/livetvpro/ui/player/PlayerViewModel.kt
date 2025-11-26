package com.livetvpro.ui.player

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.FavoriteChannel
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val channelRepository: ChannelRepository
) : ViewModel() {

    private val _relatedChannels = MutableLiveData<List<Channel>>()
    val relatedChannels: LiveData<List<Channel>> = _relatedChannels

    fun isFavorite(channelId: String): Boolean {
        return favoritesRepository.isFavorite(channelId)
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

    fun loadRelatedChannels(categoryId: String, currentChannelId: String) {
        viewModelScope.launch {
            try {
                // Get all channels from the same category
                val allChannels = channelRepository.getChannelsByCategory(categoryId)

                // Filter out the current channel, shuffle, and take 10
                val related = allChannels
                    .filter { it.id != currentChannelId }
                    .shuffled()
                    .take(10)

                _relatedChannels.value = related
            } catch (e: Exception) {
                Timber.e(e, "Error loading related channels")
                _relatedChannels.value = emptyList()
            }
        }
    }
}
