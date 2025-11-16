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

            // --- NEW: Set the from and to times ---
            binding.fromTime.text = service.fromTime
            binding.toTime.text = service.toTime
            // --- End of new code ---

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
                "LIMITED STOP" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_limited_stop)
                    binding.serviceType.text = "Limited Stop"
                }
                "ORDINARY" -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_ordinary)
                    binding.serviceType.text = "Ordinary"
                }
                else -> {
                    binding.serviceIcon.setImageResource(R.drawable.ic_bus_placeholder)
                    binding.serviceType.text = "Unspecified type"
                }
            }

            // Set the click listener for the whole item
            binding.root.setOnClickListener {
                // --- REMOVED: GlobalScope.launch ---
                // The database save is now handled in SearchResultsActivity
                // using lifecycleScope, which is a safer practice.

                // Notify the activity to handle the click
                onItemClick(service)
            }
        }
    }
}

