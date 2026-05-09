package com.company.product

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UiLogger {

    interface Listener {
        fun onLogUpdated(logs: String)
    }

    private val listeners = mutableListOf<Listener>()
    private val sb = StringBuilder()

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onLogUpdated(sb.toString())
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] [$tag] $message\n"
        sb.append(line)
        val s = sb.toString()
        listeners.forEach { it.onLogUpdated(s) }
    }

    fun clear() {
        sb.clear()
        val s = sb.toString()
        listeners.forEach { it.onLogUpdated(s) }
    }
}
