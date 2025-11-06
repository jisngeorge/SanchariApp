package com.sanchari.bus

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemEditStopBinding

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 * This adapter is complex because it needs to read data back from EditTexts.
 */
class StopEditAdapter(
    private val stops: MutableList<EditableStop>,
    private val onRemoveClicked: (position: Int) -> Unit,
    private val onTimeClicked: (position: Int) -> Unit // --- NEW: Click listener for time ---
) : RecyclerView.Adapter<StopEditAdapter.StopEditViewHolder>() {

    // This is crucial to prevent TextWatchers from causing crashes
    // during view recycling.
    // --- MODIFIED: Removed the time TextWatcher ---
    private val textWatchers = mutableMapOf<Int, TextWatcher>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopEditViewHolder {
        val binding = ItemEditStopBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StopEditViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StopEditViewHolder, position: Int) {
        // Remove any existing watchers from the recycled view
        textWatchers[holder.adapterPosition]?.let {
            holder.binding.editTextStopName.removeTextChangedListener(it)
        }
        // --- REMOVED: Time watcher logic ---

        holder.bind(stops[position])

        // Create new watchers and store them
        val stopNameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Check position to avoid crash on remove
                if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                    stops[holder.adapterPosition].stopName = s.toString()
                }
            }
        }
        // --- REMOVED: Time watcher logic ---

        holder.binding.editTextStopName.addTextChangedListener(stopNameWatcher)
        // --- REMOVED: Time watcher logic ---
        textWatchers[holder.adapterPosition] = stopNameWatcher

        holder.binding.buttonRemoveStop.setOnClickListener {
            // Check adapterPosition to be safe
            if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                onRemoveClicked(holder.adapterPosition)
            }
        }

        // --- NEW: Set click listener for the time field ---
        holder.binding.editTextScheduledTime.setOnClickListener {
            if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                onTimeClicked(holder.adapterPosition)
            }
        }
    }

    override fun getItemCount(): Int = stops.size

    inner class StopEditViewHolder(val binding: ItemEditStopBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stop: EditableStop) {
            binding.editTextStopName.setText(stop.stopName)
            binding.editTextScheduledTime.setText(stop.scheduledTime)
        }
    }

    // Helper to get all the data from the adapter
    fun getStopsData(): List<EditableStop> {
        return stops
    }

    // --- NEW: Helper to update time data from the dialog ---
    fun updateTime(position: Int, time: String) {
        if (position in stops.indices) {
            stops[position].scheduledTime = time
            notifyItemChanged(position)
        }
    }
}