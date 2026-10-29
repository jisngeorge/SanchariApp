package com.sanchari.bus.ui.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.sanchari.bus.R
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.data.model.EditorStopOption
import com.sanchari.bus.databinding.ItemEditStopBinding
import com.sanchari.bus.ui.helper.TimetableTimeFormatter
import java.util.Collections
import java.util.Locale

class StopEditAdapter(
    private val stops: MutableList<EditableStop>,
    private val onRemoveClicked: (position: Int) -> Unit,
    private val onTimeClicked: (position: Int) -> Unit,
    private val onEstimateClicked: (position: Int) -> Unit = {},
    private val onRouteChanged: () -> Unit = {}
) : RecyclerView.Adapter<StopEditAdapter.StopEditViewHolder>() {
    private var highlightedPosition = RecyclerView.NO_POSITION
    private var options: List<EditorStopOption>? = null
    private var optionsByName = emptyMap<String, List<EditorStopOption>>()
    private val associatedNames = mutableMapOf<Int, String>()
    private var lookupFailed = false
    private object OptionsChanged
    private object TimeChanged

    private fun nameKey(name: String) = name.trim().uppercase(Locale.ROOT)

    fun setStopOptions(values: List<EditorStopOption>?, failed: Boolean = false) {
        options = values
        optionsByName = values.orEmpty().groupBy { nameKey(it.name) }
        lookupFailed = failed
        notifyItemRangeChanged(0, itemCount, OptionsChanged)
    }

    fun routeReplaced() {
        stops.filter { it.originalStopId != -1 }.forEach { associatedNames[it.originalStopId] = it.stopName }
        highlightedPosition = RecyclerView.NO_POSITION
        onRouteChanged()
        notifyDataSetChanged()
    }

    fun commitNames(): Boolean {
        var valid = true
        stops.indices.forEach { if (!commitName(it)) valid = false }
        return valid
    }

    private fun commitName(position: Int): Boolean {
        val stop = stops[position]
        val name = stop.stopName.trim()
        val unchanged = stop.originalStopId != -1 &&
            nameKey(associatedNames[stop.originalStopId].orEmpty()) == nameKey(name)
        val candidates = optionsByName[nameKey(name)].orEmpty()
        val matches = candidates.filter { it.name == name }.ifEmpty { candidates }
        val match = matches.singleOrNull { it.id == stop.originalStopId && unchanged }
            ?: matches.singleOrNull()
        val updated = when {
            unchanged -> stop.copy(stopName = matches.firstOrNull { it.id == stop.originalStopId }?.name
                ?: associatedNames.getValue(stop.originalStopId))
            match != null -> stop.copy(stopName = match.name, originalStopId = match.id)
            else -> stop.copy(stopName = name, originalStopId = -1)
        }
        if (updated != stop) {
            stops[position] = updated
            onRouteChanged()
        }
        if (updated.originalStopId != -1) associatedNames[updated.originalStopId] = updated.stopName
        return unchanged || matches.size <= 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StopEditViewHolder =
        StopEditViewHolder(ItemEditStopBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: StopEditViewHolder, position: Int) {
        holder.detachWatcher()
        val stop = stops[position]
        if (stop.originalStopId != -1) associatedNames.putIfAbsent(stop.originalStopId, stop.stopName)
        holder.bind(stop, position)
        holder.attachWatcher()
        holder.showHighlight(position == highlightedPosition)
        holder.binding.buttonMoveUp.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.binding.buttonMoveDown.visibility = if (position == stops.lastIndex) View.INVISIBLE else View.VISIBLE
        holder.binding.buttonMoveUp.setOnClickListener { moveItem(holder.bindingAdapterPosition, -1) }
        holder.binding.buttonMoveDown.setOnClickListener { moveItem(holder.bindingAdapterPosition, 1) }
        holder.binding.buttonRemoveStop.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) {
                if (highlightedPosition == index) highlightedPosition = RecyclerView.NO_POSITION
                else if (highlightedPosition > index) highlightedPosition--
                onRouteChanged()
                onRemoveClicked(index)
            }
        }
        holder.binding.editTextScheduledTime.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onTimeClicked)
        }
        holder.binding.buttonSuggestTime.setOnClickListener {
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(onEstimateClicked)
        }
    }

    override fun onBindViewHolder(holder: StopEditViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            if (OptionsChanged in payloads) holder.refreshOptions(stops[position])
            if (TimeChanged in payloads) {
                holder.binding.editTextScheduledTime.setText(TimetableTimeFormatter.format(stops[position].scheduledTime))
                holder.showHighlight(position == highlightedPosition)
            }
        }
    }

    override fun onViewRecycled(holder: StopEditViewHolder) {
        holder.detachWatcher()
        holder.binding.editTextStopName.onFocusChangeListener = null
        holder.binding.editTextStopName.onItemClickListener = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = stops.size

    inner class StopEditViewHolder(val binding: ItemEditStopBinding) : RecyclerView.ViewHolder(binding.root) {
        private var watcher: TextWatcher? = null
        private var boundOptions: List<EditorStopOption>? = null

        init {
            TooltipCompat.setTooltipText(binding.buttonSuggestTime, binding.root.context.getString(R.string.editor_suggest_time))
        }

        fun showHighlight(highlighted: Boolean) {
            binding.root.strokeColor = MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            binding.root.strokeWidth = if (highlighted) (2 * binding.root.resources.displayMetrics.density).toInt() else 0
        }

        fun bind(stop: EditableStop, position: Int) {
            binding.editTextStopName.onItemClickListener = null
            binding.editTextStopName.onFocusChangeListener = null
            refreshOptions(stop)
            binding.editTextStopName.setText(stop.stopName, false)
            binding.editTextScheduledTime.setText(TimetableTimeFormatter.format(stop.scheduledTime))
            binding.textViewStopOrder.text = binding.root.context.getString(R.string.editor_stop_heading, position + 1)
            showNameFeedback(stop)
            binding.editTextStopName.setOnItemClickListener { parent, _, index, _ ->
                val option = parent.getItemAtPosition(index) as EditorStopOption
                val row = bindingAdapterPosition
                if (row != RecyclerView.NO_POSITION) {
                    stops[row] = stops[row].copy(stopName = option.name, originalStopId = option.id)
                    associatedNames[option.id] = option.name
                    detachWatcher()
                    binding.editTextStopName.setText(option.name, false)
                    attachWatcher()
                    showNameFeedback(stops[row])
                    onRouteChanged()
                }
            }
            binding.editTextStopName.setOnFocusChangeListener { _, focused ->
                val row = bindingAdapterPosition
                if (!focused && row != RecyclerView.NO_POSITION) {
                    val valid = commitName(row)
                    detachWatcher()
                    binding.editTextStopName.setText(stops[row].stopName, false)
                    attachWatcher()
                    showNameFeedback(stops[row])
                    if (!valid) binding.stopNameLayout.error = binding.root.context.getString(R.string.editor_ambiguous_stop)
                }
            }
        }

        fun refreshOptions(stop: EditableStop) {
            if (boundOptions !== options) {
                boundOptions = options
                binding.editTextStopName.setAdapter(
                    ArrayAdapter(binding.root.context, android.R.layout.simple_dropdown_item_1line, options.orEmpty())
                )
            }
            showNameFeedback(stop)
        }

        private fun showNameFeedback(stop: EditableStop) {
            binding.stopNameLayout.error = null
            val unchanged = stop.originalStopId != -1 &&
                nameKey(associatedNames[stop.originalStopId].orEmpty()) == nameKey(stop.stopName)
            binding.stopNameLayout.helperText = when {
                lookupFailed -> binding.root.context.getString(R.string.editor_lookup_unavailable)
                options == null -> null
                stop.stopName.isNotBlank() && optionsByName[nameKey(stop.stopName)].isNullOrEmpty() &&
                    !unchanged -> binding.root.context.getString(R.string.editor_new_stop)
                else -> null
            }
        }

        fun attachWatcher() {
            val listener = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val row = bindingAdapterPosition
                    if (row != RecyclerView.NO_POSITION) {
                        stops[row].stopName = s?.toString().orEmpty()
                        showNameFeedback(stops[row])
                        onRouteChanged()
                    }
                }
            }
            binding.editTextStopName.addTextChangedListener(listener)
            watcher = listener
        }

        fun detachWatcher() {
            watcher?.let(binding.editTextStopName::removeTextChangedListener)
            watcher = null
        }
    }

    fun getStopsData(): List<EditableStop> = stops

    private fun moveItem(position: Int, direction: Int) {
        if (position == RecyclerView.NO_POSITION || position + direction !in stops.indices) return
        val target = position + direction
        Collections.swap(stops, position, target)
        if (highlightedPosition == position) highlightedPosition = target
        else if (highlightedPosition == target) highlightedPosition = position
        onRouteChanged()
        notifyItemMoved(position, position + direction)
        notifyItemRangeChanged(minOf(position, position + direction), 2)
    }

    fun updateTime(position: Int, time: String): Int {
        if (position !in stops.indices) return position
        val previous = highlightedPosition
        stops[position].scheduledTime = time
        highlightedPosition = position
        onRouteChanged()
        if (previous in stops.indices && previous != position) notifyItemChanged(previous, TimeChanged)
        notifyItemChanged(position, TimeChanged)
        return position
    }

    fun appendCopies(copies: List<EditableStop>) {
        if (copies.isEmpty()) return
        val start = stops.size
        stops.addAll(copies)
        onRouteChanged()
        notifyItemRangeInserted(start, copies.size)
        if (start > 0) notifyItemChanged(start - 1)
    }
}
