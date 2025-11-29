package com.sanchari.bus.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.R
import com.sanchari.bus.databinding.ItemBusServiceBinding

class BusServiceAdapter(
    private val services: List<BusService>,
    private val onItemClick: (BusService) -> Unit
) : RecyclerView.Adapter<BusServiceAdapter.BusServiceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BusServiceViewHolder {
        val binding = ItemBusServiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BusServiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BusServiceViewHolder, position: Int) {
        val service = services[position]
        holder.bind(service)
    }

    override fun getItemCount() = services.size

    inner class BusServiceViewHolder(private val binding: ItemBusServiceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(service: BusService) {
            val context = binding.root.context

            binding.serviceName.text = service.name

            // --- NEW: Set the from and to times ---
            binding.fromTime.text = service.fromTime
            binding.toTime.text = service.toTime
            // --- End of new code ---

            // Set the icon based on the service type
            when (service.type.uppercase()) {
                "ORDINARY" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_ordinary)
                    binding.serviceType.text = context.getString(R.string.label_bus_ordinary)
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
                "DELUXE" -> {
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