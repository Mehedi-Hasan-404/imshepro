// ===================================
// FILE: app/src/main/java/com/livetvpro/ui/favorites/FavoritesFragment.kt
// ACTION: UPDATE - Change PlayerActivity to ChannelPlayerActivity
// ===================================

package com.livetvpro.ui.favorites

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.databinding.FragmentFavoritesBinding
import com.livetvpro.ui.adapters.FavoriteAdapter
import com.livetvpro.ui.player.ChannelPlayerActivity  // ⬅️ CHANGED: Use ChannelPlayerActivity
import com.livetvpro.data.models.Channel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FavoritesFragment : Fragment() {

    private var _binding: FragmentFavoritesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: FavoritesViewModel by viewModels()
    private lateinit var favoriteAdapter: FavoriteAdapter

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
                // Convert favorite to channel
                val channel = Channel(
                    id = favorite.id,
                    name = favorite.name,
                    logoUrl = favorite.logoUrl,
                    streamUrl = "", // Will be fetched by player
                    categoryId = favorite.categoryId,
                    categoryName = favorite.categoryName
                )
                // ⬇️ CHANGED: Use ChannelPlayerActivity instead of PlayerActivity
                ChannelPlayerActivity.start(requireContext(), channel)
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
