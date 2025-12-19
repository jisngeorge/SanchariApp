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
     * Updates the time for a stop, AUTO-SORTS the list using smart proximity logic,
     * and highlights the item.
     * Returns the new index.
     */
    fun updateTime(position: Int, time: String): Int {
        if (position in stops.indices) {
            val stopToUpdate = stops[position]
            stopToUpdate.scheduledTime = time

            // Only sort if we have a valid time and other items to compare against
            if (time.isNotBlank() && stops.size > 1) {

                // 1. Build a temporary list of (Stop, LinearMinutes) for all OTHER stops
                //    This establishes the "shape" of the current route's timeline.
                val timeline = mutableListOf<Pair<EditableStop, Int>>()
                var previousMins = -1
                var dayOffset = 0

                for (stop in stops) {
                    if (stop === stopToUpdate) continue // Skip the one we are editing

                    val mins = parseMinutes(stop.scheduledTime)
                    if (mins != -1) {
                        // If time jumped backwards significantly, assume new day
                        // (Simple heuristic for existing sorted list:
                        // if current is much smaller than previous, add 24h)
                        if (previousMins != -1 && mins < previousMins) {
                            // Only if the jump isn't just a small disorder (e.g. 12:00 -> 11:55)
                            // We assume existing list is mostly sorted.
                            // Let's assume a "day wrap" if drop is > 12 hours?
                            // Or just strictly increasing?
                            // Strict increasing logic is safest for timeline construction.
                            dayOffset += 1440
                        }

                        // BUT: If the existing list was manually reordered to be 23:00 -> 00:30,
                        // this loop will correctly assign 00:30 as 1470.
                        // However, if the list is messy, this might be imperfect.
                        // We rely on the user's manual ordering for the base truth.

                        // Let's simplify: Use the list order to determine day offsets.
                        // If stop[i] < stop[i-1], we assume it's next day relative to previous.

                        val adjustedMins = mins + dayOffset
                        // Double check: if we accidentally added an offset but shouldn't have?
                        // E.g. 23:00 -> 00:30 -> 01:00. Correct.

                        timeline.add(stop to adjustedMins)
                        previousMins = mins
                    } else {
                        // Keep stops with no time at the end? Or ignore for sorting context?
                        // Let's ignore for timeline context, but they exist in the list.
                    }
                }

                // 2. Determine where the NEW time fits
                if (timeline.isNotEmpty()) {
                    val rawMins = parseMinutes(time)
                    if (rawMins != -1) {
                        val firstMins = timeline.first().second
                        val lastMins = timeline.last().second

                        val cand0 = rawMins          // Same day as start (approx)
                        val cand1 = rawMins + 1440   // Next day

                        var finalMins = cand0

                        // Logic: Check if cand0 fits nicely inside the range
                        val fitsInRange = cand0 in firstMins..lastMins
                        val cand1FitsInRange = cand1 in firstMins..lastMins

                        if (fitsInRange) {
                            finalMins = cand0
                        } else if (cand1FitsInRange) {
                            finalMins = cand1
                        } else {
                            // Neither fits strictly inside. It's an extension (Start or End).
                            // User Logic: "closer to time of last... next stop. closer to time of first... before 1st"

                            val distToFirst = abs(cand0 - firstMins)
                            val distToLast = abs(cand1 - lastMins)

                            if (distToLast < distToFirst) {
                                // Closer to the end (wrapping to next day)
                                finalMins = cand1
                            } else {
                                // Closer to the start
                                finalMins = cand0
                            }
                        }

                        // 3. Re-sort the whole list based on these calculated Linear Minutes
                        // We need to map every stop to its linear value now.
                        val allStopsWithMins = mutableListOf<Pair<EditableStop, Int>>()
                        allStopsWithMins.addAll(timeline)
                        allStopsWithMins.add(stopToUpdate to finalMins)

                        // Add back any blank-time stops that were skipped (put them at end)
                        for (stop in stops) {
                            if (stop !== stopToUpdate && parseMinutes(stop.scheduledTime) == -1) {
                                allStopsWithMins.add(stop to Int.MAX_VALUE)
                            }
                        }

                        // Sort by linear minutes
                        allStopsWithMins.sortBy { it.second }

                        // Update the main list
                        stops.clear()
                        stops.addAll(allStopsWithMins.map { it.first })
                    }
                }
            }

            // Find new index
            val newIndex = stops.indexOf(stopToUpdate)
            highlightedPosition = newIndex
            notifyDataSetChanged()
            return newIndex
        }
        return position
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