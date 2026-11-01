package com.sanchari.bus.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.data.model.RecentSearch
import com.sanchari.bus.databinding.ItemRecentSearchBinding
import androidx.appcompat.widget.TooltipCompat

class RecentSearchAdapter(
    private var recentSearches: List<RecentSearch>,
    private val onItemClick: (RecentSearch) -> Unit
) : RecyclerView.Adapter<RecentSearchAdapter.RecentSearchViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentSearchViewHolder {
        val binding = ItemRecentSearchBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecentSearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentSearchViewHolder, position: Int) {
        holder.bind(recentSearches[position])
    }

    override fun getItemCount() = recentSearches.size

    inner class RecentSearchViewHolder(private val binding: ItemRecentSearchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(recentSearch: RecentSearch) {
            binding.serviceName.text = recentSearch.serviceName
            binding.root.contentDescription = recentSearch.serviceName
            TooltipCompat.setTooltipText(binding.root, recentSearch.serviceName)

            binding.root.setOnClickListener {
                onItemClick(recentSearch)
            }
        }
    }

    /**
     * Updates the list of recent searches and notifies the adapter.
     */
    fun updateData(newSearches: List<RecentSearch>) {
        this.recentSearches = newSearches
        notifyDataSetChanged()
    }
}
