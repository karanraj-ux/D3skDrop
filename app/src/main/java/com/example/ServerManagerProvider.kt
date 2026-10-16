package com.example

import android.content.Context

object ServerManagerProvider {
    @Volatile
    private var instance: ServerManager? = null

    fun getInstance(context: Context): ServerManager {
        return instance ?: synchronized(this) {
            instance ?: ServerManager(context.applicationContext).also { instance = it }
        }
    }
}
