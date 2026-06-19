package com.clawnode.agent

import android.app.Application
import com.clawnode.agent.core.ClawLog

class ClawNodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ClawLog.bp("ClawNodeApp", "onCreate", "process start pid=${android.os.Process.myPid()}")
    }

    companion object {
        lateinit var instance: ClawNodeApp
            private set
    }
}
