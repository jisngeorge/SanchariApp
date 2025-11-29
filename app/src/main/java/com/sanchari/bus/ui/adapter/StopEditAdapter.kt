package com.sanchari.bus.ui.adapter

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ItemEditStopBinding

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 */
class StopEditAdapter(
    private val stops: MutableList<EditableStop>,
    private val onRemoveClicked: (position: Int) -> Unit,
    private val onTimeClicked: (position: Int) -> Unit
) : RecyclerView.Adapter<StopEditAdapter.StopEditViewHolder>() {

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
        // FIX: Use bindingAdapterPosition instead of adapterPosition
        val currentPos = holder.bindingAdapterPosition

        // Remove existing watcher for this position to avoid infinite loops
        textWatchers[currentPos]?.let {
            holder.binding.editTextStopName.removeTextChangedListener(it)
        }

        holder.bind(stops[position])

        // Highlight logic
        if (position == highlightedPosition) {
            holder.itemView.setBackgroundColor("#FFF9C4".toColorInt())
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // Create new TextWatcher
        val stopNameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // FIX: Use bindingAdapterPosition
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    stops[adapterPos].stopName = s.toString()
                }
            }
        }

        holder.binding.editTextStopName.addTextChangedListener(stopNameWatcher)

        // Store watcher reference using the current position
        textWatchers[currentPos] = stopNameWatcher

        holder.binding.buttonRemoveStop.setOnClickListener {
            // FIX: Use bindingAdapterPosition
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                // --- FIX: Update highlightedPosition before removing ---
                if (adapterPos == highlightedPosition) {
                    // The highlighted item is being removed, so clear the highlight
                    highlightedPosition = RecyclerView.NO_POSITION
                } else if (adapterPos < highlightedPosition) {
                    // An item above the highlighted one is removed, so shift highlight up
                    highlightedPosition--
                }
                // -------------------------------------------------------

                onRemoveClicked(adapterPos)
            }
        }

        holder.binding.editTextScheduledTime.setOnClickListener {
            // FIX: Use bindingAdapterPosition
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onTimeClicked(adapterPos)
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
     * Updates the time and uses specific move events for efficiency.
     */
    fun updateTime(position: Int, time: String): Int {
        if (position in stops.indices) {
            val stopToUpdate = stops[position]
            stopToUpdate.scheduledTime = time

            // 1. Remove the item from its old position
            stops.removeAt(position)
            notifyItemRemoved(position)

            // 2. Find new index based on sort order
            var newIndex = 0
            // Logic: Find the first item that should come AFTER this one
            // We treat blank times as "largest" so they go to the end
            while (newIndex < stops.size) {
                val t1 = time
                val t2 = stops[newIndex].scheduledTime

                val comparison = if (t1.isBlank() && t2.isBlank()) 0
                else if (t1.isBlank()) 1
                else if (t2.isBlank()) -1
                else t1.compareTo(t2)

                if (comparison < 0) {
                    break // Found our spot
                }
                newIndex++
            }

            // 3. Insert at new position
            stops.add(newIndex, stopToUpdate)
            notifyItemInserted(newIndex)

            highlightedPosition = newIndex

            // If the item moved significantly, refresh the view to update highlight
            notifyItemChanged(newIndex)

            return newIndex
        }
        return position
    }
}