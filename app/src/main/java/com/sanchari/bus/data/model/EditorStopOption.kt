package com.sanchari.bus.data.model

data class EditorStopOption(val id: Int, val name: String, val label: String = name) {
    override fun toString(): String = label
}
