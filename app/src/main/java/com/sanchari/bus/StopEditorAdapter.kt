package com.sanchari.bus

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemEditableStopBinding

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 * This adapter is complex because it needs to read data back from EditTexts.
 */
class StopEditAdapter(
    private val stops: MutableList<EditableStop>,
    private val onRemoveClicked: (position: Int) -> Unit
) : RecyclerView.Adapter<StopEditAdapter.StopEditViewHolder>() {

    // This is crucial to prevent TextWatchers from causing crashes
    // during view recycling.
    private val textWatchers = mutableMapOf<Int, Pair<TextWatcher, TextWatcher>>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopEditViewHolder {
        val binding = ItemEditableStopBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StopEditViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StopEditViewHolder, position: Int) {
        // Remove any existing watchers from the recycled view
        textWatchers[holder.adapterPosition]?.let {
            holder.binding.editTextStopName.removeTextChangedListener(it.first)
            holder.binding.editTextScheduledTime.removeTextChangedListener(it.second)
        }

        holder.bind(stops[position])

        // Create new watchers and store them
        val stopNameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stops[holder.adapterPosition].stopName = s.toString()
            }
        }
        val timeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                stops[holder.adapterPosition].scheduledTime = s.toString()
            }
        }

        holder.binding.editTextStopName.addTextChangedListener(stopNameWatcher)
        holder.binding.editTextScheduledTime.addTextChangedListener(timeWatcher)
        textWatchers[holder.adapterPosition] = (stopNameWatcher to timeWatcher)

        holder.binding.buttonRemoveStop.setOnClickListener {
            // Check adapterPosition to be safe
            if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                onRemoveClicked(holder.adapterPosition)
            }
        }
    }

    override fun getItemCount(): Int = stops.size

    inner class StopEditViewHolder(val binding: ItemEditableStopBinding) :
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
}
