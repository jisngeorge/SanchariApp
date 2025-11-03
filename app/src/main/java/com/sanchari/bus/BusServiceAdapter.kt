package com.sanchari.bus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemBusServiceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

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
            binding.serviceName.text = service.name
            // Set the icon based on the service type
            when (service.type.uppercase()) {
                "FAST" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_fast)
                    binding.serviceType.text = "Fast Passenger"
                }
                "SUPERFAST" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_superfast)
                    binding.serviceType.text = "Superfast"
                }
                else -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_placeholder)
                    binding.serviceType.text = "Ordinary"
                }
            }
            // You can add more fields here, e.g., service.isRunning

            // Set the click listener for the whole item
            binding.root.setOnClickListener {
                // Launch a coroutine to save this click to the UserDatabase
                // Use GlobalScope + Dispatchers.IO for a quick background task
                // This is safe because UserDataManager is a singleton object
                GlobalScope.launch(Dispatchers.IO) {
                    UserDataManager.addRecentView(
                        binding.root.context.applicationContext,
                        service.serviceId,
                        service.name
                    )
                }

                // Notify the activity to handle the click (e.g., open BusDetailsActivity)
                onItemClick(service)
            }
        }
    }
}

