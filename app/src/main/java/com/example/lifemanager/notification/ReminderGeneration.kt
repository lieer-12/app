package com.example.lifemanager.notification

import android.content.Intent
import com.example.lifemanager.domain.maintenance.DataGeneration

/** Source identity, never a generation captured when a delayed Intent arrives. */
object ReminderGeneration {
    const val EXTRA = "reminder_data_generation"

    fun read(intent: Intent): DataGeneration? {
        // getLongExtra's default must not turn missing/wrong-typed metadata into a valid token.
        @Suppress("DEPRECATION")
        val value = intent.extras?.get(EXTRA) as? Long ?: return null
        return value.takeIf { it >= 0 }?.let(::DataGeneration)
    }
}
