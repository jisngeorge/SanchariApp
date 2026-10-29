package com.sanchari.bus.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.sanchari.bus.data.model.RouteSearchResult
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.R
import com.sanchari.bus.databinding.ItemBusServiceBinding
import com.sanchari.bus.ui.helper.TimetableTimeFormatter
import androidx.core.graphics.toColorInt

class BusServiceAdapter(
    private val showEndpoints: Boolean = false,
    private val onItemClick: (BusService) -> Unit
) : ListAdapter<RouteSearchResult, BusServiceAdapter.BusServiceViewHolder>(ServiceDiff) {

    private object ServiceDiff : DiffUtil.ItemCallback<RouteSearchResult>() {
        override fun areItemsTheSame(oldItem: RouteSearchResult, newItem: RouteSearchResult): Boolean =
            oldItem.service.serviceId == newItem.service.serviceId &&
                oldItem.fromRouteStopId == newItem.fromRouteStopId && oldItem.toRouteStopId == newItem.toRouteStopId

        override fun areContentsTheSame(oldItem: RouteSearchResult, newItem: RouteSearchResult): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusServiceViewHolder {
        val binding = ItemBusServiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        binding.fromPlace.isVisible = showEndpoints
        binding.toPlace.isVisible = showEndpoints
        if (showEndpoints) {
            binding.root.orientation = LinearLayout.VERTICAL
            binding.serviceHeader.updateLayoutParams<LinearLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
            binding.endpointTimes.updateLayoutParams<LinearLayout.LayoutParams> {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else {
            binding.endpointTimes.updateLayoutParams<LinearLayout.LayoutParams> {
                topMargin = 0
            }
        }
        return BusServiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BusServiceViewHolder, position: Int) {
        val service = getItem(position)
        holder.bind(service)
    }

    inner class BusServiceViewHolder(private val binding: ItemBusServiceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // Store default text color to restore it for recycled views
        private val defaultTextColor = binding.serviceName.currentTextColor

        fun bind(result: RouteSearchResult) {
            val service = result.service
            val context = binding.root.context

            binding.serviceName.text = service.name

            binding.fromTime.text = TimetableTimeFormatter.format(service.fromTime)
            binding.toTime.text = TimetableTimeFormatter.format(service.toTime)
            binding.fromPlace.text = context.getString(R.string.result_from_place, result.fromPlace)
            binding.toPlace.text = context.getString(R.string.result_to_place, result.toPlace)

            // --- UPDATED: Running Status Indication ---
            // Changed from dimming to text color change based on user feedback
            binding.root.alpha = 1.0f // Reset alpha in case view is recycled

            if (service.isRunning) {
                binding.serviceName.setTextColor(defaultTextColor)
            } else {
                binding.serviceName.setTextColor("#D32F2F".toColorInt()) // Red for warning
            }
            // ------------------------------------------

            // Set the icon based on the service type
            when (service.type.uppercase()) {
                "ORDINARY" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_ordinary)
                    binding.serviceType.text = context.getString(R.string.label_bus_ordinary)
                }
                "CITY SERVICE" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_city_service)
                    binding.serviceType.text = context.getString(R.string.label_bus_city_service)
                }
                "LIMITED STOP" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_limited_stop)
                    binding.serviceType.text = context.getString(R.string.label_bus_ls)
                }
                "FAST PASSENGER" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_fast)
                    binding.serviceType.text = context.getString(R.string.label_bus_fast)
                }
                "SUPERFAST" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_superfast)
                    binding.serviceType.text = context.getString(R.string.label_bus_superfast)
                }
                "EXPRESS" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_express)
                    binding.serviceType.text = context.getString(R.string.label_bus_express)
                }
                "SUPER DELUXE" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_deluxe)
                    binding.serviceType.text = context.getString(R.string.label_bus_deluxe)
                }
                "MINNAL" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_minnal)
                    binding.serviceType.text = context.getString(R.string.label_bus_minnal)
                }
                else -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_placeholder)
                    binding.serviceType.text = context.getString(R.string.label_bus_unspecified)
                }
            }

            // Set the click listener for the whole item
            binding.root.setOnClickListener {
                onItemClick(service)
            }
        }
    }
}