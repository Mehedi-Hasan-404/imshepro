package com.livetvpro.ui.live

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.livetvpro.R
import com.livetvpro.data.models.EventStatus
import com.livetvpro.databinding.FragmentLiveEventsBinding
import com.livetvpro.ui.adapters.LiveEventAdapter
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class LiveEventsFragment : Fragment() {

    private var _binding: FragmentLiveEventsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveEventsViewModel by viewModels()
    private lateinit var eventAdapter: LiveEventAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLiveEventsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        try {
            setupRecyclerView()
            setupFilters()
            observeViewModel()
            
            // Default filter to Live
            viewModel.filterEvents(EventStatus.LIVE)
        } catch (e: Exception) {
            Timber.e(e, "Error setting up LiveEventsFragment")
            showError("Failed to initialize: ${e.message}")
        }
    }

    private fun setupRecyclerView() {
        eventAdapter = LiveEventAdapter { event ->
            try {
                EventPlayerActivity.start(requireContext(), event.id)
            } catch (e: Exception) {
                Timber.e(e, "Error starting event player")
                showError("Failed to play event: ${e.message}")
            }
        }

        binding.recyclerViewEvents.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = eventAdapter
            setHasFixedSize(false)
        }
    }

    private fun setupFilters() {
        binding.chipAll.setOnClickListener { 
            viewModel.filterEvents(null)
            updateChipSelection(binding.chipAll)
        }
        binding.chipLive.setOnClickListener { 
            viewModel.filterEvents(EventStatus.LIVE)
            updateChipSelection(binding.chipLive)
        }
        binding.chipUpcoming.setOnClickListener { 
            viewModel.filterEvents(EventStatus.UPCOMING)
            updateChipSelection(binding.chipUpcoming)
        }
        binding.chipRecent.setOnClickListener { 
            viewModel.filterEvents(EventStatus.RECENT)
            updateChipSelection(binding.chipRecent)
        }

        // Default to Live
        updateChipSelection(binding.chipLive)
    }

    private fun updateChipSelection(selectedChip: Chip) {
        listOf(binding.chipAll, binding.chipLive, binding.chipUpcoming, binding.chipRecent).forEach {
            it.isChecked = (it == selectedChip)
        }
    }

    private fun observeViewModel() {
        viewModel.filteredEvents.observe(viewLifecycleOwner) { events ->
            try {
                eventAdapter.submitList(events)
                binding.emptyView.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerViewEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
            } catch (e: Exception) {
                Timber.e(e, "Error updating events list")
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            if (error != null) {
                showError(error)
            } else {
                binding.errorView.visibility = View.GONE
            }
        }
    }

    private fun showError(message: String) {
        binding.errorView.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.recyclerViewEvents.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
