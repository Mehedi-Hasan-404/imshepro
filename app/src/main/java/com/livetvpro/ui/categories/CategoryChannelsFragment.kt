package com.livetvpro.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.MainActivity
import com.livetvpro.R
import com.livetvpro.databinding.FragmentCategoryChannelsBinding
import com.livetvpro.ui.adapters.ChannelAdapter
import com.livetvpro.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class CategoryChannelsFragment : Fragment() {

    private var _binding: FragmentCategoryChannelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryChannelsViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCategoryChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupToolbar() {
        // Setup search button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_search)?.setOnClickListener {
            showSearchDialog()
        }

        // Setup favorites button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_favorites)?.setOnClickListener {
            findNavController().navigate(R.id.action_category_to_favorites)
        }

        // Setup theme toggle button
        (activity as? MainActivity)?.findViewById<View>(R.id.btn_theme_toggle)?.setOnClickListener {
            (activity as? MainActivity)?.toggleTheme()
        }
    }

    private fun showSearchDialog() {
        val searchView = SearchView(requireContext())
        searchView.queryHint = "Search channels..."
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Search Channels")
            .setView(searchView)
            .setNegativeButton("Close", null)
            .create()

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.searchChannels(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.searchChannels(newText ?: "")
                return true
            }
        })

        dialog.show()
        
        // Auto-focus search
        searchView.requestFocus()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                PlayerActivity.start(requireContext(), channel)
            },
            onFavoriteToggle = { channel ->
                viewModel.toggleFavorite(channel)
                channelAdapter.notifyDataSetChanged()
            },
            isFavorite = { channelId ->
                viewModel.isFavorite(channelId)
            }
        )

        binding.recyclerViewChannels.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = channelAdapter
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredChannels.observe(viewLifecycleOwner) { channels ->
            channelAdapter.submitList(channels)
            binding.emptyView.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            
            Timber.d("Displaying ${channels.size} channels")
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorText.text = error
            } else {
                binding.errorView.visibility = View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh adapter to update favorite indicators
        channelAdapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
