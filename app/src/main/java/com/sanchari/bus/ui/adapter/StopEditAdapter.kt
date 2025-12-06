package com.sanchari.bus.ui.adapter

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ItemEditStopBinding
import java.util.Collections

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 * Features:
 * - Auto-sorts on time change.
 * - Allows manual reordering via Up/Down buttons.
 * - Highlights the active item.
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
        // Use bindingAdapterPosition
        val currentPos = holder.bindingAdapterPosition

        // Remove existing watcher
        textWatchers[currentPos]?.let {
            holder.binding.editTextStopName.removeTextChangedListener(it)
        }

        holder.bind(stops[position])

        // --- Highlight Logic ---
        if (position == highlightedPosition) {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF9C4")) // Light Yellow
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        // --- Stop Order & Arrows ---
        holder.binding.textViewStopOrder.text = "${position + 1}"

        // Visibility logic for arrows
        if (position == 0) {
            holder.binding.buttonMoveUp.visibility = View.INVISIBLE
        } else {
            holder.binding.buttonMoveUp.visibility = View.VISIBLE
        }

        if (position == stops.size - 1) {
            holder.binding.buttonMoveDown.visibility = View.INVISIBLE
        } else {
            holder.binding.buttonMoveDown.visibility = View.VISIBLE
        }

        // Click Listeners for Move
        holder.binding.buttonMoveUp.setOnClickListener {
            moveItem(holder.bindingAdapterPosition, -1)
        }

        holder.binding.buttonMoveDown.setOnClickListener {
            moveItem(holder.bindingAdapterPosition, 1)
        }

        // Text Watcher
        val stopNameWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val adapterPos = holder.bindingAdapterPosition
                if (adapterPos != RecyclerView.NO_POSITION) {
                    stops[adapterPos].stopName = s.toString()
                }
            }
        }

        holder.binding.editTextStopName.addTextChangedListener(stopNameWatcher)
        textWatchers[currentPos] = stopNameWatcher

        holder.binding.buttonRemoveStop.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                if (adapterPos == highlightedPosition) {
                    highlightedPosition = RecyclerView.NO_POSITION
                } else if (adapterPos < highlightedPosition) {
                    highlightedPosition--
                }
                onRemoveClicked(adapterPos)
            }
        }

        holder.binding.editTextScheduledTime.setOnClickListener {
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
     * Moves an item up (-1) or down (+1) manually.
     * Updates highlight position if the moved item was highlighted.
     */
    private fun moveItem(currentPos: Int, direction: Int) {
        if (currentPos == RecyclerView.NO_POSITION) return

        val targetPos = currentPos + direction
        if (targetPos in 0 until stops.size) {
            Collections.swap(stops, currentPos, targetPos)

            // Track the highlight if we moved the highlighted item
            if (highlightedPosition == currentPos) {
                highlightedPosition = targetPos
            } else if (highlightedPosition == targetPos) {
                highlightedPosition = currentPos
            }

            notifyItemMoved(currentPos, targetPos)
            // Update order numbers and arrows
            notifyItemRangeChanged(minOf(currentPos, targetPos), 2)
        }
    }

    /**
     * Updates the time for a stop, AUTO-SORTS the list, and highlights the item.
     * Returns the new index.
     */
    fun updateTime(position: Int, time: String): Int {
        if (position in stops.indices) {
            val stopToUpdate = stops[position]
            stopToUpdate.scheduledTime = time

            // --- Auto-Sort Logic ---
            // Sort by time string, putting blanks at the end
            stops.sortWith(Comparator { o1, o2 ->
                val t1 = o1.scheduledTime
                val t2 = o2.scheduledTime

                if (t1.isBlank() && t2.isBlank()) 0
                else if (t1.isBlank()) 1
                else if (t2.isBlank()) -1
                else t1.compareTo(t2)
            })
            // -----------------------

            // Find where our item ended up after sorting
            val newIndex = stops.indexOf(stopToUpdate)
            highlightedPosition = newIndex

            // We use notifyDataSetChanged because the whole list order might have shifted
            notifyDataSetChanged()

            return newIndex
        }
        return position
    }
}