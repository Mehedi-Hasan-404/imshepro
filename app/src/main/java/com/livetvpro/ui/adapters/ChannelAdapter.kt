package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit,
    private val isFavorite: (String) -> Boolean
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Regular click - play channel
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }

            // Long click - add/remove favorite
            binding.root.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showFavoriteDialog(getItem(position))
                }
                true
            }
        }

        private fun showFavoriteDialog(channel: Channel) {
            val context = binding.root.context
            val isFav = isFavorite(channel.id)
            
            val title = if (isFav) "Remove from Favorites?" else "Add to Favorites?"
            val message = if (isFav) {
                "Do you want to remove \"${channel.name}\" from your favorites?"
            } else {
                "Do you want to add \"${channel.name}\" to your favorites?"
            }
            val positiveButton = if (isFav) "Remove" else "Add"

            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveButton) { dialog, _ ->
                    onFavoriteToggle(channel)
                    notifyItemChanged(bindingAdapterPosition)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name
            binding.channelCategory.text = channel.categoryName

            // Show favorite indicator (small badge) but no clickable button
            val isFav = isFavorite(channel.id)
            binding.favoriteIndicator.visibility = if (isFav) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }

            // Load channel logo
            Glide.with(binding.channelLogo)
                .load(channel.logoUrl)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .centerInside()
                .into(binding.channelLogo)
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}
