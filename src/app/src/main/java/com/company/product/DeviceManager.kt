package com.company.product

import android.content.Context
import android.os.Build
import kotlin.random.Random

class DeviceManager(context: Context) {
    private val prefs = context.getSharedPreferences("telegram_device", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                val suffix = (1..4).map { Random.nextInt(0, 36).toString(36).uppercase() }.joinToString("")
                id = "${Build.MODEL.replace(" ", "")}-$suffix"
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    var isActive: Boolean
        get() = prefs.getBoolean("is_active", false)
        set(value) = prefs.edit().putBoolean("is_active", value).apply()

    var ownerChatId: Long
        get() = prefs.getLong("owner_chat_id", 0L)
        set(value) = prefs.edit().putLong("owner_chat_id", value).apply()

    var lastUpdateId: Long
        get() = prefs.getLong("last_update_id", 0L)
        set(value) = prefs.edit().putLong("last_update_id", value).apply()

    companion object {
        private var instance: DeviceManager? = null

        fun getInstance(context: Context): DeviceManager {
            if (instance == null) {
                instance = DeviceManager(context.applicationContext)
            }
            return instance!!
        }
    }
}
