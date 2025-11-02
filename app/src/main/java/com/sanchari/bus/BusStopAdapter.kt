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

    override fun getItemCount(): Int = stops.size

    fun updateStops(newStops: List<BusStop>) {
        stops = newStops
        notifyDataSetChanged()
    }

    inner class BusStopViewHolder(private val binding: ItemBusStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stop: BusStop) {
            binding.textViewStopName.text = stop.locationName
            binding.textViewScheduledTime.text = stop.scheduledTime
            // Here you could add logic to tint the icon based on stopOrder (e.g., first/last)
        }
    }
}

