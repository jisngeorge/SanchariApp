package com.sanchari.bus.ui.adapter

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter
import com.sanchari.bus.ui.helper.BusSearchHandler.BusSearchItem

class BusSearchSuggestionAdapter(context: Context) :
    ArrayAdapter<BusSearchItem>(context, android.R.layout.simple_dropdown_item_1line) {
    @Volatile
    private var resultCount = 0

    // SQLite has already matched the query; ArrayAdapter's prefix filter would
    // hide substring matches and keep a stale copy of asynchronously loaded rows.
    private val resultFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply { count = resultCount }

        override fun publishResults(constraint: CharSequence?, results: FilterResults) {
            results.count = resultCount
        }
    }

    override fun getFilter(): Filter = resultFilter

    fun replaceResults(results: List<BusSearchItem>) {
        setNotifyOnChange(false)
        clear()
        addAll(results)
        resultCount = results.size
        notifyDataSetChanged()
    }
}
