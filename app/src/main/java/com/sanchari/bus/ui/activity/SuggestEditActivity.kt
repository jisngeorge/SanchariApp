package com.sanchari.bus.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanchari.bus.data.manager.SearchManager
import com.sanchari.bus.data.model.BusService
import com.sanchari.bus.data.model.EditableStop
import com.sanchari.bus.databinding.ActivitySuggestEditBinding
import com.sanchari.bus.ui.adapter.StopEditAdapter
import com.sanchari.bus.ui.helper.SuggestEditHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

class SuggestEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySuggestEditBinding
    private lateinit var adapter: StopEditAdapter
    private val editableStops = mutableListOf<EditableStop>()
    private var originalService: BusService? = null

    // Refactored Handler
    private lateinit var handler: SuggestEditHandler

    companion object {
        private const val TAG = "SuggestEditActivity"
        private const val EXTRA_BUS_SERVICE = "EXTRA_BUS_SERVICE"

        private const val KEY_SERVICE_NAME = "KEY_SERVICE_NAME"
        private const val KEY_SERVICE_TYPE = "KEY_SERVICE_TYPE"
        private const val KEY_STOPS = "KEY_STOPS"
        private const val KEY_EDIT_NOTES = "KEY_EDIT_NOTES"

        fun newIntentForEdit(context: Context, service: BusService): Intent {
            return Intent(context, SuggestEditActivity::class.java).apply {
                putExtra(EXTRA_BUS_SERVICE, service)
            }
        }

        fun newIntentForNew(context: Context): Intent {
            return Intent(context, SuggestEditActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySuggestEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()

        // Initialize Handler
        handler = SuggestEditHandler(this, binding, adapter, editableStops)

        if (savedInstanceState == null) {
            handler.showInstructionsDialog()
        }

        if (savedInstanceState != null) {
            Log.i(TAG, "Restoring state from savedInstanceState")
            restoreFromSavedState(savedInstanceState)
        } else {
            Log.i(TAG, "Loading from intent (new or edit)")
            loadFromIntent()
        }

        binding.buttonAddStop.setOnClickListener {
            addNewStop()
        }

        binding.buttonApplyChanges.setOnClickListener {
            // Delegate to handler
            handler.applyChanges(originalService)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "onSaveInstanceState: Saving user edits...")
        outState.putString(KEY_SERVICE_NAME, binding.editTextServiceName.text.toString())
        outState.putString(KEY_SERVICE_TYPE, binding.editTextServiceType.text.toString())
        outState.putParcelableArrayList(KEY_STOPS, ArrayList(adapter.getStopsData()))
        outState.putString(KEY_EDIT_NOTES, binding.editTextEditNotes.text.toString())
    }

    private fun restoreFromSavedState(savedInstanceState: Bundle) {
        val serviceName = savedInstanceState.getString(KEY_SERVICE_NAME)
        val serviceType = savedInstanceState.getString(KEY_SERVICE_TYPE)
        val editNotes = savedInstanceState.getString(KEY_EDIT_NOTES)
        val stops: ArrayList<EditableStop>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                savedInstanceState.getParcelableArrayList(KEY_STOPS, EditableStop::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelableArrayList(KEY_STOPS)
            }

        binding.editTextServiceName.setText(serviceName)
        binding.editTextServiceType.setText(serviceType)
        binding.editTextEditNotes.setText(editNotes)

        if (stops != null) {
            editableStops.clear()
            editableStops.addAll(stops)
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadFromIntent() {
        originalService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BUS_SERVICE, BusService::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BUS_SERVICE)
        }

        if (originalService != null) {
            binding.toolbar.title = "Suggest an Edit"
            preFillData(originalService!!)
        } else {
            binding.toolbar.title = "Add New Bus Service"
            addNewStop()
        }
    }

    private fun setupRecyclerView() {
        adapter = StopEditAdapter(
            editableStops,
            onRemoveClicked = { position ->
                editableStops.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.notifyItemRangeChanged(position, editableStops.size)
            },
            onTimeClicked = { position ->
                // Delegate to handler
                handler.showTimePicker(position)
            }
        )
        binding.recyclerViewStopsEditor.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewStopsEditor.adapter = adapter
    }

    private fun preFillData(service: BusService) {
        binding.editTextServiceName.setText(service.name)
        binding.editTextServiceType.setText(service.type)

        lifecycleScope.launch(Dispatchers.IO) {
            val stops = SearchManager.getBusStops(applicationContext, service.serviceId)
            withContext(Dispatchers.Main) {
                stops.sortedBy { it.stopOrder }.forEach {
                    editableStops.add(EditableStop.fromBusStop(it))
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun addNewStop() {
        // Pass -1 for new stops
        editableStops.add(EditableStop("", "", editableStops.size + 1, -1))
        adapter.notifyItemInserted(editableStops.size - 1)
        // Scroll to the new bottom item
        binding.recyclerViewStopsEditor.smoothScrollToPosition(editableStops.size - 1)
    }
}