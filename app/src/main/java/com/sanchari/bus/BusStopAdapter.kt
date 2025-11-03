package com.sanchari.bus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemBusStopBinding

class BusStopAdapter(
    private var stops: List<BusStop>
) : RecyclerView.Adapter<BusStopAdapter.BusStopViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusStopViewHolder {
        val binding = ItemBusStopBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BusStopViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BusStopViewHolder, position: Int) {
        holder.bind(stops[position])
    }

    override fun getItemCount() = stops.size

    inner class BusStopViewHolder(private val binding: ItemBusStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stop: BusStop) {
            // Corrected binding IDs
            binding.textViewStopName.text = stop.locationName
            binding.textViewScheduledTime.text = stop.scheduledTime

            // You can add logic here to show/hide icons or change views
            // based on the stop's position (e.g., first or last stop)
        }
    }

    /**
     * Updates the list of stops and notifies the adapter.
     */
    fun updateStops(newStops: List<BusStop>) {
        this.stops = newStops
        notifyDataSetChanged()
    }
}

