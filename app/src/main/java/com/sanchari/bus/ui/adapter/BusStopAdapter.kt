package com.sanchari.bus.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemBusStopBinding
import androidx.core.graphics.toColorInt
import com.sanchari.bus.data.model.BusStop

class BusStopAdapter(
    // We initially pass an empty list, data is set via updateStops
    rawStops: List<BusStop>
) : RecyclerView.Adapter<BusStopAdapter.BusStopViewHolder>() {

    // We hold the *processed* list for display, not the raw DB list
    private var displayStops: List<DisplayStop> = processStops(rawStops)

    /**
     * Internal model to handle merged stops (halts).
     */
    data class DisplayStop(
        val locationName: String,
        val timeText: String,
        val isHalt: Boolean
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusStopViewHolder {
        val binding = ItemBusStopBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BusStopViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BusStopViewHolder, position: Int) {
        val stop = displayStops[position]
        holder.bind(stop)
    }

    override fun getItemCount() = displayStops.size

    inner class BusStopViewHolder(private val binding: ItemBusStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Store the default text color (from XML theme)
        private val defaultTextColor = binding.textViewStopName.currentTextColor

        fun bind(stop: DisplayStop) {
            binding.textViewStopName.text = stop.locationName
            binding.textViewScheduledTime.text = stop.timeText

            if (stop.isHalt) {
                // Highlight halts in Red
                binding.textViewStopName.setTextColor("#D32F2F".toColorInt())
            } else {
                // Reset to default
                binding.textViewStopName.setTextColor(defaultTextColor)
            }
        }
    }

    /**
     * Process raw DB stops to merge consecutive duplicates into single "Halt" rows.
     */
    private fun processStops(rawStops: List<BusStop>): List<DisplayStop> {
        val processed = mutableListOf<DisplayStop>()
        var i = 0
        while (i < rawStops.size) {
            val current = rawStops[i]

            // Check if the NEXT stop is the same location (a Halt)
            if (i + 1 < rawStops.size && rawStops[i + 1].locationName == current.locationName) {
                val next = rawStops[i + 1]
                // Merge them: "Arrival - Departure"
                val combinedTime = "${current.scheduledTime} - ${next.scheduledTime}"

                processed.add(DisplayStop(
                    locationName = current.locationName,
                    timeText = combinedTime,
                    isHalt = true
                ))

                // Skip the next stop since we just merged it
                i += 2
            } else {
                // Normal stop
                processed.add(DisplayStop(
                    locationName = current.locationName,
                    timeText = current.scheduledTime,
                    isHalt = false
                ))
                i++
            }
        }
        return processed
    }

    /**
     * Updates the list of stops, processes them for halts, and notifies the adapter.
     */
    fun updateStops(newStops: List<BusStop>) {
        this.displayStops = processStops(newStops)
        notifyDataSetChanged()
    }
}