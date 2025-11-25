package com.livetvpro.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.livetvpro.R
import com.livetvpro.data.models.Channel
import com.livetvpro.databinding.ItemRelatedChannelBinding

class RelatedChannelAdapter(
    private val onChannelClick: (Channel) -> Unit
) : ListAdapter<Channel, RelatedChannelAdapter.RelatedChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelatedChannelViewHolder {
        val binding = ItemRelatedChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RelatedChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RelatedChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RelatedChannelViewHolder(
        private val binding: ItemRelatedChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelClick(getItem(position))
                }
            }
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name

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
