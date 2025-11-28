package com.sanchari.bus

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemEditStopBinding

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 * Handles auto-sorting and highlighting of moved items.
 */
class StopEditAdapter(
    private val stops: MutableList<EditableStop>,
    private val onRemoveClicked: (position: Int) -> Unit,
    private val onTimeClicked: (position: Int) -> Unit
) : RecyclerView.Adapter<StopEditAdapter.StopEditViewHolder>() {

    // Tracks the position of the item that was just updated/moved
    private var highlightedPosition = RecyclerView.NO_POSITION

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
        // Clear existing watchers to prevent recursion/wrong updates
        textWatchers[holder.adapterPosition]?.let {
            holder.binding.editTextStopName.removeTextChangedListener(it)
        }

        holder.bind(stops[position])

        // --- NEW: Highlight Logic ---
        if (position == highlightedPosition) {
            // Set a light yellow background to indicate this item was just moved/edited
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF9C4"))
        } else {
            // Reset to transparent
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
        // ---------------------------

        val stopNameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                    stops[holder.adapterPosition].stopName = s.toString()
                }
            }
        }

        holder.binding.editTextStopName.addTextChangedListener(stopNameWatcher)
        textWatchers[holder.adapterPosition] = stopNameWatcher

        holder.binding.buttonRemoveStop.setOnClickListener {
            if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                onRemoveClicked(holder.adapterPosition)
            }
        }

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

    fun getStopsData(): List<EditableStop> {
        return stops
    }

    /**
     * Updates the time for a stop, AUTO-SORTS the list, and returns the new position.
     */
    fun updateTime(position: Int, time: String): Int {
        if (position in stops.indices) {
            val stopToUpdate = stops[position]
            stopToUpdate.scheduledTime = time

            // Sort: time-based, putting blanks at the end
            stops.sortWith(Comparator { o1, o2 ->
                val t1 = o1.scheduledTime
                val t2 = o2.scheduledTime

                if (t1.isBlank() && t2.isBlank()) 0
                else if (t1.isBlank()) 1
                else if (t2.isBlank()) -1
                else t1.compareTo(t2)
            })

            // Find the new index of the updated item
            val newIndex = stops.indexOf(stopToUpdate)
            highlightedPosition = newIndex

            notifyDataSetChanged()

            // Return the new index so the Activity can scroll to it
            return newIndex
        }
        return position
    }
}