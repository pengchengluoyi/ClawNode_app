package com.clawnode.agent

import android.app.Application

class ClawNodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ClawNodeApp
            private set
    }
}
