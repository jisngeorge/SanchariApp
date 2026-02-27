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
import kotlin.math.abs
import androidx.core.graphics.toColorInt

/**
 * Adapter for the editable list of bus stops in SuggestEditActivity.
 * Features:
 * - Auto-sorts on time change (Handles midnight crossing using proximity logic).
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
            holder.itemView.setBackgroundColor("#FFF9C4".toColorInt()) // Light Yellow
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

    fun updateTime(position: Int, time: String): Int {
        if (position !in stops.indices) return position

        val stopToUpdate = stops[position]
        stopToUpdate.scheduledTime = time

        if (time.isBlank() || stops.size <= 1) {
            highlightedPosition = position
            notifyDataSetChanged()
            return position
        }

        val rawMins = parseMinutes(time)
        if (rawMins == -1) {
            highlightedPosition = position
            notifyDataSetChanged()
            return position
        }

        // Precompute minutes for all stops once
        val size = stops.size
        val linearMins = IntArray(size) { -1 }

        var previousMins = -1
        var dayOffset = 0

        // Build linear timeline for all except updated stop
        for (i in 0 until size) {
            val stop = stops[i]
            if (stop === stopToUpdate) continue

            val mins = parseMinutes(stop.scheduledTime)
            if (mins != -1) {
                if (previousMins != -1 && mins < previousMins) {
                    dayOffset += 1440
                }
                linearMins[i] = mins + dayOffset
                previousMins = mins
            }
        }

        // Build reference timeline bounds
        var firstMins = Int.MAX_VALUE
        var lastMins = Int.MIN_VALUE
        for (i in 0 until size) {
            val v = linearMins[i]
            if (v != -1) {
                if (v < firstMins) firstMins = v
                if (v > lastMins) lastMins = v
            }
        }

        if (firstMins != Int.MAX_VALUE) {
            val cand0 = rawMins
            val cand1 = rawMins + 1440

            val fits0 = cand0 in firstMins..lastMins
            val fits1 = cand1 in firstMins..lastMins

            val finalMins = when {
                fits0 -> cand0
                fits1 -> cand1
                else -> {
                    val distToFirst = abs(cand0 - firstMins)
                    val distToLast = abs(cand1 - lastMins)
                    if (distToLast < distToFirst) cand1 else cand0
                }
            }

            // Assign updated stop linear value
            linearMins[position] = finalMins

            // Assign MAX for blank times
            for (i in 0 until size) {
                if (linearMins[i] == -1) {
                    if (stops[i] !== stopToUpdate) {
                        val mins = parseMinutes(stops[i].scheduledTime)
                        if (mins == -1) {
                            linearMins[i] = Int.MAX_VALUE
                        }
                    }
                }
            }

            // Sort using index mapping (no Pair allocations)
            val indexed = stops.indices.sortedBy { linearMins[it] }

            val newList = ArrayList<EditableStop>(size)
            for (i in indexed) {
                newList.add(stops[i])
            }

            stops.clear()
            stops.addAll(newList)
        }

        val newIndex = stops.indexOf(stopToUpdate)
        highlightedPosition = newIndex
        notifyDataSetChanged()
        return newIndex
    }

    /**
     * Helper to parse "HH:mm" string into minutes from 00:00.
     * Returns -1 if invalid.
     */
    private fun parseMinutes(time: String): Int {
        return try {
            if (time.contains(":")) {
                val parts = time.split(":")
                val h = parts[0].trim().toInt()
                val m = parts[1].trim().toInt()
                (h * 60) + m
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}