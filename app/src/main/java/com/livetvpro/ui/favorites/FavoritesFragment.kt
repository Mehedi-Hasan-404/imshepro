package com.livetvpro.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.data.repository.ChannelRepository
import com.livetvpro.databinding.FragmentFavoritesBinding
import com.livetvpro.ui.adapters.FavoriteAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FavoritesViewModel by viewModels()
    private lateinit var favoriteAdapter: FavoriteAdapter
    
    // Inject ChannelRepository to get full channel data with streamUrl
    @Inject
    lateinit var channelRepository: ChannelRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadFavorites()
    }

    private fun setupRecyclerView() {
        favoriteAdapter = FavoriteAdapter(
            onChannelClick = { favorite ->
                // Need to fetch full channel data from repository
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        Timber.d("Loading channel: ${favorite.name}")
                        
                        // Try to get channel from Firestore first
                        var channel = channelRepository.getChannelById(favorite.id)
                        
                        // If not in Firestore, try to get from M3U
                        if (channel == null) {
                            Timber.d("Channel not in Firestore, trying M3U...")
                            val allChannels = channelRepository.getAllChannels(
                                favorite.categoryId, 
                                null, // Will get M3U URL from category
                                favorite.categoryName
                            )
                            channel = allChannels.find { it.id == favorite.id }
                        }
                        
                        if (channel != null && channel.streamUrl.isNotEmpty()) {
                            Timber.d("Found channel with streamUrl: ${channel.streamUrl}")
                            ChannelPlayerActivity.start(requireContext(), channel)
                        } else {
                            Timber.e("Channel not found or missing streamUrl")
                            Toast.makeText(
                                requireContext(), 
                                "Channel stream not available", 
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error loading channel")
                        Toast.makeText(
                            requireContext(), 
                            "Failed to load channel: ${e.message}", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onRemoveClick = { favorite ->
                viewModel.removeFavorite(favorite.id)
            }
        )

        binding.recyclerViewFavorites.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = favoriteAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupButtons() {
        binding.clearAllButton.setOnClickListener {
            showClearAllDialog()
        }
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear All Favorites")
            .setMessage("Are you sure you want to remove all favorites?")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            favoriteAdapter.submitList(favorites)

            binding.emptyView.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerViewFavorites.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
            binding.clearAllButton.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
