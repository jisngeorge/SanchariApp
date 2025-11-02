package com.sanchari.bus

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemBusServiceBinding

class BusServiceAdapter(
    private val services: List<BusService>,
    private val onItemClick: (BusService) -> Unit
) : RecyclerView.Adapter<BusServiceAdapter.ServiceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val binding = ItemBusServiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ServiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        val service = services[position]
        holder.bind(service)
    }

    override fun getItemCount() = services.size

    inner class ServiceViewHolder(private val binding: ItemBusServiceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(service: BusService) {
            binding.serviceName.text = service.name
            binding.serviceType.text = service.type

            if (service.isRunning) {
                binding.runningStatus.text = "Running"
                binding.runningStatus.setTextColor(Color.parseColor("#FF008000")) // Dark Green
            } else {
                binding.runningStatus.text = "Not Running"
                binding.runningStatus.setTextColor(Color.parseColor("#FFD32F2F")) // Dark Red
            }

            // Set the icon based on service type (basic example)
            when (service.type.lowercase()) {
                "fast passenger" -> binding.serviceIcon.setImageResource(R.drawable.ic_bus_fast)
                "super fast" -> binding.serviceIcon.setImageResource(R.drawable.ic_bus_superfast)
                else -> binding.serviceIcon.setImageResource(R.drawable.ic_bus_placeholder)
            }

            // Set the click listener for the whole item
            binding.root.setOnClickListener {
                onItemClick(service)
            }
        }
    }
}
