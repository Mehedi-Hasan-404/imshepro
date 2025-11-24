package com.livetvpro.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.livetvpro.R
import com.livetvpro.databinding.FragmentHomeBinding
import com.livetvpro.ui.adapters.CategoryAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var categoryAdapter: CategoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        try {
            Timber.d("HomeFragment onCreateView started")
            _binding = FragmentHomeBinding.inflate(inflater, container, false)
            Timber.d("HomeFragment binding inflated successfully")
            return binding.root
        } catch (e: Exception) {
            Timber.e(e, "Error in HomeFragment onCreateView")
            throw e
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            Timber.d("HomeFragment onViewCreated started")
            setupRecyclerView()
            observeViewModel()
            setupSwipeRefresh()
            Timber.d("HomeFragment onViewCreated completed")
        } catch (e: Exception) {
            Timber.e(e, "Error in HomeFragment onViewCreated")
            // Show error to user
            binding.errorView.visibility = View.VISIBLE
            binding.errorText.text = "Error loading: ${e.message}"
        }
    }

    private fun setupRecyclerView() {
        try {
            Timber.d("Setting up RecyclerView")
            categoryAdapter = CategoryAdapter { category ->
                try {
                    val bundle = bundleOf(
                        "categoryId" to category.id,
                        "categoryName" to category.name
                    )
                    findNavController().navigate(R.id.action_home_to_category, bundle)
                } catch (e: Exception) {
                    Timber.e(e, "Error navigating to category")
                }
            }

            binding.recyclerViewCategories.apply {
                layoutManager = GridLayoutManager(context, 2)
                adapter = categoryAdapter
                setHasFixedSize(true)
            }
            Timber.d("RecyclerView setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up RecyclerView")
            throw e
        }
    }

    private fun observeViewModel() {
        try {
            Timber.d("Observing ViewModel")
            
            viewModel.filteredCategories.observe(viewLifecycleOwner) { categories ->
                try {
                    Timber.d("Received ${categories.size} categories")
                    categoryAdapter.submitList(categories)
                    binding.emptyView.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerViewCategories.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
                } catch (e: Exception) {
                    Timber.e(e, "Error updating categories list")
                }
            }

            viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
                try {
                    Timber.d("Loading state: $isLoading")
                    binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isRefreshing = isLoading
                } catch (e: Exception) {
                    Timber.e(e, "Error updating loading state")
                }
            }

            viewModel.error.observe(viewLifecycleOwner) { error ->
                try {
                    if (error != null) {
                        Timber.e("ViewModel error: $error")
                        binding.errorView.visibility = View.VISIBLE
                        binding.errorText.text = error
                        binding.recyclerViewCategories.visibility = View.GONE
                    } else {
                        binding.errorView.visibility = View.GONE
                        binding.recyclerViewCategories.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error updating error state")
                }
            }
            
            Timber.d("ViewModel observation setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up ViewModel observers")
            throw e
        }
    }

    private fun setupSwipeRefresh() {
        try {
            binding.swipeRefresh.setOnRefreshListener {
                Timber.d("Swipe refresh triggered")
                viewModel.loadCategories()
            }

            binding.retryButton.setOnClickListener {
                Timber.d("Retry button clicked")
                viewModel.retry()
            }
            Timber.d("Swipe refresh setup complete")
        } catch (e: Exception) {
            Timber.e(e, "Error setting up swipe refresh")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Timber.d("HomeFragment onDestroyView")
        _binding = null
    }
}
