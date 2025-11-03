package com.sanchari.bus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemRecentSearchBinding

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
            // We can add a simple relative time logic later
            binding.viewedTimestamp.text = "Viewed recently"

            // Removed the line referencing binding.busIcon as it does not exist
            // in item_recent_search.xml

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

